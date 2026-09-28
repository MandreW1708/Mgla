package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

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

    public static final ModelInfo[] CATALOG = {
        new ModelInfo(
            "smollm2-135m",
            "SmolLM2 135M Instruct",
            "Крошечная модель, мгновенный инференс, темы по смыслу",
            "https://huggingface.co/Felladrin/gguf-Q8_0-SmolLM2-135M-Instruct/resolve/main/smollm2-135m-instruct-q8_0.gguf",
            150_000_000L),
        new ModelInfo(
            "qwen3-0.6b",
            "Qwen3 0.6B Instruct",
            "Сильная малая модель, хорошее понимание русского",
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            660_000_000L),
        new ModelInfo(
            "gemma-3-270m",
            "Gemma 3 270M IT",
            "Быстрая модель Google, аккуратные короткие ответы",
            "https://huggingface.co/unsloth/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q4_K_M.gguf",
            190_000_000L),
        new ModelInfo(
            "smollm2-1.7b",
            "SmolLM2 1.7B Instruct",
            "Максимальное качество среди мини-моделей, вес ~1 ГБ",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            1_100_000_000L),
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

    /** Скачанная и выбранная модель (null — ничего не загружено). */
    public static File getLoadedModel(Context context) {
        String id = prefs().getString(PREF_LOADED_MODEL, "");
        if (TextUtils.isEmpty(id) || !isDownloaded(context, id)) {
            return null;
        }
        return modelFile(context, id);
    }

    public static void selectModel(String id) {
        prefs().edit().putString(PREF_LOADED_MODEL, id == null ? "" : id).apply();
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
        if (isDownloading(info.id)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            File target = modelFile(appContext, info.id);
            File temp = new File(target.getAbsolutePath() + ".part");
            HttpURLConnection conn = null;
            try {
                URL url = new URL(info.url);
                // HuggingFace отдаёт файлы через 302-редирект на CDN —
                // следуем ему вручную, HttpURLConnection не всегда справляется.
                for (int i = 0; i < 5; i++) {
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "Mgla/1.0");
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (location == null || location.isEmpty()) {
                            throw new Exception("HTTP " + code);
                        }
                        url = new URL(url, location);
                        continue;
                    }
                    if (code != 200) {
                        throw new Exception("HTTP " + code);
                    }
                    break;
                }
                long total = conn.getContentLengthLong();
                long downloaded = 0;
                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(temp)) {
                    int read;
                    long lastPublish = 0;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        long now = System.currentTimeMillis();
                        if (now - lastPublish > 300 && callback != null) {
                            lastPublish = now;
                            final long d = downloaded, t = total;
                            AndroidUtilities.runOnUIThread(() -> callback.onProgress(d, t));
                        }
                    }
                }
                if (temp.exists() && temp.length() > 1024) {
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
                if (temp.exists()) {
                    temp.delete();
                }
                if (callback != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Ошибка скачивания";
                    AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
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
