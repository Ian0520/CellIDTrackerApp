# Offline Two-App Experiment Data Collection Plan

## Summary

This document supersedes the earlier manual-ground-truth experiment design.

The desired architecture is:
- the **probing app** on the rooted device logs probe-side estimates
- a separate **ground-truth app** on the target device logs GNSS-based truth samples
- each app writes its own session file locally
- the two files are analyzed later on a computer by separate tooling

This is an **offline, file-based workflow**. There is no live streaming requirement and no requirement to compute experiment results inside either app.

This is the preferred design because it is simpler, more reliable, easier to debug, and easier to analyze reproducibly.

## Answers To The Two Design Questions

### 1. Probe every 30 seconds vs higher-rate ground truth

This is **not a concern**.

A sparse probe stream and a denser ground-truth stream is the normal and correct design.

Reasons:
- Probe events are the observations of interest. They are the low-rate samples to be evaluated.
- Ground truth should be denser so there is always a good truth point near each probe timestamp.
- Offline matching by timestamp is straightforward. The analysis program can match each probe sample to the nearest truth sample, or to a small truth window around the probe timestamp.

Recommended ground-truth sampling rate:
- `1 Hz` default for most sessions
- `0.2 Hz` to `1 Hz` is enough for stationary sessions
- higher than `1 Hz` is usually unnecessary for this study unless there is a specific motion-analysis reason

Storage is not a practical issue at these rates. Even long sessions at `1 Hz` produce small files compared with normal mobile app storage budgets.

### 2. Separate files and analyze later on a computer

Yes. This is the recommended approach.

It is better than in-app analysis for this project because:
- the analysis logic will evolve and is easier to change on a computer
- file inspection, plotting, filtering, and re-running are easier off-device
- it avoids unnecessary complexity in the Android apps
- it keeps the mobile apps focused on reliable data capture

Therefore:
- the probing app should only capture and export probe-side data
- the ground-truth app should only capture and export truth-side data
- the computer-side tooling should own matching, filtering, and final evaluation metrics

## Target Architecture

### Probe App Responsibilities

The probing app must:
- create and manage experiment sessions
- attach every probe result to a session
- export one probe-session file per session

The probing app must **not**:
- require manual ground-truth coordinate entry
- import ground-truth files
- compute final containment or error metrics in v1

### Ground-Truth App Responsibilities

The ground-truth app must:
- create or join the same session ID used by the probing app
- log timestamped GNSS truth samples
- export one truth-session file per session

The ground-truth app must **not**:
- perform probe-side geolocation logic
- compute final experiment metrics in v1

### Computer-Side Analysis Responsibilities

The offline analysis program must:
- load the probe session file
- load the truth session file
- verify that the session IDs match
- match each probe sample to truth data by timestamp
- compute experiment metrics
- output a report or table for later inspection

## Session Model

Both apps must share a common `sessionId`.

The `sessionId` must be:
- globally unique
- stable for the entire session
- stored in every exported file

Recommended format:
- UUID string

Both exported files must also include:
- `schemaVersion`
- `sessionId`
- `startedAtMillis`
- `endedAtMillis`
- app name
- app version
- device identifier if available and appropriate for the study

## Probe App Export Requirements

The probe app export must contain:
- session metadata
- an array of probe samples ordered by timestamp ascending

Each probe sample must include:
- `recordedAtMillis`
- `victim`
- `mcc`
- `mnc`
- `lac`
- `cid`
- `estimatedLat`
- `estimatedLon`
- `estimatedAccuracyM`
- `geolocationStatus`
- `geolocationError`
- `towersCount`
- `towersJson`
- `deltaMs`
- any carrier / environment tags the probe app already knows

Recommended file name:
- `probe_session_<sessionId>.json`

Recommended export directory:
- app-specific external files directory under an `experiment_sessions/` folder

## Ground-Truth App Export Requirements

The ground-truth app export must contain:
- session metadata
- an array of truth samples ordered by timestamp ascending

Each truth sample must include:
- `recordedAtMillis`
- `lat`
- `lon`
- `accuracyM`
- `provider`
- `speedMps` (record raw speed even if movement class is also stored)
- optional `speedAccuracyMps` if available from platform
- optional altitude / bearing if easy to collect
- `movementClass`
- optional `movementSource` (`auto` / `manual`) if operator override is supported

Recommended file name:
- `truth_session_<sessionId>.json`

### Ground-Truth Movement Class Recommendation

Yes, movement should be recorded and it is better to keep both:
- raw `speedMps`
- derived `movementClass`

Recommended v1 classes:
- `stationary`
- `slow_move` (e.g., walking)
- `medium_move` (e.g., running / bike in slow traffic)
- `fast_move` (vehicle)
- `unknown`

Recommended speed thresholds (m/s), tunable later:
- `stationary`: `< 0.5`
- `slow_move`: `0.5 - 1.8`
- `medium_move`: `1.8 - 4.5`
- `fast_move`: `>= 4.5`

If speed is unavailable for a sample:
- set `speedMps = null`
- set `movementClass = unknown`

## Matching Rules For Offline Analysis

The analysis program must treat the probe sample as the anchor event.

For each probe sample:
- find the nearest truth sample by timestamp
- record the absolute time difference in milliseconds as `timeDeltaMs`

Recommended default acceptance window:
- `<= 5 seconds`

If no truth sample exists inside the acceptance window:
- mark the probe sample as unmatched
- exclude it from containment metrics unless the analysis mode explicitly says otherwise

### Stationary Session Rule

For stationary sessions, either of these approaches is acceptable:
- nearest truth point by timestamp
- median truth position from a small window around the probe timestamp

Recommended default for v1:
- nearest truth point by timestamp

This is simpler and sufficient as long as truth sampling is reasonably dense.

## Metrics To Compute On The Computer

For every matched probe sample:
- `distanceErrorM`
- `withinReportedRadius`
- `reportedAccuracyM`
- `truthAccuracyM`
- `timeDeltaMs`

Recommended formulas:
- `distanceErrorM = haversine(estimatedLatLon, truthLatLon)`
- `withinReportedRadius = distanceErrorM <= reportedAccuracyM`

Recommended summary metrics:
- total matched probe samples
- match rate
- containment rate
- median distance error
- 90th percentile distance error
- 95th percentile distance error

## Implementation Guidance For The Probe App

The probe app should be changed so the experiment feature becomes **probe-session logging only**.

Required changes:
- remove manual ground-truth coordinate entry from the experiment design
- keep experiment session start/stop
- generate a `sessionId` at session start
- store probe samples exactly as they occur
- export one probe-session JSON file on session stop

The current manual-ground-truth fields are not part of the desired end state.

## Probe App Implementation Status (Updated 2026-04-10)

### Done

- Added probe-session persistence with two tables:
  - `experiment_sessions`
  - `experiment_samples`
- Added session lifecycle in app:
  - start session generates UUID `sessionId`
  - stop session ends the session and exports one file
- Added probe-sample attachment to active session for each probe event, including:
  - `recordedAtMillis`, `victim`, `mcc/mnc/lac/cid`
  - `estimatedLat/estimatedLon/estimatedAccuracyM`
  - `geolocationStatus`, `geolocationError`
  - `towersCount`, `towersJson`
  - `deltaMs`, `moving`
- Added export path and filename behavior:
  - directory: `.../files/experiment_sessions/`
  - filename: `probe_session_<sessionId>.json`
- Added session metadata in export:
  - `schemaVersion`, `sessionId`, `startedAtMillis`, `endedAtMillis`
  - app name/package/version
  - device identifier (manufacturer/model/device)
- Added startup compatibility migration for previously inconsistent paused schemas:
  - DB migration to v7 recreates experiment tables if v6 legacy shape is detected.
- Added parser-side duplicate suppression for native log bursts:
  - app now suppresses repeated parsed outputs by **probe-cycle boundary** (`100: Trying` marker), not by a long fixed time window
  - this avoids the previous over-suppression bug where repeated probes in the same cell were incorrectly dropped after the first sample
  - this directly addresses duplicate history/session entries caused by native retransmissions and immediate retry behavior (e.g., after `500/408/486`)
- Added app-side commit guard against duplicate geolocation/history writes:
  - if the same `(mcc,mnc,lac,cid,deltaMs)` is about to be committed again within a short window, it is dropped
  - this prevents parser burst edge cases from triggering repeated geolocation requests for one probe cycle
- Added canonical native probe event path to reduce log-coupled duplication:
  - native now emits one structured line per accepted probe transaction:
    - `[probe_event] call_id=... status=... delta_ms=... invite_ms=... pr_ms=... mcc=... mnc=... lac=... cid=...`
  - app-side history/geolocation now consumes this structured event directly instead of reconstructing samples from raw multi-line `mcc/mnc/lac/cellId` log text
  - app deduplicates by native `call_id`, so repeated `183 Session Progress` retransmissions in the same transaction cannot create repeated history entries
- Fixed `deltaMs` binding per saved probe sample:
  - app now uses delta-anchored pairing and supports both native line orders:
    - cell fields first then `[intercarrier] delta_ms=...`
    - `[intercarrier] delta_ms=...` first then cell fields
  - app only commits a probe sample when a fresh delta marker is matched (instead of reusing stale/old delta)
  - stale pending parsed data is dropped if it exceeds the delta-match freshness window, preventing cross-cycle mis-attachment
  - this avoids the "first sample only has delta, later samples null/stale or shifted" failure mode
- Patched native probe timing reset points:
  - reset `t_trying` / `t_pr` before every fresh INVITE (normal loop + immediate retry paths)
  - this ensures native can emit a new `[intercarrier] delta_ms=...` per probe cycle instead of only once per long-running process
  - corrected delta definition to match experiment semantics:
    - `delta_ms = first provisional response time (180/183) - INVITE send time`
    - applies to normal probe cycles and immediate retry cycles after `500/408/486`
  - added INVITE transaction binding by Call-ID:
    - each sent INVITE stores `activeInviteCallId`
    - stale delayed SIP responses from previous call dialogs are ignored for state/delta updates
    - prevents abnormally low delta after immediate retry when old provisional responses arrive late
  - hardened SIP Call-ID regeneration:
    - `setCallId()` now targets the `Call-ID:` header explicitly (instead of a generic 22-char token match)
    - reduces risk of retry INVITE accidentally reusing old Call-ID and contaminating delta after timeout restarts
  - hardened continuous-probe loop transaction isolation:
    - every fresh INVITE send now regenerates branch/call-id/from-tag before timing is armed
    - active transaction matching now checks both `Call-ID` and `Via branch` before applying SIP state/delta updates
    - this specifically targets the observed issue where normal continuous probing showed lower deltas than one-shot inter-carrier test after the first probe
  - adjusted continuous loop send order to reduce overlap bias:
    - in `SPROG` rollover paths, send `CANCEL` first and then send the next fresh `INVITE`
    - avoids arming next-cycle delta timing while previous leg is still active, improving comparability with one-shot inter-carrier measurements
  - corrected transport selection for fresh probe INVITEs:
    - fresh INVITE sends now force `INVITE` state before encapsulation, so they do not inherit `SPROG` and accidentally switch to UDP
    - this is the most likely cause of the 1200 ms one-shot vs 500 ms continuous mismatch
  - note on continuous probe closure experiment:
    - an attempted `487 -> ACK -> immediate new INVITE` closure path was tested and then rolled back
    - rollback reason: it caused over-frequent probe cycles and duplicate history entries in app-side parsing
  - aligned single-target `CallDoS` timeout-retry flow with `MultiCallDoS`:
    - `500/486/408` retry now enters explicit `BUSY` handling (`CANCEL -> fresh INVITE -> session swap`) instead of direct INVITE bypass
    - this keeps retry-cycle cleanup consistent and reduces contaminated low-delta retries after timeout restarts
  - hardened `CallDoS` timeout retry into a strict two-phase boundary:
    - on `500/486/408`, retry now sends `CANCEL` first and waits for termination (`487`/`200`) before arming the new INVITE
    - includes timeout fallback when cancel response is missing, to avoid retry deadlock
    - this targets overlap between old/new transactions that can skew restart-cycle `delta_ms` low
  - removed probe-mode native geolocation duplication:
    - in `remoteCellIDProber` mode, native no longer calls Google Geolocation / Reverse Geocoding inside `extractCellularInfo`
    - app side remains the single owner of geolocation lookup and history/session attachment per accepted probe sample
  - **requires rebuilding and rebundling the `spoof` binary into app assets to take effect on device**

### Important Note About Migration

- The compatibility migration preserves `probe_history`.
- If a device already had an old/inconsistent experiment schema, old experiment-session data may be dropped once during v6 -> v7 migration (by design to avoid startup crash and guarantee schema correctness).

### Next Probe-Side Work (Priority Order)

1. Add session management UX polish:
   - show active session elapsed time and sample count
   - add "re-export last completed session" action
2. Validate and document parser cycle-boundary assumptions:
   - current model accepts at most one parsed sample per `[intercarrier]` cycle
   - validate with field logs per carrier that this matches native output structure (and adjust if a carrier emits multiple true samples per cycle)
3. Tune and document delta pairing wait window:
   - current delta/cell match freshness window: 2 seconds
   - validate against field logs to ensure both line orders are matched reliably in normal conditions
4. Add probe-session validation tests:
   - migration test for v5 -> v6 -> v7
   - migration test for inconsistent legacy v6 -> v7
   - export JSON contract test using realistic end-to-end sample sets
5. Add operator workflow docs:
   - exact runbook for sharing `sessionId` with ground-truth app operator
   - pull/export commands and expected file locations
6. Optional probe-side tags:
   - add any additional carrier/environment tags available on probe device to exported sample records.

### Probe-Side Definition Of "One Session"

One probe session means one continuous collection run:
- tap **Start session**
- run probe zero or more times
- all produced probe samples are attached to the same `sessionId`
- tap **Stop & export** to finalize and write one `probe_session_<sessionId>.json`

Within one continuous run, repeated native parsing bursts inside the same probe cycle are de-duplicated by the app before history/session insertion.

## Acceptance Criteria

The design is complete when:
- the probing app can start a session and export a probe-session JSON file
- the ground-truth app can start or join the same session and export a truth-session JSON file
- a computer-side program can load both files and compute per-probe comparison results
- no live communication is required between the devices
- the ground-truth sampling rate can be higher than the probe rate without any app-side special handling

## Assumptions

- Probe frequency is roughly one sample every 30 seconds.
- Ground-truth frequency may be higher and that is expected.
- Offline analysis on a computer is the authoritative evaluation path.
- In-app analysis is out of scope for v1.
- Live streaming is out of scope for v1.
