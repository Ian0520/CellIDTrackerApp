#include "sip_capture.h"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {
void expect(bool condition, const char* message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << std::endl;
    std::exit(1);
  }
}

std::string read(const std::filesystem::path& path) {
  std::ifstream input(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<std::string> payloadRecords(const std::filesystem::path& path) {
  std::vector<std::string> records;
  std::istringstream input(read(path));
  std::string line;
  while (std::getline(input, line)) {
    if (line.find("\"record_type\":\"transport_payload\"") != std::string::npos) {
      records.push_back(line);
    }
  }
  return records;
}

bool contains(const std::string& record, const std::string& value) {
  return record.find(value) != std::string::npos;
}
}  // namespace

int main() {
  const auto directory = std::filesystem::temp_directory_path() /
                         ("cellidtracker-sip-capture-" + std::to_string(std::rand()));
  std::filesystem::create_directories(directory);
  const auto path = directory / "capture.jsonl";
  setenv("SIP_CAPTURE_PATH", path.c_str(), 1);
  setenv("SIP_CAPTURE_RUN_ID", "test-run", 1);
  setenv("SIP_CAPTURE_MAX_BYTES", "65536", 1);
  setenv("SIP_CAPTURE_MAX_FILES", "2", 1);

  {
    SipCaptureRecorder recorder;
    expect(recorder.enabled(), "recorder should be enabled by environment");
    const std::string sip =
        "SIP/2.0 183 Session Progress\r\n"
        "Via: SIP/2.0/TCP host;branch=z9hG4bK-one\r\n"
        "X-Duplicate: first\r\n"
        "X-Duplicate: second\r\n"
        "X-Folded: first\r\n\tsecond\r\n"
        "From: <sip:caller@example.test>;tag=from-one\r\n"
        "To: <sip:target@example.test>;tag=to-one\r\n"
        "Call-ID: call-one\r\n"
        "CSeq: 42 INVITE\r\n"
        "Content-Length: 4\r\n\r\nbody";
    const auto bytes = std::span<const std::uint8_t>(
        reinterpret_cast<const std::uint8_t*>(sip.data()), sip.size());
    const SipCaptureMetadata metadata{
        .direction = "incoming",
        .capturePoint = "decoded_transport",
        .transport = "tcp",
        .ipVersion = 4,
        .sourceAddress = "192.0.2.1",
        .destinationAddress = "192.0.2.2",
        .sourcePort = 5060,
        .destinationPort = 5061,
        .tcpSequence = 10,
        .tcpAcknowledgment = 20,
        .espSequence = 30,
        .encryptedPacketSize = 512};
    recorder.recordPayload(bytes, metadata);
    recorder.recordPayload(bytes, metadata);
  }

  const auto fidelityRecords = read(path);
  expect(fidelityRecords.find("\"payload_base64\":\"U0lQLzIuMCAxODM") != std::string::npos,
         "raw SIP bytes should be Base64 encoded");
  expect(fidelityRecords.find("\"name\":\"X-Duplicate\"") != std::string::npos,
         "ordered duplicate headers should be retained");
  expect(fidelityRecords.find("\\r\\n\\tsecond") != std::string::npos,
         "folded header content should be retained");
  expect(fidelityRecords.find("\"is_repeat\":true") != std::string::npos,
         "repeated payloads should be labeled without being discarded");
  expect(fidelityRecords.find("\"run_id\":\"test-run\"") != std::string::npos,
         "run identifier should be recorded");
  expect(fidelityRecords.find("\"cseq\":\"42\"") != std::string::npos,
         "CSeq number should be indexed");
  expect(fidelityRecords.find("\"cseq_method\":\"INVITE\"") != std::string::npos,
         "CSeq method should be indexed");
  expect(fidelityRecords.find("\"body_base64\":\"Ym9keQ==\"") != std::string::npos,
         "message body should be captured separately");
  expect(fidelityRecords.find("\"encrypted_packet_size\":512") != std::string::npos,
         "ESP packet size metadata should be retained");
  expect(fidelityRecords.find("\"event\":\"capture_summary\"") != std::string::npos,
         "capture should finish with accounting metadata");
  expect(fidelityRecords.find("\"recognized_sip\":true") != std::string::npos,
         "response should still be recognized");
  expect(fidelityRecords.find("\"status\":183,\"reason\":\"Session Progress\"") !=
             std::string::npos,
         "response status and reason should remain intact");

  const auto requestPath = directory / "requests.jsonl";
  setenv("SIP_CAPTURE_PATH", requestPath.c_str(), 1);
  const std::string invite =
      "INVITE sip:target@example.test SIP/2.0\r\n"
      "Via: SIP/2.0/TCP host;rport;received=192.0.2.1;branch=z9hG4bK-invite\r\n"
      "From: <sip:caller@example.test>;tag=caller-tag\r\n"
      "To: <sip:target@example.test>\r\n"
      "Call-ID: dialog-call\r\n"
      "CSeq: 42 INVITE\r\n"
      "Content-Length: 32\r\n\r\nbody\r\nSIP/2.0 500 Not a response";
  const std::string matchingResponse =
      "SIP/2.0 100 Trying\r\n"
      "Via: SIP/2.0/TCP host;branch=z9hG4bK-invite\r\n"
      "From: <sip:caller@example.test>;tag=caller-tag\r\n"
      "To: <sip:target@example.test>\r\n"
      "Call-ID: dialog-call\r\n"
      "CSeq: 42 INVITE\r\n\r\n";
  const std::string validLongLine =
      "OPTIONS sip:" + std::string(600, 'u') + "@example.test SIP/2.0";
  const std::string truncatedValidPrefix =
      "INVITE sip:" + std::string(493, 'u') + " SIP/2.0";
  expect(truncatedValidPrefix.size() == 512, "truncation fixture size");
  const std::vector<std::string> messages{
      "REGISTER sip:ims.example.test SIP/2.0\r\n"
      "Via: SIP/2.0/UDP host;branch=z9hG4bK-register;rport\r\n"
      "Call-ID: register-call\r\nCSeq: 1 REGISTER\r\n\r\n",
      "SUBSCRIBE sips:ims.example.test SIP/2.0\r\n"
      "v: SIP/2.0/TCP host;rport;BRANCH = z9hG4bK-subscribe, SIP/2.0/TCP next;branch=next\r\n"
      "i: subscribe-call\r\nf: <sip:caller@example.test>;tag=sub-from\r\n"
      "t: <sip:target@example.test>;tag=sub-to\r\nCSeq: 2 SUBSCRIBE\r\n\r\n",
      invite,
      invite,
      "CANCEL sip:target@example.test SIP/2.0\r\n"
      "vIa: SIP/2.0/TCP host;rport;branch=z9hG4bK-invite\r\n"
      "cAlL-iD: dialog-call\r\nCSeq: 42 CANCEL\r\n\r\n",
      matchingResponse,
      validLongLine + "\r\nCall-ID: long-call\r\n\r\n",
      "unrelated\r\n\r\nSIP/2.0 200 OK\r\nCall-ID: body-only\r\n\r\n",
      std::string("\0", 1) + "INVITE sip:target@example.test SIP/2.0\r\n\r\n",
      truncatedValidPrefix + " trailing\r\n\r\n",
      "INVITE not-an-absolute-uri SIP/2.0\r\n\r\n",
      "INVITE sip:target@example.test SIP/2.0" + std::string("\0", 1) +
          "junk\r\n\r\n"};
  {
    SipCaptureRecorder recorder;
    const SipCaptureMetadata outgoing{
        .direction = "outgoing", .capturePoint = "decoded_transport", .transport = "tcp"};
    const SipCaptureMetadata incoming{
        .direction = "incoming", .capturePoint = "decoded_transport", .transport = "tcp"};
    for (std::size_t index = 0; index < messages.size(); ++index) {
      const auto& message = messages[index];
      recorder.recordPayload(
          std::span<const std::uint8_t>(
              reinterpret_cast<const std::uint8_t*>(message.data()), message.size()),
          index == 5 ? incoming : outgoing);
    }
  }
  const auto requests = payloadRecords(requestPath);
  expect(requests.size() == messages.size(), "every transport payload should be retained");
  for (std::size_t index = 0; index < 7; ++index) {
    expect(contains(requests[index], "\"recognized_sip\":true"),
           "complete request or response should be recognized");
  }
  expect(contains(requests[0], "\"method\":\"REGISTER\"") &&
             contains(requests[0], "\"call_id\":\"register-call\"") &&
             contains(requests[0], "\"via_branch\":\"z9hG4bK-register\""),
         "REGISTER request should expose method and identifiers");
  expect(contains(requests[1], "\"method\":\"SUBSCRIBE\"") &&
             contains(requests[1], "\"call_id\":\"subscribe-call\"") &&
             contains(requests[1], "\"via_branch\":\"z9hG4bK-subscribe\"") &&
             contains(requests[1], "\"from_tag\":\"sub-from\"") &&
             contains(requests[1], "\"to_tag\":\"sub-to\""),
         "compact headers and reordered Via parameters should be indexed");
  expect(contains(requests[2], "\"method\":\"INVITE\",\"status\":0") &&
             contains(requests[2], "\"call_id\":\"dialog-call\"") &&
             contains(requests[2], "\"via_branch\":\"z9hG4bK-invite\""),
         "body text should not override request classification or identifiers");
  expect(contains(requests[2], "\"body_base64\":\"Ym9keQ0KU0lQLzIuMCA1MDAgTm90IGEgcmVzcG9uc2U=\""),
         "full request body should remain byte-for-byte available");
  expect(contains(requests[2], "\"payload_base64\":\"SU5WSVRFIHNpcDp0YXJnZXRAZXhhbXBsZS50ZXN0"),
         "full raw request should remain Base64 encoded");
  expect(contains(requests[2], "\"is_repeat\":false") &&
             contains(requests[3], "\"occurrence\":2,\"is_repeat\":true"),
         "request retransmissions should be labeled and retained");
  expect(contains(requests[4], "\"method\":\"CANCEL\"") &&
             contains(requests[4], "\"call_id\":\"dialog-call\"") &&
             contains(requests[4], "\"via_branch\":\"z9hG4bK-invite\""),
         "mixed-case CANCEL headers should be indexed");
  const auto dialogStart = requests[2].find("\"dialog_id\":");
  const auto dialogEnd = requests[2].find(',', dialogStart);
  expect(contains(requests[5], requests[2].substr(dialogStart, dialogEnd - dialogStart)),
         "matching request and response should share the same dialog identifier");
  expect(contains(requests[5], "\"status\":100,\"reason\":\"Trying\""),
         "matching response should retain its response fields");
  expect(contains(requests[6], "\"method\":\"OPTIONS\""),
         "request recognition must use the complete line beyond the display limit");
  for (std::size_t index = 7; index < messages.size(); ++index) {
    expect(contains(requests[index], "\"recognized_sip\":false") &&
               contains(requests[index], "\"method\":\"\"") &&
               contains(requests[index], "\"call_id\":\"\""),
           "non-SIP and invalid start lines must remain unknown");
  }

  const auto rotationPath = directory / "rotation.jsonl";
  setenv("SIP_CAPTURE_PATH", rotationPath.c_str(), 1);
  {
    SipCaptureRecorder recorder;
    const SipCaptureMetadata metadata{
        .direction = "incoming",
        .capturePoint = "decoded_transport",
        .transport = "tcp"};

    const std::string large(20'000, 'x');
    const auto largeBytes = std::span<const std::uint8_t>(
        reinterpret_cast<const std::uint8_t*>(large.data()), large.size());
    recorder.recordPayload(largeBytes, metadata);
    recorder.recordPayload(largeBytes, metadata);
    recorder.recordPayload(largeBytes, metadata);
  }

  expect(std::filesystem::exists(rotationPath), "active capture file should exist");
  expect(std::filesystem::exists(rotationPath.string() + ".1"), "capture should rotate");
  const auto combined = read(rotationPath) + read(rotationPath.string() + ".1");
  expect(!combined.empty(), "rotated records should remain readable");

  unsetenv("SIP_CAPTURE_PATH");
  std::filesystem::remove_all(directory);
  return 0;
}
