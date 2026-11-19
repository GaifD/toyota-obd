package com.gaifd.toyotaobd.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gaifd.toyotaobd.data.Prefs

/**
 * GPS для трека на карте — только если включено в настройках. При глушилках точки «прыгают»,
 * поэтому каждую сверяем со скоростью из машины: «телепорт» быстрее, чем ехала машина, выбрасываем.
 */
object Gps {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    @Volatile
    private var lastFix: Location? = null

    @Volatile
    private var lastFixAt = 0L

    private var accepted: Location? = null
    private var acceptedAt = 0L

    fun permitted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (listener != null || !Prefs.settings.value.gpsTrack || !permitted(ctx)) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastFix = loc
                lastFixAt = System.currentTimeMillis()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
            lm = m
            listener = l
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            listener?.let { lm?.removeUpdates(it) }
        } catch (_: Exception) {
        }
        listener = null
        lm = null
        lastFix = null
        accepted = null
    }

    /** Свежая правдоподобная точка или null (нет GPS / глушат / прыжок). */
    fun point(obdKmh: Double): Pair<Double, Double>? {
        val f = lastFix ?: return null
        val now = System.currentTimeMillis()
        if (now - lastFixAt > 8000) return null
        if (f.hasAccuracy() && f.accuracy > 50) return null
        val prev = accepted
        if (prev != null) {
            val dt = (now - acceptedAt) / 1000.0
            if (dt in 0.5..60.0) {
                val kmh = prev.distanceTo(f) / dt * 3.6
                if (kmh > obdKmh * 1.5 + 30) return null
            }
        }
        accepted = f
        acceptedAt = now
        return f.latitude to f.longitude
    }
}
