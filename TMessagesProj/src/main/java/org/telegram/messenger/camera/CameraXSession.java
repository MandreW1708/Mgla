package org.telegram.messenger.camera;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.util.Range;
import android.view.Surface;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * CameraX-сессия для полной камеры (CameraView): только превью + фото.
 * Видео пишется через GL-энкодер Telegram, поэтому VideoCapture-use case не нужен.
 * Единственная расширенная опция — 60 fps превью (SharedConfig.cameraX60Fps).
 */
public class CameraXSession {

    public String cameraId;
    private boolean isInitiated;
    private boolean isFrontFace;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview preview;
    private ImageCapture imageCapture;

    private SurfaceTexture surfaceTexture;

    private int displayOrientation;
    private int currentOrientation;
    private int worldAngle;

    public CameraXSession(String cameraId, boolean isFrontFace) {
        this.cameraId = cameraId;
        this.isFrontFace = isFrontFace;
    }

    public boolean isInitiated() {
        return isInitiated;
    }

    public int getWorldAngle() {
        return worldAngle;
    }

    public int getCurrentOrientation() {
        return currentOrientation;
    }

    public int getDisplayOrientation() {
        return displayOrientation;
    }

    public void setRecordingVideo(boolean recording) {
        // видео пишется через GL-энкодер, ничего настраивать не надо
    }

    public void setScanningBarcode(boolean optimize) {
        // не поддерживается
    }

    public void setZoom(float linearZoom) {
        if (camera != null) {
            try {
                camera.getCameraControl().setLinearZoom(Math.max(0f, Math.min(1f, linearZoom)));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    public void focusToRect(Rect focusRect, Rect meteringRect) {
        // не поддерживается
    }

    public void destroy(boolean async, Runnable after) {
        if (cameraProvider != null) {
            // всегда отвязываем всё, иначе камера остаётся занятой после закрытия
            try {
                cameraProvider.unbindAll();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            cameraProvider = null;
        }
        camera = null;
        isInitiated = false;
        if (after != null) {
            if (async) {
                new Thread(after).start();
            } else {
                after.run();
            }
        }
    }

    public void open(SurfaceTexture surfaceTexture, Runnable onInit) {
        this.surfaceTexture = surfaceTexture;
        Context context = ApplicationLoader.applicationContext;
        try {
            cameraProvider = ProcessCameraProvider.getInstance(context).get();
        } catch (Throwable e) {
            FileLog.e("CameraX provider init failed", e);
            return;
        }
        try {
            bindWithConfig(SharedConfig.cameraX60Fps);
            isInitiated = true;
            if (onInit != null) {
                onInit.run();
            }
        } catch (Throwable e) {
            FileLog.e("CameraX init failed", e);
            // неподдерживаемое устройством ограничение (чаще всего 60 fps) — повторяем без него
            try {
                bindWithConfig(false);
                isInitiated = true;
                if (onInit != null) {
                    onInit.run();
                }
            } catch (Throwable e2) {
                FileLog.e("CameraX fallback init failed", e2);
            }
        }
    }

    private void bindWithConfig(boolean fps60) {
        Context context = ApplicationLoader.applicationContext;

        Preview.Builder previewBuilder = new Preview.Builder();
        if (fps60) {
            previewBuilder.setTargetFrameRate(new Range<>(60, 60));
        }
        preview = previewBuilder.build();
        preview.setSurfaceProvider(request -> {
            // поверхность пересоздаётся при поворотах/переоткрытиях — берём актуальную текстуру
            SurfaceTexture texture = CameraXSession.this.surfaceTexture;
            if (texture == null) {
                return;
            }
            try {
                texture.setDefaultBufferSize(request.getResolution().getWidth(), request.getResolution().getHeight());
                Surface surface = new Surface(texture);
                request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> surface.release());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });

        imageCapture = new ImageCapture.Builder().build();

        CameraSelector cameraSelector = isFrontFace ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(ProcessLifecycleOwner.get(), cameraSelector, preview, imageCapture);
    }

    public float getMinZoom() {
        return 0f;
    }

    public float getMaxZoom() {
        return 1f;
    }
}
