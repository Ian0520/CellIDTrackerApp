#pragma once

#include <string>
#include <unordered_set>

class InterfaceList {
public:
  static InterfaceList& get();
  bool hasIpAddress(const std::string& ip);

private:
  void update();
  static std::unordered_set<std::string> _ips;
};

std::string waitEpgdInterfaceUp(const std::string& iface);
bool testInterface(const std::string& iface);
