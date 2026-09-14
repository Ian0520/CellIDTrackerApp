# Phase 0 Behavior Baseline

This baseline captures observable behavior before the probe pipeline is reconstructed. It is a regression reference, not a claim that the current architecture is correct.

## Source and artifacts

- Checkpoint tag: `pre-refactor-20260914`
- Checkpoint commit: `477d9b5`
- Android database version: `11`
- Probe session export schema: `3`
- Packaged `armeabi-v7a` probe SHA-256: `7bd4fd827755dceb0b90a5e4455d93a4b4cae786e31d116b361b2d65289f5749`
- Android minimum API: `24`
- Native build target API: `29`

The checksum in `checksums/native-probe.sha256` detects accidental replacement of the packaged executable. Update it deliberately whenever a rebuilt native executable is bundled.

## Current app-to-native contract

The normal probe command uses remote mode (`-r`), verbose level 1, `PROBE_INTERVAL_SECONDS`, and an optional `--session-progress-response-limit`. Although `-d` is also passed, remote-mode dispatch currently takes precedence.

The app recognizes two machine-readable stdout records:

```text
[intercarrier] status=<status> delta_ms=<duration> invite=<steady-ms> pr=<steady-ms>
[probe_event] call_id=<id> status=<status> delta_ms=<duration> invite_ms=<steady-ms> pr_ms=<steady-ms> mcc=<mcc> mnc=<mnc> lac=<lac> cid=<cid>
```

The sanitized fixture at `app/src/test/resources/probe/native_stdout_sample.txt` characterizes these formats. Human-readable SIP and FSM logs are not persistence contracts.

## Timing invariants

- `delta_ms` is computed inside the native process with `std::chrono::steady_clock`.
- It measures the fresh INVITE timestamp to the first accepted provisional response timestamp, currently status 180 or 183.
- A later response carrying cell information reuses that first provisional timestamp for its emitted `probe_event`.
- Call-ID and branch checks reject responses that clearly belong to an older INVITE.
- The default number of 183 responses retained before rollover remains carrier-specific; CHT currently defaults to six unless the app supplies an override.
- Phase 0 does not change INVITE cadence, response thresholds, restart behavior, or SIP state transitions.

## Verification

Run the behavior-preserving Android checks and packaged-artifact checksum with:

```bash
./scripts/verify.sh
```

Also compile the native probe when an Android NDK is available:

```bash
VERIFY_NATIVE_BUILD=1 NDK=$HOME/android-ndk-r25 ./scripts/verify.sh
```

The Room version-10-to-11 migration test is packaged in the Android test APK. It must be run on an emulator or connected device with `./gradlew :app:connectedDebugAndroidTest` before a database-changing release.
