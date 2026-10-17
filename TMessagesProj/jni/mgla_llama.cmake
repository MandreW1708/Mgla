# CPU-only llama.cpp runtime for local AI chats.  It is a separate .so so its
# ggml symbols do not collide with Telegram's native library or Whisper.
set(MGLA_LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp/examples/talk-llama)
set(MGLA_GGML_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp/ggml)

set(MGLA_LLAMA_SOURCES
        mgla_llama_jni.cpp
        ${MGLA_LLAMA_ROOT}/llama.cpp
        ${MGLA_LLAMA_ROOT}/llama-adapter.cpp
        ${MGLA_LLAMA_ROOT}/llama-arch.cpp
        ${MGLA_LLAMA_ROOT}/llama-batch.cpp
        ${MGLA_LLAMA_ROOT}/llama-chat.cpp
        ${MGLA_LLAMA_ROOT}/llama-context.cpp
        ${MGLA_LLAMA_ROOT}/llama-cparams.cpp
        ${MGLA_LLAMA_ROOT}/llama-grammar.cpp
        ${MGLA_LLAMA_ROOT}/llama-hparams.cpp
        ${MGLA_LLAMA_ROOT}/llama-impl.cpp
        ${MGLA_LLAMA_ROOT}/llama-kv-cache.cpp
        ${MGLA_LLAMA_ROOT}/llama-mmap.cpp
        ${MGLA_LLAMA_ROOT}/llama-model-loader.cpp
        ${MGLA_LLAMA_ROOT}/llama-model.cpp
        ${MGLA_LLAMA_ROOT}/llama-quant.cpp
        ${MGLA_LLAMA_ROOT}/llama-sampling.cpp
        ${MGLA_LLAMA_ROOT}/llama-vocab.cpp
        ${MGLA_LLAMA_ROOT}/unicode.cpp
        ${MGLA_LLAMA_ROOT}/unicode-data.cpp
        ${MGLA_GGML_ROOT}/src/ggml.c
        ${MGLA_GGML_ROOT}/src/ggml-alloc.c
        ${MGLA_GGML_ROOT}/src/ggml-backend.cpp
        ${MGLA_GGML_ROOT}/src/ggml-backend-reg.cpp
        ${MGLA_GGML_ROOT}/src/ggml-quants.c
        ${MGLA_GGML_ROOT}/src/ggml-threading.cpp
        ${MGLA_GGML_ROOT}/src/gguf.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu.c
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu-aarch64.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu-hbm.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu-quants.c
        ${MGLA_GGML_ROOT}/src/ggml-cpu/ggml-cpu-traits.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/unary-ops.cpp
        ${MGLA_GGML_ROOT}/src/ggml-cpu/binary-ops.cpp)

add_library(mglallama SHARED ${MGLA_LLAMA_SOURCES})
target_compile_features(mglallama PRIVATE c_std_11 cxx_std_17)
target_compile_definitions(mglallama PRIVATE
        GGML_USE_CPU
        _POSIX_C_SOURCE=200809L
        # bionic exposes madvise constants but not the POSIX aliases used by this llama.cpp snapshot.
        posix_madvise=madvise
        POSIX_MADV_WILLNEED=MADV_WILLNEED
        POSIX_MADV_RANDOM=MADV_RANDOM)
target_compile_options(mglallama PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(mglallama PRIVATE GGML_USE_CPU_AARCH64)
    target_compile_options(mglallama PRIVATE -march=armv8.2-a+fp16+dotprod)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(mglallama PRIVATE -mfpu=neon-vfpv4)
endif()
target_include_directories(mglallama PRIVATE
        ${MGLA_LLAMA_ROOT}
        ${MGLA_GGML_ROOT}/include
        ${MGLA_GGML_ROOT}/src
        ${MGLA_GGML_ROOT}/src/ggml-cpu)
target_link_options(mglallama PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--gc-sections" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/mgla_whisper_exports.map")
target_link_libraries(mglallama log android atomic)
