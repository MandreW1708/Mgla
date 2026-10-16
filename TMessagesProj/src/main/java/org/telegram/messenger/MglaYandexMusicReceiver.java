package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Handles notification / headset media buttons for Yandex Music playback.
 */
public class MglaYandexMusicReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
        String action = intent.getAction();

        if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            if (intent.getExtras() == null) {
                return;
            }
            KeyEvent keyEvent = (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
            if (keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
                return;
            }
            switch (keyEvent.getKeyCode()) {
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    player.togglePlayPause();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    player.play();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    player.pause();
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    player.stop();
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    player.next();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    player.prev();
                    break;
            }
            return;
        }

        switch (action) {
            case MglaYandexMusicService.NOTIFY_PLAY:
                player.play();
                break;
            case MglaYandexMusicService.NOTIFY_PAUSE:
            case android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                player.pause();
                break;
            case MglaYandexMusicService.NOTIFY_NEXT:
                player.next();
                break;
            case MglaYandexMusicService.NOTIFY_PREVIOUS:
                player.prev();
                break;
            case MglaYandexMusicService.NOTIFY_CLOSE:
                player.stop();
                break;
        }
    }
}
