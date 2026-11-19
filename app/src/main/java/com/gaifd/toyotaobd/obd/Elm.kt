package com.gaifd.toyotaobd.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Адаптер не отвечает или связь порвалась — нужно переподключаться. */
class ElmException(msg: String) : Exception(msg)

/** Адаптер жив, но машина молчит (зажигание выключено). */
class NoCarException(msg: String) : Exception(msg)

/** Ответ одного блока управления: его адрес (7E8, 7EA…) и данные без служебных байтов. */
class EcuReply(val ecu: String, val data: IntArray) {
    fun u8(i: Int): Int = data.getOrElse(i) { 0 }
    fun u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
    override fun toString(): String = ecu + ": " + data.joinToString(" ") { "%02X".format(it) }
}

/**
 * Разговор с ELM327 по Bluetooth: команда → ответ до приглашения «>».
 * Заголовки (ATH1) включены, чтобы видеть, какой блок ответил, и собирать длинные ответы (ISO-TP).
 */
class Elm private constructor(private val socket: BluetoothSocket) {
    private val inp: InputStream = socket.inputStream
    private val out: OutputStream = socket.outputStream

    var version: String = ""
        private set
    var protocol: Int = 0
        private set
    val isCan: Boolean get() = protocol in 6..9
    private val is29bit: Boolean get() = protocol == 7 || protocol == 9
    private var header: String? = null

    /** Последние команды и ответы — для экрана «Журнал адаптера». */
    val journal = ArrayDeque<String>()

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Открыть Bluetooth-соединение. Китайские адаптеры иногда принимают только «небезопасный» сокет. */
        @SuppressLint("MissingPermission")
        fun connect(mac: String): Elm {
            val bt = BluetoothAdapter.getDefaultAdapter() ?: throw ElmException("На телефоне нет Bluetooth")
            if (!bt.isEnabled) throw ElmException("Bluetooth выключен")
            val dev = bt.getRemoteDevice(mac)
            try {
                bt.cancelDiscovery()
            } catch (_: Exception) {
            }
            val attempts = listOf<() -> BluetoothSocket>(
                { dev.createRfcommSocketToServiceRecord(SPP) },
                { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
                {
                    val m = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(dev, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for (make in attempts) {
                var s: BluetoothSocket? = null
                try {
                    s = make()
                    s.connect()
                    return Elm(s)
                } catch (e: Exception) {
                    last = e
                    try {
                        s?.close()
                    } catch (_: Exception) {
                    }
                }
            }
            throw ElmException("Не удалось подключиться к адаптеру: ${last?.message ?: "нет ответа"}")
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun note(s: String) {
        synchronized(journal) {
            journal.addLast(s)
            while (journal.size > 200) journal.removeFirst()
        }
    }

    /** Отправить команду и дождаться «>». */
    @Synchronized
    fun raw(cmd: String, timeoutMs: Long = 3000): String {
        try {
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray())
            out.flush()
        } catch (e: Exception) {
            throw ElmException("Связь с адаптером порвалась")
        }
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                if (inp.available() > 0) {
                    val c = inp.read()
                    if (c < 0) throw ElmException("Связь с адаптером порвалась")
                    if (c == '>'.code) break
                    sb.append(c.toChar())
                } else {
                    if (System.currentTimeMillis() > deadline) throw ElmException("Адаптер не ответил на $cmd")
                    Thread.sleep(4)
                }
            }
        } catch (e: ElmException) {
            throw e
        } catch (e: Exception) {
            throw ElmException("Связь с адаптером порвалась")
        }
        val r = sb.toString().replace("\u0000", "").trim()
        note("> $cmd\n${r.replace('\r', '\n').trim()}")
        return r
    }

    /** Настройка адаптера (машина может быть ещё выключена). */
    fun setupAdapter() {
        raw("ATZ", 5000)
        Thread.sleep(300)
        raw("ATE0")
        val i = raw("ATI")
        version = i.lines().map { it.trim() }.firstOrNull { it.contains("ELM", true) } ?: i.trim()
        raw("ATL0")
        raw("ATS1")
        raw("ATH1")
        raw("ATAT1")
        raw("ATSP0")
    }

    /** Найти протокол машины. Бросает NoCarException, если машина молчит. */
    fun findCar() {
        val r = raw("0100", 15000)
        if (!r.contains("41")) {
            throw NoCarException(
                if (r.contains("UNABLE", true) || r.contains("NO DATA", true) || r.contains("ERROR", true))
                    "Машина не отвечает — включи зажигание (READY)" else "Машина не отвечает: ${r.take(40)}"
            )
        }
        val p = raw("ATDPN").trim().removePrefix("A").removePrefix("a")
        protocol = p.toIntOrNull(16) ?: 6
        header = null
    }

    @Synchronized
    fun voltage(): Double? {
        val r = raw("ATRV")
        return Regex("""(\d+(\.\d+)?)""").find(r)?.value?.toDoubleOrNull()
    }

    /**
     * Запрос к машине. [hdr] — адрес блока (7E0 двигатель, 7E2 гибрид, 7C0 приборка) или null —
     * «всем» (7DF). Возвращает ответы, у которых первый байт = режим + 0x40.
     */
    @Synchronized
    fun request(cmd: String, hdr: String? = null, timeoutMs: Long = 3000): List<EcuReply> {
        if (isCan) {
            val want = hdr ?: "7DF"
            if (header != want) {
                raw("ATSH$want")
                header = want
            }
        }
        val r = raw(cmd, timeoutMs)
        val up = r.uppercase()
        if (up.contains("UNABLE TO CONNECT") || up.contains("CAN ERROR") || up.contains("BUS ERROR") ||
            up.contains("BUS INIT") && up.contains("ERROR")
        ) throw NoCarException("Машина перестала отвечать")
        if (up.contains("NO DATA") || up.trim() == "?" || up.contains("STOPPED")) return emptyList()
        val mode = cmd.trim().take(2).toIntOrNull(16) ?: return emptyList()
        return parse(r).filter { it.data.isNotEmpty() && it.data[0] == mode + 0x40 }
    }

    private fun hexBytes(s: String): IntArray? {
        if (s.length % 2 != 0) return null
        val out = IntArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        return out
    }

    /** Разобрать строки ответа в сообщения по блокам, склеивая многокадровые ответы CAN. */
    private fun parse(resp: String): List<EcuReply> {
        val lines = resp.split('\r', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) && !it.contains("BUS INIT", true) }
        val result = mutableListOf<EcuReply>()
        if (!isCan) {
            // J1850 / ISO: 3 байта заголовка, данные, контрольная сумма.
            for (l in lines) {
                val b = hexBytes(l.replace(" ", "")) ?: continue
                if (b.size < 5) continue
                result += EcuReply("%02X".format(b[2]), b.copyOfRange(3, b.size - 1))
            }
            return result
        }
        val hlen = if (is29bit) 8 else 3
        class Pending(val total: Int, val buf: MutableList<Int>)
        val pending = LinkedHashMap<String, Pending>()
        for (l in lines) {
            val s = l.replace(" ", "")
            if (s.length < hlen + 2) continue
            val hdr = s.substring(0, hlen).uppercase()
            if (hdr.any { !it.isLetterOrDigit() }) continue
            val b = hexBytes(s.substring(hlen)) ?: continue
            if (b.isEmpty()) continue
            val pci = b[0]
            when (pci shr 4) {
                0 -> {
                    val len = pci and 0x0F
                    if (len in 1..(b.size - 1)) result += EcuReply(hdr, b.copyOfRange(1, 1 + len))
                }
                1 -> {
                    if (b.size < 2) continue
                    val total = ((pci and 0x0F) shl 8) or b[1]
                    pending[hdr] = Pending(total, b.drop(2).toMutableList())
                }
                2 -> {
                    val p = pending[hdr] ?: continue
                    p.buf.addAll(b.drop(1))
                    if (p.buf.size >= p.total) {
                        result += EcuReply(hdr, p.buf.take(p.total).toIntArray())
                        pending.remove(hdr)
                    }
                }
            }
        }
        // Недособранные — отдаём что есть (лучше часть, чем ничего).
        for ((h, p) in pending) result += EcuReply(h, p.buf.take(p.total).toIntArray())
        return result
    }
}
