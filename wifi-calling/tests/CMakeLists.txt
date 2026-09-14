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
