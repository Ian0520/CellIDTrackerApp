package com.example.cellidtracker

import android.content.Context
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * 用 OpenStreetMap (osmdroid) 畫地圖：
 * - 如果 lat/lon 為 null：顯示預設位置（台北 101）
 * - 如果有 lat/lon：把地圖移到該點並放一個 Marker（這裡先用內建的中心點，不另外畫圖示）
 */
@Composable
fun CellMapView(
    lat: Double?,
    lon: Double?,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    // 初始化 osmdroid 的設定（一定要設 userAgent，避免被伺服器擋掉）
    LaunchedEffect(Unit) {
        setupOsmdroid(ctx)
    }

    // 記住 MapView，避免每次 recomposition 都重建
    var mapView by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
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
            // 每次 lat/lon 有改變就更新中心點
            if (lat != null && lon != null) {
                val point = GeoPoint(lat, lon)
                view.controller.setZoom(16.0)
                view.controller.setCenter(point)
                // 如果之後你想加 marker，可以再擴充 overlay
            } else {
                // 沒座標就維持預設：可以視需要移回台北
                val defaultPoint = GeoPoint(25.033968, 121.564468)
                view.controller.setZoom(12.0)
                view.controller.setCenter(defaultPoint)
            }
        }
    )
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
