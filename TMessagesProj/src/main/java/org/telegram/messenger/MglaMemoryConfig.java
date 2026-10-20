package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Mgla: настройки «Живой памяти о переписках».
 * Все значения — в SharedPreferences {@code mgla_config}.
 *
 * Чаты в наблюдении хранятся как набор строк {@code "account:uid"} — у каждого
 * аккаунта свой список, диалоги не смешиваются.
 */
public class MglaMemoryConfig {

    public static final String FEATURE_ID = "ai_memory";

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_AUTO = "auto";

    private static final String KEY_ENABLED = "memory_enabled";
    private static final String KEY_PROVIDER = "memory_provider";
    private static final String KEY_INTERVAL_MIN = "memory_interval_min";
    private static final String KEY_WATCHED = "memory_watched";
    private static final String KEY_BATCH_SIZE = "memory_batch_size";
    private static final String KEY_MAX_CHARS = "memory_max_chars";
    private static final String KEY_AI_DATE = "memory_ai_date";
    private static final String KEY_AI_COUNT = "memory_ai_count";

    public static final int AI_DAILY_LIMIT = 30;
    public static final int DEFAULT_INTERVAL_MIN = 240;
    public static final int DEFAULT_BATCH_SIZE = 60;
    public static final int DEFAULT_MAX_CHARS = 12000;
    /** Максимум чатов-источников на аккаунт. */
    public static final int MAX_WATCHED = 7;

    private MglaMemoryConfig() {
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(MglaChatDna.PREFS, Context.MODE_PRIVATE);
    }

    /** Функция доступна по фичефлагу хаба (не путать с подпиской). */
    public static boolean isActive() {
        return MglaFeatureFlags.isAllowed(FEATURE_ID);
    }

    /** Сырой переключатель в настройках (по умолчанию выкл). */
    public static boolean isToggleOn() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    /**
     * Живая память реально работает на текущем аккаунте:
     * фичефлаг + переключатель + любая платная подписка на этом аккаунте.
     */
    public static boolean isEnabled() {
        return isEnabledForAccount(UserConfig.selectedAccount);
    }

    public static boolean isEnabledForAccount(int account) {
        return isActive()
                && isToggleOn()
                && MglaSubscription.canUseLiveMemoryForAccount(account);
    }

    public static void setEnabled(boolean enabled) {
        if (enabled && !MglaSubscription.canUseLiveMemory()) {
            enabled = false;
        }
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Сбрасывает переключатель, если ни на одном аккаунте нет платной подписки.
     * Вызывать только после реальной потери плана ({@link MglaSubscription}),
     * не на каждом resume — до синка хаба планы читаются как free и ложно сбрасывали «Вкл».
     */
    public static void clearIfUnauthorized() {
        if (!isToggleOn()) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (MglaSubscription.canUseLiveMemoryForAccount(a)) {
                return;
            }
        }
        prefs().edit().putBoolean(KEY_ENABLED, false).apply();
    }

    public static String getProvider() {
        return prefs().getString(KEY_PROVIDER, PROVIDER_AUTO);
    }

    public static void setProvider(String provider) {
        prefs().edit().putString(KEY_PROVIDER, provider).apply();
    }

    public static String getProviderTitle() {
        switch (getProvider()) {
            case PROVIDER_GEMINI: return "Gemini (Ваш API)";
            case PROVIDER_OPENROUTER: return "OpenRouter / хаб";
            default: return "Авто (Gemini → OpenRouter → локально)";
        }
    }

    /** Минимальный интервал между заходами экстрактора. */
    public static int getIntervalMin() {
        return prefs().getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN);
    }

    public static void setIntervalMin(int minutes) {
        prefs().edit().putInt(KEY_INTERVAL_MIN, minutes).apply();
    }

    public static String getIntervalTitle() {
        switch (getIntervalMin()) {
            case 15: return "Каждые 15 минут";
            case 60: return "Каждый час";
            case 1440: return "Раз в сутки";
            default: return "Каждые 4 часа";
        }
    }

    public static int getBatchSize() {
        return prefs().getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE);
    }

    public static int getMaxChars() {
        return prefs().getInt(KEY_MAX_CHARS, DEFAULT_MAX_CHARS);
    }

    // region watched chats ("account:uid")

    private static Set<String> watchedSet() {
        return new HashSet<>(prefs().getStringSet(KEY_WATCHED, new HashSet<>()));
    }

    private static void saveWatched(Set<String> set) {
        prefs().edit().putStringSet(KEY_WATCHED, set).apply();
    }

    public static boolean isWatched(int account, long uid) {
        return watchedSet().contains(account + ":" + uid);
    }

    public static void addWatched(int account, long uid) {
        if (isWatched(account, uid)) {
            return;
        }
        if (watchedForAccount(account).size() >= MAX_WATCHED) {
            return;
        }
        Set<String> set = watchedSet();
        if (set.add(account + ":" + uid)) {
            saveWatched(set);
        }
    }

    /** Сколько ещё можно добавить источников для аккаунта. */
    public static int remainingWatchSlots(int account) {
        return Math.max(0, MAX_WATCHED - watchedForAccount(account).size());
    }

    public static void removeWatched(int account, long uid) {
        Set<String> set = watchedSet();
        if (set.remove(account + ":" + uid)) {
            saveWatched(set);
        }
    }

    public static void toggleWatched(int account, long uid) {
        if (isWatched(account, uid)) {
            removeWatched(account, uid);
        } else {
            addWatched(account, uid);
        }
    }

    public static int watchedCount() {
        return watchedSet().size();
    }

    /** Разобранный список watched-чатов для аккаунта. */
    public static ArrayList<Long> watchedForAccount(int account) {
        ArrayList<Long> result = new ArrayList<>();
        String prefix = account + ":";
        for (String entry : watchedSet()) {
            if (entry.startsWith(prefix)) {
                try {
                    result.add(Long.parseLong(entry.substring(prefix.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return result;
    }

    // endregion

    // region AI quota (отдельная от чат-ассистента)

    public static int getUsedToday() {
        SharedPreferences p = prefs();
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (today.equals(p.getString(KEY_AI_DATE, ""))) {
            return p.getInt(KEY_AI_COUNT, 0);
        }
        return 0;
    }

    /** @return true — квота расходуется. */
    public static boolean consumeAiQuota() {
        SharedPreferences p = prefs();
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        SharedPreferences.Editor edit = p.edit();
        if (today.equals(p.getString(KEY_AI_DATE, ""))) {
            int used = p.getInt(KEY_AI_COUNT, 0);
            if (used >= AI_DAILY_LIMIT) {
                return false;
            }
            edit.putInt(KEY_AI_COUNT, used + 1);
        } else {
            edit.putString(KEY_AI_DATE, today);
            edit.putInt(KEY_AI_COUNT, 1);
        }
        edit.apply();
        return true;
    }

    /** Когда сбросится дневной лимит (для UI). */
    public static String getResetInLabel() {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        long diff = midnight.getTimeInMillis() - System.currentTimeMillis();
        int hours = (int) (diff / 3600000);
        int minutes = (int) ((diff % 3600000) / 60000);
        if (hours <= 0) {
            return "скоро";
        }
        return "через " + hours + " ч " + minutes + " мин";
    }

    // endregion

    /** Можно ли сейчас дёргать платный/облачный ИИ с учётом настройки провайдера и квоты. */
    public static boolean canUseCloudAi() {
        if (!isEnabled()) {
            return false;
        }
        String provider = getProvider();
        if (PROVIDER_OPENROUTER.equals(provider)) {
            return MglaHubHttp.isConfigured() || !TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY);
        }
        if (PROVIDER_GEMINI.equals(provider)) {
            return !TextUtils.isEmpty(prefs().getString("ai_transcribe_api_key", ""));
        }
        // auto
        return !TextUtils.isEmpty(prefs().getString("ai_transcribe_api_key", ""))
            || MglaHubHttp.isConfigured()
            || !TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY);
    }
}
