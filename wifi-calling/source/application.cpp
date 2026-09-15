#include "application.h"

#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <iostream>
#include <regex>
#include <span>

#include "probe_controller.h"
#include "probe_event.h"

std::string Application::extractCallIdFromSip(const std::string& sip) {
  static const std::regex callIdRegex(
      R"(Call-ID:\s*([^\r\n]+))", std::regex_constants::icase);
  std::smatch match;
  if (!std::regex_search(sip, match, callIdRegex) || match.size() < 2) return "";
  auto callId = match[1].str();
  const auto first = callId.find_first_not_of(" \t");
  if (first == std::string::npos) return "";
  const auto last = callId.find_last_not_of(" \t");
  return callId.substr(first, last - first + 1);
}

std::string Application::extractBranchFromSip(const std::string& sip) {
  static const std::regex branchRegex(
      R"(Via:\s*SIP/2\.0/[A-Z]+\s+[^;]+;branch=([^;\r\n]+))",
      std::regex_constants::icase);
  std::smatch match;
  if (!std::regex_search(sip, match, branchRegex) || match.size() < 2) return "";
  auto branch = match[1].str();
  const auto first = branch.find_first_not_of(" \t");
  if (first == std::string::npos) return "";
  const auto last = branch.find_last_not_of(" \t");
  return branch.substr(first, last - first + 1);
}

void Application::prepareFreshInvite(SipMessage& sip) {
  session.setSipState(SipState::INVITE, "prepare fresh INVITE");
  sip.setBranch();
  sip.setCallId();
  sip.setFromTag();
}

void Application::armInviteTiming(const std::string& invite) {
  emitActiveAttemptFinished("next_invite");
  session.state.t_trying.reset();
  session.state.t_pr.reset();
  session.state.retryCancelPending = false;
  session.state.retryInvitePending = false;
  session.state.probeEventEmitted = false;
  session.state.attemptStartedEmitted = false;
  session.state.attemptFinishedEmitted = false;
  session.state.firstProvisionalStatus = 0;
  session.state.firstProvisionalUnixMs = 0;
  session.state.inviteUnixMs =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::system_clock::now().time_since_epoch())
          .count();
  session.state.t_invite = std::chrono::steady_clock::now();
  session.state.activeInviteCallId = extractCallIdFromSip(invite);
  session.state.activeInviteBranch = extractBranchFromSip(invite);
}

void Application::emitActiveAttemptStarted() {
  auto& state = session.state;
  if (!isRemoteProbeMode(util::context.runMode) ||
      state.attemptStartedEmitted ||
      state.activeInviteCallId.empty() ||
      !state.t_invite.has_value()) {
    return;
  }

  const auto inviteElapsedMs =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          state.t_invite->time_since_epoch())
          .count();
  std::cout << probe_event::attemptStarted(
      state.activeInviteCallId,
      inviteElapsedMs,
      state.inviteUnixMs) << std::endl;
  state.attemptStartedEmitted = true;
}

void Application::emitActiveAttemptFinished(std::string_view reason) {
  auto& state = session.state;
  if (!isRemoteProbeMode(util::context.runMode) ||
      state.attemptFinishedEmitted ||
      state.activeInviteCallId.empty() ||
      !state.t_invite.has_value()) {
    return;
  }

  emitActiveAttemptStarted();
  std::string_view outcome = "no_provisional";
  if (state.probeEventEmitted) {
    outcome = "cell_observed";
  } else if (state.t_pr.has_value()) {
    outcome = "provisional_without_cell";
  }
  const auto finishedUnixMs =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::system_clock::now().time_since_epoch())
          .count();
  std::cout << probe_event::attemptFinished(
      state.activeInviteCallId,
      finishedUnixMs,
      outcome,
      reason) << std::endl;
  state.attemptFinishedEmitted = true;
}

void Application::CallDoS(
    pollfd& pfd,
    int nReady,
    const std::string& calleeId) {
  ProbeController(*this, session).run(pfd, nReady, calleeId);
}

bool Application::handleIncomingPackets(const int nReady) {
  if (util::context.shouldStop) return false;
  if (nReady < 0) {
    if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) return true;
    return false;
  }
  if (nReady > 0) {
    const ssize_t readCount =
        read(session.sock, session.recvBuffer, sizeof(session.recvBuffer));
    if (readCount < 0) {
      if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) return true;
      return false;
    }
    session.state.ack = false;
    session.dissect(readCount);
    if (session.currentSipState != SipState::IDLE && session.state.ack) {
      session.encapsulate(std::span<uint8_t>{});
    }
  }
  return true;
}
