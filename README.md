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
- To rebuild the probe: Android NDK (r25 in the example), CMake (>=3.24), and the native dependencies present (Crypto++, libcurl with mbedtls, nlohmann/json) pulled by the `wifi-calling` CMake.

## Build and install
```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Notes
- On first run, the app copies `probe/<abi>/spoof` and `config/...` from assets into its private storage and executes the probe via root.
- Map uses osmdroid; deprecation warnings are cosmetic.
- History records time and victim; it is not segmented per target unless you clear it manually.

## Rebuild and bundle the probe binary
Use your NDK toolchain to rebuild and copy the binary into app assets. Example for armeabi-v7a:
```bash
export NDK=$HOME/android-ndk-r25
cd wifi-calling
cmake -S all -B build \
  -D CMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -D ANDROID_PLATFORM=android-29 \
  -D ANDROID_ABI=armeabi-v7a \
  -D CMAKE_BUILD_TYPE=Release
cmake --build build --config Release
cp bin/spoof ../app/src/main/assets/probe/armeabi-v7a/
```
Repeat with `ANDROID_ABI=arm64-v8a` and copy to `app/src/main/assets/probe/arm64-v8a/` for 64-bit devices. Then rebuild the APK so the updated binary is packaged.
