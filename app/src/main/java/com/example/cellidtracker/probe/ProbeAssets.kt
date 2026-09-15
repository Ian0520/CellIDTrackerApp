package com.example.cellidtracker.probe

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import com.example.cellidtracker.RootShell
import java.io.File
import java.io.IOException

data class ProbeAssets(
    val workDir: File,
    val bin: File,
    val configDir: File,
    val rootExecutionDir: String
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
        if (configVictim.exists() && !configVictim.delete()) {
            throw IOException("Unable to replace ${configVictim.absolutePath}")
        }
        Os.symlink(rootVictim.absolutePath, configVictim.absolutePath)
    }.getOrElse {
        runCatching { configVictim.writeText(rootVictim.readText()) }
    }

    val assets = ProbeAssets(
        workDir = workDir,
        bin = binDest,
        configDir = configDir,
        rootExecutionDir = "/data/local/tmp/cellidtracker-${Process.myUid()}"
    )
    stageProbeAssetsForRoot(assets)
    return assets
}

private fun stageProbeAssetsForRoot(assets: ProbeAssets) {
    val rootDir = assets.rootExecutionDir
    val prepare = RootShell.runAsRoot(
        "rm -rf '$rootDir' && mkdir -p '$rootDir/probe' '$rootDir/config' && chmod 700 '$rootDir'"
    )
    if (prepare.exitCode != 0) {
        throw IOException("Unable to prepare root probe directory: ${prepare.stderr.trim()}")
    }

    copyToRoot(assets.bin, "$rootDir/probe/spoof", executable = true)
    assets.configDir.walkTopDown()
        .filter(File::isFile)
        .forEach { source ->
            val relative = source.relativeTo(assets.configDir).invariantSeparatorsPath
            copyToRoot(source, "$rootDir/config/$relative", executable = false)
        }
    copyToRoot(File(assets.workDir, "victim_list"), "$rootDir/victim_list", executable = false)

    val verify = RootShell.runAsRoot(
        "test -x '$rootDir/probe/spoof' && test -r '$rootDir/victim_list'"
    )
    if (verify.exitCode != 0) {
        throw IOException("Root probe staging verification failed: ${verify.stderr.trim()}")
    }
}

private fun copyToRoot(source: File, destination: String, executable: Boolean) {
    val result = RootShell.copyFileAsRoot(source, destination, executable)
    if (result.exitCode != 0) {
        throw IOException(
            "Unable to stage ${source.name}: ${result.stderr.trim().ifEmpty { "root copy failed" }}"
        )
    }
}
