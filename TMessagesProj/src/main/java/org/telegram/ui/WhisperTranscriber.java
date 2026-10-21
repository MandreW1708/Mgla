package org.telegram.ui;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MglaWhisperModelsManager;
import org.telegram.messenger.MglaWhisperNative;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Локальная расшифровка голосовых/видеозаметок через whisper.cpp.
 */
public class WhisperTranscriber {

    private static final Object lock = new Object();
    private static long cachedHandle;
    private static String cachedModelPath;

    public static void transcribe(MessageObject messageObject, int account, GeminiTranscriber.Callback callback) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (!MglaWhisperNative.isAvailable()) {
            notifyError(callback, "Локальный Whisper недоступен на этом устройстве");
            return;
        }
        File model = MglaWhisperModelsManager.getLoadedModel(ctx);
        if (model == null) {
            notifyError(callback, "Скачайте модель Whisper в настройках");
            return;
        }
        File audioFile = getAudioFile(messageObject);
        if (audioFile == null || !audioFile.exists()) {
            notifyError(callback, "Файл не найден");
            return;
        }
        final String language = MglaWhisperModelsManager.getLanguage();
        final String modelPath = model.getAbsolutePath();
        // Warm model in parallel with Opus decode — first call used to serialize both.
        preload(modelPath);
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                float[] pcm = decodeToPcm16kMono(audioFile);
                if (pcm == null || pcm.length == 0) {
                    throw new Exception("Не удалось декодировать аудио");
                }
                long tDecode = System.currentTimeMillis();
                String text;
                synchronized (lock) {
                    long handle = ensureModelLocked(modelPath);
                    if (handle == 0) {
                        throw new Exception("Не удалось загрузить модель Whisper");
                    }
                    long tModel = System.currentTimeMillis();
                    text = MglaWhisperNative.transcribe(handle, pcm, language, 0);
                    FileLog.d("WhisperTranscribe decode=" + (tDecode - t0) + "ms modelReady="
                        + (tModel - tDecode) + "ms infer=" + (System.currentTimeMillis() - tModel)
                        + "ms samples=" + pcm.length);
                }
                if (text == null) {
                    throw new Exception("Ошибка распознавания Whisper");
                }
                final String result = text.trim();
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) {
                        callback.onSuccess(result);
                    }
                });
            } catch (Exception e) {
                FileLog.e("WhisperTranscriber", e);
                notifyError(callback, e.getMessage() != null ? e.getMessage() : "Ошибка расшифровки");
            }
        }, "MglaWhisperTranscribe").start();
    }

    /** Preload selected model into RAM so the first tap isn't cold. */
    public static void preloadSelected() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || !MglaWhisperNative.isAvailable()) {
            return;
        }
        File model = MglaWhisperModelsManager.getLoadedModel(ctx);
        if (model == null) {
            return;
        }
        preload(model.getAbsolutePath());
    }

    private static void preload(String modelPath) {
        if (modelPath == null) {
            return;
        }
        new Thread(() -> {
            synchronized (lock) {
                ensureModelLocked(modelPath);
            }
        }, "MglaWhisperPreload").start();
    }

    private static long ensureModelLocked(String modelPath) {
        if (cachedHandle != 0 && modelPath.equals(cachedModelPath)) {
            return cachedHandle;
        }
        if (cachedHandle != 0) {
            MglaWhisperNative.free(cachedHandle);
            cachedHandle = 0;
            cachedModelPath = null;
        }
        cachedHandle = MglaWhisperNative.init(modelPath);
        if (cachedHandle != 0) {
            cachedModelPath = modelPath;
        }
        return cachedHandle;
    }

    private static void notifyError(GeminiTranscriber.Callback callback, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private static File getAudioFile(MessageObject msg) {
        File file = null;
        if (!TextUtils.isEmpty(msg.messageOwner.attachPath)) {
            file = new File(msg.messageOwner.attachPath);
        }
        if (file == null || !file.exists()) {
            file = FileLoader.getInstance(msg.currentAccount).getPathToMessage(msg.messageOwner);
        }
        return file;
    }

    /** Декодирует файл в mono float PCM 16 kHz для whisper. */
    static float[] decodeToPcm16kMono(File file) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".ogg") || name.endsWith(".opus") || !name.contains(".")) {
            float[] opus = MglaWhisperNative.decodeOpusToPcm16k(file.getAbsolutePath());
            if (opus != null && opus.length > 0) {
                return opus;
            }
        }
        if (name.endsWith(".wav")) {
            float[] wav = decodeWavMono(file);
            if (wav != null) {
                return resample(wav, guessWavSampleRate(file), 16000);
            }
        }
        return decodeWithMediaCodec(file);
    }

    private static float[] decodeWithMediaCodec(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getAbsolutePath());
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0 || format == null) {
            extractor.release();
            throw new Exception("Аудиодорожка не найдена");
        }
        extractor.selectTrack(track);
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        ArrayList<Float> mono = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        ByteBuffer[] inputBuffers = null;
        ByteBuffer[] outputBuffers = null;
        if (android.os.Build.VERSION.SDK_INT < 21) {
            inputBuffers = codec.getInputBuffers();
            outputBuffers = codec.getOutputBuffers();
        }

        while (!outputDone) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inputBuffer = android.os.Build.VERSION.SDK_INT >= 21
                        ? codec.getInputBuffer(inIndex) : inputBuffers[inIndex];
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (android.os.Build.VERSION.SDK_INT < 21) {
                    outputBuffers = codec.getOutputBuffers();
                }
            } else if (outIndex >= 0) {
                ByteBuffer outputBuffer = android.os.Build.VERSION.SDK_INT >= 21
                    ? codec.getOutputBuffer(outIndex) : outputBuffers[outIndex];
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    // PCM 16-bit
                    while (outputBuffer.remaining() >= 2 * channels) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            short s = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).getShort();
                            sum += s / 32768.0f;
                        }
                        mono.add(sum / channels);
                    }
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
        codec.stop();
        codec.release();
        extractor.release();

        float[] pcm = new float[mono.size()];
        for (int i = 0; i < mono.size(); i++) {
            pcm[i] = mono.get(i);
        }
        return resample(pcm, sampleRate, 16000);
    }

    private static int guessWavSampleRate(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] hdr = new byte[44];
            if (in.read(hdr) < 24) {
                return 16000;
            }
            return ByteBuffer.wrap(hdr, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        } catch (Throwable ignore) {
            return 16000;
        }
    }

    private static float[] decodeWavMono(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] hdr = new byte[44];
            if (in.read(hdr) < 44) {
                return null;
            }
            if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F') {
                return null;
            }
            int channels = ByteBuffer.wrap(hdr, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
            int bits = ByteBuffer.wrap(hdr, 34, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
            if (bits != 16) {
                return null;
            }
            byte[] data = new byte[(int) (file.length() - 44)];
            int read = in.read(data);
            if (read <= 0) {
                return new float[0];
            }
            ByteBuffer buf = ByteBuffer.wrap(data, 0, read).order(ByteOrder.LITTLE_ENDIAN);
            int frames = read / (2 * Math.max(1, channels));
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += buf.getShort() / 32768.0f;
                }
                mono[i] = sum / channels;
            }
            return mono;
        }
    }

    private static float[] resample(float[] input, int srcRate, int dstRate) {
        if (input == null || input.length == 0 || srcRate <= 0) {
            return input;
        }
        if (srcRate == dstRate) {
            return input;
        }
        int dstLen = (int) ((long) input.length * dstRate / srcRate);
        if (dstLen <= 0) {
            return new float[0];
        }
        float[] out = new float[dstLen];
        double ratio = (double) srcRate / dstRate;
        for (int i = 0; i < dstLen; i++) {
            double srcPos = i * ratio;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, input.length - 1);
            double t = srcPos - i0;
            out[i] = (float) (input[i0] * (1.0 - t) + input[i1] * t);
        }
        return out;
    }
}
