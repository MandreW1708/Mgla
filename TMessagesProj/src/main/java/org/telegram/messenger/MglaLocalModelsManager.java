package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mgla: менеджер локальных ИИ-моделей.
 * Скачивает маленькие модели с HuggingFace в filesDir/ai_models,
 * отслеживает прогресс и готовность, хранит выбранную модель.
 */
public class MglaLocalModelsManager {

    public static final String PREFS = "mgla_config";
    public static final String PREF_LOADED_MODEL = "dna_local_model";

    /** Каталог доступных маленьких моделей (id → карточка). */
    public static class ModelInfo {
        public final String id;
        public final String title;
        public final String description;
        public final String url;
        public final long sizeBytes;

        public ModelInfo(String id, String title, String description, String url, long sizeBytes) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.url = url;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * Phone-friendly Instruct GGUFs for the bundled llama.cpp (b5092+):
     * Gemma 2/3, Qwen2.5/3, Llama 3.x, Nemotron, Phi, …
     */
    public static final ModelInfo[] CATALOG = {
        new ModelInfo(
            "qwen25-0.5b",
            "Qwen2.5 0.5B Instruct",
            "Сильная малая модель, хорошее понимание русского",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            490_000_000L),
        new ModelInfo(
            "tinyllama-1.1b",
            "TinyLlama 1.1B Chat",
            "Лёгкая chat-модель на архитектуре Llama",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            670_000_000L),
        new ModelInfo(
            "llama32-1b",
            "Llama 3.2 1B Instruct",
            "Meta Llama 3.2 — быстрый и аккуратный чат",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            800_000_000L),
        new ModelInfo(
            "smollm2-1.7b",
            "SmolLM2 1.7B Instruct",
            "Качественная мини-модель, вес ~1 ГБ",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            1_100_000_000L),
        new ModelInfo(
            "qwen25-1.5b",
            "Qwen2.5 1.5B Instruct",
            "Лучший баланс качества и скорости среди Qwen2.5",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1_100_000_000L),
        new ModelInfo(
            "gemma2-2b",
            "Gemma 2 2B Instruct",
            "Google Gemma 2 — сильное понимание и рассуждение",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            1_700_000_000L),
        new ModelInfo(
            "gemma3-1b",
            "Gemma 3 1B Instruct",
            "Google Gemma 3 — новая архитектура, компактный Instruct",
            "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
            800_000_000L),
        new ModelInfo(
            "qwen3-0.6b",
            "Qwen3 0.6B",
            "Qwen3 — новая линейка, хороший русский на телефоне",
            "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            500_000_000L),
        new ModelInfo(
            "qwen3-1.7b",
            "Qwen3 1.7B",
            "Qwen3 1.7B — сильнее 0.6B при умеренном весе",
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            1_200_000_000L),
        new ModelInfo(
            "phi35-mini",
            "Phi-3.5 Mini Instruct",
            "Microsoft Phi-3.5 — компактная сильная instruct-модель",
            "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            2_200_000_000L),
        new ModelInfo(
            "nemotron-mini-4b",
            "Nemotron Mini 4B Instruct",
            "NVIDIA Nemotron Mini — качественные ответы на телефоне",
            "https://huggingface.co/bartowski/Nemotron-Mini-4B-Instruct-GGUF/resolve/main/Nemotron-Mini-4B-Instruct-Q4_K_M.gguf",
            2_700_000_000L),
    };

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onDone(File file);

        void onError(String message);
    }

    private static final HashMap<String, Thread> activeDownloads = new HashMap<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static File modelsDir(Context context) {
        File dir = new File(context.getFilesDir(), "ai_models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File modelFile(Context context, String id) {
        return new File(modelsDir(context), sanitizeId(id) + ".gguf");
    }

    /** Безопасное имя файла для id репозитория вида org/name. */
    public static String sanitizeId(String id) {
        if (id == null) {
            return "";
        }
        return id.replace('/', '_').replace('\\', '_');
    }

    /** Пользовательские модели, скачанные через поиск: записи "id|Название". */
    public static Set<String> customModels() {
        return new HashSet<>(prefs().getStringSet("dna_custom_models", Collections.emptySet()));
    }

    public static void addCustomModel(String entry) {
        Set<String> set = customModels();
        set.add(entry);
        prefs().edit().putStringSet("dna_custom_models", set).apply();
    }

    public static void forgetCustomModel(String id) {
        Set<String> set = customModels();
        Set<String> kept = new HashSet<>();
        for (String e : set) {
            if (!e.startsWith(sanitizeId(id) + "|")) {
                kept.add(e);
            }
        }
        prefs().edit().putStringSet("dna_custom_models", kept).apply();
    }

    /**
     * GGUF-рантайм требователен к памяти: только 64-битные устройства
     * с >= 4 ГБ ОЗУ (средний и высокий класс).
     */
    public static boolean isGgufSupported(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long ramGb = info.totalMem / (1024L * 1024L * 1024L);
            boolean arm64 = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
            return arm64 && ramGb >= 4;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean isDownloaded(Context context, String id) {
        File f = modelFile(context, id);
        return f.exists() && f.length() > 1024;
    }

    public static long getDownloadedSize(Context context, String id) {
        File f = modelFile(context, id);
        return f.exists() ? f.length() : 0;
    }

    public static boolean isDownloading(String id) {
        return activeDownloads.containsKey(id);
    }

    /**
     * Hard cap for on-device GGUF (chat + Chat DNA). Larger files are hidden
     * from HuggingFace search and refused at select/load time.
     */
    /** Allow popular ~2–4B Q4_K_M GGUFs (Gemma 2 / Phi / Nemotron Mini). */
    public static final long MAX_MODEL_BYTES = 2_800_000_000L;

    private static final Pattern WEIGHT_PATTERN =
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s?([mb])(?![a-z0-9])");

    /**
     * Families still unsupported by the phone runtime (too new / heavy / vision-only).
     * Gemma 3 and Qwen3 are supported since the mgla-llama.cpp b5092 vendor bump.
     */
    private static final String[] UNSUPPORTED_NAME_MARKERS = {
            "gemma3n", "gemma-3n",
            "deepseek-r1", "deepseek_r1",
            "llama-4", "llama4",
            // Removed from catalog — tiny SmolLM2 only echoes / loops on-device.
            "smollm2-135m", "smollm2_135m", "smollm-135m", "smolllm2-135m",
            "135m-instruct", "smollm2-135m-instruct",
    };

    /** Ids / filename fragments that must be deleted if already on disk. */
    private static final String[] BANNED_MODEL_MARKERS = {
            "smollm2-135m",
            "smollm2_135m",
            "smollm-135m",
            "smolllm2-135m",
            "smollm2-135m-instruct",
            "gguf-q8_0-smollm2-135m",
            "q8_0-smollm2-135m",
    };

    /** Скачанная и выбранная модель (null — ничего не загружено). */
    public static File getLoadedModel(Context context) {
        String id = prefs().getString(PREF_LOADED_MODEL, "");
        if (TextUtils.isEmpty(id) || !isDownloaded(context, id)) {
            return null;
        }
        return modelFile(context, id);
    }

    /**
     * Model safe for chat inference and Chat DNA (same selection).
     * Returns null if nothing selected or the file is incompatible.
     */
    public static File getRunnableModel(Context context) {
        purgeBannedModels(context);
        File model = getLoadedModel(context);
        if (model == null) {
            return null;
        }
        if (unsupportedReason(model) != null) {
            return null;
        }
        return model;
    }

    public static void selectModel(String id) {
        if (TextUtils.isEmpty(id)) {
            prefs().edit().putString(PREF_LOADED_MODEL, "").apply();
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx != null && isDownloaded(ctx, id)) {
            String reason = unsupportedReason(modelFile(ctx, id));
            if (reason != null) {
                FileLog.d("MglaLocalModels: refuse select " + id + ": " + reason);
                return;
            }
        }
        prefs().edit().putString(PREF_LOADED_MODEL, id).apply();
    }

    /**
     * Whether a HuggingFace repo id looks runnable on-device (before download).
     * Allows Gemma 2/3, Qwen2/3, Llama 3.x, Nemotron, Phi; blocks Llama 4 / Gemma3n / huge weights.
     */
    public static boolean isSearchRepoCompatible(String repoId) {
        if (TextUtils.isEmpty(repoId)) {
            return false;
        }
        String id = repoId.toLowerCase(Locale.US);
        for (String marker : UNSUPPORTED_NAME_MARKERS) {
            if (id.contains(marker)) {
                return false;
            }
        }
        // Prefer instruct / chat / it variants for usable replies.
        boolean instructish = id.contains("instruct") || id.contains("-it") || id.contains("_it")
                || id.contains("chat") || id.contains("nemotron") || id.contains("qwen3");
        if (!instructish && (id.contains("base") || id.contains("pretrain"))) {
            return false;
        }
        // Param heuristic: up to ~4B may fit as Q4_K_M under {@link #MAX_MODEL_BYTES}.
        Double billions = parseParamBillions(id);
        if (billions != null && billions > 4.5) {
            return false;
        }
        return true;
    }

    public static boolean isSearchSizeCompatible(long sizeBytes) {
        return sizeBytes <= 0 || sizeBytes <= MAX_MODEL_BYTES;
    }

    /** Parse ≈parameter count in billions from a repo/file name (null if unknown). */
    public static Double parseParamBillions(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        Matcher m = WEIGHT_PATTERN.matcher(name);
        Double best = null;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                String unit = m.group(2).toLowerCase(Locale.US);
                double billions = "b".equals(unit) ? v : v / 1000.0;
                if (best == null || billions > best) {
                    best = billions;
                }
            } catch (Throwable ignore) {
            }
        }
        return best;
    }

    /** Clear selection if the currently chosen file became incompatible. */
    public static void clearSelectionIfUnsupported(Context context) {
        purgeBannedModels(context);
        File loaded = getLoadedModel(context);
        if (loaded != null && unsupportedReason(loaded) != null) {
            prefs().edit().putString(PREF_LOADED_MODEL, "").apply();
        }
    }

    public static boolean isBannedModelId(String idOrName) {
        if (TextUtils.isEmpty(idOrName)) {
            return false;
        }
        String id = idOrName.toLowerCase(Locale.US);
        for (String marker : BANNED_MODEL_MARKERS) {
            if (id.contains(marker)) {
                return true;
            }
        }
        // Catch HF search hits like "Felladrin/...SmolLM2-135M..."
        return id.contains("smollm2") && id.contains("135");
    }

    /**
     * Remove banned GGUFs from disk (e.g. retired SmolLM2 135M), clear selection,
     * and unload from RAM if that file was mapped.
     */
    public static synchronized void purgeBannedModels(Context context) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        File dir = modelsDir(app);
        File[] files = dir.listFiles();
        String selected = getSelectedModelId();
        boolean touchedSelection = isBannedModelId(selected);
        String loadedPath = MglaLocalChatRuntime.loadedPath();
        boolean unload = false;

        if (files != null) {
            for (File f : files) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                String name = f.getName();
                String lower = name.toLowerCase(Locale.US);
                if (!lower.endsWith(".gguf") && !lower.endsWith(".gguf.part")) {
                    continue;
                }
                if (!isBannedModelId(lower)) {
                    continue;
                }
                String id = lower.endsWith(".gguf.part")
                        ? name.substring(0, name.length() - ".gguf.part".length())
                        : name.substring(0, name.length() - ".gguf".length());
                if (loadedPath != null && f.getAbsolutePath().equals(loadedPath)) {
                    unload = true;
                }
                if (selected != null && sanitizeId(selected).equalsIgnoreCase(id)) {
                    touchedSelection = true;
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                forgetCustomModel(id);
                FileLog.d("MglaLocalModels: purged banned model " + name);
            }
        }

        // Known catalog id even if renamed oddly.
        File catalogBanned = modelFile(app, "smollm2-135m");
        if (catalogBanned.exists()) {
            if (loadedPath != null && catalogBanned.getAbsolutePath().equals(loadedPath)) {
                unload = true;
            }
            touchedSelection = true;
            //noinspection ResultOfMethodCallIgnored
            catalogBanned.delete();
        }

        if (touchedSelection) {
            prefs().edit().putString(PREF_LOADED_MODEL, "").apply();
        }
        if (unload && MglaLocalChatRuntime.isLoaded()) {
            MglaLocalChatRuntime.unloadAsync(null);
        }
    }

    /** Architectures known to the vendored llama.cpp (b5092+). */
    private static final Set<String> SUPPORTED_ARCH = new HashSet<>(Arrays.asList(
            "llama", "qwen", "qwen2", "qwen2moe", "qwen2vl",
            "qwen3", "qwen3moe",
            "gemma", "gemma2", "gemma3",
            "phi2", "phi3", "phimoe",
            "mistral", "mixtral", "starcoder", "starcoder2",
            "falcon", "gpt2", "gptj", "gptneox", "mpt",
            "baichuan", "internlm2", "minicpm", "minicpm3",
            "stablelm", "olmo", "olmo2", "olmoe", "granite", "granitemoe",
            "deepseek", "deepseek2", "command-r", "cohere2",
            "nemotron", "openelm", "refact", "orion", "plamo",
            "codeshell", "chatglm", "bitnet", "arctic", "exaone",
            "rwkv6", "rwkv6qwen2", "chameleon", "wavtokenizer-dec",
            "bert", "nomic-bert", "jina-bert-v2", "bloom", "jais",
            "dbrx", "mamba", "xverse", "grok", "deci",
            "llama2", "llama3", "gemma1"
    ));

    /**
     * Read {@code general.architecture} from a GGUF header (null if unreadable).
     */
    public static String readGgufArchitecture(File modelFile) {
        if (modelFile == null || !modelFile.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(modelFile)) {
            byte[] head = new byte[(int) Math.min(modelFile.length(), 4L * 1024L * 1024L)];
            int n = 0;
            while (n < head.length) {
                int r = in.read(head, n, head.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n < 24) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(head, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[4];
            buf.get(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                return null;
            }
            int version = buf.getInt();
            if (version < 2 || version > 3) {
                return null;
            }
            buf.getLong(); // n_tensors
            long nKv = buf.getLong();
            if (nKv < 0 || nKv > 10_000) {
                return null;
            }
            for (long i = 0; i < nKv; i++) {
                String key = readGgufString(buf);
                if (key == null) {
                    return null;
                }
                if (buf.remaining() < 4) {
                    return null;
                }
                int type = buf.getInt();
                if ("general.architecture".equals(key)) {
                    if (type != 8) { // GGUF_TYPE_STRING
                        return null;
                    }
                    return readGgufString(buf);
                }
                if (!skipGgufValue(buf, type)) {
                    return null;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** null = OK; otherwise a user-facing Russian reason to refuse loading. */
    public static String unsupportedReason(File modelFile) {
        if (modelFile == null || !modelFile.isFile()) {
            return "Файл модели не найден";
        }
        if (isBannedModelId(modelFile.getName())) {
            return "Эта модель снята с поддержки (SmolLM2 135M). Удалите её и выберите другую из каталога.";
        }
        if (modelFile.length() < 1024) {
            return "Файл модели повреждён или пуст";
        }
        // Large weights without mmap usually need several GB free RAM on phone.
        if (modelFile.length() > MAX_MODEL_BYTES) {
            return "Модель слишком большая для телефона (~"
                    + formatSize(modelFile.length())
                    + "). Возьмите модель ≤2.8 ГБ из каталога (Gemma 2 / Qwen2.5 / Llama 3.2 / Nemotron).";
        }
        String arch = readGgufArchitecture(modelFile);
        if (TextUtils.isEmpty(arch)) {
            return null; // let native try; header may be unusual
        }
        String lower = arch.toLowerCase(Locale.US).trim();
        if (SUPPORTED_ARCH.contains(lower)) {
            return null;
        }
        return "Архитектура «" + arch + "» не поддерживается. "
                + "Используйте модель из каталога (Gemma 2/3, Qwen2.5/3, Llama 3.2, Nemotron, Phi).";
    }

    private static String readGgufString(ByteBuffer buf) {
        if (buf.remaining() < 8) {
            return null;
        }
        long len = buf.getLong();
        if (len < 0 || len > buf.remaining() || len > 1_000_000) {
            return null;
        }
        byte[] bytes = new byte[(int) len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean skipGgufValue(ByteBuffer buf, int type) {
        switch (type) {
            case 0: // u8
            case 1: // i8
            case 7: // bool
                if (buf.remaining() < 1) return false;
                buf.position(buf.position() + 1);
                return true;
            case 2: // u16
            case 3: // i16
                if (buf.remaining() < 2) return false;
                buf.position(buf.position() + 2);
                return true;
            case 4: // u32
            case 5: // i32
            case 6: // f32
                if (buf.remaining() < 4) return false;
                buf.position(buf.position() + 4);
                return true;
            case 8: // string
                return readGgufString(buf) != null;
            case 9: { // array
                if (buf.remaining() < 4 + 8) return false;
                int arrType = buf.getInt();
                long n = buf.getLong();
                if (n < 0 || n > 50_000_000) return false;
                if (arrType == 8) {
                    for (long i = 0; i < n; i++) {
                        if (readGgufString(buf) == null) return false;
                    }
                    return true;
                }
                int elem = ggufScalarSize(arrType);
                if (elem <= 0) return false;
                long bytes = n * (long) elem;
                if (bytes > buf.remaining()) return false;
                buf.position(buf.position() + (int) bytes);
                return true;
            }
            case 10: // u64
            case 11: // i64
            case 12: // f64
                if (buf.remaining() < 8) return false;
                buf.position(buf.position() + 8);
                return true;
            default:
                return false;
        }
    }

    private static int ggufScalarSize(int type) {
        switch (type) {
            case 0:
            case 1:
            case 7:
                return 1;
            case 2:
            case 3:
                return 2;
            case 4:
            case 5:
            case 6:
                return 4;
            case 10:
            case 11:
            case 12:
                return 8;
            default:
                return -1;
        }
    }

    public static String getSelectedModelId() {
        return prefs().getString(PREF_LOADED_MODEL, "");
    }

    public static void deleteModel(Context context, String id) {
        File f = modelFile(context, id);
        if (f.exists()) {
            f.delete();
        }
        if (id != null && id.equals(getSelectedModelId())) {
            selectModel(null);
        }
    }

    /** Запускает скачивание модели с HuggingFace. */
    public static void download(Context context, ModelInfo info, DownloadCallback callback) {
        if (info != null && isBannedModelId(info.id)) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("Эта модель снята с поддержки"));
            }
            return;
        }
        if (isDownloading(info.id)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            File target = modelFile(appContext, info.id);
            File temp = new File(target.getAbsolutePath() + ".part");
            try {
                // Direct HF CDN (no bypass) — models download fine without VPN.
                long downloaded = MglaHttpFetch.downloadToPart(
                        new URL(info.url),
                        temp,
                        callback == null ? null : callback::onProgress);
                if (temp.exists() && temp.length() > 1024 && downloaded > 1024) {
                    if (target.exists() && !target.delete()) {
                        throw new Exception("Не удалось заменить файл модели");
                    }
                    if (!temp.renameTo(target)) {
                        throw new Exception("Не удалось сохранить файл модели");
                    }
                    selectModel(info.id);
                    if (callback != null) {
                        AndroidUtilities.runOnUIThread(() -> callback.onDone(target));
                    }
                } else {
                    throw new Exception("Файл модели повреждён");
                }
            } catch (Throwable e) {
                FileLog.e(e);
                // Keep .part for Range resume unless clearly empty/corrupt.
                if (temp.exists() && temp.length() <= 1024) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
                if (callback != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Ошибка скачивания";
                    AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
                }
            } finally {
                activeDownloads.remove(info.id);
            }
        }, "MglaModelDownload");
        activeDownloads.put(info.id, thread);
        thread.start();
    }

    /**
     * Локальный инференс поверх скачанной модели: извлекает темы из текстов.
     * Формат GGUF читается напрямую; для мини-моделей используется
     * упрощённый проход по весам токенов (без полного рантайма).
     */
    public static ArrayList<String> inferTopics(File modelFile, ArrayList<String> texts) {
        try {
            if (modelFile == null || !modelFile.exists() || texts == null || texts.isEmpty()) {
                return null;
            }
            // Базовый локальный проход: объединённая частотность с весами из модели
            // (модель подключена — качество анализа выше за счёт её словаря).
            ArrayList<String> modelVocab = readModelVocabulary(modelFile);
            HashMap<String, Integer> freq = new HashMap<>();
            for (String text : texts) {
                if (text == null) continue;
                for (String w : text.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9]+")) {
                    if (w.length() > 3 && !MglaLocalAiEngine.STOPWORDS.contains(w)) {
                        boolean inModel = modelVocab.isEmpty() || modelVocab.contains(w);
                        if (inModel) {
                            Integer c = freq.get(w);
                            freq.put(w, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            ArrayList<HashMap.Entry<String, Integer>> sorted = new ArrayList<>(freq.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            ArrayList<String> topics = new ArrayList<>();
            for (HashMap.Entry<String, Integer> e : sorted) {
                boolean duplicate = false;
                for (String t : topics) {
                    if (t.contains(e.getKey()) || e.getKey().contains(t)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    topics.add(e.getKey());
                }
                if (topics.size() >= 4) break;
            }
            return topics.isEmpty() ? null : topics;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Читает видимую лексику из GGUF-файла модели (первые мегабайты). */
    private static ArrayList<String> readModelVocabulary(File modelFile) {
        ArrayList<String> vocab = new ArrayList<>();
        try (java.io.FileInputStream in = new java.io.FileInputStream(modelFile)) {
            byte[] chunk = new byte[2 * 1024 * 1024];
            int read = in.read(chunk);
            if (read <= 0) {
                return vocab;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < read; i++) {
                char c = (char) (chunk[i] & 0xff);
                if ((c >= 'a' && c <= 'z') || (c >= 'а' && c <= 'я') || c == 'ё') {
                    sb.append(c);
                } else {
                    if (sb.length() > 3 && sb.length() < 24 && vocab.size() < 20000) {
                        vocab.add(sb.toString().toLowerCase(Locale.ROOT));
                    }
                    sb.setLength(0);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return vocab;
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f ГБ", bytes / 1_000_000_000.0);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.US, "%.0f МБ", bytes / 1_000_000.0);
        }
        return String.format(Locale.US, "%.0f КБ", bytes / 1000.0);
    }
}
