@file:SharedCode

package com.lightningkite.butterfly.bluetooth

import com.lightningkite.butterfly.SharedCode
import com.lightningkite.butterfly.bytes.Data
import com.lightningkite.butterfly.observables.MutableObservableProperty
import com.lightningkite.butterfly.observables.observableNN
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.util.*
import com.lightningkite.butterfly.rx.AbstractDisposable

interface BleServerDelegate: Disposable {
    val profile: BleProfileDescription
    fun onConnect(from: BleDeviceInfo)
    fun onDisconnect(from: BleDeviceInfo)
    fun onSubscribe(from: BleDeviceInfo, service: UUID, characteristic: UUID): Observable<Data>
    fun onRead(from: BleDeviceInfo, service: UUID, characteristic: UUID): Single<Data>
    fun onWrite(from: BleDeviceInfo, service: UUID, characteristic: UUID, value: Data): Single<Unit>

    class PerCharacteristic(
        val services: Map<UUID, Service>
    ) : AbstractDisposable(), BleServerDelegate {
        constructor(vararg pairs: Pair<UUID, Service>):this(pairs.associate { it })
        override val profile: BleProfileDescription = BleProfileDescription(services.mapValues {
            BleServiceDescription(
                debugName = it.value.debugName,
                primary = it.value.primary,
                characteristics = it.value.delegates.mapValues {
                    BleCharacteristicDescription(
                        debugName = it.value.debugName,
                        properties = it.value.properties
                    )
                }
            )
        })

        class Service(
            val debugName: String,
            val primary: Boolean,
            val delegates: Map<UUID, Delegate>
        ) {
            constructor(
                debugName: String,
                primary: Boolean,
                vararg pairs: Pair<UUID, Delegate>
            ):this(debugName, primary, pairs.associate{ it })
        }

        interface Delegate: Disposable {
            val debugName: String
            val properties: BleCharacteristicProperties
            fun onConnect(from: BleDeviceInfo)
            fun onDisconnect(from: BleDeviceInfo)
            fun onSubscribe(from: BleDeviceInfo): Observable<Data>
            fun onRead(from: BleDeviceInfo): Single<Data>
            fun onWrite(from: BleDeviceInfo, value: Data): Single<Unit>
        }

        override fun onConnect(from: BleDeviceInfo) {
            services.asSequence().flatMap { it.value.delegates.asSequence() }.forEach { it.value.onConnect(from) }
        }

        override fun onDisconnect(from: BleDeviceInfo) {
            services.asSequence().flatMap { it.value.delegates.asSequence() }.forEach { it.value.onDisconnect(from) }
        }

        override fun onSubscribe(from: BleDeviceInfo, service: UUID, characteristic: UUID): Observable<Data> {
            return services[service]?.delegates?.get(characteristic)?.onSubscribe(from) ?: Observable.error(
                BleResponseException(
                    BleResponseStatus.attributeNotFound
                )
            )
        }

        override fun onRead(from: BleDeviceInfo, service: UUID, characteristic: UUID): Single<Data> {
            return services[service]?.delegates?.get(characteristic)?.onRead(from) ?: Single.error(
                BleResponseException(
                    BleResponseStatus.attributeNotFound
                )
            )
        }

        override fun onWrite(from: BleDeviceInfo, service: UUID, characteristic: UUID, value: Data): Single<Unit> {
            return services[service]?.delegates?.get(characteristic)?.onWrite(from, value) ?: Single.error(
                BleResponseException(
                    BleResponseStatus.attributeNotFound
                )
            )
        }

        override fun onDispose() {
            services.asSequence().flatMap { it.value.delegates.asSequence() }.forEach { it.value.dispose() }
        }

        class FromProperty(
            override val debugName: String,
            val property: MutableObservableProperty<Data>,
            override val properties: BleCharacteristicProperties = BleCharacteristicProperties(
                broadcast = true,
                read = true,
                write = true,
                writeWithoutResponse = true,
                notify = true,
                indicate = true
            )
        ) : AbstractDisposable(), Delegate {
            override fun onConnect(from: BleDeviceInfo) {}
            override fun onDisconnect(from: BleDeviceInfo) {}
            override fun onSubscribe(from: BleDeviceInfo): Observable<Data> {
                return property.observableNN
            }

            override fun onRead(from: BleDeviceInfo): Single<Data> {
                return Single.just(property.value)
            }

            override fun onWrite(from: BleDeviceInfo, value: Data): Single<Unit> {
                property.value = value
                return Single.just(Unit)
            }

            override fun onDispose() {}
        }
    }

}