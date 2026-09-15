#pragma once

#include <poll.h>

#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "sip.h"
#include "session.h"


using SipPair = std::pair<std::shared_ptr<SipMessage>, std::shared_ptr<SipMessage>>;

class ProbeController;

struct cellInformation {
  std::string networkType;
  std::string cellIdentity;
  cellInformation() {};
  cellInformation(const std::string& network, const std::string& cellId)
    : networkType(network), cellIdentity(cellId) {}
};

class Application {
public:
  explicit Application(Session& session)
    : session(session), lastLineIndex(0), isProbePredicted(false), adateToNewEnvironment(false) {
  }

  ~Application();

  static std::string getFormattedAddress(const std::string& response);
  static std::string getReverseGeoCoding(const std::string& latAndlng);
  static std::string getGeoLocation(uint64_t cellId, uint64_t locationAreaCode,
                                    int mobileCountryCode, int mobileNetworkCode,
                                    int age, const std::string& phoneNumber);
  static void extractCellularInfo(const std::string& input, const std::string& phoneNumber);
  static void createTimestampFile(unsigned long long cellId);

  int createListenSocket(int port);  
  void startRemoteCallServer(pollfd& pfd, int nReady,
                        const std::vector<std::string>& victimList);
  void fetchAdaptivePrediction();
  void startPeriodicUpload(int seconds);
  bool uploadAdaptiveCellularInfo();
  bool handleIncomingPackets(const int nReady);
  void runLegacyMode(
      pollfd& pfd,
      int nReady,
      const std::vector<std::string>& victimList);

  void CallDetect(pollfd& pfd, int nReady, const std::string& calleeId);
  void MultiCallDetect(pollfd& pfd, int nReady, const std::vector<std::string>& victimList);
  void CallDoS(pollfd& pfd, int nReady, const std::string& calleeId);
  void MultiCallDoS(pollfd& pfd, int nReady, const std::vector<std::string>& victimList);

private:
  friend class ProbeController;

  static std::string extractCallIdFromSip(const std::string& sip);
  static std::string extractBranchFromSip(const std::string& sip);
  void prepareFreshInvite(SipMessage& sip);
  void armInviteTiming(const std::string& invite);
  void emitActiveAttemptStarted();
  void emitActiveAttemptFinished(std::string_view reason);

  Session& session;

  std::size_t lastLineIndex;
  bool isProbePredicted;
  bool adateToNewEnvironment;
  bool adaptiveCall;

  static std::unordered_map<std::string, cellInformation> victimLocation;
  static std::string timestampFile;
};
