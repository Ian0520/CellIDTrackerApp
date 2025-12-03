#pragma once
#include <string>
#include <random>

struct SipMessage {
  explicit SipMessage(const std::string& folderPath);
  void initialize(const std::string& localIP, const std::string& remoteIP,
                  const std::string& callerId, const std::string& calleeId,
                  const std::string& secver, const std::string& anInfo, const std::string& port);
  void setToTag(const std::string& toTag);
  void setXafi(const std::string& xafi);
  void setB2bdlg(const std::string& b2bdlg);
  void setRAck(const std::string& rseq);
  void setBranch();
  void setCallId();
  void setFromTag();
  void replaceIdOrTag(const std::string& from, const std::string& to);
  std::string invite;
  std::string _body;
  std::string prack;
  std::string cancel;
  std::string ack;
};
