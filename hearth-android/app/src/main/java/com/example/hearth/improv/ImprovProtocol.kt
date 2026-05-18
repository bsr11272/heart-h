package com.example.hearth.improv

import java.util.UUID

/**
 * Improv-WiFi BLE protocol constants and helpers.
 * Spec: https://www.improv-wifi.com/ble/
 *
 * Service UUIDs use the format 00467768-xxxx-xxxx-xxxx-xxxxxxxxxxxx (where
 * the lower 16 bits 0x6228 etc. are the actual differentiators).
 */
object ImprovProtocol {

    val SERVICE_UUID: UUID         = UUID.fromString("00467768-6228-2272-4663-277478268000")
    val CHAR_CAPABILITIES: UUID    = UUID.fromString("00467768-6228-2272-4663-277478268005")
    val CHAR_CURRENT_STATE: UUID   = UUID.fromString("00467768-6228-2272-4663-277478268001")
    val CHAR_ERROR_STATE: UUID     = UUID.fromString("00467768-6228-2272-4663-277478268002")
    val CHAR_RPC_COMMAND: UUID     = UUID.fromString("00467768-6228-2272-4663-277478268003")
    val CHAR_RPC_RESULT: UUID      = UUID.fromString("00467768-6228-2272-4663-277478268004")

    /** Standard 16-bit BLE descriptor UUID for Client Characteristic Configuration */
    val CCCD_UUID: UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- State enum (CHAR_CURRENT_STATE, 1 byte) ----
    const val STATE_AUTHORIZATION_REQUIRED: Byte = 0x01
    const val STATE_AUTHORIZED: Byte             = 0x02   // ready to receive creds
    const val STATE_PROVISIONING: Byte           = 0x03   // applying creds
    const val STATE_PROVISIONED: Byte            = 0x04   // got IP, ready

    // ---- Error enum (CHAR_ERROR_STATE, 1 byte) ----
    const val ERROR_NONE: Byte                   = 0x00
    const val ERROR_INVALID_RPC: Byte            = 0x01
    const val ERROR_UNKNOWN_RPC: Byte            = 0x02
    const val ERROR_UNABLE_TO_CONNECT: Byte      = 0x03
    const val ERROR_NOT_AUTHORIZED: Byte         = 0x04
    const val ERROR_UNKNOWN: Byte                = 0xFF.toByte()

    // ---- RPC commands (written to CHAR_RPC_COMMAND) ----
    const val RPC_SEND_WIFI: Byte                = 0x01
    const val RPC_REQUEST_CURRENT_STATE: Byte    = 0x02
    const val RPC_REQUEST_DEVICE_INFO: Byte      = 0x03
    const val RPC_REQUEST_SCAN: Byte             = 0x04

    // ---- Capabilities flags (CHAR_CAPABILITIES, 1 byte bitfield) ----
    const val CAP_IDENTIFY: Byte                 = 0x01

    /**
     * Build the RPC payload for sending wifi credentials.
     *
     * Frame format:
     *   [0]    = command (RPC_SEND_WIFI)
     *   [1]    = data length
     *   [2..N] = data: [ssid_len][ssid_utf8][pw_len][pw_utf8]
     *   [N+1]  = checksum (sum of all preceding bytes mod 256)
     */
    fun buildSendWifiPayload(ssid: String, password: String): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val pwBytes   = password.toByteArray(Charsets.UTF_8)

        val data = ByteArray(1 + ssidBytes.size + 1 + pwBytes.size)
        var i = 0
        data[i++] = ssidBytes.size.toByte()
        ssidBytes.copyInto(data, i); i += ssidBytes.size
        data[i++] = pwBytes.size.toByte()
        pwBytes.copyInto(data, i)

        val frame = ByteArray(2 + data.size + 1)
        frame[0] = RPC_SEND_WIFI
        frame[1] = data.size.toByte()
        data.copyInto(frame, 2)

        // Checksum: sum of frame[0..frame.size-2] mod 256
        var sum = 0
        for (k in 0 until frame.size - 1) sum = (sum + (frame[k].toInt() and 0xFF)) and 0xFF
        frame[frame.size - 1] = sum.toByte()
        return frame
    }

    /** Parse the RPC result: returns the list of URLs the device exposes (typically its IP) or null on failure. */
    fun parseRpcResult(bytes: ByteArray): List<String>? {
        if (bytes.size < 4) return null
        // [0] = command echo, [1] = data length, [2..] = data + checksum
        val dataLen = (bytes[1].toInt() and 0xFF)
        if (bytes.size < 3 + dataLen) return null

        val urls = mutableListOf<String>()
        var off = 2
        val end = 2 + dataLen
        while (off < end) {
            if (off >= bytes.size) break
            val strLen = bytes[off].toInt() and 0xFF
            off++
            if (off + strLen > bytes.size) break
            urls += bytes.copyOfRange(off, off + strLen).toString(Charsets.UTF_8)
            off += strLen
        }
        return urls
    }
}
