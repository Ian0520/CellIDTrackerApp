cmake_minimum_required(VERSION 3.24)
project(SpoofCall VERSION 1.0 LANGUAGES CXX)

include(cmake/CPM.cmake)

CPMAddPackage(GITHUB_REPOSITORY abdes/cryptopp-cmake VERSION 8.8.0 GIT_SHALLOW TRUE OPTIONS "CRYPTOPP_BUILD_TESTING OFF")
CPMAddPackage(NAME mbedtls GITHUB_REPOSITORY "ARMmbed/mbedtls" GIT_TAG "v3.1.0" OPTIONS "ENABLE_PROGRAMS OFF" "ENABLE_TESTING OFF")
set(CURL_COMMON_OPTIONS
  "CMAKE_USE_LIBSSH2 OFF" "CURL_USE_MBEDTLS ON" "CMAKE_USE_OPENSSL OFF"
  "BUILD_CURL_EXE OFF" "BUILD_SHARED_LIBS OFF" "BUILD_TESTING OFF"
  "HTTP_ONLY ON" "CURL_DISABLE_LDAP ON" "CURL_DISABLE_RTSP ON"
  "CURL_DISABLE_DICT ON" "CURL_DISABLE_TELNET ON" "CURL_DISABLE_TFTP ON")
CPMAddPackage(NAME curl GITHUB_REPOSITORY "curl/curl" GIT_TAG "curl-7_82_0" OPTIONS ${CURL_COMMON_OPTIONS})
CPMAddPackage("gh:nlohmann/json@3.11.3")

file(GLOB_RECURSE headers CONFIGURE_DEPENDS
     "${CMAKE_CURRENT_SOURCE_DIR}/include/*.h" "${CMAKE_CURRENT_SOURCE_DIR}/include/**/*.h")
file(GLOB_RECURSE sources CONFIGURE_DEPENDS
     "${CMAKE_CURRENT_SOURCE_DIR}/source/*.c"  "${CMAKE_CURRENT_SOURCE_DIR}/source/*.cc"
     "${CMAKE_CURRENT_SOURCE_DIR}/source/*.cpp")

add_library(${PROJECT_NAME} SHARED ${headers} ${sources})
set_target_properties(${PROJECT_NAME} PROPERTIES CXX_STANDARD 20)

target_include_directories(${PROJECT_NAME} PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_definitions(${PROJECT_NAME} PUBLIC SPOOF_ANDROID=1)

target_link_libraries(${PROJECT_NAME} PUBLIC
  cryptopp::cryptopp CURL::libcurl mbedtls mbedcrypto mbedx509 nlohmann_json::nlohmann_json
)

find_library(log-lib log)
if(log-lib)
  target_link_libraries(${PROJECT_NAME} PUBLIC ${log-lib})
endif()
