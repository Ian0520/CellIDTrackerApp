#include "native_run_mode.h"

#include <cstdlib>
#include <iostream>

namespace {

void expect(NativeRunMode actual, NativeRunMode expected, const char* message) {
  if (actual == expected) return;
  std::cerr << "native_run_mode_test failed: " << message << std::endl;
  std::exit(1);
}

}  // namespace

int main() {
  expect(selectNativeRunMode({}), NativeRunMode::NONE, "no mode flags");
  expect(
      selectNativeRunMode({.remoteProbe = true}),
      NativeRunMode::REMOTE_PROBE,
      "remote mode");
  expect(
      selectNativeRunMode({.localServer = true}),
      NativeRunMode::LOCAL_SERVER,
      "local mode");
  expect(
      selectNativeRunMode({.adaptiveRemoteServer = true}),
      NativeRunMode::ADAPTIVE_REMOTE_SERVER,
      "adaptive mode");
  expect(
      selectNativeRunMode({.unavailabilityEvaluation = true}),
      NativeRunMode::UNAVAILABILITY_EVALUATION,
      "unavailability evaluation mode");
  expect(
      selectNativeRunMode({.detectionEvaluation = true}),
      NativeRunMode::DETECTION_EVALUATION,
      "detection evaluation mode");

  expect(
      selectNativeRunMode({
          .remoteProbe = true,
          .localServer = true,
          .adaptiveRemoteServer = true,
          .unavailabilityEvaluation = true,
          .detectionEvaluation = true}),
      NativeRunMode::REMOTE_PROBE,
      "remote mode keeps highest precedence");
  expect(
      selectNativeRunMode({
          .localServer = true,
          .adaptiveRemoteServer = true,
          .unavailabilityEvaluation = true,
          .detectionEvaluation = true}),
      NativeRunMode::LOCAL_SERVER,
      "local mode precedence");
  expect(
      selectNativeRunMode({
          .adaptiveRemoteServer = true,
          .unavailabilityEvaluation = true,
          .detectionEvaluation = true}),
      NativeRunMode::ADAPTIVE_REMOTE_SERVER,
      "adaptive mode precedence");
  expect(
      selectNativeRunMode({
          .unavailabilityEvaluation = true,
          .detectionEvaluation = true}),
      NativeRunMode::UNAVAILABILITY_EVALUATION,
      "evaluation mode precedence");

  return 0;
}
