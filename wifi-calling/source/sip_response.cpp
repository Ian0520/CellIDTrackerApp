#include "sip_response.h"

#include <regex>

namespace sip_response {
namespace {

std::string trimAsciiWhitespace(const std::string& input) {
  const auto first = input.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) return "";
  const auto last = input.find_last_not_of(" \t\r\n");
  return input.substr(first, last - first + 1);
}

std::optional<CellInfo> parseCellInfo(const std::string& payload) {
  static const std::regex networkInfoRegex(
      R"((?:(?:Cellular-Network-Info)|(?:P-Access-Network-Info)):\s*([^;]+);[\s\S]*?utran-cell-id-3gpp=([A-Fa-f0-9]+))",
      std::regex_constants::ECMAScript);
  static const std::regex cellIdRegex(
      R"(^([0-9A-Fa-f]{3})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]+)$)");

  std::smatch match;
  if (!std::regex_search(payload, match, networkInfoRegex) || match.size() < 3) {
    return std::nullopt;
  }

  const std::string cellIdHex = match[2].str();
  if (!std::regex_match(cellIdHex, match, cellIdRegex) || match.size() < 5) {
    return std::nullopt;
  }

  return CellInfo{
      .mcc = std::stoi(match[1].str()),
      .mnc = std::stoi(match[2].str()),
      .lac = static_cast<int>(std::stoul(match[3].str(), nullptr, 16)),
      .cid = static_cast<int>(std::stoull(match[4].str(), nullptr, 16))};
}

void captureFirst(
    const std::string& payload,
    const std::regex& pattern,
    std::string& destination) {
  std::smatch match;
  if (std::regex_search(payload, match, pattern) && match.size() >= 2) {
    destination = match[1].str();
  }
}

}  // namespace

Message parse(std::string_view payloadView) {
  static const std::regex requestHead(R"((.*) sip:(.*) SIP/2\.0\r?)");
  static const std::regex responseHead(R"(SIP/2\.0 ([0-9]{3}) (.*)\r?)");
  static const std::regex toTag(R"(To:.*tag=(.*)\r?\n)");
  static const std::regex contactParam(R"(Contact: <sip:.*;x-afi=(.*)>)");
  static const std::regex b2bdlg(R"(b2bdlg=(.*)>)");
  static const std::regex rseq(R"(RSeq: ([0-9]{1}))");
  static const std::regex secver(R"(Security-Verify:(.*))");
  static const std::regex accessNetwork(R"(P-Access-Network-Info:(.*))");
  static const std::regex calleeId(R"(To: <sip:([^;@]+))");
  static const std::regex callerId(R"(From: <sip:([^;@]+))");
  static const std::regex callId(
      R"(Call-ID:\s*([^\r\n]+))", std::regex_constants::icase);
  static const std::regex branch(
      R"(Via:\s*SIP/2\.0/[A-Z]+\s+[^;]+;branch=([^;\r\n]+))",
      std::regex_constants::icase);

  const std::string payload(payloadView);
  Message parsed;
  captureFirst(payload, calleeId, parsed.calleeId);

  std::smatch match;
  if (std::regex_match(payload, match, requestHead)) {
    parsed.type = MessageType::REQUEST;
    parsed.method = match[1].str();
    parsed.uri = match[2].str();
  } else if (std::regex_search(payload, match, responseHead)) {
    parsed.type = MessageType::RESPONSE;
    std::string::const_iterator searchStart(payload.cbegin());
    while (std::regex_search(searchStart, payload.cend(), match, responseHead)) {
      parsed.status = std::stoi(match[1].str());
      parsed.reason = match[2].str();
      searchStart = match.suffix().first;
    }
    captureFirst(payload, callerId, parsed.callerId);
  } else {
    return parsed;
  }

  captureFirst(payload, callId, parsed.callId);
  parsed.callId = trimAsciiWhitespace(parsed.callId);
  captureFirst(payload, branch, parsed.branch);
  parsed.branch = trimAsciiWhitespace(parsed.branch);
  captureFirst(payload, toTag, parsed.toTag);
  captureFirst(payload, contactParam, parsed.contactParam);
  captureFirst(payload, b2bdlg, parsed.b2bdlg);
  captureFirst(payload, rseq, parsed.rseq);
  captureFirst(payload, secver, parsed.securityVerify);
  captureFirst(payload, accessNetwork, parsed.accessNetwork);
  if (parsed.type == MessageType::RESPONSE) {
    parsed.cell = parseCellInfo(payload);
  }
  return parsed;
}

bool isStaleForTransaction(
    const Message& message,
    std::string_view expectedCallId,
    std::string_view expectedBranch) noexcept {
  const bool callIdMismatch =
      !expectedCallId.empty() &&
      !message.callId.empty() &&
      message.callId != expectedCallId;
  const bool branchMismatch =
      !expectedBranch.empty() &&
      !message.branch.empty() &&
      message.branch != expectedBranch;
  return callIdMismatch || branchMismatch;
}

}  // namespace sip_response
