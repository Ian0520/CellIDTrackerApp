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
    ): Result<CellLocationResult> = withContext(Dispatchers.IO) {
        try {
            val url =
                "https://www.googleapis.com/geolocation/v1/geolocate?key=$API_KEY"

            // 建 request JSON
            val root = JSONObject().apply {
                put("considerIp", false)
                put("cellTowers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mobileCountryCode", mcc)
                        put("mobileNetworkCode", mnc)
                        put("locationAreaCode", lac)
                        put("cellId", cellId)
                        put("radioType", radioType)
                    })
                })
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
