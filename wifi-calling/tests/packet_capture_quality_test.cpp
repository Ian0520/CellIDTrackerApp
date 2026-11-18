#include "packet_capture_quality.h"

#include <cassert>
#include <vector>

using namespace packet_capture_quality;

int main() {
  assert(inspectIp({}).issue == Issue::TRUNCATED);
  std::vector<std::uint8_t> ip(28);
  ip[0] = 0x45;
  ip[3] = 28;
  assert(inspectIp(ip).issue == Issue::NONE);
  ip[3] = 29;
  assert(inspectIp(ip).issue == Issue::TRUNCATED);
  ip[3] = 19;
  assert(inspectIp(ip).issue == Issue::INVALID_LENGTH);
  ip[3] = 28;
  ip[6] = 0x20;
  assert(inspectIp(ip).issue == Issue::FRAGMENTED);
  ip[6] = 0;
  ip[7] = 1;
  assert(inspectIp(ip).issue == Issue::FRAGMENTED);
  ip[7] = 0;
  ip[6] = 0x40;
  assert(inspectIp(ip).issue == Issue::NONE);
  ip[0] = 0x44;
  assert(inspectIp(ip).issue == Issue::INVALID_LENGTH);
  std::vector<std::uint8_t> ip6(48);
  ip6[0] = 0x60;
  ip6[5] = 8;
  ip6[6] = 17;
  assert(inspectIp(ip6).issue == Issue::NONE);
  ip6[6] = 44;
  assert(inspectIp(ip6).issue == Issue::FRAGMENTED);
  ip6[6] = 17;
  ip6[5] = 9;
  assert(inspectIp(ip6).issue == Issue::TRUNCATED);
  ip6[5] = 8;
  ip6[6] = 0;
  ip6[40] = 44;
  assert(inspectIp(ip6).issue == Issue::FRAGMENTED);
  ip6[40] = 17;
  assert(inspectIp(ip6).issue == Issue::UNSUPPORTED_EXTENSION);
  ip6[41] = 1;
  assert(inspectIp(ip6).issue == Issue::INVALID_LENGTH);
  std::vector<std::uint8_t> udp(16);
  udp[5] = 16;
  assert(inspectUdp(udp).issue == Issue::NONE);
  udp[5] = 17;
  assert(inspectUdp(udp).issue == Issue::TRUNCATED);
  udp[5] = 7;
  assert(inspectUdp(udp).issue == Issue::INVALID_LENGTH);
  udp[5] = 8;
  assert(inspectUdp(udp).declaredBytes == 8);
  assert(inspectUdp(std::span(udp).first(7)).issue == Issue::TRUNCATED);
  std::vector<std::uint8_t> tcp(24);
  tcp[12] = 0x60;
  assert(inspectTcp(tcp).issue == Issue::NONE);
  tcp[12] = 0x70;
  assert(inspectTcp(tcp).issue == Issue::TRUNCATED);
  tcp[12] = 0x40;
  assert(inspectTcp(tcp).issue == Issue::INVALID_LENGTH);
}
