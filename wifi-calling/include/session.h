#pragma once
#include <memory>
#include <span>
#include <string>
#include <vector>
#include <unordered_map>
#include <optional>
#include <chrono>

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
  std::string activeInviteCallId;
  std::string activeInviteBranch;
  std::unordered_map<std::string, bool> calleeDoSAttackable;
  std::unordered_map<std::string, bool> extractedCellId;

  std::unordered_map<std::string, int> DoSCount;
  std::unordered_map<std::string, int> sessionProgressCount;
  // std::unordered_map<std::string, bool> prackToRing; // special for cross operator
  
  int maxSessionProgressOfCarrier;

  // Track whether a 487 (Request Terminated) was received and still needs ACK
  bool needAck487{false};
  // Signal to immediately retry after 486/500/408
  bool retryImmediate{false};
  // Two-phase retry after timeout/busy in CallDoS:
  // 1) send CANCEL, 2) wait for termination (or timeout fallback), 3) send fresh INVITE.
  bool retryCancelPending{false};
  bool retryInvitePending{false};
  // Emit one structured probe event per fresh INVITE transaction.
  bool probeEventEmitted{false};
  int firstProvisionalStatus{0};

  bool ack;
  bool psh;
  bool useESP;

  std::optional<std::chrono::steady_clock::time_point> t_invite;
  std::optional<std::chrono::steady_clock::time_point> t_trying;
  std::optional<std::chrono::steady_clock::time_point> t_pr;
};

enum class SipState { IDLE, INVITE, SPROG, PRACK, RING, CANCEL, BUSY, REQUESTERMINATE, ACK, END };
enum class SipApp { NULLAPP, DOS, MUTICALL };

inline const char* sipStateToString(SipState state) noexcept {
  switch (state) {
    case SipState::IDLE: return "IDLE";
    case SipState::INVITE: return "INVITE";
    case SipState::SPROG: return "SPROG";
    case SipState::PRACK: return "PRACK";
    case SipState::RING: return "RING";
    case SipState::CANCEL: return "CANCEL";
    case SipState::BUSY: return "BUSY";
    case SipState::REQUESTERMINATE: return "REQUESTERMINATE";
    case SipState::ACK: return "ACK";
    case SipState::END: return "END";
  }
  return "UNKNOWN";
}
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
  void setSipState(SipState next, const char* reason);

  int sock;
  int mtu;
  uint8_t recvBuffer[4096], *sendBuffer;
  ESPConfig config;
  SipState currentSipState;
  SipApp currentSipApp;
  State state;
};
