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
    val steadyKm: List<Double> = List(BUCKETS) { 0.0 },   // то же, но только ровный ход (без разгонов)
    val steadyL: List<Double> = List(BUCKETS) { 0.0 },
    val tankStart: Double? = null,
    val tankEnd: Double? = null,
    val hasTrack: Boolean = false,
) {
    val l100: Double? get() = if (km >= 0.5) fuelL / km * 100 else null
    val avgKmh: Double get() = if (sec > 0) km / (sec / 3600.0) else 0.0
    val avgMovingKmh: Double get() = if (movingSec > 0) km / (movingSec / 3600.0) else 0.0
    val evShare: Double get() = if (km > 0) (evKm / km).coerceIn(0.0, 1.0) else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start); put("end", end); put("km", km); put("sec", sec); put("movingSec", movingSec)
        put("fuelL", fuelL); put("evKm", evKm); put("maxKmh", maxKmh); put("price", price); put("cost", cost)
        put("bucketKm", JSONArray(bucketKm)); put("bucketL", JSONArray(bucketL))
        put("steadyKm", JSONArray(steadyKm)); put("steadyL", JSONArray(steadyL))
        tankStart?.let { put("tankStart", it) }
        tankEnd?.let { put("tankEnd", it) }
        put("hasTrack", hasTrack)
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
            steadyKm = arr(o.optJSONArray("steadyKm")), steadyL = arr(o.optJSONArray("steadyL")),
            tankStart = if (o.has("tankStart")) o.optDouble("tankStart") else null,
            tankEnd = if (o.has("tankEnd")) o.optDouble("tankEnd") else null,
            hasTrack = o.optBoolean("hasTrack"),
        )

        fun bucketLabel(i: Int): String = if (i == BUCKETS - 1) "${i * 10}+" else "${i * 10}–${i * 10 + 10}"
    }
}

/** Точка записи поездки (раз в ~5 с): накопленные км и литры, скорость, расход, заряд, ОЖ, координаты. */
data class TripPoint(
    val t: Long,
    val km: Double,
    val fuel: Double,
    val speed: Double,
    val lph: Double,
    val soc: Double?,
    val coolant: Double?,
    val lat: Double?,
    val lon: Double?,
)

/** Подробная запись поездок — отдельными файлами, чтобы список поездок оставался лёгким. */
object Series {
    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "series").apply { mkdirs() }
    }

    private fun file(start: Long) = File(dir, "$start.json")

    fun save(start: Long, pts: List<TripPoint>) {
        try {
            val a = JSONArray()
            for (p in pts) {
                val r = JSONArray()
                r.put(p.t); r.put(p.km); r.put(p.fuel); r.put(p.speed); r.put(p.lph)
                r.put(p.soc ?: JSONObject.NULL); r.put(p.coolant ?: JSONObject.NULL)
                r.put(p.lat ?: JSONObject.NULL); r.put(p.lon ?: JSONObject.NULL)
                a.put(r)
            }
            file(start).writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    fun load(start: Long): List<TripPoint> = try {
        val f = file(start)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            List(a.length()) { i ->
                val r = a.getJSONArray(i)
                fun d(k: Int): Double? = if (r.isNull(k)) null else r.optDouble(k)
                TripPoint(r.optLong(0), r.optDouble(1), r.optDouble(2), r.optDouble(3), r.optDouble(4), d(5), d(6), d(7), d(8))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun delete(start: Long) {
        try {
            file(start).delete()
        } catch (_: Exception) {
        }
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
        Series.init(ctx)
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

    fun delete(t: Trip) {
        save(_items.value.filter { it.start != t.start })
        Series.delete(t.start)
        Riders.deleteTrip(t.start)
    }

    val totalKm: Double get() = _items.value.sumOf { it.km }

    fun totals(list: List<Trip>) = Totals(
        list.size, list.sumOf { it.km }, list.sumOf { it.fuelL }, list.sumOf { it.cost },
        list.sumOf { it.sec }, list.sumOf { it.evKm },
    )

    /** Пробег и литры за последние ~150 км — основа прогноза запаса хода. */
    fun recent(maxKm: Double = 150.0): Pair<Double, Double> {
        var km = 0.0
        var l = 0.0
        for (t in _items.value) {
            km += t.km; l += t.fuelL
            if (km >= maxKm) break
        }
        return km to l
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

    /** Расход на ровном ходу по скоростям (км, л) — из него ищем самую экономную скорость. */
    fun steadyBySpeed(): List<Pair<Double, Double>> {
        val km = DoubleArray(Trip.BUCKETS)
        val l = DoubleArray(Trip.BUCKETS)
        for (t in _items.value) for (i in 0 until Trip.BUCKETS) {
            km[i] += t.steadyKm.getOrElse(i) { 0.0 }
            l[i] += t.steadyL.getOrElse(i) { 0.0 }
        }
        return List(Trip.BUCKETS) { km[it] to l[it] }
    }

    /** Самая экономная скорость на ровном ходу (от 30 км/ч): номер диапазона и л/100. Нужно ≥ 3 км в диапазоне. */
    fun optimal(): Pair<Int, Double>? = steadyBySpeed().withIndex()
        .filter { it.index >= 3 && it.value.first >= 3.0 }
        .map { it.index to it.value.second / it.value.first * 100 }
        .minByOrNull { it.second }

    /** Расход на ровном ходу в диапазоне скорости, если накоплено. */
    fun steadyL100At(kmh: Double): Double? {
        val i = (kmh / 10).toInt().coerceIn(0, Trip.BUCKETS - 1)
        val (km, l) = steadyBySpeed()[i]
        return if (km >= 3) l / km * 100 else null
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

/**
 * Запас хода: литры в баке (с датчика) ÷ расход. Расход — твой за последние ~150 км вместе с текущей
 * поездкой, поэтому меняется плавно от стиля езды и сезона. Две цифры: до лампочки и до сухого бака.
 */
data class RangeInfo(val toLamp: Double, val toEmpty: Double, val l100: Double, val fewData: Boolean, val lampKnown: Boolean)

object Range {
    const val DEFAULT_L100 = 9.5
    const val DEFAULT_LAMP_L = 7.5

    /** Расход для прогноза: недавние поездки + текущая. */
    fun l100(tripKm: Double = 0.0, tripL: Double = 0.0): Pair<Double, Boolean> {
        val (km, l) = Trips.recent()
        val k = km + tripKm
        val f = l + tripL
        return if (k >= 30) (f / k * 100).coerceIn(3.0, 30.0) to (k < 100) else DEFAULT_L100 to true
    }

    fun of(levelL: Double?, tripKm: Double = 0.0, tripL: Double = 0.0, l100Override: Double? = null): RangeInfo? {
        val lvl = levelL ?: return null
        val (l100, few) = l100Override?.let { it to false } ?: l100(tripKm, tripL)
        val lamp = Fuel.tank.value.lampL
        val reserve = lamp ?: DEFAULT_LAMP_L
        return RangeInfo(
            toLamp = (lvl - reserve).coerceAtLeast(0.0) / l100 * 100,
            toEmpty = lvl / l100 * 100,
            l100 = l100, fewData = few, lampKnown = lamp != null,
        )
    }
}
