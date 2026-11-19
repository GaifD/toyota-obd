package com.gaifd.toyotaobd.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Пункт обслуживания. «Заменено» — следующий раз через полный интервал;
 * «Проверено, в норме» — через половину (раз не меняли, глянуть надо раньше).
 */
data class MaintItem(
    val id: Long,
    val name: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val lastKm: Double? = null,
    val lastTs: Long? = null,
    val lastWasCheck: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name)
        intervalKm?.let { put("intervalKm", it) }
        intervalMonths?.let { put("intervalMonths", it) }
        lastKm?.let { put("lastKm", it) }
        lastTs?.let { put("lastTs", it) }
        put("lastWasCheck", lastWasCheck)
    }

    companion object {
        fun fromJson(o: JSONObject) = MaintItem(
            id = o.optLong("id"), name = o.optString("name"),
            intervalKm = if (o.has("intervalKm")) o.optInt("intervalKm") else null,
            intervalMonths = if (o.has("intervalMonths")) o.optInt("intervalMonths") else null,
            lastKm = if (o.has("lastKm")) o.optDouble("lastKm") else null,
            lastTs = if (o.has("lastTs")) o.optLong("lastTs") else null,
            lastWasCheck = o.optBoolean("lastWasCheck"),
        )
    }
}

enum class MaintState { UNKNOWN, OK, SOON, OVERDUE }

data class MaintStatus(val state: MaintState, val kmLeft: Double?, val daysLeft: Long?, val fraction: Float?)

object Maint {
    private lateinit var sp: SharedPreferences
    private val _items = MutableStateFlow<List<MaintItem>>(emptyList())
    val items: StateFlow<List<MaintItem>> = _items
    private var ready = false

    /** Интервалы — ориентир по регламенту Toyota для гибридов; правятся под себя. */
    private fun defaults(): List<MaintItem> {
        var id = 1L
        fun m(name: String, km: Int?, months: Int?) = MaintItem(id++, name, km, months)
        return listOf(
            m("Масло двигателя и фильтр", 10_000, 12),
            m("Воздушный фильтр двигателя", 30_000, 36),
            m("Салонный фильтр", 15_000, 12),
            m("Фильтр охлаждения гибридной батареи (чистка)", 15_000, 12),
            m("Свечи зажигания", 120_000, null),
            m("Тормозная жидкость", 40_000, 24),
            m("Антифриз двигателя", 160_000, 120),
            m("Антифриз инвертора", 160_000, 120),
            m("Жидкость гибридной коробки", 100_000, null),
        )
    }

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("maint", Context.MODE_PRIVATE)
        val raw = sp.getString("items", null)
        _items.value = if (raw == null) defaults() else try {
            val a = JSONArray(raw)
            List(a.length()) { MaintItem.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            defaults()
        }
        ready = true
    }

    @Synchronized
    private fun save(list: List<MaintItem>) {
        _items.value = list
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp.edit().putString("items", a.toString()).apply()
    }

    fun update(id: Long, f: (MaintItem) -> MaintItem) = save(_items.value.map { if (it.id == id) f(it) else it })
    fun delete(id: Long) = save(_items.value.filter { it.id != id })
    fun add(name: String, km: Int?, months: Int?) =
        save(_items.value + MaintItem((_items.value.maxOfOrNull { it.id } ?: 0) + 1, name, km, months))

    /** Текущий пробег машины: введённый вручную + проехано с тех пор. */
    fun odo(): Double? {
        val s = Prefs.settings.value
        return s.odoKm?.let { it + (Trips.totalKm - s.odoAtTripsKm) }
    }

    fun status(m: MaintItem, odo: Double?, now: Long = System.currentTimeMillis()): MaintStatus {
        if (m.lastKm == null && m.lastTs == null) return MaintStatus(MaintState.UNKNOWN, null, null, null)
        val factor = if (m.lastWasCheck) 0.5 else 1.0
        val kmLeft = if (m.intervalKm != null && m.lastKm != null && odo != null) m.lastKm + m.intervalKm * factor - odo else null
        val daysLeft = if (m.intervalMonths != null && m.lastTs != null)
            ((m.lastTs + (m.intervalMonths * factor * 30.44 * 86_400_000L).toLong() - now) / 86_400_000L) else null
        val fracKm = if (kmLeft != null && m.intervalKm != null) (1 - kmLeft / (m.intervalKm * factor)).toFloat() else null
        val fracT = if (daysLeft != null && m.intervalMonths != null) (1 - daysLeft / (m.intervalMonths * factor * 30.44)).toFloat() else null
        val frac = listOfNotNull(fracKm, fracT).maxOrNull()?.coerceIn(0f, 1f)
        val state = when {
            (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0) -> MaintState.OVERDUE
            (kmLeft != null && (kmLeft < 1000 || (m.intervalKm != null && kmLeft < m.intervalKm * factor * 0.1))) ||
                (daysLeft != null && daysLeft < 30) -> MaintState.SOON
            else -> MaintState.OK
        }
        return MaintStatus(state, kmLeft, daysLeft, frac)
    }
}
