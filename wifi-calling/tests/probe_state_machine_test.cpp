#include "probe_state_machine.h"

#include <cstdlib>
#include <iostream>
#include <string_view>

namespace {

using probe_state_machine::ResponseDecision;

void expect(bool condition, const char* message) {
  if (condition) return;
  std::cerr << "probe_state_machine_test failed: " << message << std::endl;
  std::exit(1);
}

void expectAfter(
    const ResponseDecision& decision,
    SipState expected,
    std::string_view reason) {
  expect(decision.afterProcessing.size() == 1, "one after-processing transition");
  expect(decision.afterProcessing[0].next == expected, "after-processing state");
  expect(decision.afterProcessing[0].reason == reason, "after-processing reason");
}

ResponseDecision decide(
    int status,
    SipState state = SipState::INVITE,
    SipApp app = SipApp::DOS,
    bool retryInvitePending = false) {
  return probe_state_machine::decideResponse(
      state, app, status, retryInvitePending);
}

}  // namespace

int main() {
  const auto first183 = decide(183);
  expect(first183.beforeProcessing.size() == 1, "183 enters SPROG");
  expect(first183.beforeProcessing[0].next == SipState::SPROG, "183 SPROG state");
  expect(first183.holdDosProvisional, "DOS holds 183 flow");
  expect(first183.afterProcessing.empty(), "DOS does not cancel first 183");

  const auto repeated183 = decide(183, SipState::PRACK);
  expect(repeated183.beforeProcessing.size() == 1, "183 after PRACK enters SPROG");
  expect(repeated183.beforeProcessing[0].next == SipState::SPROG, "repeat 183 SPROG");

  const auto nonDos183 = decide(183, SipState::INVITE, SipApp::MUTICALL);
  expect(nonDos183.beforeProcessing.size() == 1, "non-DOS 183 first enters SPROG");
  expectAfter(nonDos183, SipState::CANCEL, "183 received, send CANCEL");

  const auto ringing = decide(180);
  expectAfter(ringing, SipState::CANCEL, "180 ringing, send CANCEL");
  expect(ringing.calleeAttackable == true, "180 marks target attackable");

  const auto forwarded = decide(181);
  expectAfter(forwarded, SipState::CANCEL, "181 forwarded, send CANCEL");
  expect(forwarded.calleeAttackable == false, "181 marks target unavailable");

  expectAfter(decide(200, SipState::PRACK), SipState::CANCEL, "200 after PRACK");
  expectAfter(
      decide(200, SipState::PRACK, SipApp::DOS, true),
      SipState::CANCEL,
      "200 after PRACK");
  expectAfter(
      decide(200, SipState::REQUESTERMINATE, SipApp::DOS, true),
      SipState::BUSY,
      "200 after CANCEL during retry");

  for (const int status : {408, 486, 500}) {
    const auto retryable = decide(status);
    expectAfter(retryable, SipState::BUSY, "busy/timeout response");
    expect(retryable.requestImmediateRetry, "timeout/busy requests immediate retry");
  }

  for (const int status : {401, 407}) {
    const auto auth = decide(status);
    expectAfter(auth, SipState::BUSY, "auth response in DOS");
    expect(auth.requestImmediateRetry, "DOS auth requests retry");
    expectAfter(
        decide(status, SipState::INVITE, SipApp::DOS, true),
        SipState::BUSY,
        "auth response while retry pending");
    expectAfter(
        decide(status, SipState::INVITE, SipApp::NULLAPP),
        SipState::ACK,
        "auth response outside DOS");
  }

  const auto staleTransaction = decide(481);
  expectAfter(staleTransaction, SipState::BUSY, "481 in DOS");
  expect(staleTransaction.requestImmediateRetry, "481 requests retry in DOS");
  expectAfter(
      decide(481, SipState::REQUESTERMINATE, SipApp::DOS, true),
      SipState::BUSY,
      "481 while retry pending");
  expectAfter(
      decide(481, SipState::INVITE, SipApp::NULLAPP),
      SipState::ACK,
      "481 outside DOS");

  expectAfter(
      decide(487, SipState::REQUESTERMINATE, SipApp::DOS, true),
      SipState::BUSY,
      "487 while retry pending");
  expectAfter(decide(487), SipState::SPROG, "487 in DOS");
  expectAfter(
      decide(487, SipState::INVITE, SipApp::NULLAPP),
      SipState::ACK,
      "487 outside DOS");

  const auto trying = decide(100);
  expect(trying.beforeProcessing.empty(), "100 has no transition before processing");
  expect(trying.afterProcessing.empty(), "100 has no transition after processing");

  const auto unknown = decide(603);
  expect(unknown.beforeProcessing.empty(), "unknown response has no early transition");
  expect(unknown.afterProcessing.empty(), "unknown response has no late transition");

  return 0;
}
