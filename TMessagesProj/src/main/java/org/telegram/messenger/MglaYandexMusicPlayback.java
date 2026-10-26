package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Yandex Music tracks into Telegram's {@link MediaController} via synthetic MessageObjects
 * so system MediaSession / MusicPlayerService / in-chat player work unchanged.
 */
public final class MglaYandexMusicPlayback {

    /** Encrypted dialog id so MusicListenReporter is skipped. */
    private static final long YANDEX_DIALOG_ID = DialogObject.makeEncryptedDialogId(0x4D474C41); // 'MGLA'
    private static final long DOC_ID_BASE = -7_100_000_000_000_000L;

    private static final ConcurrentHashMap<Long, MglaYandexMusicModel.Track> tracksByDocId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> trackIdByMsgId = new ConcurrentHashMap<>();
    public static final DispatchQueue musicDownloadQueue = new DispatchQueue("mglaMusicDownloadQueue");

    private MglaYandexMusicPlayback() {
    }

    public static boolean isYandexMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return messageObject.getDialogId() == YANDEX_DIALOG_ID;
    }

    public static MglaYandexMusicModel.Track getTrack(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return null;
        }
        return tracksByDocId.get(messageObject.getDocument().id);
    }

    public static String getYandexTrackId(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        return trackIdByMsgId.get(messageObject.getId());
    }

    /** Short-lived streaming cache (app cacheDir). Prefer {@link #fileForPlayback(String)}. */
    public static File streamingCacheFileForTrack(String trackId) {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "mgla_ym");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String safeId = trackId != null ? trackId.replaceAll("[^a-zA-Z0-9._-]", "_") : "track";
        return new File(dir, safeId + ".mp3");
    }

    /**
     * File used for playback: offline download (Telegram storage path) first,
     * otherwise streaming cache.
     */
    public static File fileForPlayback(String trackId) {
        if (!TextUtils.isEmpty(trackId)) {
            File offline = MglaYandexMusicOffline.getInstance().fileForTrack(trackId);
            if (offline.exists() && offline.length() > 1024) {
                return offline;
            }
        }
        return streamingCacheFileForTrack(trackId);
    }

    /** @deprecated use {@link #fileForPlayback(String)} */
    public static File cacheFileForTrack(String trackId) {
        return fileForPlayback(trackId);
    }

    public static boolean isCached(String trackId) {
        File f = fileForPlayback(trackId);
        return f.exists() && f.length() > 1024;
    }

    public static MessageObject createMessageObject(MglaYandexMusicModel.Track track, File file, int account) {
        if (track == null || file == null) {
            return null;
        }
        long docId = DOC_ID_BASE + (Math.abs((long) track.id.hashCode()) & 0xFFFFFFFFL);
        int msgId = -(Math.abs(track.id.hashCode()) % 1_000_000_000);
        if (msgId == 0) {
            msgId = -1;
        }

        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = msgId;
        msg.date = (int) (System.currentTimeMillis() / 1000L);
        msg.dialog_id = YANDEX_DIALOG_ID;
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = UserConfig.getInstance(account).getClientUserId();
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = msg.peer_id.user_id;
        msg.out = true;
        msg.attachPath = file.getAbsolutePath();
        msg.message = !TextUtils.isEmpty(track.title) ? track.title : "Yandex Music";

        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = docId;
        document.access_hash = 1;
        document.file_reference = new byte[0];
        document.date = msg.date;
        document.mime_type = "audio/mpeg";
        document.size = file.length();
        document.dc_id = 2;

        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.voice = false;
        audio.title = track.title != null ? track.title : "";
        audio.performer = track.artist != null ? track.artist : "";
        audio.duration = (int) Math.max(1, track.durationMs / 1000L);
        audio.flags |= 3; // title + performer
        document.attributes.add(audio);

        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = (track.id != null ? track.id : "track") + ".mp3";
        document.attributes.add(filename);

        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        media.flags |= 1;
        msg.media = media;

        MessageObject object = new MessageObject(account, msg, false, false);
        object.attachPathExists = file.exists();
        object.mediaExists = file.exists();

        tracksByDocId.put(docId, track);
        trackIdByMsgId.put(msgId, track.id);
        return object;
    }

    public static ArrayList<MessageObject> buildPlaylist(List<MglaYandexMusicModel.Track> tracks, int account) {
        ArrayList<MessageObject> list = new ArrayList<>();
        if (tracks == null) {
            return list;
        }
        for (MglaYandexMusicModel.Track track : tracks) {
            if (track == null || TextUtils.isEmpty(track.id)) {
                continue;
            }
            File file = fileForPlayback(track.id);
            MessageObject obj = createMessageObject(track, file, account);
            if (obj != null) {
                list.add(obj);
            }
        }
        return list;
    }

    /**
     * Called from MediaController when a Yandex synthetic message has no local file yet.
     * @return true if download was scheduled
     */
    public static boolean ensureDownloadedThenPlay(MessageObject messageObject) {
        if (!isYandexMessage(messageObject)) {
            return false;
        }
        MglaYandexMusicModel.Track track = getTrack(messageObject);
        if (track == null) {
            return false;
        }
        File file = fileForPlayback(track.id);
        if (file.exists() && file.length() > 1024) {
            messageObject.messageOwner.attachPath = file.getAbsolutePath();
            messageObject.attachPathExists = true;
            messageObject.mediaExists = true;
            AndroidUtilities.runOnUIThread(() -> {
                MediaController.getInstance().playMessage(messageObject);
            });
            return true;
        }

        final String trackId = track.id;
        musicDownloadQueue.postRunnable(() -> {
            try {
                // Prefer offline store path if a download finished while we were waiting.
                File offline = MglaYandexMusicOffline.getInstance().fileForTrack(trackId);
                if (offline.exists() && offline.length() > 1024) {
                    finishPlay(messageObject, track, offline);
                    return;
                }
                MglaYandexMusicApi.AudioStream stream = MglaYandexMusicApi.resolveAudioStreamSync(trackId);
                if (stream == null || TextUtils.isEmpty(stream.url)) {
                    throw new Exception("No stream");
                }
                File local;
                if (stream.url.startsWith("file:")) {
                    local = new File(android.net.Uri.parse(stream.url).getPath());
                } else {
                    MglaYandexMusicApi.AudioStream localStream = MglaYandexMusicApi.downloadToLocalCache(trackId, stream.url);
                    local = new File(android.net.Uri.parse(localStream.url).getPath());
                }
                if (local == null || !local.exists()) {
                    throw new Exception("File missing after download");
                }
                finishPlay(messageObject, track, local);
            } catch (Exception e) {
                FileLog.e("MglaYandexMusicPlayback: download failed", e);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                                "Яндекс Музыка: " + (e.getMessage() != null ? e.getMessage() : "ошибка"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                });
            }
        });
        return true;
    }

    private static void finishPlay(MessageObject messageObject, MglaYandexMusicModel.Track track, File local) {
        AndroidUtilities.runOnUIThread(() -> {
            messageObject.messageOwner.attachPath = local.getAbsolutePath();
            messageObject.attachPathExists = true;
            messageObject.mediaExists = true;
            if (messageObject.getDocument() != null) {
                messageObject.getDocument().size = local.length();
            }
            tracksByDocId.put(messageObject.getDocument().id, track);
            MediaController.getInstance().playMessage(messageObject);
        });
    }
}
