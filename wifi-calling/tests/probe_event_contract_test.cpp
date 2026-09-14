#include "probe_event.h"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

void expectEqual(
    const std::string& actual,
    const std::string& expected,
    const char* testName) {
  if (actual == expected) return;
  std::cerr << testName << " failed\nexpected: " << expected
            << "\nactual:   " << actual << std::endl;
  std::exit(1);
}

}  // namespace

int main() {
  expectEqual(
      probe_event::streamReady(),
      R"({"contract":"cellidtracker.probe","version":1,"event":"stream_ready"})",
      "streamReady");

  expectEqual(
      probe_event::attemptStarted("call\"id\\one", 100, 1'700'000'000'000),
      R"({"contract":"cellidtracker.probe","version":1,"event":"attempt_started","attempt_id":"call\"id\\one","invite_elapsed_ms":100,"invite_unix_ms":1700000000000})",
      "attemptStarted");

  expectEqual(
      probe_event::provisionalReceived(
          "call-1", 183, 749, 100, 849, 1'700'000'000'000, 1'700'000'000'749),
      R"({"contract":"cellidtracker.probe","version":1,"event":"provisional_received","attempt_id":"call-1","status":183,"delta_ms":749,"invite_elapsed_ms":100,"response_elapsed_ms":849,"invite_unix_ms":1700000000000,"response_unix_ms":1700000000749})",
      "provisionalReceived");

  expectEqual(
      probe_event::cellObserved(
          "call-1", 183, 749, 100, 849, 1'700'000'000'000, 1'700'000'000'749,
          466, 92, 13'700, 81'261'593),
      R"({"contract":"cellidtracker.probe","version":1,"event":"cell_observed","attempt_id":"call-1","status":183,"delta_ms":749,"invite_elapsed_ms":100,"response_elapsed_ms":849,"invite_unix_ms":1700000000000,"response_unix_ms":1700000000749,"mcc":466,"mnc":92,"lac":13700,"cid":81261593})",
      "cellObserved");

  expectEqual(
      probe_event::attemptFinished(
          "call-1", 1'700'000'001'000, "cell_observed", "next_invite"),
      R"({"contract":"cellidtracker.probe","version":1,"event":"attempt_finished","attempt_id":"call-1","finished_unix_ms":1700000001000,"outcome":"cell_observed","reason":"next_invite"})",
      "attemptFinished");

  return 0;
}
