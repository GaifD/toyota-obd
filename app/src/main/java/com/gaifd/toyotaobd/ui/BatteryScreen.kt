package com.gaifd.toyotaobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Prefs
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Live
import com.gaifd.toyotaobd.service.Obd
import com.gaifd.toyotaobd.service.Samples

/** Гибридная батарея: 15 блоков, разброс, сопротивление, заряд. Формулы пока проверяем. */
@Composable
fun BatteryScreen(live: Live, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        Obd.watchBattery.value = true
        onDispose { Obd.watchBattery.value = false }
    }
    val b = live.battery
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 12.dp)) { Button("Назад", filled = false, onClick = onBack) }
        ScreenTitle("Гибридная батарея", "проверяем")
        if (live.conn != Conn.ONLINE) Banner("Подключись к машине и включи READY", icon = Ic.plug)
        Banner(
            "Расшифровку блоков нашли по сканеру и пока проверяем. Тест: плавно газ — напряжения должны дружно просесть, " +
                "торможение — подрасти. Если так и есть, формула верная.",
            color = C.accent, bg = C.accentBg, icon = Ic.info,
        )

        Card {
            Row(Modifier.fillMaxWidth()) {
                Stat("Заряд", fmt(live.soc, 0), "%", Modifier.weight(1f), color = socColor(live.soc))
                Stat("Всего", fmt(b?.total, 0), "В", Modifier.weight(1f))
                Stat("Разброс", fmt(b?.spread, 2), "В", Modifier.weight(1f), color = b?.let { listOf(C.green, C.amber, C.red)[it.level] } ?: C.text)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Двигатель заводится примерно при ${Prefs.socThreshold.toInt()}% заряда" +
                    (if (Prefs.engineStartSoc.size < 3) " (пока по умолчанию, уточнится само)" else " (по твоим поездкам)") +
                    ". Разброс между блоками до 0,3 В — норма, 0,3–0,5 — присмотреться, больше — проблемный блок.",
                fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
            )
        }

        if (b == null) {
            Text(
                if (live.conn == Conn.ONLINE) "Жду данные от гибридной системы…" else "Нет данных.",
                fontSize = 13.sp, color = C.muted,
            )
        } else {
            SectionTitle("Напряжение блоков", "слабейший — №${b.weakest + 1}")
            Card {
                val lo = b.min - 0.05
                val hi = b.max + 0.05
                val irAvg = b.ir?.average() ?: 0.0
                b.blocks.forEachIndexed { i, v ->
                    val weak = i == b.weakest && b.spread >= 0.1
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = 12.sp, color = C.muted, modifier = Modifier.width(22.dp))
                        val f = ((v - lo) / (hi - lo)).toFloat().coerceIn(0.05f, 1f)
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(if (weak) C.amber else C.accent))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${fmt(v, 2)} В", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(62.dp))
                        b.ir?.getOrNull(i)?.let {
                            Text("$it", fontSize = 12.sp, color = if (it >= irAvg + 3) C.amber else C.muted, modifier = Modifier.width(26.dp))
                        }
                    }
                }
                if (b.ir != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Справа — похоже на внутреннее сопротивление блока, мОм. Выше соседей на 3+ — блок слабеет.",
                        fontSize = 12.sp, color = C.muted, lineHeight = 16.sp,
                    )
                }
            }
            SectionTitle("Разброс за последние 5 минут", "В")
            Card {
                LineChart(listOf(Samples.get("bat_spread", live.updated - 300_000) to C.amber), minY = 0.0, refs = listOf(0.3), windowMs = 300_000)
            }
            SectionTitle("Общее напряжение", "В")
            Card {
                LineChart(listOf(Samples.get("bat_total", live.updated - 300_000) to C.accent), windowMs = 300_000)
            }
        }
    }
}
