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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.obd.Dtc
import com.gaifd.toyotaobd.obd.Dtcs
import com.gaifd.toyotaobd.obd.Mode6
import com.gaifd.toyotaobd.obd.TestResult
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.DiagResult
import com.gaifd.toyotaobd.service.FreezeFrame
import com.gaifd.toyotaobd.service.Live
import com.gaifd.toyotaobd.service.Obd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Последние прочитанные данные диагностики — живут, пока открыто приложение. */
object DiagStore {
    val diag = MutableStateFlow<DiagResult?>(null)
    val tests = MutableStateFlow<List<TestResult>?>(null)
    val freeze = MutableStateFlow<FreezeFrame?>(null)
    val busy = MutableStateFlow<String?>(null)

    suspend fun readAll() {
        busy.value = "Читаю ошибки…"
        Obd.readDiag()?.let { d ->
            diag.value = d
            Prefs.knownDtcs = d.dtcs.filter { it.kind != Dtc.Kind.PERMANENT }.map { it.code }.toSet()
        }
        busy.value = "Читаю самотесты…"
        Obd.mode6()?.let { tests.value = it }
        busy.value = null
    }
}

@Composable
fun DiagScreen(live: Live, onCat: () -> Unit, onLive: () -> Unit) {
    val diag by DiagStore.diag.collectAsState()
    val tests by DiagStore.tests.collectAsState()
    val freeze by DiagStore.freeze.collectAsState()
    val busy by DiagStore.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var askClear by remember { mutableStateOf(false) }
    var showTests by rememberSaveable { mutableStateOf(false) }
    val online = live.conn == Conn.ONLINE
    val tf = remember { SimpleDateFormat("HH:mm", Locale.US) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Диагностика", diag?.let { "прочитано в ${tf.format(Date(it.time))}" })
        if (!online) Banner("Подключись к машине на вкладке «Приборы» и включи зажигание (READY)", icon = Ic.plug)

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val d = diag
                val color = when {
                    d == null -> C.faint
                    d.mil -> C.red
                    d.dtcs.isNotEmpty() -> C.amber
                    else -> C.green
                }
                Dot(color, 12)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            d == null -> "Ошибки не прочитаны"
                            d.dtcs.isEmpty() -> "Ошибок нет"
                            else -> "Ошибок: ${d.dtcs.map { it.code }.distinct().size}"
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    if (d != null) Text(if (d.mil) "Check Engine горит" else "Check Engine не горит", fontSize = 12.sp, color = C.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(busy ?: "Прочитать ошибки и самотесты", Modifier.fillMaxWidth(), enabled = online && busy == null, icon = Ic.refresh) {
                scope.launch { DiagStore.readAll() }
            }
        }

        diag?.let { d ->
            if (d.dtcs.isNotEmpty()) {
                SectionTitle("Коды")
                Card {
                    d.dtcs.sortedBy { it.kind.ordinal }.forEachIndexed { i, dtc ->
                        DtcRow(dtc, onCat, divider = i < d.dtcs.size - 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Условия ошибки", Modifier.weight(1f), filled = false, enabled = online) {
                        scope.launch { DiagStore.freeze.value = Obd.freezeFrame() }
                    }
                    Button("Стереть", Modifier.weight(1f), filled = false, enabled = online, icon = Ic.trash) { askClear = true }
                }
            }
            if (d.dtcs.any { Dtcs.isCatalystRelated(it.code) }) {
                Banner("Есть ошибки по катализатору или смеси — разберём причину", color = C.accent, bg = C.accentBg, icon = Ic.cat, onClick = onCat)
            }
        }

        freeze?.let { f ->
            SectionTitle("Условия, когда записалась ошибка", f.code)
            Card {
                if (f.values.isEmpty()) Text("Машина не сохранила снимок условий.", fontSize = 13.sp, color = C.muted)
                f.values.forEachIndexed { i, (k, v) -> KeyValue(k, v, divider = i < f.values.size - 1) }
            }
        }

        SectionTitle("Инструменты")
        Card {
            ActionRow("Катализатор: поиск причины", "Самотест ЭБУ, датчики до и после, пропуски, смесь — и вывод", Ic.cat, C.accent, onClick = onCat)
            ActionRow("Все датчики", "Живые значения и графики всего, что отдаёт машина", Ic.pulse, divider = false, onClick = onLive)
        }

        diag?.let { d ->
            if (d.monitors.isNotEmpty()) {
                SectionTitle("Готовность самопроверок", "${d.monitors.count { it.complete }} из ${d.monitors.size}")
                Card {
                    d.monitors.forEachIndexed { i, m ->
                        KeyValue(m.name, if (m.complete) "проверено" else "ещё нет", if (m.complete) C.green else C.amber, divider = i < d.monitors.size - 1)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "«Ещё нет» — после сброса ошибок или отключения аккумулятора машина ещё не прогнала этот тест. " +
                            "Обычно хватает нескольких поездок с прогретым двигателем.",
                        fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                    )
                }
            }
        }

        tests?.let { list ->
            if (list.isNotEmpty()) {
                SectionTitle("Самотесты ЭБУ (Mode 6)", if (showTests) "свернуть" else "${list.size} шт.")
                Card(Modifier.clickable { showTests = !showTests }) {
                    if (!showTests) {
                        val failed = list.count { !it.passed }
                        Text(
                            if (failed == 0) "Все тесты пройдены. Нажми, чтобы посмотреть значения." else "Не пройдено: $failed. Нажми, чтобы посмотреть.",
                            fontSize = 13.sp, color = if (failed == 0) C.soft else C.red,
                        )
                    } else {
                        TestList(list)
                    }
                }
            }
        }
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            containerColor = C.card,
            title = { Text("Стереть ошибки?") },
            text = {
                Text(
                    "Вместе с ошибками сотрутся результаты самотестов (Mode 6) и готовность — по ним как раз видно, " +
                        "что с катализатором. Сначала разберись с причиной на экране катализатора. " +
                        "Если неисправность не устранена, ошибка вернётся через несколько поездок."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    scope.launch {
                        Obd.clearDtcs()
                        DiagStore.freeze.value = null
                        DiagStore.readAll()
                    }
                }) { Text("Стереть", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("Отмена", color = C.soft) } },
        )
    }
}

@Composable
private fun DtcRow(d: Dtc, onCat: () -> Unit, divider: Boolean) {
    val cat = Dtcs.isCatalystRelated(d.code)
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (cat) Modifier.clickable(onClick = onCat) else Modifier).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.code, fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (d.kind == Dtc.Kind.STORED) C.red else C.amber)
                    Spacer(Modifier.width(8.dp))
                    Text("${d.kind.label} · ${d.ecuName}", fontSize = 11.sp, color = C.muted)
                }
                Text(d.description, fontSize = 13.sp, color = C.soft, lineHeight = 18.sp)
            }
            if (cat) ChevronIcon(Ic.chevronRight)
        }
        if (divider) Divider()
    }
}

@Composable
private fun ChevronIcon(v: androidx.compose.ui.graphics.vector.ImageVector) {
    androidx.compose.material3.Icon(v, contentDescription = null, tint = C.accent, modifier = Modifier.size(20.dp))
}

@Composable
fun TestList(list: List<TestResult>) {
    val grouped = list.groupBy { it.mid }.toSortedMap()
    grouped.entries.forEachIndexed { gi, (mid, tests) ->
        Text(Mode6.midName(mid), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (gi == 0) 0.dp else 12.dp, bottom = 2.dp))
        tests.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (t.passed) C.green else C.red, 8)
                Spacer(Modifier.width(8.dp))
                Text(t.name, fontSize = 12.sp, color = C.soft, modifier = Modifier.weight(1f), lineHeight = 15.sp)
                Spacer(Modifier.width(8.dp))
                val lim = when {
                    t.onlyMin && !t.onlyMax -> "≥ ${fmtT(t.min)}"
                    t.onlyMax -> "≤ ${fmtT(t.max)}"
                    else -> "${fmtT(t.min)}…${fmtT(t.max)}"
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmtT(t.value)} ${t.unit}".trim(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(lim, fontSize = 10.sp, color = C.muted)
                }
            }
        }
    }
}

private fun fmtT(x: Double): String =
    (if (kotlin.math.abs(x) >= 100) "%.0f".format(x) else "%.3f".format(x).trimEnd('0').trimEnd('.', ',')).replace('.', ',')
