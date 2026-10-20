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
import org.telegram.ui.WhisperTranscriber;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Запись с микрофона + локальный Whisper → текст для поля ввода.
 * Live гоняет только последние ~4 с (не весь буфер), финал — один быстрый проход.
 */
public class MglaWhisperDictationController {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);

        void onStateChanged(boolean recording, boolean processing);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int LIVE_INTERVAL_MS = 2200;
    private static final int LIVE_WINDOW_SEC = 4;
    private static final int MAX_SECONDS = 90;
    /** Короткий лимит токенов — 3 слова не должны декодировать как эссе. */
    private static final int DICTATION_MAX_TOKENS = 48;
    private static final int LIVE_MAX_TOKENS = 32;

    private final Object pcmLock = new Object();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean liveBusy = new AtomicBoolean(false);
    private final AtomicBoolean abortLive = new AtomicBoolean(false);

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
        abortLive.set(false);
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
        // Сразу рвём live — иначе финал ждёт длинный проход по старому буферу
        abortLive.set(true);
        recording.set(false);
        notifyState();
        Thread rt = recordThread;
        if (rt != null) {
            try {
                rt.join(800);
            } catch (InterruptedException ignore) {
            }
        }
        recordThread = null;
        liveThread = null;

        float[] pcm;
        int filled;
        synchronized (pcmLock) {
            filled = pcmFilled;
            if (filled <= SAMPLE_RATE / 5) {
                notifyError("Слишком короткая запись");
                notifyState();
                return;
            }
            pcm = new float[filled];
            System.arraycopy(pcmBuffer, 0, pcm, 0, filled);
        }

        // Короткая фраза + уже есть live-текст → не гоняем вторую полную расшифровку
        final float sec = filled / (float) SAMPLE_RATE;
        if (!TextUtils.isEmpty(lastPartial) && sec <= 5f) {
            waitLiveIdle(400);
            final String quick = lastPartial.trim();
            processing.set(false);
            notifyState();
            if (listener != null) {
                listener.onFinal(quick);
            }
            return;
        }

        waitLiveIdle(1500);

        processing.set(true);
        notifyState();
        final String language = MglaWhisperModelsManager.getLanguage();
        final File model = MglaWhisperModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
        final float[] trimmed = trimSilence(pcm);
        new Thread(() -> {
            try {
                if (model == null) {
                    throw new Exception("Модель не найдена");
                }
                String text = WhisperTranscriber.transcribePcm(trimmed, model.getAbsolutePath(), language, DICTATION_MAX_TOKENS);
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
        abortLive.set(true);
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

    private void waitLiveIdle(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (liveBusy.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                break;
            }
        }
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
        while (recording.get() && !abortLive.get()) {
            try {
                Thread.sleep(LIVE_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!recording.get() || abortLive.get() || liveBusy.get()) {
                continue;
            }
            float[] snapshot;
            synchronized (pcmLock) {
                if (pcmFilled < (int) (SAMPLE_RATE * 1.2f)) {
                    continue;
                }
                // Только хвост — полный буфер на каждом тике убивает скорость
                int window = SAMPLE_RATE * LIVE_WINDOW_SEC;
                int start = Math.max(0, pcmFilled - window);
                int len = pcmFilled - start;
                snapshot = new float[len];
                System.arraycopy(pcmBuffer, start, snapshot, 0, len);
            }
            if (abortLive.get() || !recording.get()) {
                break;
            }
            liveBusy.set(true);
            try {
                if (abortLive.get()) {
                    break;
                }
                File model = MglaWhisperModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
                if (model == null) {
                    continue;
                }
                String text = WhisperTranscriber.transcribePcm(snapshot, model.getAbsolutePath(),
                    MglaWhisperModelsManager.getLanguage(), LIVE_MAX_TOKENS);
                if (abortLive.get() || !recording.get()) {
                    break;
                }
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
                    if (listener != null && recording.get()) {
                        listener.onPartial(partial);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                liveBusy.set(false);
            }
        }
        liveBusy.set(false);
    }

    /** Обрезает тишину по краям — меньше samples → меньше audio_ctx. */
    private static float[] trimSilence(float[] pcm) {
        if (pcm == null || pcm.length < SAMPLE_RATE / 4) {
            return pcm;
        }
        final float thr = 0.012f;
        int start = 0;
        while (start < pcm.length && Math.abs(pcm[start]) < thr) {
            start++;
        }
        int end = pcm.length - 1;
        while (end > start && Math.abs(pcm[end]) < thr) {
            end--;
        }
        // Небольшой pad, чтобы не срезать начало слова
        start = Math.max(0, start - SAMPLE_RATE / 10);
        end = Math.min(pcm.length - 1, end + SAMPLE_RATE / 10);
        int len = end - start + 1;
        if (len >= pcm.length - SAMPLE_RATE / 5) {
            return pcm;
        }
        float[] out = new float[len];
        System.arraycopy(pcm, start, out, 0, len);
        return out;
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
