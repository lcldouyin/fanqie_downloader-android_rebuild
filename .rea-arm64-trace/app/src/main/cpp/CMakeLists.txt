cmake_minimum_required(VERSION 3.22.1)
project(arm64traceprobe LANGUAGES CXX)

add_library(traceprobe SHARED trace.cpp)
target_compile_options(traceprobe PRIVATE -Wall -Wextra -fno-omit-frame-pointer)
target_link_libraries(traceprobe PRIVATE android log dl)