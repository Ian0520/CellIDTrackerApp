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
- optional altitude / speed / bearing if easy to collect

Recommended file name:
- `truth_session_<sessionId>.json`

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

### Important Note About Migration

- The compatibility migration preserves `probe_history`.
- If a device already had an old/inconsistent experiment schema, old experiment-session data may be dropped once during v6 -> v7 migration (by design to avoid startup crash and guarantee schema correctness).

### Next Probe-Side Work (Priority Order)

1. Add session management UX polish:
   - show active session elapsed time and sample count
   - add "re-export last completed session" action
2. Add probe-session validation tests:
   - migration test for v5 -> v6 -> v7
   - migration test for inconsistent legacy v6 -> v7
   - export JSON contract test using realistic end-to-end sample sets
3. Add operator workflow docs:
   - exact runbook for sharing `sessionId` with ground-truth app operator
   - pull/export commands and expected file locations
4. Optional probe-side tags:
   - add any additional carrier/environment tags available on probe device to exported sample records.

### Probe-Side Definition Of "One Session"

One probe session means one continuous collection run:
- tap **Start session**
- run probe zero or more times
- all produced probe samples are attached to the same `sessionId`
- tap **Stop & export** to finalize and write one `probe_session_<sessionId>.json`

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
