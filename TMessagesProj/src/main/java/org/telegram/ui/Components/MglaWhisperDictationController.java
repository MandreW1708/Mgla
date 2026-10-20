package org.telegram.ui.Components;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MglaWhisperDictationConfig;
import org.telegram.messenger.MglaWhisperModelsManager;
import org.telegram.messenger.MglaWhisperNative;
import org.telegram.ui.WhisperTranscriber;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Запись с микрофона + локальный Whisper → текст для поля ввода.
 * При включённом live каждые ~1.8 с гоняет инференс по накопленному PCM
 * и отдаёт промежуточный текст.
 */
public class MglaWhisperDictationController {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);

        void onStateChanged(boolean recording, boolean processing);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int LIVE_INTERVAL_MS = 1800;
    private static final int MAX_SECONDS = 90;

    private final Object pcmLock = new Object();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean liveBusy = new AtomicBoolean(false);

    private Listener listener;
    private Thread recordThread;
    private Thread liveThread;
    private float[] pcmBuffer = new float[SAMPLE_RATE * 8];
    private int pcmFilled;
    private String lastPartial = "";

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public boolean isBusy() {
        return recording.get() || processing.get();
    }

    public void toggle() {
        if (recording.get()) {
            stopAndTranscribe();
        } else {
            start();
        }
    }

    public void start() {
        if (recording.get() || processing.get()) {
            return;
        }
        if (!MglaWhisperDictationConfig.isEnabled()) {
            notifyError("Голосовой ввод выключен в настройках");
            return;
        }
        File model = MglaWhisperModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
        if (model == null) {
            notifyError("Скачайте модель Whisper в настройках");
            return;
        }
        WhisperTranscriber.preloadSelected();
        synchronized (pcmLock) {
            pcmFilled = 0;
        }
        lastPartial = "";
        recording.set(true);
        notifyState();
        recordThread = new Thread(this::recordLoop, "MglaWhisperDictationRecord");
        recordThread.start();
        if (MglaWhisperDictationConfig.isLiveEnabled()) {
            liveThread = new Thread(this::liveLoop, "MglaWhisperDictationLive");
            liveThread.start();
        }
    }

    public void stopAndTranscribe() {
        if (!recording.get()) {
            return;
        }
        recording.set(false);
        notifyState();
        Thread rt = recordThread;
        if (rt != null) {
            try {
                rt.join(1500);
            } catch (InterruptedException ignore) {
            }
        }
        recordThread = null;
        liveThread = null;

        float[] pcm;
        synchronized (pcmLock) {
            if (pcmFilled <= SAMPLE_RATE / 5) {
                notifyError("Слишком короткая запись");
                notifyState();
                return;
            }
            pcm = new float[pcmFilled];
            System.arraycopy(pcmBuffer, 0, pcm, 0, pcmFilled);
        }
        processing.set(true);
        notifyState();
        final String language = MglaWhisperModelsManager.getLanguage();
        final File model = MglaWhisperModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
        new Thread(() -> {
            try {
                if (model == null) {
                    throw new Exception("Модель не найдена");
                }
                String text = WhisperTranscriber.transcribePcm(pcm, model.getAbsolutePath(), language);
                final String result = text == null ? "" : text.trim();
                AndroidUtilities.runOnUIThread(() -> {
                    processing.set(false);
                    notifyState();
                    if (listener != null) {
                        listener.onFinal(result);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    processing.set(false);
                    notifyState();
                    notifyError(e.getMessage() != null ? e.getMessage() : "Ошибка расшифровки");
                });
            }
        }, "MglaWhisperDictationFinal").start();
    }

    public void cancel() {
        recording.set(false);
        processing.set(false);
        recordThread = null;
        liveThread = null;
        lastPartial = "";
        synchronized (pcmLock) {
            pcmFilled = 0;
        }
        notifyState();
    }

    private void recordLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = SAMPLE_RATE;
        }
        AudioRecord recorder = null;
        try {
            int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
            recorder = new AudioRecord(source, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, SAMPLE_RATE));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                try {
                    recorder.release();
                } catch (Throwable ignore) {
                }
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, SAMPLE_RATE));
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new Exception("Не удалось открыть микрофон");
            }
            recorder.startRecording();
            short[] buf = new short[2048];
            int maxSamples = SAMPLE_RATE * MAX_SECONDS;
            while (recording.get()) {
                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) {
                    continue;
                }
                synchronized (pcmLock) {
                    ensureCapacity(pcmFilled + n);
                    for (int i = 0; i < n; i++) {
                        pcmBuffer[pcmFilled++] = buf[i] / 32768.0f;
                    }
                    if (pcmFilled >= maxSamples) {
                        recording.set(false);
                        AndroidUtilities.runOnUIThread(this::stopAndTranscribe);
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            recording.set(false);
            AndroidUtilities.runOnUIThread(() -> {
                notifyState();
                notifyError(e.getMessage() != null ? e.getMessage() : "Ошибка записи");
            });
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Throwable ignore) {
                }
                try {
                    recorder.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private void liveLoop() {
        while (recording.get()) {
            try {
                Thread.sleep(LIVE_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!recording.get() || liveBusy.get()) {
                continue;
            }
            float[] snapshot;
            synchronized (pcmLock) {
                if (pcmFilled < SAMPLE_RATE) { // need ≥1s
                    continue;
                }
                snapshot = new float[pcmFilled];
                System.arraycopy(pcmBuffer, 0, snapshot, 0, pcmFilled);
            }
            liveBusy.set(true);
            try {
                File model = MglaWhisperModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
                if (model == null) {
                    continue;
                }
                String text = WhisperTranscriber.transcribePcm(snapshot, model.getAbsolutePath(),
                    MglaWhisperModelsManager.getLanguage());
                if (text == null) {
                    continue;
                }
                text = text.trim();
                if (TextUtils.isEmpty(text) || text.equals(lastPartial)) {
                    continue;
                }
                lastPartial = text;
                final String partial = text;
                AndroidUtilities.runOnUIThread(() -> {
                    if (listener != null && (recording.get() || processing.get())) {
                        listener.onPartial(partial);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                liveBusy.set(false);
            }
        }
    }

    private void ensureCapacity(int need) {
        if (need <= pcmBuffer.length) {
            return;
        }
        int ncap = Math.max(need, pcmBuffer.length * 2);
        float[] nbuf = new float[ncap];
        System.arraycopy(pcmBuffer, 0, nbuf, 0, pcmFilled);
        pcmBuffer = nbuf;
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) {
            AndroidUtilities.runOnUIThread(() -> l.onStateChanged(recording.get(), processing.get()));
        }
    }

    private void notifyError(String msg) {
        Listener l = listener;
        if (l != null) {
            AndroidUtilities.runOnUIThread(() -> l.onError(msg));
        }
    }
}
