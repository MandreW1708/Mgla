package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatExportManager;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;

import java.io.File;

public class ExportChatAlert extends AlertDialog.Builder {

    public static final int REQUEST_CODE_FOLDER_PICK = 99001;
    private static final int STEP_MB = 5;
    private static final int MAX_MB = 4096; // 4 GB
    private static final int SEEK_BAR_MAX = MAX_MB / STEP_MB; // 819 steps

    private static ExportChatAlert currentInstance;

    private ChatActivity chatActivity;
    private long dialogId;
    private Theme.ResourcesProvider resourcesProvider;

    private boolean htmlFormat = true;
    private long maxFileSize = 0;
    private String saveLocation;
    private String saveLocationDisplay;

    private TextSettingsCell locationCell;
    private TextView sizeValueTextView;
    private SeekBar sizeSeekBar;

    public ExportChatAlert(Context context, long dialogId, Theme.ResourcesProvider resourcesProvider, ChatActivity chatActivity) {
        super(context, resourcesProvider);
        this.chatActivity = chatActivity;
        this.dialogId = dialogId;
        this.resourcesProvider = resourcesProvider;
        this.saveLocation = Environment.DIRECTORY_DOWNLOADS;
        this.saveLocationDisplay = "Загрузки";
        currentInstance = this;

        setTitle(LocaleController.getString("ExportChatTitle", R.string.ExportChatTitle));

        ScrollView scrollView = new ScrollView(context);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(container);

        // === Section: Media ===
        container.addView(createSectionHeader(context, "Медиа"));

        LinearLayout mediaContainer = createRoundedSection(context);
        TextCheckCell cbPhotos = createCheckCellWithIcon(context, "Фото", 0xff5fa8d3, R.drawable.msg_photos);
        cbPhotos.setOnClickListener(v -> cbPhotos.setChecked(!cbPhotos.isChecked()));
        mediaContainer.addView(cbPhotos);
        TextCheckCell cbVideos = createCheckCellWithIcon(context, "Видео", 0xffd3585f, R.drawable.msg_video);
        cbVideos.setOnClickListener(v -> cbVideos.setChecked(!cbVideos.isChecked()));
        mediaContainer.addView(cbVideos);
        TextCheckCell cbVoice = createCheckCellWithIcon(context, "Голосовые сообщения", 0xff9a8cff, R.drawable.msg_voicechat);
        cbVoice.setOnClickListener(v -> cbVoice.setChecked(!cbVoice.isChecked()));
        mediaContainer.addView(cbVoice);
        TextCheckCell cbStickers = createCheckCellWithIcon(context, "Стикеры", 0xff5fbf6f, R.drawable.msg_sticker);
        cbStickers.setOnClickListener(v -> cbStickers.setChecked(!cbStickers.isChecked()));
        mediaContainer.addView(cbStickers);

        // File size limit inside Media section
        TextCheckCell cbLimitSize = createCheckCellWithIcon(context, "Файлы", 0xff9a8cff, R.drawable.msg_filehq);
        cbLimitSize.setChecked(false);

        // Slider row
        LinearLayout sliderRow = new LinearLayout(context);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        sliderRow.setVisibility(View.GONE);

        sizeSeekBar = new SeekBar(context);
        sizeSeekBar.setMax(SEEK_BAR_MAX);
        sizeSeekBar.setProgress(20); // 100 MB default
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sizeSeekBar.setLayoutParams(seekParams);

        sizeValueTextView = new TextView(context);
        sizeValueTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        sizeValueTextView.setTextSize(15);
        sizeValueTextView.setTypeface(AndroidUtilities.bold());
        sizeValueTextView.setGravity(Gravity.CENTER);
        sizeValueTextView.setMinWidth(AndroidUtilities.dp(80));
        sizeValueTextView.setText("100 МБ");

        sizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int mb = (progress + 1) * STEP_MB;
                if (mb > MAX_MB) mb = MAX_MB;
                maxFileSize = (long) mb * 1024 * 1024;
                sizeValueTextView.setText(formatSize(mb));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Set initial value
        maxFileSize = 100L * 1024 * 1024;

        sliderRow.addView(sizeSeekBar);
        sliderRow.addView(sizeValueTextView);

        cbLimitSize.setOnClickListener(v -> {
            cbLimitSize.setChecked(!cbLimitSize.isChecked());
            sliderRow.setVisibility(cbLimitSize.isChecked() ? View.VISIBLE : View.GONE);
        });

        mediaContainer.addView(cbLimitSize);
        mediaContainer.addView(sliderRow);
        container.addView(mediaContainer);

        // === Section: Save location ===
        container.addView(createSectionHeader(context, "Место сохранения"));

        LinearLayout locationContainer = createRoundedSection(context);
        locationCell = new TextSettingsCell(context, resourcesProvider);
        locationCell.setTextAndValue("Сохранить в", saveLocationDisplay, false);
        locationCell.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (chatActivity != null) {
                    chatActivity.startActivityForResult(intent, REQUEST_CODE_FOLDER_PICK);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        locationContainer.addView(locationCell);
        container.addView(locationContainer);

        // === Section: Format ===
        container.addView(createSectionHeader(context, "Формат экспорта"));

        LinearLayout formatContainer = createRoundedSection(context);
        TextCheckCell cbHtml = createCheckCell(context, "HTML");
        cbHtml.setChecked(true);
        TextCheckCell cbJson = createCheckCell(context, "JSON");
        cbJson.setChecked(false);
        cbHtml.setOnClickListener(v -> {
            htmlFormat = true;
            cbHtml.setChecked(true);
            cbJson.setChecked(false);
        });
        cbJson.setOnClickListener(v -> {
            htmlFormat = false;
            cbJson.setChecked(true);
            cbHtml.setChecked(false);
        });
        formatContainer.addView(cbHtml);
        formatContainer.addView(cbJson);
        container.addView(formatContainer);

        // === Section: Folder name ===
        container.addView(createSectionHeader(context, "Название папки"));

        LinearLayout folderContainer = createRoundedSection(context);
        EditText folderInput = new EditText(context);
        folderInput.setInputType(InputType.TYPE_CLASS_TEXT);
        folderInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        folderInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        folderInput.setHint("ChatExport_" + dialogId);
        folderInput.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        folderInput.setBackground(null);
        folderInput.setTextSize(16);
        folderContainer.addView(folderInput);
        container.addView(folderContainer);

        setView(scrollView);

        setPositiveButton(LocaleController.getString("ExportStart", R.string.ExportStart), (dialog, which) -> {
            boolean photos = cbPhotos.isChecked();
            boolean videos = cbVideos.isChecked();
            boolean voice = cbVoice.isChecked();
            boolean stickers = cbStickers.isChecked();
            String folderName = folderInput.getText().toString().trim();
            if (folderName.isEmpty()) {
                folderName = "ChatExport_" + dialogId;
            }
            long fileSizeLimit = cbLimitSize.isChecked() ? maxFileSize : 0;

            startExportProcess(context, photos, videos, voice, stickers, htmlFormat, folderName, saveLocation, fileSizeLimit);
        });

        setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
    }

    public static void onFolderPicked(Uri treeUri) {
        if (currentInstance == null || treeUri == null) return;
        String path = treeUriToPath(treeUri);
        if (path != null) {
            currentInstance.saveLocation = path;
            currentInstance.saveLocationDisplay = path;
        } else {
            currentInstance.saveLocation = treeUri.toString();
            currentInstance.saveLocationDisplay = treeUri.getLastPathSegment();
        }
        if (currentInstance.locationCell != null) {
            currentInstance.locationCell.setTextAndValue("Сохранить в", currentInstance.saveLocationDisplay, false);
        }
    }

    private static String treeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            String[] split = docId.split(":");
            String type = split[0];
            if ("primary".equalsIgnoreCase(type)) {
                return Environment.getExternalStorageDirectory() + "/" + (split.length > 1 ? split[1] : "");
            }
            return "/storage/" + type + "/" + (split.length > 1 ? split[1] : "");
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String formatSize(int mb) {
        if (mb >= 1024) {
            double gb = mb / 1024.0;
            if (gb == (int) gb) {
                return (int) gb + " ГБ";
            }
            return String.format("%.1f ГБ", gb);
        }
        return mb + " МБ";
    }

    private View createSectionHeader(Context context, String title) {
        HeaderCell headerCell = new HeaderCell(context, resourcesProvider);
        headerCell.setText(title);
        headerCell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(16);
        lp.bottomMargin = AndroidUtilities.dp(4);
        headerCell.setLayoutParams(lp);
        return headerCell;
    }

    private LinearLayout createRoundedSection(Context context) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        return section;
    }

    private TextCheckCell createCheckCell(Context context, String text) {
        TextCheckCell cell = new TextCheckCell(context, 21, true, resourcesProvider);
        cell.setTextAndCheck(text, false, true);
        cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        return cell;
    }

    private TextCheckCell createCheckCellWithIcon(Context context, String text, int iconColor, int iconRes) {
        TextCheckCell cell = new TextCheckCell(context, 21, true, resourcesProvider);
        cell.setTextAndCheck(text, false, true);
        cell.setColorfullIcon(iconColor, iconRes);
        cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        return cell;
    }

    private void startExportProcess(Context context, boolean photos, boolean videos, boolean voice, boolean stickers, boolean htmlFormat, String folderName, String saveLocation, long maxFileSize) {
        ChatExportManager.startExport(chatActivity.getCurrentAccount(), dialogId, photos, videos, voice, stickers, htmlFormat, folderName, saveLocation, maxFileSize, new ChatExportManager.ExportCallback() {
            @Override
            public void onProgress(int progress, int total) {
            }

            @Override
            public void onComplete(String path) {
            }

            @Override
            public void onError(String error) {
            }
        });
    }
}
