package com.gaifd.toyotaobd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Trip
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.obd.Dtcs
import com.gaifd.toyotaobd.obd.Elm
import com.gaifd.toyotaobd.obd.ElmException
import com.gaifd.toyotaobd.obd.NoCarException
import com.gaifd.toyotaobd.obd.Pids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Фоновая служба: держит связь с ELM327, опрашивает машину, считает поездку и топливо,
 * предупреждает о проблемах. Работает, пока ты в машине.
 */
class ObdService : Service() {

    companion object {
        const val ACTION_START = "com.gaifd.toyotaobd.START"
        const val ACTION_STOP = "com.gaifd.toyotaobd.STOP"
        const val ACTION_CAR_GONE = "com.gaifd.toyotaobd.CAR_GONE"
        const val EXTRA_FROM_CAR = "fromCar"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context, fromCar: Boolean = false) {
            val i = Intent(ctx, ObdService::class.java).setAction(ACTION_START).putExtra(EXTRA_FROM_CAR, fromCar)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Система не дала стартовать из фона — просим нажать.
                Notify.info(ctx, Notify.ID_START, "Машина подключена", "Нажми, чтобы подключить Бортовик к адаптеру. Чтобы подключался сам — сними ограничение батареи.", tab = 0)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ObdService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }

        fun carGone(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, ObdService::class.java).setAction(ACTION_CAR_GONE))
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    // Текущие значения PID и история уровня топлива.
    private val values = ConcurrentHashMap<Int, Double>()
    private val levels = ArrayDeque<Double>()
    private var level: Double? = null
    private var levelSource = ""
    private var fillChecked = false
    private var volt: Double? = null
    private var mil = false
    private var dtcCount = -1
    private var info = CarInfo()

    // Поездка.
    private class Acc(val start: Long) {
        var km = 0.0
        var ms = 0L
        var movingMs = 0L
        var fuelL = 0.0
        var evKm = 0.0
        var maxKmh = 0.0
        val bKm = DoubleArray(Trip.BUCKETS)
        val bL = DoubleArray(Trip.BUCKETS)
        val sKm = DoubleArray(Trip.BUCKETS)
        val sL = DoubleArray(Trip.BUCKETS)
        var tankStart: Double? = null
        var lastPoint = 0L
        var hasTrack = false
    }

    /** Скорости за последние 30 с — чтобы понять, ровный ли ход. */
    private val speedWin = ArrayDeque<Pair<Long, Double>>()

    // Заряд гибридной батареи: запуски двигателя и «не растёт».
    private var prevRpm: Double? = null
    private var lowSocSince = 0L
    private var lowSocAt = 0.0
    private var warnedSoc = false
    private var maintNotified = false

    private var acc: Acc? = null
    private var lastT = 0L
    private var lastNotif = 0L

    // Предупреждения — по одному за поездку.
    private var warnedCoolant = false
    private var warnedFuel = false
    private var warnedVolt = false
    private var lowVoltCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Trips.init(this)
        Fuel.init(this)
        com.gaifd.toyotaobd.data.Riders.init(this)
        com.gaifd.toyotaobd.data.Maint.init(this)
        Notify.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_CAR_GONE -> {
                // Вышли из машины: поездку закрываем, связь отпускаем.
                shutdown()
                return START_NOT_STICKY
            }
        }
        if (!goForeground()) return START_NOT_STICKY
        running = true
        if (job?.isActive != true) job = scope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val n = Notify.service(this, "Подключаюсь к адаптеру…")
        if (Build.VERSION.SDK_INT >= 29) {
            val withGps = Prefs.settings.value.gpsTrack && Gps.permitted(this)
            try {
                startForeground(
                    Notify.SERVICE_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        (if (withGps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0),
                )
                if (withGps) Gps.start(this)
            } catch (e: SecurityException) {
                // Геолокация «только при использовании», а запустились из фона — едем без трека.
                startForeground(Notify.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(Notify.SERVICE_ID, n)
            Gps.start(this)
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bortovik:obd")
                ?.apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        }
        true
    } catch (e: Exception) {
        Obd.live.update { it.copy(conn = Conn.ERROR, message = "Нет разрешения на Bluetooth — открой приложение") }
        stopSelf()
        false
    }

    private fun shutdown() {
        job?.cancel()
        job = null
        finishTrip()
        Gps.stop()
        Obd.elm?.close()
        Obd.elm = null
        Obd.live.update { Live(conn = Conn.OFF, info = it.info) }
        running = false
        try {
            wake?.release()
        } catch (_: Exception) {
        }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finishTrip()
        Gps.stop()
        Obd.elm?.close()
        Obd.elm = null
        running = false
        scope.cancel()
        try {
            wake?.release()
        } catch (_: Exception) {
        }
        if (Obd.live.value.conn != Conn.OFF) Obd.live.update { Live(conn = Conn.OFF, info = it.info) }
        super.onDestroy()
    }

    private fun setConn(c: Conn, msg: String) {
        Obd.live.update { it.copy(conn = c, message = msg) }
        val text = when (c) {
            Conn.CONNECTING -> "Подключаюсь к адаптеру…"
            Conn.NO_CAR -> "Адаптер на связи, машина выключена"
            Conn.ERROR -> msg
            Conn.ONLINE -> "На связи с машиной"
            Conn.OFF -> "Отключено"
        }
        Notify.updateService(this, text)
    }

    // ---------------- Главный цикл ----------------

    private suspend fun runLoop() {
        var failures = 0
        var noCarSince = 0L
        while (coroutineContext.isActive) {
            val mac = Prefs.settings.value.elmMac
            if (mac == null) {
                setConn(Conn.ERROR, "Выбери адаптер в настройках")
                break
            }
            setConn(Conn.CONNECTING, "")
            val e = try {
                val c = Elm.connect(mac)
                try {
                    c.setupAdapter()
                } catch (ex: Exception) {
                    c.close()
                    throw ex
                }
                c
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                failures++
                setConn(Conn.ERROR, ex.message ?: "Адаптер не отвечает")
                if (failures >= 8) break
                delay((5000L * failures).coerceAtMost(30_000L))
                continue
            }
            failures = 0
            Obd.elm = e
            Obd.live.update { it.copy(adapter = e.version) }
            try {
                readVolt(e, first = true)
                while (coroutineContext.isActive) {
                    try {
                        e.findCar()
                        discover(e)
                        noCarSince = 0
                        setConn(Conn.ONLINE, "")
                        poll(e)
                    } catch (nc: NoCarException) {
                        finishTrip()
                        setConn(Conn.NO_CAR, nc.message ?: "")
                        if (noCarSince == 0L) noCarSince = System.currentTimeMillis()
                        // Машина давно выключена — отпускаем адаптер, чтобы не держать телефон.
                        if (System.currentTimeMillis() - noCarSince > 10 * 60_000L) {
                            e.close()
                            Obd.elm = null
                            shutdownFromLoop()
                            return
                        }
                        delay(5000)
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ElmException) {
                setConn(Conn.ERROR, ex.message ?: "Связь с адаптером порвалась")
            } catch (ex: Exception) {
                setConn(Conn.ERROR, "Ошибка: ${ex.message}")
            }
            finishTrip()
            Obd.elm = null
            e.close()
            delay(3000)
        }
        shutdownFromLoop()
    }

    private fun shutdownFromLoop() {
        android.os.Handler(mainLooper).post { shutdown() }
    }

    private fun readVolt(e: Elm, first: Boolean) {
        val v = try {
            e.voltage()
        } catch (_: NoCarException) {
            null
        } ?: return
        volt = v
        Obd.live.update { it.copy(volt12 = v) }
        if (first) {
            Prefs.voltLog = Prefs.voltLog + (System.currentTimeMillis() to v)
            if (v < 11.9 && Prefs.settings.value.warnVolt) {
                Notify.alert(
                    this, Notify.ID_VOLT, "Аккумулятор 12 В разряжен: ${fmt(v, 1)} В",
                    "Если машина стояла недолго — возможно, адаптер в разъёме OBD сажает аккумулятор на стоянке. " +
                        "Вынимай его на долгую стоянку или возьми адаптер с выключателем.", tab = 0,
                )
            }
        }
    }

    /** Что умеет машина: какие блоки отвечают, какие параметры есть, где датчики кислорода. */
    private fun discover(e: Elm) {
        val bySupport = LinkedHashMap<String, MutableSet<Int>>()
        var base = 0
        while (base <= 0xA0) {
            val rs = e.request("01%02X".format(base), null, 5000)
            var more = false
            for (r in rs) {
                if (r.u8(1) != base) continue
                val set = Pids.supported(base, r)
                bySupport.getOrPut(r.ecu) { mutableSetOf() }.addAll(set)
                if (base + 0x20 in set) more = true
            }
            if (!more) break
            base += 0x20
        }
        val ecus = bySupport.keys.sorted()
        val engine = when {
            "7E8" in bySupport -> "7E8"
            else -> bySupport.entries.maxByOrNull { it.value.size }?.key ?: "7E8"
        }
        val pidHdr = HashMap<Int, String?>()
        val all = bySupport.values.flatten().toSet()
        for (pid in all) {
            val src = if (bySupport[engine]?.contains(pid) == true) engine
            else bySupport.entries.firstOrNull { pid in it.value }?.key ?: engine
            pidHdr[pid] = Obd.physHdr(src)
        }
        info = CarInfo(ecus = ecus, engineEcu = engine, pidHdr = pidHdr)
        // Датчики кислорода.
        val map13 = if (info.has(Pids.O2_PRESENT)) e.request("0113", info.hdrFor(Pids.O2_PRESENT)).firstOrNull()?.u8(2) else null
        val map1d = if (map13 == null && info.has(Pids.O2_PRESENT4)) e.request("011D", info.hdrFor(Pids.O2_PRESENT4)).firstOrNull()?.u8(2) else null
        val sensors = Pids.o2Sensors(map13, map1d)
        val o2Pid = HashMap<Int, Int>()
        for (s in sensors) {
            val i = s.index
            when {
                info.has(0x34 + i) -> o2Pid[i] = 0x34 + i
                info.has(0x24 + i) -> o2Pid[i] = 0x24 + i
                info.has(0x14 + i) -> o2Pid[i] = 0x14 + i
            }
        }
        // Уровень топлива в литрах с приборки Toyota (если отвечает).
        val tank = Prefs.settings.value.tankL
        val toyotaFuel = e.isCan && try {
            e.request("2129", "7C0", 1500).firstOrNull { it.u8(1) == 0x29 }?.let { it.u8(2) / 2.0 in 0.5..(tank * 1.3) } ?: false
        } catch (_: NoCarException) {
            false
        }
        info = info.copy(
            o2 = sensors.filter { o2Pid.containsKey(it.index) },
            o2Pid = o2Pid,
            toyotaFuel = toyotaFuel,
            hybrid = "7EA" in ecus || info.has(Pids.HV_SOC),
        )
        Obd.live.update { it.copy(info = info, protocol = e.protocol) }
    }

    private fun read(e: Elm, pid: Int): Double? {
        val r = e.request("01%02X".format(pid), info.hdrFor(pid)).firstOrNull { it.u8(1) == pid } ?: return null
        val v = Pids.all[pid]?.decode?.invoke(r) ?: return null
        values[pid] = v
        Samples.add("01%02X".format(pid), v)
        return v
    }

    private fun readRaw(e: Elm, pid: Int) = e.request("01%02X".format(pid), info.hdrFor(pid)).firstOrNull { it.u8(1) == pid }

    private suspend fun poll(e: Elm) {
        val slow = listOf(
            Pids.COOLANT, Pids.ECU_VOLT, Pids.HV_SOC, Pids.STFT1, Pids.LTFT1, Pids.STFT2, Pids.LTFT2,
            Pids.IAT, Pids.LOAD, Pids.THROTTLE, Pids.AMBIENT, Pids.OIL, Pids.FUEL_SYS, Pids.CAT_T1, Pids.CAT_T2,
        ).filter { info.has(it) }
        val everything = Pids.all.keys.filter { info.has(it) }.sorted()
        var slowIdx = 0
        var allIdx = 0
        var cycle = 0
        var lastLevel = 0L
        var lastStatus = 0L
        var lastVolt = System.currentTimeMillis()
        var lastOk = System.currentTimeMillis()
        levels.clear()
        level = null
        baseLevel = null
        fillChecked = false
        warnedCoolant = false; warnedFuel = false; warnedVolt = false; lowVoltCount = 0
        warnedSoc = false; lowSocSince = 0L; prevRpm = null
        var lastBattery = 0L
        var lastIr = 0L
        val hasBattery = "7EA" in info.ecus
        startTrip()
        maintCheck()
        while (true) {
            coroutineContext.ensureActive()
            val t0 = System.currentTimeMillis()
            val rpm = if (info.has(Pids.RPM)) read(e, Pids.RPM) else null
            val spd = if (info.has(Pids.SPEED)) read(e, Pids.SPEED) else null
            if (rpm == null && spd == null) {
                if (t0 - lastOk > 20_000) throw NoCarException("Машина выключена")
            } else lastOk = t0
            val lph = fuel(e, rpm, cycle)

            if (Obd.watchO2.value || CatTest.state.value.running) readO2(e, rpm)

            if (slow.isNotEmpty()) {
                read(e, slow[slowIdx % slow.size])
                slowIdx++
            }
            if (Obd.watchAll.value && everything.isNotEmpty()) {
                repeat(2) {
                    read(e, everything[allIdx % everything.size])
                    allIdx++
                }
            }
            if (t0 - lastLevel > 10_000) {
                readLevel(e)
                lastLevel = t0
            }
            if (t0 - lastStatus > 30_000) {
                readStatus(e)
                lastStatus = t0
            }
            if (t0 - lastVolt > 60_000) {
                readVolt(e, first = false)
                lastVolt = t0
            }
            if (hasBattery) {
                val fast = Obd.watchBattery.value
                if (t0 - lastBattery > (if (fast) 1500L else 60_000L)) {
                    readBattery(e, t0 - lastIr > (if (fast) 10_000L else 300_000L))
                    if (t0 - lastIr > (if (fast) 10_000L else 300_000L)) lastIr = t0
                    lastBattery = t0
                }
            }
            socWatch(rpm)
            integrate(rpm, spd, lph)
            alerts()
            cycle++
            val el = System.currentTimeMillis() - t0
            if (el < 200) delay(200 - el)
        }
    }

    /** Расход, л/ч. Двигатель стоит — ноль (у гибрида это часто). */
    private fun fuel(e: Elm, rpm: Double?, cycle: Int): Double? {
        if (rpm != null && rpm < 50) return 0.0
        val k = Prefs.settings.value.fuelK
        if (info.has(Pids.FUEL_RATE)) read(e, Pids.FUEL_RATE)?.let { return it * k }
        if (!info.has(Pids.MAF)) return null
        val maf = read(e, Pids.MAF) ?: return null
        var lambda = 1.0
        if (info.has(Pids.LAMBDA_CMD)) {
            if (cycle % 3 == 0) read(e, Pids.LAMBDA_CMD)
            val l = values[Pids.LAMBDA_CMD]
            if (l != null) {
                if (l < 0.1 || l > 1.9) return 0.0   // отсечка топлива
                if (l in 0.6..1.4) lambda = l
            }
        }
        val gramsPerSec = maf / (14.7 * lambda)
        return gramsPerSec * 3600 / 745.0 * k
    }

    private fun readO2(e: Elm, rpm: Double?) {
        val up = HashMap<Int, Double>()
        val upLambda = HashMap<Int, Boolean>()
        val down = HashMap<Int, Double>()
        for (s in info.o2) {
            val pid = info.o2Pid[s.index] ?: continue
            val r = readRaw(e, pid) ?: continue
            val (v, isLambda) = when (pid) {
                in 0x34..0x3B -> Pids.wideLambdaI(r) to true
                in 0x24..0x2B -> Pids.wideLambdaV(r) to true
                else -> Pids.narrowVolt(r) to false
            }
            Samples.add("o2_${s.bank}_${s.pos}", v)
            if (s.pos == 1) {
                up[s.bank] = v
                upLambda[s.bank] = isLambda
            } else if (s.pos == 2) down[s.bank] = v
        }
        val fs = if (info.has(Pids.FUEL_SYS)) readRaw(e, Pids.FUEL_SYS)?.u8(2) else null
        if (fs != null) values[Pids.FUEL_SYS] = fs.toDouble()
        val trim = HashMap<Int, Double>()
        val s1 = values[Pids.STFT1]
        val l1 = values[Pids.LTFT1]
        val s2 = values[Pids.STFT2]
        val l2 = values[Pids.LTFT2]
        if (s1 != null && l1 != null) trim[1] = s1 + l1
        if (s2 != null && l2 != null) trim[2] = s2 + l2
        CatTest.record(rpm, fs, up, upLambda, down, trim)
    }

    private fun readLevel(e: Elm) {
        val tank = Prefs.settings.value.tankL
        var liters: Double? = null
        if (info.toyotaFuel) {
            liters = try {
                e.request("2129", "7C0", 1500).firstOrNull { it.u8(1) == 0x29 }?.let { it.u8(2) / 2.0 }
            } catch (_: NoCarException) {
                null
            }
            if (liters != null) levelSource = "датчик (литры)"
        }
        if (liters == null && info.has(Pids.FUEL_LEVEL)) {
            liters = read(e, Pids.FUEL_LEVEL)?.let { it / 100 * tank }
            if (liters != null) levelSource = "датчик (%)"
        }
        if (liters == null || liters < 0 || liters > tank * 1.3) return
        levels.addLast(liters)
        while (levels.size > 15) levels.removeFirst()
        level = levels.sorted()[levels.size / 2]
        Samples.add("level", liters)
        if (!fillChecked && levels.size >= 3) {
            fillChecked = true
            val l = level ?: return
            baseLevel = l
            acc?.let { if (it.tankStart == null) it.tankStart = l }
            Fuel.atTripStart(l)?.let { added ->
                Notify.info(
                    this, Notify.ID_FILL, "Заправка: примерно ${fmt(added, 0)} л",
                    "Укажи цену — стоимость поездок будет считаться по ней.", tab = 1, fill = true,
                )
            }
            return
        }
        // Заправка посреди поездки (заправлялся с включённой машиной): уровень вырос на 5+ л
        // относительно самого низкого за поездку.
        val l = level ?: return
        val b = baseLevel
        if (b == null || l < b) {
            baseLevel = l
        } else if (fillChecked && levels.size >= 5 && l - b >= 5.0) {
            baseLevel = l
            Fuel.midTripFill(l - b, l, acc?.fuelL ?: 0.0)
            Notify.info(
                this, Notify.ID_FILL, "Заправка: примерно ${fmt(l - b, 0)} л",
                "Укажи цену — стоимость поездок будет считаться по ней.", tab = 1, fill = true,
            )
        }
    }

    private var baseLevel: Double? = null

    private fun readStatus(e: Elm) {
        val r = readRaw(e, Pids.STATUS) ?: return
        val newMil = r.u8(2) and 0x80 != 0
        val count = r.u8(2) and 0x7F
        val changed = count != dtcCount || newMil != mil
        mil = newMil
        dtcCount = count
        if (!changed || !Prefs.settings.value.warnDtc) return
        // Посмотрим, нет ли новых кодов.
        val codes = mutableSetOf<String>()
        for (cmd in listOf("03", "07")) {
            try {
                e.request(cmd, null, 5000).forEach { rr ->
                    Dtcs.parse(rr, if (cmd == "03") com.gaifd.toyotaobd.obd.Dtc.Kind.STORED else com.gaifd.toyotaobd.obd.Dtc.Kind.PENDING, e.isCan)
                        .forEach { codes += it.code }
                }
            } catch (_: NoCarException) {
            }
        }
        val known = Prefs.knownDtcs
        val fresh = codes - known
        if (fresh.isNotEmpty()) {
            val first = fresh.first()
            Notify.alert(
                this, Notify.ID_DTC,
                if (fresh.size == 1) "Новая ошибка: $first" else "Новые ошибки: ${fresh.joinToString()}",
                com.gaifd.toyotaobd.obd.DtcBook.info(first).let { "${it.title}. ${it.urgency.label}." },
            )
        }
        Prefs.knownDtcs = codes
    }

    // ---------------- Поездка ----------------

    private fun startTrip() {
        synchronized(this) {
            if (acc == null) acc = Acc(System.currentTimeMillis())
            lastT = System.currentTimeMillis()
        }
    }

    private fun integrate(rpm: Double?, spdRaw: Double?, lph: Double?) {
        val now = System.currentTimeMillis()
        val s = Prefs.settings.value
        val speed = (spdRaw ?: 0.0) * s.speedK
        var inst: Double? = null
        val tripLive = synchronized(this) {
            val a = acc ?: Acc(now).also { acc = it }
            val dt = (now - lastT).coerceIn(0, 5000)
            lastT = now
            val dKm = speed * dt / 3_600_000.0
            val dL = (lph ?: 0.0) * dt / 3_600_000.0
            a.km += dKm
            a.fuelL += dL
            a.ms += dt
            if (speed > 2) a.movingMs += dt
            if (rpm != null && rpm < 100 && speed > 2) a.evKm += dKm
            if (speed > a.maxKmh) a.maxKmh = speed
            val b = (speed / 10).toInt().coerceIn(0, Trip.BUCKETS - 1)
            a.bKm[b] += dKm
            a.bL[b] += dL
            // Ровный ход: за последние 30 с скорость гуляет не больше чем на 6 км/ч.
            speedWin.addLast(now to speed)
            while (speedWin.isNotEmpty() && now - speedWin.first().first > 30_000) speedWin.removeFirst()
            val steady = speed > 20 && speedWin.size >= 10 && now - speedWin.first().first > 20_000 &&
                (speedWin.maxOf { it.second } - speedWin.minOf { it.second }) <= 6
            if (steady) {
                a.sKm[b] += dKm
                a.sL[b] += dL
            }
            if (now - a.lastPoint >= 5000) {
                a.lastPoint = now
                val ll = Gps.point(speed)
                if (ll != null) a.hasTrack = true
                Obd.addPoint(
                    com.gaifd.toyotaobd.data.TripPoint(
                        now, a.km, a.fuelL, speed, lph ?: 0.0, values[Pids.HV_SOC], values[Pids.COOLANT], ll?.first, ll?.second,
                    )
                )
            }
            TripLive(a.start, a.km, a.ms / 1000, a.movingMs / 1000, a.fuelL, a.evKm, a.maxKmh)
        }
        if (lph != null && speed > 3) inst = lph / speed * 100
        Samples.add("speed", speed, now)
        lph?.let { Samples.add("lph", it, now) }
        Obd.live.update {
            it.copy(
                rpm = rpm, speed = spdRaw?.let { v -> v * s.speedK }, fuelLph = lph, instL100 = inst,
                fuelLevelL = level, fuelLevelSource = levelSource, milOn = mil, dtcCount = dtcCount.coerceAtLeast(0),
                values = HashMap(values), trip = tripLive, volt12 = volt, updated = now,
            )
        }
        if (now - lastNotif > 15_000) {
            lastNotif = now
            val l100 = tripLive.l100?.let { " · ${fmt(it, 1)} л/100" } ?: ""
            Notify.updateService(this, "Поездка: ${fmt(tripLive.km, 1)} км$l100")
        }
    }

    private fun finishTrip() {
        val a = synchronized(this) {
            val x = acc
            acc = null
            x
        } ?: return
        val pts = Obd.takePoints()
        com.gaifd.toyotaobd.data.Riders.closeTrip(a.start, System.currentTimeMillis(), a.km, a.fuelL)
        Obd.goal.value = null
        speedWin.clear()
        if (a.km < 0.1 && a.fuelL < 0.05) {
            com.gaifd.toyotaobd.data.Riders.deleteTrip(a.start)
            return
        }
        val price = Fuel.price()
        val t = Trip(
            start = a.start, end = System.currentTimeMillis(), km = a.km, sec = a.ms / 1000,
            movingSec = a.movingMs / 1000, fuelL = a.fuelL, evKm = a.evKm, maxKmh = a.maxKmh,
            price = price, cost = a.fuelL * price, bucketKm = a.bKm.toList(), bucketL = a.bL.toList(),
            steadyKm = a.sKm.toList(), steadyL = a.sL.toList(), tankStart = a.tankStart, tankEnd = level,
            hasTrack = a.hasTrack,
        )
        com.gaifd.toyotaobd.data.Series.save(a.start, pts)
        Trips.add(t)
        Fuel.consume(a.fuelL, level)
        Obd.live.update { it.copy(trip = null) }
        if (a.km >= 1) {
            val l100 = t.l100?.let { " · ${fmt(it, 1)} л/100" } ?: ""
            Notify.info(
                this, Notify.ID_TRIP, "Поездка: ${fmt(t.km, 1)} км$l100",
                "${fmt(t.fuelL, 2)} л · ${fmt(t.cost, 0)} грн · средняя ${fmt(t.avgKmh, 0)} км/ч" +
                    if (t.evKm > 0.1) " · на электричестве ${(t.evShare * 100).toInt()}%" else "",
            )
        }
    }

    // ---------------- Предупреждения ----------------

    private fun alerts() {
        val s = Prefs.settings.value
        val c = values[Pids.COOLANT]
        if (c != null) {
            if (c >= 108 && !warnedCoolant && s.warnCoolant) {
                warnedCoolant = true
                Notify.alert(this, Notify.ID_COOLANT, "Перегрев двигателя: ${c.toInt()} °C", "Сбавь нагрузку. Когда остынет — проверь уровень антифриза и вентиляторы.", tab = 0)
            } else if (c < 100) warnedCoolant = false
        }
        val v = values[Pids.ECU_VOLT] ?: volt
        val moving = (Obd.live.value.speed ?: 0.0) > 5
        if (v != null && moving && s.warnVolt && !warnedVolt) {
            if (v < 12.4) lowVoltCount++ else lowVoltCount = 0
            if (lowVoltCount >= 5) {
                warnedVolt = true
                Notify.alert(
                    this, Notify.ID_VOLT, "Низкое напряжение на ходу: ${fmt(v, 1)} В",
                    "При работающей машине должно быть 13,5–14,5 В. Похоже, аккумулятор не заряжается (у гибрида — преобразователь DC/DC).", tab = 0,
                )
            }
        }
        val lvl = level
        if (lvl != null && s.warnFuel && !warnedFuel) {
            val t = acc
            val r = com.gaifd.toyotaobd.data.Range.of(lvl, t?.km ?: 0.0, t?.fuelL ?: 0.0)
            if (r != null && r.toLamp < 60) {
                warnedFuel = true
                Notify.alert(
                    this, Notify.ID_FUEL, "Мало топлива: ~${fmt(lvl, 0)} л",
                    "До лампочки ~${r.toLamp.toInt()} км, до сухого бака ~${r.toEmpty.toInt()} км.", tab = 0,
                )
            }
        }
    }

    private fun readBattery(e: Elm, withIr: Boolean) {
        val r = try {
            e.request("2181", com.gaifd.toyotaobd.obd.Battery.HDR, 1500).firstOrNull { it.u8(1) == 0x81 }
        } catch (_: NoCarException) {
            null
        } ?: return
        val blocks = com.gaifd.toyotaobd.obd.Battery.parseBlocks(r) ?: return
        val ir = if (withIr) {
            try {
                e.request("2195", com.gaifd.toyotaobd.obd.Battery.HDR, 1500).firstOrNull { it.u8(1) == 0x95 }
                    ?.let { com.gaifd.toyotaobd.obd.Battery.parseIr(it, blocks.size) }
            } catch (_: NoCarException) {
                null
            }
        } else Obd.live.value.battery?.ir
        val d = com.gaifd.toyotaobd.obd.BatteryData(blocks, ir)
        Samples.add("bat_spread", d.spread)
        Samples.add("bat_total", d.total)
        Obd.live.update { it.copy(battery = d) }
    }

    /** Запоминаем заряд при запусках двигателя и следим, чтобы он не падал ниже 35% без подзарядки. */
    private fun socWatch(rpm: Double?) {
        val soc = values[Pids.HV_SOC] ?: return
        val now = System.currentTimeMillis()
        val p = prevRpm
        val spd = Obd.live.value.speed ?: 0.0
        if (p != null && rpm != null && p < 100 && rpm > 600 && spd > 5 && soc in 25.0..70.0) {
            Prefs.engineStartSoc = Prefs.engineStartSoc + soc
        }
        prevRpm = rpm
        if (!Prefs.settings.value.warnSoc || warnedSoc) return
        if (soc < 35 && (rpm ?: 0.0) > 600) {
            if (lowSocSince == 0L) {
                lowSocSince = now
                lowSocAt = soc
            } else if (now - lowSocSince > 120_000) {
                if (soc <= lowSocAt) {
                    warnedSoc = true
                    Notify.alert(
                        this, Notify.ID_SOC, "Гибридная батарея не заряжается: ${soc.toInt()}%",
                        "Двигатель работает уже 2 минуты, а заряд не растёт. Если повторится — проверь батарею в «Диагностике».", tab = 2,
                    )
                } else {
                    lowSocSince = now
                    lowSocAt = soc
                }
            }
        } else lowSocSince = 0L
    }

    /** Раз в поездку — напомнить о просроченном ТО. */
    private fun maintCheck() {
        if (maintNotified) return
        maintNotified = true
        val odo = com.gaifd.toyotaobd.data.Maint.odo()
        val due = com.gaifd.toyotaobd.data.Maint.items.value.filter {
            com.gaifd.toyotaobd.data.Maint.status(it, odo).state == com.gaifd.toyotaobd.data.MaintState.OVERDUE
        }
        if (due.isNotEmpty()) {
            Notify.info(this, Notify.ID_MAINT, "Пора на ТО", due.joinToString { it.name }, tab = 0)
        }
    }

    private fun fmt(v: Double, d: Int) = if (d == 0) Math.round(v).toString() else "%.${d}f".format(v).replace('.', ',')
}
