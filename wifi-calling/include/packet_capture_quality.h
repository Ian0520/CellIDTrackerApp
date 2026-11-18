#pragma once

#include <cstddef>
#include <cstdint>
#include <span>

namespace packet_capture_quality {

enum class Issue { NONE, TRUNCATED, INVALID_LENGTH, FRAGMENTED, UNSUPPORTED_EXTENSION };

struct Inspection {
  Issue issue{Issue::NONE};
  std::size_t declaredBytes{0};
};

inline std::uint16_t network16(std::span<const std::uint8_t> bytes,
                               std::size_t offset) {
  return (static_cast<std::uint16_t>(bytes[offset]) << 8) | bytes[offset + 1];
}

inline Inspection inspectIp(std::span<const std::uint8_t> bytes) {
  if (bytes.empty()) return {Issue::TRUNCATED, 1};
  if ((bytes[0] >> 4) == 4) {
    if (bytes.size() < 20) return {Issue::TRUNCATED, 20};
    const std::size_t header = (bytes[0] & 0x0f) * 4;
    const std::size_t length = network16(bytes, 2);
    if (header < 20 || length < header) return {Issue::INVALID_LENGTH, length};
    if (header > bytes.size() || length > bytes.size()) return {Issue::TRUNCATED, length};
    // Both non-first fragments and first fragments with MF require IP reassembly.
    if ((network16(bytes, 6) & 0x3fff) != 0) return {Issue::FRAGMENTED, length};
    return {Issue::NONE, length};
  }
  if ((bytes[0] >> 4) == 6) {
    if (bytes.size() < 40) return {Issue::TRUNCATED, 40};
    const std::size_t length = 40U + network16(bytes, 4);
    if (length > bytes.size()) return {Issue::TRUNCATED, length};
    auto next = bytes[6];
    std::size_t offset = 40;
    bool extensionSeen = false;
    for (int depth = 0; depth < 8; ++depth) {
      if (next == 44) return {Issue::FRAGMENTED, length};
      if (next != 0 && next != 43 && next != 60 && next != 51) break;
      extensionSeen = true;
      if (offset + 2 > length) return {Issue::INVALID_LENGTH, length};
      const std::size_t extensionLength = next == 51
          ? (static_cast<std::size_t>(bytes[offset + 1]) + 2) * 4
          : (static_cast<std::size_t>(bytes[offset + 1]) + 1) * 8;
      if (offset + extensionLength > length) return {Issue::INVALID_LENGTH, length};
      next = bytes[offset];
      offset += extensionLength;
    }
    // The current decoder does not walk extension chains. Report rather than
    // silently claiming that their transport data was collected.
    if (extensionSeen) return {Issue::UNSUPPORTED_EXTENSION, length};
    return {Issue::NONE, length};
  }
  return {Issue::INVALID_LENGTH, 0};
}

inline Inspection inspectUdp(std::span<const std::uint8_t> bytes) {
  if (bytes.size() < 8) return {Issue::TRUNCATED, 8};
  const std::size_t length = network16(bytes, 4);
  if (length < 8) return {Issue::INVALID_LENGTH, length};
  if (length > bytes.size()) return {Issue::TRUNCATED, length};
  return {Issue::NONE, length};
}

inline Inspection inspectTcp(std::span<const std::uint8_t> bytes) {
  if (bytes.size() < 20) return {Issue::TRUNCATED, 20};
  const std::size_t header = (bytes[12] >> 4) * 4;
  if (header < 20) return {Issue::INVALID_LENGTH, header};
  if (header > bytes.size()) return {Issue::TRUNCATED, header};
  return {Issue::NONE, header};
}

}  // namespace packet_capture_quality
