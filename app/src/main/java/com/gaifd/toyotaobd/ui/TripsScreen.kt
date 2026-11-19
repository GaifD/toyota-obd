package com.gaifd.toyotaobd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Tank
import com.gaifd.toyotaobd.data.Trip
import com.gaifd.toyotaobd.data.Trips
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(trips: List<Trip>, tank: Tank, onFill: () -> Unit, onExport: () -> Unit, onOpen: (Trip) -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(1) }
    val since = remember(period) { periodStart(period) }
    val list = remember(trips, since) { trips.filter { it.start >= since } }
    val tot = remember(list) { Trips.totals(list) }
    val df = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ScreenTitle("Поездки") }
        if (tank.pendingFillL != null) item {
            Banner("Похоже, ты заправился (~${fmt(tank.pendingFillL, 0)} л). Укажи цену", color = C.accent, bg = C.accentBg, icon = Ic.fuel, onClick = onFill)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Сегодня", "Неделя", "Месяц", "Всё").forEachIndexed { i, t -> Chip(t, period == i) { period = i } }
            }
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth()) {
                    Stat("Пробег", fmt(tot.km, 0), "км", Modifier.weight(1f))
                    Stat("Топливо", fmt(tot.fuelL, 1), "л", Modifier.weight(1f))
                    Stat("Потрачено", fmt(tot.cost, 0), "грн", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("Средний расход", fmt(tot.l100, 1), "л/100", Modifier.weight(1f))
                    Stat("Средняя", fmt(tot.avgKmh, 0), "км/ч", Modifier.weight(1f))
                    Stat("За рулём", if (tot.sec > 0) dur(tot.sec) else "—", "", Modifier.weight(1f))
                }
                if (tot.evKm > 0.1 && tot.km > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("На электричестве: ${fmt(tot.evKm, 0)} км (${(tot.evKm / tot.km * 100).toInt()}% пути)", fontSize = 12.sp, color = C.muted)
                }
            }
        }
        item {
            SectionTitle("Бак и цена")
            Card {
                KeyValue("Топливо в баке (учёт)", tank.inTankL?.let { "${fmt(it, 0)} л" } ?: "—")
                KeyValue("Средняя цена в баке", "${fmt(Fuel.price(), 2)} грн/л")
                val k = Prefs.settings.value.fuelK
                KeyValue(
                    "Поправка расхода", if (k == 1.0) "ещё не уточнялась" else "×${fmt(k, 3)}",
                    divider = false,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (tank.lastCalib?.let { "Последняя сверка: $it. " } ?: "") +
                        "Расход уточняется сам: приложение сверяет насчитанное с тем, насколько упал уровень в баке. " +
                        "Заправки ловятся сами по скачку уровня.",
                    fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                )
                Text(
                    "Добавить заправку вручную",
                    fontSize = 13.sp, color = C.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp).clickable(onClick = onFill),
                )
                if (tank.fills.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    tank.fills.takeLast(3).reversed().forEachIndexed { i, f ->
                        KeyValue(
                            df.format(Date(f.ts)) + if (f.full) " · полный" else "",
                            "${fmt(f.liters, 1)} л × ${fmt(f.price, 2)}",
                            divider = i < minOf(3, tank.fills.size) - 1,
                        )
                    }
                }
            }
        }
        if (list.size >= 2) item {
            SectionTitle("Расход по поездкам", "л/100 км")
            Card {
                val last = list.filter { it.l100 != null }.take(14).reversed()
                BarChart(last.map { it.l100 }, last.map { SimpleDateFormat("d.MM", Locale.US).format(Date(it.start)) })
            }
        }
        val steady = Trips.steadyBySpeed()
        val useSteady = steady.sumOf { it.first } > 10
        val speed = if (useSteady) steady else Trips.bySpeed()
        if (speed.sumOf { it.first } > 5) item {
            SectionTitle("Расход по скоростям", if (useSteady) "л/100, ровный ход" else "л/100, вся езда")
            Card {
                val vals = speed.map { (km, l) -> if (km >= 0.5) l / km * 100 else null }
                val opt = Trips.optimal()
                BarChart(
                    vals, List(Trip.BUCKETS) { if (it == Trip.BUCKETS - 1) "110+" else "${it * 10}" }, highlight = opt?.first,
                    info = { i ->
                        val (km, l) = speed[i]
                        "${Trip.bucketLabel(i)} км/ч: ${fmt(vals[i], 1)} л/100 · проехано ${fmt(km, 1)} км, сожжено ${fmt(l, 2)} л"
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (opt != null) "Экономнее всего на ровном ходу — ${Trip.bucketLabel(opt.first)} км/ч: ${fmt(opt.second, 1)} л/100"
                    else "Самая экономная скорость появится, когда наберётся ровной езды (по 3+ км на скорость).",
                    fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                )
            }
        }
        item { SectionTitle("История", if (trips.isNotEmpty()) "нажми — подробности" else null) }
        if (list.isEmpty()) item {
            Text("Поездок за этот период нет. Они записываются сами, пока приложение на связи с машиной.", fontSize = 13.sp, color = C.muted, lineHeight = 18.sp)
        }
        items(list.take(200), key = { it.start }) { t ->
            Card(Modifier.clickable { onOpen(t) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(df.format(Date(t.start)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${dur(t.sec)} · ${fmt(t.avgKmh, 0)} км/ч" + if (t.evKm > 0.1) " · EV ${(t.evShare * 100).toInt()}%" else "",
                            fontSize = 12.sp, color = C.muted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmt(t.km, 1)} км", fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${fmt(t.l100, 1)} л/100 · ${fmt(t.cost, 0)} грн", fontSize = 12.sp, color = C.muted)
                    }
                }
            }
        }
        if (trips.isNotEmpty()) item {
            Spacer(Modifier.height(4.dp))
            Button("Экспорт в таблицу (CSV)", Modifier.fillMaxWidth(), filled = false, icon = Ic.share, onClick = onExport)
        }
    }

}

private fun periodStart(p: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return when (p) {
        0 -> c.timeInMillis
        1 -> c.timeInMillis - 6 * 86_400_000L
        2 -> c.timeInMillis - 29 * 86_400_000L
        else -> 0L
    }
}

/** Заправка: литры, цена, до полного ли. */
@Composable
fun FillDialog(suggestL: Double?, sensorAfter: Double?, onDismiss: () -> Unit) {
    val s = Prefs.settings.value
    var liters by remember { mutableStateOf(suggestL?.let { fmt(it, 1) } ?: "") }
    var price by remember { mutableStateOf(fmt(Fuel.lastPrice() ?: s.defaultPrice, 2)) }
    var full by remember { mutableStateOf(false) }
    fun num(t: String) = t.replace(',', '.').trim().toDoubleOrNull()
    val l = num(liters)
    val p = num(price)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Заправка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Сколько литров", liters) { liters = it }
                NumField("Цена, грн за литр", price) { price = it }
                ToggleRow("До полного бака", "Нужно для точной калибровки расхода", full, divider = false) { full = it }
                if (l != null && p != null) Text("Итого: ${fmt(l * p, 0)} грн", fontSize = 13.sp, color = C.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = l != null && l > 0 && p != null && p > 0, onClick = {
                Fuel.addFill(l!!, p!!, full, sensorAfter)
                onDismiss()
            }) { Text("Сохранить", color = C.accent) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (suggestL != null) Fuel.dismissPending()
                onDismiss()
            }) { Text(if (suggestL != null) "Не заправлялся" else "Отмена", color = C.soft) }
        },
    )
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.accent, unfocusedBorderColor = C.border, focusedLabelColor = C.accent,
            unfocusedLabelColor = C.muted, cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
