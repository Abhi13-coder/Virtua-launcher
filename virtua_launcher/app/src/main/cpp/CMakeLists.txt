cmake_minimum_required(VERSION 3.22.1)
project(virtua_core)

add_library(virtua_core SHARED virtua_core.cpp)

find_library(log-lib log)
target_link_libraries(virtua_core ${log-lib})

# Streamed feature .so files are loaded at runtime via dlopen from Java FeatureRegistry paths.
# Bundle only the core bridge in the APK; CompactFlow / FSRCNN / depth weights ship via update_manifest.
