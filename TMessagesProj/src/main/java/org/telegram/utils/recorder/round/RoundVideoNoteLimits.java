package org.telegram.utils.recorder.round;

import androidx.annotation.NonNull;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

/**
 * Telegram video-note (кружок) protocol limits.
 *
 * <p>TDLib requires square notes with side ≤ 640. Official clients encode at
 * {@link MessagesController#roundVideoSize} (usually 384). Uploading 720×720 with
 * {@code round_message=true} is accepted by some clients locally, then demoted by
 * the server to a regular square video — exactly the CameraX Pro failure mode.</p>
 */
public final class RoundVideoNoteLimits {

    /** TDLib / Bot API hard ceiling for video-note diameter. */
    public static final int MAX_SIDE = 640;

    /** Safe fallback when server config is missing. */
    public static final int DEFAULT_SIDE = 384;

    private RoundVideoNoteLimits() {
    }

    /** Server-configured note diameter, clamped to protocol limits. */
    public static int serverNoteSide() {
        return serverNoteSide(UserConfig.selectedAccount);
    }

    public static int serverNoteSide(int account) {
        int side = MessagesController.getInstance(account).roundVideoSize;
        if (side <= 0) {
            side = DEFAULT_SIDE;
        }
        return clampNoteSide(side);
    }

    /**
     * Resolves MediaCodec / GL output side for a CameraX Pro quality choice.
     * {@link RoundVideoSession.OutputResolution#P720} means HQ capture with
     * note-safe encode (server size, at least 384, at most {@link #MAX_SIDE}).
     */
    public static int encodeSide(
            int account,
            @NonNull RoundVideoSession.OutputResolution requested
    ) {
        int server = serverNoteSide(account);
        int side;
        if (requested == RoundVideoSession.OutputResolution.P360) {
            side = 360;
        } else if (requested == RoundVideoSession.OutputResolution.P480) {
            side = Math.min(480, MAX_SIDE);
        } else {
            // P720 / HQ: sharpest note-compatible encode.
            side = Math.max(server, Math.min(480, MAX_SIDE));
            side = Math.min(side, MAX_SIDE);
        }
        return clampNoteSide(side);
    }

    /** Caps Studio bitrate so note uploads stay within ~12 MB / quality norms. */
    public static int clampNoteBitrate(int encodeSide, int requestedBitrate) {
        int max;
        if (encodeSide <= 384) {
            max = 1_500_000;
        } else if (encodeSide <= 480) {
            max = 2_000_000;
        } else {
            max = 2_500_000;
        }
        int bitrate = requestedBitrate <= 0 ? 1_000_000 : requestedBitrate;
        return Math.min(bitrate, max);
    }

    public static int clampNoteSide(int side) {
        if (side <= 0) {
            side = DEFAULT_SIDE;
        }
        if (side > MAX_SIDE) {
            side = MAX_SIDE;
        }
        return side & ~1;
    }
}
