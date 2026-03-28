cmake_minimum_required(VERSION 3.22.1)
project("modelviewer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    tinyobjloader
    GIT_REPOSITORY https://github.com/tinyobjloader/tinyobjloader.git
    GIT_TAG        v2.0.0rc13
    GIT_SHALLOW    TRUE
)

FetchContent_Declare(
    tinygltf
    GIT_REPOSITORY https://github.com/syoyo/tinygltf.git
    GIT_TAG        v2.8.21
    GIT_SHALLOW    TRUE
)

FetchContent_MakeAvailable(tinyobjloader tinygltf)

add_library(
    modelviewer
    SHARED
    jni_bridge.cpp
    renderer.cpp
    shader_utils.cpp
    model_loader.cpp
    mesh_separator.cpp
)

target_include_directories(modelviewer PRIVATE
    ${tinyobjloader_SOURCE_DIR}
    ${tinygltf_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib     log)
find_library(android-lib android)
find_library(gles3-lib   GLESv3)
find_library(egl-lib     EGL)

target_link_libraries(modelviewer
    ${log-lib}
    ${android-lib}
    ${gles3-lib}
    ${egl-lib}
)

# Release-grade optimisation — critical for radix sort speed on mobile
target_compile_options(modelviewer PRIVATE
    -O3                     # full optimisation (was -O2)
    -ffast-math             # allows SIMD vectorisation of float ops
    -fvisibility=hidden
    -march=armv8-a          # target ARMv8-A baseline (arm64-v8a)
    -funroll-loops          # radix sort inner loops benefit from this
)
