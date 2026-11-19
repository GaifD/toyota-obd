package com.gaifd.toyotaobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaifd.toyotaobd.service.Level

@Composable
fun Card(modifier: Modifier = Modifier, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.card)
            .padding(horizontal = padding.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun ScreenTitle(text: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (hint != null) Text(hint, fontSize = 12.sp, color = C.muted)
    }
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (hint != null) Text(hint, fontSize = 12.sp, color = C.muted)
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

@Composable
fun ToggleRow(title: String, hint: String, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = C.onAccent,
                    checkedTrackColor = C.accent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = C.muted,
                    uncheckedTrackColor = C.border,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
        if (divider) Divider()
    }
}

@Composable
fun ActionRow(
    title: String,
    hint: String,
    icon: ImageVector? = Ic.chevronRight,
    iconTint: Color = C.muted,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, fontSize = 12.sp, color = C.muted, lineHeight = 16.sp)
            }
            if (icon != null) {
                Spacer(Modifier.width(12.dp))
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        if (divider) Divider()
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(99.dp))
            .then(if (selected) Modifier.background(C.accentBg) else Modifier.border(1.dp, C.border, RoundedCornerShape(99.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) C.accent else C.soft)
    }
}

/** Главная кнопка: залитая цветом приложения или контурная. */
@Composable
fun Button(text: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(if (enabled) C.accent else C.border) else Modifier.border(1.dp, C.border, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (filled) (if (enabled) C.onAccent else C.muted) else C.text
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun Banner(text: String, color: Color = C.amber, bg: Color = C.amberBg, icon: ImageVector = Ic.alert, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 13.sp, color = C.text, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Ic.chevronRight, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/** Плитка «значение + подпись». */
@Composable
fun Stat(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, color: Color = C.text, big: Boolean = false) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontFamily = Chakra, fontWeight = FontWeight.Bold, fontSize = if (big) 30.sp else 22.sp, color = color, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 12.sp, color = C.muted, modifier = Modifier.padding(bottom = if (big) 5.dp else 3.dp))
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = C.text, divider: Boolean = true) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(key, fontSize = 13.sp, color = C.soft, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
        }
        if (divider) Divider()
    }
}

@Composable
fun levelColor(l: Level): Color = when (l) {
    Level.BAD -> C.red
    Level.WARN -> C.amber
    Level.OK -> C.green
    Level.INFO -> C.accent
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(99.dp)).background(color))
}

/**
 * Простой линейный график. [lines] — серии точек (время, значение) со своими цветами.
 * [refs] — горизонтальные пунктирные отметки (например, 0,45 В).
 */
@Composable
fun LineChart(
    lines: List<Pair<List<Pair<Long, Double>>, Color>>,
    modifier: Modifier = Modifier,
    minY: Double? = null,
    maxY: Double? = null,
    refs: List<Double> = emptyList(),
    windowMs: Long? = null,
) {
    val grid = C.line
    val refColor = C.faint
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val all = lines.flatMap { it.first }
        if (all.isEmpty()) return@Canvas
        val tMax = all.maxOf { it.first }
        val tMin = windowMs?.let { tMax - it } ?: all.minOf { it.first }
        val vMin = minY ?: all.minOf { it.second }
        var vMax = maxY ?: all.maxOf { it.second }
        if (vMax - vMin < 1e-6) vMax = vMin + 1
        val w = size.width
        val h = size.height
        fun x(t: Long) = ((t - tMin).toFloat() / (tMax - tMin).coerceAtLeast(1)) * w
        fun y(v: Double) = h - ((v - vMin) / (vMax - vMin)).toFloat().coerceIn(0f, 1f) * h
        for (i in 0..3) {
            val yy = h * i / 3
            drawLine(grid, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
        }
        for (r in refs) {
            drawLine(refColor, Offset(0f, y(r)), Offset(w, y(r)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        for ((pts, color) in lines) {
            val vis = pts.filter { it.first >= tMin }
            if (vis.size < 2) continue
            val p = Path()
            vis.forEachIndexed { i, (t, v) -> if (i == 0) p.moveTo(x(t), y(v)) else p.lineTo(x(t), y(v)) }
            drawPath(p, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}

/** Столбики (например, расход по скоростям) с цифрой над каждым. */
@Composable
fun BarChart(
    values: List<Double?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
    decimals: Int = 1,
    info: ((Int) -> String)? = null,
) {
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val accent = C.accent
    val dim = C.border
    val textColor = C.soft.toArgb()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textPx = with(density) { 10.sp.toPx() }
    val maxV = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .pointerInput(values, info) {
                    if (info == null) return@pointerInput
                    // Нажатие на столбик — подробности под графиком.
                    detectTapGestures { pos ->
                        val i = (pos.x / (size.width / values.size.coerceAtLeast(1))).toInt().coerceIn(0, values.size - 1)
                        selected = if (selected == i || values.getOrNull(i) == null) null else i
                    }
                },
        ) {
            val n = values.size
            if (n == 0) return@Canvas
            val top = textPx * 1.6f
            val usable = size.height - top
            val slot = size.width / n
            val bw = slot * 0.62f
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = textColor
                textSize = textPx
                textAlign = android.graphics.Paint.Align.CENTER
            }
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val bh = (v / maxV).toFloat() * usable
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(
                    color = when {
                        selected != null -> if (selected == i) accent else dim
                        highlight == null || highlight == i -> accent
                        else -> dim
                    },
                    topLeft = Offset(x, size.height - bh),
                    size = androidx.compose.ui.geometry.Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
                val label = if (decimals == 0) Math.round(v).toString() else "%.${decimals}f".format(v).replace('.', ',')
                drawContext.canvas.nativeCanvas.drawText(label, x + bw / 2, size.height - bh - textPx * 0.4f, paint)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach {
                Text(it, fontSize = 9.sp, color = C.muted, modifier = Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val sel = selected
        if (info != null && sel != null && sel < values.size) {
            Text(info(sel), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.text, modifier = Modifier.padding(top = 8.dp), lineHeight = 18.sp)
        } else if (info != null) {
            Text("Нажми на столбик — подробности", fontSize = 11.sp, color = C.faint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

fun fmt(v: Double?, d: Int = 1): String {
    if (v == null || v.isNaN()) return "—"
    return if (d == 0) Math.round(v).toString() else "%.${d}f".format(v).replace('.', ',')
}

fun dur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "$h ч ${m} мин" else "$m мин"
}
