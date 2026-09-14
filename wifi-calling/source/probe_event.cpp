#include "probe_event.h"

#include <iomanip>
#include <sstream>

namespace probe_event {
namespace {

std::string jsonString(std::string_view value) {
  std::ostringstream out;
  out << '"';
  for (const unsigned char character : value) {
    switch (character) {
      case '"': out << "\\\""; break;
      case '\\': out << "\\\\"; break;
      case '\b': out << "\\b"; break;
      case '\f': out << "\\f"; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (character < 0x20) {
          out << "\\u"
              << std::hex << std::setw(4) << std::setfill('0')
              << static_cast<int>(character)
              << std::dec << std::setfill(' ');
        } else {
          out << static_cast<char>(character);
        }
    }
  }
  out << '"';
  return out.str();
}

std::ostringstream eventPrefix(std::string_view eventName) {
  std::ostringstream out;
  out << "{\"contract\":" << jsonString(kContract)
      << ",\"version\":" << kVersion
      << ",\"event\":" << jsonString(eventName);
  return out;
}

void appendTiming(
    std::ostringstream& out,
    std::int64_t deltaMs,
    std::int64_t inviteElapsedMs,
    std::int64_t responseElapsedMs,
    std::int64_t inviteUnixMs,
    std::int64_t responseUnixMs) {
  out << ",\"delta_ms\":" << deltaMs
      << ",\"invite_elapsed_ms\":" << inviteElapsedMs
      << ",\"response_elapsed_ms\":" << responseElapsedMs
      << ",\"invite_unix_ms\":" << inviteUnixMs
      << ",\"response_unix_ms\":" << responseUnixMs;
}

}  // namespace

std::string streamReady() {
  auto out = eventPrefix("stream_ready");
  out << '}';
  return out.str();
}

std::string attemptStarted(
    std::string_view attemptId,
    std::int64_t inviteElapsedMs,
    std::int64_t inviteUnixMs) {
  auto out = eventPrefix("attempt_started");
  out << ",\"attempt_id\":" << jsonString(attemptId)
      << ",\"invite_elapsed_ms\":" << inviteElapsedMs
      << ",\"invite_unix_ms\":" << inviteUnixMs
      << '}';
  return out.str();
}

std::string provisionalReceived(
    std::string_view attemptId,
    int status,
    std::int64_t deltaMs,
    std::int64_t inviteElapsedMs,
    std::int64_t responseElapsedMs,
    std::int64_t inviteUnixMs,
    std::int64_t responseUnixMs) {
  auto out = eventPrefix("provisional_received");
  out << ",\"attempt_id\":" << jsonString(attemptId)
      << ",\"status\":" << status;
  appendTiming(
      out,
      deltaMs,
      inviteElapsedMs,
      responseElapsedMs,
      inviteUnixMs,
      responseUnixMs);
  out << '}';
  return out.str();
}

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
    int cid) {
  auto out = eventPrefix("cell_observed");
  out << ",\"attempt_id\":" << jsonString(attemptId)
      << ",\"status\":" << status;
  appendTiming(
      out,
      deltaMs,
      inviteElapsedMs,
      responseElapsedMs,
      inviteUnixMs,
      responseUnixMs);
  out << ",\"mcc\":" << mcc
      << ",\"mnc\":" << mnc
      << ",\"lac\":" << lac
      << ",\"cid\":" << cid
      << '}';
  return out.str();
}

std::string attemptFinished(
    std::string_view attemptId,
    std::int64_t finishedUnixMs,
    std::string_view outcome,
    std::string_view reason) {
  auto out = eventPrefix("attempt_finished");
  out << ",\"attempt_id\":" << jsonString(attemptId)
      << ",\"finished_unix_ms\":" << finishedUnixMs
      << ",\"outcome\":" << jsonString(outcome)
      << ",\"reason\":" << jsonString(reason)
      << '}';
  return out.str();
}

}  // namespace probe_event
