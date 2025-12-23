# CellIDTracker

## About the app
CellIDTracker is an Android app that drives a bundled (root-required) probe binary to discover cell IDs, looks up their location via Google Geolocation, and visualizes the result on an OpenStreetMap (osmdroid) map with an accuracy circle.

Key features:
- Set victim number (written to `victim_list` for the probe).
- Run the probe (root) and live-parse MCC/MNC/LAC/CID from stdout.
- Send multiple recent cell towers to Google Geolocation to improve stability/accuracy; show lat/lon/accuracy on the map with an accuracy circle.
- View recent probe history (time, victim, cell info, location) and tap an entry to restore its location on the map.
- View the raw log stream inside the app.

## Requirements
- Rooted device (probe binary runs via `su`).
- Google Geolocation API key (currently hard-coded in the app).
- Network access for geolocation.
- Bundled probe binary and configs per ABI in assets (e.g., `app/src/main/assets/probe/armeabi-v7a/spoof` and `app/src/main/assets/config/...`). Add other ABIs (e.g., arm64-v8a) as needed.

## Build and install
```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Notes
- On first run, the app copies `probe/<abi>/spoof` and `config/...` from assets into its private storage and executes the probe via root.
- Map uses osmdroid; deprecation warnings are cosmetic.
- History records time and victim; it is not segmented per target unless you clear it manually.
