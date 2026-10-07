package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.biometric.BiometricManager;

import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class MglaHiddenChats {

    public static final int NOTIFY_SHOW = 0;
    public static final int NOTIFY_NO_CONTENT = 1;
    public static final int NOTIFY_OFF = 2;

    private static final String PREFS = "mgla_hidden_chats";
    private static final String KEY_IDS = "ids_";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_TYPE = "type";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_NOTIFY = "notify_mode";
    private static final String KEY_BAD_TRIES = "bad_tries";

    private static final Object lock = new Object();
    @SuppressWarnings("unchecked")
    private static final HashSet<Long>[] hidden = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    private static boolean loaded;
    private static String passcodeHash = "";
    private static byte[] passcodeSalt = new byte[0];
    private static int passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
    private static boolean fingerprint = true;
    private static volatile int notifyMode = NOTIFY_NO_CONTENT;
    private static int badTries;
    private static long retryUntil;

    private MglaHiddenChats() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        synchronized (lock) {
            if (loaded) {
                return;
            }
            SharedPreferences p = prefs();
            for (int a = 0; a < hidden.length; a++) {
                HashSet<Long> set = new HashSet<>();
                Set<String> raw = p.getStringSet(KEY_IDS + a, null);
                if (raw != null) {
                    for (String s : raw) {
                        try {
                            set.add(Long.parseLong(s));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                hidden[a] = set;
            }
            passcodeHash = p.getString(KEY_HASH, "");
            String salt = p.getString(KEY_SALT, "");
            passcodeSalt = TextUtils.isEmpty(salt) ? new byte[0] : Utilities.hexToBytes(salt);
            passcodeType = p.getInt(KEY_TYPE, SharedConfig.PASSCODE_TYPE_PIN);
            fingerprint = p.getBoolean(KEY_FINGERPRINT, true);
            notifyMode = p.getInt(KEY_NOTIFY, NOTIFY_NO_CONTENT);
            badTries = p.getInt(KEY_BAD_TRIES, 0);
            loaded = true;
        }
    }

    private static void saveIds(int account) {
        HashSet<String> raw = new HashSet<>();
        for (Long id : hidden[account]) {
            raw.add(String.valueOf(id));
        }
        prefs().edit().putStringSet(KEY_IDS + account, raw).apply();
    }

    private static boolean validAccount(int account) {
        return account >= 0 && account < hidden.length;
    }

    public static boolean isHidden(int account, long dialogId) {
        if (dialogId == 0 || !validAccount(account)) {
            return false;
        }
        ensureLoaded();
        synchronized (lock) {
            return hidden[account].contains(dialogId);
        }
    }

    public static boolean hasHidden(int account) {
        if (!validAccount(account)) {
            return false;
        }
        ensureLoaded();
        synchronized (lock) {
            return !hidden[account].isEmpty();
        }
    }

    public static int getHiddenCount(int account) {
        if (!validAccount(account)) {
            return 0;
        }
        ensureLoaded();
        synchronized (lock) {
            return hidden[account].size();
        }
    }

    public static void setHidden(int account, ArrayList<Long> dialogIds, boolean hide) {
        if (!validAccount(account) || dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        ensureLoaded();
        synchronized (lock) {
            for (Long id : dialogIds) {
                if (id == null || id == 0) {
                    continue;
                }
                if (hide) {
                    hidden[account].add(id);
                } else {
                    hidden[account].remove(id);
                }
            }
            saveIds(account);
        }
        refreshUnreadCounters(account);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    private static void refreshUnreadCounters(int account) {
        if (!UserConfig.isValidAccount(account)) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            storage.resetAllUnreadCounters(false);
            NotificationsController.getInstance(account).updateBadge();
        });
    }

    public static long getDialogIdForObject(Object obj) {
        if (obj instanceof TLRPC.User) {
            return ((TLRPC.User) obj).id;
        } else if (obj instanceof TLRPC.Chat) {
            return -((TLRPC.Chat) obj).id;
        } else if (obj instanceof TLRPC.EncryptedChat) {
            return DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) obj).id);
        }
        return 0;
    }

    public static ArrayList<TLRPC.Dialog> filterOut(int account, ArrayList<TLRPC.Dialog> dialogs) {
        if (dialogs == null || dialogs.isEmpty() || !hasHidden(account)) {
            return dialogs;
        }
        boolean any = false;
        synchronized (lock) {
            HashSet<Long> set = hidden[account];
            for (int i = 0, n = dialogs.size(); i < n; i++) {
                if (set.contains(dialogs.get(i).id)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return dialogs;
            }
            ArrayList<TLRPC.Dialog> result = new ArrayList<>(dialogs.size());
            for (int i = 0, n = dialogs.size(); i < n; i++) {
                TLRPC.Dialog d = dialogs.get(i);
                if (!set.contains(d.id)) {
                    result.add(d);
                }
            }
            return result;
        }
    }

    public static ArrayList<TLRPC.Dialog> buildHiddenList(int account) {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        if (!hasHidden(account)) {
            return result;
        }
        ArrayList<TLRPC.Dialog> all = MessagesController.getInstance(account).getAllDialogs();
        synchronized (lock) {
            HashSet<Long> set = hidden[account];
            for (int i = 0, n = all.size(); i < n; i++) {
                TLRPC.Dialog d = all.get(i);
                if (!(d instanceof TLRPC.TL_dialogFolder) && set.contains(d.id)) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    public static boolean hasPasscode() {
        ensureLoaded();
        return !TextUtils.isEmpty(passcodeHash);
    }

    public static int getPasscodeType() {
        ensureLoaded();
        return passcodeType;
    }

    public static void setPasscode(String code, int type) {
        ensureLoaded();
        byte[] salt = new byte[16];
        Utilities.random.nextBytes(salt);
        String hash = computeHash(code, salt);
        synchronized (lock) {
            passcodeSalt = salt;
            passcodeHash = hash;
            passcodeType = type;
            badTries = 0;
            retryUntil = 0;
        }
        prefs().edit()
                .putString(KEY_HASH, hash)
                .putString(KEY_SALT, Utilities.bytesToHex(salt))
                .putInt(KEY_TYPE, type)
                .putInt(KEY_BAD_TRIES, 0)
                .apply();
    }

    private static String computeHash(String code, byte[] salt) {
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[salt.length * 2 + codeBytes.length];
        System.arraycopy(salt, 0, bytes, 0, salt.length);
        System.arraycopy(codeBytes, 0, bytes, salt.length, codeBytes.length);
        System.arraycopy(salt, 0, bytes, salt.length + codeBytes.length, salt.length);
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
    }

    public static long getRetryInMs() {
        long left = retryUntil - SystemClock.elapsedRealtime();
        return Math.max(0, left);
    }

    public static boolean checkPasscode(String code) {
        ensureLoaded();
        if (!hasPasscode() || code == null) {
            return false;
        }
        boolean ok = passcodeHash.equals(computeHash(code, passcodeSalt));
        if (ok) {
            onUnlocked();
        } else {
            badTries++;
            if (badTries >= 3) {
                long delay;
                switch (badTries) {
                    case 3: delay = 5000; break;
                    case 4: delay = 10000; break;
                    case 5: delay = 15000; break;
                    case 6: delay = 20000; break;
                    case 7: delay = 25000; break;
                    default: delay = 30000; break;
                }
                retryUntil = SystemClock.elapsedRealtime() + delay;
            }
            prefs().edit().putInt(KEY_BAD_TRIES, badTries).apply();
        }
        return ok;
    }

    public static void onUnlocked() {
        badTries = 0;
        retryUntil = 0;
        prefs().edit().putInt(KEY_BAD_TRIES, 0).apply();
    }

    public static boolean isFingerprintEnabled() {
        ensureLoaded();
        return fingerprint;
    }

    public static void setFingerprintEnabled(boolean enabled) {
        ensureLoaded();
        fingerprint = enabled;
        prefs().edit().putBoolean(KEY_FINGERPRINT, enabled).apply();
    }

    public static boolean isFingerprintAvailable() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            return BiometricManager.from(ApplicationLoader.applicationContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean canUseFingerprint() {
        return hasPasscode() && isFingerprintEnabled() && isFingerprintAvailable();
    }

    public static int getNotifyMode() {
        ensureLoaded();
        return notifyMode;
    }

    public static void setNotifyMode(int mode) {
        ensureLoaded();
        notifyMode = mode;
        prefs().edit().putInt(KEY_NOTIFY, mode).apply();
    }

    public static boolean isContentHidden(int account, long dialogId) {
        return getNotifyMode() == NOTIFY_NO_CONTENT && isHidden(account, dialogId);
    }

    public static boolean isNotificationsDisabled(int account, long dialogId) {
        return getNotifyMode() == NOTIFY_OFF && isHidden(account, dialogId);
    }

    public static void resetAll() {
        ensureLoaded();
        synchronized (lock) {
            for (HashSet<Long> set : hidden) {
                set.clear();
            }
            passcodeHash = "";
            passcodeSalt = new byte[0];
            passcodeType = SharedConfig.PASSCODE_TYPE_PIN;
            badTries = 0;
            retryUntil = 0;
        }
        SharedPreferences.Editor editor = prefs().edit();
        for (int a = 0; a < hidden.length; a++) {
            editor.remove(KEY_IDS + a);
        }
        editor.remove(KEY_HASH).remove(KEY_SALT).remove(KEY_TYPE).remove(KEY_BAD_TRIES).apply();
        for (int a = 0; a < hidden.length; a++) {
            if (UserConfig.isValidAccount(a)) {
                refreshUnreadCounters(a);
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
            }
        }
    }
}
