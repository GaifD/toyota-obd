package com.gaifd.toyotaobd.service

import com.gaifd.toyotaobd.obd.Dtc
import com.gaifd.toyotaobd.obd.Mode6
import com.gaifd.toyotaobd.obd.TestResult
import kotlin.math.abs

enum class Level { BAD, WARN, OK, INFO }

data class Finding(val level: Level, val title: String, val text: String)

data class Verdict(val headline: String, val findings: List<Finding>)

/**
 * Разбор причин ошибки катализатора. Идём от дешёвого и частого к дорогому:
 * пропуски → датчик → смесь/утечки → сам катализатор.
 */
object Diagnosis {

    fun analyze(
        dtcs: List<Dtc>,
        tests: List<TestResult>?,
        live: CatState?,
        trimsNow: Map<Int, Double>,
    ): Verdict {
        val f = mutableListOf<Finding>()
        val codes = dtcs.map { it.code }.toSet()
        val catBanks = buildSet {
            if (codes.any { it in setOf("P0420", "P0421", "P0422") }) add(1)
            if (codes.any { it in setOf("P0430", "P0431", "P0432") }) add(2)
        }
        var cause: String? = null

        if (catBanks.isNotEmpty()) {
            f += Finding(
                Level.INFO, "Ошибка по катализатору: ряд ${catBanks.joinToString(" и ")}",
                "Машина считает, что катализатор этого ряда плохо держит кислород. Ниже — проверка, сам ли он виноват."
            )
        }

        // 1. Пропуски зажигания. Единичные счёты за поездку — норма (особенно у гибрида с частыми пусками);
        // тревожно, когда растёт среднее за 10 поездок или их десятки за одну.
        val misfireCodes = codes.filter { it.startsWith("P030") }
        val avg10 = mutableMapOf<Int, Int>()
        val cur = mutableMapOf<Int, Int>()
        tests?.filter { it.mid in 0xA2..0xAD }?.forEach {
            val cyl = it.mid - 0xA1
            if (it.tid == 0x0B) avg10[cyl] = maxOf(avg10[cyl] ?: 0, it.rawValue)
            if (it.tid == 0x0C) cur[cyl] = maxOf(cur[cyl] ?: 0, it.rawValue)
        }
        val cyls = (avg10.keys + cur.keys).toSortedSet()
        val badCyl = cyls.filter { (avg10[it] ?: 0) >= 5 || (cur[it] ?: 0) >= 50 }
        if (misfireCodes.isNotEmpty() || badCyl.isNotEmpty()) {
            val list = badCyl.joinToString { "цил. $it: ${cur[it] ?: 0} за поездку, ${avg10[it] ?: 0} в среднем" }
            f += Finding(
                Level.BAD, "Есть пропуски зажигания",
                (if (list.isNotEmpty()) "$list. " else "") +
                    "Несгоревшее топливо догорает в катализаторе и перегревает его. Сначала свечи и катушки " +
                    "(можно переставить катушку на другой цилиндр и посмотреть, переедут ли пропуски)."
            )
            cause = cause ?: "пропуски зажигания"
        } else if (cyls.isNotEmpty()) {
            val total = cur.values.sum()
            f += Finding(
                Level.OK, "Пропусков зажигания нет",
                if (total > 0) "За поездку единичные счёты ($total на все цилиндры) при нулевом среднем — это норма, не неисправность."
                else "Счётчики по всем цилиндрам в норме."
            )
        }

        // 2. Ошибки датчиков кислорода — датчик дешевле катализатора.
        val sensorCodes = codes.filter {
            it in setOf("P0136", "P0137", "P0138", "P0139", "P0140", "P0141", "P0156", "P0157", "P0158", "P0159", "P0160", "P0161")
        }
        if (sensorCodes.isNotEmpty()) {
            f += Finding(
                Level.BAD, "Ошибка датчика после катализатора (${sensorCodes.joinToString()})",
                "Датчик или его проводка неисправны — катализатор оценивается по нему, так что ошибка катализатора " +
                    "может быть ложной. Начинать с датчика: он в разы дешевле."
            )
            cause = cause ?: "датчик кислорода после катализатора"
        }
        val afCodes = codes.filter { it.startsWith("P213") || it in setOf("P2195", "P2196", "P2197", "P2198", "P2237", "P2240", "P2A00", "P2A03") }
        if (afCodes.isNotEmpty()) {
            f += Finding(
                Level.WARN, "Ошибка датчика A/F до катализатора (${afCodes.joinToString()})",
                "Если первый датчик врёт, смесь уходит в сторону, и катализатор работает хуже."
            )
            cause = cause ?: "датчик A/F до катализатора"
        }

        // 3. Живая запись датчиков.
        live?.banks?.forEach { b ->
            if (b.samples < 20 || b.validSec < 40) return@forEach
            val r = b.downRange
            when {
                r < 0.06 && b.downMean < 0.15 -> {
                    f += Finding(
                        Level.BAD, "Ряд ${b.bank}: датчик после катализатора всё время «бедно» (${v(b.downMean)} В)",
                        "Так бывает при подсосе воздуха в выпуск перед датчиком (прогар прокладки, трещина) " +
                            "или если датчик умер. Проверить выпуск на утечки, затем датчик."
                    )
                    cause = cause ?: "утечка выхлопа или мёртвый датчик после катализатора (ряд ${b.bank})"
                }
                r < 0.06 && b.downMean > 0.9 -> {
                    f += Finding(
                        Level.WARN, "Ряд ${b.bank}: датчик после катализатора всё время «богато» (${v(b.downMean)} В)",
                        "Либо смесь богатая (смотри коррекции), либо датчик залип."
                    )
                }
                r < 0.08 -> {
                    f += Finding(
                        Level.BAD, "Ряд ${b.bank}: датчик после катализатора почти не меняется (${v(b.downMin)}–${v(b.downMax)} В)",
                        "Живой датчик хоть немного «дышит». Ровная линия посередине — признак мёртвого датчика или обрыва."
                    )
                    cause = cause ?: "датчик после катализатора (ряд ${b.bank})"
                }
                b.downPerMin >= 8 && r > 0.5 -> {
                    f += Finding(
                        Level.BAD, "Ряд ${b.bank}: катализатор не держит кислород",
                        "Датчик после катализатора скачет так же, как до него (${b.downSwitches} переключений за " +
                            "${(b.validSec / 60).toInt().coerceAtLeast(1)} мин). Живой катализатор сглаживает сигнал почти в ровную линию."
                    )
                    cause = cause ?: "износ катализатора ряда ${b.bank}"
                }
                b.downPerMin >= 4 && r > 0.4 -> {
                    f += Finding(
                        Level.WARN, "Ряд ${b.bank}: катализатор на грани",
                        "Сигнал после катализатора начинает повторять сигнал до него (${b.downSwitches} переключений). " +
                            "Катализатор ослаб, но ещё работает."
                    )
                    cause = cause ?: "ослабевший катализатор ряда ${b.bank}"
                }
                else -> f += Finding(
                    Level.OK, "Ряд ${b.bank}: по живым данным катализатор работает",
                    "Сигнал после катализатора ровный (${v(b.downMin)}–${v(b.downMax)} В, ${b.downSwitches} переключений). " +
                        "Если ошибка всё равно есть — возможны редкие утечки выхлопа или ленивый датчик."
                )
            }
        }

        // 4. Коррекции смеси по рядам.
        val trims = live?.trims?.takeIf { it.isNotEmpty() } ?: trimsNow
        for ((bank, t) in trims.toSortedMap()) {
            when {
                t > 10 -> {
                    f += Finding(
                        Level.WARN, "Ряд $bank: смесь бедная, ЭБУ добавляет ${"%+.0f".format(t)}%",
                        "Подсос воздуха (впуск, вакуумные трубки), утечка выхлопа до первого датчика или слабая подача топлива."
                    )
                    cause = cause ?: "бедная смесь на ряду $bank"
                }
                t < -10 -> {
                    f += Finding(
                        Level.WARN, "Ряд $bank: смесь богатая, ЭБУ убирает ${"%.0f".format(-t)}%",
                        "Богатая смесь перегревает катализатор. Подтекающие форсунки, давление топлива, датчик A/F."
                    )
                    cause = cause ?: "богатая смесь на ряду $bank"
                }
            }
        }
        if (trims.size >= 2) {
            val vals = trims.values.toList()
            val d = abs(vals[0] - vals[1])
            if (d > 8) f += Finding(
                Level.WARN, "Ряды отличаются по смеси на ${"%.0f".format(d)}%",
                "Проблема на одном ряду: подсос воздуха с одной стороны или утечка выхлопа до датчика этого ряда."
            )
            else if (trims.values.all { abs(it) <= 10 }) f += Finding(Level.OK, "Смесь в норме", "Коррекции по обоим рядам в пределах ±10%.")
        }

        // 5. Самотест катализатора из ЭБУ.
        tests?.filter { it.mid in 0x21..0x24 }?.groupBy { it.mid }?.forEach { (mid, list) ->
            val bank = mid - 0x20
            val worst = list.minByOrNull { it.margin } ?: return@forEach
            val title = Mode6.midName(mid)
            when {
                list.any { !it.passed } -> {
                    f += Finding(
                        Level.BAD, "$title: самотест ЭБУ не пройден",
                        "Значение ${num(worst.value)} при пределе ${limit(worst)}. Так машина и ставит ошибку."
                    )
                    cause = cause ?: "износ катализатора ряда $bank"
                }
                worst.margin < 0.15 -> f += Finding(
                    Level.WARN, "$title: проходит на грани",
                    "Значение ${num(worst.value)} при пределе ${limit(worst)} — запас меньше 15%."
                )
                else -> f += Finding(
                    Level.OK, "$title: самотест пройден",
                    "Значение ${num(worst.value)} при пределе ${limit(worst)}, запас ${(worst.margin * 100).toInt().coerceAtMost(999)}%."
                )
            }
        }
        if (tests != null && tests.none { it.mid in 0x21..0x24 }) {
            f += Finding(
                Level.INFO, "Самотест катализатора ещё не выполнялся",
                "После сброса ошибок или отключения аккумулятора машине нужно несколько поездок с прогретым двигателем, " +
                    "чтобы снова проверить катализатор."
            )
        }

        val headline = when {
            cause != null -> "Вероятнее всего: $cause"
            f.isEmpty() -> "Данных пока нет — запусти проверку"
            f.none { it.level == Level.BAD || it.level == Level.WARN } -> "Явных неисправностей не найдено"
            else -> "Есть на что посмотреть — подробности ниже"
        }
        val order = listOf(Level.BAD, Level.WARN, Level.INFO, Level.OK)
        return Verdict(headline, f.sortedBy { order.indexOf(it.level) })
    }

    private fun v(x: Double) = "%.2f".format(x).replace('.', ',')
    private fun num(x: Double): String =
        (if (abs(x) >= 100) "%.0f".format(x) else "%.3f".format(x).trimEnd('0').trimEnd('.', ',')).replace('.', ',')

    private fun limit(t: TestResult): String = when {
        t.onlyMin && !t.onlyMax -> "не ниже ${num(t.min)}"
        t.onlyMax -> "не выше ${num(t.max)}"
        else -> "${num(t.min)}…${num(t.max)}"
    } + if (t.unit.isNotEmpty()) " ${t.unit}" else ""
}
