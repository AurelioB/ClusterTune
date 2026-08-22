cmake_minimum_required(VERSION 3.22.1)
project("spake2")

add_library(spake2 SHARED spake2.cpp)

find_library(log-lib log)
find_package(boringssl REQUIRED CONFIG)

target_link_libraries(spake2 ${log-lib} boringssl::crypto_static)