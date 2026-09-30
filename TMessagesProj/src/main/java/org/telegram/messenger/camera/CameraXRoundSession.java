package org.telegram.messenger.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

/**
 * CameraX-сессия для записи круглых видеосообщений (InstantCameraView).
 *
 * Кадры отдаются в SurfaceTexture GL-пайплайна Telegram — сама запись идёт штатным
 * GL-энкодером (как у Camera1/Camera2), результат идентичен стоковым вариантам.
 *
 * Безопасность: одна камера за раз, целевое разрешение фиксируется до бинда
 * (никаких пересозданий поверхности на лету), при закрытии — unbindAll.
 */
public class CameraXRoundSession implements RoundCameraSession {

    private static final int PREVIEW_WIDTH = 1920;
    private static final int PREVIEW_HEIGHT = 1080;

    private final boolean isFront;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview preview;
    private SurfaceTexture surfaceTexture;

    private volatile boolean isInitiated;
    private boolean isError;
    private volatile boolean highFpsActive;

    private float maxZoom = 1f;

    private Utilities.Callback<Size> onPreviewSizeCallback;

    public CameraXRoundSession(boolean front) {
        this.isFront = front;
    }

    /** вызывается на главном потоке, если CameraX выбрал разрешение, отличное от дефолтного */
    public void setOnPreviewSizeCallback(Utilities.Callback<Size> callback) {
        onPreviewSizeCallback = callback;
    }

    @Override
    public boolean isInitiated() {
        return isInitiated && !isError;
    }

    @Override
    public void open(SurfaceTexture surfaceTexture) {
        this.surfaceTexture = surfaceTexture;
        Context context = ApplicationLoader.applicationContext;
        if (cameraProvider == null) {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(context).get();
            } catch (Throwable e) {
                FileLog.e("CameraXRound provider init failed", e);
                isError = true;
                return;
            }
        }
        // лесенка конфигураций: сначала 60 fps с фиксированным разрешением,
        // затем 60 fps с автоматическим разрешением (многие устройства держат 60 не на всех),
        // и только потом дефолтные 30. 60 fps пробуем, только если выбранная камера их умеет —
        // иначе CameraX может открыть поток молча в 30.
        boolean want60 = SharedConfig.cameraX60Fps && isHighFpsSupported();
        boolean[][] attempts = want60 ? new boolean[][]{{true, true}, {true, false}, {false, true}} : new boolean[][]{{false, true}};
        for (int i = 0; i < attempts.length; i++) {
            try {
                bind(attempts[i][0], attempts[i][1]);
                isInitiated = true;
                return;
            } catch (Throwable e) {
                FileLog.e("CameraXRound bind attempt " + i + " failed", e);
            }
        }
        isError = true;
    }

    /** умеет ли выбранная камера 60 fps превью (по AE_TARGET_FPS_RANGES) */
    private boolean isHighFpsSupported() {
        try {
            for (CameraInfo info : cameraProvider.getAvailableCameraInfos()) {
                if (info.getLensFacing() != (isFront ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK)) {
                    continue;
                }
                try {
                    android.util.Range<Integer>[] ranges = Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (ranges != null) {
                        for (android.util.Range<Integer> range : ranges) {
                            if (range != null && range.getUpper() != null && range.getUpper() >= 60) {
                                return true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            FileLog.e("CameraXRound fps ranges check failed", e);
        }
        return false;
    }

    /** удалось ли открыть поток в 60 fps (опция «60 кадров в секунду при записи») */
    public boolean isHighFps() {
        return highFpsActive;
    }

    private void bind(boolean fps60, boolean fixedResolution) {
        Context context = ApplicationLoader.applicationContext;

        Preview.Builder previewBuilder = new Preview.Builder();
        if (fixedResolution) {
            previewBuilder.setTargetResolution(new Size(PREVIEW_WIDTH, PREVIEW_HEIGHT));
        }
        if (fps60) {
            previewBuilder.setTargetFrameRate(new Range<>(60, 60));
        }
        preview = previewBuilder.build();
        preview.setSurfaceProvider(request -> {
            SurfaceTexture texture = CameraXRoundSession.this.surfaceTexture;
            if (texture == null) {
                try {
                    request.willNotProvideSurface();
                } catch (Throwable ignored) {
                }
                return;
            }
            try {
                Size resolution = new Size(request.getResolution().getWidth(), request.getResolution().getHeight());
                if (resolution.getWidth() <= 0 || resolution.getHeight() <= 0) {
                    request.willNotProvideSurface();
                    return;
                }
                texture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
                Surface surface = new Surface(texture);
                request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> surface.release());
                if ((resolution.getWidth() != PREVIEW_WIDTH || resolution.getHeight() != PREVIEW_HEIGHT) && onPreviewSizeCallback != null) {
                    onPreviewSizeCallback.run(resolution);
                }
            } catch (Throwable e) {
                FileLog.e("CameraXRound provideSurface failed", e);
                try {
                    request.willNotProvideSurface();
                } catch (Throwable ignored) {
                }
            }
        });

        CameraSelector cameraSelector = isFront ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(ProcessLifecycleOwner.get(), cameraSelector, preview);
        highFpsActive = fps60;

        try {
            maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        } catch (Throwable ignored) {
            maxZoom = 1f;
        }
    }

    @Override
    public int getPreviewWidth() {
        return PREVIEW_WIDTH;
    }

    @Override
    public int getPreviewHeight() {
        return PREVIEW_HEIGHT;
    }

    @Override
    public int getWorldAngle() {
        // CameraX отдаёт кадры, ориентированные по дисплею — дополнительный поворот не нужен
        return 0;
    }

    @Override
    public int getCurrentOrientation() {
        return 0;
    }

    @Override
    public int getDisplayOrientation() {
        return 0;
    }

    @Override
    public void setRecordingVideo(boolean recording) {
        // запись идёт через GL-энкодер, готовить поток не нужно
    }

    @Override
    public void setFlash(boolean flash) {
        // не поддерживается
    }

    @Override
    public float getMaxZoom() {
        return maxZoom;
    }

    @Override
    public float getMinZoom() {
        return 1f;
    }

    @Override
    public void setZoom(float value) {
        if (camera == null) {
            return;
        }
        try {
            camera.getCameraControl().setZoomRatio(Math.max(1f, Math.min(maxZoom, value)));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void destroy(boolean async) {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            cameraProvider = null;
        }
        camera = null;
        preview = null;
        isInitiated = false;
    }

    public void destroy(boolean async, Runnable after) {
        destroy(async);
        if (after != null) {
            if (async) {
                new Thread(after).start();
            } else {
                after.run();
            }
        }
    }
}
