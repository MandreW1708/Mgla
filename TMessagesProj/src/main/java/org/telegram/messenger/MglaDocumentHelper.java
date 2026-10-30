package org.telegram.messenger;

import android.app.Activity;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MglaDocumentViewer;

import java.io.File;
import java.util.Locale;

/**
 * Built-in document preview routing for PDF / Office / text files.
 */
public final class MglaDocumentHelper {

    public static final int KIND_UNSUPPORTED = 0;
    public static final int KIND_PDF = 1;
    public static final int KIND_WORD = 2;
    public static final int KIND_SHEET = 3;
    public static final int KIND_SLIDES = 4;
    public static final int KIND_TEXT = 5;
    public static final int KIND_HTML = 6;
    public static final int KIND_CSV = 7;

    private MglaDocumentHelper() {
    }

    public static boolean canOpen(File file, String fileName, String mimeType) {
        return kindOf(file, fileName, mimeType) != KIND_UNSUPPORTED;
    }

    public static boolean canOpen(MessageObject message) {
        if (message == null) {
            return false;
        }
        File f = resolveFile(message);
        if (f == null || !f.exists()) {
            return false;
        }
        String name = message.getDocumentName();
        if (TextUtils.isEmpty(name)) {
            name = f.getName();
        }
        return canOpen(f, name, message.getMimeType());
    }

    public static int kindOf(File file, String fileName, String mimeType) {
        String name = !TextUtils.isEmpty(fileName) ? fileName : (file != null ? file.getName() : "");
        String ext = extensionOf(name);
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";

        if ("pdf".equals(ext) || "application/pdf".equals(mime)) {
            return KIND_PDF;
        }
        if ("docx".equals(ext) || "odt".equals(ext)
                || mime.contains("wordprocessingml")
                || mime.contains("opendocument.text")
                || "application/msword".equals(mime) && "docx".equals(ext)) {
            return KIND_WORD;
        }
        if ("xlsx".equals(ext) || "ods".equals(ext)
                || mime.contains("spreadsheetml")
                || mime.contains("opendocument.spreadsheet")) {
            return KIND_SHEET;
        }
        if ("pptx".equals(ext) || "odp".equals(ext)
                || mime.contains("presentationml")
                || mime.contains("opendocument.presentation")) {
            return KIND_SLIDES;
        }
        if ("csv".equals(ext) || "tsv".equals(ext) || "text/csv".equals(mime)
                || "text/tab-separated-values".equals(mime)) {
            return KIND_CSV;
        }
        if ("html".equals(ext) || "htm".equals(ext) || "text/html".equals(mime)) {
            return KIND_HTML;
        }
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "log".equals(ext) || "json".equals(ext) || "xml".equals(ext)
                || "yaml".equals(ext) || "yml".equals(ext) || "rtf".equals(ext)
                || "ini".equals(ext) || "conf".equals(ext) || "cfg".equals(ext)
                || "java".equals(ext) || "kt".equals(ext) || "py".equals(ext)
                || "js".equals(ext) || "ts".equals(ext) || "css".equals(ext)
                || "c".equals(ext) || "cpp".equals(ext) || "h".equals(ext)
                || "swift".equals(ext) || "go".equals(ext) || "rs".equals(ext)
                || "sh".equals(ext) || "bat".equals(ext) || "ps1".equals(ext)
                || (mime.startsWith("text/") && !"text/html".equals(mime))
                || "application/json".equals(mime)
                || "application/xml".equals(mime)
                || "application/rtf".equals(mime)
                || "text/rtf".equals(mime)) {
            return KIND_TEXT;
        }
        // Legacy OLE Office — not parsed offline; still open viewer with external fallback UI.
        if ("doc".equals(ext) || "xls".equals(ext) || "ppt".equals(ext)
                || "application/msword".equals(mime)
                || "application/vnd.ms-excel".equals(mime)
                || "application/vnd.ms-powerpoint".equals(mime)) {
            return KIND_WORD; // viewer shows legacy empty-state + open externally
        }
        return KIND_UNSUPPORTED;
    }

    public static String kindLabel(int kind) {
        switch (kind) {
            case KIND_PDF:
                return "PDF";
            case KIND_WORD:
                return "Документ";
            case KIND_SHEET:
                return "Таблица";
            case KIND_SLIDES:
                return "Презентация";
            case KIND_CSV:
                return "CSV";
            case KIND_HTML:
                return "HTML";
            case KIND_TEXT:
                return "Текст";
            default:
                return "Файл";
        }
    }

    public static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.US);
    }

    public static String guessMime(String fileName, String fallback) {
        String ext = extensionOf(fileName);
        if (!TextUtils.isEmpty(ext)) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (!TextUtils.isEmpty(mime)) {
                return mime;
            }
        }
        if (!TextUtils.isEmpty(fallback)) {
            return fallback;
        }
        return "application/octet-stream";
    }

    public static File resolveFile(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        File f = null;
        if (message.messageOwner.attachPath != null && message.messageOwner.attachPath.length() != 0) {
            f = new File(message.messageOwner.attachPath);
            if (f.exists()) {
                return f;
            }
        }
        return FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
    }

    /**
     * Opens the built-in viewer when the format is supported.
     * @return true if handled internally
     */
    public static boolean open(File file, String fileName, String mimeType, Activity activity, BaseFragment parentFragment) {
        if (file == null || !file.exists() || !canOpen(file, fileName, mimeType)) {
            return false;
        }
        String name = !TextUtils.isEmpty(fileName) ? fileName : file.getName();
        MglaDocumentViewer viewer = MglaDocumentViewer.create(file, name, mimeType);
        if (parentFragment != null) {
            parentFragment.presentFragment(viewer);
            return true;
        }
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.presentFragment(viewer);
            return true;
        }
        return false;
    }

    public static boolean open(MessageObject message, Activity activity, BaseFragment parentFragment) {
        if (!canOpen(message)) {
            return false;
        }
        File f = resolveFile(message);
        String name = message.getDocumentName();
        if (TextUtils.isEmpty(name) && f != null) {
            name = f.getName();
        }
        return open(f, name, message.getMimeType(), activity, parentFragment);
    }
}
