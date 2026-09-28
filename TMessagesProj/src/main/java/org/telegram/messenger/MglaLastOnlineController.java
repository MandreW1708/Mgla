package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MglaLastOnlineController {

    private static final String PREFS_NAME = "mgla_last_online";
    private static volatile MglaLastOnlineController[] Instance = new MglaLastOnlineController[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final ConcurrentHashMap<Long, Integer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pendingWrites = new ConcurrentHashMap<>();
    private final Runnable flushRunnable = this::flushPendingWrites;

    public static MglaLastOnlineController getInstance(int num) {
        if (num < 0 || num >= UserConfig.MAX_ACCOUNT_COUNT) {
            num = UserConfig.selectedAccount;
        }
        MglaLastOnlineController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (MglaLastOnlineController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new MglaLastOnlineController(num);
                }
            }
        }
        return localInstance;
    }

    private MglaLastOnlineController(int account) {
        this.currentAccount = account;
        loadFromPrefs();
    }

    private SharedPreferences getPrefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadFromPrefs() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            return;
        }
        Map<String, ?> all = prefs.getAll();
        if (all == null || all.isEmpty()) {
            return;
        }
        int minValidTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - 60 * 86400;
        boolean hasPruned = false;
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            try {
                long uid = Long.parseLong(entry.getKey());
                if (entry.getValue() instanceof Integer) {
                    int val = (Integer) entry.getValue();
                    if (val >= minValidTime) {
                        cache.put(uid, val);
                    } else {
                        if (editor == null) {
                            editor = prefs.edit();
                        }
                        editor.remove(entry.getKey());
                        hasPruned = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (hasPruned && editor != null) {
            editor.apply();
        }
    }

    public int getLastOnline(long userId) {
        if (userId <= 0) {
            return 0;
        }
        Integer time = cache.get(userId);
        return time != null ? time : 0;
    }

    public void setLastOnline(long userId, int time) {
        if (userId <= 0 || time <= 0) {
            return;
        }
        int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        if (time > now) {
            time = now;
        }
        Integer existing = cache.get(userId);
        if (existing != null && existing >= time) {
            return;
        }
        cache.put(userId, time);
        saveDebounced(userId, time);
    }

    private void saveDebounced(long userId, int time) {
        pendingWrites.put(String.valueOf(userId), time);
        AndroidUtilities.cancelRunOnUIThread(flushRunnable);
        AndroidUtilities.runOnUIThread(flushRunnable, 500);
    }

    private void flushPendingWrites() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Integer> entry : pendingWrites.entrySet()) {
                editor.putInt(entry.getKey(), entry.getValue());
            }
            pendingWrites.clear();
            editor.apply();
        });
    }

    public void clearAll() {
        cache.clear();
        pendingWrites.clear();
        AndroidUtilities.cancelRunOnUIThread(flushRunnable);
        Utilities.globalQueue.postRunnable(() -> {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                prefs.edit().clear().apply();
            }
        });
    }
}
