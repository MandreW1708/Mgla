package org.telegram.messenger.camera;

import android.graphics.SurfaceTexture;

/**
 * Общий контракт сессии камеры для записи круглых видеосообщений (InstantCameraView).
 * Реализации: стоковая {@link Camera2Session} и {@link CameraXRoundSession}.
 * Запись видео идёт через GL-энкодер Telegram: сессия только отдаёт кадры
 * в SurfaceTexture и сообщает свои параметры.
 */
public interface RoundCameraSession {

    /** камера открыта и поток кадров настроен */
    boolean isInitiated();

    /** привязать поток кадров к текстуре GL-пайплайна */
    void open(SurfaceTexture surfaceTexture);

    int getPreviewWidth();

    int getPreviewHeight();

    int getWorldAngle();

    int getCurrentOrientation();

    int getDisplayOrientation();

    /** сессия может подготовить поток под запись (у CameraX ничего делать не нужно) */
    void setRecordingVideo(boolean recording);

    /** фонарь; не обязательно к поддержке */
    void setFlash(boolean flash);

    float getMaxZoom();

    float getMinZoom();

    void setZoom(float value);

    void destroy(boolean async);
}
