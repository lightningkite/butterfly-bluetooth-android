package com.lightningkite.butterfly.bluetooth

import com.lightningkite.butterfly.bytes.Data
import com.lightningkite.rxkotlinproperty.forever
import com.lightningkite.rxkotlinproperty.viewgenerators.Log
import com.polidea.rxandroidble2.RxBleConnection
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.subjects.PublishSubject
import java.util.*
import kotlin.collections.HashMap

class BleRequestServer(
    val service: UUID = UUID.fromString("43a4be8f-507d-45d1-96fe-63d343da1cfb"),
    val handle: (ByteArray) -> Single<ByteArray>
) : BleServerDelegate {

    companion object {
        val requestCharacteristic = UUID.fromString("927130da-ad5a-414e-a966-69296c136a75")
        val responseCharacteristic = UUID.fromString("daf1ea4e-3610-43fa-a2bc-cdf31ac4e236")
    }

    override val profile: BleProfileDescription = BleProfileDescription(
        mapOf(
            service to BleServiceDescription(
                debugName = "FauxStream",
                primary = true,
                characteristics = mapOf(
                    requestCharacteristic to BleCharacteristicDescription(
                        debugName = "request",
                        properties = BleCharacteristicProperties(writeWithoutResponse = true, write = true)
                    ),
                    responseCharacteristic to BleCharacteristicDescription(
                        debugName = "response",
                        properties = BleCharacteristicProperties(indicate = true, notify = true, read = true)
                    )
                )
            )
        )
    )

    override fun onConnect(from: BleDeviceInfo) {
    }

    override fun onDisconnect(from: BleDeviceInfo) {
        deviceSubjects.remove(from.id)?.onComplete()
    }

    val deviceSubjects = HashMap<String, PublishSubject<Data>>()

    override fun onSubscribe(from: BleDeviceInfo, service: UUID, characteristic: UUID): Observable<Data> {
        return deviceSubjects.getOrPut(from.id) { PublishSubject.create() }
    }

    override fun onRead(from: BleDeviceInfo, service: UUID, characteristic: UUID): Single<Data> {
        return Single.error(BleResponseException(BleResponseStatus.requestNotSupported))
    }

    override fun onWrite(from: BleDeviceInfo, service: UUID, characteristic: UUID, value: Data): Single<Unit> {
        val s = deviceSubjects.getOrPut(from.id) { PublishSubject.create() }
        val requestNum = value[0]
        val requestData = value.sliceArray(1 until value.size)
        handle(requestData).subscribeBy(
            onError = { Log.w("RequestServer", "Errored on handler; no response will be sent back to device.") },
            onSuccess = { s.onNext(byteArrayOf(requestNum) + it) }
        ).forever()
        return Single.just(Unit)
    }

    private var disposed = false
    override fun isDisposed(): Boolean = disposed
    override fun dispose() {
        for(value in deviceSubjects.values){
            value.onComplete()
        }
        deviceSubjects.clear()
        disposed = true
    }
}

private class BleRequestClient private constructor(
    val connection: RxBleConnection,
    val service: UUID = UUID.fromString("43a4be8f-507d-45d1-96fe-63d343da1cfb")
) {
    companion object {
        val allConnections: HashMap<RxBleConnection, BleRequestClient> = HashMap()
        fun get(
            connection: RxBleConnection,
            service: UUID = UUID.fromString("43a4be8f-507d-45d1-96fe-63d343da1cfb")
        ): BleRequestClient {
            return allConnections.getOrPut(connection) {
                BleRequestClient(connection, service)
            }
        }
    }

    var number: Byte = 0
    val responses = connection.setupIndication(BleRequestServer.responseCharacteristic).flatMap { it }.share()
    init {
        responses.subscribeBy(
            onError = {},
            onComplete = {
                allConnections.remove(connection)
            }
        ).forever()
    }

    fun request(data: ByteArray): Single<ByteArray> {
        val reqNumber = number++
        return connection.writeCharacteristic(BleRequestServer.requestCharacteristic, byteArrayOf(reqNumber) + data).toObservable()
            .switchMap { responses.filter { it[0] == reqNumber } }
            .take(1)
            .map { it.sliceArray(1 until it.size) }
            .singleOrError()
    }
}
fun RxBleConnection.request(
    data: ByteArray,
    service: UUID = UUID.fromString("43a4be8f-507d-45d1-96fe-63d343da1cfb")
): Single<ByteArray> {
    return BleRequestClient.get(this, service).request(data)
}