#pragma once
#include <string>

namespace core_adapter {

struct Params {
  std::string victim;     // UI 傳入
  std::string carrier;    // Kotlin 端探測/或手選
  bool remoteCellIDProber = false;
  bool localCellIDProber  = false;
  bool rlRemoteProber     = false;
  bool unavailabilityEval = false;
  bool detectEval         = false;
  int  verbose            = 1;

  // 是否允許在 native 內做系統控制（預設 false；之後可由 Kotlin 控制）
  bool allowSystemOps     = false;
};

// 回傳一段人類可讀的 log（之後你要也能換 JSON）
std::string run_original_flow(const Params& p);

} // namespace core_adapter
