@file:SuppressLint("MissingPermission")
@file:RequiresApi(Build.VERSION_CODES.LOLLIPOP)

package com.lightningkite.butterfly.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import com.lightningkite.butterfly.bytes.Data
import com.lightningkite.butterfly.rx.DisposeCondition
import com.lightningkite.butterfly.rx.forever
import com.lightningkite.butterfly.rx.until
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.subjects.PublishSubject
import java.util.*

class BleServer(
    val delegate: BleServerDelegate,
    val advertisingIntensity: Float = .5f
) : BluetoothGattServerCallback(), Disposable {

    var advertiserCallback: AdvertiseCallback? = null
    var advertiser: BluetoothLeAdvertiser? = null

    var server: BluetoothGattServer? = null
        set(value){
            field = value
            if(value != null){
                value.clearServices()
                servicesToAdd.clear()
                servicesToAdd.addAll(services.values)
                if(servicesToAdd.isNotEmpty())
                    value.addService(servicesToAdd.removeAt(0))
            }
        }
    private val servicesToAdd = ArrayList<BluetoothGattService>()

    val services = delegate.profile.services.mapValues {
        BluetoothGattService(it.key, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            for (item in it.value.characteristics) {
                addCharacteristic(BluetoothGattCharacteristic(
                    item.key,
                    run {
                        var total = 0
                        if (item.value.properties.broadcast)
                            total = total or BluetoothGattCharacteristic.PROPERTY_BROADCAST
                        if (item.value.properties.read)
                            total = total or BluetoothGattCharacteristic.PROPERTY_READ
                        if (item.value.properties.writeWithoutResponse)
                            total = total or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        if (item.value.properties.write)
                            total = total or BluetoothGattCharacteristic.PROPERTY_WRITE
                        if (item.value.properties.notify)
                            total = total or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        if (item.value.properties.indicate)
                            total = total or BluetoothGattCharacteristic.PROPERTY_INDICATE
                        if (item.value.properties.authenticatedSignedWrites)
                            total = total or BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
                        if (item.value.properties.extendedProperties)
                            total = total or BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
                        total
                    },
                    run {
                        var total = 0
                        if (item.value.properties.read)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.value.properties.writeWithoutResponse)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.value.properties.write)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.value.properties.notify)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.value.properties.indicate)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.value.properties.authenticatedSignedWrites)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.value.properties.notifyEncryptionRequired)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        if (item.value.properties.indicateEncryptionRequired)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        total
                    }
                ).apply {
                    if (item.value.properties.notify || item.value.properties.indicate) {
                        addDescriptor(
                            BluetoothGattDescriptor(
                                notificationDescriptorUuid,
                                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                            )
                        )
                    }
                })
            }
        }
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        super.onDescriptorReadRequest(device, requestId, offset, descriptor)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        Log.i("BleServerImpl", "onNotificationSent(device = ${device.address}, status = $status)")
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        Log.i("BleServerImpl", "onMtuChanged(device = ${device.address}, mtu = $mtu)")
    }

    override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
        Log.i("BleServerImpl", "onPhyUpdate(device = ${device.address}, txPhy = $txPhy, rxPhy = $rxPhy, status = $status)")
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: Data
    ) {
        Log.i("BleServerImpl", "onCharacteristicWriteRequest(device = ${device.address}, characteristic = ${characteristic.uuid}, value = ${value.joinToString { it.toString(16) }})")
        delegate.onWrite(BleDeviceInfo(device.address, device.name), characteristic.service.uuid, characteristic.uuid, value).subscribeBy(
            onError = {
                if(it is BleResponseException){
                    server?.sendResponse(device, requestId, it.value.value, 0, byteArrayOf())
                } else {
                    server?.sendResponse(device, requestId, BleResponseStatus.requestNotSupported.value, 0, byteArrayOf())
                }
            },
            onSuccess = {
                server?.sendResponse(device, requestId, BleResponseStatus.success.value, 0, byteArrayOf())
            }
        ).forever()
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        Log.i("BleServerImpl", "onCharacteristicReadRequest(device = ${device.address}, characteristic = ${characteristic.uuid})")
        delegate.onRead(BleDeviceInfo(device.address, device.name), characteristic.service.uuid, characteristic.uuid).subscribeBy(
            onError = {
                if(it is BleResponseException){
                    server?.sendResponse(device, requestId, it.value.value, 0, byteArrayOf())
                } else {
                    server?.sendResponse(device, requestId, BleResponseStatus.requestNotSupported.value, 0, byteArrayOf())
                }
            },
            onSuccess = {
                server?.sendResponse(device, requestId, BleResponseStatus.success.value, 0, it)
            }
        ).forever()
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Log.i("BleServerImpl", "onConnectionStateChange(device = ${device.address}, newState = ${newState})")
        when(newState){
            BluetoothProfile.STATE_CONNECTED -> {
                delegate.onConnect(BleDeviceInfo(device.address, device.name))
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                delegate.onDisconnect(BleDeviceInfo(device.address, device.name))
                unsubOrDisconnectListeners[device]?.onComplete()
                unsubOrDisconnectListeners.remove(device)
            }
        }
    }

    override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
        super.onPhyRead(device, txPhy, rxPhy, status)
    }

    val unsubOrDisconnectListeners = HashMap<BluetoothDevice, PublishSubject<BluetoothGattDescriptor>>()
    private fun deviceUnsubscribesOrDisconnects(device: BluetoothDevice, descriptor: BluetoothGattDescriptor): DisposeCondition {
        return DisposeCondition { disposable ->
            unsubOrDisconnectListeners.getOrPut(device) { PublishSubject.create() }
                .filter { it == descriptor }
                .take(1)
                .subscribeBy(
                    onComplete = {
                        disposable.dispose()
                    }
                )
                .forever()
        }
    }
    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: Data
    ) {
        Log.i("BleServerImpl", "onDescriptorWriteRequest(device = ${device.address}, descriptor = ${descriptor.characteristic.uuid}/${descriptor.uuid})")
        when(value[0]){
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[0] -> {
                delegate.onSubscribe(BleDeviceInfo(device.address, device.name), descriptor.characteristic.service.uuid, descriptor.characteristic.uuid).subscribeBy(
                    onError = {
                        Log.e("BleServerImpl", "Got an error while handling indication:")
                        it.printStackTrace()
                        //TODO: Not sure what to do here?  There's no way to inform the device it failed.
                    },
                    onNext = { updateValue ->
                        server?.notifyCharacteristicChanged(
                            device,
                            descriptor.characteristic.apply { this.value = updateValue },
                            true
                        )
                    },
                    onComplete = {
                        Log.v("BleServerImpl", "Completed indication updates.")
                    }
                ).until(deviceUnsubscribesOrDisconnects(device, descriptor))
            }
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0] -> {
                delegate.onSubscribe(BleDeviceInfo(device.address, device.name), descriptor.characteristic.service.uuid, descriptor.characteristic.uuid).subscribeBy(
                    onError = {
                        Log.e("BleServerImpl", "Got an error while handling notification:")
                        it.printStackTrace()
                        //TODO: Not sure what to do here?  There's no way to inform the device it failed.
                    },
                    onNext = { updateValue ->
                        server?.notifyCharacteristicChanged(
                            device,
                            descriptor.characteristic.apply { this.value = updateValue },
                            false
                        )
                    },
                    onComplete = {
                        Log.v("BleServerImpl", "Completed notification updates.")
                    }
                ).until(deviceUnsubscribesOrDisconnects(device, descriptor))
            }
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE[0] -> {
                unsubOrDisconnectListeners[device]?.onNext(descriptor)
            }
        }
        server?.sendResponse(device, requestId, BleResponseStatus.success.value, 0, null)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        Log.i("BleServerImpl", "Service ${service.uuid} added")
        if(servicesToAdd.isNotEmpty())
            server?.addService(servicesToAdd.removeAt(0))
    }

    var advertising: Boolean = false
        set(value) {
            if(value && !field) startAdvertising()
            else if(!value) stopAdvertising()

            field = value
        }
    private fun startAdvertising() {
        val advertiserCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i("BleServerImpl", "Advertising successfully!")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BleServerImpl", "Failed to begin advertising.  Code: $errorCode")
            }
        }
        advertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setTimeout(0)
                .setConnectable(true)
                .setAdvertiseMode(
                    when (advertisingIntensity) {
                        in 0f..0.33f -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                        in 0.33f..0.66f -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                        in 0.66f..1f -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                        else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                    }
                )
                .build(),
            AdvertiseData.Builder()
                .apply {
                    this.setIncludeDeviceName(true)
//                    this.setIncludeTxPowerLevel(true)
//                    delegate.profile.services.filter { it.value.primary }.keys.forEach {
//                        addServiceUuid(ParcelUuid(it))
//                    }
                }
                .build(),
            advertiserCallback
        )
    }
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiserCallback)
        advertiserCallback = null
    }

    override fun isDisposed(): Boolean {
        return server == null
    }

    override fun dispose() {
        Log.i("BleServerImpl", "Closing...")
        if(advertiserCallback != null) {
            advertiser?.stopAdvertising(advertiserCallback)
        }
        advertiserCallback = null
        server?.close()
        server = null
    }

}
