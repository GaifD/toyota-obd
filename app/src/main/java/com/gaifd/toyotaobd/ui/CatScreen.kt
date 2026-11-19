package com.gaifd.toyotaobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.obd.Pids
import com.gaifd.toyotaobd.service.CatTest
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Diagnosis
import com.gaifd.toyotaobd.service.Live
import com.gaifd.toyotaobd.service.Obd
import com.gaifd.toyotaobd.service.Samples
import kotlinx.coroutines.launch

/** Поиск причины ошибки катализатора: данные ЭБУ + живая запись датчиков → вывод. */
@Composable
fun CatScreen(live: Live, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        Obd.watchO2.value = true
        onDispose { Obd.watchO2.value = false }
    }
    val diag by DiagStore.diag.collectAsState()
    val tests by DiagStore.tests.collectAsState()
    val busy by DiagStore.busy.collectAsState()
    val cat by CatTest.state.collectAsState()
    val scope = rememberCoroutineScope()
    val online = live.conn == Conn.ONLINE

    val trimsNow = buildMap {
        val s1 = live.values[Pids.STFT1]; val l1 = live.values[Pids.LTFT1]
        val s2 = live.values[Pids.STFT2]; val l2 = live.values[Pids.LTFT2]
        if (s1 != null && l1 != null) put(1, s1 + l1)
        if (s2 != null && l2 != null) put(2, s2 + l2)
    }
    val verdict = Diagnosis.analyze(
        diag?.dtcs ?: emptyList(), tests,
        cat.takeIf { it.banks.isNotEmpty() && it.validSec >= 40 }, trimsNow,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button("Назад", filled = false, onClick = onBack)
        }
        ScreenTitle("Катализатор")
        if (!online) Banner("Подключись к машине и включи READY", icon = Ic.plug)

        // Вывод
        Card {
            Text("Вывод", fontSize = 12.sp, color = C.muted)
            Text(verdict.headline, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            if (diag == null || cat.banks.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Для точного вывода нужны оба шага: данные из ЭБУ и живая запись датчиков.",
                    fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                )
            }
        }
        verdict.findings.forEach { f ->
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp)) { Dot(levelColor(f.level)) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(f.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(f.text, fontSize = 13.sp, color = C.soft, lineHeight = 18.sp)
                    }
                }
            }
        }

        SectionTitle("Шаг 1. Данные из ЭБУ", if (diag != null) "готово" else null)
        Card {
            Text(
                "Коды ошибок, самотест катализатора (Mode 6), счётчики пропусков по цилиндрам. Безопасно — ничего не стирает.",
                fontSize = 13.sp, color = C.soft, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(busy ?: if (diag == null) "Прочитать" else "Прочитать ещё раз", Modifier.fillMaxWidth(), enabled = online && busy == null, icon = Ic.refresh) {
                scope.launch { DiagStore.readAll() }
            }
        }

        SectionTitle("Шаг 2. Живая запись датчиков", if (cat.validSec > 0) "${cat.validSec.toInt()} из ${CatTest.TARGET_SEC.toInt()} с" else null)
        Card {
            Text(
                "Прогрей двигатель (ОЖ выше 80 °C) и едь ровно, 50–90 км/ч. У гибрида двигатель часто глохнет — " +
                    "считаются только секунды, когда он работает в нормальном режиме, поэтому запись может занять дольше.",
                fontSize = 13.sp, color = C.soft, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            val coolant = live.coolant
            val rpm = live.rpm
            val fs = live.values[Pids.FUEL_SYS]?.toInt()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cond("ОЖ ${coolant?.toInt() ?: "—"}°", coolant != null && coolant >= 75)
                Cond(if ((rpm ?: 0.0) > 400) "двигатель работает" else "двигатель стоит", (rpm ?: 0.0) > 400)
                Cond(if (fs == 2) "замкнутый цикл" else "цикл: ${fs?.let { Pids.fuelSystem(it).substringBefore(',') } ?: "—"}", fs == 2)
            }
            Spacer(Modifier.height(10.dp))
            if (cat.running) {
                LinearProgressIndicator(
                    progress = { (cat.validSec / CatTest.TARGET_SEC).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = C.accent, trackColor = C.line,
                )
                Spacer(Modifier.height(10.dp))
                Button("Остановить", Modifier.fillMaxWidth(), filled = false) { CatTest.stop() }
            } else {
                Button(if (cat.banks.isEmpty()) "Начать запись" else "Записать заново", Modifier.fillMaxWidth(), enabled = online && live.info.o2.isNotEmpty(), icon = Ic.pulse) {
                    CatTest.start()
                }
                if (online && live.info.o2.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Машина не отдаёт показания датчиков кислорода по стандартному OBD2.", fontSize = 12.sp, color = C.amber)
                }
            }
            cat.banks.forEach { b ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ряд ${b.bank}: после катализатора ${fmt(b.downMin, 2)}–${fmt(b.downMax, 2)} В, переключений ${b.downSwitches}" +
                        " (до катализатора ${b.upSwitches})",
                    fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                )
            }
        }

        // Графики датчиков по рядам.
        val banks = live.info.o2.map { it.bank }.distinct().sorted()
        val now = live.updated
        banks.forEach { bank ->
            val upS = live.info.o2.firstOrNull { it.bank == bank && it.pos == 1 }
            val dnS = live.info.o2.firstOrNull { it.bank == bank && it.pos == 2 }
            SectionTitle("Ряд $bank", "последние 2 минуты")
            Card {
                if (upS != null) {
                    val pts = Samples.get("o2_${bank}_1", now - 120_000)
                    val isLambda = (live.info.o2Pid[upS.index] ?: 0) >= 0x24
                    Text(if (isLambda) "До катализатора (A/F), λ" else "До катализатора, В", fontSize = 12.sp, color = C.muted)
                    LineChart(listOf(pts to C.amber), refs = if (isLambda) listOf(1.0) else listOf(0.45), windowMs = 120_000)
                    Spacer(Modifier.height(10.dp))
                }
                if (dnS != null) {
                    val pts = Samples.get("o2_${bank}_2", now - 120_000)
                    Text("После катализатора, В — у живого почти ровная линия", fontSize = 12.sp, color = C.muted)
                    LineChart(listOf(pts to C.accent), minY = 0.0, maxY = 1.0, refs = listOf(0.45), windowMs = 120_000)
                }
                if (upS == null && dnS == null) Text("Нет данных", fontSize = 13.sp, color = C.muted)
            }
        }

        tests?.filter { it.mid in 0x21..0x24 || it.mid in 0xA1..0xAD }?.takeIf { it.isNotEmpty() }?.let {
            SectionTitle("Самотест: катализатор и пропуски")
            Card { TestList(it) }
        }

        SectionTitle("Как читать")
        Card {
            Text(
                "Датчик до катализатора показывает смесь и постоянно колеблется — так работает регулировка. " +
                    "Исправный катализатор запасает кислород и сглаживает эти колебания, поэтому датчик после него " +
                    "показывает почти ровную линию около 0,6–0,8 В. Если нижний график скачет так же, как верхний, — " +
                    "катализатор не держит кислород. Если нижний — ровная линия у нуля, скорее утечка выхлопа или мёртвый датчик.",
                fontSize = 13.sp, color = C.soft, lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun Cond(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) C.green else C.faint, 8)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = if (ok) C.text else C.muted)
    }
}
