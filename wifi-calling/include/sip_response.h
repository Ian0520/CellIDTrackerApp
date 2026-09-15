#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace sip_response {

struct CellInfo {
  int mcc;
  int mnc;
  int lac;
  int cid;

  bool operator==(const CellInfo&) const = default;
};

enum class MessageType { UNKNOWN, REQUEST, RESPONSE };

struct Message {
  MessageType type{MessageType::UNKNOWN};
  int status{0};
  std::string reason;
  std::string method;
  std::string uri;
  std::string calleeId;
  std::string callerId;
  std::string callId;
  std::string branch;
  std::string toTag;
  std::string contactParam;
  std::string b2bdlg;
  std::string rseq;
  std::string securityVerify;
  std::string accessNetwork;
  std::optional<CellInfo> cell;

  [[nodiscard]] bool recognized() const noexcept {
    return type != MessageType::UNKNOWN;
  }
};

[[nodiscard]] Message parse(std::string_view payload);

[[nodiscard]] bool isStaleForTransaction(
    const Message& message,
    std::string_view expectedCallId,
    std::string_view expectedBranch) noexcept;

}  // namespace sip_response
