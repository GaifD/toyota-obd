package com.gaifd.toyotaobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.obd.Pids
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Live
import com.gaifd.toyotaobd.service.Obd
import com.gaifd.toyotaobd.service.Samples

/** Все параметры, которые отдаёт машина. Нажатие — график за 2 минуты. */
@Composable
fun LiveScreen(live: Live, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        Obd.watchAll.value = true
        onDispose { Obd.watchAll.value = false }
    }
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val pids = Pids.all.values.filter { live.info.has(it.id) }.sortedBy { it.name }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(Modifier.padding(top = 12.dp)) { Button("Назад", filled = false, onClick = onBack) }
        }
        item { ScreenTitle("Все датчики", "${pids.size} параметров") }
        if (live.conn != Conn.ONLINE) item { Banner("Подключись к машине, чтобы видеть значения", icon = Ic.plug) }
        items(pids, key = { it.id }) { p ->
            val v = live.values[p.id]
            Card(Modifier.clickable { open = if (open == p.id) null else p.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = 13.sp, color = C.soft, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val text = when {
                        v == null -> "—"
                        p.id == Pids.FUEL_SYS -> Pids.fuelSystem(v.toInt())
                        else -> Pids.format(v, p.decimals) + if (p.unit.isNotEmpty()) " ${p.unit}" else ""
                    }
                    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (open == p.id) {
                    Spacer(Modifier.height(8.dp))
                    LineChart(listOf(Samples.get(p.key, live.updated - 120_000) to C.accent), windowMs = 120_000)
                    Text("PID ${p.key} · ${live.info.hdrFor(p.id) ?: "общий запрос"}", fontSize = 11.sp, color = C.faint)
                }
            }
        }
    }
}
