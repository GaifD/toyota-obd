package com.gaifd.toyotaobd.service

import com.gaifd.toyotaobd.obd.Dtc
import com.gaifd.toyotaobd.obd.Dtcs
import com.gaifd.toyotaobd.obd.EcuReply
import com.gaifd.toyotaobd.obd.Elm
import com.gaifd.toyotaobd.obd.Mode6
import com.gaifd.toyotaobd.obd.NoCarException
import com.gaifd.toyotaobd.obd.O2Sensor
import com.gaifd.toyotaobd.obd.Pids
import com.gaifd.toyotaobd.obd.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class Conn { OFF, CONNECTING, NO_CAR, ONLINE, ERROR }

data class TripLive(
    val start: Long,
    val km: Double,
    val sec: Long,
    val movingSec: Long,
    val fuelL: Double,
    val evKm: Double,
    val maxKmh: Double,
) {
    val l100: Double? get() = if (km >= 0.3) fuelL / km * 100 else null
    val avgKmh: Double get() = if (sec > 0) km / (sec / 3600.0) else 0.0
    val avgMovingKmh: Double get() = if (movingSec > 0) km / (movingSec / 3600.0) else 0.0
}

/** Всё, что знаем о машине после опроса при подключении. */
data class CarInfo(
    val ecus: List<String> = emptyList(),
    val engineEcu: String = "7E8",
    val pidHdr: Map<Int, String?> = emptyMap(),   // PID → адрес блока, у которого его спрашивать
    val o2: List<O2Sensor> = emptyList(),
    val o2Pid: Map<Int, Int> = emptyMap(),         // индекс датчика → PID
    val toyotaFuel: Boolean = false,
    val hybrid: Boolean = false,
) {
    fun has(pid: Int) = pidHdr.containsKey(pid)
    fun hdrFor(pid: Int): String? = pidHdr[pid]
    val engineHdr: String? get() = Obd.physHdr(engineEcu)
    val fuelMethod: String
        get() = when {
            has(Pids.FUEL_RATE) -> "по данным ЭБУ (PID 5E)"
            has(Pids.MAF) -> "по расходу воздуха (MAF)"
            else -> "нет данных для расчёта"
        }
}

data class Live(
    val conn: Conn = Conn.OFF,
    val message: String = "",
    val adapter: String = "",
    val protocol: Int = 0,
    val volt12: Double? = null,
    val rpm: Double? = null,
    val speed: Double? = null,       // с поправкой
    val fuelLph: Double? = null,
    val instL100: Double? = null,
    val fuelLevelL: Double? = null,
    val fuelLevelSource: String = "",
    val milOn: Boolean = false,
    val dtcCount: Int = 0,
    val values: Map<Int, Double> = emptyMap(),
    val trip: TripLive? = null,
    val info: CarInfo = CarInfo(),
    val battery: com.gaifd.toyotaobd.obd.BatteryData? = null,
    val updated: Long = 0,
) {
    val coolant: Double? get() = values[Pids.COOLANT]
    val soc: Double? get() = values[Pids.HV_SOC]
    val ev: Boolean get() = (rpm ?: 1.0) < 100 && (speed ?: 0.0) > 2
}

data class Monitor(val name: String, val complete: Boolean)

data class DiagResult(
    val dtcs: List<Dtc>,
    val mil: Boolean,
    val count: Int,
    val monitors: List<Monitor>,
    val ecus: List<String>,
    val time: Long = System.currentTimeMillis(),
)

data class FreezeFrame(val code: String?, val values: List<Pair<String, String>>)

/** История значений для графиков. */
object Samples {
    private val data = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    fun add(key: String, v: Double, t: Long = System.currentTimeMillis()) {
        synchronized(data) {
            val q = data.getOrPut(key) { ArrayDeque() }
            q.addLast(t to v)
            while (q.size > 1500) q.removeFirst()
        }
    }

    fun get(key: String, sinceMs: Long): List<Pair<Long, Double>> =
        synchronized(data) { data[key]?.filter { it.first >= sinceMs } ?: emptyList() }
}

/** Связь UI ↔ сервис: состояние и разовые команды диагностики. */
object Obd {
    val live = MutableStateFlow(Live())

    @Volatile
    var elm: Elm? = null

    /** Экран катализатора открыт — опрашиваем датчики кислорода каждый цикл. */
    val watchO2 = MutableStateFlow(false)

    /** Экран «Все датчики» открыт — перебираем все PID. */
    val watchAll = MutableStateFlow(false)

    /** Экран «Батарея» открыт — опрашиваем блоки часто. */
    val watchBattery = MutableStateFlow(false)

    /** «Хватит ли»: сколько ехать, отсчёт от пробега поездки в момент постановки цели. */
    data class Goal(val startKm: Double, val distanceKm: Double)

    val goal = MutableStateFlow<Goal?>(null)

    /** Точки текущей поездки (раз в ~5 с) — для попутчиков «сел N минут назад» и для записи. */
    private val points = ArrayList<com.gaifd.toyotaobd.data.TripPoint>()

    fun addPoint(p: com.gaifd.toyotaobd.data.TripPoint) = synchronized(points) { points += p }

    fun takePoints(): List<com.gaifd.toyotaobd.data.TripPoint> = synchronized(points) { ArrayList(points).also { points.clear() } }

    /** Накопленные км и литры поездки в момент [t] (ближайшая точка не позже). */
    fun pointAt(t: Long): com.gaifd.toyotaobd.data.TripPoint? = synchronized(points) {
        points.lastOrNull { it.t <= t } ?: points.firstOrNull()
    }

    fun physHdr(ecu: String): String? = when (ecu.length) {
        3 -> ecu.toIntOrNull(16)?.let { "%03X".format(it - 8) }
        8 -> "18DA" + ecu.substring(6, 8) + ecu.substring(4, 6)
        else -> null
    }

    private suspend fun <T> withElm(block: (Elm) -> T): T? = withContext(Dispatchers.IO) {
        val e = elm ?: return@withContext null
        if (live.value.conn != Conn.ONLINE) return@withContext null
        try {
            block(e)
        } catch (_: Exception) {
            null
        }
    }

    val online: Boolean get() = live.value.conn == Conn.ONLINE

    private fun safe(e: Elm, cmd: String, hdr: String?, timeout: Long = 4000): List<EcuReply> = try {
        e.request(cmd, hdr, timeout)
    } catch (_: NoCarException) {
        emptyList()
    }

    /** Ошибки всех блоков (сохранённые, ожидающие, постоянные) и готовность мониторов. */
    suspend fun readDiag(): DiagResult? = withElm { e ->
        val dtcs = mutableListOf<Dtc>()
        for ((cmd, kind) in listOf("03" to Dtc.Kind.STORED, "07" to Dtc.Kind.PENDING, "0A" to Dtc.Kind.PERMANENT)) {
            safe(e, cmd, null, 6000).forEach { dtcs += Dtcs.parse(it, kind, e.isCan) }
        }
        val info = live.value.info
        val st = safe(e, "0101", info.engineHdr).firstOrNull { it.u8(1) == 1 }
        val mil = st?.let { it.u8(2) and 0x80 != 0 } ?: false
        val count = st?.let { it.u8(2) and 0x7F } ?: 0
        val monitors = st?.let { monitors(it) } ?: emptyList()
        DiagResult(dtcs.distinctBy { it.code + it.kind + it.ecu }, mil, count, monitors, info.ecus)
    }

    private fun monitors(r: EcuReply): List<Monitor> {
        val b = r.u8(3)
        val c = r.u8(4)
        val d = r.u8(5)
        val out = mutableListOf<Monitor>()
        val base = listOf("Пропуски зажигания", "Топливная система", "Компоненты")
        for (i in 0 until 3) if (b and (1 shl i) != 0) out += Monitor(base[i], b and (1 shl (i + 4)) == 0)
        val compression = b and 0x08 != 0
        val names = if (!compression) listOf(
            "Катализатор", "Подогрев катализатора", "Система EVAP", "Вторичный воздух",
            "Кондиционер", "Датчики кислорода", "Подогрев датчиков кислорода", "EGR / VVT",
        ) else listOf(
            "Катализатор NMHC", "NOx / SCR", "—", "Наддув", "—", "Датчик выхлопа", "Сажевый фильтр", "EGR / VVT",
        )
        for (i in 0 until 8) if (c and (1 shl i) != 0 && names[i] != "—") out += Monitor(names[i], d and (1 shl i) == 0)
        return out
    }

    /** Стереть ошибки (режим 04). Вместе с ними стираются результаты самотестов (Mode 6) и готовность. */
    suspend fun clearDtcs(): Boolean = withElm { e ->
        safe(e, "04", null, 6000).isNotEmpty()
    } ?: false

    /** Снимок условий, при которых записалась ошибка. */
    suspend fun freezeFrame(): FreezeFrame? = withElm { e ->
        val hdr = live.value.info.engineHdr
        val code = safe(e, "020200", hdr).firstOrNull()?.let { Dtcs.decode(it.u8(3), it.u8(4)) }
        if (code == null) return@withElm FreezeFrame(null, emptyList())
        val vals = mutableListOf<Pair<String, String>>()
        val pids = listOf(
            Pids.FUEL_SYS, Pids.LOAD, Pids.COOLANT, Pids.STFT1, Pids.LTFT1, Pids.STFT2, Pids.LTFT2,
            Pids.MAP, Pids.RPM, Pids.SPEED, Pids.IAT, Pids.MAF, Pids.THROTTLE,
        )
        for (pid in pids) {
            val r = safe(e, "02%02X00".format(pid), hdr).firstOrNull { it.u8(1) == pid } ?: continue
            // Ответ режима 02: 42 PID кадр A B… — сдвигаем, чтобы расшифровать как режим 01.
            val shifted = EcuReply(r.ecu, intArrayOf(0x41, pid) + r.data.drop(3).toIntArray())
            val p = Pids.all[pid] ?: continue
            val v = p.decode(shifted) ?: continue
            val text = if (pid == Pids.FUEL_SYS) Pids.fuelSystem(v.toInt()) else Pids.format(v, p.decimals) + " " + p.unit
            vals += p.name to text
        }
        FreezeFrame(code, vals)
    }

    /** Результаты самотестов (режим 06): катализаторы, датчики кислорода, пропуски по цилиндрам. */
    suspend fun mode6(): List<TestResult>? = withElm { e ->
        if (!e.isCan) return@withElm emptyList<TestResult>()
        val hdr = live.value.info.engineHdr
        val mids = mutableSetOf<Int>()
        var base = 0
        while (base <= 0xE0) {
            val r = safe(e, "06%02X".format(base), hdr).firstOrNull { it.u8(1) == base } ?: break
            val s = Mode6.supported(base, r)
            mids += s
            if (base + 0x20 !in s) break
            base += 0x20
        }
        val wanted = mids.filter {
            it in 0x01..0x10 || it in 0x21..0x24 || it in 0x35..0x38 || it in 0x41..0x48 ||
                it in 0x81..0x84 || it in 0xA1..0xAD
        }.sorted()
        val out = mutableListOf<TestResult>()
        for (mid in wanted) safe(e, "06%02X".format(mid), hdr, 5000).forEach { out += Mode6.parse(it) }
        out
    }

    /**
     * Перебор расширенных параметров Toyota (режим 21) у двигателя, гибридной системы, батареи и приборки.
     * Сырые ответы сохраняются в файл — по ним в следующей версии расшифруем блоки батареи.
     */
    suspend fun toyotaScan(progress: (Int, Int) -> Unit): String? = withElm { e ->
        val sb = StringBuilder()
        sb.append("Бортовик — сканер Toyota\nАдаптер: ${e.version}, протокол ${e.protocol}\n")
        sb.append("Блоки: ${live.value.info.ecus.joinToString()}\n\n")
        val hdrs = listOf("7E0", "7E2", "7E3", "7C0")
        val total = hdrs.size * 256
        var n = 0
        for (h in hdrs) {
            sb.append("== $h ==\n")
            for (p in 0 until 256) {
                n++
                if (n % 8 == 0) progress(n, total)
                val rs = try {
                    e.request("21%02X".format(p), h, 1500)
                } catch (_: NoCarException) {
                    emptyList()
                }
                rs.filter { it.u8(1) == p }.forEach { sb.append("21%02X ".format(p)).append(it.toString()).append('\n') }
            }
        }
        progress(total, total)
        sb.toString()
    }

    fun journal(): String = elm?.journal?.let { synchronized(it) { it.joinToString("\n\n") } } ?: "Адаптер не подключён"
}
