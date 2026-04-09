package com.example.cellidtracker.history

import android.content.Context
import com.example.cellidtracker.CellTowerParams
import com.example.cellidtracker.data.HistoryDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun encodeTowers(towers: List<CellTowerParams>): String =
    JSONArray().apply {
        towers.forEach { t ->
            put(
                JSONObject()
                    .put("mcc", t.mcc)
                    .put("mnc", t.mnc)
                    .put("lac", t.lac)
                    .put("cid", t.cid)
                    .put("radio", t.radioType)
            )
        }
    }.toString()

fun decodeTowers(json: String): List<CellTowerParams> =
    runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CellTowerParams(
                        mcc = o.optInt("mcc"),
                        mnc = o.optInt("mnc"),
                        lac = o.optInt("lac"),
                        cid = o.optInt("cid"),
                        radioType = o.optString("radio", "lte")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

suspend fun exportHistoryToFile(ctx: Context, db: HistoryDatabase): File = withContext(Dispatchers.IO) {
    val all = db.historyDao().getAll()
    val arr = JSONArray()
    all.forEach { e ->
        arr.put(
            JSONObject()
                .put("victim", e.victim)
                .put("mcc", e.mcc)
                .put("mnc", e.mnc)
                .put("lac", e.lac)
                .put("cid", e.cid)
                .put("lat", e.lat)
                .put("lon", e.lon)
                .put("accuracy", e.accuracy)
                .put("timestampMillis", e.timestampMillis)
                .put("towersCount", e.towersCount)
                .put("towers", JSONArray(e.towersJson))
                .put("moving", e.moving)
                .put("deltaMs", e.deltaMs)
        )
    }
    val outFile = File(ctx.getExternalFilesDir(null), "probe_history.json")
    outFile.writeText(arr.toString(2))
    outFile
}
