package com.gaifd.toyotaobd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Поездка. Цена и стоимость записываются в момент окончания и больше не меняются —
 * новая цена топлива старую статистику не трогает.
 */
data class Trip(
    val start: Long,
    val end: Long,
    val km: Double,
    val sec: Long,
    val movingSec: Long,
    val fuelL: Double,
    val evKm: Double,
    val maxKmh: Double,
    val price: Double,
    val cost: Double,
    val bucketKm: List<Double>,   // пробег по скоростям: 0–10, 10–20 … 110+ км/ч
    val bucketL: List<Double>,    // топливо по тем же скоростям
) {
    val l100: Double? get() = if (km >= 0.5) fuelL / km * 100 else null
    val avgKmh: Double get() = if (sec > 0) km / (sec / 3600.0) else 0.0
    val avgMovingKmh: Double get() = if (movingSec > 0) km / (movingSec / 3600.0) else 0.0
    val evShare: Double get() = if (km > 0) (evKm / km).coerceIn(0.0, 1.0) else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start); put("end", end); put("km", km); put("sec", sec); put("movingSec", movingSec)
        put("fuelL", fuelL); put("evKm", evKm); put("maxKmh", maxKmh); put("price", price); put("cost", cost)
        put("bucketKm", JSONArray(bucketKm)); put("bucketL", JSONArray(bucketL))
    }

    companion object {
        const val BUCKETS = 12

        private fun arr(a: JSONArray?): List<Double> =
            if (a == null) List(BUCKETS) { 0.0 } else List(BUCKETS) { i -> a.optDouble(i, 0.0) }

        fun fromJson(o: JSONObject) = Trip(
            start = o.optLong("start"), end = o.optLong("end"), km = o.optDouble("km", 0.0),
            sec = o.optLong("sec"), movingSec = o.optLong("movingSec"), fuelL = o.optDouble("fuelL", 0.0),
            evKm = o.optDouble("evKm", 0.0), maxKmh = o.optDouble("maxKmh", 0.0),
            price = o.optDouble("price", 0.0), cost = o.optDouble("cost", 0.0),
            bucketKm = arr(o.optJSONArray("bucketKm")), bucketL = arr(o.optJSONArray("bucketL")),
        )

        fun bucketLabel(i: Int): String = if (i == BUCKETS - 1) "${i * 10}+" else "${i * 10}–${i * 10 + 10}"
    }
}

/** Сумма за период. */
data class Totals(val trips: Int, val km: Double, val fuelL: Double, val cost: Double, val sec: Long, val evKm: Double) {
    val l100: Double? get() = if (km >= 1) fuelL / km * 100 else null
    val avgKmh: Double get() = if (sec > 0) km / (sec / 3600.0) else 0.0
}

object Trips {
    private lateinit var file: File
    private val _items = MutableStateFlow<List<Trip>>(emptyList())
    val items: StateFlow<List<Trip>> = _items
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        file = File(ctx.applicationContext.filesDir, "trips.json")
        _items.value = try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                List(a.length()) { Trip.fromJson(a.getJSONObject(it)) }.sortedByDescending { it.start }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        ready = true
    }

    @Synchronized
    private fun save(list: List<Trip>) {
        _items.value = list
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        val tmp = File(file.parentFile, "trips.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    fun add(t: Trip) = save((listOf(t) + _items.value).sortedByDescending { it.start })

    fun delete(t: Trip) = save(_items.value.filter { it.start != t.start })

    val totalKm: Double get() = _items.value.sumOf { it.km }

    fun totals(list: List<Trip>) = Totals(
        list.size, list.sumOf { it.km }, list.sumOf { it.fuelL }, list.sumOf { it.cost },
        list.sumOf { it.sec }, list.sumOf { it.evKm },
    )

    fun since(ms: Long): List<Trip> = _items.value.filter { it.start >= ms }

    /** Средний расход по последним ~500 км — для запаса хода. */
    fun recentL100(): Double? {
        var km = 0.0
        var l = 0.0
        for (t in _items.value) {
            km += t.km; l += t.fuelL
            if (km >= 500) break
        }
        return if (km >= 20) l / km * 100 else null
    }

    /** Расход по скоростям за всё время. */
    fun bySpeed(): List<Pair<Double, Double>> {
        val km = DoubleArray(Trip.BUCKETS)
        val l = DoubleArray(Trip.BUCKETS)
        for (t in _items.value) for (i in 0 until Trip.BUCKETS) {
            km[i] += t.bucketKm.getOrElse(i) { 0.0 }
            l[i] += t.bucketL.getOrElse(i) { 0.0 }
        }
        return List(Trip.BUCKETS) { km[it] to l[it] }
    }

    fun csv(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("Начало;Конец;Км;Минут;Литров;л/100км;Ср. скорость;Макс. скорость;На электричестве км;Цена грн/л;Стоимость грн\n")
        for (t in _items.value.sortedBy { it.start }) {
            sb.append(f.format(Date(t.start))).append(';').append(f.format(Date(t.end))).append(';')
                .append("%.2f".format(Locale.US, t.km)).append(';').append(t.sec / 60).append(';')
                .append("%.2f".format(Locale.US, t.fuelL)).append(';')
                .append(t.l100?.let { "%.1f".format(Locale.US, it) } ?: "").append(';')
                .append("%.0f".format(Locale.US, t.avgKmh)).append(';').append("%.0f".format(Locale.US, t.maxKmh)).append(';')
                .append("%.2f".format(Locale.US, t.evKm)).append(';')
                .append("%.2f".format(Locale.US, t.price)).append(';').append("%.2f".format(Locale.US, t.cost)).append('\n')
        }
        return sb.toString()
    }
}
