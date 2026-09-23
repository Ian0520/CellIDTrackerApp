#include "sip_capture.h"

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <regex>
#include <sstream>
#include <string_view>
#include <utility>
#include <vector>

#include "sip_response.h"

namespace {
struct HeaderEntry {
  std::string name;
  std::string value;
};

std::string jsonEscape(std::string_view value) {
  std::ostringstream out;
  for (const unsigned char c : value) {
    switch (c) {
      case '\"': out << "\\\""; break;
      case '\\': out << "\\\\"; break;
      case '\b': out << "\\b"; break;
      case '\f': out << "\\f"; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        // Keep JSONL valid even if a rejected transport payload contains binary bytes.
        if (c < 0x20 || c >= 0x7f) {
          out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
              << static_cast<int>(c) << std::dec;
        } else {
          out << c;
        }
    }
  }
  return out.str();
}

std::string quote(std::string_view value) {
  return "\"" + jsonEscape(value) + "\"";
}

std::string base64(std::span<const std::uint8_t> input) {
  static constexpr char alphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string output;
  output.reserve(((input.size() + 2) / 3) * 4);
  for (std::size_t i = 0; i < input.size(); i += 3) {
    const std::uint32_t a = input[i];
    const std::uint32_t b = i + 1 < input.size() ? input[i + 1] : 0;
    const std::uint32_t c = i + 2 < input.size() ? input[i + 2] : 0;
    const std::uint32_t value = (a << 16) | (b << 8) | c;
    output.push_back(alphabet[(value >> 18) & 0x3f]);
    output.push_back(alphabet[(value >> 12) & 0x3f]);
    output.push_back(i + 1 < input.size() ? alphabet[(value >> 6) & 0x3f] : '=');
    output.push_back(i + 2 < input.size() ? alphabet[value & 0x3f] : '=');
  }
  return output;
}

std::string fnv1a64(std::span<const std::uint8_t> input) {
  std::uint64_t hash = 14695981039346656037ULL;
  for (const auto byte : input) {
    hash ^= byte;
    hash *= 1099511628211ULL;
  }
  std::ostringstream out;
  out << std::hex << std::setw(16) << std::setfill('0') << hash;
  return out.str();
}

std::string fnv1a64(std::string_view input) {
  return fnv1a64(std::span<const std::uint8_t>(
      reinterpret_cast<const std::uint8_t*>(input.data()), input.size()));
}

std::int64_t unixMillis() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::system_clock::now().time_since_epoch())
      .count();
}

std::int64_t elapsedNanos() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

std::size_t readSize(const char* name, std::size_t fallback,
                     std::size_t minimum, std::size_t maximum) {
  const char* raw = std::getenv(name);
  if (raw == nullptr) return fallback;
  char* end = nullptr;
  const auto parsed = std::strtoull(raw, &end, 10);
  if (end == raw || *end != '\0') return fallback;
  return std::clamp<std::size_t>(parsed, minimum, maximum);
}

std::string trim(std::string_view value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string_view::npos) return "";
  const auto last = value.find_last_not_of(" \t\r\n");
  return std::string(value.substr(first, last - first + 1));
}

bool equalsIgnoreCase(std::string_view left, std::string_view right) {
  return left.size() == right.size() &&
      std::equal(left.begin(), left.end(), right.begin(),
                 [](unsigned char a, unsigned char b) {
                   return std::tolower(a) == std::tolower(b);
                 });
}

std::vector<HeaderEntry> captureHeaders(std::string_view payload) {
  const auto crlfEnd = payload.find("\r\n\r\n");
  const auto lfEnd = payload.find("\n\n");
  const auto end = crlfEnd != std::string_view::npos ? crlfEnd : lfEnd;
  const auto block = payload.substr(
      0, end == std::string_view::npos ? payload.size() : end);
  std::vector<HeaderEntry> headers;
  std::size_t cursor = 0;
  bool firstLine = true;
  while (cursor <= block.size()) {
    const auto next = block.find('\n', cursor);
    auto line = block.substr(cursor, next == std::string_view::npos
                                        ? block.size() - cursor
                                        : next - cursor);
    if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
    if (firstLine) {
      firstLine = false;
    } else if (!line.empty() && (line.front() == ' ' || line.front() == '\t') &&
               !headers.empty()) {
      headers.back().value += "\r\n" + std::string(line);
    } else {
      const auto colon = line.find(':');
      if (colon != std::string_view::npos) {
        headers.push_back(
            {std::string(line.substr(0, colon)), std::string(line.substr(colon + 1))});
      }
    }
    if (next == std::string_view::npos) break;
    cursor = next + 1;
  }
  return headers;
}

std::string headersJson(const std::vector<HeaderEntry>& headers) {
  std::ostringstream out;
  out << '[';
  for (std::size_t i = 0; i < headers.size(); ++i) {
    if (i > 0) out << ',';
    out << "{\"name\":" << quote(headers[i].name)
        << ",\"value\":" << quote(headers[i].value) << '}';
  }
  out << ']';
  return out.str();
}

std::string headerValue(const std::vector<HeaderEntry>& headers,
                        std::string_view name) {
  for (const auto& header : headers) {
    if (equalsIgnoreCase(header.name, name)) return trim(header.value);
  }
  return "";
}

std::string parameterValue(std::string_view value, std::string_view parameter) {
  const std::regex pattern(
      "(?:^|;)\\s*" + std::string(parameter) + "=([^;>\\s]+)",
      std::regex_constants::icase);
  std::match_results<std::string_view::const_iterator> match;
  if (std::regex_search(value.begin(), value.end(), match, pattern) &&
      match.size() >= 2) {
    return std::string(match[1].first, match[1].second);
  }
  return "";
}

std::span<const std::uint8_t> messageBody(std::span<const std::uint8_t> payload) {
  const std::string_view view(
      reinterpret_cast<const char*>(payload.data()), payload.size());
  auto marker = view.find("\r\n\r\n");
  std::size_t offset = 4;
  if (marker == std::string_view::npos) {
    marker = view.find("\n\n");
    offset = 2;
  }
  if (marker == std::string_view::npos) return {};
  return payload.subspan(marker + offset);
}

std::string firstLine(std::string_view payload) {
  const auto end = payload.find_first_of("\r\n");
  const auto length = std::min<std::size_t>(
      end == std::string_view::npos ? payload.size() : end, 512);
  return std::string(payload.substr(0, length));
}
}  // namespace

std::size_t SipCaptureRecorder::PendingRecord::memoryCost() const noexcept {
  return sizeof(PendingRecord) + payload.size() + event.size() + detail.size() +
      metadata.direction.size() + metadata.capturePoint.size() +
      metadata.transport.size() + metadata.sourceAddress.size() +
      metadata.destinationAddress.size();
}

SipCaptureRecorder::SipCaptureRecorder() {
  const char* path = std::getenv("SIP_CAPTURE_PATH");
  if (path == nullptr || path[0] == '\0') return;
  path_ = path;
  const char* runId = std::getenv("SIP_CAPTURE_RUN_ID");
  runId_ = runId == nullptr ? "unknown" : runId;
  maxBytes_ = readSize("SIP_CAPTURE_MAX_BYTES", maxBytes_, 64U * 1024U,
                       64U * 1024U * 1024U);
  maxFiles_ = static_cast<int>(
      readSize("SIP_CAPTURE_MAX_FILES", maxFiles_, 1, 32));
  maxQueueBytes_ = readSize("SIP_CAPTURE_QUEUE_MAX_BYTES", maxQueueBytes_,
                            64U * 1024U, 64U * 1024U * 1024U);
  enabled_.store(true);
  openFile();
  if (!enabled_.load()) return;
  writer_ = std::thread(&SipCaptureRecorder::writerLoop, this);
  recordRuntimeEvent("process_started", "native capture enabled");
}

SipCaptureRecorder::~SipCaptureRecorder() {
  if (!writer_.joinable()) return;
  recordRuntimeEvent("process_finished");
  {
    std::lock_guard lock(mutex_);
    stopping_ = true;
  }
  condition_.notify_one();
  writer_.join();
  output_.close();
}

void SipCaptureRecorder::openFile() {
  std::error_code error;
  const auto parent = std::filesystem::path(path_).parent_path();
  if (!parent.empty()) std::filesystem::create_directories(parent, error);
  output_.clear();
  output_.open(path_, std::ios::binary | std::ios::app);
  if (!output_) {
    enabled_.store(false);
    std::cerr << "[capture] unable to open research capture file" << std::endl;
  }
}

void SipCaptureRecorder::rotateIfNeeded(std::size_t incomingBytes) {
  if (!enabled_.load()) return;
  std::error_code error;
  const auto size = std::filesystem::file_size(path_, error);
  if (!error && size + incomingBytes > maxBytes_) rotate();
}

void SipCaptureRecorder::rotate() {
  output_.close();
  std::error_code error;
  if (maxFiles_ <= 1) {
    std::filesystem::remove(path_, error);
  } else {
    std::filesystem::remove(path_ + "." + std::to_string(maxFiles_ - 1), error);
    for (int index = maxFiles_ - 2; index >= 1; --index) {
      error.clear();
      std::filesystem::rename(path_ + "." + std::to_string(index),
                              path_ + "." + std::to_string(index + 1), error);
    }
    error.clear();
    std::filesystem::rename(path_, path_ + ".1", error);
  }
  openFile();
}

void SipCaptureRecorder::appendLine(const std::string& line) {
  if (!enabled_.load()) return;
  rotateIfNeeded(line.size() + 1);
  output_ << line << '\n';
  output_.flush();
  if (!output_) {
    enabled_.store(false);
    std::cerr << "[capture] research capture write failed" << std::endl;
  }
}

void SipCaptureRecorder::enqueue(PendingRecord&& record) {
  if (!enabled_.load()) return;
  const auto cost = record.memoryCost();
  {
    std::lock_guard lock(mutex_);
    if (stopping_ || !enabled_.load()) return;
    if (queuedBytes_ + cost > maxQueueBytes_) {
      ++droppedEvents_;
      return;
    }
    record.sequence = ++sequence_;
    queuedBytes_ += cost;
    queue_.push_back(std::move(record));
    ++enqueuedEvents_;
  }
  condition_.notify_one();
}

void SipCaptureRecorder::recordRuntimeEvent(const std::string& event,
                                            const std::string& detail) {
  PendingRecord record;
  record.type = PendingType::RUNTIME_EVENT;
  record.unixMillis = unixMillis();
  record.elapsedNanos = elapsedNanos();
  record.event = event;
  record.detail = detail;
  enqueue(std::move(record));
}

void SipCaptureRecorder::recordPayload(std::span<const std::uint8_t> payload,
                                       const SipCaptureMetadata& metadata) {
  if (!enabled_.load() || payload.empty()) return;
  PendingRecord record;
  record.type = PendingType::PAYLOAD;
  record.unixMillis = unixMillis();
  record.elapsedNanos = elapsedNanos();
  record.metadata = metadata;
  record.payload.assign(payload.begin(), payload.end());
  enqueue(std::move(record));
}

std::string SipCaptureRecorder::serialize(const PendingRecord& record) {
  std::ostringstream out;
  out << "{\"capture_format_version\":1"
      << ",\"record_type\":"
      << quote(record.type == PendingType::PAYLOAD ? "transport_payload"
                                                   : "runtime_event")
      << ",\"sequence\":" << record.sequence
      << ",\"process_id\":" << getpid()
      << ",\"run_id\":" << quote(runId_)
      << ",\"unix_ms\":" << record.unixMillis
      << ",\"elapsed_ns\":" << record.elapsedNanos;

  if (record.type == PendingType::RUNTIME_EVENT) {
    out << ",\"event\":" << quote(record.event)
        << ",\"detail\":" << quote(record.detail) << '}';
    return out.str();
  }

  const std::string body(
      reinterpret_cast<const char*>(record.payload.data()), record.payload.size());
  const auto parsed = sip_response::parse(body);
  const auto headers = captureHeaders(body);
  const auto payloadHash = fnv1a64(record.payload);
  const auto cseqValue = headerValue(headers, "CSeq");
  std::string cseq;
  std::string cseqMethod;
  {
    std::istringstream input(cseqValue);
    input >> cseq >> cseqMethod;
  }
  const auto fromTag = parameterValue(headerValue(headers, "From"), "tag");
  const auto toTag = parameterValue(headerValue(headers, "To"), "tag");
  const auto dialogId = fnv1a64(parsed.callId + "|" + fromTag + "|" + toTag);
  const auto retransmissionKey = fnv1a64(
      record.metadata.direction + "|" + record.metadata.capturePoint + "|" +
      parsed.callId + "|" + cseqValue + "|" + parsed.branch + "|" + payloadHash);
  const auto occurrence = ++occurrenceCounts_[retransmissionKey];
  const auto sipBody = messageBody(record.payload);

  out << ",\"direction\":" << quote(record.metadata.direction)
      << ",\"capture_point\":" << quote(record.metadata.capturePoint)
      << ",\"transport\":" << quote(record.metadata.transport)
      << ",\"ip_version\":" << record.metadata.ipVersion
      << ",\"source_address\":" << quote(record.metadata.sourceAddress)
      << ",\"destination_address\":" << quote(record.metadata.destinationAddress)
      << ",\"source_port\":" << record.metadata.sourcePort
      << ",\"destination_port\":" << record.metadata.destinationPort
      << ",\"tcp_sequence\":" << record.metadata.tcpSequence
      << ",\"tcp_acknowledgment\":" << record.metadata.tcpAcknowledgment
      << ",\"esp_sequence\":" << record.metadata.espSequence
      << ",\"encrypted_packet_size\":" << record.metadata.encryptedPacketSize
      << ",\"payload_size\":" << record.payload.size()
      << ",\"payload_hash_fnv1a64\":" << quote(payloadHash)
      << ",\"retransmission_key\":" << quote(retransmissionKey)
      << ",\"occurrence\":" << occurrence
      << ",\"is_repeat\":" << (occurrence > 1 ? "true" : "false")
      << ",\"recognized_sip\":" << (parsed.recognized() ? "true" : "false")
      << ",\"start_line\":" << quote(firstLine(body))
      << ",\"method\":" << quote(parsed.method)
      << ",\"status\":" << parsed.status
      << ",\"reason\":" << quote(parsed.reason)
      << ",\"call_id\":" << quote(parsed.callId)
      << ",\"cseq\":" << quote(cseq)
      << ",\"cseq_method\":" << quote(cseqMethod)
      << ",\"via_branch\":" << quote(parsed.branch)
      << ",\"from_tag\":" << quote(fromTag)
      << ",\"to_tag\":" << quote(toTag)
      << ",\"dialog_id\":" << quote(dialogId)
      << ",\"headers\":" << headersJson(headers)
      << ",\"body_base64\":" << quote(base64(sipBody))
      << ",\"payload_base64\":" << quote(base64(record.payload)) << '}';
  return out.str();
}

std::string SipCaptureRecorder::serializeSummary() {
  std::uint64_t sequence;
  std::uint64_t enqueued;
  std::uint64_t written;
  std::uint64_t dropped;
  {
    std::lock_guard lock(mutex_);
    sequence = ++sequence_;
    enqueued = enqueuedEvents_;
    written = writtenEvents_;
    dropped = droppedEvents_;
  }
  std::ostringstream out;
  out << "{\"capture_format_version\":1,\"record_type\":\"runtime_event\""
      << ",\"sequence\":" << sequence
      << ",\"process_id\":" << getpid()
      << ",\"run_id\":" << quote(runId_)
      << ",\"unix_ms\":" << unixMillis()
      << ",\"elapsed_ns\":" << elapsedNanos()
      << ",\"event\":\"capture_summary\",\"detail\":\"\""
      << ",\"enqueued_event_count\":" << enqueued
      << ",\"written_event_count\":" << written
      << ",\"dropped_event_count\":" << dropped << '}';
  return out.str();
}

void SipCaptureRecorder::writerLoop() {
  while (true) {
    PendingRecord record;
    {
      std::unique_lock lock(mutex_);
      condition_.wait(lock, [this] { return stopping_ || !queue_.empty(); });
      if (queue_.empty() && stopping_) break;
      record = std::move(queue_.front());
      queuedBytes_ -= record.memoryCost();
      queue_.pop_front();
    }
    if (enabled_.load()) {
      appendLine(serialize(record));
      if (enabled_.load()) {
        std::lock_guard lock(mutex_);
        ++writtenEvents_;
      }
    }
  }
  if (enabled_.load()) appendLine(serializeSummary());
}
