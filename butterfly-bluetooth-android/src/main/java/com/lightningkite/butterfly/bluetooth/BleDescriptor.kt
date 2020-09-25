//! This file will translate using Khrysalis.
package com.lightningkite.butterfly.bluetooth

import java.util.*

data class BleDescriptor(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val descriptorUuid: UUID
)
