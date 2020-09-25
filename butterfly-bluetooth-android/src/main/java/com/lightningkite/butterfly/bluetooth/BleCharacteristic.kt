//! This file is Khrysalis compatible.
package com.lightningkite.butterfly.bluetooth

import java.util.*

data class BleCharacteristic(
    val serviceUuid: UUID,
    val characteristicUuid: UUID
)
