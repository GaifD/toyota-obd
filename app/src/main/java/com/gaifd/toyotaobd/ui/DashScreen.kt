package com.gaifd.toyotaobd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Maint
import com.gaifd.toyotaobd.data.MaintState
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Range
import com.gaifd.toyotaobd.data.Settings
import com.gaifd.toyotaobd.data.Tank
import com.gaifd.toyotaobd.obd.Pids
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Live

/** Цвет заряда гибридной батареи: от 50% — обычный, 40–50 — жёлтый, 40 и ниже — красный. */
@Composable
fun socColor(soc: Double?, normal: Color = C.text): Color = when {
    soc == null -> normal
    soc <= 40 -> C.red
    soc < 50 -> C.amber
    else -> normal
}

@Composable
fun DashScreen(
    live: Live,
    s: Settings,
    tank: Tank,
    batteryOk: Boolean,
    update: String?,
    radio: RadioState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDrive: () -> Unit,
    onSettings: () -> Unit,
    onDiag: () -> Unit,
    onBattery: () -> Unit,
    onMaint: () -> Unit,
    onFixBattery: () -> Unit,
    onFill: () -> Unit,
) {
    var showRange by remember { mutableStateOf(false) }
    var askLamp by remember { mutableStateOf(false) }
    val maint by Maint.items.collectAsState()

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

        if (radio.installed && radio.playing) RadioBar(radio, big = false)

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
                    live.soc?.let { Stat("Батарея", fmt(it, 0), "%", Modifier.weight(1f), color = socColor(it)) }
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
            RidersCard(t)
        }

        SectionTitle("Топливо")
        Card {
            val lvl = live.fuelLevelL ?: tank.inTankL
            val r = Range.of(lvl, live.trip?.km ?: 0.0, live.trip?.fuelL ?: 0.0)
            Row(Modifier.fillMaxWidth()) {
                Stat("В баке", fmt(lvl, 0), "л", Modifier.weight(1f))
                Stat("До лампочки", fmt(r?.toLamp, 0), "км", Modifier.weight(1f), color = if (r != null && r.toLamp < 50) C.amber else C.text)
                Stat("До сухого бака", fmt(r?.toEmpty, 0), "км", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val note = buildString {
                append(if (live.fuelLevelL != null) "Уровень: ${live.fuelLevelSource}" else if (tank.inTankL != null) "Уровень: по последнему подключению" else "Уровень появится после подключения")
                if (r != null) {
                    append(" · расход ${fmt(r.l100, 1)} л/100")
                    if (r.fewData) append(" (пока мало поездок)")
                    append(if (r.lampKnown) " · лампочка на ${fmt(tank.lampL, 1)} л" else " · порог лампочки примерно")
                }
                append(" · ${fmt(Fuel.price(), 2)} грн/л")
            }
            Text(note, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Хватит ли?", Modifier.weight(1f), filled = false, icon = Ic.route) { showRange = true }
                Button("Лампочка", Modifier.weight(1f), filled = false, icon = Ic.lamp, enabled = live.fuelLevelL != null) { askLamp = true }
            }
            Text(
                "Добавить заправку вручную",
                fontSize = 13.sp, color = C.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp).clickable(onClick = onFill),
            )
        }

        if (live.conn == Conn.ONLINE) {
            SectionTitle("Машина")
            Card {
                val c = live.coolant
                KeyValue("Температура ОЖ", c?.let { "${it.toInt()} °C" } ?: "—", if (c != null && c >= 105) C.red else C.text)
                val v = live.values[Pids.ECU_VOLT] ?: live.volt12
                KeyValue("Бортсеть 12 В", v?.let { "${fmt(it, 1)} В" } ?: "—", if (v != null && v < 12.2) C.amber else C.text)
                live.soc?.let { soc ->
                    val th = Prefs.socThreshold
                    KeyValue(
                        "Заряд гибридной батареи",
                        "${soc.toInt()} %" + if ((live.rpm ?: 1.0) < 100 && soc > th) " · до мотора ~${(soc - th).toInt()}%" else "",
                        socColor(soc),
                    )
                }
                live.battery?.let { b ->
                    Box(Modifier.clickable(onClick = onBattery)) {
                        KeyValue(
                            "Батарея по блокам", "разброс ${fmt(b.spread, 2)} В — " + listOf("норма", "присмотреться", "проблема")[b.level],
                            listOf(C.green, C.amber, C.red)[b.level],
                        )
                    }
                }
                live.values[Pids.AMBIENT]?.let { KeyValue("Снаружи", "${it.toInt()} °C") }
                Box(Modifier.clickable(onClick = onDiag)) {
                    KeyValue(
                        "Ошибки", if (live.dtcCount > 0) "${live.dtcCount} — открыть" else "нет",
                        if (live.dtcCount > 0) C.red else C.green, divider = false,
                    )
                }
            }
        }

        SectionTitle("Обслуживание")
        Card(Modifier.clickable(onClick = onMaint)) {
            val odo = Maint.odo()
            val st = maint.map { Maint.status(it, odo).state }
            val over = st.count { it == MaintState.OVERDUE }
            val soon = st.count { it == MaintState.SOON }
            val unknown = st.count { it == MaintState.UNKNOWN }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (over > 0) C.red else if (soon > 0) C.amber else C.green)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            over > 0 -> "Просрочено: $over" + if (soon > 0) ", скоро: $soon" else ""
                            soon > 0 -> "Скоро: $soon"
                            unknown == maint.size -> "Отметь, когда что меняли"
                            else -> "Всё в порядке"
                        },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(odo?.let { "Пробег ${fmt(it, 0)} км" } ?: "Пробег машины не задан", fontSize = 12.sp, color = C.muted)
                }
            }
        }
    }

    if (showRange) RangeDialog(live.fuelLevelL ?: tank.inTankL, live.trip) { showRange = false }
    if (askLamp) {
        AlertDialog(
            onDismissRequest = { askLamp = false },
            containerColor = C.card,
            title = { Text("Загорелась лампочка?") },
            text = {
                Text(
                    "Запомню, что лампочка горит при ${fmt(live.fuelLevelL, 1)} л в баке. Дальше «до лампочки» будет считаться точно." +
                        if (tank.lampL != null) "\n\nСейчас запомнено: ${fmt(tank.lampL, 1)} л." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { Fuel.setLamp(live.fuelLevelL); askLamp = false }) { Text("Да, горит", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { askLamp = false }) { Text("Отмена", color = C.soft) } },
        )
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
