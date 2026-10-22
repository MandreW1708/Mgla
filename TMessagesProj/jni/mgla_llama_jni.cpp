#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MglaLlama", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MglaLlama", __VA_ARGS__)

struct runtime {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 0;
    int n_batch = 64;
    int threads = 1;
    std::atomic<bool> abort{false};
    std::mutex lock;
};

static std::mutex g_err_lock;
static std::string g_last_error;

static void set_last_error(const std::string & msg) {
    std::lock_guard<std::mutex> guard(g_err_lock);
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

static void clear_last_error() {
    std::lock_guard<std::mutex> guard(g_err_lock);
    g_last_error.clear();
}

static void android_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) {
        return;
    }
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        prio = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        prio = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_DEBUG) {
        prio = ANDROID_LOG_DEBUG;
    }
    __android_log_print(prio, "MglaLlama", "%s", text);
}

static void ensure_backend() {
    static std::once_flag once;
    std::call_once(once, []() {
        llama_log_set(android_log_callback, nullptr);
        llama_backend_init();
        const size_t n = ggml_backend_dev_count();
        LOGI("backends ready, devices=%zu", (unsigned long) n);
        if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
            set_last_error("CPU backend не зарегистрирован");
        }
    });
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_bos) {
    int n = (int) text.size() + 8;
    std::vector<llama_token> tokens(n);
    n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_bos, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), (int32_t) tokens.size(), add_bos, true);
    }
    if (n < 0) {
        return {};
    }
    tokens.resize(n);
    return tokens;
}

static std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(32);
    int n = llama_token_to_piece(vocab, token, buffer.data(), (int32_t) buffer.size(), 0, false);
    if (n < 0) {
        buffer.resize(-n);
        n = llama_token_to_piece(vocab, token, buffer.data(), (int32_t) buffer.size(), 0, false);
    }
    return n > 0 ? std::string(buffer.data(), n) : std::string();
}

/** JNI NewStringUTF breaks on incomplete UTF-8 from token pieces — use String(byte[],UTF-8). */
static jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    if (s.empty()) {
        return env->NewStringUTF("");
    }
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    if (!bytes) {
        return nullptr;
    }
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = (jstring) env->NewObject(strClass, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return result;
}

static llama_model * load_model_file(const char * path) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = false;
    mp.use_mlock = false;
    mp.check_tensors = false;
    return llama_model_load_from_file(path, mp);
}

static bool decode_tokens(runtime * rt, llama_batch & batch, const std::vector<llama_token> & tokens, int pos0) {
    const int n = (int) tokens.size();
    // Small chunks: lower peak RAM, abort can interrupt mid-prompt, first token sooner.
    const int batch_cap = std::max(1, std::min(std::min(rt->n_batch, 64), rt->n_ctx));
    for (int offset = 0; offset < n; ) {
        if (rt->abort.load()) {
            set_last_error("генерация прервана");
            return false;
        }
        int chunk = std::min(batch_cap, n - offset);
        batch.n_tokens = chunk;
        for (int i = 0; i < chunk; i++) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = pos0 + offset + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (offset + i == n - 1) ? 1 : 0;
        }
        const int rc = llama_decode(rt->ctx, batch);
        if (rc != 0) {
            LOGE("llama_decode failed at pos=%d chunk=%d rc=%d", pos0 + offset, chunk, rc);
            return false;
        }
        offset += chunk;
    }
    return true;
}

struct sink_bridge {
    JNIEnv * env = nullptr;
    jobject sink = nullptr;
    jmethodID on_token = nullptr;
    jmethodID on_phase = nullptr;
};

static bool emit_phase(sink_bridge & s, const char * phase) {
    if (!s.env || !s.sink || !s.on_phase || !phase) {
        return true;
    }
    jstring jphase = s.env->NewStringUTF(phase);
    s.env->CallVoidMethod(s.sink, s.on_phase, jphase);
    s.env->DeleteLocalRef(jphase);
    if (s.env->ExceptionCheck()) {
        s.env->ExceptionClear();
        return false;
    }
    return true;
}

static bool emit_token(sink_bridge & s, const std::string & piece) {
    if (piece.empty()) {
        return true;
    }
    if (!s.env || !s.sink || !s.on_token) {
        return true;
    }
    jstring jpiece = utf8_to_jstring(s.env, piece);
    if (!jpiece) {
        return false;
    }
    jboolean keep = s.env->CallBooleanMethod(s.sink, s.on_token, jpiece);
    s.env->DeleteLocalRef(jpiece);
    if (s.env->ExceptionCheck()) {
        s.env->ExceptionClear();
        return false;
    }
    return keep == JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> guard(g_err_lock);
    if (g_last_error.empty()) {
        return nullptr;
    }
    return utf8_to_jstring(env, g_last_error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeLoad(JNIEnv * env, jclass, jstring path, jint context, jint threads) {
    clear_last_error();
    if (!path) {
        set_last_error("путь к модели пуст");
        return 0;
    }
    const char * raw_path = env->GetStringUTFChars(path, nullptr);
    if (!raw_path) {
        set_last_error("не удалось прочитать путь");
        return 0;
    }
    std::string path_copy(raw_path);
    env->ReleaseStringUTFChars(path, raw_path);

    try {
        ensure_backend();
        if (ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == nullptr) {
            set_last_error("CPU backend недоступен");
            return 0;
        }

        LOGI("loading model: %s", path_copy.c_str());
        llama_model * model = load_model_file(path_copy.c_str());
        if (!model) {
            set_last_error(
                    "не удалось открыть GGUF (файл битый, архитектура не поддерживается "
                    "или не хватает памяти). Возьмите модель из каталога (Gemma 2/3, Qwen2.5/3…).");
            return 0;
        }

        llama_context_params cp = llama_context_default_params();
        // Keep context modest — KV cache dominates RAM and latency on phones.
        cp.n_ctx = (uint32_t) std::max(256, std::min(2048, (int) context));
        cp.n_batch = std::min(cp.n_ctx, 128u);
        cp.n_ubatch = std::min(cp.n_batch, 64u);
        // 1–2 threads: more often deadlocks / oversubscription on mid phones.
        cp.n_threads = std::max(1, std::min(2, (int) threads));
        cp.n_threads_batch = cp.n_threads;
        cp.flash_attn = false;
        cp.offload_kqv = false;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;

        llama_context * ctx = llama_init_from_model(model, cp);
        if (!ctx) {
            llama_model_free(model);
            set_last_error("модель открылась, но контекст не создался (мало RAM — уменьшите «Контекст»)");
            return 0;
        }

        runtime * rt = new runtime();
        rt->model = model;
        rt->ctx = ctx;
        rt->n_ctx = (int) cp.n_ctx;
        rt->n_batch = (int) cp.n_batch;
        rt->threads = (int) cp.n_threads;
        rt->abort.store(false);
        LOGI("model ready n_ctx=%d n_batch=%d threads=%d", rt->n_ctx, rt->n_batch, rt->threads);
        return (jlong) (intptr_t) rt;
    } catch (const std::exception & e) {
        set_last_error(std::string("исключение при загрузке: ") + e.what());
        return 0;
    } catch (...) {
        set_last_error("неизвестная ошибка при загрузке GGUF");
        return 0;
    }
}

/**
 * Stream tokens to sink.onToken(String):boolean while generating.
 * sink.onPhase(String) is optional ("prompt" / "generate").
 * Returns number of generated tokens, or -1 on hard failure.
 */
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens, jfloat temperature, jobject sink) {
    clear_last_error();
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt || !prompt) {
        set_last_error("нет загруженной модели");
        return -1;
    }

    sink_bridge bridge;
    bridge.env = env;
    bridge.sink = sink;
    if (sink) {
        jclass cls = env->GetObjectClass(sink);
        bridge.on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        bridge.on_phase = env->GetMethodID(cls, "onPhase", "(Ljava/lang/String;)V");
        // onPhase is optional — clear exception if missing.
        if (!bridge.on_phase) {
            env->ExceptionClear();
        }
        if (!bridge.on_token) {
            env->ExceptionClear();
            set_last_error("TokenSink.onToken не найден");
            return -1;
        }
        env->DeleteLocalRef(cls);
    }

    std::lock_guard<std::mutex> guard(rt->lock);
    // Always clear a stale abort from a previous Stop / tab switch — a new generate is a fresh request.
    rt->abort.store(false);
    if (!rt->ctx || !rt->model) {
        set_last_error("модель выгружена из памяти");
        return -1;
    }

    const char * raw = env->GetStringUTFChars(prompt, nullptr);
    if (!raw) {
        set_last_error("пустой промпт");
        return -1;
    }
    std::string prompt_copy(raw);
    env->ReleaseStringUTFChars(prompt, raw);

    const llama_vocab * vocab = llama_model_get_vocab(rt->model);
    // ChatML — never prepend BOS (Qwen BOS is often <|endoftext|> / EOG).
    std::vector<llama_token> input = tokenize(vocab, prompt_copy, false);
    if (input.empty()) {
        set_last_error("не удалось токенизировать промпт");
        return -1;
    }
    if ((int) input.size() >= rt->n_ctx - 8) {
        input.erase(input.begin(), input.end() - (rt->n_ctx - 8));
    }
    LOGI("prompt tokens=%d n_ctx=%d", (int) input.size(), rt->n_ctx);

    emit_phase(bridge, "prompt");

    llama_batch batch = llama_batch_init(std::max(rt->n_batch, 64), 0, 1);
    llama_kv_self_clear(rt->ctx);
    llama_set_n_threads(rt->ctx, rt->threads, rt->threads);

    if (!decode_tokens(rt, batch, input, 0)) {
        llama_batch_free(batch);
        if (g_last_error.empty()) {
            set_last_error("ошибка llama_decode на промпте (мало памяти или слишком длинный контекст)");
        }
        return -1;
    }

    emit_phase(bridge, "generate");

    float temp = std::max(0.0f, std::min(1.2f, (float) temperature));
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Prefer greedy for tiny on-device instruct — less empty/garbage output.
    if (temp <= 0.05f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.85f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) llama_time_us()));
    }

    std::string output;
    int n_past = (int) input.size();
    // Allow long CoT + answer (Qwen3 / reasoning GGUFs); still capped by n_ctx.
    int limit = std::max(8, std::min(2048, (int) max_tokens));
    int generated = 0;

    for (int i = 0; i < limit && n_past < rt->n_ctx - 1; i++) {
        if (rt->abort.load()) {
            set_last_error("генерация прервана");
            break;
        }
        // llama_sampler_sample already accepts the token internally — do not accept twice.
        llama_token token = llama_sampler_sample(sampler, rt->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            LOGI("EOG at token %d after %d generated", token, generated);
            break;
        }

        std::string piece = token_piece(vocab, token);
        // Stop on common chat control tokens (ChatML / Gemma / Llama-3 / Phi / …).
        if (piece.find("<|im_end|>") != std::string::npos
                || piece.find("<|im_start|>") != std::string::npos
                || piece.find("<|endoftext|>") != std::string::npos
                || piece.find("<end_of_turn>") != std::string::npos
                || piece.find("<start_of_turn>") != std::string::npos
                || piece.find("<|eot_id|>") != std::string::npos
                || piece.find("<|end_of_text|>") != std::string::npos
                || piece.find("<|end|>") != std::string::npos
                || piece.find("<|assistant|>") != std::string::npos
                || piece.find("<|user|>") != std::string::npos) {
            break;
        }
        output += piece;
        generated++;
        if (!emit_token(bridge, piece)) {
            rt->abort.store(true);
            set_last_error("генерация прервана");
            break;
        }

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(rt->ctx, batch) != 0) {
            set_last_error("ошибка llama_decode при генерации");
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    if (generated == 0 && !rt->abort.load()) {
        // Retry once with add_bos=true in case this GGUF expects it.
        LOGI("0 tokens, retrying with add_bos=true");
        std::vector<llama_token> alt = tokenize(vocab, prompt_copy, true);
        if (!alt.empty()) {
            if ((int) alt.size() >= rt->n_ctx - 8) {
                alt.erase(alt.begin(), alt.end() - (rt->n_ctx - 8));
            }
            llama_batch batch2 = llama_batch_init(std::max(rt->n_batch, 64), 0, 1);
            llama_kv_self_clear(rt->ctx);
            if (decode_tokens(rt, batch2, alt, 0)) {
                llama_sampler * sampler2 = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(sampler2, llama_sampler_init_greedy());
                int n_past2 = (int) alt.size();
                for (int i = 0; i < limit && n_past2 < rt->n_ctx - 1; i++) {
                    if (rt->abort.load()) {
                        break;
                    }
                    llama_token token = llama_sampler_sample(sampler2, rt->ctx, -1);
                    if (llama_vocab_is_eog(vocab, token)) {
                        break;
                    }
                    std::string piece = token_piece(vocab, token);
                    if (piece.find("<|im_end|>") != std::string::npos) {
                        break;
                    }
                    generated++;
                    if (!emit_token(bridge, piece)) {
                        break;
                    }
                    batch2.n_tokens = 1;
                    batch2.token[0] = token;
                    batch2.pos[0] = n_past2++;
                    batch2.n_seq_id[0] = 1;
                    batch2.seq_id[0][0] = 0;
                    batch2.logits[0] = 1;
                    if (llama_decode(rt->ctx, batch2) != 0) {
                        break;
                    }
                }
                llama_sampler_free(sampler2);
            }
            llama_batch_free(batch2);
        }
    }

    if (generated == 0) {
        if (g_last_error.empty()) {
            set_last_error("модель сразу вернула конец ответа. Нажмите «Выгрузить», затем «Загрузить» снова.");
        }
        return -1;
    }
    LOGI("generated %d tokens", generated);
    return generated;
}

// Kept for binary compatibility with older Java that still calls complete().
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeComplete(
        JNIEnv * env, jclass clazz, jlong handle, jstring prompt, jint max_tokens, jfloat temperature) {
    // Collect into a local buffer via a tiny Java-less path: call generate without sink, rebuild string.
    // Without a sink we only get a count — so run a private accumulation using a one-off approach.
    clear_last_error();
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt || !prompt) {
        set_last_error("нет загруженной модели");
        return nullptr;
    }
    // Fallback: use generate with no streaming (sink null) won't collect text.
    // Inline a minimal non-streaming path.
    std::lock_guard<std::mutex> guard(rt->lock);
    rt->abort.store(false);
    if (!rt->ctx || !rt->model) {
        set_last_error("модель выгружена из памяти");
        return nullptr;
    }
    const char * raw = env->GetStringUTFChars(prompt, nullptr);
    if (!raw) {
        return nullptr;
    }
    std::string prompt_copy(raw);
    env->ReleaseStringUTFChars(prompt, raw);
    const llama_vocab * vocab = llama_model_get_vocab(rt->model);
    auto input = tokenize(vocab, prompt_copy, false);
    if (input.empty()) {
        set_last_error("не удалось токенизировать промпт");
        return nullptr;
    }
    if ((int) input.size() >= rt->n_ctx - 8) {
        input.erase(input.begin(), input.end() - (rt->n_ctx - 8));
    }
    llama_batch batch = llama_batch_init(std::max(rt->n_batch, 64), 0, 1);
    llama_kv_self_clear(rt->ctx);
    llama_set_n_threads(rt->ctx, rt->threads, rt->threads);
    if (!decode_tokens(rt, batch, input, 0)) {
        llama_batch_free(batch);
        set_last_error("ошибка llama_decode на промпте");
        return nullptr;
    }
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string output;
    int n_past = (int) input.size();
    int limit = std::max(8, std::min(2048, (int) max_tokens));
    for (int i = 0; i < limit && n_past < rt->n_ctx - 1; i++) {
        if (rt->abort.load()) {
            break;
        }
        llama_token token = llama_sampler_sample(sampler, rt->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        output += token_piece(vocab, token);
        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(rt->ctx, batch) != 0) {
            break;
        }
    }
    llama_sampler_free(sampler);
    llama_batch_free(batch);
    size_t cut = output.find("<|im_end|>");
    if (cut != std::string::npos) {
        output.resize(cut);
    }
    while (!output.empty() && (output.back() == '\n' || output.back() == ' ')) {
        output.pop_back();
    }
    if (output.empty()) {
        set_last_error("пустой ответ");
        return nullptr;
    }
    return utf8_to_jstring(env, output);
}

/**
 * Format messages with the GGUF's chat template (Gemma / Llama-3 / ChatML / Phi / …).
 * roles/texts are parallel String arrays. Returns null on failure (caller falls back).
 */
extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeApplyChatTemplate(
        JNIEnv * env, jclass, jlong handle, jobjectArray roles, jobjectArray texts, jboolean addAssistant) {
    clear_last_error();
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt || !rt->model || !roles || !texts) {
        set_last_error("нет загруженной модели для шаблона");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(roles);
    if (n <= 0 || n != env->GetArrayLength(texts)) {
        set_last_error("пустые сообщения для шаблона");
        return nullptr;
    }

    std::vector<std::string> role_storage;
    std::vector<std::string> text_storage;
    std::vector<llama_chat_message> msgs;
    role_storage.reserve((size_t) n + 1);
    text_storage.reserve((size_t) n + 1);
    msgs.reserve((size_t) n + 1);

    // Leading system message improves Instruct models across families.
    role_storage.emplace_back("system");
    text_storage.emplace_back("Ты полезный ассистент. Отвечай кратко на языке пользователя.");
    msgs.push_back({role_storage.back().c_str(), text_storage.back().c_str()});

    for (jsize i = 0; i < n; i++) {
        auto roleObj = (jstring) env->GetObjectArrayElement(roles, i);
        auto textObj = (jstring) env->GetObjectArrayElement(texts, i);
        if (!roleObj || !textObj) {
            if (roleObj) env->DeleteLocalRef(roleObj);
            if (textObj) env->DeleteLocalRef(textObj);
            continue;
        }
        const char * roleUtf = env->GetStringUTFChars(roleObj, nullptr);
        const char * textUtf = env->GetStringUTFChars(textObj, nullptr);
        std::string role = roleUtf ? roleUtf : "user";
        std::string text = textUtf ? textUtf : "";
        if (roleUtf) env->ReleaseStringUTFChars(roleObj, roleUtf);
        if (textUtf) env->ReleaseStringUTFChars(textObj, textUtf);
        env->DeleteLocalRef(roleObj);
        env->DeleteLocalRef(textObj);
        if (text.empty()) {
            continue;
        }
        if (role != "assistant" && role != "system") {
            role = "user";
        }
        role_storage.push_back(std::move(role));
        text_storage.push_back(std::move(text));
        msgs.push_back({role_storage.back().c_str(), text_storage.back().c_str()});
    }
    if (msgs.size() <= 1) {
        set_last_error("нет пользовательских сообщений");
        return nullptr;
    }

    const char * embedded = llama_model_chat_template(rt->model, nullptr);
    // Prefer GGUF jinja (detected as ChatML / Gemma / Llama-3 / …), then short aliases.
    const char * try_tmpls[] = {
            embedded,
            "chatml",
            "llama3",
            "gemma",
            "phi3",
            "zephyr",
    };

    std::string formatted;
    bool ok = false;
    for (const char * cur : try_tmpls) {
        if (cur == nullptr || cur[0] == '\0') {
            continue;
        }
        int32_t need = llama_chat_apply_template(cur, msgs.data(), msgs.size(), addAssistant != 0, nullptr, 0);
        if (need < 0) {
            continue;
        }
        std::vector<char> buf((size_t) need + 1);
        int32_t wrote = llama_chat_apply_template(cur, msgs.data(), msgs.size(), addAssistant != 0, buf.data(), (int32_t) buf.size());
        if (wrote < 0) {
            continue;
        }
        formatted.assign(buf.data(), (size_t) std::min(wrote, (int32_t) buf.size()));
        if (formatted.empty()) {
            continue;
        }
        ok = true;
        LOGI("chat template ok chars=%d", (int) formatted.size());
        break;
    }
    if (!ok) {
        set_last_error("шаблон чата модели не распознан");
        return nullptr;
    }
    return utf8_to_jstring(env, formatted);
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeAbort(JNIEnv *, jclass, jlong handle) {
    runtime * rt = (runtime *) (intptr_t) handle;
    if (rt) {
        rt->abort.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt) {
        return;
    }
    rt->abort.store(true);
    std::lock_guard<std::mutex> guard(rt->lock);
    if (rt->ctx) {
        llama_free(rt->ctx);
        rt->ctx = nullptr;
    }
    if (rt->model) {
        llama_model_free(rt->model);
        rt->model = nullptr;
    }
    delete rt;
}
