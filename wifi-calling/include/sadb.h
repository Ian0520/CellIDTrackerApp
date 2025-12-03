#pragma once
#include <linux/pfkeyv2.h>

#include <iostream>
#include <memory>
#include <optional>
#include <string>

#include "encoder.h"
#include "util.h"

struct ESPConfig {
  // For ESP Header
  uint32_t spi;
  uint32_t seq;
  // ESP encryption
  std::unique_ptr<ESP_EALG> ealg;
  // ESP authentication
  std::unique_ptr<ESP_AALG> aalg;
  // Remote
  std::string remote;
  // Local
  std::string local;
  // Internal use
  uint32_t reqid;
  bool useESP, useIPv4, useTCP;
  friend std::ostream& operator<<(std::ostream& os, const ESPConfig& config);
};

int getCurrentSeq(uint32_t reqid);
std::optional<ESPConfig> getConfigFromSADB();
