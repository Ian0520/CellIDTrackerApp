#include "probe_loop_policy.h"

#include <chrono>
#include <cstdlib>
#include <iostream>

namespace {

void expect(
    probe_loop_policy::WatchdogAction actual,
    probe_loop_policy::WatchdogAction expected,
    const char* message) {
  if (actual == expected) return;
  std::cerr << "probe_loop_policy_test failed: " << message << std::endl;
  std::exit(1);
}

}  // namespace

int main() {
  using namespace std::chrono_literals;
  using probe_loop_policy::WatchdogAction;
  using probe_loop_policy::evaluateWatchdog;

  expect(evaluateWatchdog(SipState::INVITE, 19s), WatchdogAction::NONE, "INVITE before limit");
  expect(
      evaluateWatchdog(SipState::INVITE, 20s),
      WatchdogAction::FORCE_FRESH_INVITE,
      "INVITE at limit");
  expect(
      evaluateWatchdog(SipState::SPROG, 20s),
      WatchdogAction::FORCE_CANCEL_RETRY,
      "SPROG at limit");
  expect(
      evaluateWatchdog(SipState::PRACK, 20s),
      WatchdogAction::FORCE_CANCEL_RETRY,
      "PRACK at limit");
  expect(evaluateWatchdog(SipState::CANCEL, 9s), WatchdogAction::NONE, "CANCEL before limit");
  expect(
      evaluateWatchdog(SipState::CANCEL, 10s),
      WatchdogAction::FORCE_CANCEL_RETRY,
      "CANCEL at limit");
  expect(
      evaluateWatchdog(SipState::REQUESTERMINATE, 10s),
      WatchdogAction::FORCE_FRESH_INVITE,
      "REQUESTERMINATE at limit");
  expect(evaluateWatchdog(SipState::ACK, 60s), WatchdogAction::NONE, "unwatched state");

  return 0;
}
