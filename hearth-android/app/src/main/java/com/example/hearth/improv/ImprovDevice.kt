package com.example.hearth.improv

/** A device discovered by [ImprovBleClient.scan]. */
data class ImprovDevice(
    val address: String,    // MAC (e.g. "1C:C3:AB:C1:9A:BA")
    val name: String,       // BLE local name (typically the ESPHome node name)
    val rssi: Int,          // signal strength in dBm; closer to 0 = stronger
)
