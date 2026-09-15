#pragma once

#include <chrono>

#include "sip_state.h"

namespace probe_loop_policy {

enum class WatchdogAction {
  NONE,
  FORCE_FRESH_INVITE,
  FORCE_CANCEL_RETRY
};

[[nodiscard]] WatchdogAction evaluateWatchdog(
    SipState state,
    std::chrono::steady_clock::duration elapsed) noexcept;

}  // namespace probe_loop_policy
