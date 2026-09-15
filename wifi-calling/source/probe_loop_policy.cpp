#include "probe_loop_policy.h"

namespace probe_loop_policy {

WatchdogAction evaluateWatchdog(
    SipState state,
    std::chrono::steady_clock::duration elapsed) noexcept {
  using namespace std::chrono_literals;

  if (state == SipState::INVITE && elapsed >= 20s) {
    return WatchdogAction::FORCE_FRESH_INVITE;
  }
  if ((state == SipState::SPROG || state == SipState::PRACK) && elapsed >= 20s) {
    return WatchdogAction::FORCE_CANCEL_RETRY;
  }
  if (state == SipState::CANCEL && elapsed >= 10s) {
    return WatchdogAction::FORCE_CANCEL_RETRY;
  }
  if (state == SipState::REQUESTERMINATE && elapsed >= 10s) {
    return WatchdogAction::FORCE_FRESH_INVITE;
  }
  return WatchdogAction::NONE;
}

}  // namespace probe_loop_policy
