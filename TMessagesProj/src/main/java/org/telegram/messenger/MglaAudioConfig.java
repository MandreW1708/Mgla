package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

public class MglaAudioConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_AUTO_PAUSE = "audio_autopause";

    private static AudioManager.OnAudioFocusChangeListener standaloneListener;
    private static boolean standaloneFocusHeld;

    public static boolean isAutoPauseEnabled() {
        if (!MglaFeatureFlags.isAllowed(KEY_AUTO_PAUSE)) {
            return false;
        }
        return ApplicationLoader.applicationContext != null
            && prefs().getBoolean(KEY_AUTO_PAUSE, false);
    }

    public static void setAutoPauseEnabled(boolean enabled) {
        if (enabled && !MglaFeatureFlags.isAllowed(KEY_AUTO_PAUSE)) {
            enabled = false;
        }
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_AUTO_PAUSE, false) == enabled) {
            return;
        }
        p.edit().putBoolean(KEY_AUTO_PAUSE, enabled).apply();
        if (!enabled) {
            abandonStandaloneAutopauseFocus();
        }
    }

    public static boolean useTransientAutopause(MessageObject messageObject) {
        return isAutoPauseEnabled() && messageObject != null
            && (messageObject.isVoice() || messageObject.isRoundVideo() || messageObject.isVideo());
    }

    public static void requestStandaloneAutopauseFocus() {
        if (!isAutoPauseEnabled() || ApplicationLoader.applicationContext == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (standaloneListener == null) {
            standaloneListener = focusChange -> {};
        }
        if (!standaloneFocusHeld) {
            int result = audioManager.requestAudioFocus(standaloneListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            standaloneFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    public static void abandonStandaloneAutopauseFocus() {
        if (!standaloneFocusHeld || ApplicationLoader.applicationContext == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocus(standaloneListener);
        standaloneFocusHeld = false;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
