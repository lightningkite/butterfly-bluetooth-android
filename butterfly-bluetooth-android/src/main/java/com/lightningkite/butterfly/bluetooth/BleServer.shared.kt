package com.lightningkite.butterfly.bluetooth

import com.lightningkite.butterfly.bytes.Data
import com.lightningkite.butterfly.observables.*
import io.reactivex.disposables.Disposable
import java.util.*

//My implementation

interface BleServer: Disposable {
    val characteristics: Map<UUID, Map<UUID, BleCharacteristicServer>>
    var advertising: Boolean
}

interface BleClient {
    val info: BleDeviceInfo
    val connected: Boolean
    fun respond(request: RequestId, data: Data, status: BleResponseStatus)
    /** Does not require a confirmation from the device. **/
    fun notify(service: UUID, characteristic: UUID, value: Data)
    /** Requires a confirmation from the device. **/
    fun indicate(service: UUID, characteristic: UUID, value: Data)
}
