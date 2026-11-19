package com.gaifd.toyotaobd.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.data.Fuel
import com.gaifd.toyotaobd.data.Settings
import com.gaifd.toyotaobd.data.Trips
import com.gaifd.toyotaobd.service.Conn
import com.gaifd.toyotaobd.service.Live

/** Экран «на ходу»: крупный спидометр в км/ч, обороты (или EV), расход. HUD — зеркально для отражения в стекле. */
@Composable
fun DriveScreen(live: Live, s: Settings, radio: RadioState, onClose: () -> Unit) {
    var hud by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(hud) {
        view.keepScreenOn = true
        val w = (ctx as? Activity)?.window
        val old = w?.attributes?.screenBrightness
        if (hud && w != null) {
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            view.keepScreenOn = false
            if (w != null && old != null) w.attributes = w.attributes.apply { screenBrightness = old }
        }
    }
    val fg = if (hud) Color(0xFF7CFFF4) else C.text
    val muted = if (hud) Color(0xFF3FAFA6) else C.muted
    val accent = if (hud) Color(0xFF7CFFF4) else C.accent
    val track = if (hud) Color(0xFF0E2B29) else C.line
    val speed = live.speed
    val rpm = live.rpm
    val ev = live.info.hybrid && rpm != null && rpm < 100

    Column(Modifier.fillMaxSize().background(if (hud) Color.Black else C.bg).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pill("Выйти", muted, onClose)
            Spacer(Modifier.width(8.dp))
            Pill(if (hud) "Обычный" else "HUD", muted) { hud = !hud }
            Spacer(Modifier.weight(1f))
            if (live.conn != Conn.ONLINE) Text("нет связи", fontSize = 14.sp, color = C.amber)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { if (hud && s.hudMirror) scaleX = -1f }
                .pointerInput(radio.installed) {
                    // Свайп по экрану — переключить станцию в Авторадио.
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            if (dx < -120) radio.next() else if (dx > 120) radio.prev()
                        },
                    ) { _, d -> dx += d }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.5f), contentAlignment = Alignment.Center) {
                Arc(fraction = ((speed ?: 0.0) / 180.0).toFloat(), color = accent, track = track, width = 26f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        speed?.let { Math.round(it).toString() } ?: "—",
                        fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 120.sp, color = fg, lineHeight = 120.sp,
                    )
                    Text("км/ч", fontSize = 20.sp, color = muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            // Обороты полосой; у гибрида на электричестве — надпись EV.
            if (ev) {
                Text("EV", fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = if (hud) fg else C.green)
                live.soc?.let { soc ->
                    val th = com.gaifd.toyotaobd.data.Prefs.socThreshold
                    if (soc > th) Text("до мотора ~${(soc - th).toInt()}%", fontSize = 18.sp, color = muted)
                }
            } else {
                val f = ((rpm ?: 0.0) / 6000.0).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(0.85f).height(14.dp).clip(RoundedCornerShape(99.dp)).background(track)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(if ((rpm ?: 0.0) > 4500) C.amber else accent))
                }
                Spacer(Modifier.height(4.dp))
                Text("${rpm?.let { Math.round(it) } ?: "—"} об/мин", fontSize = 20.sp, color = muted)
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) {
                val inst = live.instL100
                Big(if (inst != null) fmt(inst, 1) else fmt(live.fuelLph, 1), if (inst != null) "л/100 сейчас" else "л/ч сейчас", fg, muted, Modifier.weight(1f))
                Big(fmt(live.trip?.l100, 1), "л/100 поездка", fg, muted, Modifier.weight(1f))
                val lvl = live.fuelLevelL ?: Fuel.tank.value.inTankL
                val range = com.gaifd.toyotaobd.data.Range.of(lvl, live.trip?.km ?: 0.0, live.trip?.fuelL ?: 0.0)
                Big(fmt(range?.toLamp, 0), "км до лампочки", if (range != null && range.toLamp < 50) C.amber else fg, muted, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Big(fmt(live.trip?.km, 1), "км поездка", fg, muted, Modifier.weight(1f))
                Big(live.coolant?.let { "${it.toInt()}°" } ?: "—", "двигатель", if ((live.coolant ?: 0.0) >= 105) C.red else fg, muted, Modifier.weight(1f))
                if (live.soc != null) Big("${live.soc!!.toInt()}%", "батарея", socColor(live.soc, fg), muted, Modifier.weight(1f))
                else Big(fmt(live.trip?.avgKmh, 0), "км/ч средняя", fg, muted, Modifier.weight(1f))
            }
            // Цель «Хватит ли» и экономная скорость.
            val goal by com.gaifd.toyotaobd.service.Obd.goal.collectAsState()
            val g = goal
            val lvl2 = live.fuelLevelL ?: Fuel.tank.value.inTankL
            val r2 = com.gaifd.toyotaobd.data.Range.of(lvl2, live.trip?.km ?: 0.0, live.trip?.fuelL ?: 0.0)
            if (g != null && r2 != null) {
                val left = (g.distanceKm - ((live.trip?.km ?: 0.0) - g.startKm)).coerceAtLeast(0.0)
                val (txt, col) = when {
                    left <= r2.toLamp -> "До цели ${fmt(left, 0)} км · хватит, запас ${fmt(r2.toLamp - left, 0)} км" to C.green
                    left <= r2.toEmpty -> "До цели ${fmt(left, 0)} км · впритык, на лампочке" to C.amber
                    else -> "До цели ${fmt(left, 0)} км · не хватит ${fmt(left - r2.toEmpty, 0)} км — заправься" to C.red
                }
                Spacer(Modifier.height(14.dp))
                Text(txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (hud) fg else col)
            }
            Trips.optimal()?.let { (b, l100) ->
                Spacer(Modifier.height(8.dp))
                Text("экономнее всего: ${com.gaifd.toyotaobd.data.Trip.bucketLabel(b)} км/ч · ${fmt(l100, 1)} л/100", fontSize = 15.sp, color = muted)
            }
        }
        if (radio.installed) {
            Spacer(Modifier.height(8.dp))
            RadioBar(radio, big = true, fg = fg, muted = muted, bg = if (hud) Color(0xFF071615) else C.card)
        }
    }
}

@Composable
private fun Big(value: String, label: String, fg: Color, muted: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = fg, maxLines = 1)
        Text(label, fontSize = 13.sp, color = muted, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(99.dp))
            .border(1.dp, color, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color) }
}

/** Дуга спидометра: 240° снизу-слева направо. */
@Composable
private fun Arc(fraction: Float, color: Color, track: Color, width: Float) {
    Canvas(Modifier.fillMaxSize()) {
        // Дуга занимает по высоте 1,5 радиуса (сверху целиком, снизу до 30°) — центрируем именно её.
        val d = minOf(size.width * 0.95f, size.height * 1.3f)
        val r = d / 2
        val cy = (size.height - 1.5f * r) / 2 + r
        val tl = Offset((size.width - d) / 2, cy - r)
        val sz = Size(d, d)
        drawArc(track, 150f, 240f, false, tl, sz, style = Stroke(width, cap = StrokeCap.Round))
        drawArc(color, 150f, 240f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(width, cap = StrokeCap.Round))
    }
}
