package com.example.cellidtracker

import android.content.Context
import org.json.JSONObject

object AssetConfig {
    /** 列出 assets/config/ 下面有哪些子資料夾（視為 carrier 名稱）*/
    fun listCarriers(ctx: Context): List<String> {
        return runCatching { ctx.assets.list("config")?.toList().orEmpty() }.getOrElse { emptyList() }
            .filter { it.isNotBlank() }
    }

    /** 列出某個 carrier 目錄下有哪些檔案（不進一步遞迴）*/
    fun listFiles(ctx: Context, carrier: String): List<String> {
        val base = "config/$carrier"
        return runCatching { ctx.assets.list(base)?.toList().orEmpty() }.getOrElse { emptyList() }
            .filter { it.isNotBlank() }
    }

    /** 讀取文字檔內容（以 UTF-8 當作文字）*/
    fun readText(ctx: Context, path: String): String {
        return ctx.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 將整個 config 轉成 JSON：{ carriers: { CHT:{file:content,...}, ... }, summary:{...} } */
    fun dumpAllAsJson(ctx: Context): String {
        val carriers = listCarriers(ctx)
        val root = JSONObject()
        val carriersObj = JSONObject()
        var fileCount = 0

        carriers.forEach { c ->
            val files = listFiles(ctx, c)
            val obj = JSONObject()
            files.forEach { f ->
                val path = "config/$c/$f"
                runCatching {
                    val text = readText(ctx, path)
                    obj.put(f, text)
                    fileCount += 1
                }
            }
            carriersObj.put(c, obj)
        }
        root.put("carriers", carriersObj)
        root.put("summary", JSONObject().apply {
            put("carrierCount", carriers.size)
            put("fileCount", fileCount)
        })
        return root.toString()
    }
}
