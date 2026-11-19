package com.gaifd.toyotaobd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Тонкие линейные иконки — как в самокате и радио. */
private fun stroke(width: Float, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (p in paths) {
        b.addPath(
            pathData = addPathNodes(p),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object Ic {
    val gauge by lazy { stroke(2f, "M4.5 17.5a9 9 0 1 1 15 0", "M12 14l4-4.5", "M10.5 14a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0") }
    val route by lazy { stroke(2f, "M6 19a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M18 9a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M6 15V9a3 3 0 0 1 3-3h7", "M18 9v6a3 3 0 0 1-3 3H8") }
    val wrench by lazy { stroke(2f, "M14.7 6.3a4 4 0 0 0 5 5L21 13l-8 8-2-2 1.3-1.3a4 4 0 0 0-5-5L3 16.4V20h3.6l4.1-4.1", "M14.7 6.3L17 4l3 3-2.3 2.3") }
    val sliders by lazy {
        stroke(
            2f,
            "M4 6h10", "M18 6h2", "M14 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
            "M4 12h4", "M12 12h8", "M8 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
            "M4 18h12", "M16 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        )
    }
    val fuel by lazy { stroke(2f, "M4 20V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v14", "M3 20h12", "M7 8h4", "M14 10h2a2 2 0 0 1 2 2v4a1.5 1.5 0 0 0 3 0V9l-3-3") }
    val plug by lazy { stroke(2f, "M9 3v5", "M15 3v5", "M6 8h12v3a6 6 0 0 1-12 0V8z", "M12 17v4") }
    val check by lazy { stroke(2.5f, "M5 12l5 5L20 7") }
    val chevronRight by lazy { stroke(2f, "M9 6l6 6-6 6") }
    val alert by lazy {
        stroke(
            2f,
            "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z",
            "M12 9v4", "M12 17h.01",
        )
    }
    val info by lazy { stroke(2f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 11v5", "M12 8h.01") }
    val close by lazy { stroke(2f, "M6 6l12 12", "M18 6L6 18") }
    val refresh by lazy { stroke(2f, "M20 11a8 8 0 1 0-2.3 5.7", "M20 5v6h-6") }
    val trash by lazy { stroke(2f, "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3") }
    val cat by lazy { stroke(2f, "M3 12h3", "M18 12h3", "M6 8h12v8H6z", "M9 8v8", "M12 8v8", "M15 8v8") }
    val pulse by lazy { stroke(2f, "M3 12h4l2-6 4 12 2-6h6") }
    val download by lazy { stroke(2f, "M12 4v11", "M7 10l5 5 5-5", "M5 20h14") }
    val share by lazy { stroke(2f, "M12 3v12", "M7 8l5-5 5 5", "M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5") }
    val bolt by lazy { stroke(2f, "M13 3L5 14h6l-1 7 8-11h-6l1-7z") }
}
