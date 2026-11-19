package com.gaifd.toyotaobd.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class Settings(
    val elmMac: String? = null,
    val elmName: String? = null,
    val carMac: String? = null,          // магнитола машины — по её подключению стартуем сами
    val carName: String? = null,
    val autoStart: Boolean = true,
    val tankL: Double = 65.0,
    val defaultPrice: Double = 60.0,     // грн/л, пока нет ни одной заправки
    val fuelK: Double = 1.0,             // поправка расхода (калибруется по заправкам)
    val speedK: Double = 1.0,            // поправка скорости
    val odoKm: Double? = null,           // пробег машины, введённый вручную (км)
    val odoAtTripsKm: Double = 0.0,      // сколько было в поездках на момент ввода
    val warnCoolant: Boolean = true,
    val warnDtc: Boolean = true,
    val warnVolt: Boolean = true,
    val warnFuel: Boolean = true,
    val warnSoc: Boolean = true,
    val wearRate: Double = 2.0,          // грн/км износа для «Пассажиров»
    val gpsTrack: Boolean = false,       // трек на карте — по желанию (глушилки)
    val hudMirror: Boolean = true,
    val keepScreenOn: Boolean = true,
    val theme: Int = THEME_DARK,
    val accent: Int = DEFAULT_ACCENT,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("elmMac", elmMac); put("elmName", elmName); put("carMac", carMac); put("carName", carName)
        put("autoStart", autoStart); put("tankL", tankL); put("defaultPrice", defaultPrice)
        put("fuelK", fuelK); put("speedK", speedK); put("odoKm", odoKm); put("odoAtTripsKm", odoAtTripsKm)
        put("warnCoolant", warnCoolant); put("warnDtc", warnDtc); put("warnVolt", warnVolt); put("warnFuel", warnFuel); put("warnSoc", warnSoc); put("wearRate", wearRate); put("gpsTrack", gpsTrack)
        put("hudMirror", hudMirror); put("keepScreenOn", keepScreenOn); put("theme", theme); put("accent", accent)
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        const val THEME_BY_TIME = 3
        const val DEFAULT_ACCENT = 0xFF2DE1D3.toInt()

        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
        private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            return Settings(
                elmMac = o.str("elmMac"), elmName = o.str("elmName"), carMac = o.str("carMac"), carName = o.str("carName"),
                autoStart = o.optBoolean("autoStart", d.autoStart),
                tankL = o.optDouble("tankL", d.tankL),
                defaultPrice = o.optDouble("defaultPrice", d.defaultPrice),
                fuelK = o.optDouble("fuelK", d.fuelK),
                speedK = o.optDouble("speedK", d.speedK),
                odoKm = o.dbl("odoKm"),
                odoAtTripsKm = o.optDouble("odoAtTripsKm", 0.0),
                warnCoolant = o.optBoolean("warnCoolant", true),
                warnDtc = o.optBoolean("warnDtc", true),
                warnVolt = o.optBoolean("warnVolt", true),
                warnFuel = o.optBoolean("warnFuel", true),
                warnSoc = o.optBoolean("warnSoc", true),
                wearRate = o.optDouble("wearRate", 2.0),
                gpsTrack = o.optBoolean("gpsTrack", false),
                hudMirror = o.optBoolean("hudMirror", true),
                keepScreenOn = o.optBoolean("keepScreenOn", true),
                theme = o.optInt("theme", d.theme),
                accent = o.optInt("accent", d.accent),
            )
        }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("bortovik", Context.MODE_PRIVATE)
        _settings.value = try {
            sp.getString("settings", null)?.let { Settings.fromJson(JSONObject(it)) } ?: Settings()
        } catch (_: Exception) {
            Settings()
        }
        ready = true
    }

    @Synchronized
    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        if (s == _settings.value) return
        _settings.value = s
        sp.edit().putString("settings", s.toJson().toString()).apply()
    }

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) = sp.edit().putBoolean("askedNotif", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /** Ошибки, о которых уже предупредили — чтобы не напоминать о той же самой каждую поездку. */
    var knownDtcs: Set<String>
        get() = sp.getStringSet("knownDtcs", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("knownDtcs", v).apply()

    /** Заряд гибридной батареи в моменты, когда заводился двигатель, — из них узнаём порог. */
    var engineStartSoc: List<Double>
        get() = (sp.getString("engineStartSoc", "") ?: "").split(';').mapNotNull { it.toDoubleOrNull() }
        set(v) = sp.edit().putString("engineStartSoc", v.takeLast(30).joinToString(";")).apply()

    /** Порог заряда, при котором заводится двигатель (медиана наблюдений, по умолчанию 40%). */
    val socThreshold: Double
        get() = engineStartSoc.takeIf { it.size >= 3 }?.sorted()?.let { it[it.size / 2] } ?: 40.0

    /** Напряжение 12 В при последних подключениях — видно, если адаптер сажает аккумулятор. */
    var voltLog: List<Pair<Long, Double>>
        get() = (sp.getString("voltLog", "") ?: "").split(';').mapNotNull {
            val t = it.substringBefore(',').toLongOrNull()
            val v = it.substringAfter(',').toDoubleOrNull()
            if (t != null && v != null) t to v else null
        }
        set(v) = sp.edit().putString("voltLog", v.takeLast(30).joinToString(";") { "${it.first},${it.second}" }).apply()
}
