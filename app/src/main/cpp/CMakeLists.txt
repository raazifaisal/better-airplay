cmake_minimum_required(VERSION 3.22)
project(better_airplay_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
get_filename_component(TP ${TP} ABSOLUTE)

# Strip local paths from binaries.
add_compile_options(-ffile-prefix-map=${TP}=third_party)

# ---------------------------------------------------------------------------
# BoringSSL (crypto + decrepit for BIO_f_base64). Built from source with CMake;
# no Perl/Go/make needed. Statically linked, symbols hidden.
# ---------------------------------------------------------------------------
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/boringssl ${CMAKE_BINARY_DIR}/boringssl EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# libplist (static, C only)
# ---------------------------------------------------------------------------
file(GLOB PLIST_SRCS ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRCS})
target_include_directories(plist
    PUBLIC  ${TP}/libplist/include
    PRIVATE ${TP}/libplist/src ${TP}/libplist/libcnary/include)
target_compile_definitions(plist PRIVATE
    HAVE_STRNDUP HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TIMEGM HAVE_MEMMEM HAVE_STRPTIME
    HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE PACKAGE_VERSION="2.8.1" LIBPLIST_STATIC)
target_compile_options(plist PRIVATE -w)

# ---------------------------------------------------------------------------
# UxPlay AirPlay core (lib/, playfair, llhttp) + our Android dnssd backend
# ---------------------------------------------------------------------------
set(UX ${TP}/UxPlay/lib)
file(GLOB UX_SRCS ${UX}/*.c ${UX}/playfair/*.c ${UX}/llhttp/*.c)
add_library(uxplay_core STATIC ${UX_SRCS} android_dnssd.c)
target_include_directories(uxplay_core PUBLIC ${UX} ${UX}/playfair ${UX}/llhttp ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(uxplay_core PUBLIC
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _LINUX _REENTRANT
    NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(uxplay_core PRIVATE -Wno-unused-variable -Wno-unused-function -Wno-deprecated-declarations)
target_link_libraries(uxplay_core PUBLIC plist crypto decrepit)

# ---------------------------------------------------------------------------
# Apple ALAC decoder (Apache-2.0), decode side only
# ---------------------------------------------------------------------------
set(ALAC ${TP}/alac/codec)
# ALACDecoder.cpp, ALACBitUtilities.c and ag_dec.c come from alac_hardened/: bounds-checked copies
# (see the HARDENED notes in each) so untrusted packets can't read or write out of bounds.
set(ALAC_HARDENED ${CMAKE_CURRENT_SOURCE_DIR}/alac_hardened CACHE PATH "ALAC decoder sources; point at upstream only to benchmark")
add_library(alac STATIC
    ${ALAC_HARDENED}/ALACDecoder.cpp ${ALAC_HARDENED}/ALACBitUtilities.c ${ALAC_HARDENED}/ag_dec.c
    ${ALAC}/EndianPortable.c ${ALAC}/dp_dec.c ${ALAC}/matrix_dec.c)
target_include_directories(alac PUBLIC ${ALAC})
# Apple's code only auto-detects little-endian on x86; ARM Android is little-endian too.
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -w)

# ---------------------------------------------------------------------------
# JNI library
# ---------------------------------------------------------------------------
add_library(betterairplay SHARED native_bridge.cpp media_pipeline.cpp)
target_link_libraries(betterairplay PRIVATE uxplay_core alac mediandk aaudio log android)

# ---------------------------------------------------------------------------
# On-device native tests (not part of the APK). Run with tools/device-tests.sh.
# ---------------------------------------------------------------------------
option(BAP_DEVICE_TESTS "Build the on-device native test executable" OFF)
if (BAP_DEVICE_TESTS)
    add_executable(bap_device_tests
        ${CMAKE_CURRENT_SOURCE_DIR}/../../deviceTest/cpp/device_tests.cpp
        media_pipeline.cpp
        ${ALAC}/ALACEncoder.cpp ${ALAC}/ag_enc.c ${ALAC}/dp_enc.c ${ALAC}/matrix_enc.c)
    target_include_directories(bap_device_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(bap_device_tests PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
    target_link_libraries(bap_device_tests PRIVATE alac mediandk aaudio log android)
endif()
target_link_options(betterairplay PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
