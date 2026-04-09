package com.example.cellidtracker.probe

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.file.Files

data class ProbeAssets(
    val workDir: File,
    val bin: File,
    val configDir: File
)

fun currentVictimFromList(workDir: File): String {
    return try {
        File(workDir, "victim_list")
            .takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    } catch (_: Exception) {
        ""
    }
}

fun ensureProbeAssets(ctx: Context): ProbeAssets {
    val workDir = ctx.filesDir
    val configDir = File(workDir, "config")
    val probeDir = File(workDir, "probe")

    fun assetExists(path: String): Boolean =
        try {
            ctx.assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }

    val abi = (Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a"))
        .firstOrNull { assetExists("probe/$it/spoof") }
        ?: throw IllegalStateException("No bundled probe binary found for any ABI.")

    val binDest = File(probeDir, "spoof")

    fun copyAssetToFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = ctx.assets.list(assetPath) ?: return
        if (children.isEmpty()) return
        for (child in children) {
            val childPath = "$assetPath/$child"
            val possibleDir = ctx.assets.list(childPath)
            if (possibleDir?.isNotEmpty() == true) {
                copyAssetDir(childPath, File(destDir, child))
            } else {
                copyAssetToFile(childPath, File(destDir, child))
            }
        }
    }

    runCatching { binDest.delete() }
    copyAssetToFile("probe/$abi/spoof", binDest)
    binDest.setExecutable(true, true)

    runCatching {
        if (configDir.exists()) configDir.deleteRecursively()
    }
    copyAssetDir("config", configDir)

    val rootVictim = File(workDir, "victim_list")
    if (!rootVictim.exists()) {
        rootVictim.parentFile?.mkdirs()
        runCatching { rootVictim.writeText("") }
    }

    val configVictim = File(configDir, "CHT/victim_list")
    configVictim.parentFile?.mkdirs()
    runCatching {
        Files.deleteIfExists(configVictim.toPath())
        Files.createSymbolicLink(configVictim.toPath(), rootVictim.toPath())
    }.getOrElse {
        runCatching { configVictim.writeText(rootVictim.readText()) }
    }

    return ProbeAssets(workDir = workDir, bin = binDest, configDir = configDir)
}
