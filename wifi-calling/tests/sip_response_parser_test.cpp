#include "sip_response.h"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

void expect(bool condition, const char* message) {
  if (condition) return;
  std::cerr << "sip_response_parser_test failed: " << message << std::endl;
  std::exit(1);
}

}  // namespace

int main() {
  const std::string provisional =
      "SIP/2.0 183 Session Progress\r\n"
      "Via: SIP/2.0/UDP 10.0.0.2:5060;branch=z9hG4bK-active;rport\r\n"
      "From: <sip:+886900000001@example.test>;tag=caller\r\n"
      "To: <sip:+886900000002@example.test>;tag=target-tag\r\n"
      "Call-ID: attempt-123   \r\n"
      "Contact: <sip:server@example.test;x-afi=afi-value>\r\n"
      "RSeq: 7\r\n"
      "P-Access-Network-Info: 3GPP-E-UTRAN-FDD;"
      "utran-cell-id-3gpp=4669235844D7F419\r\n\r\n";

  const auto response = sip_response::parse(provisional);
  expect(response.type == sip_response::MessageType::RESPONSE, "response type");
  expect(response.status == 183, "response status");
  expect(response.reason == "Session Progress", "response reason");
  expect(response.callId == "attempt-123", "trimmed Call-ID");
  expect(response.branch == "z9hG4bK-active", "trimmed Via branch");
  expect(response.callerId == "+886900000001", "caller ID");
  expect(response.calleeId == "+886900000002", "callee ID");
  expect(response.toTag == "target-tag", "To tag");
  expect(response.contactParam == "afi-value", "x-afi contact parameter");
  expect(response.rseq == "7", "RSeq");
  expect(response.cell.has_value(), "cell header parsed");
  expect(
      *response.cell == sip_response::CellInfo{466, 92, 0x3584, 0x4D7F419},
      "cell identity fields");
  expect(
      !sip_response::isStaleForTransaction(
          response, "attempt-123", "z9hG4bK-active"),
      "matching transaction accepted");
  expect(
      sip_response::isStaleForTransaction(
          response, "another-attempt", "z9hG4bK-active"),
      "mismatched Call-ID rejected");
  expect(
      sip_response::isStaleForTransaction(
          response, "attempt-123", "another-branch"),
      "mismatched branch rejected");

  const std::string request =
      "INVITE sip:+886900000002@example.test SIP/2.0\r";
  const auto parsedRequest = sip_response::parse(request);
  expect(parsedRequest.type == sip_response::MessageType::REQUEST, "request type");
  expect(parsedRequest.method == "INVITE", "request method");
  expect(parsedRequest.uri == "+886900000002@example.test", "request URI");

  const auto multilineRequest = sip_response::parse(
      "INVITE sip:+886900000002@example.test SIP/2.0\r\n"
      "Via: SIP/2.0/TCP 10.0.0.2:5060;branch=z9hG4bK-request\r\n"
      "Security-Verify: ipsec-3gpp;alg=hmac-sha-1-96\r\n"
      "P-Access-Network-Info: IEEE-802.11\r\n"
      "Call-ID: request-call\r\n\r\n");
  expect(
      !multilineRequest.recognized(),
      "legacy whole-payload request matching remains unchanged");

  const auto unrelated = sip_response::parse("not a SIP message\r\n");
  expect(!unrelated.recognized(), "unrelated payload rejected");

  const auto noCell = sip_response::parse(
      "SIP/2.0 481 Call/Transaction Does Not Exist\r\n"
      "Call-ID: stale-attempt\r\n\r\n");
  expect(noCell.status == 481, "481 status");
  expect(!noCell.cell.has_value(), "missing cell header remains empty");
  expect(
      !sip_response::isStaleForTransaction(
          noCell, "stale-attempt", "expected-but-missing-branch"),
      "missing response branch remains accepted");

  const auto noIdentifiers = sip_response::parse(
      "SIP/2.0 500 Timeout sending provisional SIP message\r\n\r\n");
  expect(
      !sip_response::isStaleForTransaction(
          noIdentifiers, "expected-call", "expected-branch"),
      "missing response identifiers remain accepted");

  const auto coalesced = sip_response::parse(
      "SIP/2.0 100 Trying\r\n\r\n"
      "SIP/2.0 183 Session Progress\r\n\r\n");
  expect(coalesced.status == 183, "last coalesced SIP response wins");

  return 0;
}
