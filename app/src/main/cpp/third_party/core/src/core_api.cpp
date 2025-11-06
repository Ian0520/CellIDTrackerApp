#include "core_api.h"
#include <sstream>
#include <ctime>

namespace core {

    std::string process(const std::string& input) {
        return std::string("echo: ") + input;
    }

    std::string detectCarrier() {
        // TODO: 之後用你原本 CLI 邏輯判斷（目前先回 TBD/UNKNOWN）
        return "UNKNOWN";
    }

    WifiInfo queryWifiInfo() {
        // TODO: 之後由 Kotlin 查 Wi-Fi 狀態傳入，或在這裡接你原邏輯
        WifiInfo w{};
        w.enabled = false;
        w.ssid = "";
        return w;
    }

    ImsInfo queryImsInfo() {
        // TODO: 之後由 Kotlin 取得或你移植既有查詢流程
        ImsInfo i{};
        i.registered = false;
        i.rat = "UNKNOWN";
        return i;
    }

    long long nowEpoch() {
        return static_cast<long long>(std::time(nullptr));
    }


    std::string runReport(const std::string& victimNumber) {
        Report rpt{};
        rpt.victim  = victimNumber;
        rpt.carrier = detectCarrier();
        rpt.wifi    = queryWifiInfo();
        rpt.ims     = queryImsInfo();
        rpt.ts      = nowEpoch();
        return toJson(rpt);
    }

    std::string runReportWithWifi(const std::string& victim, bool wifiEnabled, const std::string& ssid) {
        Report rpt{};
        rpt.victim  = victim;
        rpt.carrier = detectCarrier();   // 之後再接
        rpt.ims     = queryImsInfo();    // 之後再接
        rpt.wifi.enabled = wifiEnabled;
        rpt.wifi.ssid    = ssid;
        rpt.ts = nowEpoch();
        return toJson(rpt);
    }
std::string runReportWithWifiAndCarrier(const std::string& victim,
                                        bool wifiEnabled,
                                        const std::string& ssid,
                                        const std::string& carrier) {
        Report rpt{};
        rpt.victim  = victim;
        rpt.carrier = carrier.empty() ? "UNKNOWN" : carrier; // 用手機回報的名稱
        rpt.wifi.enabled = wifiEnabled;
        rpt.wifi.ssid    = ssid;
        rpt.ims     = queryImsInfo(); // 先維持 UNKNOWN
        rpt.ts      = nowEpoch();
        return toJson(rpt);
    }

    std::string runReportWithConfig(const std::string& victim,
                                bool wifiEnabled,
                                const std::string& ssid,
                                const std::string& carrier,
                                const std::string& configJson) {
    Report rpt{};
    rpt.victim  = victim;
    rpt.carrier = carrier.empty() ? "UNKNOWN" : carrier;
    rpt.wifi.enabled = wifiEnabled;
    rpt.wifi.ssid    = ssid;
    rpt.ims     = queryImsInfo(); // 先維持 UNKNOWN
    rpt.ts      = nowEpoch();

    // 這裡先做個「摘要」：不做 JSON 解析，只把字數/長度存進去，確定管線 OK
    // 之後你要的規則（例如用 configJson 內容推斷 carrier）我們再補。
    std::ostringstream sum;
    sum << "config_json_len=" << configJson.size();
    rpt.configSummary = sum.str();

    return toJson(rpt);
}

std::string toJson(const Report& r) {
    std::ostringstream oss;
    oss << "{"
        << "\"victim\":\""  << r.victim   << "\","
        << "\"carrier\":\"" << r.carrier  << "\","
        << "\"wifi\":{"
           << "\"enabled\":" << (r.wifi.enabled ? "true" : "false") << ","
           << "\"ssid\":\""  << r.wifi.ssid << "\"},"
        << "\"ims\":{"
           << "\"registered\":" << (r.ims.registered ? "true" : "false") << ","
           << "\"rat\":\""      << r.ims.rat << "\"},"
        << "\"ts\":" << r.ts << ","
        << "\"configSummary\":\"" << r.configSummary << "\""
        << "}";
    return oss.str();
}



} // namespace core
