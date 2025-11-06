#include "sadb.h"

#include <arpa/inet.h>
#include <unistd.h>

#include <array>
#include <chrono>
#include <iomanip>
#include <iostream>
#include <span>
#include <thread>

#include "iface.h"
namespace {
  std::array<std::span<uint8_t>, SADB_EXT_MAX> getSadbKey(std::span<uint8_t> message) {
    std::array<std::span<uint8_t>, SADB_EXT_MAX> result;
    auto head = message.data() + sizeof(sadb_msg);
    int msgLength = message.size() - sizeof(sadb_msg);
    while (msgLength > 0) {
      auto ext = reinterpret_cast<sadb_ext *>(head);
      int extLength = static_cast<int>(ext->sadb_ext_len) << 3;
      result[ext->sadb_ext_type] = message.subspan(std::distance(message.data(), head), extLength);
      msgLength -= extLength;
      head += extLength;
    }
    return result;
  }

  std::optional<ESPConfig> makeConfig(std::span<uint8_t> message) {
    ESPConfig config{};
    auto extensions = getSadbKey(message);
    int aalg = 0, ealg = 0;
    {
      // Get security parameter index
      auto ext = extensions[SADB_EXT_SA];
      if (ext.empty()) return std::nullopt;
      auto ptr = reinterpret_cast<sadb_sa *>(ext.data());
      config.spi = ntohl(ptr->sadb_sa_spi);
      // Get aalg
      aalg = ptr->sadb_sa_auth;
      // Get ealg
      ealg = ptr->sadb_sa_encrypt;
    }
    {
      // Get current sequence number
      auto ext = extensions[SADB_EXT_LIFETIME_CURRENT];
      if (ext.empty()) return std::nullopt;
      auto ptr = reinterpret_cast<sadb_lifetime *>(ext.data());
      config.seq = ntohl(ptr->sadb_lifetime_allocations);
    }
    {
      // Get akey
      auto ext = extensions[SADB_EXT_KEY_AUTH];
      if (!ext.empty()) {
        // Has auth key
        auto ptr = reinterpret_cast<sadb_key *>(ext.data());
        int keyByte = ptr->sadb_key_bits >> 3;
        auto keyStart = reinterpret_cast<uint8_t *>(ptr + 1);
        config.aalg = std::make_unique<ESP_AALG>(aalg, std::span<uint8_t>(keyStart, keyByte));
      } else {
        config.aalg = std::make_unique<ESP_AALG>(SADB_AALG_NONE, std::span<uint8_t>{});
      }
    }
    {
      // Get ekey
      auto ext = extensions[SADB_EXT_KEY_ENCRYPT];
      if (!ext.empty()) {
        // Has encryption key
        auto ptr = reinterpret_cast<sadb_key *>(ext.data());
        int keyByte = ptr->sadb_key_bits >> 3;
        auto keyStart = reinterpret_cast<uint8_t *>(ptr + 1);
        config.ealg = std::make_unique<ESP_EALG>(ealg, std::span<uint8_t>(keyStart, keyByte));
      } else {
        config.ealg = std::make_unique<ESP_EALG>(SADB_EALG_NONE, std::span<uint8_t>{});
      }
    }
    {
      // Get local address (src)
      auto ext = extensions[SADB_EXT_ADDRESS_SRC];
      if (ext.empty()) return std::nullopt;
      auto addr = reinterpret_cast<sadb_address *>(ext.data());
      if (addr->sadb_address_prefixlen == 32 /* IPv4 address is 32 bits*/) {
        config.local = ipToString(reinterpret_cast<sockaddr_in *>(addr + 1)->sin_addr.s_addr);
      } else if (addr->sadb_address_prefixlen == 128 /* IPv6 address is 128 bits*/) {
        config.local = ipToString(reinterpret_cast<sockaddr_in6 *>(addr + 1)->sin6_addr.s6_addr);
      }
    }
    {
      // Get remote address (dst)
      auto ext = extensions[SADB_EXT_ADDRESS_DST];
      if (ext.empty()) return std::nullopt;
      auto addr = reinterpret_cast<sadb_address *>(ext.data());
      if (addr->sadb_address_prefixlen == 32 /* IPv4 address is 32 bits*/) {
        config.remote = ipToString(reinterpret_cast<sockaddr_in *>(addr + 1)->sin_addr.s_addr);
      } else if (addr->sadb_address_prefixlen == 128 /* IPv6 address is 128 bits*/) {
        config.remote = ipToString(reinterpret_cast<sockaddr_in6 *>(addr + 1)->sin6_addr.s6_addr);
      }
    }
    {
      // Get reqid
      auto ext = extensions[SADB_X_EXT_SA2];
      if (ext.empty()) return std::nullopt;
      auto ptr = reinterpret_cast<sadb_x_sa2 *>(ext.data());
      config.reqid = ptr->sadb_x_sa2_reqid;
    }
    return config;
  }

  std::pair<uint32_t, uint32_t> getSeq(std::span<uint8_t> message) {
    auto extensions = getSadbKey(message);
    // Get reqid
    auto ext = extensions[SADB_X_EXT_SA2];
    auto ptr = reinterpret_cast<sadb_x_sa2 *>(ext.data());
    auto reqid = ptr->sadb_x_sa2_reqid;
    // Get current sequence number
    auto extLifetime = extensions[SADB_EXT_LIFETIME_CURRENT];
    auto ptrLifetime = reinterpret_cast<sadb_lifetime *>(extLifetime.data());
    return std::make_pair(reqid, ntohl(ptrLifetime->sadb_lifetime_allocations));
  }
}  // namespace

int getCurrentSeq(uint32_t reqid) {
  // Allocate buffer
  std::vector<uint8_t> message;
  message.resize(65536);
  sadb_msg msg{};

  msg.sadb_msg_version = PF_KEY_V2;
  msg.sadb_msg_type = SADB_DUMP;
  msg.sadb_msg_satype = SADB_SATYPE_UNSPEC;
  msg.sadb_msg_len = sizeof(sadb_msg) / 8;
  msg.sadb_msg_pid = getpid();

  int sock = socket(AF_KEY, SOCK_RAW, PF_KEY_V2);
  write(sock, &msg, sizeof(msg));

  int size = sizeof(sadb_msg);
  // Wait IPSec tunnel create, timeout set to 10 second
  for (int i = 0; i < 100; ++i) {
    size = read(sock, message.data(), message.size());
    if (size != sizeof(sadb_msg)) break;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  // Has SADB entry
  if (size != sizeof(sadb_msg)) {
    // Iter through all SADB entries
    while (size != 0) {
      // Resize buffer
      message.resize(size);
      auto [reqidCurrent, seq] = getSeq(message);
      // Check local IP exists, them check sequence number is set
      if (reqidCurrent == reqid) {
        return seq;
      }
      size = read(sock, message.data(), message.size());
    }
  }
  std::cerr << "Warning: SADB entry with reqid " << reqid << " not found!" << std::endl;
  return 0;
}

std::optional<ESPConfig> getConfigFromSADB() {
  // List all interfaces' IP
  auto &&ipList = InterfaceList::get();
  // Allocate buffer
  std::vector<uint8_t> message;
  message.resize(65536);
  sadb_msg msg{};
  msg.sadb_msg_version = PF_KEY_V2;
  msg.sadb_msg_type = SADB_DUMP;
  msg.sadb_msg_satype = SADB_SATYPE_ESP;
  msg.sadb_msg_len = sizeof(sadb_msg) / 8;
  msg.sadb_msg_pid = getpid();

  int sock = socket(AF_KEY, SOCK_RAW, PF_KEY_V2);
  write(sock, &msg, sizeof(msg));

  int size = sizeof(sadb_msg);
  // Wait IPSec tunnel create, timeout set to 10 second
  for (int i = 0; i < 100; ++i) {
    size = read(sock, message.data(), message.size());
    if (size != sizeof(sadb_msg)) break;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  // Has SADB entry
  if (size != sizeof(sadb_msg)) {
    // Iter through all SADB entries
    while (size != 0) {
      // Resize buffer
      message.resize(size);
      auto configRes = makeConfig(message);
      if (!configRes) {
        close(sock);
        return std::nullopt;
      }
      auto &&config = *configRes;
      // Check local IP exists, them check sequence number is set
      if (config.seq > 0 && ipList.hasIpAddress(config.local)) {
        close(sock);
        return configRes;
      }
      size = read(sock, message.data(), message.size());
    }
  }
  close(sock);
  std::cout << "SADB entry not found, maybe this operator does not use IPSec." << std::endl;
  return std::nullopt;
}

std::ostream &operator<<(std::ostream &os, const ESPConfig &config) {
  os << "------------------------------------------------------------" << std::endl;
  os << "REQID : " << config.reqid << std::endl;
  os << "SPI   : 0x" << std::hex << std::setw(8) << std::setfill('0') << config.spi << std::dec
     << std::endl;
  os << "AALG  : ";
  if (!config.aalg->empty()) {
    os << std::left << std::setw(30) << std::setfill(' ') << config.aalg->name();
    os << "HWACCEL: " << config.aalg->provider() << std::endl;
  } else {
    os << "NONE" << std::endl;
  }
  os << "EALG  : ";
  if (!config.ealg->empty()) {
    os << std::left << std::setw(30) << std::setfill(' ') << config.ealg->name();
    os << "HWACCEL: " << config.aalg->provider() << std::endl;
  } else {
    os << "NONE" << std::endl;
  }
  os << "Local : " << config.local << std::endl;
  os << "Remote: " << config.remote << std::endl;
  os << "------------------------------------------------------------";
  return os;
}
