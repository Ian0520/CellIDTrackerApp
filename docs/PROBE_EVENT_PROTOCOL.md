# Probe Event Protocol

The native probe emits newline-delimited JSON (JSONL) for machine consumption. Human-readable SIP and diagnostic lines remain available, but Android persistence must use this protocol once `stream_ready` is observed.

## Contract

- `contract`: `cellidtracker.probe`
- `version`: `1`
- One JSON object per stdout line.
- `attempt_id` is the SIP Call-ID of one fresh INVITE transaction.
- Unknown contracts, versions, events, missing fields, and inconsistent timing are rejected by the app parser.

## Events

| Event | Purpose | Event-specific fields |
|---|---|---|
| `stream_ready` | Announces that structured output is authoritative for this native process | None |
| `attempt_started` | Defines a fresh INVITE transaction | `attempt_id`, `invite_elapsed_ms`, `invite_unix_ms` |
| `provisional_received` | Records the first matching `180` or `183` | `attempt_id`, `status`, `delta_ms`, invite/response elapsed and Unix timestamps |
| `cell_observed` | Attaches the first valid cellular header to that attempt | Provisional fields plus `mcc`, `mnc`, `lac`, `cid` |
| `attempt_finished` | Closes an attempt at rollover or normal loop completion | `attempt_id`, `finished_unix_ms`, `outcome`, `reason` |

Example:

```json
{"contract":"cellidtracker.probe","version":1,"event":"provisional_received","attempt_id":"example-call-id","status":183,"delta_ms":749,"invite_elapsed_ms":100,"response_elapsed_ms":849,"invite_unix_ms":1700000000000,"response_unix_ms":1700000000749}
```

## Timing Invariant

`delta_ms` is computed only by the native process using `std::chrono::steady_clock`:

```text
first matching provisional response timestamp - fresh INVITE timestamp
```

The first accepted `180` or `183` fixes the response timestamp. Retransmitted provisional responses cannot replace it. Call-ID and Via branch checks reject stale responses from earlier attempts. Structured lines are serialized only after the response timestamp is fixed; attempts with no response emit their start event immediately before their finish event.

Unix timestamps are correlation metadata. They must not be used to recalculate latency because wall clocks can be adjusted.

## Android Reduction

The app reduces all events with the same `attempt_id` into one in-memory snapshot and one `probe_attempts` database row. Repeated or conflicting copies do not create changes. A cell observation starts one geolocation request and one history entry at most.

Legacy `[intercarrier]` and `[probe_event]` lines remain in stdout for diagnostics and compatibility with older packaged binaries. After `stream_ready`, the app displays but does not persist those legacy lines.

### Code ownership

- Native `sip_response` converts raw SIP payloads into typed response metadata and applies the existing Call-ID/Via-branch stale-response rule.
- Native `probe_state_machine` owns response-driven state decisions; it does not perform packet I/O or calculate latency.
- Native `probe_loop_policy` owns watchdog thresholds, while `ProbeController` executes the continuous transaction loop.
- Native `Session` retains packet decoding/encoding, INVITE/provisional timestamp capture, and structured event emission.
- Native `NativeRunMode` resolves overlapping CLI flags to one mode using the historical precedence order.
- Native `application.cpp` contains the active probe integration; CLI-only workflows are isolated in `legacy_application.cpp`.
- `ProbeStreamSession` owns one native process stream: protocol selection, legacy deduplication, ordered event reduction, and attempt context lifetime.
- `ProbeAttemptRepository` maps reduced attempt changes to Room writes and maintains the interval between accepted responses.
- `ProbeProcessCoordinator` owns process execution, watchdogs, controlled restarts, backoff, and user-stop state.
- `ProbeResultRepository`, `ProbeRunRepository`, and `ExperimentSessionRepository` own result, run, and session persistence respectively.
- `MainViewModel` owns Compose-facing state and delegates process and persistence work to those components.

Create a new `ProbeStreamSession` for every native process restart. Never reuse reducer or deduplication state across process invocations.

## Native Host Tests

Run `./scripts/test-native-contract.sh` from the repository root. The suite verifies the JSONL contract, SIP metadata and cell parsing, stale transaction matching, response-state decisions, watchdog boundaries, and native mode selection without requiring an Android device or live network.
