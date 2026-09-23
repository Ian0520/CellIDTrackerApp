#include "sip_capture.h"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
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
