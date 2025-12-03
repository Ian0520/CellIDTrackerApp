#include "util.h"

#include <arpa/inet.h>
#include <net/ethernet.h>

#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>

namespace {
  std::string _ipToString(int afFamily, const void *addr) {
    char temp[64] = {};
    auto result = inet_ntop(afFamily, addr, temp, sizeof(temp));
    if (result == nullptr) {
      checkError(-1, "Convert IP to string failed");
    }
    return temp;
  }
}  // namespace

namespace util {
  Context context;
}


void printHex(std::span<uint8_t> buffer) {
  std::cout << std::hex << "0x";
  for (const auto &i : buffer) std::cout << static_cast<int>(i);
  std::cout << std::dec << std::endl;
}

void checkError(int error, const char *message) {
#ifndef NDEBUG
  if (error == -1) {
    perror(message);
    exit(EXIT_FAILURE);
  }
#endif
}

void hexdump(std::span<uint8_t> buffer) {
  std::ofstream ofs("dump.txt");
  ofs << std::hex;
  for (int i = 0; i < buffer.size(); i += 16) {
    ofs << std::setw(4) << std::setfill('0') << i << "   ";
    for (int j = 0; j < 16 && i + j < buffer.size(); j++) {
      ofs << std::setw(2) << std::setfill('0') << static_cast<int>(buffer[i + j]) << " ";
    }
    ofs << std::endl;
  }
}

uint16_t checksum(std::span<uint8_t> buffer, uint32_t initial) {
  uint32_t sum = initial;
  auto buf16 = reinterpret_cast<uint16_t *>(buffer.data());
  int size = buffer.size();
  /* Accumulate checksum */
  while (size > 1) {
    sum += *(buf16++);
    size -= 2;
  }
  /* Handle odd-sized case */
  if (size) sum += buffer.back();
  /* Fold to get the ones-complement result */
  sum = (sum >> 16) + (sum & 0xFFFF);
  sum += (sum >> 16);
  /* Invert to get the negative in ones-complement arithmetic */
  return ~sum;
}

std::string ipToString(uint32_t v4Addr) { return _ipToString(AF_INET, &v4Addr); }

std::string ipToString(uint8_t v6Addr[16]) { return _ipToString(AF_INET6, v6Addr); }

in_addr stringToIPv4(const std::string &v4Addr) {
  in_addr addr;
  inet_pton(AF_INET, v4Addr.c_str(), &addr.s_addr);
  return addr;
}
void stringToIPv6(const std::string &v6Addr, uint8_t *dst) {
  inet_pton(AF_INET6, v6Addr.c_str(), dst);
}

template<typename T>
bool checkInputOptions(T& inputOption, const std::regex& correctFormat) {
  return std::regex_match(inputOption, correctFormat);
}

std::string createVictimList(bool enableInput) {
  std::vector<std::string> numbers;

  if (enableInput) {
    std::string input;
    std::regex mobile("^09\\d{8}$");
    std::regex tel("^(02|03|04|05|06|07|08|037|049|089)\\d{7}$");

    std::cout << "\033[32m" 
              << "\nEnter up to " << MAX_NUMBERS << " phone numbers (enter '0' to stop early):" 
              << "\033[0m" << std::endl;

    while (numbers.size() < MAX_NUMBERS) {
      std::cout << "Victim " << (numbers.size() + 1) << ": ";
      std::cin >> input;

      if (input == "0" && numbers.size() >= 1) {
        break;
      } else if (input == "0") {
        std::cout << "You must enter at least 1 numbers before stopping" << std::endl;
        continue;
      } else if (!checkInputOptions(input, mobile) && !checkInputOptions(input, tel)) {
        std::cout << "Wrong format of phone number" << std::endl;
        continue;
      }
      numbers.emplace_back(input);
    }
    std::ofstream outFile(VICTIM_LIST);
    for (const auto& target : numbers) {
      outFile << target << std::endl;
    }
    outFile.close();
  } else {
    std::ifstream inFile(VICTIM_LIST);
    if (!inFile) {
      std::cerr << "The file " << VICTIM_LIST << " doesn't exist" << std::endl;
      exit(EXIT_FAILURE);
    }
    std::string line;
    std::cout << "Read victim list: ";
    while (std::getline(inFile, line)) {
      std::cout << line << " ";
    }
    std::cout << std::endl;
    inFile.close();
  }

  return VICTIM_LIST;
}

std::string readCarrierName() {
  std::string carrier;
  std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(
      "getprop | grep sys.smf.mnoname", "r"), pclose);
  if (!pipe) {
    std::cerr << "popen failed\n";
    return carrier;
  }
  char buffer[128];
  while (fgets(buffer, sizeof(buffer), pipe.get()) != nullptr) {
    std::string line(buffer);

    if (line.find("LOADED") != std::string::npos) {
      auto p = line.find(": [");
      auto start = p + 3;
      auto end = line.find(']', start);

      // TWM_TW|LOADED
      std::string content = line.substr(start, end - start);
      auto country_pos = content.find('|');
      std::string country = content.substr(0, country_pos);
      auto under = country.find('_');
      carrier = country.substr(0, under);
      break;
    }
  }
  return carrier;
}

void writeProbeTimeCDFToFile(const std::vector<double>& intervals, const std::string& outputPath) {
  if (intervals.empty()) return;

  std::vector<double> sorted = intervals;
  std::sort(sorted.begin(), sorted.end());

  std::ofstream out(outputPath, std::ios::out | std::ios::app);
  if (!out.is_open()) return;

  size_t N = sorted.size();
  for (size_t i = 0 ; i < N ; ++i) {
    // double cdf = static_cast<double>(i+1) / N;
    out << sorted[i] << "\n";
  }
  
  out.close();
}