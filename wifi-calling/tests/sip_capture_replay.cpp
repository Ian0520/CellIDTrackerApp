#include "sip_capture.h"

#include <array>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
// Host-only offline replay format: four little-endian uint32 lengths, followed
// by direction, capture point, transport, and the original payload bytes.
// Payloads stay length-delimited so embedded NULs and CRLFs are not modified.
std::uint32_t readLength(std::ifstream& input) {
  std::array<unsigned char, 4> bytes{};
  if (!input.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) {
    throw std::runtime_error("incomplete frame header");
  }
  return static_cast<std::uint32_t>(bytes[0]) |
      (static_cast<std::uint32_t>(bytes[1]) << 8) |
      (static_cast<std::uint32_t>(bytes[2]) << 16) |
      (static_cast<std::uint32_t>(bytes[3]) << 24);
}

std::string readBytes(std::ifstream& input, std::uint32_t size) {
  std::string value(size, '\0');
  if (!input.read(value.data(), size)) {
    throw std::runtime_error("incomplete frame data");
  }
  return value;
}
}  // namespace

int main(int argc, char* argv[]) {
  if (argc != 3) {
    std::cerr << "Usage: sip_capture_replay INPUT.frames OUTPUT.jsonl\n";
    return 2;
  }
  if (std::filesystem::exists(argv[2])) {
    std::cerr << "Refusing to append to an existing replay output\n";
    return 2;
  }
  std::ifstream input(argv[1], std::ios::binary);
  if (!input) {
    std::cerr << "Unable to open replay input\n";
    return 2;
  }
  setenv("SIP_CAPTURE_PATH", argv[2], 1);
  setenv("SIP_CAPTURE_RUN_ID", "offline-replay", 1);
  setenv("SIP_CAPTURE_MAX_BYTES", "67108864", 1);
  setenv("SIP_CAPTURE_MAX_FILES", "1", 1);
  setenv("SIP_CAPTURE_QUEUE_MAX_BYTES", "67108864", 1);
  try {
    std::size_t count = 0;
    {
      SipCaptureRecorder recorder;
      if (!recorder.enabled()) throw std::runtime_error("capture recorder unavailable");
      while (input.peek() != std::char_traits<char>::eof()) {
        const auto directionSize = readLength(input);
        const auto pointSize = readLength(input);
        const auto transportSize = readLength(input);
        const auto payloadSize = readLength(input);
        if (directionSize > 4096 || pointSize > 4096 || transportSize > 4096 ||
            payloadSize == 0 || payloadSize > 16U * 1024U * 1024U) {
          throw std::runtime_error("frame length outside replay limits");
        }
        SipCaptureMetadata metadata;
        metadata.direction = readBytes(input, directionSize);
        metadata.capturePoint = readBytes(input, pointSize);
        metadata.transport = readBytes(input, transportSize);
        const auto payload = readBytes(input, payloadSize);
        recorder.recordPayload(
            std::span<const std::uint8_t>(
                reinterpret_cast<const std::uint8_t*>(payload.data()), payload.size()),
            metadata);
        ++count;
      }
    }
    std::cout << "Replayed " << count << " transport payloads\n";
  } catch (const std::exception& error) {
    std::cerr << "Replay failed: " << error.what() << '\n';
    return 1;
  }
  return 0;
}
