@file:SharedCode
package com.lightningkite.butterfly.bluetooth

import com.lightningkite.butterfly.SharedCode
import com.lightningkite.butterfly.bytes.Data
import java.util.*

data class BleProfileDescription(
    val services: Map<UUID, BleServiceDescription>
)
data class BleServiceDescription(
    val debugName: String,
    val primary: Boolean,
    val characteristics: Map<UUID, BleCharacteristicDescription>
)
data class BleCharacteristicDescription(
    val debugName: String,
    val properties: BleCharacteristicProperties
)

data class BleDeviceInfo(
    val id: String,
    val name: String?
)

data class BleScanResult(
    val info: BleDeviceInfo,
    val rssi: Int
)

data class BleCharacteristicProperties(
    val broadcast: Boolean = false,
    val read: Boolean = false,
    val writeWithoutResponse: Boolean = false,
    val write: Boolean = false,
    val notify: Boolean = false,
    val indicate: Boolean = false,
    val authenticatedSignedWrites: Boolean = false,
    val extendedProperties: Boolean = false,
    val notifyEncryptionRequired: Boolean = false,
    val indicateEncryptionRequired: Boolean = false,
    val writeEncryptionRequired: Boolean = false
)

class BleResponseException(val value: BleResponseStatus): Exception(value.name)