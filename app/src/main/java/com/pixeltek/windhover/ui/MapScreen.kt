package com.pixeltek.windhover.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixeltek.windhover.util.Geo
import kotlin.math.max

private val TrailColor = Color(0xFF1E88E5)
private val StartColor = Color(0xFF2E7D32)
private val InkColor = Color(0xFF212121)

private val ScaleSteps = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0)

/**
 * Deliberately simple map: white background, the trail as a polyline, and a heading arrow at the
 * current position. No tiles, no API key. Pinch to zoom, drag to pan, "Follow" recentres on you.
 */
@Composable
fun MapScreen(vm: MainViewModel) {
    val trail by vm.trail.collectAsStateWithLifecycle()
    val fix by vm.latestFix.collectAsStateWithLifecycle()
    val now by rememberNow()
    val textMeasurer = rememberTextMeasurer()

    var follow by rememberSaveable { mutableStateOf(true) }
    var metersPerPx by rememberSaveable { mutableFloatStateOf(1f) }
    var centerLat by rememberSaveable { mutableDoubleStateOf(0.0) }
    var centerLon by rememberSaveable { mutableDoubleStateOf(0.0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Where "you" are: the live fix, or the newest stored sample if the service is not running.
    val current = fix?.let { Marker(it.lat, it.lon, it.accuracyM, it.bearingDeg, it.timeMs) }
        ?: trail.lastOrNull()?.let { Marker(it.lat, it.lon, it.accuracyM, it.bearingDeg, it.timeMs) }

    if (follow && current != null) {
        centerLat = current.lat
        centerLon = current.lon
    }

    fun fitTrail() {
        val pts = trail
        if (pts.isEmpty() || canvasSize == IntSize.Zero) return
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        centerLat = (minLat + maxLat) / 2
        centerLon = (minLon + maxLon) / 2
        val (x0, y0) = Geo.project(minLat, minLon, centerLat, centerLon)
        val (x1, y1) = Geo.project(maxLat, maxLon, centerLat, centerLon)
        val padPx = 80f
        val spanX = kotlin.math.abs(x1 - x0).toFloat()
        val spanY = kotlin.math.abs(y1 - y0).toFloat()
        val mppX = spanX / max(1f, canvasSize.width - padPx)
        val mppY = spanY / max(1f, canvasSize.height - padPx)
        metersPerPx = max(mppX, mppY).coerceIn(0.2f, 500f)
        follow = false
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val oldMpp = metersPerPx
                        val newMpp = (oldMpp / zoom).coerceIn(0.05f, 500f)
                        // Keep the world point under the fingers fixed while zooming, then apply the pan.
                        val dxM = (centroid.x - w / 2) * (oldMpp - newMpp) - pan.x * newMpp
                        val dyM = (centroid.y - h / 2) * (oldMpp - newMpp) - pan.y * newMpp
                        val (lat, lon) = Geo.offset(centerLat, centerLon, dxM.toDouble(), dyM.toDouble())
                        centerLat = lat
                        centerLon = lon
                        metersPerPx = newMpp
                        follow = false
                    }
                },
        ) {
            if (current == null) return@Canvas
            val mpp = metersPerPx
            val origin = Offset(size.width / 2f, size.height / 2f)
            val cLat = centerLat
            val cLon = centerLon

            fun toScreen(lat: Double, lon: Double): Offset {
                val (x, y) = Geo.project(lat, lon, cLat, cLon)
                return Offset(origin.x + (x / mpp).toFloat(), origin.y + (y / mpp).toFloat())
            }

            // Trail
            if (trail.size >= 2) {
                val path = Path()
                trail.forEachIndexed { i, s ->
                    val p = toScreen(s.lat, s.lon)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, TrailColor, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (mpp < 2f) {
                trail.forEach { drawCircle(TrailColor, 2.5.dp.toPx(), toScreen(it.lat, it.lon)) }
            }
            trail.firstOrNull()?.let {
                val p = toScreen(it.lat, it.lon)
                drawCircle(Color.White, 8.dp.toPx(), p)
                drawCircle(StartColor, 6.dp.toPx(), p)
            }

            // Current position: accuracy disc, heading arrow, dot
            val p = toScreen(current.lat, current.lon)
            val accuracyPx = current.accuracyM / mpp
            if (accuracyPx > 12.dp.toPx()) {
                drawCircle(TrailColor.copy(alpha = 0.10f), accuracyPx, p)
                drawCircle(TrailColor.copy(alpha = 0.35f), accuracyPx, p, style = Stroke(1.dp.toPx()))
            }
            current.bearingDeg?.let { bearing ->
                rotate(bearing, pivot = p) {
                    val arrow = Path().apply {
                        moveTo(p.x, p.y - 26.dp.toPx())
                        lineTo(p.x - 9.dp.toPx(), p.y - 9.dp.toPx())
                        lineTo(p.x + 9.dp.toPx(), p.y - 9.dp.toPx())
                        close()
                    }
                    drawPath(arrow, TrailColor)
                }
            }
            drawCircle(Color.White, 11.dp.toPx(), p)
            drawCircle(TrailColor, 8.dp.toPx(), p)

            // Scale bar
            val maxBarPx = size.width * 0.3f
            val barMeters = ScaleSteps.lastOrNull { it / mpp <= maxBarPx } ?: ScaleSteps.first()
            val barPx = (barMeters / mpp).toFloat()
            val x0 = 16.dp.toPx()
            val y0 = size.height - 20.dp.toPx()
            val tick = 6.dp.toPx()
            drawLine(InkColor, Offset(x0, y0), Offset(x0 + barPx, y0), 3.dp.toPx())
            drawLine(InkColor, Offset(x0, y0 - tick), Offset(x0, y0), 3.dp.toPx())
            drawLine(InkColor, Offset(x0 + barPx, y0 - tick), Offset(x0 + barPx, y0), 3.dp.toPx())
            val label = if (barMeters >= 1000) "${(barMeters / 1000).toInt()} km" else "${barMeters.toInt()} m"
            drawText(textMeasurer, label, Offset(x0, y0 - 24.dp.toPx()), TextStyle(fontSize = 12.sp, color = InkColor))

            // North indicator
            drawText(
                textMeasurer, "N", Offset(size.width - 28.dp.toPx(), size.height - 36.dp.toPx()),
                TextStyle(fontSize = 14.sp, color = InkColor, fontWeight = FontWeight.Bold),
            )
        }

        if (current == null) {
            Text(
                "No fixes yet.\nStart tracking from the Status tab.",
                Modifier.align(Alignment.Center).padding(32.dp),
                textAlign = TextAlign.Center,
                color = InkColor,
            )
        }

        Surface(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 3.dp,
        ) {
            Text(
                buildString {
                    append("${trail.size} points")
                    current?.let { append(" · ${formatAge(now - it.timeMs)}") }
                    append(" · %.1f m/px".format(metersPerPx))
                },
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { follow = true }, enabled = !follow) { Text("Follow me") }
            FilledTonalButton(onClick = { fitTrail() }, enabled = trail.size >= 2) { Text("Fit trail") }
            FilledTonalButton(onClick = { metersPerPx = (metersPerPx / 1.6f).coerceAtLeast(0.05f) }) { Text("Zoom in") }
            FilledTonalButton(onClick = { metersPerPx = (metersPerPx * 1.6f).coerceAtMost(500f) }) { Text("Zoom out") }
        }
    }
}

private data class Marker(val lat: Double, val lon: Double, val accuracyM: Float, val bearingDeg: Float?, val timeMs: Long)
