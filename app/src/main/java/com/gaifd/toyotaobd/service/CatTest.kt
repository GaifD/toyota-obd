package com.gaifd.toyotaobd.service

import kotlinx.coroutines.flow.MutableStateFlow

/** Итог живой записи по одному ряду цилиндров. */
data class BankLive(
    val bank: Int,
    val validSec: Double,
    val upSwitches: Int,
    val downSwitches: Int,
    val downMin: Double,
    val downMax: Double,
    val downMean: Double,
    val upIsLambda: Boolean,
    val samples: Int,
) {
    val downPerMin: Double get() = if (validSec > 0) downSwitches / (validSec / 60) else 0.0
    val downRange: Double get() = downMax - downMin
}

data class CatState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val validSec: Double = 0.0,
    val banks: List<BankLive> = emptyList(),
    val trims: Map<Int, Double> = emptyMap(),   // ряд → суммарная коррекция, %
)

/**
 * Запись датчиков кислорода для проверки катализатора. Берём только моменты, когда двигатель
 * работает и смесь в замкнутом цикле — у гибрида двигатель часто стоит, это не считается.
 */
object CatTest {
    const val TARGET_SEC = 180.0

    val state = MutableStateFlow(CatState())

    private class Series {
        val up = ArrayList<Double>()
        val down = ArrayList<Double>()
        var upIsLambda = false
    }

    private val banks = HashMap<Int, Series>()
    private val trims = HashMap<Int, ArrayList<Double>>()
    private var lastT = 0L
    private var valid = 0.0

    @Synchronized
    fun start() {
        banks.clear()
        trims.clear()
        valid = 0.0
        lastT = 0
        state.value = CatState(running = true, startedAt = System.currentTimeMillis())
        Obd.watchO2.value = true
    }

    @Synchronized
    fun stop() {
        state.value = compute().copy(running = false)
    }

    /**
     * Один срез: обороты, режим топливной системы (2 — замкнутый цикл), датчики по рядам и коррекции.
     * up/down — значения датчиков до/после катализатора по номеру ряда.
     */
    @Synchronized
    fun record(
        rpm: Double?,
        fuelSys: Int?,
        up: Map<Int, Double>,
        upLambda: Map<Int, Boolean>,
        down: Map<Int, Double>,
        trim: Map<Int, Double>,
    ) {
        if (!state.value.running) return
        val now = System.currentTimeMillis()
        val dt = if (lastT == 0L) 0.0 else ((now - lastT) / 1000.0).coerceAtMost(3.0)
        lastT = now
        val engineOn = (rpm ?: 0.0) > 400
        val closed = fuelSys == null || fuelSys == 2
        if (!engineOn || !closed) {
            publish()
            return
        }
        valid += dt
        for ((b, v) in up) banks.getOrPut(b) { Series() }.let { it.up.add(v); it.upIsLambda = upLambda[b] == true }
        for ((b, v) in down) banks.getOrPut(b) { Series() }.down.add(v)
        for ((b, v) in trim) trims.getOrPut(b) { ArrayList() }.add(v)
        publish()
        if (valid >= TARGET_SEC) state.value = compute().copy(running = false)
    }

    private fun publish() {
        state.value = compute()
    }

    private fun switches(xs: List<Double>, mid: Double, hyst: Double): Int {
        var st = 0
        var n = 0
        for (x in xs) {
            if (x > mid + hyst) {
                if (st == -1) n++
                st = 1
            } else if (x < mid - hyst) {
                if (st == 1) n++
                st = -1
            }
        }
        return n
    }

    private fun compute(): CatState {
        val out = banks.entries.sortedBy { it.key }.map { (b, s) ->
            val upMid = if (s.upIsLambda) (if (s.up.isEmpty()) 1.0 else s.up.average()) else 0.45
            val upH = if (s.upIsLambda) 0.008 else 0.1
            BankLive(
                bank = b,
                validSec = valid,
                upSwitches = switches(s.up, upMid, upH),
                downSwitches = switches(s.down, 0.45, 0.12),
                downMin = s.down.minOrNull() ?: 0.0,
                downMax = s.down.maxOrNull() ?: 0.0,
                downMean = if (s.down.isEmpty()) 0.0 else s.down.average(),
                upIsLambda = s.upIsLambda,
                samples = s.down.size,
            )
        }
        val t = trims.mapValues { it.value.average() }
        return state.value.copy(validSec = valid, banks = out, trims = t)
    }
}
