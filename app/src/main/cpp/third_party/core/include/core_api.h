#pragma once
#include <string>

namespace core {

struct WifiInfo { bool enabled = false; std::string ssid; };
struct ImsInfo  { bool registered = false; std::string rat; };

struct Report {
  std::string victim;
  std::string carrier; // TWM / CHT / FET / UNKNOWN / device string
  WifiInfo wifi;
  ImsInfo ims;
  long long ts = 0;
  // 新增：暫存來自 assets 的摘要（先放 summary 字串，未來換成你要的欄位）
  std::string configSummary;
};

std::string process(const std::string& input);
std::string runReport(const std::string& victim);
std::string runReportWithWifi(const std::string& victim, bool wifiEnabled, const std::string& ssid);
std::string runReportWithWifiAndCarrier(const std::string& victim, bool wifiEnabled, const std::string& ssid, const std::string& carrier);

// 新增：接受 config JSON
std::string runReportWithConfig(const std::string& victim,
                                bool wifiEnabled,
                                const std::string& ssid,
                                const std::string& carrier,
                                const std::string& configJson);

// 你原本的子功能
std::string detectCarrier();
WifiInfo    queryWifiInfo();
ImsInfo     queryImsInfo();
long long   nowEpoch();
std::string toJson(const Report& rpt);

} // namespace core
