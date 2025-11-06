#pragma once

#include <netinet/in.h>
#include <span>   

#include <memory>
#include <span>
#include <string>
#include <utility>
#include <variant>
#include <vector>
#include <regex>
#include <optional>

#define MAX_NUMBERS 5
#define VICTIM_LIST "victim_list"

using HostType = std::variant<in_addr, in6_addr>;
using AddressType = std::variant<sockaddr_in, sockaddr_in6>;

namespace util {
  struct Context {
    std::string callerId;
    std::string calleeId;
    std::string configFolder;
    int verbose = 0;
    bool shouldStop = false;

    bool remoteCellIDProber = false;
    bool localCellIDProber = false;
    bool rlRemoteCellIDProber = false;
    bool unavailabilityEval = false;
    bool detectEval = false;
  };

  extern Context context;
}

void printHex(std::span<uint8_t> buffer);

void checkError(int error, const char *message);
void hexdump(std::span<uint8_t> buffer);
uint16_t checksum(std::span<uint8_t> buffer, uint32_t initial = 0);
std::string ipToString(uint32_t v4Addr);
std::string ipToString(uint8_t v6Addr[16]);
in_addr stringToIPv4(const std::string &v4Addr);
void stringToIPv6(const std::string &v6Addr, uint8_t *dst);


std::string createVictimList(bool enableInput);

template<typename T>
bool checkInputOptions(T& inputOption, const std::regex& correctFormat);

std::string readCarrierName();

void writeProbeTimeCDFToFile(const std::vector<double>& intervals, const std::string& outputPath);

inline bool iequals(std::string_view a, std::string_view b) noexcept {
    return std::equal(a.begin(), a.end(), b.begin(), b.end(),
        [](char a, char b) { return std::tolower(a) == std::tolower(b); });
}

inline std::optional<uint8_t> getMaxSessionProgressOfCarrier(std::string_view name) noexcept {
  if (name == "CHT") return 6;
  if (name == "TWM") return 4;
  if (name == "FET") return 4;
  return std::nullopt;
}
