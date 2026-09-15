#pragma once

enum class NativeRunMode {
  NONE,
  REMOTE_PROBE,
  LOCAL_SERVER,
  ADAPTIVE_REMOTE_SERVER,
  UNAVAILABILITY_EVALUATION,
  DETECTION_EVALUATION
};

struct NativeRunModeFlags {
  bool remoteProbe{false};
  bool localServer{false};
  bool adaptiveRemoteServer{false};
  bool unavailabilityEvaluation{false};
  bool detectionEvaluation{false};
};

[[nodiscard]] NativeRunMode selectNativeRunMode(
    const NativeRunModeFlags& flags) noexcept;

[[nodiscard]] constexpr bool isRemoteProbeMode(NativeRunMode mode) noexcept {
  return mode == NativeRunMode::REMOTE_PROBE;
}
