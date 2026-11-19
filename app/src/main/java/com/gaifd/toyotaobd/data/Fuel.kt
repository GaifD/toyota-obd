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
    val lastSensorL: Double? = null,      // уровень по датчику в начале/конце прошлой поездки
    val pendingFillL: Double? = null,     // похоже, заправился — ждём цену
    val fills: List<Fill> = emptyList(),
    val lampL: Double? = null,            // сколько литров в баке, когда загорается лампочка
    val anchorL: Double? = null,          // уровень по датчику, от которого сверяем расход
    val countedSinceAnchor: Double = 0.0, // сколько насчитали с тех пор
    val lastCalib: String? = null,        // как прошла последняя сверка — для экрана
    val missedDrive: Boolean = false,     // похоже, машина ездила без приложения
)

/**
 * Учёт топлива в баке и его цены. Цена считается средневзвешенной: долил 20 л по 62 грн к 30 л по 58 —
 * в баке 50 л по 59,6. Поездка оплачивается по этой цене в момент её окончания.
 * Расход уточняется сам: насчитанное сверяется с тем, насколько упал уровень по датчику.
 */
object Fuel {
    private lateinit var sp: SharedPreferences
    private val _tank = MutableStateFlow(Tank())
    val tank: StateFlow<Tank> = _tank
    private var ready = false

    /** Сверяем, когда насчитали хотя бы столько — иначе шум датчика (±1–2 л) больше самой ошибки. */
    private const val CALIB_MIN_L = 12.0

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("fuel", Context.MODE_PRIVATE)
        _tank.value = try {
            val o = JSONObject(sp.getString("tank", "{}") ?: "{}")
            val fa = o.optJSONArray("fills") ?: JSONArray()
            fun d(k: String): Double? = if (o.has(k)) o.optDouble(k) else null
            Tank(
                inTankL = d("inTankL"),
                avgPrice = d("avgPrice"),
                countedSinceFull = o.optDouble("countedSinceFull", 0.0),
                hadFull = o.optBoolean("hadFull"),
                lastSensorL = d("lastSensorL"),
                pendingFillL = d("pendingFillL"),
                fills = List(fa.length()) { Fill.fromJson(fa.getJSONObject(it)) },
                lampL = d("lampL"),
                anchorL = d("anchorL"),
                countedSinceAnchor = o.optDouble("countedSinceAnchor", 0.0),
                lastCalib = if (o.has("lastCalib")) o.optString("lastCalib") else null,
                missedDrive = o.optBoolean("missedDrive"),
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
        t.lampL?.let { o.put("lampL", it) }
        t.anchorL?.let { o.put("anchorL", it) }
        o.put("countedSinceAnchor", t.countedSinceAnchor)
        t.lastCalib?.let { o.put("lastCalib", it) }
        o.put("missedDrive", t.missedDrive)
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
                inTankL = sensorL ?: t.inTankL?.let { (it - liters).coerceAtLeast(0.0) },
                countedSinceFull = t.countedSinceFull + liters,
                countedSinceAnchor = t.countedSinceAnchor + liters,
                lastSensorL = sensorL ?: t.lastSensorL,
            )
        )
    }

    /**
     * Начало поездки, машина стоит — уровень по датчику самый честный.
     * Вырос заметно — заправка. Иначе — сверка расхода, если насчитали достаточно.
     */
    @Synchronized
    fun atTripStart(sensorL: Double): Double? {
        val t = _tank.value
        val before = t.lastSensorL
        if (before == null) {
            set(t.copy(lastSensorL = sensorL, inTankL = sensorL, anchorL = sensorL, countedSinceAnchor = 0.0))
            return null
        }
        val diff = sensorL - before
        if (diff >= 4.0) {
            // Заправка: новая точка отсчёта для сверки.
            set(t.copy(pendingFillL = t.pendingFillL ?: diff, lastSensorL = sensorL, inTankL = sensorL, anchorL = sensorL, countedSinceAnchor = 0.0))
            return diff
        }
        if (diff <= -2.0) {
            // Топлива стало меньше, чем в конце прошлой поездки, — машина ездила без приложения.
            // Литры честные (датчик), но сверять расход по этому отрезку нельзя: начинаем отсчёт заново.
            set(
                t.copy(
                    anchorL = sensorL, countedSinceAnchor = 0.0, lastSensorL = sensorL, inTankL = sensorL,
                    lastCalib = "пропущена — была поездка без приложения (ушло ${"%.1f".format(-diff)} л)",
                )
            )
            return null
        }
        // Топлива меньше, чем в конце прошлой записанной поездки, — значит, ездили без приложения.
        // Сверку расхода в этом случае не делаем (насчитанного нет), начинаем отсчёт заново.
        if (before - sensorL > 3.0 || (t.anchorL != null && t.anchorL - sensorL > t.countedSinceAnchor * 1.3 + 3)) {
            set(t.copy(anchorL = sensorL, countedSinceAnchor = 0.0, lastSensorL = sensorL, inTankL = sensorL, missedDrive = true))
            return null
        }
        val anchor = t.anchorL
        if (anchor == null) {
            set(t.copy(anchorL = sensorL, countedSinceAnchor = 0.0, lastSensorL = sensorL, inTankL = sensorL))
            return null
        }
        if (t.countedSinceAnchor >= CALIB_MIN_L) {
            val real = anchor - sensorL
            if (real > 3) {
                val ratio = (real / t.countedSinceAnchor).coerceIn(0.75, 1.3)
                val s = Prefs.settings.value
                val k = (s.fuelK * ratio).coerceIn(0.5, 2.0)
                // Плавно: половина шага — один неточный замер не испортит всё.
                Prefs.update { it.copy(fuelK = it.fuelK * 0.5 + k * 0.5) }
                val note = "по датчику ушло ${"%.1f".format(real)} л, насчитано ${"%.1f".format(t.countedSinceAnchor)} л"
                set(t.copy(anchorL = sensorL, countedSinceAnchor = 0.0, lastSensorL = sensorL, inTankL = sensorL, lastCalib = note))
                return null
            }
        }
        set(t.copy(lastSensorL = sensorL, inTankL = sensorL))
        return null
    }

    fun clearMissed() {
        set(_tank.value.copy(missedDrive = false))
    }

    fun dismissPending() {
        set(_tank.value.copy(pendingFillL = null))
    }

    /** Загорелась лампочка — запоминаем, сколько в этот момент в баке. */
    fun setLamp(liters: Double?) {
        set(_tank.value.copy(lampL = liters))
    }

    /**
     * Записать заправку. До полного — ещё и калибровка расхода по двум полным бакам (точнее всего).
     */
    @Synchronized
    fun addFill(liters: Double, price: Double, full: Boolean, sensorAfter: Double?) {
        val t = _tank.value
        val s = Prefs.settings.value
        val pending = t.pendingFillL != null
        // Если заправку поймал датчик, уровень «до» — это уровень после минус залитое.
        val before = when {
            pending && sensorAfter != null -> (sensorAfter - liters).coerceAtLeast(0.0)
            else -> t.inTankL ?: 0.0
        }
        val oldPrice = t.avgPrice ?: price
        val newIn = when {
            sensorAfter != null -> sensorAfter
            full -> s.tankL
            else -> (before + liters).coerceAtMost(s.tankL)
        }
        val avg = if (before + liters > 0) (before * oldPrice + liters * price) / (before + liters) else price
        var counted = t.countedSinceFull
        if (full) {
            if (t.hadFull && counted >= 8.0) {
                val ratio = (liters / counted).coerceIn(0.7, 1.4)
                val k = (s.fuelK * ratio).coerceIn(0.5, 2.0)
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
                anchorL = if (pending) t.anchorL else sensorAfter ?: t.anchorL,
                countedSinceAnchor = if (pending || sensorAfter != null) 0.0 else t.countedSinceAnchor,
                fills = t.fills + Fill(System.currentTimeMillis(), liters, price, full),
            )
        )
    }

    fun lastPrice(): Double? = _tank.value.fills.lastOrNull()?.price
}
