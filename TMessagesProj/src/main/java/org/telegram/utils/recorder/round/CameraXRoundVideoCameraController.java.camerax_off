package org.telegram.utils.recorder.round;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX Preview capture feeding the shared GL + MediaCodec round-video pipeline.
 * Does not use CameraX Recorder; encoding stays RoundVideoCodecRecorder.
 */
final class CameraXRoundVideoCameraController implements RoundVideoCameraBackend {

    private static final int PREVIEW_SHORT_SIDE = 720;
    private static final int MAXIMUM_SOURCE_SHORT_SIDE = 1088;
    private static final int MAXIMUM_SOURCE_LONG_SIDE = 1920;
    private static final int MAXIMUM_CROP_OVERSIZE_PERCENT = 15;
    private static final float MAXIMUM_UI_ZOOM_RATIO = 4f;
    // CameraX Preview timestamps can lag absolute A/V arming; keep headroom vs Camera2.
    private static final long COMMON_START_TIMEOUT_MS = 8_000L;

    private final Context context;
    private final CameraManager cameraManager;
    private final TextureView previewView;
    private final RoundVideoSession.OutputResolution outputResolution;
    private final int outputSize;
    private final int videoBitrate;
    private final RoundVideoSession.CameraResolution requestedCameraResolution;
    private final RoundVideoSession.FrameRate requestedFrameRate;
    private final boolean compositionEnabled;
    private final RoundVideoDiagnostics diagnostics;
    private final RoundVideoCameraBackend.Callback callback;
    private final Handler mainHandler;
    private final CameraLifecycleOwner lifecycleOwner = new CameraLifecycleOwner();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Executor cameraExecutor;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    private RoundVideoMp4Writer writer;
    private long timelineOffsetUs;
    private volatile RoundVideoSession.CameraFacing requestedFacing;
    private RoundVideoSession.CameraFacing activeFacing;
    private RoundVideoSession.CameraResolution activeCameraResolution;
    private RoundVideoSession.FrameRate activeFrameRate;
    private Size previewSize;
    private volatile Size recordingSize;
    private volatile int sourceCropSize;
    private float maximumZoomRatio = 1f;
    private volatile float zoomProgress;
    private boolean torchAvailable;
    private boolean torchEnabled;
    private boolean timestampRealtime;

    private Surface previewOutputSurface;
    private RoundVideoGlProcessor glProcessor;
    private RoundVideoCodecRecorder recorder;
    private RoundVideoCodecRecorder commonStartRecorder;

    private volatile boolean active;
    private volatile boolean stopping;
    private boolean opening;
    private boolean switchingCamera;
    private boolean notifySwitchCompletion;
    private volatile boolean awaitingSwitchPreviewFrame;
    private volatile boolean previewTransformEnabled;
    private long segmentStartedNs;
    private long cameraSwitchStartedNs;

    private final Runnable commonStartTimeoutRunnable = () -> {
        RoundVideoCodecRecorder pendingRecorder = commonStartRecorder;
        if (!active || stopping || pendingRecorder == null || recorder != pendingRecorder) return;
        commonStartRecorder = null;
        reportError(new IllegalStateException(
                "Timed out waiting for synchronized audio and video start"
        ));
    };

    private final View.OnLayoutChangeListener layoutChangeListener = (
            view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom
    ) -> updatePreviewTransform();

    CameraXRoundVideoCameraController(
            @NonNull Context context,
            @NonNull TextureView previewView,
            @NonNull RoundVideoSession.OutputResolution outputResolution,
            int videoBitrate,
            @NonNull RoundVideoSession.CameraResolution cameraResolution,
            @NonNull RoundVideoSession.FrameRate frameRate,
            boolean compositionEnabled,
            @NonNull RoundVideoDiagnostics diagnostics,
            @NonNull RoundVideoCameraBackend.Callback callback
    ) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.previewView = previewView;
        this.outputResolution = outputResolution;
        this.outputSize = outputResolution.getSize();
        this.videoBitrate = videoBitrate;
        this.requestedCameraResolution = cameraResolution;
        this.requestedFrameRate = frameRate;
        this.compositionEnabled = compositionEnabled;
        this.diagnostics = diagnostics;
        this.callback = callback;
        this.mainHandler = new Handler(android.os.Looper.getMainLooper());
        previewView.addOnLayoutChangeListener(layoutChangeListener);
    }

    @Override
    public void startSegment(
            @NonNull RoundVideoMp4Writer writer,
            long timelineOffsetUs,
            @NonNull RoundVideoSession.CameraFacing facing
    ) {
        ensureThread();
        this.writer = writer;
        this.timelineOffsetUs = timelineOffsetUs;
        requestedFacing = facing;
        active = true;
        stopping = false;
        commonStartRecorder = null;
        previewTransformEnabled = true;
        segmentStartedNs = SystemClock.elapsedRealtimeNanos();
        diagnostics.log("cameraX segment start: facing=" + facing
                + ", timelineOffsetUs=" + timelineOffsetUs
                + ", textureAvailable=" + previewView.isAvailable());
        ensureLifecycleResumed();
        previewView.setSurfaceTextureListener(previewListener);
        maybeOpenCamera();
    }

    @Override
    public boolean setCameraFacing(@NonNull RoundVideoSession.CameraFacing facing) {
        requestedFacing = facing;
        Handler handler = cameraHandler;
        if (!active || handler == null) return false;
        handler.post(() -> switchCameraIfNeeded(facing));
        return true;
    }

    @Override
    public boolean setZoom(float progress) {
        zoomProgress = clamp(progress, 0f, 1f);
        Handler handler = cameraHandler;
        if (!active || handler == null) return false;
        handler.post(this::applyZoom);
        return true;
    }

    @Override
    public boolean setTorchEnabled(boolean enabled) {
        Handler handler = cameraHandler;
        if (!active || handler == null) return false;
        handler.post(() -> {
            torchEnabled = enabled;
            applyTorch();
        });
        return true;
    }

    @Override
    public boolean stopSegment() {
        Handler handler = cameraHandler;
        if (!active || stopping || handler == null) return false;
        stopping = true;
        previewTransformEnabled = false;
        diagnostics.log("cameraX segment stop requested");
        handler.post(() -> {
            active = false;
            cancelCommonStartWait();
            if (recorder != null) {
                long stopPresentationTimeUs = recorder.requestStop();
                if (glProcessor != null && stopPresentationTimeUs != Long.MAX_VALUE) {
                    glProcessor.stopRecordingAt(stopPresentationTimeUs);
                }
            }
            unbindCamera();
            stopPipeline();
            stopping = false;
            callback.onRecordingStopped();
        });
        return true;
    }

    @Override
    public void release() {
        active = false;
        previewView.setSurfaceTextureListener(null);
        previewView.removeOnLayoutChangeListener(layoutChangeListener);
        Handler handler = cameraHandler;
        HandlerThread thread = cameraThread;
        cameraHandler = null;
        cameraThread = null;
        cameraExecutor = null;
        if (handler == null || thread == null) {
            destroyLifecycle();
            return;
        }
        handler.post(() -> {
            cancelCommonStartWait();
            if (recorder != null) {
                long stopPresentationTimeUs = recorder.requestStop();
                if (glProcessor != null && stopPresentationTimeUs != Long.MAX_VALUE) {
                    glProcessor.stopRecordingAt(stopPresentationTimeUs);
                }
            }
            unbindCamera();
            stopPipeline();
            destroyLifecycle();
            thread.quitSafely();
        });
    }

    private void ensureThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("RoundVideoCameraX");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = cameraHandler::post;
    }

    private void ensureLifecycleResumed() {
        mainHandler.post(() -> {
            try {
                lifecycleOwner.ensureResumed();
            } catch (RuntimeException e) {
                diagnostics.error("cameraX lifecycle resume failed", e);
            }
        });
    }

    private void destroyLifecycle() {
        mainHandler.post(() -> {
            try {
                lifecycleOwner.destroy();
            } catch (RuntimeException e) {
                diagnostics.error("cameraX lifecycle destroy failed", e);
            }
        });
    }

    private final TextureView.SurfaceTextureListener previewListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        @NonNull SurfaceTexture surfaceTexture,
                        int width,
                        int height
                ) {
                    diagnostics.log("cameraX preview surface available: view="
                            + width + "x" + height);
                    maybeOpenCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        @NonNull SurfaceTexture surfaceTexture,
                        int width,
                        int height
                ) {
                    updatePreviewTransform();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    diagnostics.log("cameraX preview surface destroyed: active=" + active);
                    if (active) {
                        reportError(new IllegalStateException(
                                "Preview SurfaceTexture was destroyed"
                        ));
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                    if (awaitingSwitchPreviewFrame) {
                        awaitingSwitchPreviewFrame = false;
                        diagnostics.log("cameraX switch first preview frame: elapsedMs="
                                + elapsedMs(cameraSwitchStartedNs));
                        callback.onCameraSwitchFirstFrame();
                    }
                }
            };

    private void maybeOpenCamera() {
        Handler handler = cameraHandler;
        if (!active || handler == null || !previewView.isAvailable()) return;
        handler.post(this::openCameraIfReady);
    }

    private void openCameraIfReady() {
        if (!active || opening || camera != null) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            reportError(new SecurityException("Camera permission is not granted"));
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            reportError(new SecurityException("Audio recording permission is not granted"));
            return;
        }
        opening = true;
        try {
            selectCameraAndSizes(requestedFacing);
            preparePreviewSurface();
            if (recorder == null) {
                createRecordingPipeline();
            } else if (glProcessor != null) {
                glProcessor.updateInputConfiguration(
                        recordingSize,
                        sourceCropSize,
                        timestampRealtime
                );
            }
            obtainProviderAndBind(false);
        } catch (Exception e) {
            opening = false;
            reportError(e);
        }
    }

    private void preparePreviewSurface() {
        SurfaceTexture previewTexture = previewView.getSurfaceTexture();
        if (previewTexture == null) {
            throw new IllegalStateException("Preview SurfaceTexture is unavailable");
        }
        int glPreviewSize = Math.max(
                1,
                Math.min(
                        PREVIEW_SHORT_SIDE,
                        Math.min(
                                Math.max(1, previewView.getWidth()),
                                Math.max(1, previewView.getHeight())
                        )
                )
        );
        previewTexture.setDefaultBufferSize(glPreviewSize, glPreviewSize);
        if (previewOutputSurface == null) {
            previewOutputSurface = new Surface(previewTexture);
        }
        previewView.post(this::updatePreviewTransform);
    }

    private void createRecordingPipeline() throws IOException {
        activeFrameRate = requestedFrameRate;
        recorder = new RoundVideoCodecRecorder(
                writer,
                timelineOffsetUs,
                outputSize,
                videoBitrate,
                requestedFrameRate.getValue(),
                diagnostics,
                callback::onError
        );
        Surface encoderSurface = recorder.prepare();
        glProcessor = new RoundVideoGlProcessor(
                recordingSize,
                encoderSurface,
                previewOutputSurface,
                false,
                outputSize,
                sourceCropSize,
                timestampRealtime,
                compositionEnabled,
                diagnostics,
                0,
                recorder,
                callback::onError
        );
        glProcessor.setFirstFrameListener(() -> {
            Handler handler = cameraHandler;
            if (handler != null) handler.post(this::startRecordingAfterFirstFrame);
        });
        glProcessor.start();
        diagnostics.log("cameraX recording pipeline ready: recording=" + recordingSize
                + ", crop=" + sourceCropSize
                + ", fps=" + requestedFrameRate.getValue()
                + ", composition=" + compositionEnabled);
    }

    private void obtainProviderAndBind(boolean cameraSwitch) {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        AtomicBoolean delivered = new AtomicBoolean();
        Runnable listener = () -> {
            if (!delivered.compareAndSet(false, true)) return;
            Handler handler = cameraHandler;
            if (handler == null) return;
            handler.post(() -> {
                try {
                    cameraProvider = future.get();
                    bindPreview(cameraSwitch);
                } catch (ExecutionException e) {
                    opening = false;
                    switchingCamera = false;
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    reportError(cause instanceof Exception
                            ? (Exception) cause
                            : new IllegalStateException(cause));
                } catch (InterruptedException e) {
                    opening = false;
                    switchingCamera = false;
                    Thread.currentThread().interrupt();
                    reportError(e);
                } catch (Exception e) {
                    opening = false;
                    switchingCamera = false;
                    reportError(e);
                }
            });
        };
        Executor executor = cameraExecutor;
        if (executor == null) {
            opening = false;
            return;
        }
        future.addListener(listener, executor);
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindPreview(boolean cameraSwitch) {
        if (!active || stopping) {
            opening = false;
            switchingCamera = false;
            return;
        }
        ProcessCameraProvider provider = cameraProvider;
        if (provider == null || glProcessor == null) {
            opening = false;
            switchingCamera = false;
            reportError(new IllegalStateException("CameraX provider or GL processor unavailable"));
            return;
        }

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(
                        requestedFacing == RoundVideoSession.CameraFacing.FRONT
                                ? CameraSelector.LENS_FACING_FRONT
                                : CameraSelector.LENS_FACING_BACK
                )
                .build();

        Preview.Builder previewBuilder = new Preview.Builder();
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        recordingSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                ))
                .build();
        previewBuilder.setResolutionSelector(resolutionSelector);
        // Do not force AE FPS via Camera2Interop: unsupported ranges can stall Preview
        // frames and trip the common A/V start timeout without a process crash.

        Preview preview = previewBuilder.build();
        Executor executor = cameraExecutor;
        if (executor == null) {
            opening = false;
            switchingCamera = false;
            return;
        }
        preview.setSurfaceProvider(executor, request -> {
            if (!active || stopping || glProcessor == null) {
                request.willNotProvideSurface();
                return;
            }
            Size requested = request.getResolution();
            if (requested != null
                    && (recordingSize == null
                    || requested.getWidth() != recordingSize.getWidth()
                    || requested.getHeight() != recordingSize.getHeight())) {
                diagnostics.log("cameraX surface resolution: requested=" + requested
                        + ", selected=" + recordingSize);
            }
            try {
                Surface cameraSurface = glProcessor.getCameraSurface();
                request.provideSurface(cameraSurface, executor, result -> {
                    diagnostics.log("cameraX surface result: " + result.getResultCode());
                });
            } catch (RuntimeException e) {
                request.willNotProvideSurface();
                reportError(e);
            }
        });

        // bindToLifecycle / unbindAll must run on the main thread.
        mainHandler.post(() -> {
            if (!active || stopping) {
                opening = false;
                switchingCamera = false;
                return;
            }
            try {
                lifecycleOwner.ensureResumed();
                try {
                    provider.unbindAll();
                } catch (RuntimeException e) {
                    diagnostics.log("cameraX unbind failed: " + e.getMessage());
                }
                camera = null;
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview);
                activeFacing = requestedFacing;
                refreshZoomAndTorchFromCamera();
                opening = false;
                boolean wasSwitch = cameraSwitch || notifySwitchCompletion;
                notifySwitchCompletion = false;
                switchingCamera = false;
                awaitingSwitchPreviewFrame = wasSwitch;
                if (glProcessor != null) {
                    glProcessor.onCameraSessionConfigured();
                }
                previewView.post(this::updatePreviewTransform);
                diagnostics.log("cameraX bound: facing=" + activeFacing
                        + ", recording=" + recordingSize
                        + ", crop=" + sourceCropSize
                        + ", maxZoom=" + maximumZoomRatio
                        + ", torch=" + torchAvailable
                        + ", switch=" + wasSwitch
                        + (wasSwitch
                        ? ", switchElapsedMs=" + elapsedMs(cameraSwitchStartedNs) : ""));
                callback.onConfigured(
                        new RoundVideoCameraBackend.CameraInfo(
                                activeFacing,
                                activeCameraResolution,
                                activeFrameRate,
                                previewSize,
                                recordingSize,
                                maximumZoomRatio,
                                torchAvailable
                        ),
                        wasSwitch
                );
            } catch (RuntimeException e) {
                opening = false;
                switchingCamera = false;
                camera = null;
                reportError(e);
            }
        });
    }

    private void switchCameraIfNeeded(@NonNull RoundVideoSession.CameraFacing facing) {
        if (!active || switchingCamera || facing == activeFacing) return;
        switchingCamera = true;
        notifySwitchCompletion = true;
        torchEnabled = false;
        zoomProgress = 0f;
        callback.onCameraSwitchStarted(facing);
        cameraSwitchStartedNs = SystemClock.elapsedRealtimeNanos();
        diagnostics.log("cameraX device switch started: from=" + activeFacing + ", to=" + facing);
        if (glProcessor != null) {
            glProcessor.beginCameraSwitch(activeFacing, facing);
        }
        try {
            selectCameraAndSizes(facing);
            if (glProcessor != null) {
                glProcessor.updateInputConfiguration(
                        recordingSize,
                        sourceCropSize,
                        timestampRealtime
                );
            }
            applyZoom();
            applyTorch();
            unbindCameraOnly();
            obtainProviderAndBind(true);
        } catch (Exception e) {
            switchingCamera = false;
            notifySwitchCompletion = false;
            reportError(e);
        }
    }

    private void startRecordingAfterFirstFrame() {
        if (!active || camera == null || recorder == null || recorder.isStarted()) return;
        try {
            RoundVideoCodecRecorder currentRecorder = recorder;
            commonStartRecorder = currentRecorder;
            Handler handler = cameraHandler;
            if (handler != null) {
                handler.removeCallbacks(commonStartTimeoutRunnable);
                handler.postDelayed(commonStartTimeoutRunnable, COMMON_START_TIMEOUT_MS);
            }
            currentRecorder.startRecording(
                    () -> postToCameraThread(
                            () -> startVideoAfterAudioReady(currentRecorder)
                    ),
                    () -> postToCameraThread(
                            () -> announceRecordingStarted(currentRecorder)
                    )
            );
            diagnostics.log("cameraX first frame; audio startup requested: segmentElapsedMs="
                    + elapsedMs(segmentStartedNs));
        } catch (RuntimeException e) {
            cancelCommonStartWait();
            reportError(e);
        }
    }

    private void startVideoAfterAudioReady(
            @NonNull RoundVideoCodecRecorder expectedRecorder
    ) {
        if (!active || stopping || recorder != expectedRecorder
                || camera == null || glProcessor == null) {
            return;
        }
        try {
            glProcessor.startRecording(expectedRecorder.getTimeOriginNs());
            diagnostics.log("cameraX common A/V start armed: segmentElapsedMs="
                    + elapsedMs(segmentStartedNs));
        } catch (RuntimeException e) {
            cancelCommonStartWait();
            reportError(e);
        }
    }

    private void announceRecordingStarted(
            @NonNull RoundVideoCodecRecorder expectedRecorder
    ) {
        if (!active || stopping || recorder != expectedRecorder) return;
        cancelCommonStartWait();
        diagnostics.log("cameraX common A/V start completed: segmentElapsedMs="
                + elapsedMs(segmentStartedNs));
        callback.onRecordingStarted();
    }

    private void postToCameraThread(@NonNull Runnable runnable) {
        Handler handler = cameraHandler;
        if (handler != null) handler.post(runnable);
    }

    private void cancelCommonStartWait() {
        Handler handler = cameraHandler;
        if (handler != null) handler.removeCallbacks(commonStartTimeoutRunnable);
        commonStartRecorder = null;
    }

    private void applyZoom() {
        Camera current = camera;
        if (current == null) return;
        try {
            float maxZoom = Math.min(maximumZoomRatio, MAXIMUM_UI_ZOOM_RATIO);
            float zoomRatio = 1f + zoomProgress * (Math.max(1f, maxZoom) - 1f);
            CameraControl control = current.getCameraControl();
            ListenableFuture<Void> future = control.setZoomRatio(zoomRatio);
            future.addListener(() -> {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    diagnostics.log("cameraX zoom failed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, cameraExecutor == null ? Runnable::run : cameraExecutor);
            diagnostics.log("cameraX zoom requested: progress=" + zoomProgress
                    + ", ratio=" + zoomRatio);
        } catch (RuntimeException e) {
            diagnostics.log("cameraX zoom unavailable: " + e.getMessage());
        }
    }

    private void applyTorch() {
        Camera current = camera;
        if (current == null) return;
        if (!torchAvailable) {
            if (torchEnabled) {
                diagnostics.log("cameraX torch ignored: unavailable");
            }
            return;
        }
        try {
            ListenableFuture<Void> future = current.getCameraControl().enableTorch(torchEnabled);
            future.addListener(() -> {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    diagnostics.log("cameraX torch failed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, cameraExecutor == null ? Runnable::run : cameraExecutor);
            diagnostics.log("cameraX torch requested: enabled=" + torchEnabled);
        } catch (RuntimeException e) {
            diagnostics.log("cameraX torch unavailable: " + e.getMessage());
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void refreshZoomAndTorchFromCamera() {
        Camera current = camera;
        if (current == null) return;
        try {
            androidx.camera.core.ZoomState zoomState =
                    current.getCameraInfo().getZoomState().getValue();
            if (zoomState != null) {
                maximumZoomRatio = clamp(zoomState.getMaxZoomRatio(), 1f, MAXIMUM_UI_ZOOM_RATIO);
            }
        } catch (RuntimeException e) {
            diagnostics.log("cameraX zoom state unavailable: " + e.getMessage());
        }
        try {
            torchAvailable = activeFacing == RoundVideoSession.CameraFacing.BACK
                    && current.getCameraInfo().hasFlashUnit();
        } catch (RuntimeException e) {
            torchAvailable = false;
        }
        applyZoom();
        applyTorch();
    }

    private void unbindCamera() {
        unbindCameraOnly();
    }

    private void unbindCameraOnly() {
        ProcessCameraProvider provider = cameraProvider;
        camera = null;
        if (provider == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                provider.unbindAll();
            } catch (RuntimeException e) {
                diagnostics.log("cameraX unbind failed: " + e.getMessage());
            }
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                provider.unbindAll();
            } catch (RuntimeException e) {
                diagnostics.log("cameraX unbind failed: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                diagnostics.log("cameraX unbind timed out waiting for main thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopPipeline() {
        switchingCamera = false;
        notifySwitchCompletion = false;
        awaitingSwitchPreviewFrame = false;
        previewTransformEnabled = false;
        opening = false;
        if (glProcessor != null) {
            glProcessor.stop();
            glProcessor = null;
        }
        if (previewOutputSurface != null) {
            previewOutputSurface.release();
            previewOutputSurface = null;
        }
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
    }

    private void updatePreviewTransform() {
        if (!previewTransformEnabled) return;
        previewView.setTransform(new Matrix());
    }

    private void selectCameraAndSizes(@NonNull RoundVideoSession.CameraFacing facing)
            throws Exception {
        int requiredFacing = facing == RoundVideoSession.CameraFacing.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        CameraCharacteristics selectedCharacteristics = null;
        String selectedId = null;
        Size[] selectedSizes = null;
        OutputPair selectedPair = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics candidate = cameraManager.getCameraCharacteristics(id);
            Integer candidateFacing = candidate.get(CameraCharacteristics.LENS_FACING);
            if (candidateFacing == null || candidateFacing != requiredFacing) continue;
            StreamConfigurationMap map = candidate.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            );
            if (map == null) continue;
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) continue;
            try {
                OutputPair pair = chooseOutputPair(
                        sizes,
                        outputResolution,
                        requestedCameraResolution
                );
                if (selectedPair == null
                        || preferCandidate(facing, candidate, selectedCharacteristics)) {
                    selectedPair = pair;
                    selectedCharacteristics = candidate;
                    selectedId = id;
                    selectedSizes = sizes;
                }
            } catch (RuntimeException e) {
                diagnostics.log("cameraX candidate rejected: id=" + id + ", reason=" + e);
            }
        }
        if (selectedPair == null || selectedCharacteristics == null) {
            throw new IllegalStateException("Requested camera is not available");
        }
        previewSize = selectedPair.preview;
        recordingSize = selectedPair.recording;
        sourceCropSize = selectedPair.cropSize;
        activeCameraResolution = selectedPair.cameraResolution;
        activeFrameRate = requestedFrameRate;
        maximumZoomRatio = readMaximumZoomRatio(selectedCharacteristics);
        // CameraX Preview SurfaceTexture timestamps are not reliably aligned with
        // AudioRecord boottime / elapsedRealtime. Forcing REALTIME drops frames
        // before the A/V origin and trips common-start timeout (~auto-stop, no crash).
        // Relative (UNKNOWN) mapping anchors the first encoded frame to elapsedRealtime.
        boolean sensorRealtime = isCameraTimestampRealtime(selectedCharacteristics);
        timestampRealtime = false;
        torchAvailable = facing == RoundVideoSession.CameraFacing.BACK
                && hasFlashUnit(selectedCharacteristics);
        diagnostics.log("cameraX outputs selected: id=" + selectedId
                + ", facing=" + facing
                + ", mode=" + activeCameraResolution
                + ", crop=" + sourceCropSize
                + ", preview=" + previewSize
                + ", recording=" + recordingSize
                + ", fps=" + activeFrameRate.getValue()
                + ", sensorTimestampSource=" + (sensorRealtime ? "REALTIME" : "UNKNOWN")
                + ", pipelineTimestampMode=RELATIVE"
                + ", sizes=" + (selectedSizes == null ? 0 : selectedSizes.length));
    }

    private static boolean preferCandidate(
            @NonNull RoundVideoSession.CameraFacing facing,
            @NonNull CameraCharacteristics candidate,
            @Nullable CameraCharacteristics current
    ) {
        if (current == null) return true;
        return facing == RoundVideoSession.CameraFacing.BACK
                && hasFlashUnit(candidate)
                && !hasFlashUnit(current);
    }

    private float readMaximumZoomRatio(@NonNull CameraCharacteristics cameraCharacteristics) {
        float maximum = 1f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Range<Float> range = cameraCharacteristics.get(
                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
            );
            if (range != null) maximum = range.getUpper();
        } else {
            Float value = cameraCharacteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
            );
            if (value != null) maximum = value;
        }
        return clamp(maximum, 1f, MAXIMUM_UI_ZOOM_RATIO);
    }

    private static boolean isCameraTimestampRealtime(
            @Nullable CameraCharacteristics cameraCharacteristics
    ) {
        if (cameraCharacteristics == null) return false;
        Integer source = cameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
        );
        return source != null
                && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
    }

    private static boolean hasFlashUnit(@NonNull CameraCharacteristics characteristics) {
        return Boolean.TRUE.equals(
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        );
    }

    private void reportError(@NonNull Exception error) {
        diagnostics.error("cameraX error", error);
        callback.onError(error);
    }

    private static long elapsedMs(long startedNs) {
        return startedNs == 0
                ? -1
                : (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @NonNull
    private static OutputPair chooseOutputPair(
            @NonNull Size[] sizes,
            @NonNull RoundVideoSession.OutputResolution outputResolution,
            @NonNull RoundVideoSession.CameraResolution requestedResolution
    ) {
        int preferredCrop = getSourceCropSize(outputResolution, requestedResolution);
        OutputPair preferred = chooseTierPair(sizes, preferredCrop, requestedResolution);
        if (preferred != null) return preferred;

        if (outputResolution == RoundVideoSession.OutputResolution.P360
                && requestedResolution == RoundVideoSession.CameraResolution.MEDIUM) {
            OutputPair mediumFallback = chooseTierPair(
                    sizes,
                    480,
                    RoundVideoSession.CameraResolution.MEDIUM
            );
            if (mediumFallback != null) return mediumFallback;
        }

        int lowCrop = outputResolution.getSize();
        if (requestedResolution != RoundVideoSession.CameraResolution.LOW) {
            OutputPair low = chooseTierPair(
                    sizes,
                    lowCrop,
                    RoundVideoSession.CameraResolution.LOW
            );
            if (low != null) return low;
        }

        Size recording = chooseFallbackRecordingSize(sizes, outputResolution.getSize());
        int cropSize = Math.min(shortSide(recording), MAXIMUM_SOURCE_SHORT_SIDE);
        return new OutputPair(
                choosePreviewSize(sizes, recording),
                recording,
                RoundVideoSession.CameraResolution.LOW,
                cropSize
        );
    }

    @Nullable
    private static OutputPair chooseTierPair(
            @NonNull Size[] sizes,
            int cropSize,
            @NonNull RoundVideoSession.CameraResolution resolution
    ) {
        OutputPair best = null;
        for (Size recording : sizes) {
            if (!isValidTierSize(recording, cropSize)) continue;
            Size preview = choosePreviewSize(sizes, recording);
            OutputPair candidate = new OutputPair(preview, recording, resolution, cropSize);
            if (best == null || compareOutputPairs(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    @NonNull
    private static Size chooseFallbackRecordingSize(@NonNull Size[] sizes, int outputSize) {
        Size best = null;
        for (Size size : sizes) {
            if (!isWithinAbsoluteLimit(size) || shortSide(size) < outputSize) continue;
            if (best == null || area(size) < area(best)) best = size;
        }
        if (best != null) return best;
        for (Size size : sizes) {
            if (!isWithinAbsoluteLimit(size)) continue;
            if (best == null
                    || shortSide(size) > shortSide(best)
                    || shortSide(size) == shortSide(best) && area(size) < area(best)) {
                best = size;
            }
        }
        if (best != null) return best;
        throw new IllegalStateException("Camera has no output at or below the bandwidth cap");
    }

    private static int getSourceCropSize(
            @NonNull RoundVideoSession.OutputResolution outputResolution,
            @NonNull RoundVideoSession.CameraResolution cameraResolution
    ) {
        if (cameraResolution == RoundVideoSession.CameraResolution.LOW) {
            return outputResolution.getSize();
        }
        if (outputResolution == RoundVideoSession.OutputResolution.P480) {
            return cameraResolution == RoundVideoSession.CameraResolution.HIGH ? 960 : 720;
        }
        return cameraResolution == RoundVideoSession.CameraResolution.HIGH ? 720 : 540;
    }

    @NonNull
    private static Size choosePreviewSize(@NonNull Size[] sizes, @NonNull Size recording) {
        Size best = recording;
        for (Size size : sizes) {
            if (hasSameAspectRatio(size, recording) && comparePreviewSizes(size, best) < 0) {
                best = size;
            }
        }
        return best;
    }

    private static int comparePreviewSizes(Size left, Size right) {
        int leftError = Math.abs(shortSide(left) - PREVIEW_SHORT_SIDE);
        int rightError = Math.abs(shortSide(right) - PREVIEW_SHORT_SIDE);
        return leftError != rightError
                ? Integer.compare(leftError, rightError)
                : Long.compare(area(left), area(right));
    }

    private static int compareOutputPairs(OutputPair left, OutputPair right) {
        int leftError = Math.abs(shortSide(left.preview) - PREVIEW_SHORT_SIDE);
        int rightError = Math.abs(shortSide(right.preview) - PREVIEW_SHORT_SIDE);
        if (leftError != rightError) return Integer.compare(leftError, rightError);
        long leftArea = area(left.preview) + area(left.recording);
        long rightArea = area(right.preview) + area(right.recording);
        return leftArea != rightArea
                ? Long.compare(leftArea, rightArea)
                : Long.compare(area(left.recording), area(right.recording));
    }

    private static boolean hasSameAspectRatio(Size left, Size right) {
        return (long) left.getWidth() * right.getHeight()
                == (long) left.getHeight() * right.getWidth();
    }

    private static boolean isValidTierSize(@NonNull Size size, int cropSize) {
        int maximumShortSide = cropSize
                + cropSize * MAXIMUM_CROP_OVERSIZE_PERCENT / 100;
        int maximumLongSide = Math.min(MAXIMUM_SOURCE_LONG_SIDE, cropSize * 2);
        int shortSide = shortSide(size);
        int longSide = Math.max(size.getWidth(), size.getHeight());
        return shortSide >= cropSize
                && shortSide <= maximumShortSide
                && longSide <= maximumLongSide;
    }

    private static boolean isWithinAbsoluteLimit(@NonNull Size size) {
        return shortSide(size) <= MAXIMUM_SOURCE_SHORT_SIDE
                && Math.max(size.getWidth(), size.getHeight()) <= MAXIMUM_SOURCE_LONG_SIDE;
    }

    private static int shortSide(Size size) {
        return Math.min(size.getWidth(), size.getHeight());
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static final class OutputPair {
        final Size preview;
        final Size recording;
        final RoundVideoSession.CameraResolution cameraResolution;
        final int cropSize;

        OutputPair(
                Size preview,
                Size recording,
                RoundVideoSession.CameraResolution cameraResolution,
                int cropSize
        ) {
            this.preview = preview;
            this.recording = recording;
            this.cameraResolution = cameraResolution;
            this.cropSize = cropSize;
        }
    }

    private static final class CameraLifecycleOwner implements LifecycleOwner {
        private final LifecycleRegistry registry = new LifecycleRegistry(this);

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return registry;
        }

        void ensureResumed() {
            Lifecycle.State state = registry.getCurrentState();
            if (state == Lifecycle.State.DESTROYED) {
                throw new IllegalStateException("CameraX lifecycle already destroyed");
            }
            if (!registry.getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
                registry.setCurrentState(Lifecycle.State.CREATED);
            }
            if (!registry.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                registry.setCurrentState(Lifecycle.State.STARTED);
            }
            if (!registry.getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                registry.setCurrentState(Lifecycle.State.RESUMED);
            }
        }

        boolean isAtLeastResumed() {
            return registry.getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        }

        void destroy() {
            if (registry.getCurrentState() == Lifecycle.State.DESTROYED) return;
            if (registry.getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
                registry.setCurrentState(Lifecycle.State.DESTROYED);
            }
        }
    }
}
