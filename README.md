# Location Tracking Android App Exploiting IMS Messaging Vulnerabilities

## About the app
CellIDTracker is an Android app that drives a bundled (root-required) probe binary to discover cell IDs, looks up their location via Google Geolocation, and visualizes the result on an OpenStreetMap (osmdroid) map with an accuracy circle.

Key features:
- Set victim number (written to `victim_list` for the probe).
- Run the probe (root) and consume versioned native events containing timing and MCC/MNC/LAC/CID.
- Send the latest cell from each native attempt to Google Geolocation; show lat/lon/accuracy on the map with an accuracy circle.
- Start/stop offline experiment sessions with automatic date/time IDs and filenames.
- Optionally record bounded caller-side SIP and device context for controlled research campaigns.
- View recent probe history (time, victim, cell info, location) and tap an entry to restore its location on the map.
- View the raw log stream inside the app.

## Requirements
- Rooted device (probe binary runs via `su`).
- Google Geolocation API key (placed locally, not committed).
- Network access for geolocation.
- Configs have a single source of truth in `wifi-calling/config/...`. The app build copies them into `app/src/main/assets/config/...` automatically. Edit only `wifi-calling/config`.
- Bundled probe binary per ABI in assets (e.g., `app/src/main/assets/probe/armeabi-v7a/spoof`). Add other ABIs (e.g., arm64-v8a) as needed.
- To rebuild the probe: Android NDK (r25 in the example), CMake (>=3.24), and the native dependencies present (Crypto++, libcurl with mbedtls, nlohmann/json) pulled by the `wifi-calling` CMake.

## Build and install
```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Developer verification

Run the Android tests, lint, APK builds, and packaged native-artifact check before committing:

```bash
./scripts/verify.sh
```

Set `VERIFY_NATIVE_BUILD=1` and `NDK` to include a native Release build. The pre-refactor behavior contract is recorded in `docs/baselines/PHASE_0_BASELINE.md`.

### Provide your Google API key (Android app)
Put your key in `local.properties` (git-ignored):
```
GOOGLE_API_KEY=your_real_key_here
```
The build injects it into `BuildConfig.GOOGLE_API_KEY`; the app and bundled probe read it from there and pass it to the native binary as an environment variable.

## Notes
- When preparing a run, the app copies `probe/<abi>/spoof` and `config/...` from assets into private storage, then stages `probe/spoof`, `config/...`, and `victim_list` under `/data/local/tmp/cellidtracker-<appUid>/`. It changes to that directory and executes through `su -c`.
- The Android app invokes only the native remote-probe mode; other native CLI workflows are isolated from the active application source.
- Native startup sets airplane mode on the **probing phone**, enables Wi-Fi, and waits for its ePDG interface and IMS/IPsec configuration. Wi-Fi Calling must be available on that phone.
- Map uses osmdroid; deprecation warnings are cosmetic.
- History records time and victim; it is not segmented per target unless you clear it manually.

## Start the probe from the Android app

1. Open **Probe**, enter the authorized test number, and press **Set victim number**. The native workflow expects a local Taiwan number beginning with `0`, for example `09XXXXXXXX` with the placeholder replaced by the actual number.
2. Select the INVITE interval, 183 response limit and auto-restart. The app's initial interval is **10 seconds**; explicitly select response limit **1** for the existing research setup.
3. For session recording, enable **Comprehensive SIP research capture** as needed, fill Campaign/Condition, and press **Start session**.
4. Press **Probe** separately and check new log/results. Session creation does not start probing.
5. Press **Stop**, wait for **Idle**, then **Stop & export** if a session is active.

The app's native invocation is equivalent to:

```text
working directory: /data/local/tmp/cellidtracker-<appUid>
environment: GOOGLE_API_KEY, PROBE_INTERVAL_SECONDS, optional SIP_CAPTURE_*
command: ./probe/spoof -r --verbose 1 [--session-progress-response-limit N]
```

The response-limit argument is omitted for **Carrier default**. The app also manages a PID file and process restarts. A bare manual `./spoof -r` instead defaults to **30 seconds**, carrier-default response limit, and no native capture unless configured. See [manual CLI startup](wifi-calling/README.md).

The legacy `-l` flag starts a command server. For the current **local mall database collection**, use LocationData and [the local database plan](docs/LOCAL_DATABASE_PRECISION_PLAN.md).

## Rebuild and bundle the probe binary
From the repository root, build and explicitly copy the executable into app assets. The script uses **android-29 / armeabi-v7a**:
```bash
export NDK="$HOME/android-ndk-r25"
./scripts/build-native-probe.sh
cp wifi-calling/bin/spoof app/src/main/assets/probe/armeabi-v7a/spoof
```

After deliberately replacing the bundled executable, update and verify the pinned checksum, then rebuild/reinstall the APK:

```bash
sha256sum app/src/main/assets/probe/armeabi-v7a/spoof > checksums/native-probe.sha256
./scripts/verify-native-artifact.sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Gradle automatically synchronizes config files, but does not compile or copy the native executable. For another ABI, configure a separate CMake build and copy its binary to `app/src/main/assets/probe/<abi>/`. Do not reuse a build directory configured for a different ABI.

## Export probe history to a file
1. In the app, open the **History** tab and tap **Export all to file**.  
2. The app writes a JSON file to its external files directory:  
   `Android/data/com.example.cellidtracker/files/probe_history.json`
3. Pull it to your computer (USB + adb):  
   ```bash
   adb pull /sdcard/Android/data/com.example.cellidtracker/files/probe_history.json .
   ```
The JSON contains each entry’s victim, mcc/mnc/lac/cid, lat/lon/accuracy, timestamp, towersCount, and the full towers array used for that lookup.

## Offline experiment session export (probe app side)
1. In the **Probe** tab, tap **Start session**. The session ID is generated automatically from the current date and time.
2. Tap **Probe** to start probing.
3. Tap **Stop** and wait for **Idle** when finished.
4. Tap **Stop & export**.

The app exports:
- `Android/data/com.example.cellidtracker/files/experiment_sessions/<sessionId>.json`

Schema 4 exports one record per native INVITE attempt, including attempts without a returned cell. It records Call-ID, monotonic INVITE/provisional timestamps, correlation wall-clock timestamps, `deltaMs`, SIP status, outcome, actual probe interval, returned cell/location, and session-salted probe-side Wi-Fi context. See `EXPERIMENT_DATA_SCHEMA.md` for the complete cross-app schema and `docs/PROBE_EVENT_PROTOCOL.md` for the native/app contract.

## Caller-side SIP observability experiment

The existing campaign selects a **10-second INVITE interval**, **one provisional response**, and auto-restart enabled. Ten seconds is a selected setting, not the native software's hard lower bound; actual sends can be later. Both apps generate and retain a date-based campaign ID when first needed; they do not automatically synchronize it or reset it each day. Verify/update the campaign for the current visit, then assign conditions according to the experiment's pairing plan.

Raw SIP evidence remains in app-private storage. `Stop & export` writes a sanitized research directory beside the normal session export. Follow [`docs/SIP_OBSERVABILITY_EXPERIMENT.md`](docs/SIP_OBSERVABILITY_EXPERIMENT.md) for the A-B-A procedure, naming rules, extraction, and validity checks.

The follow-up [cell sequence study](docs/CELL_SEQUENCE_STUDY.md) measures what one cell per attempt and the sequence of attempts reveal. Its offline tools report stationary false-change controls and score empirical one-cell areas on separate-day target GPS. Private captures and per-attempt locations remain ignored under `captures/`.
