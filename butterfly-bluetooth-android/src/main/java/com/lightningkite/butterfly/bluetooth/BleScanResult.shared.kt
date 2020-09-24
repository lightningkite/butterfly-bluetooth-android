package com.lightningkite.butterfly.bluetooth


data class BleScanResult(
    val info: BleDeviceInfo,
    val rssi: Int
)
