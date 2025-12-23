package com.example.cellidtracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// 你原本用在地圖上的資料 class
data class CellLocationResult(
    val lat: Double,
    val lon: Double,
    val range: Double? = null    // accuracy (meters)
)

data class CellTowerParams(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val radioType: String = "lte"
)

object GoogleGeolocationClient {

    // TODO: 換成你自己的 API Key（最好放在更安全的位置，這裡是示意）
    private const val API_KEY = "AIzaSyBftPJXqqEc7KcUzvhaP_82kr1rDT85yIE"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 用 MCC / MNC / LAC / CID 呼叫 Google Geolocation API 換經緯度
     */
    suspend fun queryByCell(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Int,
        // 可選：radioType 你可以依實際情況傳 "lte" / "wcdma" / "gsm"...
        radioType: String = "lte"
    ): Result<CellLocationResult> = queryByCells(
        listOf(CellTowerParams(mcc, mnc, lac, cellId, radioType))
    )

    /**
     * 使用多個 cell towers 提升定位穩定度/精度。
     */
    suspend fun queryByCells(
        towers: List<CellTowerParams>
    ): Result<CellLocationResult> = withContext(Dispatchers.IO) {
        if (towers.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No cell towers provided"))
        }
        try {
            val url =
                "https://www.googleapis.com/geolocation/v1/geolocate?key=$API_KEY"

            // 建 request JSON
            val root = JSONObject().apply {
                put("considerIp", false)
                val arr = JSONArray()
                towers.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("mobileCountryCode", t.mcc)
                        put("mobileNetworkCode", t.mnc)
                        put("locationAreaCode", t.lac)
                        put("cellId", t.cid)
                        put("radioType", t.radioType)
                    })
                }
                put("cellTowers", arr)
            }
            val requestJson = root.toString()
            val body: RequestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)

            val req = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${resp.code} ${resp.message} body=$respStr request=$requestJson")
                    )
                }

                val obj = JSONObject(respStr)

                if (!obj.has("location")) {
                    return@withContext Result.failure(
                        RuntimeException("No 'location' in response: $respStr")
                    )
                }

                val location = obj.getJSONObject("location")
                val lat = location.getDouble("lat")
                val lon = location.getDouble("lng")
                val acc = obj.optDouble("accuracy", Double.NaN)
                val range = if (acc.isNaN()) null else acc

                Result.success(
                    CellLocationResult(
                        lat = lat,
                        lon = lon,
                        range = range
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
