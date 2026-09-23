#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <condition_variable>
#include <deque>
#include <fstream>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

struct SipCaptureMetadata {
  std::string direction;
  std::string capturePoint;
  std::string transport;
  int ipVersion{0};
  std::string sourceAddress;
  std::string destinationAddress;
  std::uint16_t sourcePort{0};
  std::uint16_t destinationPort{0};
  std::uint32_t tcpSequence{0};
  std::uint32_t tcpAcknowledgment{0};
  std::uint32_t espSequence{0};
  std::size_t encryptedPacketSize{0};
};

class SipCaptureRecorder {
 public:
  SipCaptureRecorder();
  ~SipCaptureRecorder();

  [[nodiscard]] bool enabled() const noexcept { return enabled_.load(); }
  void recordPayload(std::span<const std::uint8_t> payload,
                     const SipCaptureMetadata& metadata);
  void recordRuntimeEvent(const std::string& event,
                          const std::string& detail = "");

 private:
  enum class PendingType { PAYLOAD, RUNTIME_EVENT };

  struct PendingRecord {
    PendingType type{PendingType::RUNTIME_EVENT};
    std::uint64_t sequence{0};
    std::int64_t unixMillis{0};
    std::int64_t elapsedNanos{0};
    SipCaptureMetadata metadata;
    std::vector<std::uint8_t> payload;
    std::string event;
    std::string detail;

    [[nodiscard]] std::size_t memoryCost() const noexcept;
  };

  void enqueue(PendingRecord&& record);
  void writerLoop();
  [[nodiscard]] std::string serialize(const PendingRecord& record);
  [[nodiscard]] std::string serializeSummary();
  void appendLine(const std::string& line);
  void openFile();
  void rotateIfNeeded(std::size_t incomingBytes);
  void rotate();

  std::atomic<bool> enabled_{false};
  std::string path_;
  std::string runId_;
  std::size_t maxBytes_{16U * 1024U * 1024U};
  int maxFiles_{8};
  std::size_t maxQueueBytes_{8U * 1024U * 1024U};
  std::uint64_t sequence_{0};
  std::uint64_t enqueuedEvents_{0};
  std::uint64_t writtenEvents_{0};
  std::uint64_t droppedEvents_{0};
  std::size_t queuedBytes_{0};
  bool stopping_{false};
  std::mutex mutex_;
  std::condition_variable condition_;
  std::deque<PendingRecord> queue_;
  std::thread writer_;
  std::ofstream output_;
  std::unordered_map<std::string, std::uint64_t> occurrenceCounts_;
};
