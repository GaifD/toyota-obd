package com.gaifd.toyotaobd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Попутчик на участке поездки. km/l — накопленные пробег и литры поездки в моменты посадки и выхода,
 * так что считается реальный расход именно на его участке.
 */
data class Rider(
    val id: Long,
    val trip: Long,          // начало поездки
    val name: String,
    val friend: Boolean,     // «Друг» — только топливо, «Пассажир» — плюс износ
    val split: Boolean,      // «Поровну» или «Платит пассажир»
    val t0: Long,
    val km0: Double,
    val l0: Double,
    val t1: Long? = null,
    val km1: Double? = null,
    val l1: Double? = null,
    val price: Double,       // цена топлива на момент посадки
    val wear: Double,        // грн/км износа на момент посадки
) {
    val active: Boolean get() = t1 == null

    fun toJson() = JSONObject().apply {
        put("id", id); put("trip", trip); put("name", name); put("friend", friend); put("split", split)
        put("t0", t0); put("km0", km0); put("l0", l0)
        t1?.let { put("t1", it) }; km1?.let { put("km1", it) }; l1?.let { put("l1", it) }
        put("price", price); put("wear", wear)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rider(
            id = o.optLong("id"), trip = o.optLong("trip"), name = o.optString("name"),
            friend = o.optBoolean("friend", true), split = o.optBoolean("split", true),
            t0 = o.optLong("t0"), km0 = o.optDouble("km0"), l0 = o.optDouble("l0"),
            t1 = if (o.has("t1")) o.optLong("t1") else null,
            km1 = if (o.has("km1")) o.optDouble("km1") else null,
            l1 = if (o.has("l1")) o.optDouble("l1") else null,
            price = o.optDouble("price"), wear = o.optDouble("wear", 0.0),
        )
    }
}

/** Доля попутчика: проехал км, его литры, сумма. */
data class Share(val km: Double, val liters: Double, val fuelCost: Double, val wearCost: Double, val people: String) {
    val total: Double get() = fuelCost + wearCost
}

object Riders {
    private lateinit var file: File
    private val _items = MutableStateFlow<List<Rider>>(emptyList())
    val items: StateFlow<List<Rider>> = _items
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        file = File(ctx.applicationContext.filesDir, "riders.json")
        _items.value = try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                List(a.length()) { Rider.fromJson(a.getJSONObject(it)) }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        ready = true
    }

    @Synchronized
    private fun save(list: List<Rider>) {
        _items.value = list
        try {
            val a = JSONArray()
            list.takeLast(1000).forEach { a.put(it.toJson()) }
            file.writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    fun forTrip(trip: Long): List<Rider> = _items.value.filter { it.trip == trip }

    fun add(r: Rider) = save(_items.value + r)

    fun update(id: Long, f: (Rider) -> Rider) = save(_items.value.map { if (it.id == id) f(it) else it })

    fun delete(id: Long) = save(_items.value.filter { it.id != id })

    fun deleteTrip(trip: Long) = save(_items.value.filter { it.trip != trip })

    /** Поездка закончилась — всех, кто ещё «едет», высаживаем в её конце. */
    fun closeTrip(trip: Long, t: Long, km: Double, l: Double) =
        save(_items.value.map { if (it.trip == trip && it.active) it.copy(t1 = t, km1 = km, l1 = l) else it })

    /**
     * Делим топливо по участкам. На каждом отрезке между посадками/выходами смотрим, кто был в машине:
     * есть «Платит пассажир» — они платят за отрезок поровну между собой; иначе — поровну на всех,
     * включая водителя. Износ — только «Пассажирам», по их километрам.
     */
    fun shares(riders: List<Rider>, nowT: Long, nowKm: Double, nowL: Double): Map<Long, Share> {
        if (riders.isEmpty()) return emptyMap()
        data class P(val t: Long, val km: Double, val l: Double)
        val pts = riders.flatMap { r -> listOf(P(r.t0, r.km0, r.l0), P(r.t1 ?: nowT, r.km1 ?: nowKm, r.l1 ?: nowL)) }
            .sortedBy { it.t }.distinctBy { it.t }
        val km = HashMap<Long, Double>()
        val l = HashMap<Long, Double>()
        val maxPeople = HashMap<Long, Int>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dKm = (b.km - a.km).coerceAtLeast(0.0)
            val dL = (b.l - a.l).coerceAtLeast(0.0)
            val present = riders.filter { it.t0 <= a.t && (it.t1 ?: nowT) >= b.t }
            if (present.isEmpty()) continue
            val payers = present.filter { !it.split }
            if (payers.isNotEmpty()) {
                for (r in payers) l[r.id] = (l[r.id] ?: 0.0) + dL / payers.size
            } else {
                for (r in present) l[r.id] = (l[r.id] ?: 0.0) + dL / (present.size + 1)
            }
            for (r in present) {
                km[r.id] = (km[r.id] ?: 0.0) + dKm
                maxPeople[r.id] = maxOf(maxPeople[r.id] ?: 0, present.size + 1)
            }
        }
        return riders.associate { r ->
            val k = km[r.id] ?: 0.0
            val lit = l[r.id] ?: 0.0
            val ppl = maxPeople[r.id] ?: 2
            r.id to Share(
                km = k, liters = lit, fuelCost = lit * r.price,
                wearCost = if (r.friend) 0.0 else k * r.wear,
                people = if (r.split) "поровну, в машине до $ppl чел." else "платит пассажир",
            )
        }
    }

    fun shareText(r: Rider, s: Share): String {
        fun f(v: Double, d: Int) = if (d == 0) Math.round(v).toString() else "%.${d}f".format(v).replace('.', ',')
        val sb = StringBuilder()
        sb.append("Поездка: ${f(s.km, 1)} км, топливо ${f(s.liters, 2)} л × ${f(r.price, 2)} грн = ${f(s.fuelCost, 0)} грн")
        if (r.split) sb.append(" (делим поровну)")
        if (s.wearCost > 0) sb.append("\nИзнос машины: ${f(s.km, 1)} км × ${f(r.wear, 2)} = ${f(s.wearCost, 0)} грн")
        sb.append("\nИтого: ${f(s.total, 0)} грн")
        return sb.toString()
    }
}
