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
import com.lightningkite.butterfly.observables.StandardObservableProperty
import io.reactivex.subjects.PublishSubject
import java.util.*
import kotlin.collections.HashSet

class BleServerImpl(
    override val characteristics: Map<UUID, Map<UUID, BleCharacteristicServer>>,
    val serviceUuids: List<UUID>? = null,
    val advertisingIntensity: Float = .5f
) :
    BluetoothGattServerCallback(), BleServer {

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

    val clients: StandardObservableProperty<Map<String, BleClient>> = StandardObservableProperty(mapOf(), PublishSubject.create()/*TODO main thread only*/)

    private val subscriptionTracking = HashMap<BleClient, HashSet<BleCharacteristicServer>>()

    fun clientFor(device: BluetoothDevice): BleClient {
        clients.value[device.address]?.let { return it }
        val new = BleClientImpl(this, device)
        new.connected = true
        clients.value = clients.value + (device.address to new)
        return new
    }

    fun removeClientFor(device: BluetoothDevice) {
        clients.value = clients.value.toMutableMap().apply { (remove(device.address) as? BleClientImpl)?.connected = false }
    }

    val services = characteristics.mapValues {
        BluetoothGattService(it.key, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            for (item in it.value.values) {
                addCharacteristic(BluetoothGattCharacteristic(
                    item.characteristic.characteristicUuid,
                    run {
                        var total = 0
                        if (item.properties.broadcast)
                            total = total or BluetoothGattCharacteristic.PROPERTY_BROADCAST
                        if (item.properties.read)
                            total = total or BluetoothGattCharacteristic.PROPERTY_READ
                        if (item.properties.writeWithoutResponse)
                            total = total or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        if (item.properties.write)
                            total = total or BluetoothGattCharacteristic.PROPERTY_WRITE
                        if (item.properties.notify)
                            total = total or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        if (item.properties.indicate)
                            total = total or BluetoothGattCharacteristic.PROPERTY_INDICATE
                        if (item.properties.authenticatedSignedWrites)
                            total = total or BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
                        if (item.properties.extendedProperties)
                            total = total or BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
                        total
                    },
                    run {
                        var total = 0
                        if (item.properties.read)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.properties.writeWithoutResponse)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.properties.write)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.properties.notify)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.properties.indicate)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ
                        if (item.properties.authenticatedSignedWrites)
                            total = total or BluetoothGattCharacteristic.PERMISSION_WRITE
                        if (item.properties.notifyEncryptionRequired)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        if (item.properties.indicateEncryptionRequired)
                            total = total or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        total
                    }
                ).apply {
                    if (item.properties.notify || item.properties.indicate) {
                        addDescriptor(
                            BluetoothGattDescriptor(
                                Ble.notificationDescriptorUuid,
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
        val service = characteristics[characteristic.service.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Service ${characteristic.service.uuid} not found")
            return
        }
        val characteristicServer = service[characteristic.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Characteristic ${characteristic.uuid} not found")
            return
        }
        characteristicServer.onWrite(clientFor(device), requestId, value)
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        Log.i("BleServerImpl", "onCharacteristicReadRequest(device = ${device.address}, characteristic = ${characteristic.uuid})")
        val service = characteristics[characteristic.service.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Service ${characteristic.service.uuid} not found")
            return
        }
        val characteristicServer = service[characteristic.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Characteristic ${characteristic.uuid} not found")
            return
        }
        characteristicServer.onRead(clientFor(device), requestId)
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Log.i("BleServerImpl", "onConnectionStateChange(device = ${device.address}, newState = ${newState})")
        when(newState){
            BluetoothProfile.STATE_CONNECTED -> clientFor(device)
            BluetoothProfile.STATE_DISCONNECTED -> {
                val client = clientFor(device)
                subscriptionTracking[client]?.forEach { it.onDisconnect(client) }
                removeClientFor(device)
            }
        }
    }

    override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
        super.onPhyRead(device, txPhy, rxPhy, status)
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
        val service = characteristics[descriptor.characteristic.service.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Service ${descriptor.characteristic.service.uuid} not found")
            return
        }
        val characteristicServer = service[descriptor.characteristic.uuid] ?: run {
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            Log.e("BleServerImpl", "Characteristic ${descriptor.characteristic.uuid} not found")
            return
        }
        if(descriptor.uuid != Ble.notificationDescriptorUuid){
            server?.sendResponse(device, requestId, BleResponseStatus.attributeNotFound.value, 0, byteArrayOf())
            return
        }
        when(value.joinToString()){
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.joinToString() -> {
                val client = clientFor(device)
                subscriptionTracking.getOrPut(client){HashSet()}.add(characteristicServer)
                characteristicServer.onSubscribe(client)
            }
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.joinToString() -> {
                val client = clientFor(device)
                subscriptionTracking.getOrPut(client){HashSet()}.add(characteristicServer)
                characteristicServer.onSubscribe(client)
            }
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.joinToString() -> {
                val client = clientFor(device)
                subscriptionTracking.getOrPut(client){HashSet()}.remove(characteristicServer)
                characteristicServer.onUnsubscribe(client)
            }
        }
        server?.sendResponse(device, requestId, BleResponseStatus.success.value, 0, null)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        Log.i("BleServerImpl", "Service ${service.uuid} added")
        if(servicesToAdd.isNotEmpty())
            server?.addService(servicesToAdd.removeAt(0))
    }

    override var advertising: Boolean = false
        set(value) {
            if(value && !field) startAdvertising()
            else if(!value) stopAdvertising()

            field = value
        }
    private fun startAdvertising() {
        val advertiserCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                println("Advertising successfully!")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("Ble.Actual", "Failed to begin advertising.  Code: $errorCode")
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
                    serviceUuids?.forEach {
                        addServiceUuid(ParcelUuid(it))
                    } ?: run {
                        characteristics.keys.forEach {
                            addServiceUuid(ParcelUuid(it))
                        }
                    }
                    //TODO: Service/manufacturing data?
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

class BleClientImpl(val server: BleServerImpl, val device: BluetoothDevice) : BleClient {
    override val info: BleDeviceInfo = BleDeviceInfo(device.address ?: "", device.name ?: "")
    override var connected: Boolean = false
    override fun respond(request: RequestId, data: Data, status: BleResponseStatus) {
        server.server?.sendResponse(device, request, status.value, 0, data)
    }

    override fun notify(service: UUID, characteristic: UUID, value: Data) {
        server.server?.notifyCharacteristicChanged(
            device,
            server.services[service]!!.getCharacteristic(characteristic).apply { this.value = value },
            false
        )
    }

    override fun indicate(service: UUID, characteristic: UUID, value: Data) {
        server.server?.notifyCharacteristicChanged(
            device,
            server.services[service]!!.getCharacteristic(characteristic).apply { this.value = value },
            true
        )
    }

}