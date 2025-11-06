cmake_minimum_required(VERSION 3.18)
project(core_lib LANGUAGES CXX)

file(GLOB_RECURSE CORE_HEADERS CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/include/*.h")
file(GLOB_RECURSE CORE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")

add_library(core_lib STATIC ${CORE_HEADERS} ${CORE_SOURCES})
target_include_directories(core_lib PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
set_target_properties(core_lib PROPERTIES CXX_STANDARD 17)
