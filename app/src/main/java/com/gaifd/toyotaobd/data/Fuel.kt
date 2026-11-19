package com.gaifd.toyotaobd.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Fill(val ts: Long, val liters: Double, val price: Double, val full: Boolean) {
    fun toJson() = JSONObject().apply { put("ts", ts); put("liters", liters); put("price", price); put("full", full) }

    companion object {
        fun fromJson(o: JSONObject) = Fill(o.optLong("ts"), o.optDouble("liters", 0.0), o.optDouble("price", 0.0), o.optBoolean("full"))
    }
}

data class Tank(
    val inTankL: Double? = null,          // сколько топлива в баке по нашему учёту
    val avgPrice: Double? = null,         // средняя цена топлива, которое сейчас в баке
    val countedSinceFull: Double = 0.0,   // насчитано литров после последней заправки до полного
    val hadFull: Boolean = false,
    val lastSensorL: Double? = null,      // уровень по датчику в конце прошлой поездки
    val pendingFillL: Double? = null,     // похоже, заправился — ждём цену
    val fills: List<Fill> = emptyList(),
)

/**
 * Учёт топлива в баке и его цены. Цена считается средневзвешенной: долил 20 л по 62 грн к 30 л по 58 —
 * в баке 50 л по 59,6. Поездка оплачивается по этой цене в момент её окончания.
 */
object Fuel {
    private lateinit var sp: SharedPreferences
    private val _tank = MutableStateFlow(Tank())
    val tank: StateFlow<Tank> = _tank
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("fuel", Context.MODE_PRIVATE)
        _tank.value = try {
            val o = JSONObject(sp.getString("tank", "{}") ?: "{}")
            val fa = o.optJSONArray("fills") ?: JSONArray()
            Tank(
                inTankL = if (o.has("inTankL")) o.optDouble("inTankL") else null,
                avgPrice = if (o.has("avgPrice")) o.optDouble("avgPrice") else null,
                countedSinceFull = o.optDouble("countedSinceFull", 0.0),
                hadFull = o.optBoolean("hadFull"),
                lastSensorL = if (o.has("lastSensorL")) o.optDouble("lastSensorL") else null,
                pendingFillL = if (o.has("pendingFillL")) o.optDouble("pendingFillL") else null,
                fills = List(fa.length()) { Fill.fromJson(fa.getJSONObject(it)) },
            )
        } catch (_: Exception) {
            Tank()
        }
        ready = true
    }

    @Synchronized
    private fun set(t: Tank) {
        _tank.value = t
        val o = JSONObject()
        t.inTankL?.let { o.put("inTankL", it) }
        t.avgPrice?.let { o.put("avgPrice", it) }
        o.put("countedSinceFull", t.countedSinceFull)
        o.put("hadFull", t.hadFull)
        t.lastSensorL?.let { o.put("lastSensorL", it) }
        t.pendingFillL?.let { o.put("pendingFillL", it) }
        val fa = JSONArray()
        t.fills.takeLast(300).forEach { fa.put(it.toJson()) }
        o.put("fills", fa)
        sp.edit().putString("tank", o.toString()).apply()
    }

    /** Цена топлива сейчас: средняя по баку или из настроек. */
    fun price(): Double = _tank.value.avgPrice ?: Prefs.settings.value.defaultPrice

    /** Поездка закончилась: списать топливо и запомнить уровень по датчику. */
    @Synchronized
    fun consume(liters: Double, sensorL: Double?) {
        val t = _tank.value
        set(
            t.copy(
                inTankL = t.inTankL?.let { (it - liters).coerceAtLeast(0.0) } ?: sensorL,
                countedSinceFull = t.countedSinceFull + liters,
                lastSensorL = sensorL ?: t.lastSensorL,
            )
        )
    }

    /** Датчик показал уровень в начале поездки. Если он заметно выше, чем в конце прошлой, — заправка. */
    @Synchronized
    fun checkFill(sensorL: Double): Double? {
        val t = _tank.value
        val before = t.lastSensorL
        if (before == null) {
            set(t.copy(lastSensorL = sensorL, inTankL = t.inTankL ?: sensorL))
            return null
        }
        val diff = sensorL - before
        return if (diff >= 4.0 && t.pendingFillL == null) {
            set(t.copy(pendingFillL = diff))
            diff
        } else null
    }

    fun dismissPending() {
        set(_tank.value.copy(pendingFillL = null))
    }

    /**
     * Записать заправку. До полного — ещё и калибровка расхода: сколько залил на самом деле
     * против того, сколько насчитало приложение с прошлого полного бака.
     */
    @Synchronized
    fun addFill(liters: Double, price: Double, full: Boolean, sensorAfter: Double?) {
        val t = _tank.value
        val s = Prefs.settings.value
        val before = t.inTankL ?: 0.0
        val oldPrice = t.avgPrice ?: price
        val newIn = when {
            full -> s.tankL
            sensorAfter != null -> sensorAfter
            else -> (before + liters).coerceAtMost(s.tankL)
        }
        val avg = if (before + liters > 0) (before * oldPrice + liters * price) / (before + liters) else price
        var counted = t.countedSinceFull
        if (full) {
            if (t.hadFull && counted >= 8.0) {
                val ratio = (liters / counted).coerceIn(0.7, 1.4)
                val k = (s.fuelK * ratio).coerceIn(0.5, 2.0)
                // Плавно: половина шага, чтобы одна неточная заправка не испортила всё.
                Prefs.update { it.copy(fuelK = it.fuelK * 0.5 + k * 0.5) }
            }
            counted = 0.0
        }
        set(
            t.copy(
                inTankL = newIn,
                avgPrice = avg,
                countedSinceFull = counted,
                hadFull = t.hadFull || full,
                pendingFillL = null,
                lastSensorL = sensorAfter ?: t.lastSensorL,
                fills = t.fills + Fill(System.currentTimeMillis(), liters, price, full),
            )
        )
    }

    fun lastPrice(): Double? = _tank.value.fills.lastOrNull()?.price
}
