#include "probe_state_machine.h"

namespace probe_state_machine {

ResponseDecision decideResponse(
    SipState current,
    SipApp app,
    int status,
    bool retryInvitePending) {
  ResponseDecision decision;

  if (status == 183) {
    if (current == SipState::INVITE) {
      decision.beforeProcessing.push_back({SipState::SPROG, "183 provisional"});
    } else if (current == SipState::PRACK && app == SipApp::DOS) {
      decision.beforeProcessing.push_back({SipState::SPROG, "183 after PRACK in DOS"});
    }

    if (app == SipApp::DOS) {
      decision.holdDosProvisional = true;
    } else {
      decision.afterProcessing.push_back({SipState::CANCEL, "183 received, send CANCEL"});
    }
    return decision;
  }

  if (status == 180) {
    decision.afterProcessing.push_back({SipState::CANCEL, "180 ringing, send CANCEL"});
    decision.calleeAttackable = true;
  } else if (status == 181) {
    decision.afterProcessing.push_back({SipState::CANCEL, "181 forwarded, send CANCEL"});
    decision.calleeAttackable = false;
  } else if (status == 200 && current == SipState::PRACK) {
    decision.afterProcessing.push_back({SipState::CANCEL, "200 after PRACK"});
  } else if (status == 200 && retryInvitePending) {
    decision.afterProcessing.push_back({SipState::BUSY, "200 after CANCEL during retry"});
  } else if (status == 486 || status == 500 || status == 408) {
    decision.afterProcessing.push_back({SipState::BUSY, "busy/timeout response"});
    decision.requestImmediateRetry = true;
  } else if (status == 401 || status == 407) {
    if (retryInvitePending) {
      decision.afterProcessing.push_back({SipState::BUSY, "auth response while retry pending"});
    } else if (app == SipApp::DOS) {
      decision.afterProcessing.push_back({SipState::BUSY, "auth response in DOS"});
      decision.requestImmediateRetry = true;
    } else {
      decision.afterProcessing.push_back({SipState::ACK, "auth response outside DOS"});
    }
  } else if (status == 481) {
    if (retryInvitePending) {
      decision.afterProcessing.push_back({SipState::BUSY, "481 while retry pending"});
    } else if (app == SipApp::DOS) {
      decision.afterProcessing.push_back({SipState::BUSY, "481 in DOS"});
      decision.requestImmediateRetry = true;
    } else {
      decision.afterProcessing.push_back({SipState::ACK, "481 outside DOS"});
    }
  } else if (status == 487) {
    if (retryInvitePending) {
      decision.afterProcessing.push_back({SipState::BUSY, "487 while retry pending"});
    } else if (app == SipApp::DOS) {
      decision.afterProcessing.push_back({SipState::SPROG, "487 in DOS"});
    } else {
      decision.afterProcessing.push_back({SipState::ACK, "487 outside DOS"});
    }
  }

  return decision;
}

}  // namespace probe_state_machine
