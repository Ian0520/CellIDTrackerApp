#include "sip.h"

#include <fstream>
#include <regex>
#include <iostream>

namespace {
  const std::string chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  const std::string chars_hex = "abcdef0123456789";
  static std::regex fromtag(R"(tag=([0-9A-Fa-f]{8})(?![0-9A-Fa-f]))");
  static std::regex branch("524287-1---[0-9a-f]{16}");
  static std::regex callid("[0-9a-zA-Z]{22}");

  void replaceAll(std::string& str, const std::string& from, const std::string& to) {
    if (from.empty()) return;
    size_t start_pos = 0;
    while ((start_pos = str.find(from, start_pos)) != std::string::npos) {
      str.replace(start_pos, from.length(), to);
      start_pos += to.length();
    }
  }
  void replaceSip(std::string& target, const std::string& localIP, const std::string& remoteIP,
                  const std::string& callerId, const std::string& calleeId,
                  const std::string& secver, const std::string& anInfo, const std::string& port) {
    replaceAll(target, "<LOCAL_IP>", localIP);
    replaceAll(target, "<REMOTE_IP>", remoteIP);
    replaceAll(target, "<CALLER_ID>", callerId);
    replaceAll(target, "<CALLEE_ID>", calleeId);
    replaceAll(target, "<SEC_VERIFY>", secver);
    replaceAll(target, "<PORT>", port);
    replaceAll(target, "<AN_INFO>", anInfo);
  }
}  // namespace

SipMessage::SipMessage(const std::string& folderPath) {
  // Some random tags;
  std::string fromTag = "";//"CCoE_Test";
  std::string branchId = "524287-1---"; //"CCoE_Test";
  std::string callId = "";//"CCoE_Test";
  for (int i = 0; i < 16; ++i) branchId += chars_hex[rand() % chars_hex.size()];
  for (int i = 0; i < 22; ++i) callId += chars[rand() % chars.size()];
  for (int i = 0; i < 8; ++i) fromTag += chars_hex[rand() % chars_hex.size()];

  {
    std::ifstream ifs(folderPath + "/ack.bin");
    ifs.seekg(0, std::ios::end);
    ack.reserve(ifs.tellg());
    ifs.seekg(0, std::ios::beg);
    ack.assign((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());
    replaceAll(ack, "<FROM_TAG>", fromTag);
    replaceAll(ack, "<BRANCH_ID>", branchId);
    replaceAll(ack, "<CALL_ID>", callId);
    ack += "\r\n";
  }
  {
    std::ifstream ifs(folderPath + "/cancel.bin");
    ifs.seekg(0, std::ios::end);
    cancel.reserve(ifs.tellg());
    ifs.seekg(0, std::ios::beg);
    cancel.assign((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());
    replaceAll(cancel, "<FROM_TAG>", fromTag);
    replaceAll(cancel, "<BRANCH_ID>", branchId);
    replaceAll(cancel, "<CALL_ID>", callId);
    cancel += "\r\n";
  }
  {
    std::ifstream ifs1(folderPath + "/invite.header.bin"), ifs2(folderPath + "/invite.body.bin");
    ifs1.seekg(0, std::ios::end);
    invite.reserve(static_cast<uint32_t>(ifs1.tellg()) + 2);
    ifs1.seekg(0, std::ios::beg);
    invite.assign((std::istreambuf_iterator<char>(ifs1)), std::istreambuf_iterator<char>());

    ifs2.seekg(0, std::ios::end);
    _body.reserve(ifs2.tellg());
    ifs2.seekg(0, std::ios::beg);
    _body.assign((std::istreambuf_iterator<char>(ifs2)), std::istreambuf_iterator<char>());

    invite += "\r\n";

    replaceAll(invite, "<FROM_TAG>", fromTag);
    replaceAll(invite, "<BRANCH_ID>", branchId);
    replaceAll(invite, "<CALL_ID>", callId);
  }
  {
    std::ifstream ifs(folderPath + "/prack.bin");
    ifs.seekg(0, std::ios::end);
    prack.reserve(ifs.tellg());
    ifs.seekg(0, std::ios::beg);
    prack.assign((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());
    replaceAll(prack, "<FROM_TAG>", fromTag);
    replaceAll(prack, "<BRANCH_ID>", branchId);
    replaceAll(prack, "<CALL_ID>", callId);
    prack += "\r\n";
  }
}

void SipMessage::initialize(const std::string& localIP, const std::string& remoteIP,
                            const std::string& callerId, const std::string& calleeId,
                            const std::string& secver, const std::string& anInfo,
                            const std::string& port) {
  replaceSip(ack, localIP, remoteIP, callerId, calleeId, secver, anInfo, port);
  replaceSip(cancel, localIP, remoteIP, callerId, calleeId, secver, anInfo, port);
  replaceSip(prack, localIP, remoteIP, callerId, calleeId, secver, anInfo, port);
  replaceSip(invite, localIP, remoteIP, callerId, calleeId, secver, anInfo, port);

  replaceAll(_body, "<LOCAL_IP>", localIP);
  replaceAll(invite, "<CONTENT_LENGTH>", std::to_string(_body.length()));
  invite += _body;
  // invite += "\r\n"; // may have data 0d0a outside the INVITE
}

void SipMessage::setToTag(const std::string& toTag) {
  replaceAll(prack, "<TO_TAG>", toTag);
  replaceAll(ack, "<TO_TAG>", toTag);
}

void SipMessage::setXafi(const std::string& xafi) { replaceAll(prack, "<CONTACT_PARAM>", xafi); }

void SipMessage::setB2bdlg(const std::string& b2bdlg) { replaceAll(prack, "<B2BDLG>", b2bdlg); }

void SipMessage::setRAck(const std::string& rseq) {
  if (!rseq.empty()) {
    // special for FET
    int oldRSeq = std::stoi(rseq) - 1;
    if (oldRSeq > 1) {
      std::string oldSeq = std::to_string(oldRSeq);
      replaceAll(prack, "CSeq: " + oldSeq, "CSeq: " + rseq);
      replaceAll(prack, "RAck: " + oldSeq, "RAck: " + rseq);
    }
    else replaceAll(prack, "<SEQ>", rseq);
  }
}

void SipMessage::setFromTag() {
  std::string newFromTag = "tag=";
  for (int i = 0; i < 8; ++i) newFromTag += chars_hex[rand() % chars_hex.size()];
  std::smatch match;
  if (std::regex_search(invite, match, fromtag)) {
    std::string oldFromTag = match[0].str();
    replaceIdOrTag(oldFromTag, newFromTag);
  }
}

void SipMessage::setBranch() {
  std::string newBranchID = "524287-1---";
  for (int i = 0; i < 16; ++i) newBranchID += chars_hex[rand() % chars_hex.size()];
  std::smatch match;
  if (std::regex_search(invite, match, branch)) {
    std::string oldBranchID = match[0].str();
    replaceIdOrTag(oldBranchID, newBranchID);
  }
}

void SipMessage::setCallId() {
  std::string newCallID = "";
  for (int i = 0; i < 22; ++i) newCallID += chars[rand() % chars.size()];
  std::smatch match;
  if (std::regex_search(invite, match, callid)) {
    std::string oldCallID = match[0].str();
    replaceIdOrTag(oldCallID, newCallID);
  }
}

void SipMessage::replaceIdOrTag(const std::string& from, const std::string& to) {
  replaceAll(invite, from, to);
  replaceAll(cancel, from, to);
  replaceAll(prack, from, to);
  replaceAll(ack, from, to);
}