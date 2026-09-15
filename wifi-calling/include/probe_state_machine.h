#pragma once

#include <optional>
#include <string_view>
#include <vector>

#include "sip_state.h"

namespace probe_state_machine {

struct Transition {
  SipState next;
  std::string_view reason;

  bool operator==(const Transition&) const = default;
};

struct ResponseDecision {
  std::vector<Transition> beforeProcessing;
  std::vector<Transition> afterProcessing;
  std::optional<bool> calleeAttackable;
  bool requestImmediateRetry{false};
  bool holdDosProvisional{false};
};

[[nodiscard]] ResponseDecision decideResponse(
    SipState current,
    SipApp app,
    int status,
    bool retryInvitePending);

}  // namespace probe_state_machine
