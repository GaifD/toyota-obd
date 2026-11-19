package com.gaifd.toyotaobd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gaifd.toyotaobd.R
import com.gaifd.toyotaobd.ui.MainActivity

object Notify {
    const val SERVICE_ID = 1
    private const val CH_SERVICE = "service"
    private const val CH_ALERT = "alerts"
    private const val CH_INFO = "info"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Связь с машиной", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Предупреждения", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Поездки и заправки", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context, tab: Int, req: Int, fill: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .putExtra(MainActivity.EXTRA_FILL, fill),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 9,
            Intent(ctx, ObdService::class.java).setAction(ObdService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Бортовик")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, 0, 1))
            .addAction(0, "Отключить", stop)
            .build()
    }

    fun updateService(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(SERVICE_ID, service(ctx, text))
        } catch (_: SecurityException) {
        }
    }

    fun alert(ctx: Context, id: Int, title: String, text: String, tab: Int = 2) {
        post(ctx, id, NotificationCompat.Builder(ctx, CH_ALERT), title, text, open(ctx, tab, 100 + id))
    }

    fun info(ctx: Context, id: Int, title: String, text: String, tab: Int = 1, fill: Boolean = false) {
        post(ctx, id, NotificationCompat.Builder(ctx, CH_INFO), title, text, open(ctx, tab, 200 + id, fill))
    }

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder, title: String, text: String, pi: PendingIntent) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    const val ID_COOLANT = 10
    const val ID_DTC = 11
    const val ID_VOLT = 12
    const val ID_FUEL = 13
    const val ID_FILL = 20
    const val ID_TRIP = 21
    const val ID_START = 22
    const val ID_SOC = 14
    const val ID_MAINT = 23
}
