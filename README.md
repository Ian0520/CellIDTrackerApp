# CellIDTracker (Android Port)

[![Android](https://img.shields.io/badge/Platform-Android-green)](https://developer.android.com/)
[![NDK](https://img.shields.io/badge/NDK-C%2B%2B%20Native-blue)](https://developer.android.com/ndk)
[![Language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## Overview

**CellIDTracker** is an Android port of a Linux CLI project that performs  
Wi-Fi Calling and Cell ID analysis.  

It integrates a **Kotlin Jetpack Compose UI** with **C++ (NDK)** native modules,  
bridging between Android APIs and the original system-level logic.  

---

## Adapter Layer

The `adapter/` directory provides Android-safe replacements for    
Linux-specific functionality that cannot directly compile or execute on Android.  

| Type | Original Behavior | Android Adaptation |
|------|-------------------|--------------------|
| **Crypto++ / libcurl** | `#include <cryptopp/aes.h>`, `#include <curl/curl.h>` | Stubbed out or replaced with Kotlin equivalents (e.g. OkHttp) |
| **System Calls** | `system()`, `fork()`, `/proc`, `/dev` | No-op or replaced with Android API calls |
| **Networking** | `socket`, `poll()` | Preserved when NDK supports it; otherwise bridged through Kotlin |
| **File Paths** | `/var`, `/etc`, `/tmp` | Redirected to Android `Context.filesDir` |

This layer allows the original logic to **compile unmodified**,  
while ensuring the app remains functional and sandbox-safe on Android.

---

## Build Configuration

| Macro / Flag | Purpose |
|---------------|----------|
| `ANDROID_NO_CRYPTO` | Disables libcurl and Crypto++ dependencies; enables stub implementations |
| `CMAKE_CXX_STANDARD 20` | Enables modern C++ features (`std::span`, etc.) |
| `-fPIC` | Required for building shared libraries |
| `target_link_libraries(native_port android log)` | Enables NDK logging support |

---

## JNI Features

| Feature | UI Button | Status |
|----------|------------|--------|
| Echo (JNI) | `Echo` | Working |
| Run report | `Run report` | Working |
| Run original flow | `Run ORIGINAL flow` | Currently stubbed (awaiting libcurl/Crypto++) |

---

## Current Progress

- App launches and UI is functional  
- JNI bridge operational  
- Original C++ code compiles under `ANDROID_NO_CRYPTO`  
- `adapter/` stubs established (for `application`, `encoder`, etc.)  
- “Run ORIGINAL flow” not yet implemented (safe fallback in place)

---

## Development Roadmap

### Short-Term
- Add safe early return for `Run ORIGINAL flow` JNI entry  
- Improve UI error handling and logging  

### Mid-Term
- Implement JNI → Kotlin bridge for system APIs (Wi-Fi, HTTP, Telephony)  
- Replace `curl` with `OkHttp` via Kotlin-side integration  

### Long-Term
- Cross-compile **libcurl** + **Crypto++** for Android (arm64)  
- Re-enable full original native workflow and encryption features  

---

## Example Output

wifi.enable = true  
wifi.ssid = "TWM_WIFI"  
timestamp = 2025-11-06 16:23  
echo: JNI Bridge OK  


---

## Contribution Guidelines

1. Clone the repository:
   ```bash
   git clone https://github.com/<yourname>/CellIDTracker.git  
2. Open the project in Android Studio.  

3. Ensure NDK, CMake, and SDK Platform 34+ are installed.  

4. Build and run:

## License

This project is licensed under the MIT License
.
