package com.example.hearth.improv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Improv-WiFi BLE client: scan for advertising devices, connect, push wifi
 * credentials, observe the device's state machine.
 *
 * The caller is responsible for obtaining runtime BLE permissions:
 *  - Android 12+ (API 31): BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 *  - Android <12:          ACCESS_FINE_LOCATION (BLE scan requires it)
 *
 * All Android BLE callbacks fire on the system's binder thread; we marshal
 * them onto a coroutine via [callbackFlow] and suspend functions.
 */
@SuppressLint("MissingPermission")  // caller guarantees permissions via PermissionsHelper
class ImprovBleClient(private val context: Context) {

    private val manager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private val TAG = "ImprovBleClient"

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    // ============================== SCAN ==============================

    /**
     * Continuous BLE scan filtered to devices advertising the Improv service.
     * Emits each discovery; consumers should dedupe by MAC if needed.
     */
    fun scan(): Flow<ImprovDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { close(IllegalStateException("BLE scanner unavailable")); return@callbackFlow }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ImprovProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = ImprovDevice(
                    address = result.device.address,
                    name = result.device.name ?: result.scanRecord?.deviceName ?: "(unknown)",
                    rssi = result.rssi,
                )
                trySend(dev)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                close(RuntimeException("BLE scan failed with code $errorCode"))
            }
        }
        scanner.startScan(listOf(filter), settings, cb)
        awaitClose { scanner.stopScan(cb) }
    }

    // ============================ PROVISION ============================

    /**
     * Connect to [device], wait until state is AUTHORIZED, write SSID + password,
     * wait for PROVISIONED (or surface any error). Returns the list of URLs the
     * device reports — usually a single IP-based URL like http://192.168.4.42 .
     *
     * This single suspend function encapsulates the entire BLE conversation;
     * the caller wraps it in viewModelScope.launch.
     */
    suspend fun provision(
        device: ImprovDevice,
        ssid: String,
        password: String,
        progress: (ProvisionProgress) -> Unit = {},
    ): List<String> = suspendCancellableCoroutine { cont ->
        val btDev = adapter?.getRemoteDevice(device.address)
            ?: return@suspendCancellableCoroutine cont.resumeWithException(
                IllegalStateException("Cannot resolve device ${device.address}"))

        var gatt: BluetoothGatt? = null
        var rpcCmd: BluetoothGattCharacteristic? = null
        var rpcRes: BluetoothGattCharacteristic? = null
        var state: BluetoothGattCharacteristic? = null
        var error: BluetoothGattCharacteristic? = null
        var subscribeStep = 0   // we enable notifications on 3 chars sequentially

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    progress(ProvisionProgress.Connected)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    if (!cont.isCompleted) {
                        cont.resumeWithException(RuntimeException(
                            "BLE disconnected before provisioning completed (status=$status)"))
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    cont.resumeWithException(RuntimeException("Service discovery failed: $status"))
                    return
                }
                val svc = g.getService(ImprovProtocol.SERVICE_UUID)
                    ?: return cont.resumeWithException(RuntimeException("Improv service not present"))

                rpcCmd = svc.getCharacteristic(ImprovProtocol.CHAR_RPC_COMMAND)
                rpcRes = svc.getCharacteristic(ImprovProtocol.CHAR_RPC_RESULT)
                state  = svc.getCharacteristic(ImprovProtocol.CHAR_CURRENT_STATE)
                error  = svc.getCharacteristic(ImprovProtocol.CHAR_ERROR_STATE)
                if (rpcCmd == null || rpcRes == null || state == null || error == null) {
                    cont.resumeWithException(RuntimeException("Required Improv characteristics missing"))
                    return
                }
                progress(ProvisionProgress.Discovered)
                // Subscribe to state notifications first
                enableNotify(g, state!!)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                subscribeStep++
                when (subscribeStep) {
                    1 -> enableNotify(g, error!!)
                    2 -> enableNotify(g, rpcRes!!)
                    3 -> {
                        // All subscriptions live. Read initial state.
                        g.readCharacteristic(state!!)
                    }
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (c.uuid == ImprovProtocol.CHAR_CURRENT_STATE) handleStateBytes(g, c.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
            ) {
                when (c.uuid) {
                    ImprovProtocol.CHAR_CURRENT_STATE -> handleStateBytes(g, c.value)
                    ImprovProtocol.CHAR_ERROR_STATE   -> {
                        val e = c.value.firstOrNull() ?: ImprovProtocol.ERROR_NONE
                        if (e != ImprovProtocol.ERROR_NONE) {
                            cont.resumeWithException(RuntimeException("Improv error code: $e"))
                            g.disconnect()
                        }
                    }
                    ImprovProtocol.CHAR_RPC_RESULT -> {
                        val urls = ImprovProtocol.parseRpcResult(c.value) ?: emptyList()
                        progress(ProvisionProgress.Provisioned(urls))
                        if (!cont.isCompleted) cont.resume(urls)
                        g.disconnect()
                    }
                }
            }

            private fun handleStateBytes(g: BluetoothGatt, bytes: ByteArray?) {
                val s = bytes?.firstOrNull() ?: return
                progress(ProvisionProgress.State(s))
                when (s) {
                    ImprovProtocol.STATE_AUTHORIZED -> sendWifi(g)
                    ImprovProtocol.STATE_PROVISIONING -> { /* wait for next state */ }
                    ImprovProtocol.STATE_PROVISIONED -> { /* wait for RPC result with URL */ }
                    ImprovProtocol.STATE_AUTHORIZATION_REQUIRED -> {
                        cont.resumeWithException(RuntimeException(
                            "Device requires physical authorization (button press)"))
                        g.disconnect()
                    }
                }
            }

            private fun sendWifi(g: BluetoothGatt) {
                val payload = ImprovProtocol.buildSendWifiPayload(ssid, password)
                rpcCmd!!.value = payload
                rpcCmd!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!g.writeCharacteristic(rpcCmd!!)) {
                    cont.resumeWithException(RuntimeException("Failed to write RPC_SEND_WIFI"))
                }
                progress(ProvisionProgress.SentCredentials)
            }
        }

        gatt = btDev.connectGatt(context, false, cb)
        progress(ProvisionProgress.Connecting)
        cont.invokeOnCancellation { gatt?.disconnect(); gatt?.close() }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val desc = c.getDescriptor(ImprovProtocol.CCCD_UUID) ?: return
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(desc)
    }

    companion object {
        /** Permissions that must be granted at runtime for [scan] and [provision]. */
        val REQUIRED_PERMISSIONS: Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

/** Sealed progress events emitted during a [ImprovBleClient.provision] call. */
sealed interface ProvisionProgress {
    data object Connecting : ProvisionProgress
    data object Connected : ProvisionProgress
    data object Discovered : ProvisionProgress
    data class State(val raw: Byte) : ProvisionProgress
    data object SentCredentials : ProvisionProgress
    data class Provisioned(val urls: List<String>) : ProvisionProgress
}
