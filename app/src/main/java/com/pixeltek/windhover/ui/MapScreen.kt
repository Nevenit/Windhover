package com.pixeltek.windhover.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixeltek.windhover.data.LocationSample
import com.pixeltek.windhover.util.Geo
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

private data class Marker(val lat: Double, val lon: Double, val accuracyM: Float, val bearingDeg: Float?, val timeMs: Long)

/**
 * Real basemap via MapLibre, tiles from OpenFreeMap. The trail, accuracy disc, start marker and
 * heading arrow are GeoJSON layers updated on every fix. "Follow me" keeps the camera on you until
 * you drag the map; "Fit trail" frames everything stored.
 */
@Composable
fun MapScreen(vm: MainViewModel) {
    val trail by vm.trail.collectAsStateWithLifecycle()
    val fix by vm.latestFix.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by rememberNow()
    val dark = isSystemInDarkTheme()
    val styleUrl = settings.mapStyle.resolve(dark, settings.customStyleUrl)

    var follow by rememberSaveable { mutableStateOf(true) }
    val holder = remember { MapHolder() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val current = fix?.let { Marker(it.lat, it.lon, it.accuracyM, it.bearingDeg, it.timeMs) }
        ?: trail.lastOrNull()?.let { Marker(it.lat, it.lon, it.accuracyM, it.bearingDeg, it.timeMs) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = holder.mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapLibre.getInstance(ctx)
                MapView(ctx).also { mv ->
                    holder.mapView = mv
                    mv.onCreate(null)
                    val state = lifecycleOwner.lifecycle.currentState
                    if (state.isAtLeast(Lifecycle.State.STARTED)) mv.onStart()
                    if (state.isAtLeast(Lifecycle.State.RESUMED)) mv.onResume()
                    mv.getMapAsync { map -> holder.attach(map) }
                }
            },
            update = {
                holder.onUserGesture = { follow = false }
                holder.render(trail, current, follow, styleUrl)
            },
        )

        if (current == null) {
            Surface(Modifier.align(Alignment.Center).padding(32.dp), shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
                Text(
                    "No fixes yet.\nStart tracking from the Status tab.",
                    Modifier.padding(16.dp), textAlign = TextAlign.Center,
                )
            }
        }

        Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp) {
            Text(
                buildString {
                    append("${trail.size} points")
                    current?.let { append(" · ${formatAge(now - it.timeMs)}") }
                },
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { follow = true }, enabled = !follow) { Text("Follow me") }
            FilledTonalButton(onClick = { follow = false; holder.fit(trail) }, enabled = trail.isNotEmpty()) { Text("Fit trail") }
        }

        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(6.dp), tonalElevation = 3.dp) {
            Text(
                "© OpenStreetMap contributors · OpenFreeMap",
                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Owns the MapView and its layers so recompositions only push new GeoJSON. */
private class MapHolder {
    var mapView: MapView? = null
    var onUserGesture: () -> Unit = {}

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var loadedStyleUrl: String? = null
    private var pendingRender: (() -> Unit)? = null
    private var hasCentered = false
    private var lastTarget: LatLng? = null

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isAttributionEnabled = false // we show our own attribution line
            isLogoEnabled = false
            isCompassEnabled = true
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
        }
        pendingRender?.invoke()
    }

    fun render(trail: List<LocationSample>, current: Marker?, follow: Boolean, styleUrl: String) {
        val m = map
        if (m == null) {
            pendingRender = { render(trail, current, follow, styleUrl) }
            return
        }
        if (styleUrl != loadedStyleUrl) {
            loadedStyleUrl = styleUrl
            style = null
            pendingRender = { render(trail, current, follow, styleUrl) }
            m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
                style = s
                installLayers(s)
                pendingRender?.invoke()
            }
            return
        }
        val s = style
        if (s == null || !s.isFullyLoaded) {
            pendingRender = { render(trail, current, follow, styleUrl) }
            return
        }
        pendingRender = null

        s.getSourceAs<GeoJsonSource>(SRC_TRAIL)?.setGeoJson(
            if (trail.size >= 2) {
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(trail.map { Point.fromLngLat(it.lon, it.lat) })))
            } else {
                EMPTY
            },
        )
        s.getSourceAs<GeoJsonSource>(SRC_START)?.setGeoJson(
            trail.firstOrNull()?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) } ?: EMPTY,
        )
        if (current != null) {
            val here = Feature.fromGeometry(Point.fromLngLat(current.lon, current.lat))
            current.bearingDeg?.let { here.addNumberProperty("bearing", it) }
            s.getSourceAs<GeoJsonSource>(SRC_POSITION)?.setGeoJson(FeatureCollection.fromFeature(here))
            s.getSourceAs<GeoJsonSource>(SRC_ACCURACY)?.setGeoJson(
                FeatureCollection.fromFeature(Feature.fromGeometry(circle(current.lat, current.lon, current.accuracyM.toDouble()))),
            )
        } else {
            s.getSourceAs<GeoJsonSource>(SRC_POSITION)?.setGeoJson(EMPTY)
            s.getSourceAs<GeoJsonSource>(SRC_ACCURACY)?.setGeoJson(EMPTY)
        }

        if (follow && current != null) {
            val target = LatLng(current.lat, current.lon)
            if (!hasCentered) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
                hasCentered = true
            } else if (target != lastTarget) {
                m.easeCamera(CameraUpdateFactory.newLatLng(target), 600)
            }
            lastTarget = target
        }
    }

    fun fit(trail: List<LocationSample>) {
        val m = map ?: return
        if (trail.isEmpty()) return
        if (trail.size == 1) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(trail[0].lat, trail[0].lon), 16.0))
            return
        }
        val bounds = LatLngBounds.Builder().apply { trail.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
    }

    fun destroy() {
        mapView?.let {
            it.onPause()
            it.onStop()
            it.onDestroy()
        }
        mapView = null
        map = null
        style = null
        loadedStyleUrl = null
        hasCentered = false
    }

    private fun installLayers(s: Style) {
        s.addImage(IMG_ARROW, arrowBitmap())
        listOf(SRC_TRAIL, SRC_ACCURACY, SRC_START, SRC_POSITION).forEach { s.addSource(GeoJsonSource(it)) }
        s.addLayer(
            LineLayer(LAYER_TRAIL, SRC_TRAIL).withProperties(
                PropertyFactory.lineColor(TRAIL_COLOR),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            FillLayer(LAYER_ACCURACY, SRC_ACCURACY).withProperties(
                PropertyFactory.fillColor(TRAIL_COLOR),
                PropertyFactory.fillOpacity(0.12f),
            ),
        )
        s.addLayer(
            CircleLayer(LAYER_START, SRC_START).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor("#2E7D32"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            SymbolLayer(LAYER_ARROW, SRC_POSITION).withProperties(
                PropertyFactory.iconImage(IMG_ARROW),
                PropertyFactory.iconSize(1.6f),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ).withFilter(Expression.has("bearing")),
        )
        s.addLayer(
            CircleLayer(LAYER_POSITION, SRC_POSITION).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(TRAIL_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    /** A triangle in the top half of a tall bitmap, so rotating about the centre pivots it around the dot. */
    private fun arrowBitmap(): Bitmap {
        val w = 48
        val h = 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor(TRAIL_COLOR) }
        val path = Path().apply {
            moveTo(w / 2f, 4f)
            lineTo(w / 2f - 14f, 34f)
            lineTo(w / 2f + 14f, 34f)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun circle(lat: Double, lon: Double, radiusM: Double, steps: Int = 48): Polygon {
        val ring = (0..steps).map { i ->
            val a = 2 * Math.PI * i / steps
            val (pLat, pLon) = Geo.offset(lat, lon, radiusM * sin(a), -radiusM * cos(a))
            Point.fromLngLat(pLon, pLat)
        }
        return Polygon.fromLngLats(listOf(ring))
    }

    private companion object {
        const val TRAIL_COLOR = "#1E88E5"
        const val SRC_TRAIL = "windhover-trail"
        const val SRC_ACCURACY = "windhover-accuracy"
        const val SRC_START = "windhover-start"
        const val SRC_POSITION = "windhover-position"
        const val LAYER_TRAIL = "windhover-trail-line"
        const val LAYER_ACCURACY = "windhover-accuracy-fill"
        const val LAYER_START = "windhover-start-dot"
        const val LAYER_ARROW = "windhover-heading"
        const val LAYER_POSITION = "windhover-position-dot"
        const val IMG_ARROW = "windhover-arrow"
        val EMPTY: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    }
}
