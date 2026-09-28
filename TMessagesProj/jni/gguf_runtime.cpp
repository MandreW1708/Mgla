#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <algorithm>
#include <cstdio>
#include <unistd.h>

#include <android/log.h>

#include "llama.h"
#include "ggml-backend.h"

#define MGLA_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MglaGGUF", __VA_ARGS__)
#define MGLA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MglaGGUF", __VA_ARGS__)

namespace {

constexpr int32_t N_CTX = 2048;
// Держим запас под служебные токены шаблона при обрезке длинного промпта.
constexpr int32_t TOKEN_SAFETY_MARGIN = 16;
// Частота отчётов о прогрессе — каждые N токенов (Java тоже душит частоту).
constexpr int32_t PROGRESS_EVERY_TOKENS = 3;

std::once_flag backend_once;

// Кэшированные ссылки для отчёта о прогрессе в Java (класс живёт всю сессию).
jclass g_runtime_class = nullptr;
jmethodID g_progress_method = nullptr;

// RSS процесса в МБ — для проверки, что модель действительно выгрузилась.
long rss_mb() {
    FILE * f = fopen("/proc/self/statm", "r");
    if (f == nullptr) {
        return -1;
    }
    long pages = -1;
    if (fscanf(f, "%*s %ld", &pages) != 1) {
        pages = -1;
    }
    fclose(f);
    if (pages < 0) {
        return -1;
    }
    return pages * sysconf(_SC_PAGESIZE) / (1024 * 1024);
}

void ensure_backend() {
    std::call_once(backend_once, []() {
        llama_backend_init();
        ggml_backend_load_all();
    });
}

// text != nullptr — стриминг накопленного текста генерации.
void report_progress(JNIEnv * env, jclass clazz, int generated, int max_tokens,
                     const std::string * text) {
    if (g_progress_method == nullptr) {
        g_runtime_class = (jclass) env->NewGlobalRef(clazz);
        g_progress_method = env->GetStaticMethodID(clazz, "onNativeProgress", "(IILjava/lang/String;)V");
        if (g_progress_method == nullptr) {
            return;
        }
    }
    jstring jtext = nullptr;
    if (text != nullptr) {
        jtext = env->NewStringUTF(text->c_str());
    }
    env->CallStaticVoidMethod(g_runtime_class, g_progress_method, generated, max_tokens, jtext);
    if (jtext != nullptr) {
        env->DeleteLocalRef(jtext);
    }
}

std::string apply_template(const llama_model * model, const std::string & prompt) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        return prompt;
    }
    llama_chat_message message = { "user", prompt.c_str() };
    int32_t required = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (required <= 0) {
        return prompt;
    }
    std::vector<char> buffer(required + 1);
    if (llama_chat_apply_template(tmpl, &message, 1, true, buffer.data(), buffer.size()) <= 0) {
        return prompt;
    }
    return std::string(buffer.data(), required);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaGgufRuntime_nativeGenerateTopics(
        JNIEnv * env, jclass clazz, jstring model_path, jstring prompt, jint max_tokens) {
    if (model_path == nullptr || prompt == nullptr || max_tokens <= 0) {
        return nullptr;
    }

    const char * model_chars = env->GetStringUTFChars(model_path, nullptr);
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (model_chars == nullptr || prompt_chars == nullptr) {
        if (model_chars != nullptr) env->ReleaseStringUTFChars(model_path, model_chars);
        if (prompt_chars != nullptr) env->ReleaseStringUTFChars(prompt, prompt_chars);
        return nullptr;
    }

    std::string path(model_chars);
    std::string user_prompt(prompt_chars);
    env->ReleaseStringUTFChars(model_path, model_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    auto started = std::chrono::steady_clock::now();
    long rss_before = rss_mb();
    MGLA_LOGI("start model=%s prompt_chars=%zu max_tokens=%d rss=%ldMB",
              path.c_str(), user_prompt.size(), max_tokens, rss_before);

    ensure_backend();
    // generated < 0 — Java показывает неопределённый прогресс (загрузка весов).
    report_progress(env, clazz, -1, 0, nullptr);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        MGLA_LOGE("model load failed: %s", path.c_str());
        return nullptr;
    }

    // The model, context and sampler deliberately live only inside this call.
    // Every return below releases their RAM before control goes back to Java.
    std::string formatted_prompt = apply_template(model, user_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    int32_t prompt_count = -llama_tokenize(vocab, formatted_prompt.c_str(), formatted_prompt.size(), nullptr, 0, true, true);
    if (prompt_count <= 0) {
        MGLA_LOGE("tokenize failed (count=%d)", prompt_count);
        llama_model_free(model);
        return nullptr;
    }
    // Промпт не влезает в контекст — обрезаем хвост (самые старые сообщения:
    // в промпте сообщения идут от новых к старым), вместо отказа.
    if (prompt_count + max_tokens > N_CTX) {
        int32_t keep = N_CTX - max_tokens - TOKEN_SAFETY_MARGIN;
        if (keep < 64) {
            MGLA_LOGE("prompt too long and max_tokens too big (%d + %d > %d)",
                      prompt_count, max_tokens, N_CTX);
            llama_model_free(model);
            return nullptr;
        }
        MGLA_LOGI("prompt truncated: %d -> %d tokens", prompt_count, keep);
        prompt_count = keep;
    }
    std::vector<llama_token> prompt_tokens(prompt_count);
    if (llama_tokenize(vocab, formatted_prompt.c_str(), formatted_prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        MGLA_LOGE("tokenize pass 2 failed");
        llama_model_free(model);
        return nullptr;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = N_CTX;
    context_params.n_batch = std::min<int32_t>(prompt_count, N_CTX);
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    llama_context * context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        MGLA_LOGE("context init failed (n_ctx=%d)", N_CTX);
        llama_model_free(model);
        return nullptr;
    }
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    report_progress(env, clazz, 0, max_tokens, &output);
    int generated = 0;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    for (; generated < max_tokens; generated++) {
        if (llama_decode(context, batch) != 0) {
            MGLA_LOGE("decode failed at token %d", generated);
            break;
        }
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        char piece[256];
        int32_t length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (length > 0) {
            output.append(piece, length);
        }
        // Детальный прогресс + стриминг текста «как рассуждает модель».
        int done = generated + 1;
        if (done % PROGRESS_EVERY_TOKENS == 0 || done >= max_tokens) {
            report_progress(env, clazz, done, max_tokens, &output);
        }
        batch = llama_batch_get_one(&token, 1);
    }

    // Модель, контекст и сэмплер полностью выгружаются ещё до возврата в Java:
    // между запусками Chat DNA в памяти не остаётся ничего, кроме лёгкого backend.
    llama_sampler_free(sampler);
    llama_free(context);
    llama_model_free(model);

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
    MGLA_LOGI("freed: rss_before=%ldMB rss_after=%ldMB", rss_before, rss_mb());
    if (output.empty()) {
        MGLA_LOGE("empty output after %d tokens (%lld ms)", generated, (long long) elapsed);
        return nullptr;
    }
    MGLA_LOGI("done tokens=%d output_chars=%zu (%lld ms)", generated, output.size(), (long long) elapsed);
    return env->NewStringUTF(output.c_str());
}
