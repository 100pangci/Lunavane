cmake_minimum_required(VERSION 3.22.1)
project(zerotiernova LANGUAGES C CXX)

set(ZT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/zerotier)
file(GLOB ZT_NODE_SOURCES CONFIGURE_DEPENDS ${ZT_ROOT}/node/*.cpp)

add_library(zerotiernova SHARED
    NativeBridge.cpp
    ${ZT_NODE_SOURCES}
    ${ZT_ROOT}/osdep/OSUtils.cpp
)

target_compile_features(zerotiernova PRIVATE cxx_std_17)
target_compile_options(zerotiernova PRIVATE -O2 -fPIC -DNDEBUG)
target_compile_definitions(zerotiernova PRIVATE OMIT_JSON_SUPPORT)
target_include_directories(zerotiernova PUBLIC
    ${ZT_ROOT}
    ${ZT_ROOT}/include
)
target_include_directories(zerotiernova SYSTEM PRIVATE
    ${ZT_ROOT}/ext
    ${ZT_ROOT}/ext/prometheus-cpp-lite-1.0/core/include
    ${ZT_ROOT}/ext/prometheus-cpp-lite-1.0/simpleapi/include
)
target_link_options(zerotiernova PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384)
target_link_libraries(zerotiernova PRIVATE log)
