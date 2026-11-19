package com.gaifd.toyotaobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Maint
import com.gaifd.toyotaobd.data.MaintItem
import com.gaifd.toyotaobd.data.MaintState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun MaintScreen(onBack: () -> Unit, onSetOdo: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by Maint.items.collectAsState()
    val odo = Maint.odo()
    var open by remember { mutableStateOf<MaintItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val order = listOf(MaintState.OVERDUE, MaintState.SOON, MaintState.UNKNOWN, MaintState.OK)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Row(Modifier.padding(top = 12.dp)) { Button("Назад", filled = false, onClick = onBack) } }
        item { ScreenTitle("Обслуживание", odo?.let { "пробег ${fmt(it, 0)} км" }) }
        if (odo == null) item {
            Banner("Введи пробег машины — без него напоминания считаются только по времени", onClick = onSetOdo)
        }
        item {
            Text(
                "«Заменено» — следующий раз через полный интервал. «Проверено, в норме» — через половину: раз не меняли, глянуть стоит раньше. Интервалы — ориентир Toyota, нажми на пункт, чтобы поправить.",
                fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
            )
        }
        items(items.sortedBy { order.indexOf(Maint.status(it, odo).state) }, key = { it.id }) { m ->
            val st = Maint.status(m, odo)
            val color = when (st.state) {
                MaintState.OVERDUE -> C.red
                MaintState.SOON -> C.amber
                MaintState.OK -> C.green
                MaintState.UNKNOWN -> C.faint
            }
            Card(Modifier.clickable { open = m }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val left = buildList {
                            st.kmLeft?.let { add(if (it >= 0) "через ${fmt(it, 0)} км" else "просрочено на ${fmt(-it, 0)} км") }
                            st.daysLeft?.let { add(if (it >= 0) "через ${daysText(it)}" else "просрочено на ${daysText(-it)}") }
                        }
                        Text(
                            if (st.state == MaintState.UNKNOWN) "Не отмечено, когда делали" else left.joinToString(" или "),
                            fontSize = 12.sp, color = if (st.state == MaintState.OK || st.state == MaintState.UNKNOWN) C.muted else color,
                        )
                    }
                }
                st.fraction?.let { f ->
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
                        Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(color))
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button("Добавить пункт", Modifier.fillMaxWidth(), filled = false) { adding = true }
        }
    }

    open?.let { m -> MaintDialog(m, odo) { open = null } }
    if (adding) EditMaintDialog(null) { adding = false }
}

private fun daysText(d: Long): String = when {
    d >= 60 -> "${d / 30} мес"
    else -> "$d дн"
}

@Composable
private fun MaintDialog(m: MaintItem, odo: Double?, onDismiss: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("d MMM yyyy", Locale("ru")) }
    if (edit) {
        EditMaintDialog(m, onDismiss)
        return
    }
    if (manual) {
        ManualDoneDialog(m, odo, onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Интервал: " + listOfNotNull(m.intervalKm?.let { "${fmt(it.toDouble(), 0)} км" }, m.intervalMonths?.let { "$it мес" }).joinToString(" или "),
                    fontSize = 13.sp, color = C.soft,
                )
                if (m.lastKm != null || m.lastTs != null) {
                    Text(
                        "В последний раз: " + listOfNotNull(m.lastTs?.let { df.format(Date(it)) }, m.lastKm?.let { "${fmt(it, 0)} км" }).joinToString(", ") +
                            if (m.lastWasCheck) " (проверено)" else " (заменено)",
                        fontSize = 13.sp, color = C.soft,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button("Заменено сейчас", Modifier.fillMaxWidth()) {
                    Maint.update(m.id) { it.copy(lastKm = odo, lastTs = System.currentTimeMillis(), lastWasCheck = false) }
                    onDismiss()
                }
                Button("Проверено, в норме", Modifier.fillMaxWidth(), filled = false) {
                    Maint.update(m.id) { it.copy(lastKm = odo, lastTs = System.currentTimeMillis(), lastWasCheck = true) }
                    onDismiss()
                }
                Button("Было раньше — указать", Modifier.fillMaxWidth(), filled = false) { manual = true }
            }
        },
        confirmButton = { TextButton(onClick = { edit = true }) { Text("Изменить", color = C.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = C.soft) } },
    )
}

@Composable
private fun ManualDoneDialog(m: MaintItem, odo: Double?, onDismiss: () -> Unit) {
    var km by remember { mutableStateOf(odo?.let { fmt(it, 0) } ?: "") }
    var months by remember { mutableStateOf("0") }
    var check by remember { mutableStateOf(false) }
    val k = km.replace(',', '.').toDoubleOrNull()
    val mo = months.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text("Когда делали") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("На каком пробеге, км", km) { km = it }
                NumField("Сколько месяцев назад", months) { months = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Заменено", !check) { check = false }
                    Chip("Проверено", check) { check = true }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = k != null || mo != null, onClick = {
                val ts = Calendar.getInstance().apply { add(Calendar.MONTH, -(mo ?: 0)) }.timeInMillis
                Maint.update(m.id) { it.copy(lastKm = k, lastTs = ts, lastWasCheck = check) }
                onDismiss()
            }) { Text("Сохранить", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = C.soft) } },
    )
}

@Composable
private fun EditMaintDialog(m: MaintItem?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(m?.name ?: "") }
    var km by remember { mutableStateOf(m?.intervalKm?.toString() ?: "") }
    var months by remember { mutableStateOf(m?.intervalMonths?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        title = { Text(if (m == null) "Новый пункт" else "Изменить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Что") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.accent, unfocusedBorderColor = C.border, focusedLabelColor = C.accent,
                        unfocusedLabelColor = C.muted, cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumField("Каждые, км (пусто — не по пробегу)", km) { km = it.filter(Char::isDigit) }
                NumField("Каждые, месяцев (пусто — не по времени)", months) { months = it.filter(Char::isDigit) }
                if (m != null) {
                    Text(
                        "Удалить пункт", fontSize = 13.sp, color = C.red, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp).clickable { Maint.delete(m.id); onDismiss() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val k = km.toIntOrNull()
                val mo = months.toIntOrNull()
                if (m == null) Maint.add(name.trim(), k, mo)
                else Maint.update(m.id) { it.copy(name = name.trim(), intervalKm = k, intervalMonths = mo) }
                onDismiss()
            }) { Text("Сохранить", color = C.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = C.soft) } },
    )
}
