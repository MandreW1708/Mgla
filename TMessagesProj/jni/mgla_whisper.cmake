# mglawhisper: whisper.cpp (CPU) + JNI for local voice transcription.
# Built as a separate SHARED library so ggml symbols do not collide with tmessages.

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)

set(MGLA_WHISPER_SOURCES
        mgla_whisper_jni.c
        ${WHISPER_ROOT}/src/whisper.cpp
        ${WHISPER_ROOT}/ggml/src/ggml.c
        ${WHISPER_ROOT}/ggml/src/ggml-alloc.c
        ${WHISPER_ROOT}/ggml/src/ggml-backend.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-backend-reg.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-quants.c
        ${WHISPER_ROOT}/ggml/src/ggml-threading.cpp
        ${WHISPER_ROOT}/ggml/src/gguf.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu.c
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu-quants.c
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/unary-ops.cpp
        ${WHISPER_ROOT}/ggml/src/ggml-cpu/binary-ops.cpp
        # Opus/Ogg decode for Telegram voice messages (same sources as tmessages)
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opusfile/src/info.c
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opusfile/src/internal.c
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opusfile/src/opusfile.c
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opusfile/src/stream.c
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/ogg/src/bitwise.c
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/ogg/src/framing.c
)

add_library(mglawhisper SHARED ${MGLA_WHISPER_SOURCES})

target_compile_features(mglawhisper PRIVATE c_std_11 cxx_std_17)
target_compile_definitions(mglawhisper PRIVATE
        GGML_USE_CPU
        OPUS_BUILD
        FIXED_POINT
        USE_ALLOCA
        # Same Telegram hack as tmessages: os_types.h skips missing ogg/config_types.h
        __EMX__
)
target_compile_options(mglawhisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(mglawhisper PRIVATE GGML_USE_CPU_AARCH64)
    target_compile_options(mglawhisper PRIVATE -march=armv8.2-a+fp16)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_definitions(mglawhisper PRIVATE GGML_USE_CPU_AARCH64)
    target_compile_options(mglawhisper PRIVATE -mfpu=neon-vfpv4)
endif()

add_library(mgla_whisper_opus STATIC IMPORTED)
set_target_properties(mgla_whisper_opus PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuild/lib/${ANDROID_ABI}/libopus.a)

target_include_directories(mglawhisper PRIVATE
        ${WHISPER_ROOT}/include
        ${WHISPER_ROOT}/src
        ${WHISPER_ROOT}/ggml/include
        ${WHISPER_ROOT}/ggml/src
        ${WHISPER_ROOT}/ggml/src/ggml-cpu
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opus/include
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/opusfile/include
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xiph/ogg/include
)

target_link_options(mglawhisper PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,--gc-sections"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/mgla_whisper_exports.map"
)
target_link_libraries(mglawhisper log android atomic mgla_whisper_opus)
