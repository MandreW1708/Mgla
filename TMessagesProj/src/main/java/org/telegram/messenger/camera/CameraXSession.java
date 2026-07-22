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
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.util.concurrent.ExecutionException;
import androidx.lifecycle.ProcessLifecycleOwner;

public class CameraXSession {

    public String cameraId;
    private boolean isInitiated;
    private boolean isFrontFace;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Camera secondaryCamera;
    private Preview preview;
    private ImageCapture imageCapture;

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
        // Video capture state handling
    }

    public void setScanningBarcode(boolean optimize) {
        // Barcode optimization state
    }

    public void setZoom(float zoom) {
        if (camera != null) {
            CameraControl control = camera.getCameraControl();
            control.setLinearZoom(zoom);
        }
    }

    public void focusToRect(Rect focusRect, Rect meteringRect) {
        // AutoFocus using CameraControl
    }

    public void destroy(boolean async, Runnable after) {
        if (cameraProvider != null) {
            if (SharedConfig.cameraXSeamlessSwitch && preview != null && imageCapture != null) {
                cameraProvider.unbind(preview, imageCapture);
            } else {
                cameraProvider.unbindAll();
            }
            cameraProvider = null;
        }
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
        Context context = ApplicationLoader.applicationContext;
        try {
            cameraProvider = ProcessCameraProvider.getInstance(context).get();
            
            // Apply CameraX settings from SharedConfig
            boolean startWide = SharedConfig.cameraXStartWide && !isFrontFace;
            CameraSelector cameraSelector;
            if (isFrontFace) {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
            } else {
                cameraSelector = startWide ? new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build() : CameraSelector.DEFAULT_BACK_CAMERA;
            }

            Preview.Builder previewBuilder = new Preview.Builder();

            // Configure 60 FPS range if enabled
            if (SharedConfig.cameraX60Fps) {
                previewBuilder.setTargetFrameRate(new Range<>(60, 60));
            }

            preview = previewBuilder.build();
            preview.setSurfaceProvider(request -> {
                Surface surface = new Surface(surfaceTexture);
                request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> {
                    surface.release();
                });
            });

            imageCapture = new ImageCapture.Builder().build();

            // Seamless switching (Dual camera active binding)
            if (SharedConfig.cameraXSeamlessSwitch) {
                try {
                    CameraSelector secondarySelector = isFrontFace ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                    if (cameraProvider.hasCamera(secondarySelector)) {
                        FileLog.d("CameraX: Seamless switching dual cameras pre-initialized");
                        // Pre-bind secondary camera to keep the hardware warm
                        cameraProvider.bindToLifecycle(ProcessLifecycleOwner.get(), secondarySelector);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else {
                cameraProvider.unbindAll();
            }

            camera = cameraProvider.bindToLifecycle(ProcessLifecycleOwner.get(), cameraSelector, preview, imageCapture);

            isInitiated = true;
            if (onInit != null) {
                onInit.run();
            }
        } catch (ExecutionException | InterruptedException e) {
            FileLog.e("CameraX init failed", e);
        }
    }

    public float getMinZoom() {
        return 0f;
    }

    public float getMaxZoom() {
        return 1f;
    }
}
