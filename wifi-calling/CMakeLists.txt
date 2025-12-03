cmake_minimum_required(VERSION 3.24)

# ---- Project ----

# Note: update this to your new project's name and version
project(
  SpoofCall
  VERSION 1.0
  LANGUAGES CXX
)

# ---- Include guards ----

if(PROJECT_SOURCE_DIR STREQUAL PROJECT_BINARY_DIR)
  message(
    FATAL_ERROR
      "In-source builds not allowed. Please make a new directory (called a build directory) and run CMake from there."
  )
endif()

# ---- Add dependencies via CPM ----
# see https://github.com/TheLartians/CPM.cmake for more info

include(cmake/CPM.cmake)

CPMAddPackage(
  GITHUB_REPOSITORY abdes/cryptopp-cmake
  VERSION 8.8.0
  GIT_SHALLOW TRUE
  OPTIONS "CRYPTOPP_BUILD_TESTING OFF"
)

CPMAddPackage(
    NAME mbedtls
    GITHUB_REPOSITORY "ARMmbed/mbedtls"
    GIT_TAG "v3.1.0"
    OPTIONS
        "ENABLE_PROGRAMS OFF"
        "ENABLE_TESTING OFF"
)
set(MBEDTLS_INCLUDE_DIRS mbedtls)
set(MBEDTLS_LIBRARY mbedtls)
set(MBEDX509_LIBRARY mbedtls)
set(MBEDCRYPTO_LIBRARY mbedtls)

CPMAddPackage(
    NAME curl
    GITHUB_REPOSITORY "curl/curl"
    GIT_TAG "curl-7_82_0"
    OPTIONS
        "CMAKE_USE_LIBSSH2 OFF"
        "CURL_USE_MBEDTLS ON"
        "BUILD_CURL_EXE OFF"
        "BUILD_SHARED_LIBS OFF"
        "BUILD_TESTING OFF"
)

CPMAddPackage("gh:nlohmann/json@3.11.3") # see https://json.nlohmann.me/integration/package_managers/#cpmcmake

# ---- Add source files ----

# Note: globbing sources is considered bad practice as CMake's generators may not detect new files
# automatically. Keep that in mind when changing files, or explicitly mention them here.
file(GLOB_RECURSE headers CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/include/*.h")
file(GLOB_RECURSE sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/source/*.cpp")

# ---- Create library ----

# Note: for header-only libraries change all PUBLIC flags to INTERFACE and create an interface
# target: add_library(${PROJECT_NAME} INTERFACE)
add_library(${PROJECT_NAME} ${headers} ${sources})
set_target_properties(${PROJECT_NAME} PROPERTIES CXX_STANDARD 20)

# being a cross-platform target, we enforce standards conformance on MSVC
target_compile_options(${PROJECT_NAME} PUBLIC "$<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/permissive->")

# Link dependencies
target_link_libraries(${PROJECT_NAME} PUBLIC cryptopp::cryptopp)
target_link_libraries(${PROJECT_NAME} PUBLIC
    CURL::libcurl
    mbedtls
    mbedcrypto
    mbedx509
)
target_link_libraries(${PROJECT_NAME} PUBLIC nlohmann_json::nlohmann_json)

target_include_directories(
  ${PROJECT_NAME} PUBLIC $<BUILD_INTERFACE:${PROJECT_SOURCE_DIR}/include>
                         $<INSTALL_INTERFACE:include/${PROJECT_NAME}-${PROJECT_VERSION}>
)
