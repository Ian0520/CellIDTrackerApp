package com.example.cellidtracker.experiment

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import androidx.core.content.ContextCompat
import com.example.cellidtracker.BuildConfig
import com.example.cellidtracker.RootShell
import java.io.File
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal const val RESEARCH_CAPTURE_FORMAT_VERSION = 1
internal const val RESEARCH_CAPTURE_MAX_BYTES = 16 * 1024 * 1024
internal const val RESEARCH_CAPTURE_MAX_FILES = 8

internal data class ResearchSessionConfig(
    val sessionId: String,
    val campaignId: String,
    val conditionLabel: String,
    val probeIntervalSeconds: Int,
    val responseLimit: Int?,
    val autoRestart: Boolean,
    val gitCommit: String,
    val notes: String
)

internal data class ResearchExportResult(
    val exportDirectory: File,
    val rawDirectory: File,
    val copiedCaptureFiles: Int
)

internal class ResearchCaptureManager(private val context: Context) {
    private var activeConfig: ResearchSessionConfig? = null
    private var contextFile: File? = null
    private var pseudonymizer: CampaignPseudonymizer? = null
    private var contextWriteFailures = 0L
    private var lastBssidToken: String? = null
    private var bssidInitialized = false
    private var wifiApChangeSequence = 0L
    private var lastNetworkToken: String? = null
    private var networkInitialized = false
    private var connectivityChangeSequence = 0L

    @Synchronized
    fun begin(config: ResearchSessionConfig, nativeBinary: File, rootExecutionDir: String) {
        val cleanup = RootShell.removeResearchCaptureFiles(
            rootExecutionDir,
            RESEARCH_CAPTURE_MAX_FILES
        )
        check(cleanup.exitCode == 0) {
            "Unable to clear stale research captures: ${cleanup.stderr.trim()}"
        }
        activeConfig = config
        contextWriteFailures = 0
        lastBssidToken = null
        bssidInitialized = false
        wifiApChangeSequence = 0
        lastNetworkToken = null
        networkInitialized = false
        connectivityChangeSequence = 0
        pseudonymizer = CampaignPseudonymizer(context, config.campaignId)
        val directory = rawDirectory(config.sessionId).apply { mkdirs() }
        contextFile = File(directory, "caller_context.jsonl")
        File(directory, "session_metadata.json").writeText(
            buildMetadata(config, nativeBinary).toString(2)
        )
        recordContext("session_started")
    }

    @Synchronized
    fun restore(sessionId: String): Boolean {
        val metadataFile = File(rawDirectory(sessionId), "session_metadata.json")
        if (!metadataFile.exists()) return false
        val metadata = JSONObject(metadataFile.readText())
        activeConfig = ResearchSessionConfig(
            sessionId = sessionId,
            campaignId = metadata.getString("campaignId"),
            conditionLabel = metadata.getString("conditionLabel"),
            probeIntervalSeconds = metadata.getInt("probeIntervalSeconds"),
            responseLimit = metadata.optInt("responseLimit").takeIf { !metadata.isNull("responseLimit") },
            autoRestart = metadata.getBoolean("autoRestart"),
            gitCommit = metadata.optString("gitCommit", "unknown"),
            notes = metadata.optString("notes", "")
        )
        pseudonymizer = CampaignPseudonymizer(context, activeConfig!!.campaignId)
        contextFile = File(rawDirectory(sessionId), "caller_context.jsonl")
        recordContext("session_restored")
        return true
    }

    @Synchronized
    fun recordContext(event: String) {
        val config = activeConfig ?: return
        val destination = contextFile ?: return
        runCatching {
            appendRotating(destination, captureCallerContext(config, event).toString() + "\n")
        }.onFailure {
            contextWriteFailures++
        }
    }

    @Synchronized
    fun writeDiagnosticLog(lines: List<String>) {
        val config = activeConfig ?: return
        File(rawDirectory(config.sessionId), "diagnostic.log")
            .writeText(lines.joinToString("\n"))
    }

    @Synchronized
    fun finish(rootExecutionDir: String): ResearchExportResult? {
        val config = activeConfig ?: return null
        recordContext("session_stopping")
        val rawDirectory = rawDirectory(config.sessionId).apply { mkdirs() }
        val nativeDirectory = File(rawDirectory, "native").apply { mkdirs() }
        var copied = 0
        var copyErrors = 0
        // Highest suffix is oldest after rotation. Preserve that order in file names.
        for (suffix in (RESEARCH_CAPTURE_MAX_FILES - 1 downTo 1).map { ".$it" } + listOf("")) {
            val source = "$rootExecutionDir/sip_capture.jsonl$suffix"
            val destination = File(nativeDirectory, "sip_capture.jsonl$suffix")
            val result = RootShell.copyFileFromRoot(source, destination)
            if (result.exitCode == 0) copied++
            else if (result.exitCode != 44) copyErrors++
        }

        val exportDirectory = externalDirectory(config.sessionId).apply { mkdirs() }
        val sanitizer = CaptureSanitizer(requireNotNull(pseudonymizer))
        val exportedFiles = mutableListOf<JSONObject>()
        rawDirectory.walkTopDown().filter(File::isFile).forEach { source ->
            val relative = source.relativeTo(rawDirectory).invariantSeparatorsPath
            val destination = File(exportDirectory, sanitizedRelativePath(relative))
            when {
                ".jsonl" in source.name -> sanitizer.sanitizeJsonLines(source, destination)
                source.name == "session_metadata.json" -> sanitizer.sanitizeJsonFile(source, destination)
                source.name == "diagnostic.log" -> sanitizer.sanitizeTextFile(source, destination)
                else -> return@forEach
            }
            exportedFiles += JSONObject()
                .put("path", destination.relativeTo(exportDirectory).invariantSeparatorsPath)
                .put("bytes", destination.length())
                .put("sha256", sha256(destination))
        }
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("captureFormatVersion", RESEARCH_CAPTURE_FORMAT_VERSION)
            .put("sessionId", config.sessionId)
            .put("campaignId", config.campaignId)
            .put("conditionLabel", config.conditionLabel)
            .put("completedAtMillis", System.currentTimeMillis())
            .put("nativeCaptureFilesCopied", copied)
            .put("nativeCaptureCopyErrors", copyErrors)
            .put("callerContextWriteFailures", contextWriteFailures)
            .put("files", JSONArray(exportedFiles))
            .put("rawDataLocation", "app-private; not included in this export")
        File(exportDirectory, "manifest.json").writeText(manifest.toString(2))
        if (copyErrors == 0) {
            RootShell.removeResearchCaptureFiles(rootExecutionDir, RESEARCH_CAPTURE_MAX_FILES)
        }

        activeConfig = null
        contextFile = null
        pseudonymizer = null
        return ResearchExportResult(exportDirectory, rawDirectory, copied)
    }

    fun nativeCaptureEnvironment(rootExecutionDir: String): String? {
        val config = activeConfig ?: return null
        return buildString {
            append("SIP_CAPTURE_PATH='")
            append(rootExecutionDir)
            append("/sip_capture.jsonl' ")
            append("SIP_CAPTURE_RUN_ID='")
            append(config.sessionId)
            append("' SIP_CAPTURE_MAX_BYTES='")
            append(RESEARCH_CAPTURE_MAX_BYTES)
            append("' SIP_CAPTURE_MAX_FILES='")
            append(RESEARCH_CAPTURE_MAX_FILES)
            append("' SIP_CAPTURE_QUEUE_MAX_BYTES='")
            append(8 * 1024 * 1024)
            append("' ")
        }
    }

    fun isActive(): Boolean = activeConfig != null

    private fun rawDirectory(sessionId: String) =
        File(context.filesDir, "research_captures/$sessionId")

    private fun externalDirectory(sessionId: String) = File(
        requireNotNull(context.getExternalFilesDir(null)),
        "experiment_sessions/${sessionId}_research"
    )

    private fun buildMetadata(config: ResearchSessionConfig, nativeBinary: File): JSONObject =
        JSONObject()
            .put("captureFormatVersion", RESEARCH_CAPTURE_FORMAT_VERSION)
            .put("sessionId", config.sessionId)
            .put("campaignId", config.campaignId)
            .put("conditionLabel", config.conditionLabel)
            .put("probeIntervalSeconds", config.probeIntervalSeconds)
            .put("responseLimit", config.responseLimit ?: JSONObject.NULL)
            .put("autoRestart", config.autoRestart)
            .put("gitCommit", config.gitCommit)
            .put("notes", config.notes)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("apkSha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("nativeBinarySha256", sha256(nativeBinary))
            .put("createdAtMillis", System.currentTimeMillis())

    private fun sanitizedRelativePath(relative: String): String = when {
        ".jsonl" in relative -> relative.replaceFirst(".jsonl", ".sanitized.jsonl")
        relative.endsWith(".json") -> relative.removeSuffix(".json") + ".sanitized.json"
        relative.endsWith(".log") -> relative.removeSuffix(".log") + ".sanitized.log"
        else -> "$relative.sanitized"
    }

    private fun appendRotating(destination: File, line: String) {
        if (destination.exists() && destination.length() + line.toByteArray().size > RESEARCH_CAPTURE_MAX_BYTES) {
            File(destination.path + ".${RESEARCH_CAPTURE_MAX_FILES - 1}").delete()
            for (index in RESEARCH_CAPTURE_MAX_FILES - 2 downTo 1) {
                val source = File(destination.path + ".$index")
                if (source.exists()) source.renameTo(File(destination.path + ".${index + 1}"))
            }
            destination.renameTo(File(destination.path + ".1"))
        }
        destination.appendText(line)
    }

    @SuppressLint("MissingPermission")
    private fun captureCallerContext(config: ResearchSessionConfig, event: String): JSONObject {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val wifiInfo = runCatching { wifi?.connectionInfo }.getOrNull()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val links = activeNetwork?.let(connectivity::getLinkProperties)
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val runtime = Runtime.getRuntime()
        val pseudo = pseudonymizer
        val bssidToken = wifiInfo?.bssid?.let { pseudo?.token("bssid", it) }
        val ssidToken = wifiInfo?.ssid?.let { pseudo?.token("ssid", it) }
        if (bssidInitialized && bssidToken != lastBssidToken) wifiApChangeSequence++
        lastBssidToken = bssidToken
        bssidInitialized = true

        val transports = JSONArray().apply {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) put("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) put("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) put("vpn")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) put("ethernet")
        }
        val networkInterfaces = JSONArray()
        val epdgInterfaces = JSONArray()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (iface.name.startsWith("epdg")) epdgInterfaces.put(iface.name)
                networkInterfaces.put(
                    JSONObject()
                        .put("name", iface.name)
                        .put("isUp", runCatching { iface.isUp }.getOrDefault(false))
                        .put("addresses", JSONArray(iface.inetAddresses.toList().map {
                            pseudo?.token("address", it.hostAddress.orEmpty())
                        }))
                )
            }
        }
        val networkAddresses = links?.linkAddresses?.map {
            pseudo?.token("address", it.address.hostAddress.orEmpty())
        }.orEmpty()
        val dnsServers = links?.dnsServers?.map {
            pseudo?.token("address", it.hostAddress.orEmpty())
        }.orEmpty()
        val gateways = links?.routes?.mapNotNull { route ->
            route.gateway?.hostAddress?.let { pseudo?.token("address", it) }
        }.orEmpty()
        val networkToken = activeNetwork?.let {
            pseudo?.token(
                "network",
                listOf(it.toString(), links?.interfaceName, transports.toString()).joinToString("|")
            )
        }
        if (networkInitialized && networkToken != lastNetworkToken) connectivityChangeSequence++
        lastNetworkToken = networkToken
        networkInitialized = true

        return JSONObject()
            .put("contextFormatVersion", 1)
            .put("event", event)
            .put("sessionId", config.sessionId)
            .put("campaignId", config.campaignId)
            .put("conditionLabel", config.conditionLabel)
            .put("unixMillis", System.currentTimeMillis())
            .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            .put("app", JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("gitCommit", config.gitCommit)
                .put("captureFormatVersion", RESEARCH_CAPTURE_FORMAT_VERSION))
            .put("probeConfig", JSONObject()
                .put("intervalSeconds", config.probeIntervalSeconds)
                .put("responseLimit", jsonValue(config.responseLimit))
                .put("autoRestart", config.autoRestart)
                .put("mode", "remote_probe"))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH)
                .put("baseband", jsonValue(Build.getRadioVersion()))
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("carrierName", jsonValue(runCatching { telephony?.networkOperatorName }.getOrNull()))
            .put("telephony", captureTelephony(telephony))
            .put("wifi", JSONObject()
                .put("rssiDbm", jsonValue(wifiInfo?.rssi))
                .put("frequencyMhz", jsonValue(wifiInfo?.frequency))
                .put("linkSpeedMbps", jsonValue(wifiInfo?.linkSpeed))
                .put("rxLinkSpeedMbps", jsonValue(if (Build.VERSION.SDK_INT >= 29) wifiInfo?.rxLinkSpeedMbps else null))
                .put("txLinkSpeedMbps", jsonValue(if (Build.VERSION.SDK_INT >= 29) wifiInfo?.txLinkSpeedMbps else null))
                .put("bssidToken", jsonValue(bssidToken))
                .put("ssidToken", jsonValue(ssidToken))
                .put("apChangeSequence", wifiApChangeSequence))
            .put("network", JSONObject()
                .put("transports", transports)
                .put("metered", jsonValue(connectivity?.isActiveNetworkMetered))
                .put("networkToken", jsonValue(networkToken))
                .put("changeSequence", connectivityChangeSequence)
                .put("interfaceName", jsonValue(links?.interfaceName))
                .put("addresses", JSONArray(networkAddresses))
                .put("dns", JSONArray(dnsServers))
                .put("gateways", JSONArray(gateways))
                .put("interfaces", networkInterfaces)
                .put("epdgInterfaces", epdgInterfaces))
            .put("power", JSONObject()
                .put("batteryPercent", jsonValue(battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)))
                .put("charging", jsonValue(battery?.isCharging))
                .put("powerSave", jsonValue(power?.isPowerSaveMode))
                .put("deviceIdle", jsonValue(power?.isDeviceIdleMode))
                .put("interactive", jsonValue(power?.isInteractive))
                .put("thermalStatus", jsonValue(if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null)))
            .put("process", JSONObject()
                .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("javaMaxBytes", runtime.maxMemory())
                .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                .put("storageUsableBytes", context.filesDir.usableSpace))
    }

    @SuppressLint("MissingPermission")
    private fun captureTelephony(telephony: TelephonyManager?): JSONObject {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val output = JSONObject().put("readPhoneStateGranted", permissionGranted)
        if (!permissionGranted || telephony == null) {
            return output.put("subscriptions", JSONArray())
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val imsManager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(ImsManager::class.java)
        } else null
        val subscriptions = runCatching {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())
        return output.put("subscriptions", JSONArray().apply {
            subscriptions.forEach { subscription ->
                val perSubscription = runCatching {
                    telephony.createForSubscriptionId(subscription.subscriptionId)
                }.getOrNull()
                val wifiCallingSettingEnabled = if (Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        imsManager?.getImsMmTelManager(subscription.subscriptionId)
                            ?.isVoWiFiSettingEnabled
                    }.getOrNull()
                } else null
                put(JSONObject()
                    .put("subscriptionId", subscription.subscriptionId)
                    .put("simSlotIndex", subscription.simSlotIndex)
                    .put("carrierName", jsonValue(subscription.carrierName?.toString()))
                    .put("voiceNetworkType", jsonValue(runCatching { perSubscription?.voiceNetworkType }.getOrNull()))
                    .put("dataNetworkType", jsonValue(runCatching { perSubscription?.dataNetworkType }.getOrNull()))
                    .put("callState", jsonValue(runCatching { perSubscription?.callState }.getOrNull()))
                    .put("dataActivity", jsonValue(runCatching { perSubscription?.dataActivity }.getOrNull()))
                    .put("networkRoaming", jsonValue(runCatching { perSubscription?.isNetworkRoaming }.getOrNull()))
                    .put("wifiCallingSettingEnabled", jsonValue(wifiCallingSettingEnabled))
                    .put("wifiCallingAvailable", JSONObject.NULL)
                    .put("imsRegistered", JSONObject.NULL)
                    .put("imsTransport", JSONObject.NULL))
            }
        })
    }
}

internal class CampaignPseudonymizer internal constructor(private val key: ByteArray) {
    constructor(context: Context, campaignId: String) : this(loadCampaignKey(context, campaignId))

    fun token(kind: String, value: String): String {
        if (value.isBlank()) return value
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal("$kind:$value".toByteArray())
        return "$kind-" + digest.joinToString("") { "%02x".format(it) }.take(20)
    }
}

internal class CaptureSanitizer(private val pseudonymizer: CampaignPseudonymizer) {
    private val phone = Regex(
        "(?<![A-Za-z0-9])(?:\\+[0-9][0-9 -]{7,14}[0-9]|0[0-9][0-9 -]{6,13}[0-9])(?![A-Za-z0-9])"
    )
    private val ipv4 = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex(
        "(?i)(?<![0-9a-f:])(?=[0-9a-f:]*:)(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}" +
            "(?:%[A-Za-z0-9._-]+)?(?![0-9a-f:])"
    )
    private val mac = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val sipUser = Regex("(?i)(sip:)([^@;>\\s]+)")
    private val sipParameter = Regex(
        "(?i)(\\b(?:branch|tag|i-wlan-node-id|ue-local-ip-address)=)(\\\"?)([^;>,\\s\\\"]+)(\\\"?)"
    )
    private val uuid = Regex(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"
    )
    private val apiKey = Regex("AIza[0-9A-Za-z_-]{30,}")
    private val credentialHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "authentication-info"
    )

    fun sanitizeJsonLines(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { writer ->
            source.useLines { lines ->
                lines.forEach { line ->
                    val sanitized = runCatching { sanitizeRecord(JSONObject(line)) }
                        .getOrElse { JSONObject().put("sanitizationError", it.message).put("rawLineDropped", true) }
                    writer.append(sanitized.toString()).append('\n')
                }
            }
        }
    }

    fun sanitizeTextFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.bufferedReader().useLines { lines ->
            destination.bufferedWriter().use { writer ->
                lines.forEach { writer.append(sanitizeText(it)).append('\n') }
            }
        }
    }

    fun sanitizeJsonFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val sanitized = sanitizeRecord(JSONObject(source.readText()))
        destination.writeText(sanitized.toString(2))
    }

    internal fun sanitizeRecord(record: JSONObject): JSONObject {
        listOf(
            "source_address",
            "destination_address",
            "call_id",
            "via_branch",
            "from_tag",
            "to_tag",
            "dialog_id",
            "retransmission_key",
            "payload_hash_fnv1a64"
        ).forEach { field ->
            if (record.has(field) && !record.isNull(field)) {
                record.put(field, pseudonymizer.token(field, record.optString(field)))
            }
        }
        val headers = record.optJSONArray("headers")
        if (headers != null) {
            for (index in 0 until headers.length()) {
                val header = headers.getJSONObject(index)
                val name = header.optString("name")
                val value = when (name.lowercase()) {
                    in credentialHeaders -> "<redacted>"
                    "call-id", "i" -> pseudonymizer.token("call-id", header.optString("value").trim())
                    else -> sanitizeText(header.optString("value"))
                }
                header.put("value", value)
            }
        }
        val recognizedSip = record.optBoolean("recognized_sip", false)
        for (field in listOf("payload_base64", "body_base64")) {
            val encoded = record.optString(field)
            if (encoded.isEmpty()) continue
            if (!recognizedSip) {
                record.put(field, JSONObject.NULL)
                record.put("unrecognizedPayloadRemoved", true)
                continue
            }
            val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.ISO_8859_1)
            val sanitized = sanitizeSip(decoded).toByteArray(StandardCharsets.ISO_8859_1)
            record.put(field, Base64.getEncoder().encodeToString(sanitized))
        }
        sanitizeNestedStrings(record)
        record.put("sanitized", true)
        return record
    }

    internal fun sanitizeSip(value: String): String {
        var redactContinuation = false
        return value.lineSequence().joinToString("\r\n") { line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && redactContinuation) {
                " <redacted>"
            } else {
                val name = line.substringBefore(':', "")
                redactContinuation = name.lowercase() in credentialHeaders
                when {
                    redactContinuation -> "$name: <redacted>"
                    name.equals("Call-ID", true) || name == "i" -> {
                        "$name: ${pseudonymizer.token("call-id", line.substringAfter(':').trim())}"
                    }
                    else -> sanitizeText(line)
                }
            }
        }
    }

    internal fun sanitizeText(value: String): String {
        var result = apiKey.replace(value, "<redacted-api-key>")
        result = sipUser.replace(result) {
            it.groupValues[1] + pseudonymizer.token("sip-user", it.groupValues[2])
        }
        result = sipParameter.replace(result) {
            it.groupValues[1] + it.groupValues[2] +
                pseudonymizer.token("sip-parameter", it.groupValues[3]) + it.groupValues[4]
        }
        result = uuid.replace(result) { pseudonymizer.token("uuid", it.value.lowercase()) }
        result = mac.replace(result) { pseudonymizer.token("mac", it.value.lowercase()) }
        result = ipv4.replace(result) { pseudonymizer.token("ip", it.value) }
        result = ipv6.replace(result) { pseudonymizer.token("ip", it.value.lowercase()) }
        result = phone.replace(result) { pseudonymizer.token("phone", it.value.filter(Char::isDigit)) }
        return result
    }

    private fun sanitizeNestedStrings(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                if (key == "payload_base64" || key == "body_base64") return@forEach
                when (val child = value.opt(key)) {
                    is String -> value.put(key, sanitizeText(child))
                    else -> sanitizeNestedStrings(child)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                when (val child = value.opt(index)) {
                    is String -> value.put(index, sanitizeText(child))
                    else -> sanitizeNestedStrings(child)
                }
            }
        }
    }
}

private fun loadCampaignKey(context: Context, campaignId: String): ByteArray {
    val keyDirectory = File(context.filesDir, "research_keys").apply { mkdirs() }
    val keyName = MessageDigest.getInstance("SHA-256")
        .digest(campaignId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    val keyFile = File(keyDirectory, "$keyName.key")
    return if (keyFile.exists()) {
        keyFile.readBytes()
    } else {
        ByteArray(32).also(SecureRandom()::nextBytes).also(keyFile::writeBytes)
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL
