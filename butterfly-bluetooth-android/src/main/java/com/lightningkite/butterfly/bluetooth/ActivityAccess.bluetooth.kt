package com.lightningkite.butterfly.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.lightningkite.butterfly.PlatformSpecific
import com.lightningkite.butterfly.net.HttpClient
import com.lightningkite.butterfly.views.startIntent
import com.lightningkite.rxkotlinproperty.viewgenerators.ActivityAccess
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Single
import io.reactivex.SingleEmitter
import java.util.*

private var activityAccessBle: RxBleClient? = null
val ActivityAccess.ble: Single<RxBleClient> get(){
    return Single.create { emitter: SingleEmitter<RxBleClient> ->
        activateBluetoothDialog(
            onPermissionRejected = {
                emitter.onError(IllegalStateException("User turned down your request to use Bluetooth."))
            },
            onBluetooth = {
                val current = activityAccessBle
                if(current != null){
                    emitter.onSuccess(current)
                } else {
                    val new = RxBleClient.create(HttpClient.appContext)
                    activityAccessBle = new
                    emitter.onSuccess(new)
                }
            }
        )
    }
}

@PlatformSpecific
private fun ActivityAccess.activateBluetoothDialog(
    onPermissionRejected: () -> Unit,
    onBluetooth: (BluetoothAdapter) -> Unit
) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter != null) {
        if (adapter.isEnabled) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                onBluetooth.invoke(adapter)
            } else {
                requestPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) {
                    if (it) {
                        onBluetooth.invoke(adapter)
                    } else {
                        onPermissionRejected.invoke()
                    }
                }
            }
        } else {
            startIntent(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) { _, _ ->
                activateBluetoothDialog(onPermissionRejected, onBluetooth)
            }
        }
    }
}

/**
 * @param serviceUuids If default, advertises all services described by [characteristics].
 */
fun ActivityAccess.bleServer(
    delegate: BleServerDelegate,
    advertisingIntensity: Float = .5f,
    advertiseName: Boolean = false,
    advertiseService: UUID? = null,
    advertiseTxPower: Boolean = false
): BleServer {
    val impl =
        BleServer(
            delegate = delegate,
            advertiseSettings = AdvertiseSettings.Builder()
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
            advertiseData = AdvertiseData.Builder()
                .apply {
                    this.setIncludeDeviceName(advertiseName)
                    this.setIncludeTxPowerLevel(advertiseTxPower)
                    if(advertiseService != null){
                        addServiceUuid(ParcelUuid(advertiseService))
                    }
                }
                .build()
        )
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val server = manager.openGattServer(context, impl)
    impl.advertiser = manager.adapter.bluetoothLeAdvertiser
    impl.server = server
    impl.ready()
    return impl
}
