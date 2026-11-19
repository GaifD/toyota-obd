package com.gaifd.toyotaobd.obd

/**
 * Результат одного теста самодиагностики (режим 06, CAN): значение и пределы, которые машина
 * сама с ним сравнивает. Сравнение — по «сырым» числам, поэтому не зависит от единиц.
 */
data class TestResult(
    val mid: Int,
    val tid: Int,
    val uas: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
    val ecu: String,
) {
    private val signed: Boolean get() = uas >= 0x80

    private fun s(v: Int) = if (signed && v >= 0x8000) v - 0x10000 else v

    val value: Double get() = Mode6.scale(uas, s(rawValue))
    val min: Double get() = Mode6.scale(uas, s(rawMin))
    val max: Double get() = Mode6.scale(uas, s(rawMax))
    val unit: String get() = Mode6.unit(uas)

    val passed: Boolean get() = s(rawValue) >= s(rawMin) && s(rawValue) <= s(rawMax)

    /** У теста задан только нижний предел (верхний — «бесконечность»). */
    val onlyMin: Boolean get() = (if (signed) rawMax == 0x7FFF else rawMax == 0xFFFF) || s(rawMax) <= s(rawMin)
    val onlyMax: Boolean get() = s(rawMin) == 0 || (signed && rawMin == 0x8000)

    /**
     * Насколько далеко от предела: 1 — «в два раза лучше порога», 0 — ровно на пороге, < 0 — не прошёл.
     * Для тестов с двумя пределами — расстояние до ближайшего, в долях половины диапазона.
     */
    val margin: Double
        get() {
            val v = s(rawValue).toDouble()
            val lo = s(rawMin).toDouble()
            val hi = s(rawMax).toDouble()
            return when {
                onlyMin && !onlyMax -> if (lo == 0.0) 1.0 else (v - lo) / kotlin.math.abs(lo)
                onlyMax || rawMin == 0 -> if (hi == 0.0) (if (v == 0.0) 1.0 else -1.0) else (hi - v) / kotlin.math.abs(hi)
                else -> {
                    val half = (hi - lo) / 2
                    if (half <= 0) 0.0 else minOf(v - lo, hi - v) / half
                }
            }
        }

    val name: String get() = Mode6.tidName(mid, tid)
}

object Mode6 {
    /** Разобрать ответ «06 XX»: записи по 9 байт — MID, TID, UAS, значение, минимум, максимум. */
    fun parse(r: EcuReply): List<TestResult> {
        val d = r.data
        val out = mutableListOf<TestResult>()
        var i = 1
        while (i + 8 < d.size) {
            out += TestResult(
                mid = d[i], tid = d[i + 1], uas = d[i + 2],
                rawValue = (d[i + 3] shl 8) or d[i + 4],
                rawMin = (d[i + 5] shl 8) or d[i + 6],
                rawMax = (d[i + 7] shl 8) or d[i + 8],
                ecu = r.ecu,
            )
            i += 9
        }
        return out
    }

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x08 -> {
            val i = mid - 1
            "Датчик кислорода: ряд ${i / 4 + 1}, датчик ${i % 4 + 1}"
        }
        in 0x09..0x10 -> "Датчик кислорода ${mid - 8}"
        0x21 -> "Катализатор, ряд 1"
        0x22 -> "Катализатор, ряд 2"
        0x23 -> "Катализатор, ряд 3"
        0x24 -> "Катализатор, ряд 4"
        in 0x31..0x34 -> "Система EGR"
        in 0x35..0x38 -> "Изменение фаз (VVT), ряд ${mid - 0x34}"
        0x39 -> "EVAP: отключено"
        0x3A -> "EVAP: утечка 0,090\""
        0x3B -> "EVAP: утечка 0,040\""
        0x3C -> "EVAP: утечка 0,020\""
        0x3D -> "EVAP: продувка"
        in 0x41..0x48 -> {
            val i = mid - 0x41
            "Подогрев датчика кислорода: ряд ${i / 4 + 1}, датчик ${i % 4 + 1}"
        }
        in 0x61..0x64 -> "Подогрев катализатора, ряд ${mid - 0x60}"
        in 0x71..0x74 -> "Вторичный воздух ${mid - 0x70}"
        in 0x81..0x84 -> "Топливная система, ряд ${mid - 0x80}"
        0xA1 -> "Пропуски зажигания: общие"
        in 0xA2..0xAD -> "Пропуски зажигания: цилиндр ${mid - 0xA1}"
        else -> "Тест %02X".format(mid)
    }

    fun tidName(mid: Int, tid: Int): String = when {
        mid in 0xA1..0xAD && tid == 0x0B -> "Пропуски: среднее за 10 поездок"
        mid in 0xA1..0xAD && tid == 0x0C -> "Пропуски: эта/последняя поездка"
        mid in 0x01..0x10 && tid == 0x01 -> "Порог «богато → бедно»"
        mid in 0x01..0x10 && tid == 0x02 -> "Порог «бедно → богато»"
        mid in 0x01..0x10 && tid == 0x05 -> "Время переключения «богато → бедно»"
        mid in 0x01..0x10 && tid == 0x06 -> "Время переключения «бедно → богато»"
        mid in 0x01..0x10 && tid == 0x07 -> "Минимальное напряжение"
        mid in 0x01..0x10 && tid == 0x08 -> "Максимальное напряжение"
        mid in 0x21..0x24 -> "Запас кислорода / эффективность (тест %02X)".format(tid)
        tid >= 0x80 -> "Тест производителя %02X".format(tid)
        else -> "Тест %02X".format(tid)
    }

    /** Единицы и масштаб по стандарту (основные). Неизвестные — «как есть». */
    fun scale(uas: Int, v: Int): Double = when (uas and 0x7F) {
        0x01 -> v.toDouble()
        0x02 -> v * 0.1
        0x03 -> v * 0.01
        0x04 -> v * 0.001
        0x05 -> v * 0.0000305
        0x06 -> v * 0.000305
        0x07 -> v * 0.25
        0x08 -> v * 0.01
        0x09 -> v.toDouble()
        0x0A -> v * 0.122
        0x0B -> v * 0.001
        0x0C -> v * 0.01
        0x0D -> v * 0.00390625
        0x0E -> v * 0.001
        0x0F -> v * 0.01
        0x10 -> v.toDouble()
        0x11 -> v * 100.0
        0x12 -> v.toDouble()
        0x13 -> v.toDouble()
        0x14 -> v.toDouble()
        0x15 -> v.toDouble()
        0x16 -> v * 0.1 - 40
        0x17 -> v * 0.01
        0x18 -> v * 0.0117
        0x19 -> v * 0.079
        0x1A -> v.toDouble()
        0x1B -> v * 10.0
        0x1C -> v * 0.01
        0x1D -> v * 0.5
        0x1E -> v * 0.0000305
        0x1F -> v * 0.05
        0x20 -> v * 0.00390625
        0x21 -> v * 0.001
        0x22 -> v.toDouble()
        0x23 -> v * 0.01
        0x24 -> v.toDouble()
        0x25 -> v.toDouble()
        0x26 -> v * 0.0001
        0x27 -> v * 0.01
        0x28 -> v.toDouble()
        0x29 -> v * 0.25
        0x2A -> v * 0.001
        0x2B -> v.toDouble()
        0x2C -> v * 0.01
        0x2D -> v * 0.01
        0x2E -> v.toDouble()
        0x2F -> v * 0.01
        0x30 -> v * 0.001526
        0x31 -> v * 0.001
        0x32 -> v * 0.0000305
        0x33 -> v * 0.00024414
        0x34 -> v.toDouble()
        0x35 -> v * 0.01
        0x36 -> v * 0.01
        0x37 -> v * 0.01
        0x38 -> v.toDouble()
        0x39 -> v * 0.01
        0x3A -> v * 0.001
        0x3B -> v * 0.0001
        0x3C -> v * 0.01
        0x3D -> v * 0.01
        0x3E -> v * 0.00006103516
        0x3F -> v * 0.01
        else -> v.toDouble()
    }

    fun unit(uas: Int): String = when (uas and 0x7F) {
        0x07 -> "об/мин"
        0x08, 0x09 -> "км/ч"
        0x0A -> "мВ"
        0x0B, 0x0C -> "В"
        0x0D -> "мА"
        0x0E, 0x0F -> "А"
        0x10, 0x11 -> "мс"
        0x12 -> "с"
        0x13 -> "мОм"
        0x14 -> "Ом"
        0x15 -> "кОм"
        0x16 -> "°C"
        0x17, 0x18, 0x19 -> "кПа"
        0x1E, 0x32 -> "λ"
        0x24 -> "раз"
        0x25 -> "км"
        0x27 -> "г/с"
        0x2F -> "%"
        else -> ""
    }

    /** Список поддерживаемых тестов из ответов «06 00», «06 20»… */
    fun supported(base: Int, r: EcuReply): Set<Int> {
        val s = mutableSetOf<Int>()
        for (byte in 0 until 4) {
            val v = r.u8(2 + byte)
            for (bit in 0 until 8) if (v and (0x80 shr bit) != 0) s += base + byte * 8 + bit + 1
        }
        return s
    }
}
