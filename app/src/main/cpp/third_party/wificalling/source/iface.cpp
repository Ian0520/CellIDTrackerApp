#include "iface.h"

#include <ifaddrs.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <unistd.h>

#include <chrono>
#include <iostream>
#include <thread>

#include "util.h"
std::unordered_set<std::string> InterfaceList::_ips;

InterfaceList& InterfaceList::get() {
  static InterfaceList interfaceList;
  interfaceList.update();
  return interfaceList;
}

bool InterfaceList::hasIpAddress(const std::string& ip) { return _ips.find(ip) != _ips.end(); }

void InterfaceList::update() {
  ifaddrs* addrs;
  getifaddrs(&addrs);
  std::string ip;
  _ips.clear();
  for (auto i = addrs; i; i = i->ifa_next) {
    if (i->ifa_addr) {
      switch (i->ifa_addr->sa_family) {
        case AF_INET:
          ip = ipToString(reinterpret_cast<sockaddr_in*>(i->ifa_addr)->sin_addr.s_addr);
          break;
        case AF_INET6:
          ip = ipToString(reinterpret_cast<sockaddr_in6*>(i->ifa_addr)->sin6_addr.s6_addr);
          break;
        default:
          break;
      }
      if (!ip.empty()) {
        _ips.emplace(ip);
      }
    }
  }
  freeifaddrs(addrs);
}

std::string waitEpgdInterfaceUp(const std::string& iface) {
  ifreq ifr{}, ifr_now;
  int sock = socket(PF_INET6, SOCK_DGRAM, IPPROTO_IP);
  int suffix = 0;
  std::string epdgInterface;
  do {
    suffix++; if (suffix == 10) suffix = 0;
    epdgInterface = iface + std::to_string(suffix);
    snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "%s", epdgInterface.c_str());
    ifr_now = ifr;
    checkError(ioctl(sock, SIOCGIFFLAGS, &ifr_now), "SIOCGIFFLAGS");
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  } while ((ifr_now.ifr_flags & IFF_UP) == 0);
  close(sock);
  std::cout << "Interface " << epdgInterface << " is up" << std::endl;
  return epdgInterface;
}

bool testInterface(const std::string& iface) {
  ifreq ifr{};
  snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "%s", iface.c_str());
  int sock = socket(PF_INET6, SOCK_DGRAM, IPPROTO_IP);
  checkError(ioctl(sock, SIOCGIFFLAGS, &ifr), "SIOCGIFFLAGS");
  close(sock);
  return (ifr.ifr_flags & IFF_UP) == 0;
}
