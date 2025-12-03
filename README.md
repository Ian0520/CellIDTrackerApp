# CellIDTracker

## 新增功能：
1. Clean up UI
## 待補：
1. 自動化CellID偵測->顯示位置
2. 改用Google Maps

## How to run
try to build   
./gradlew :app:assembleDebug   
to install  
./gradlew clean :app:installDebug  

## Building the native probe externally and bundling it
1. Build for each ABI you need (example for arm64-v8a; adjust ABI/platform as needed):
   ```
   cd wifi-calling
   cmake -S all -B build -D CMAKE_TOOLCHAIN_FILE=$HOME/android-ndk-r25/build/cmake/android.toolchain.cmake -D ANDROID_PLATFORM=android-29 -D ANDROID_ABI=arm64-v8a -D CMAKE_BUILD_TYPE=Release
   cmake --build build --config Release
   ```
2. Copy the resulting `spoof` binary into the app assets for that ABI:
   ```
   mkdir -p app/src/main/assets/probe/arm64-v8a
   cp wifi-calling/bin/spoof app/src/main/assets/probe/arm64-v8a/
   ```
   (Repeat for other ABIs you build, e.g., armeabi-v7a → `probe/armeabi-v7a/`.)
3. Copy the configs into app assets:
   ```
   rm -rf app/src/main/assets/config
   cp -r wifi-calling/config app/src/main/assets/config
   ```
4. Build and install the app (`./gradlew :app:assembleDebug` or `:app:installDebug`). At runtime the app extracts `probe/<abi>/spoof` and `config/...` to its private storage and runs the probe via root.

If you instead wire Gradle/CMake to build the native code inside the app, ensure all native deps are vendored locally (no network fetch), and add a copy task to move `spoof` from the native build output into `app/src/main/assets/probe/<abi>/`.


## Native probe layout (recommended for GitHub)
- Put native sources under `app/src/main/cpp/` (e.g., `third_party/wificalling`, `core_adapter`, etc.) so Gradle can see them.
- Vendor any native deps (Crypto++, curl, mbedtls, nlohmann/json) under `app/src/main/cpp/third_party/` or commit prebuilt static libs per ABI to avoid network fetches.
- If you build the executable externally, store the outputs per ABI under `app/src/main/assets/probe/<abi>/spoof` (e.g., `probe/arm64-v8a/spoof`, `probe/armeabi-v7a/spoof`) and configs under `app/src/main/assets/config/...`.
- The app copies these to `filesDir` at runtime and runs `./probe/spoof` via root; no manual adb push is needed once assets are present.
