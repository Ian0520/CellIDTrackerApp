#pragma once
#include <memory>
#include <span>
#include <string>
#include <vector>
#include <unordered_map>

#include "encoder.h"
#include "sadb.h"
#include "util.h"

struct State {
  int afFamily;
  uint32_t espseq;
  uint32_t tcpseq;
  uint32_t tcpackseq;
  uint16_t srcPort;
  uint16_t dstPort;
  uint16_t srcUdpPort;
  uint16_t dstUdpPort;
  uint16_t ipId;

  std::string toTag;
  std::string contactParam; // special for TWM
  std::string b2bdlg; // special for TWM
  std::string rseq; // special for FET
  std::string secver;
  std::string accessNetwork;
  std::string calleeId;
  std::unordered_map<std::string, bool> calleeDoSAttackable;
  std::unordered_map<std::string, bool> extractedCellId;

  std::unordered_map<std::string, int> DoSCount;
  std::unordered_map<std::string, int> sessionProgressCount;
  // std::unordered_map<std::string, bool> prackToRing; // special for cross operator
  
  int maxSessionProgressOfCarrier;

  bool ack;
  bool psh;
  bool useESP;
};

enum class SipState { IDLE, INVITE, SPROG, PRACK, RING, CANCEL, BUSY, REQUESTERMINATE, ACK, END };
enum class SipApp { NULLAPP, DOS, MUTICALL };
class Application;
class Session {
friend class Application;
public:
  explicit Session(const std::string& iface);
  ~Session();
  void run(ESPConfig&& cfg, Application& application);

private:
  void dissect(ssize_t rdcnt);
  void dissectIPv4(std::span<uint8_t> buffer);
  void dissectIPv6(std::span<uint8_t> buffer);
  void dissectESP(std::span<uint8_t> buffer, bool receivePacket);
  void dissectTCP(std::span<uint8_t> buffer, bool receivePacket);
  void dissectUDP(std::span<uint8_t> buffer, bool receivePacket);
  bool dissectSIP(std::span<uint8_t> buffer, bool receivePacket);

  void encapsulate(std::span<uint8_t> payload);
  int encapsulateIPv4(std::span<uint8_t> buffer, std::span<uint8_t> payload);
  int encapsulateIPv6(std::span<uint8_t> buffer, std::span<uint8_t> payload);
  int encapsulateESP(std::span<uint8_t> buffer, std::span<uint8_t> payload);
  int encapsulateTCP(std::span<uint8_t> buffer, std::span<uint8_t> payload);
  int encapsulateUDP(std::span<uint8_t> buffer, std::span<uint8_t> payload);
  int encapsulateSIP(std::span<uint8_t> buffer, std::span<uint8_t> payload);

  uint32_t pseudoIpv4(uint8_t proto, uint16_t len);
  uint32_t pseudoIpv6(uint8_t proto, uint32_t len);

  int sock;
  int mtu;
  uint8_t recvBuffer[4096], *sendBuffer;
  ESPConfig config;
  SipState currentSipState;
  SipApp currentSipApp;
  State state;
};
