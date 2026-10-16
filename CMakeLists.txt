cmake_minimum_required(VERSION 3.22)
project(quickjs-wrapper VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(QUICKJS_ENABLE_THIN_LTO "Enable ThinLTO with a Clang toolchain" OFF)

include(CheckCSourceCompiles)
if(CMAKE_C_COMPILER_ID STREQUAL "MSVC")
    set(CMAKE_REQUIRED_FLAGS "/experimental:c11atomics")
endif()
check_c_source_compiles("#include <stdatomic.h>
int main(void) { atomic_int value = 1; return atomic_load(&value) - 1; }"
    QUICKJS_HAS_C11_ATOMICS)
unset(CMAKE_REQUIRED_FLAGS)
if(NOT QUICKJS_HAS_C11_ATOMICS)
    message(FATAL_ERROR "QuickJS-NG requires a C11 compiler with stdatomic.h support")
endif()

set(QUICKJS_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/quickjs")
if(NOT EXISTS "${QUICKJS_SOURCE}/quickjs.c")
    message(FATAL_ERROR "Initialize the engine with: git submodule update --init vendor/quickjs")
endif()
add_library(quickjs-core STATIC
    "${QUICKJS_SOURCE}/dtoa.c"
    "${QUICKJS_SOURCE}/libregexp.c"
    "${QUICKJS_SOURCE}/libunicode.c"
    "${QUICKJS_SOURCE}/quickjs.c"
)
set_target_properties(quickjs-core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
)
target_include_directories(quickjs-core PUBLIC "${QUICKJS_SOURCE}")
target_compile_definitions(quickjs-core PRIVATE QUICKJS_NG_BUILD _GNU_SOURCE)

if(MSVC)
    if(CMAKE_C_COMPILER_ID STREQUAL "MSVC")
        target_compile_options(quickjs-core PRIVATE /experimental:c11atomics)
    endif()
    target_compile_options(quickjs-core PRIVATE /J)
    target_compile_definitions(quickjs-core PRIVATE
        WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0601
        _CRT_SECURE_NO_WARNINGS _CRT_NONSTDC_NO_DEPRECATE
    )
else()
    target_compile_options(quickjs-core PRIVATE -funsigned-char)
    find_package(Threads REQUIRED)
    target_link_libraries(quickjs-core PUBLIC Threads::Threads "${CMAKE_DL_LIBS}" m)
endif()

add_library(quickjs-wrapper SHARED native/quickjs_jni.cpp native/console.cpp)
set_target_properties(quickjs-wrapper PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_link_libraries(quickjs-wrapper PRIVATE quickjs-core)

if(QUICKJS_ENABLE_THIN_LTO)
    if(NOT CMAKE_C_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "ThinLTO requires a Clang toolchain")
    endif()
    target_compile_options(quickjs-core PRIVATE -flto=thin)
    target_compile_options(quickjs-wrapper PRIVATE -flto=thin)
    if(NOT MSVC)
        target_link_options(quickjs-wrapper PRIVATE -flto=thin)
    endif()
endif()

if(ANDROID)
    target_link_libraries(quickjs-wrapper PRIVATE log)
    if(ANDROID_NDK_MAJOR AND ANDROID_NDK_MAJOR LESS 26)
        message(FATAL_ERROR "QuickJS-NG requires Android NDK 26 or newer")
    endif()
    target_link_options(quickjs-wrapper PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,--no-undefined"
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(quickjs-wrapper PRIVATE ${JNI_INCLUDE_DIRS})
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/native/tests/CMakeLists.txt")
        include(native/tests/CMakeLists.txt)
    endif()
endif()
