package com.example.cellidtracker

import android.content.Context
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Color
import kotlin.math.abs
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

enum class CellMapMode(val label: String) {
    Origin("origin"),
    AccuracyScaled("accuracy x0.7"),
    RecentProbes("recent 3 min")
}

data class CellMapProbePoint(
    val lat: Double,
    val lon: Double,
    val accuracy: Double?,
    val timestampMillis: Long
)

/**
 * 用 OpenStreetMap (osmdroid) 畫地圖：
 * - 如果 lat/lon 為 null：顯示預設位置（台北 101）
 * - 如果有 lat/lon：把地圖移到該點並畫出 accuracy circle
 */
@Composable
fun CellMapView(
    lat: Double?,
    lon: Double?,
    accuracy: Double? = null,
    mode: CellMapMode = CellMapMode.Origin,
    recentProbePoints: List<CellMapProbePoint> = emptyList(),
    modifier: Modifier = Modifier
) {
    // 記住 MapView，避免每次 recomposition 都重建
    var mapView by remember { mutableStateOf<MapView?>(null) }
    // 記住覆蓋層，方便更新/移除
    var accuracyOverlays by remember { mutableStateOf<List<Polygon>>(emptyList()) }
    // 記住上次套用的座標/精度，避免每次 recomposition 都重設中心與縮放（會讓使用者無法拖/縮放）
    var lastLat by remember { mutableStateOf<Double?>(null) }
    var lastLon by remember { mutableStateOf<Double?>(null) }
    var lastAcc by remember { mutableStateOf<Double?>(null) }
    var lastMode by remember { mutableStateOf<CellMapMode?>(null) }
    var lastRecentSignature by remember { mutableStateOf("") }
    var hasLocation by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            setupOsmdroid(context)
            MapView(context).apply {
                // 設定地圖來源（MAPNIK = OpenStreetMap 預設瓦片）
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)

                // 預設先看台北 101
                val startPoint = GeoPoint(25.033968, 121.564468)
                controller.setZoom(14.0)
                controller.setCenter(startPoint)

                mapView = this
            }
        },
        update = { view ->
            val newestRecentPoint = recentProbePoints.maxByOrNull { it.timestampMillis }
            val effectiveLat = lat ?: newestRecentPoint?.lat
            val effectiveLon = lon ?: newestRecentPoint?.lon
            val effectiveAccuracy = accuracy ?: newestRecentPoint?.accuracy
            val currentRecentSignature = recentSignature(recentProbePoints)
            // 每次 lat/lon 有改變就更新中心點
            fun changed(a: Double?, b: Double?): Boolean {
                if (a == null || b == null) return true
                return abs(a - b) > 1e-6
            }
            val shouldUpdate = (effectiveLat != null && effectiveLon != null) &&
                (
                    changed(effectiveLat, lastLat) ||
                        changed(effectiveLon, lastLon) ||
                        changed(effectiveAccuracy, lastAcc) ||
                        mode != lastMode ||
                        currentRecentSignature != lastRecentSignature
                    )

            if (effectiveLat != null && effectiveLon != null && shouldUpdate) {
                val point = GeoPoint(effectiveLat, effectiveLon)
                val displayAccuracy = when (mode) {
                    CellMapMode.Origin,
                    CellMapMode.RecentProbes -> effectiveAccuracy
                    CellMapMode.AccuracyScaled -> effectiveAccuracy?.times(0.7)
                }
                // 調整縮放，確保精度圈可以落在可視範圍內
                val zoom = when {
                    displayAccuracy != null && displayAccuracy > 15000 -> 11.5
                    displayAccuracy != null && displayAccuracy > 8000  -> 12.5
                    displayAccuracy != null && displayAccuracy > 4000  -> 13.0
                    displayAccuracy != null && displayAccuracy > 2000  -> 13.5
                    displayAccuracy != null && displayAccuracy > 1000  -> 14.0
                    displayAccuracy != null && displayAccuracy > 500   -> 14.5
                    displayAccuracy != null && displayAccuracy > 200   -> 15.0
                    else -> 16.0
                }
                view.controller.setZoom(zoom)
                view.controller.setCenter(point)
                // 畫精度圈
                accuracyOverlays.forEach { existing ->
                    view.overlays.remove(existing)
                }
                accuracyOverlays = emptyList()
                if (mode == CellMapMode.RecentProbes && recentProbePoints.isNotEmpty()) {
                    val overlays = recentProbePoints
                        .sortedBy { it.timestampMillis }
                        .mapIndexed { index, item ->
                            val itemAccuracy = item.accuracy?.takeIf { it > 0 } ?: 25.0
                            val ratio = if (recentProbePoints.size <= 1) {
                                1f
                            } else {
                                index.toFloat() / (recentProbePoints.size - 1).toFloat()
                            }
                            Polygon(view).apply {
                                points = Polygon.pointsAsCircle(GeoPoint(item.lat, item.lon), itemAccuracy)
                                fillPaint.color = Color.argb((20 + ratio * 75).toInt(), 0, 150, 255)
                                outlinePaint.color = Color.argb((65 + ratio * 130).toInt(), 0, 90, 230)
                                outlinePaint.strokeWidth = 2f
                            }
                        }
                    view.overlays.addAll(overlays)
                    accuracyOverlays = overlays
                    view.invalidate()
                } else if (displayAccuracy != null && displayAccuracy > 0) {
                    val circle = createAccuracyCircle(view, point, displayAccuracy)
                    view.overlays.add(circle)
                    accuracyOverlays = listOf(circle)
                    view.invalidate()
                }
                lastLat = effectiveLat
                lastLon = effectiveLon
                lastAcc = effectiveAccuracy
                lastMode = mode
                lastRecentSignature = currentRecentSignature
                hasLocation = true
            } else {
                // 如果尚未有任何定位結果，第一次顯示預設點；之後不再強制重置，避免來回閃爍
                if (!hasLocation) {
                    val defaultPoint = GeoPoint(25.033968, 121.564468)
                    view.controller.setZoom(12.0)
                    view.controller.setCenter(defaultPoint)
                    lastLat = null
                    lastLon = null
                    lastAcc = null
                }
            }
        }
    )
}

private fun createAccuracyCircle(
    mapView: MapView,
    point: GeoPoint,
    accuracy: Double
): Polygon {
    return Polygon(mapView).apply {
        points = Polygon.pointsAsCircle(point, accuracy)
        fillPaint.color = Color.argb(40, 0, 150, 255) // 淺藍半透明
        outlinePaint.color = Color.argb(120, 0, 120, 255)
        outlinePaint.strokeWidth = 2f
    }
}

private fun recentSignature(points: List<CellMapProbePoint>): String {
    return points.joinToString("|") {
        "${it.timestampMillis}:${it.lat}:${it.lon}:${it.accuracy}"
    }
}

/**
 * 一次性的 osmdroid 初始化：設定 userAgent、讀取偏好設定
 */
private fun setupOsmdroid(context: Context) {
    val cfg = Configuration.getInstance()
    // 設一個合理的 userAgent（官方要求，不然可能被 ban）
    cfg.userAgentValue = context.packageName
    cfg.load(context, PreferenceManager.getDefaultSharedPreferences(context))
}
