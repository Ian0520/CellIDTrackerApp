package com.example.cellidtracker

object NativeBridge {
    init { System.loadLibrary("native_port") }

    external fun process(input: String): String
    external fun runReport(victimNumber: String): String
    external fun runReportWithWifi(victimNumber: String, wifiEnabled: Boolean, ssid: String): String
    external fun runReportWithWifiAndCarrier(victimNumber: String, wifiEnabled: Boolean, ssid: String, carrier: String): String

    // 新增：把 assets 的 config JSON 一起給 C++
    external fun runReportWithConfig(
        victimNumber: String,
        wifiEnabled: Boolean,
        ssid: String,
        carrier: String,
        configJson: String
    ): String

    external fun runOriginalFlow(
        victim: String,
        carrier: String,
        remote: Boolean,
        local: Boolean,
        rl: Boolean,
        unavail: Boolean,
        detect: Boolean,
        verbose: Int,
        allowSystemOps: Boolean
    ): String

}
