# Caller-Side SIP Observability Experiment

## Fixed Configuration

- Caller: rooted CellIDTracker phone with comprehensive capture enabled.
- Target: authorized LocationData phone, stationary during each A-B-A block.
- Minimum INVITE interval: `10 seconds`.
- Provisional-response limit: `1`.
- Auto-restart: enabled.
- Both apps generate `sipobs_YYYYMMDD` on first use and retain it. Verify the displayed values match, then keep that ID across both days.
- Use a distinct condition label for every block.

Do not change APKs, settings, target position, or analysis definitions during a campaign. Probe latency is calculated only from the caller's monotonic clock.

## Preflight

1. Confirm VoWiFi is registered on both relevant devices and the caller has root.
2. Confirm CellIDTracker can complete a 2-3-attempt capture and export without a native crash.
3. Confirm LocationData can start and export with the same campaign and condition values.
4. Verify the caller shows `10s`, response limit `1`, and auto-restart enabled.
5. Keep the target at one physical location and avoid unrelated calls or network changes.

## A-B-A Collection

Use a campaign such as `sipobs_20260916`. Suggested condition labels are `day1_A1_normal`, `day1_B_normal`, and `day1_A2_normal`.

For each block:

1. Connect the target to the specified Wi-Fi network and wait five minutes for stable VoWiFi registration.
2. Start LocationData with the campaign ID and block condition.
3. In CellIDTracker, enable comprehensive capture and enter the identical campaign and condition.
4. Press **Start session**, then **Start probe**.
5. Collect 30 fresh persisted INVITE attempts. Do not count retransmitted SIP responses as attempts.
6. Press **Stop probe** and wait for process completion.
7. Press **Stop & export** in CellIDTracker, then stop/export LocationData.
8. Record any serving-cell change, AP failure, IMS loss, process restart, or manual interaction.
9. Pull both exports before starting the next block.

Run A, then B, then A again. Networks A and B should use different APs and internet exits. Repeat the full sequence on a second day with `day2_*` labels.

## Signaling-State Blocks

After the A-B-A sequence, keep the target on Network A and collect separate sessions for 20 normal ring-and-cancel attempts and 10 manually rejected attempts. Defer answered-call collection until that path has passed a short controlled smoke test. Never mix these actions into an A-B-A block.

## Export Locations

Sanitized caller files:

```text
/sdcard/Android/data/com.example.cellidtracker/files/experiment_sessions/<sessionId>.json
/sdcard/Android/data/com.example.cellidtracker/files/experiment_sessions/<sessionId>_research/
```

Raw caller evidence remains private:

```text
/data/user/0/com.example.cellidtracker/files/research_captures/<sessionId>/
```

Ground-truth export:

```text
/sdcard/Android/data/com.example.locationdata/files/ground_truth_sessions/<sessionId>.json
```

Store pulled files under the repository's ignored `captures/` directory. Raw files contain sensitive identifiers and must never enter Git.

## Block Validity

A primary block is valid only when it has 30 unique persisted attempts, matching caller/target campaign and condition labels, no capture-copy/write errors, parseable JSONL, stable configuration, and no unexplained recording gap. Keep interrupted or anomalous blocks as diagnostics, but repeat them rather than combining them with a valid block.
