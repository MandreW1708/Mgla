import urllib.request, json, time

prompt = "Сделай краткую сводку следующего текста:\n\nМаша пошла в магазин, купила хлеб, молоко и яйца. Потом вернулась домой и приготовила омлет."

models = [
    "liquid/lfm-2.5-1.2b-instruct:free",
    "qwen/qwen3-next-80b-a3b-instruct:free",
    "google/gemma-4-31b-it:free",
    "moonshotai/kimi-k2.6:free",
    "nvidia/nemotron-3-nano-30b-a3b:free",
]

for model in models:
    print(f"Testing: {model}...", end=" ", flush=True)
    data = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": "Ты — полезный AI-ассистент в мессенджере Mgla. Отвечай кратко, на русском языке."},
            {"role": "user", "content": prompt}
        ],
        "max_tokens": 300
    }).encode()
    
    req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=data, headers={
        "Authorization": "Bearer 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",
        "Content-Type": "application/json"
    })
    
    try:
        start = time.time()
        resp = urllib.request.urlopen(req, timeout=20)
        body = json.loads(resp.read())
        elapsed = time.time() - start
        content = body["choices"][0]["message"]["content"].strip()
        print(f"OK ({elapsed:.1f}s): {content[:100]}...")
    except Exception as e:
        code = getattr(e, 'code', '?')
        if hasattr(e, 'read'):
            err = json.loads(e.read()).get("error",{}).get("message","")[:100]
        else:
            err = str(e)[:100]
        print(f"ERR {code}: {err}")
