package com.gaifd.toyotaobd.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Settings
import com.gaifd.toyotaobd.data.Tank
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Live

@Composable
fun DashScreen(
    live: Live,
    s: Settings,
    tank: Tank,
    batteryOk: Boolean,
    update: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDrive: () -> Unit,
    onSettings: () -> Unit,
    onDiag: () -> Unit,
    onFixBattery: () -> Unit,
    onFill: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Бортовик", live.adapter.ifBlank { null })

        if (update != null) Banner("Доступна версия $update", color = C.accent, bg = C.accentBg, icon = Ic.download, onClick = onSettings)
        if (s.elmMac == null) {
            Banner("Выбери адаптер ELM327 в настройках (сначала сопряги его в Bluetooth телефона)", onClick = onSettings)
        } else if (!batteryOk) {
            Banner("Сними ограничение батареи — иначе Android может оборвать запись поездки", onClick = onFixBattery)
        }
        if (tank.pendingFillL != null) {
            Banner("Похоже, ты заправился (~${fmt(tank.pendingFillL, 0)} л). Укажи цену", color = C.accent, bg = C.accentBg, icon = Ic.fuel, onClick = onFill)
        }
        if (live.milOn) Banner("Горит Check Engine (ошибок: ${live.dtcCount})", color = C.red, bg = C.redBg, onClick = onDiag)

        StatusCard(live, s, onConnect, onDisconnect)

        if (live.conn == Conn.ONLINE) {
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Stat("Скорость", fmt(live.speed, 0), "км/ч", Modifier.weight(1f), big = true)
                    val rpm = live.rpm
                    if (live.info.hybrid && rpm != null && rpm < 100) {
                        Stat("Двигатель", "EV", "", Modifier.weight(1f), color = C.green, big = true)
                    } else {
                        Stat("Обороты", fmt(rpm, 0), "об/мин", Modifier.weight(1f), big = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    val inst = live.instL100
                    if (inst != null) Stat("Сейчас", fmt(inst, 1), "л/100", Modifier.weight(1f))
                    else Stat("Сейчас", fmt(live.fuelLph, 2), "л/ч", Modifier.weight(1f))
                    Stat("За поездку", fmt(live.trip?.l100, 1), "л/100", Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Button("Экран «на ходу»", Modifier.fillMaxWidth(), icon = Ic.gauge, onClick = onDrive)
            }
        }

        live.trip?.let { t ->
            SectionTitle("Поездка", dur(t.sec))
            Card {
                Row(Modifier.fillMaxWidth()) {
                    Stat("Пробег", fmt(t.km, 1), "км", Modifier.weight(1f))
                    Stat("Топливо", fmt(t.fuelL, 2), "л", Modifier.weight(1f))
                    Stat("Стоимость", fmt(t.fuelL * Fuel.price(), 0), "грн", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("Средняя", fmt(t.avgKmh, 0), "км/ч", Modifier.weight(1f))
                    Stat("В движении", fmt(t.avgMovingKmh, 0), "км/ч", Modifier.weight(1f))
                    if (live.info.hybrid) Stat("На электричестве", if (t.km > 0.05) "${(t.evKm / t.km * 100).toInt()}" else "—", "%", Modifier.weight(1f))
                    else Stat("Максимум", fmt(t.maxKmh, 0), "км/ч", Modifier.weight(1f))
                }
            }
        }

        SectionTitle("Топливо")
        Card {
            val lvl = live.fuelLevelL ?: tank.inTankL
            val l100 = Trips.recentL100()
            val range = if (lvl != null) lvl / (l100 ?: 9.0) * 100 else null
            Row(Modifier.fillMaxWidth()) {
                Stat("В баке", fmt(lvl, 0), "л", Modifier.weight(1f))
                Stat("Запас хода", fmt(range, 0), "км", Modifier.weight(1f), color = if (range != null && range < 60) C.amber else C.text)
                Stat("Цена", fmt(Fuel.price(), 2), "грн/л", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val src = when {
                live.fuelLevelL != null -> "Уровень: ${live.fuelLevelSource}"
                tank.inTankL != null -> "Уровень: по учёту заправок и расхода"
                else -> "Уровень станет известен после подключения или первой заправки"
            }
            Text(
                src + (l100?.let { " · запас по среднему ${fmt(it, 1)} л/100" } ?: ""),
                fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button("Заправился", Modifier.fillMaxWidth(), filled = false, icon = Ic.fuel, onClick = onFill)
        }

        if (live.conn == Conn.ONLINE) {
            SectionTitle("Машина")
            Card {
                val c = live.coolant
                KeyValue("Температура ОЖ", c?.let { "${it.toInt()} °C" } ?: "—", if (c != null && c >= 105) C.red else C.text)
                val v = live.values[com.gaifd.toyotaobd.obd.Pids.ECU_VOLT] ?: live.volt12
                KeyValue("Бортсеть 12 В", v?.let { "${fmt(it, 1)} В" } ?: "—", if (v != null && v < 12.2) C.amber else C.text)
                live.soc?.let { KeyValue("Заряд гибридной батареи", "${it.toInt()} %") }
                live.values[com.gaifd.toyotaobd.obd.Pids.AMBIENT]?.let { KeyValue("Снаружи", "${it.toInt()} °C") }
                KeyValue(
                    "Ошибки", if (live.dtcCount > 0) "${live.dtcCount} — открыть" else "нет",
                    if (live.dtcCount > 0) C.red else C.green, divider = false,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(live: Live, s: Settings, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (color, title) = when (live.conn) {
                Conn.OFF -> C.faint to "Не подключено"
                Conn.CONNECTING -> C.amber to "Подключаюсь к адаптеру…"
                Conn.NO_CAR -> C.amber to "Адаптер на связи, машина молчит"
                Conn.ONLINE -> C.green to "На связи с машиной"
                Conn.ERROR -> C.red to "Нет связи"
            }
            Dot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val sub = when (live.conn) {
                    Conn.OFF -> s.elmName?.let { "Адаптер: $it" } ?: "Адаптер не выбран"
                    Conn.NO_CAR -> live.message.ifBlank { "Включи зажигание (READY)" }
                    Conn.ONLINE -> buildString {
                        append(live.info.ecus.size).append(" бл. · протокол ").append(live.protocol)
                        if (live.info.hybrid) append(" · гибрид")
                    }
                    else -> live.message
                }
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
            }
        }
        if (s.elmMac != null) {
            Spacer(Modifier.height(12.dp))
            if (live.conn == Conn.OFF || live.conn == Conn.ERROR) Button("Подключиться", Modifier.fillMaxWidth(), icon = Ic.plug, onClick = onConnect)
            else Button("Отключиться", Modifier.fillMaxWidth(), filled = false, onClick = onDisconnect)
        }
    }
}
