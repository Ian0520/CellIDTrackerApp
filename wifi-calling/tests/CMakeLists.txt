cmake_minimum_required(VERSION 3.16)
project(ProbeEventContractTests LANGUAGES CXX)

enable_testing()

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
