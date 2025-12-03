cmake_minimum_required(VERSION 3.24)

project(Standalone LANGUAGES CXX)

# --- Import tools ----

include(../cmake/tools.cmake)

# ---- Dependencies ----

include(../cmake/CPM.cmake)

CPMAddPackage(
  GITHUB_REPOSITORY jarro2783/cxxopts
  VERSION 3.1.1
  GIT_SHALLOW TRUE
  OPTIONS "CXXOPTS_BUILD_EXAMPLES NO" "CXXOPTS_BUILD_TESTS NO"
)

CPMAddPackage(NAME SpoofCall SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}/..)

# ---- Create standalone executables ----

add_executable(spoof ${CMAKE_CURRENT_SOURCE_DIR}/source/main.cpp)
set_target_properties(spoof PROPERTIES CXX_STANDARD 20 OUTPUT_NAME "spoof")
target_link_libraries(spoof SpoofCall cxxopts)
