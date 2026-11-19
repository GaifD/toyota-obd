package com.gaifd.toyotaobd.obd

/** Стандартный параметр режима 01: номер, название, единица, расшифровка байтов. */
class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val decimals: Int,
    val decode: (EcuReply) -> Double?,
) {
    val key: String get() = "01%02X".format(id)
}

/** Датчик кислорода: где стоит и как опрашивается. */
data class O2Sensor(val index: Int, val bank: Int, val pos: Int) {
    val label: String get() = "Ряд $bank, датчик $pos" + if (pos == 1) " (до катализатора)" else " (после)"
}

object Pids {
    // Байты данных начинаются после «41 XX»: A = u8(2), B = u8(3)…
    private fun a(r: EcuReply) = r.u8(2).toDouble()
    private fun ab(r: EcuReply) = r.u16(2).toDouble()
    private fun trim(r: EcuReply) = (r.u8(2) - 128) * 100.0 / 128

    const val STATUS = 0x01
    const val FUEL_SYS = 0x03
    const val LOAD = 0x04
    const val COOLANT = 0x05
    const val STFT1 = 0x06
    const val LTFT1 = 0x07
    const val STFT2 = 0x08
    const val LTFT2 = 0x09
    const val MAP = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val TIMING = 0x0E
    const val IAT = 0x0F
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val O2_PRESENT = 0x13
    const val O2_PRESENT4 = 0x1D
    const val RUNTIME = 0x1F
    const val DIST_MIL = 0x21
    const val FUEL_LEVEL = 0x2F
    const val DIST_CLR = 0x31
    const val BARO = 0x33
    const val CAT_T1 = 0x3C
    const val CAT_T2 = 0x3D
    const val MONITOR_DRIVE = 0x41
    const val ECU_VOLT = 0x42
    const val ABS_LOAD = 0x43
    const val LAMBDA_CMD = 0x44
    const val REL_THROTTLE = 0x45
    const val AMBIENT = 0x46
    const val HV_SOC = 0x5B
    const val OIL = 0x5C
    const val FUEL_RATE = 0x5E

    val all: Map<Int, Pid> = listOf(
        Pid(FUEL_SYS, "Топливная система", "", 0) { a(it) },
        Pid(LOAD, "Нагрузка двигателя", "%", 0) { a(it) * 100 / 255 },
        Pid(COOLANT, "Температура ОЖ", "°C", 0) { a(it) - 40 },
        Pid(STFT1, "Краткосрочная коррекция, ряд 1", "%", 1) { trim(it) },
        Pid(LTFT1, "Долгосрочная коррекция, ряд 1", "%", 1) { trim(it) },
        Pid(STFT2, "Краткосрочная коррекция, ряд 2", "%", 1) { trim(it) },
        Pid(LTFT2, "Долгосрочная коррекция, ряд 2", "%", 1) { trim(it) },
        Pid(MAP, "Давление во впуске", "кПа", 0) { a(it) },
        Pid(RPM, "Обороты", "об/мин", 0) { ab(it) / 4 },
        Pid(SPEED, "Скорость (ЭБУ)", "км/ч", 0) { a(it) },
        Pid(TIMING, "Угол опережения", "°", 1) { a(it) / 2 - 64 },
        Pid(IAT, "Температура воздуха на впуске", "°C", 0) { a(it) - 40 },
        Pid(MAF, "Расход воздуха (MAF)", "г/с", 2) { ab(it) / 100 },
        Pid(THROTTLE, "Дроссель", "%", 0) { a(it) * 100 / 255 },
        Pid(RUNTIME, "С момента пуска", "с", 0) { ab(it) },
        Pid(DIST_MIL, "Пробег с горящим Check", "км", 0) { ab(it) },
        Pid(FUEL_LEVEL, "Уровень топлива", "%", 0) { a(it) * 100 / 255 },
        Pid(DIST_CLR, "Пробег после сброса ошибок", "км", 0) { ab(it) },
        Pid(BARO, "Атмосферное давление", "кПа", 0) { a(it) },
        Pid(CAT_T1, "Температура катализатора, ряд 1", "°C", 0) { ab(it) / 10 - 40 },
        Pid(CAT_T2, "Температура катализатора, ряд 2", "°C", 0) { ab(it) / 10 - 40 },
        Pid(ECU_VOLT, "Напряжение бортсети (ЭБУ)", "В", 2) { ab(it) / 1000 },
        Pid(ABS_LOAD, "Абсолютная нагрузка", "%", 0) { ab(it) * 100 / 255 },
        Pid(LAMBDA_CMD, "Заданная лямбда", "λ", 3) { ab(it) / 32768 },
        Pid(REL_THROTTLE, "Педаль/дроссель относительно", "%", 0) { a(it) * 100 / 255 },
        Pid(AMBIENT, "Температура снаружи", "°C", 0) { a(it) - 40 },
        Pid(HV_SOC, "Заряд гибридной батареи", "%", 0) { a(it) * 100 / 255 },
        Pid(OIL, "Температура масла", "°C", 0) { a(it) - 40 },
        Pid(FUEL_RATE, "Расход топлива (ЭБУ)", "л/ч", 2) { ab(it) / 20 },
        Pid(0x4D, "Время с горящим Check", "мин", 0) { ab(it) },
        Pid(0x4E, "Время после сброса ошибок", "мин", 0) { ab(it) },
    ).associateBy { it.id }

    /** Узкополосный датчик (0x14…0x1B): напряжение, В. */
    fun narrowVolt(r: EcuReply): Double = r.u8(2) / 200.0

    /** Широкополосный (0x24…0x2B): лямбда. */
    fun wideLambdaV(r: EcuReply): Double = r.u16(2) * 2.0 / 65536

    /** Широкополосный по току (0x34…0x3B): лямбда. Toyota отдаёт датчик A/F именно так. */
    fun wideLambdaI(r: EcuReply): Double = r.u16(2) / 32768.0

    /** Какие датчики кислорода есть. */
    fun o2Sensors(map13: Int?, map1d: Int?): List<O2Sensor> {
        val list = mutableListOf<O2Sensor>()
        if (map13 != null) {
            for (i in 0 until 8) if (map13 and (1 shl i) != 0) list += O2Sensor(i, i / 4 + 1, i % 4 + 1)
        } else if (map1d != null) {
            for (i in 0 until 8) if (map1d and (1 shl i) != 0) list += O2Sensor(i, i / 2 + 1, i % 2 + 1)
        }
        return list
    }

    fun fuelSystem(code: Int): String = when (code) {
        1 -> "холодный, без обратной связи"
        2 -> "замкнутый цикл (норма)"
        4 -> "разомкнут: нагрузка или отсечка"
        8 -> "разомкнут из-за неисправности"
        16 -> "замкнутый, но датчик неисправен"
        0 -> "двигатель не работает"
        else -> "код $code"
    }

    /** Битовая карта поддерживаемых PID из ответов 0100, 0120… */
    fun supported(base: Int, r: EcuReply): Set<Int> {
        val s = mutableSetOf<Int>()
        for (byte in 0 until 4) {
            val v = r.u8(2 + byte)
            for (bit in 0 until 8) if (v and (0x80 shr bit) != 0) s += base + byte * 8 + bit + 1
        }
        return s
    }

    fun format(v: Double, decimals: Int): String = when (decimals) {
        0 -> Math.round(v).toString()
        else -> "%.${decimals}f".format(v).replace('.', ',')
    }
}
