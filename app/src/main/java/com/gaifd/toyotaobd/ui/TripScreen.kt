package com.gaifd.toyotaobd.ui

import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gaifd.toyotaobd.data.Riders
import com.gaifd.toyotaobd.data.Series
import com.gaifd.toyotaobd.data.Trip
import com.gaifd.toyotaobd.data.TripPoint
import com.gaifd.toyotaobd.data.Trips
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подробности поездки: сводка, графики, расход по скоростям, бак, попутчики, карта. */
@Composable
fun TripScreen(t: Trip, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pts = remember(t.start) { Series.load(t.start) }
    val allRiders by Riders.items.collectAsState()
    val riders = allRiders.filter { it.trip == t.start }
    var askDelete by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("d MMMM, HH:mm", Locale("ru")) }
    val tf = remember { SimpleDateFormat("HH:mm", Locale.US) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 12.dp)) { Button("Назад", filled = false, onClick = onBack) }
        ScreenTitle(df.format(Date(t.start)), "до ${tf.format(Date(t.end))}")

        Card {
            Row(Modifier.fillMaxWidth()) {
                Stat("Пробег", fmt(t.km, 1), "км", Modifier.weight(1f))
                Stat("Топливо", fmt(t.fuelL, 2), "л", Modifier.weight(1f))
                Stat("Стоимость", fmt(t.cost, 0), "грн", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Расход", fmt(t.l100, 1), "л/100", Modifier.weight(1f))
                Stat("Средняя", fmt(t.avgKmh, 0), "км/ч", Modifier.weight(1f))
                Stat("Максимум", fmt(t.maxKmh, 0), "км/ч", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            KeyValue("Время в пути", dur(t.sec))
            KeyValue("В движении", "${dur(t.movingSec)} · ${fmt(t.avgMovingKmh, 0)} км/ч")
            if (t.evKm > 0.05) KeyValue("На электричестве", "${fmt(t.evKm, 1)} км (${(t.evShare * 100).toInt()}%)")
            KeyValue("Цена топлива", "${fmt(t.price, 2)} грн/л")
            if (t.tankStart != null || t.tankEnd != null) {
                KeyValue("Бак", "${fmt(t.tankStart, 1)} → ${fmt(t.tankEnd, 1)} л", divider = false)
            }
        }

        if (riders.isNotEmpty()) {
            SectionTitle("Попутчики")
            Card {
                val sh = Riders.shares(riders, t.end, t.km, t.fuelL)
                riders.forEach { RiderRow(it, sh[it.id], live = false) }
            }
        }

        if (pts.any { it.lat != null }) {
            SectionTitle("Маршрут", "цвет — расход")
            TrackMap(pts)
        }

        if (pts.size >= 3) {
            val from = pts.first().t
            val to = pts.last().t
            val span = (to - from).coerceAtLeast(1)
            SectionTitle("Скорость", "км/ч")
            Card { LineChart(listOf(pts.map { it.t to it.speed } to C.accent), minY = 0.0, windowMs = span) }
            SectionTitle("Расход", "л/ч")
            Card { LineChart(listOf(pts.map { it.t to it.lph } to C.amber), minY = 0.0, windowMs = span) }
            if (pts.any { it.soc != null }) {
                SectionTitle("Заряд гибридной батареи", "%")
                Card { LineChart(listOf(pts.mapNotNull { p -> p.soc?.let { p.t to it } } to C.green), refs = listOf(40.0), windowMs = span) }
            }
            if (pts.any { it.coolant != null }) {
                SectionTitle("Температура двигателя", "°C")
                Card { LineChart(listOf(pts.mapNotNull { p -> p.coolant?.let { p.t to it } } to C.red), windowMs = span) }
            }
        } else {
            Text("Графики есть у поездок, записанных начиная с версии 1.1.", fontSize = 12.sp, color = C.muted)
        }

        if (t.bucketKm.sum() > 1) {
            SectionTitle("Расход по скоростям", "л/100")
            Card {
                val vals = t.bucketKm.indices.map { i -> if (t.bucketKm[i] >= 0.3) t.bucketL[i] / t.bucketKm[i] * 100 else null }
                BarChart(
                    vals, List(Trip.BUCKETS) { if (it == Trip.BUCKETS - 1) "110+" else "${it * 10}" },
                    info = { i ->
                        "${Trip.bucketLabel(i)} км/ч: ${fmt(vals[i], 1)} л/100 · проехано ${fmt(t.bucketKm[i], 1)} км, сожжено ${fmt(t.bucketL[i], 2)} л"
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button("Удалить поездку", Modifier.fillMaxWidth(), filled = false, icon = Ic.trash) { askDelete = true }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            containerColor = C.card,
            title = { Text("Удалить поездку?") },
            text = { Text("${df.format(Date(t.start))}, ${fmt(t.km, 1)} км. Топливо в баке не изменится.") },
            confirmButton = {
                TextButton(onClick = { Trips.delete(t); askDelete = false; onBack() }) { Text("Удалить", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Отмена", color = C.soft) } },
        )
    }
}

/** Трек на карте OpenStreetMap; цвет участка — по расходу (зелёный — экономно/EV, красный — много). */
@Composable
private fun TrackMap(pts: List<TripPoint>) {
    val geo = pts.filter { it.lat != null && it.lon != null }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(18.dp)),
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            Configuration.getInstance().osmdroidBasePath = ctx.cacheDir
            Configuration.getInstance().osmdroidTileCache = java.io.File(ctx.cacheDir, "tiles")
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                for (i in 1 until geo.size) {
                    val a = geo[i - 1]
                    val b = geo[i]
                    // Разрыв больше минуты — GPS пропадал: не соединяем.
                    if (b.t - a.t > 60_000) continue
                    val l100 = if (b.speed > 3) b.lph / b.speed * 100 else if (b.lph < 0.1) 0.0 else 20.0
                    val color = when {
                        l100 < 4 -> AColor.rgb(74, 222, 128)
                        l100 < 9 -> AColor.rgb(45, 225, 211)
                        l100 < 14 -> AColor.rgb(255, 181, 74)
                        else -> AColor.rgb(255, 107, 107)
                    }
                    val line = Polyline(this)
                    line.setPoints(listOf(GeoPoint(a.lat!!, a.lon!!), GeoPoint(b.lat!!, b.lon!!)))
                    line.outlinePaint.color = color
                    line.outlinePaint.strokeWidth = 10f
                    overlays.add(line)
                }
                val gp = geo.map { GeoPoint(it.lat!!, it.lon!!) }
                if (gp.isNotEmpty()) {
                    controller.setZoom(14.0)
                    controller.setCenter(gp.first())
                    if (gp.size > 1) post {
                        try {
                            zoomToBoundingBox(BoundingBox.fromGeoPoints(gp).increaseByScale(1.2f), false)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        },
        onRelease = { it.onDetach() },
    )
}
