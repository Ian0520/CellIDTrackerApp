#include "native_run_mode.h"

NativeRunMode selectNativeRunMode(const NativeRunModeFlags& flags) noexcept {
  if (flags.remoteProbe) return NativeRunMode::REMOTE_PROBE;
  if (flags.localServer) return NativeRunMode::LOCAL_SERVER;
  if (flags.adaptiveRemoteServer) return NativeRunMode::ADAPTIVE_REMOTE_SERVER;
  if (flags.unavailabilityEvaluation) {
    return NativeRunMode::UNAVAILABILITY_EVALUATION;
  }
  if (flags.detectionEvaluation) return NativeRunMode::DETECTION_EVALUATION;
  return NativeRunMode::NONE;
}
