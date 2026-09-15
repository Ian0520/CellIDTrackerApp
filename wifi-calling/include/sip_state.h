#pragma once

enum class SipState {
  IDLE,
  INVITE,
  SPROG,
  PRACK,
  RING,
  CANCEL,
  BUSY,
  REQUESTERMINATE,
  ACK,
  END
};

enum class SipApp { NULLAPP, DOS, MUTICALL };

inline const char* sipStateToString(SipState state) noexcept {
  switch (state) {
    case SipState::IDLE: return "IDLE";
    case SipState::INVITE: return "INVITE";
    case SipState::SPROG: return "SPROG";
    case SipState::PRACK: return "PRACK";
    case SipState::RING: return "RING";
    case SipState::CANCEL: return "CANCEL";
    case SipState::BUSY: return "BUSY";
    case SipState::REQUESTERMINATE: return "REQUESTERMINATE";
    case SipState::ACK: return "ACK";
    case SipState::END: return "END";
  }
  return "UNKNOWN";
}
