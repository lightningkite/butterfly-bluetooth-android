package com.lightningkite.butterfly.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.ContextCompat
import com.lightningkite.butterfly.PlatformSpecific
import com.lightningkite.butterfly.android.ActivityAccess
import com.lightningkite.butterfly.net.HttpClient
import com.lightningkite.butterfly.views.startIntent
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Single
import io.reactivex.SingleEmitter
import java.util.*

val notificationDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
    advertisingIntensity: Float = .5f
): BleServer {
    val impl =
        BleServer(
            delegate = delegate,
            advertisingIntensity = advertisingIntensity
        )
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val server = manager.openGattServer(context, impl)
    impl.advertiser = manager.adapter.bluetoothLeAdvertiser
    impl.server = server
    return impl
}
