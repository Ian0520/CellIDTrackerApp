cmake_minimum_required(VERSION 3.24)

# ---- Project ----
project(
  SpoofCall
  VERSION 1.0
  LANGUAGES CXX
)

# ---- Include guards ----
if(PROJECT_SOURCE_DIR STREQUAL PROJECT_BINARY_DIR)
  message(FATAL_ERROR "In-source builds not allowed. Please build in a separate directory.")
endif()

# ---- Dependencies via CPM ----
include(cmake/CPM.cmake)

# Crypto++ (header+static lib)
CPMAddPackage(
  GITHUB_REPOSITORY abdes/cryptopp-cmake
  VERSION 8.8.0
  GIT_SHALLOW TRUE
  OPTIONS "CRYPTOPP_BUILD_TESTING OFF"
)

# mbedTLS（禁用內建測試/範例）
CPMAddPackage(
  NAME mbedtls
  GITHUB_REPOSITORY "ARMmbed/mbedtls"
  GIT_TAG "v3.1.0"
  OPTIONS
    "ENABLE_PROGRAMS OFF"
    "ENABLE_TESTING OFF"
)

# curl（Android 走 mbedTLS、HTTP-only、關閉 EXE/測試）
# 注意：這些選項同時也適用於桌機，若桌機要完整協定，移到 else() 分支另行開啟
set(CURL_COMMON_OPTIONS
  "CMAKE_USE_LIBSSH2 OFF"
  "CURL_USE_MBEDTLS ON"
  "CMAKE_USE_OPENSSL OFF"
  "BUILD_CURL_EXE OFF"
  "BUILD_SHARED_LIBS OFF"
  "BUILD_TESTING OFF"
  "HTTP_ONLY ON"               # 精簡協定
  "CURL_DISABLE_LDAP ON"
  "CURL_DISABLE_RTSP ON"
  "CURL_DISABLE_DICT ON"
  "CURL_DISABLE_TELNET ON"
  "CURL_DISABLE_TFTP ON"
)
CPMAddPackage(
  NAME curl
  GITHUB_REPOSITORY "curl/curl"
  GIT_TAG "curl-7_82_0"
  OPTIONS ${CURL_COMMON_OPTIONS}
)

CPMAddPackage("gh:nlohmann/json@3.11.3")

# ---- Sources ----
file(GLOB_RECURSE headers CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/include/*.h")
file(GLOB_RECURSE sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/source/*.cpp")

# ---- Target (Android: SHARED; Others: 你可以選 SHARED/STATIC 任一) ----
if(ANDROID)
  add_library(${PROJECT_NAME} SHARED ${headers} ${sources})
else()
  add_library(${PROJECT_NAME} ${headers} ${sources})
endif()

set_target_properties(${PROJECT_NAME} PROPERTIES CXX_STANDARD 20)

# MSVC 嚴格模式（保留）
target_compile_options(${PROJECT_NAME} PUBLIC "$<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/permissive->")

# ---- Include dirs ----
target_include_directories(
  ${PROJECT_NAME}
  PUBLIC
    $<BUILD_INTERFACE:${PROJECT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include/${PROJECT_NAME}-${PROJECT_VERSION}>
)

# ---- Compile defs（平台差異巨集）----
if(ANDROID)
  target_compile_definitions(${PROJECT_NAME} PUBLIC SPOOF_ANDROID=1)
else()
  target_compile_definitions(${PROJECT_NAME} PUBLIC SPOOF_DESKTOP=1)
endif()

# ---- Link deps ----
target_link_libraries(${PROJECT_NAME} PUBLIC
  cryptopp::cryptopp
  CURL::libcurl
  mbedtls
  mbedcrypto
  mbedx509
  nlohmann_json::nlohmann_json
)

# Android 連結系統 log（可用 __android_log_print）
if(ANDROID)
  find_library(log-lib log)
  if(log-lib)
    target_link_libraries(${PROJECT_NAME} PUBLIC ${log-lib})
  endif()
endif()
