cmake_minimum_required(VERSION 3.16)
project(ProbeEventContractTests LANGUAGES CXX)

enable_testing()

add_executable(packet_capture_quality_test packet_capture_quality_test.cpp)
target_include_directories(packet_capture_quality_test PRIVATE ../include)
target_compile_features(packet_capture_quality_test PRIVATE cxx_std_20)
add_test(NAME packet_capture_quality_test COMMAND packet_capture_quality_test)

add_executable(
  probe_event_contract_test
  probe_event_contract_test.cpp
  ../source/probe_event.cpp
)
target_include_directories(probe_event_contract_test PRIVATE ../include)
target_compile_features(probe_event_contract_test PRIVATE cxx_std_20)

add_test(NAME probe_event_contract_test COMMAND probe_event_contract_test)

add_executable(
  sip_response_parser_test
  sip_response_parser_test.cpp
  ../source/sip_response.cpp
)
target_include_directories(sip_response_parser_test PRIVATE ../include)
target_compile_features(sip_response_parser_test PRIVATE cxx_std_20)
add_test(NAME sip_response_parser_test COMMAND sip_response_parser_test)

add_executable(
  probe_state_machine_test
  probe_state_machine_test.cpp
  ../source/probe_state_machine.cpp
)
target_include_directories(probe_state_machine_test PRIVATE ../include)
target_compile_features(probe_state_machine_test PRIVATE cxx_std_20)
add_test(NAME probe_state_machine_test COMMAND probe_state_machine_test)

add_executable(
  probe_loop_policy_test
  probe_loop_policy_test.cpp
  ../source/probe_loop_policy.cpp
)
target_include_directories(probe_loop_policy_test PRIVATE ../include)
target_compile_features(probe_loop_policy_test PRIVATE cxx_std_20)
add_test(NAME probe_loop_policy_test COMMAND probe_loop_policy_test)

add_executable(
  native_run_mode_test
  native_run_mode_test.cpp
  ../source/native_run_mode.cpp
)
target_include_directories(native_run_mode_test PRIVATE ../include)
target_compile_features(native_run_mode_test PRIVATE cxx_std_20)
add_test(NAME native_run_mode_test COMMAND native_run_mode_test)

add_executable(
  sip_capture_test
  sip_capture_test.cpp
  ../source/sip_capture.cpp
  ../source/sip_response.cpp
)
target_include_directories(sip_capture_test PRIVATE ../include)
target_compile_features(sip_capture_test PRIVATE cxx_std_20)
find_package(Threads REQUIRED)
target_link_libraries(sip_capture_test PRIVATE Threads::Threads)
add_test(NAME sip_capture_test COMMAND sip_capture_test)

# Offline fidelity checks against locally held captures; no network or phones.
add_executable(
  sip_capture_replay
  sip_capture_replay.cpp
  ../source/sip_capture.cpp
  ../source/sip_response.cpp
)
target_include_directories(sip_capture_replay PRIVATE ../include)
target_compile_features(sip_capture_replay PRIVATE cxx_std_20)
target_link_libraries(sip_capture_replay PRIVATE Threads::Threads)
