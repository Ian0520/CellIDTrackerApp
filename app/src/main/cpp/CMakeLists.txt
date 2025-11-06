cmake_minimum_required(VERSION 3.22)

project(native_port_project LANGUAGES C CXX)

# ---- Global C++ settings ----
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- Core (your simplified core_api) as an object library ----
add_library(core_lib OBJECT
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/core/src/core_api.cpp
)
target_include_directories(core_lib PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/core/include
)
target_compile_features(core_lib PUBLIC cxx_std_20)

# ---- Wifi-calling original sources + adapter ----
# Collect sources
file(GLOB WIFI_CALLING_SRC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/source/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/adapter/*.cpp
)

# Exclude modules that require external libraries (libcurl / Crypto++)
list(REMOVE_ITEM WIFI_CALLING_SRC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/source/application.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/source/encoder.cpp
)

# ---- Native shared library ----
add_library(native_port SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp
    ${WIFI_CALLING_SRC}
    $<TARGET_OBJECTS:core_lib>
)

# Include order matters: put adapter first so its stub headers override originals
target_include_directories(native_port PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/adapter
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wificalling/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/core/include
)

# Compile features / definitions
target_compile_features(native_port PRIVATE cxx_std_20)
target_compile_definitions(native_port PRIVATE
    ANDROID_BUILD
    ANDROID_NO_CRYPTO=1
)

# ---- NDK libraries ----
find_library(log-lib log)

target_link_libraries(native_port
    ${log-lib}
)
