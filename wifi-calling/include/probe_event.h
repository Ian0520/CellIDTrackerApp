#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace probe_event {

inline constexpr std::string_view kContract = "cellidtracker.probe";
inline constexpr int kVersion = 1;

std::string streamReady();

std::string attemptStarted(
    std::string_view attemptId,
    std::int64_t inviteElapsedMs,
    std::int64_t inviteUnixMs);

std::string provisionalReceived(
    std::string_view attemptId,
    int status,
    std::int64_t deltaMs,
    std::int64_t inviteElapsedMs,
    std::int64_t responseElapsedMs,
    std::int64_t inviteUnixMs,
    std::int64_t responseUnixMs);

std::string cellObserved(
    std::string_view attemptId,
    int status,
    std::int64_t deltaMs,
    std::int64_t inviteElapsedMs,
    std::int64_t responseElapsedMs,
    std::int64_t inviteUnixMs,
    std::int64_t responseUnixMs,
    int mcc,
    int mnc,
    int lac,
    int cid);

std::string attemptFinished(
    std::string_view attemptId,
    std::int64_t finishedUnixMs,
    std::string_view outcome,
    std::string_view reason);

}  // namespace probe_event
