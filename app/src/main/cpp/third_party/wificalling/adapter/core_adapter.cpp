#include "core_adapter.h"

#include <sstream>
#include <thread>
#include <chrono>

#include "iface.h"
#include "sadb.h"
#include "session.h"
#include "util.h"

// main.cpp 裡用到的常數
static const std::string kEpdgInterface   = "epdg";
static const std::string kWlan0Interface  = "wlan0";
static const std::string kConfigFolderTop = "config";

namespace core_adapter {

static void set_context_from_params(const Params& p) {
  util::context.remoteCellIDProber = p.remoteCellIDProber;
  util::context.localCellIDProber  = p.localCellIDProber;
  util::context.rlRemoteCellIDProber = p.rlRemoteProber;
  util::context.unavailabilityEval = p.unavailabilityEval;
  util::context.detectEval         = p.detectEval;
  util::context.verbose            = p.verbose;

  util::context.calleeId = p.victim;
  util::context.callerId = "";
  util::context.configFolder = kConfigFolderTop + "/" + p.carrier;
}

std::string run_original_flow(const Params& p) {
  std::ostringstream log;

  // 1) 初始化 context（取代 CLI parse）
  set_context_from_params(p);
  log << "[adapter] victim=" << p.victim << ", carrier=" << p.carrier
      << ", verbose=" << p.verbose << "\n";
  log << "[adapter] configFolder=" << util::context.configFolder << "\n";

  // 2) （可選）系統操作：Android 端建議改由 Kotlin 完成；預設跳過
  if (p.allowSystemOps) {
    log << "[adapter] request: enable airplane + toggle wifi ON (native)\n";
    // !!! 不建議在 app 內用 system()，先保留你原有寫法但加 if 守護
    system("settings put global airplane_mode_on 1 > /dev/null 2>&1");
    system("su -c am broadcast -a android.intent.action.AIRPLANE_MODE > /dev/null 2>&1");
    std::this_thread::sleep_for(std::chrono::milliseconds(5000));
    system("svc wifi enable");
    while (testInterface(kWlan0Interface)) {
      std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
  } else {
    log << "[adapter] skip system ops (handled by Kotlin/UI)\n";
  }

  // 3) 建立 Session（等待 epdg up）
  log << "[adapter] wait epdg up...\n";
  Session session(waitEpgdInterfaceUp(kEpdgInterface));

  // 4) 取得 SADB 設定（注意：NDK 下 linux/pfkey 可能無法用，若失敗就略過）
  std::optional<ESPConfig> config;
  for (int i = 0; i < 100; ++i) {
    try {
      config = getConfigFromSADB();
    } catch (...) {
      // 在某些環境可能直接 throw / 不可用
      config.reset();
    }
    if (config) break;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }

  if (!config) {
    log << "[adapter] getConfigFromSADB(): not available on this build.\n";
    // 依你的需求，這裡可以選擇直接返回或走 degraded path
    // return log.str();
  } else if (util::context.verbose) {
    log << "[adapter] ESPConfig acquired:\n" << *config << "\n";
  }

  // 5) 跑原本的 application/session 流程（如果 config 拿到了）
  log << "[adapter] initializing Wi-Fi Calling packets ...\n";
  if (config) {
    #ifdef ANDROID_HAVE_ORIGINAL_APP_CORE
    Application application(session);
    session.run(std::move(*config), application);
    log << "[adapter] session.run() returned.\n";
    #else
     log << "[adapter] skip session.run() on Android (libcurl/Crypto++ not linked yet)\n";
    #endif
  } else {
    log << "[adapter] session skipped (no SADB config).\n";
  }

  return log.str();
}

} // namespace core_adapter
