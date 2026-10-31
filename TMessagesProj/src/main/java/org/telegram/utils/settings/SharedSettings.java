package org.telegram.utils.settings;

import org.telegram.messenger.BuildConfig;
import org.telegram.utils.recorder.round.RoundVideoSession;
import org.telegram.utils.recorder.audio.AudioRecordingSession;

public final class SharedSettings {

    public static final BooleanSetting experimentalSettingsAllowed =
        BooleanSetting.of("experimental_settings_allowed", BuildConfig.DEBUG_VERSION);

    public static final BooleanSetting audioRecorderV2Enabled =
        BooleanSetting.of("audio_recorder_v2_enabled", BuildConfig.DEBUG_VERSION);

    public static final EnumSetting<AudioRecordingSession.NoiseSuppression> audioRecorderNoiseSuppression =
        EnumSetting.of("audio_recorder_noise_suppression", AudioRecordingSession.NoiseSuppression.DISABLED);

    public static final BooleanSetting roundVideoCamera2Enabled =
        BooleanSetting.of("round_video_camera2_enabled", BuildConfig.DEBUG_VERSION);

    /** When true (and Camera2 recorder is selected), InstantCameraView2 uses CameraX capture. */
    public static final BooleanSetting roundVideoCameraXEnabled =
        BooleanSetting.of("round_video_camerax_enabled", false);

    public static final EnumSetting<RoundVideoSession.OutputResolution> roundVideoOutputResolution =
        EnumSetting.of("round_video_output_resolution", RoundVideoSession.OutputResolution.P480);

    public static final EnumSetting<RoundVideoSession.CameraResolution> roundVideoCameraResolution =
        EnumSetting.of("round_video_camera_resolution", RoundVideoSession.CameraResolution.HIGH);

    public static final EnumSetting<RoundVideoSession.FrameRate> roundVideoFrameRate =
        EnumSetting.of("round_video_frame_rate", RoundVideoSession.FrameRate.FPS_30);

    public static final IntSetting roundVideoVideoBitrate =
        IntSetting.of("round_video_video_bitrate", 1_200_000);

    public static final BooleanSetting roundVideoComposition =
        BooleanSetting.of("round_video_composition", true);

    /** CameraX-only: quality preset (drives defaults; individuals may diverge → Custom). */
    public static final EnumSetting<RoundVideoSession.CameraXPreset> roundVideoCameraXPreset =
        EnumSetting.of("round_video_camerax_preset", RoundVideoSession.CameraXPreset.ULTRA);

    /** P720 = HQ capture with note-safe encode (≤640). Not a literal 720×720 video note. */
    public static final EnumSetting<RoundVideoSession.OutputResolution> roundVideoCameraXOutputResolution =
        EnumSetting.of("round_video_camerax_output_resolution", RoundVideoSession.OutputResolution.P720);

    public static final EnumSetting<RoundVideoSession.CameraXCapture> roundVideoCameraXCapture =
        EnumSetting.of("round_video_camerax_capture", RoundVideoSession.CameraXCapture.ULTRA);

    public static final EnumSetting<RoundVideoSession.FrameRate> roundVideoCameraXFrameRate =
        EnumSetting.of("round_video_camerax_frame_rate", RoundVideoSession.FrameRate.FPS_60);

    public static final IntSetting roundVideoCameraXBitrate =
        IntSetting.of("round_video_camerax_bitrate", 1_500_000);

    public static final BooleanSetting roundVideoCameraXStabilization =
        BooleanSetting.of("round_video_camerax_stabilization", true);

    public static final BooleanSetting roundVideoCameraXComposition =
        BooleanSetting.of("round_video_camerax_composition", true);

    public static final EnumSetting<RoundVideoSession.CameraFacing> roundVideoLastCamera =
        EnumSetting.of("round_video_last_camera", RoundVideoSession.CameraFacing.FRONT);

    private SharedSettings() {
    }
}
