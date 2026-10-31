package org.telegram.utils.recorder.round;

import android.util.Size;

import androidx.annotation.NonNull;

interface RoundVideoCameraBackend {

    interface Callback {
        void onConfigured(@NonNull CameraInfo info, boolean cameraSwitch);

        void onRecordingStarted();

        void onCameraSwitchStarted(@NonNull RoundVideoSession.CameraFacing facing);

        void onCameraSwitchFirstFrame();

        void onRecordingStopped();

        void onError(@NonNull Exception error);
    }

    final class CameraInfo {
        final RoundVideoSession.CameraFacing facing;
        final RoundVideoSession.CameraResolution cameraResolution;
        final RoundVideoSession.FrameRate frameRate;
        final Size previewSize;
        final Size recordingSize;
        final float maximumZoomRatio;
        final boolean torchAvailable;

        CameraInfo(
                RoundVideoSession.CameraFacing facing,
                RoundVideoSession.CameraResolution cameraResolution,
                RoundVideoSession.FrameRate frameRate,
                Size previewSize,
                Size recordingSize,
                float maximumZoomRatio,
                boolean torchAvailable
        ) {
            this.facing = facing;
            this.cameraResolution = cameraResolution;
            this.frameRate = frameRate;
            this.previewSize = previewSize;
            this.recordingSize = recordingSize;
            this.maximumZoomRatio = maximumZoomRatio;
            this.torchAvailable = torchAvailable;
        }
    }

    void startSegment(
            @NonNull RoundVideoMp4Writer writer,
            long timelineOffsetUs,
            @NonNull RoundVideoSession.CameraFacing facing
    );

    boolean stopSegment();

    boolean setCameraFacing(@NonNull RoundVideoSession.CameraFacing facing);

    boolean setZoom(float progress);

    boolean setTorchEnabled(boolean enabled);

    void release();
}
