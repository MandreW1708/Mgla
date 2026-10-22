# Mgla local LLM runtime

Vendored [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) **b5092** (stripped: no CUDA/Vulkan/examples).

Used only by `libmglallama.so` (`jni/mgla_llama.cmake`). Independent of Whisper’s older `talk-llama`.

Includes architectures needed for phone GGUFs: **Gemma 3**, **Qwen3**, plus Gemma 2 / Qwen2 / Llama 3 / Phi / Nemotron / …
