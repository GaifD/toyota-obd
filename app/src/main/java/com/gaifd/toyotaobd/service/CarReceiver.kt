package com.gaifd.toyotaobd.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gaifd.toyotaobd.data.Prefs

/**
 * Android будит приложение, когда телефон соединяется по Bluetooth с машиной (магнитолой) —
 * значит, сели в машину: подключаемся к адаптеру. Отключились от машины — заканчиваем поездку.
 */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        Prefs.init(context)
        val s = Prefs.settings.value
        val car = s.carMac ?: return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (dev?.address?.equals(car, ignoreCase = true) != true) return
        val app = context.applicationContext
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            if (s.autoStart && s.elmMac != null && !ObdService.running) ObdService.start(app, fromCar = true)
        } else if (ObdService.running) {
            ObdService.carGone(app)
        }
    }
}
