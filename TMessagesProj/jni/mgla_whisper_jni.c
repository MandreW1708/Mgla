// JNI-обёртка над whisper.cpp для локальной расшифровки голосовых в Mgla.
// Отдельная shared-библиотека mglawhisper — не смешиваем символы ggml с tmessages.

#include <jni.h>
#include <android/log.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <opusfile.h>

#include "whisper.h"

#define TAG "MglaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int max_threads(void) {
    int n = 0;
#ifdef _SC_NPROCESSORS_ONLN
    n = (int) sysconf(_SC_NPROCESSORS_ONLN);
#endif
    if (n <= 0) {
        n = 2;
    }
    // Whisper scales well on big.LITTLE phones; keep a bit of headroom for UI.
    if (n > 6) {
        n = 6;
    }
    return n;
}

// Encoder context frames: 1500 ≈ 30s. Scale down for short voice notes.
static int audio_ctx_for_samples(int n_samples) {
    if (n_samples <= 0) {
        return 0;
    }
    // 50 frames/sec of encoder context; +64 pad so we don't clip the end.
    int ctx = (int) ((n_samples / (float) WHISPER_SAMPLE_RATE) * 50.0f) + 64;
    if (ctx < 128) {
        ctx = 128;
    }
    if (ctx > 1500) {
        ctx = 1500;
    }
    return ctx;
}

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MglaWhisperNative_nativeInit(JNIEnv *env, jclass clazz, jstring modelPath) {
    (void) clazz;
    if (modelPath == NULL) {
        return 0;
    }
    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);
    if (path == NULL) {
        return 0;
    }
    LOGI("init model %s", path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only build
    cparams.flash_attn = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, path);
    if (ctx == NULL) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_MglaWhisperNative_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle == 0) {
        return;
    }
    whisper_free((struct whisper_context *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaWhisperNative_nativeTranscribe(
        JNIEnv *env, jclass clazz, jlong handle, jfloatArray audio, jstring language, jint threads, jint maxTokens) {
    (void) clazz;
    if (handle == 0 || audio == NULL) {
        return NULL;
    }
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    jsize n = (*env)->GetArrayLength(env, audio);
    if (n <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (samples == NULL) {
        return NULL;
    }

    const char *lang = NULL;
    if (language != NULL) {
        lang = (*env)->GetStringUTFChars(env, language, NULL);
    }

    int n_threads = threads > 0 ? threads : max_threads();
    if (n_threads > 8) {
        n_threads = 8;
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.n_threads = n_threads;
    params.offset_ms = 0;
    params.no_context = true;
    // Voice notes / dictation: skip timestamp decoding and multi-pass temperature fallbacks.
    params.no_timestamps = true;
    params.single_segment = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.greedy.best_of = 1;
    // Short dictation rarely needs hundreds of tokens — caps decode time hard.
    params.max_tokens = maxTokens > 0 ? maxTokens : 224;
    if (params.max_tokens > 224) {
        params.max_tokens = 224;
    }
    params.audio_ctx = audio_ctx_for_samples((int) n);
    if (lang != NULL && lang[0] != '\0' && strcmp(lang, "auto") != 0) {
        params.language = lang;
        params.detect_language = false;
    } else {
        // Auto language detection is expensive — default to Russian for this client.
        params.language = "ru";
        params.detect_language = false;
    }

    LOGI("whisper_full samples=%d threads=%d lang=%s audio_ctx=%d", (int) n, n_threads,
         params.language != NULL ? params.language : "?", params.audio_ctx);
    int rc = whisper_full(ctx, params, samples, n);
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    if (lang != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang);
    }
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return NULL;
    }

    const int n_segments = whisper_full_n_segments(ctx);
    size_t cap = 256;
    size_t len = 0;
    char *out = (char *) malloc(cap);
    if (out == NULL) {
        return NULL;
    }
    out[0] = '\0';
    for (int i = 0; i < n_segments; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg == NULL) {
            continue;
        }
        size_t seg_len = strlen(seg);
        if (len + seg_len + 1 >= cap) {
            size_t ncap = (len + seg_len + 1) * 2;
            char *nbuf = (char *) realloc(out, ncap);
            if (nbuf == NULL) {
                free(out);
                return NULL;
            }
            out = nbuf;
            cap = ncap;
        }
        memcpy(out + len, seg, seg_len);
        len += seg_len;
        out[len] = '\0';
    }
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_MglaWhisperNative_nativeIsAvailable(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return JNI_TRUE;
}

// Decode Ogg/Opus (Telegram voice) to mono float PCM @ 16 kHz for whisper.
JNIEXPORT jfloatArray JNICALL
Java_org_telegram_messenger_MglaWhisperNative_nativeDecodeOpusToPcm16k(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (path == NULL) {
        return NULL;
    }
    const char *path_chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_chars == NULL) {
        return NULL;
    }
    int error = 0;
    OggOpusFile *of = op_open_file(path_chars, &error);
    (*env)->ReleaseStringUTFChars(env, path, path_chars);
    if (of == NULL || error != 0) {
        LOGE("op_open_file failed: %d", error);
        return NULL;
    }

    const int src_rate = 48000;
    const int dst_rate = 16000;
    ogg_int64_t total = op_pcm_total(of, -1);
    if (total < 0) {
        total = 0;
    }
    // op_read returns interleaved samples for all channels; Telegram voice is mono.
    size_t cap = (size_t) ((total > 0 ? total : src_rate * 60) + src_rate);
    float *pcm48 = (float *) malloc(cap * sizeof(float));
    if (pcm48 == NULL) {
        op_free(of);
        return NULL;
    }
    size_t filled = 0;
    opus_int16 buf[5760];
    while (1) {
        int n = op_read(of, buf, 5760, NULL);
        if (n < 0) {
            LOGE("op_read failed: %d", n);
            free(pcm48);
            op_free(of);
            return NULL;
        }
        if (n == 0) {
            break;
        }
        if (filled + (size_t) n > cap) {
            size_t ncap = (filled + (size_t) n) * 2;
            float *np = (float *) realloc(pcm48, ncap * sizeof(float));
            if (np == NULL) {
                free(pcm48);
                op_free(of);
                return NULL;
            }
            pcm48 = np;
            cap = ncap;
        }
        for (int i = 0; i < n; i++) {
            pcm48[filled++] = buf[i] / 32768.0f;
        }
    }
    op_free(of);

    size_t dst_len = (size_t) ((double) filled * dst_rate / src_rate);
    if (dst_len == 0) {
        free(pcm48);
        return (*env)->NewFloatArray(env, 0);
    }
    jfloatArray out = (*env)->NewFloatArray(env, (jsize) dst_len);
    if (out == NULL) {
        free(pcm48);
        return NULL;
    }
    jfloat *dst = (*env)->GetFloatArrayElements(env, out, NULL);
    if (dst == NULL) {
        free(pcm48);
        return NULL;
    }
    double ratio = (double) src_rate / dst_rate;
    for (size_t i = 0; i < dst_len; i++) {
        double src_pos = i * ratio;
        size_t i0 = (size_t) src_pos;
        size_t i1 = i0 + 1 < filled ? i0 + 1 : filled - 1;
        double t = src_pos - (double) i0;
        dst[i] = (jfloat) (pcm48[i0] * (1.0 - t) + pcm48[i1] * t);
    }
    (*env)->ReleaseFloatArrayElements(env, out, dst, 0);
    free(pcm48);
    return out;
}
