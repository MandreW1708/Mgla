#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MglaLlama", __VA_ARGS__)

struct runtime {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 0;
    int threads = 1;
    std::mutex lock;
};

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = (int) text.size() + 4;
    std::vector<llama_token> tokens(n);
    n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true);
    if (n < 0) { tokens.resize(-n); n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), (int32_t) tokens.size(), true, true); }
    if (n < 0) return {};
    tokens.resize(n); return tokens;
}

static std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(32);
    int n = llama_token_to_piece(vocab, token, buffer.data(), (int32_t) buffer.size(), 0, false);
    if (n < 0) { buffer.resize(-n); n = llama_token_to_piece(vocab, token, buffer.data(), (int32_t) buffer.size(), 0, false); }
    return n > 0 ? std::string(buffer.data(), n) : std::string();
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeLoad(JNIEnv * env, jclass, jstring path, jint context, jint threads) {
    if (!path) return 0;
    const char * raw_path = env->GetStringUTFChars(path, nullptr);
    if (!raw_path) return 0;
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(raw_path, mp);
    env->ReleaseStringUTFChars(path, raw_path);
    if (!model) { LOGE("unable to load model"); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(512, std::min(8192, (int) context));
    cp.n_batch = std::min(cp.n_ctx, 512u);
    cp.n_threads = std::max(1, std::min(8, (int) threads));
    cp.n_threads_batch = cp.n_threads;
    cp.flash_attn = false;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    runtime * rt = new runtime(); rt->model = model; rt->ctx = ctx; rt->n_ctx = (int) cp.n_ctx; rt->threads = (int) cp.n_threads;
    return (jlong) (intptr_t) rt;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeComplete(JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens, jfloat temperature) {
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt || !prompt) return nullptr;
    std::lock_guard<std::mutex> guard(rt->lock);
    const char * raw = env->GetStringUTFChars(prompt, nullptr);
    if (!raw) return nullptr;
    const llama_vocab * vocab = llama_model_get_vocab(rt->model);
    std::vector<llama_token> input = tokenize(vocab, raw);
    env->ReleaseStringUTFChars(prompt, raw);
    if (input.empty()) return nullptr;
    if ((int) input.size() >= rt->n_ctx) input.erase(input.begin(), input.end() - rt->n_ctx + 1);
    llama_kv_cache_clear(rt->ctx);
    llama_set_n_threads(rt->ctx, rt->threads, rt->threads);
    llama_batch batch = llama_batch_init(rt->n_ctx, 0, 1);
    batch.n_tokens = (int32_t) input.size();
    for (int i = 0; i < batch.n_tokens; i++) { batch.token[i] = input[i]; batch.pos[i] = i; batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0; batch.logits[i] = i == batch.n_tokens - 1; }
    if (llama_decode(rt->ctx, batch) != 0) { llama_batch_free(batch); return nullptr; }
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.0f, std::min(2.0f, (float) temperature))));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) llama_time_us()));
    std::string output;
    int n_past = (int) input.size();
    int limit = std::max(1, std::min(2048, (int) max_tokens));
    for (int i = 0; i < limit && n_past < rt->n_ctx - 1; i++) {
        llama_token token = llama_sampler_sample(sampler, rt->ctx, -1);
        if (token == llama_vocab_eos(vocab)) break;
        llama_sampler_accept(sampler, token);
        output += token_piece(vocab, token);
        batch.n_tokens = 1; batch.token[0] = token; batch.pos[0] = n_past++; batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
        if (llama_decode(rt->ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler); llama_batch_free(batch);
    return output.empty() ? nullptr : env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_MglaLlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    runtime * rt = (runtime *) (intptr_t) handle;
    if (!rt) return;
    std::lock_guard<std::mutex> guard(rt->lock);
    if (rt->ctx) llama_free(rt->ctx);
    if (rt->model) llama_model_free(rt->model);
    delete rt;
}
