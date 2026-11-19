package com.gaifd.toyotaobd.obd

/**
 * Блоки гибридной батареи Toyota (Highlander Hybrid 2014–2019 и родственные).
 * Найдено сканером: блок гибридной системы 7E2, режим 21:
 *  2181 — напряжения блоков (по 2 байта, ×79,99/65535 В),
 *  2195 — по байту на блок, похоже на внутреннее сопротивление, мОм.
 * Это гипотеза до проверки под нагрузкой — на экране помечено «проверяем».
 */
data class BatteryData(
    val blocks: List<Double>,
    val ir: List<Int>?,
    val time: Long = System.currentTimeMillis(),
) {
    val min: Double get() = blocks.minOrNull() ?: 0.0
    val max: Double get() = blocks.maxOrNull() ?: 0.0
    val spread: Double get() = max - min
    val total: Double get() = blocks.sum()
    val weakest: Int get() = blocks.indices.minByOrNull { blocks[it] } ?: 0

    /** Оценка по разбросу: до 0,3 В — норма, до 0,5 — присмотреться, выше — проблема. */
    val level: Int get() = when {
        spread < 0.3 -> 0
        spread < 0.5 -> 1
        else -> 2
    }
}

object Battery {
    const val HDR = "7E2"

    fun parseBlocks(r: EcuReply): List<Double>? {
        val d = r.data
        if (d.size < 4 || d[1] != 0x81) return null
        // После 61 81 идут пары байтов; последние 4 байта — не блоки (служебные).
        val pairs = (d.size - 2 - 4) / 2
        if (pairs < 6) return null
        val v = List(pairs) { i -> ((d[2 + i * 2] shl 8) or d[3 + i * 2]) * 79.99 / 65535 }
        return if (v.all { it in 10.0..26.0 }) v else null
    }

    fun parseIr(r: EcuReply, count: Int): List<Int>? {
        val d = r.data
        if (d.size < 2 + count || d[1] != 0x95) return null
        return List(count) { d[2 + it] }
    }
}
