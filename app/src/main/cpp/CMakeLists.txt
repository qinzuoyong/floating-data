# 移植自 Shizuku (https://github.com/RikkaApps/Shizuku) manager/src/main/jni/CMakeLists.txt
# Copyright (C) 2021 RikkaApps
# Licensed under the Apache License, Version 2.0
#
# 仅保留 adb 配对库目标(libadb.so);boringssl/libcxx 经 prefab 提供

cmake_minimum_required(VERSION 3.22.1)

project("batteryfloat_adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message("Building Release...")

    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    message("Building Debug...")
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(adb SHARED
        adb_pairing.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)

# 内置特权服务:可执行目标。目标名 libbfd.so 使产物落在 jniLibs 并保持可执行位
# (Shizuku 的 libshizuku.so 同款做法);纯 C,不链 boringssl
add_executable(libbfd.so
        bfd_server.c)
set_target_properties(libbfd.so PROPERTIES PREFIX "")
