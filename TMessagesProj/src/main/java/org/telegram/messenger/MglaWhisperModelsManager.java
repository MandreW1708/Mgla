package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;

/**
 * Каталог и скачивание ggml-моделей Whisper (HuggingFace ggerganov/whisper.cpp).
 */
public final class MglaWhisperModelsManager {

    public static final String PREFS = "mgla_config";
    public static final String PREF_SELECTED = "ai_transcribe_whisper_model";
    public static final String PREF_LANGUAGE = "ai_transcribe_whisper_language";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_WHISPER = "whisper";
    public static final String PREF_PROVIDER = "ai_transcribe_provider";

    public static class ModelInfo {
        public final String id;
        public final String title;
        public final String description;
        public final String url;
        public final long sizeBytes;
        public final String fileName;

        public ModelInfo(String id, String title, String description, String url, long sizeBytes, String fileName) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.url = url;
            this.sizeBytes = sizeBytes;
            this.fileName = fileName;
        }
    }

    public static final ModelInfo[] CATALOG = {
        new ModelInfo(
            "tiny-q5_1",
            "Tiny Q5_1",
            "Самая быстрая, ~31 МБ. Базовое качество, подходит для коротких голосовых",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            31_000_000L,
            "ggml-tiny-q5_1.bin"),
        new ModelInfo(
            "tiny",
            "Tiny",
            "Быстрая мультиязычная (~75 МБ)",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            75_000_000L,
            "ggml-tiny.bin"),
        new ModelInfo(
            "base-q5_1",
            "Base Q5_1",
            "Баланс скорость/качество (~57 МБ)",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            57_000_000L,
            "ggml-base-q5_1.bin"),
        new ModelInfo(
            "base",
            "Base",
            "Лучше Tiny на русском (~142 МБ)",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            142_000_000L,
            "ggml-base.bin"),
        new ModelInfo(
            "small-q5_1",
            "Small Q5_1",
            "Выше качество, медленнее (~181 МБ)",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            181_000_000L,
            "ggml-small-q5_1.bin"),
    };

    public interface DownloadCallback {
        void onProgress(long downloaded, long total);

        void onDone(File file);

        void onError(String message);
    }

    private static final HashMap<String, Thread> activeDownloads = new HashMap<>();

    private MglaWhisperModelsManager() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getProvider() {
        String p = prefs().getString(PREF_PROVIDER, PROVIDER_GEMINI);
        if (PROVIDER_WHISPER.equals(p)) {
            return PROVIDER_WHISPER;
        }
        return PROVIDER_GEMINI;
    }

    public static void setProvider(String provider) {
        prefs().edit().putString(PREF_PROVIDER, provider == null ? PROVIDER_GEMINI : provider).apply();
    }

    public static String getProviderTitle() {
        return PROVIDER_WHISPER.equals(getProvider()) ? "Локальный Whisper" : "Gemini";
    }

    public static String getLanguage() {
        String lang = prefs().getString(PREF_LANGUAGE, "ru");
        return TextUtils.isEmpty(lang) ? "ru" : lang;
    }

    public static void setLanguage(String language) {
        prefs().edit().putString(PREF_LANGUAGE, language == null ? "auto" : language).apply();
    }

    public static String getLanguageTitle() {
        switch (getLanguage()) {
            case "auto":
                return "Авто";
            case "ru":
                return "Русский";
            case "en":
                return "English";
            case "uk":
                return "Українська";
            case "be":
                return "Беларуская";
            case "kk":
                return "Қазақша";
            default:
                return getLanguage();
        }
    }

    public static File modelsDir(Context context) {
        File dir = new File(context.getFilesDir(), "whisper_models");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static ModelInfo find(String id) {
        if (id == null) {
            return null;
        }
        for (ModelInfo info : CATALOG) {
            if (info.id.equals(id)) {
                return info;
            }
        }
        return null;
    }

    public static File modelFile(Context context, String id) {
        ModelInfo info = find(id);
        String name = info != null ? info.fileName : (id + ".bin");
        return new File(modelsDir(context), name);
    }

    public static boolean isDownloaded(Context context, String id) {
        File f = modelFile(context, id);
        return f.exists() && f.length() > 1024;
    }

    public static boolean isDownloading(String id) {
        return activeDownloads.containsKey(id);
    }

    public static void selectModel(String id) {
        prefs().edit().putString(PREF_SELECTED, id == null ? "" : id).apply();
    }

    public static String getSelectedModelId() {
        return prefs().getString(PREF_SELECTED, "tiny-q5_1");
    }

    public static File getLoadedModel(Context context) {
        String id = getSelectedModelId();
        if (TextUtils.isEmpty(id) || !isDownloaded(context, id)) {
            return null;
        }
        return modelFile(context, id);
    }

    public static String getSelectedModelTitle() {
        ModelInfo info = find(getSelectedModelId());
        if (info == null) {
            return "Не выбрана";
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx != null && !isDownloaded(ctx, info.id)) {
            return info.title + " (не скачана)";
        }
        return info.title;
    }

    public static void deleteModel(Context context, String id) {
        File f = modelFile(context, id);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        if (id != null && id.equals(getSelectedModelId())) {
            selectModel(null);
        }
    }

    public static void download(Context context, ModelInfo info, DownloadCallback callback) {
        if (info == null || isDownloading(info.id)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            File target = modelFile(appContext, info.id);
            File temp = new File(target.getAbsolutePath() + ".part");
            try {
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
        }, "MglaWhisperDownload");
        activeDownloads.put(info.id, thread);
        thread.start();
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
