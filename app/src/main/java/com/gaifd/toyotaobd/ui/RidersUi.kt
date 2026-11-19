package com.gaifd.toyotaobd.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.data.Range
import com.gaifd.toyotaobd.data.Rider
import com.gaifd.toyotaobd.data.Riders
import com.gaifd.toyotaobd.data.Share
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.service.Obd
import com.gaifd.toyotaobd.service.TripLive

fun shareText(ctx: Context, text: String) {
    try {
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Отправить")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

/** Попутчики текущей поездки — карточка на «Приборах». */
@Composable
fun RidersCard(trip: TripLive) {
    val all by Riders.items.collectAsState()
    val riders = all.filter { it.trip == trip.start }
    var add by remember { mutableStateOf(false) }
    val shares = Riders.shares(riders, System.currentTimeMillis(), trip.km, trip.fuelL)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Попутчики", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (riders.isEmpty()) Text("никого", fontSize = 12.sp, color = C.muted)
        }
        riders.forEach { r -> RiderRow(r, shares[r.id], live = true) }
        Spacer(Modifier.height(10.dp))
        Button("Попутчик сел", Modifier.fillMaxWidth(), filled = false, icon = Ic.users) { add = true }
    }
    if (add) AddRiderDialog(trip) { add = false }
}

@Composable
fun RiderRow(r: Rider, s: Share?, live: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.name + if (r.active) " · едет" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (r.active) C.accent else C.text,
                )
                Text(
                    "${fmt(s?.km, 1)} км · ${fmt(s?.liters, 2)} л" + if (!r.friend) " · пассажир" else " · друг",
                    fontSize = 12.sp, color = C.muted,
                )
            }
            Text("${fmt(s?.total, 0)} грн", fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Поровну", r.split) { Riders.update(r.id) { it.copy(split = true) } }
            Chip("Платит пассажир", !r.split) { Riders.update(r.id) { it.copy(split = false) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live && r.active) {
                Button("Вышел", Modifier.weight(1f), filled = true) {
                    val t = Obd.live.value.trip
                    if (t != null) Riders.update(r.id) { it.copy(t1 = System.currentTimeMillis(), km1 = t.km, l1 = t.fuelL) }
                }
            }
            Button("Отправить", Modifier.weight(1f), filled = false, icon = Ic.share) {
                if (s != null) shareText(ctx, Riders.shareText(r, s))
            }
            if (!live) Button("Удалить", Modifier.weight(1f), filled = false) { Riders.delete(r.id) }
        }
    }
}

@Composable
private fun AddRiderDialog(trip: TripLive, onDismiss: () -> Unit) {
    val n = Riders.items.collectAsState().value.count { it.trip == trip.start }
    var name by remember { mutableStateOf("Попутчик ${n + 1}") }
    var friend by remember { mutableStateOf(true) }
    var ago by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Попутчик сел") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Имя") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.accent, unfocusedBorderColor = C.border, focusedLabelColor = C.accent,
                        unfocusedLabelColor = C.muted, cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Друг", friend) { friend = true }
                    Chip("Пассажир", !friend) { friend = false }
                }
                Text(
                    if (friend) "Только топливо по факту." else "Топливо + износ ${fmt(Prefs.settings.value.wearRate, 2)} грн/км.",
                    fontSize = 12.sp, color = C.muted,
                )
                Text("Когда сел", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "сейчас", 5 to "5 мин", 10 to "10", 15 to "15", 30 to "30").forEach { (m, t) ->
                        Chip(t, ago == m) { ago = m }
                    }
                }
                if (ago > 0) Text("…минут назад", fontSize = 12.sp, color = C.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                val t0 = now - ago * 60_000L
                val p = if (ago > 0) Obd.pointAt(t0) else null
                val live = Obd.live.value.trip
                val km0 = p?.km ?: live?.km ?: trip.km
                val l0 = p?.fuel ?: live?.fuelL ?: trip.fuelL
                Riders.add(
                    Rider(
                        id = now, trip = trip.start, name = name.ifBlank { "Попутчик" }, friend = friend, split = true,
                        t0 = p?.t ?: now, km0 = km0, l0 = l0, price = Fuel.price(), wear = Prefs.settings.value.wearRate,
                    )
                )
                onDismiss()
            }) { Text("Поехали", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = C.soft) } },
    )
}

/** «Хватит ли?» — сколько ехать → хватит или нет, с запасом; и подсказка про экономную скорость. */
@Composable
fun RangeDialog(level: Double?, trip: TripLive?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val km = text.replace(',', '.').toDoubleOrNull()
    val r = Range.of(level, trip?.km ?: 0.0, trip?.fuelL ?: 0.0)
    val opt = Trips.optimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Хватит ли?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r == null) {
                    Text("Уровень в баке пока неизвестен — подключись к машине.", fontSize = 13.sp, color = C.amber)
                    return@Column
                }
                NumField("Сколько ехать, км", text) { text = it }
                if (km != null && km > 0) {
                    val need = km * r.l100 / 100
                    val left = (level ?: 0.0) - need
                    val color = when {
                        km <= r.toLamp -> C.green
                        km <= r.toEmpty -> C.amber
                        else -> C.red
                    }
                    Text(
                        when {
                            km <= r.toLamp -> "Хватит. Останется ~${fmt(left, 0)} л, лампочка не загорится"
                            km <= r.toEmpty -> "Хватит впритык: доедешь на лампочке, останется ~${fmt(left, 0)} л"
                            else -> "Не хватит ~${fmt(km - r.toEmpty, 0)} км — заправься в пределах ${fmt(r.toLamp, 0)} км"
                        },
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, lineHeight = 20.sp,
                    )
                    Text("Нужно ~${fmt(need, 1)} л при твоём расходе ${fmt(r.l100, 1)} л/100" + if (r.fewData) " (пока мало данных)" else "", fontSize = 12.sp, color = C.muted)
                    if (opt != null && km > r.toLamp) {
                        val (b, l100) = opt
                        val optRange = (level ?: 0.0) / l100 * 100
                        Text(
                            "Если держать ${com.gaifd.toyotaobd.data.Trip.bucketLabel(b)} км/ч (твой самый экономный режим, ${fmt(l100, 1)} л/100) — " +
                                if (optRange >= km) "хватит, запас ~${fmt(optRange - km, 0)} км" else "всё равно не хватит ~${fmt(km - optRange, 0)} км",
                            fontSize = 13.sp, color = C.soft, lineHeight = 18.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = km != null && km > 0 && r != null, onClick = {
                Obd.goal.value = Obd.Goal(trip?.km ?: 0.0, km!!)
                onDismiss()
            }) { Text("Следить в пути", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = C.soft) } },
    )
}
