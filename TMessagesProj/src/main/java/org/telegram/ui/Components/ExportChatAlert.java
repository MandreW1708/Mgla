package org.telegram.ui.Components;

import android.app.ProgressDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatExportManager;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

public class ExportChatAlert extends AlertDialog.Builder {

    private ChatActivity chatActivity;
    private long dialogId;

    public ExportChatAlert(Context context, long dialogId, Theme.ResourcesProvider resourcesProvider, ChatActivity chatActivity) {
        super(context, resourcesProvider);
        this.chatActivity = chatActivity;
        this.dialogId = dialogId;

        setTitle(LocaleController.getString("ExportChatTitle", org.telegram.messenger.R.string.ExportChatTitle));

        ScrollView scrollView = new ScrollView(context);
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        scrollView.addView(linearLayout);

        // Media Checkboxes
        CheckBox cbPhotos = new CheckBox(context);
        cbPhotos.setText(LocaleController.getString("ExportPhotos", org.telegram.messenger.R.string.ExportPhotos));
        cbPhotos.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        linearLayout.addView(cbPhotos);

        CheckBox cbVideos = new CheckBox(context);
        cbVideos.setText(LocaleController.getString("ExportVideos", org.telegram.messenger.R.string.ExportVideos));
        cbVideos.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        linearLayout.addView(cbVideos);

        CheckBox cbVoice = new CheckBox(context);
        cbVoice.setText(LocaleController.getString("ExportVoiceMessages", org.telegram.messenger.R.string.ExportVoiceMessages));
        cbVoice.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        linearLayout.addView(cbVoice);

        CheckBox cbStickers = new CheckBox(context);
        cbStickers.setText(LocaleController.getString("ExportStickers", org.telegram.messenger.R.string.ExportStickers));
        cbStickers.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        linearLayout.addView(cbStickers);

        // Format RadioGroup
        TextView formatTitle = new TextView(context);
        formatTitle.setText(LocaleController.getString("ExportChatFormat", org.telegram.messenger.R.string.ExportChatFormat));
        formatTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        formatTitle.setTextSize(16);
        formatTitle.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        linearLayout.addView(formatTitle);

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(RadioGroup.VERTICAL);
        
        RadioButton rbHtml = new RadioButton(context);
        rbHtml.setText(LocaleController.getString("ExportHTML", org.telegram.messenger.R.string.ExportHTML));
        rbHtml.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        rbHtml.setId(1);
        rbHtml.setChecked(true);
        radioGroup.addView(rbHtml);

        RadioButton rbJson = new RadioButton(context);
        rbJson.setText(LocaleController.getString("ExportJSON", org.telegram.messenger.R.string.ExportJSON));
        rbJson.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        rbJson.setId(2);
        radioGroup.addView(rbJson);

        linearLayout.addView(radioGroup);

        // Folder Name
        TextView folderTitle = new TextView(context);
        folderTitle.setText(LocaleController.getString("ExportFolderName", org.telegram.messenger.R.string.ExportFolderName));
        folderTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        folderTitle.setTextSize(16);
        folderTitle.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        linearLayout.addView(folderTitle);

        EditText folderInput = new EditText(context);
        folderInput.setInputType(InputType.TYPE_CLASS_TEXT);
        folderInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        folderInput.setText("ChatExport_" + dialogId);
        linearLayout.addView(folderInput);

        setView(scrollView);

        setPositiveButton(LocaleController.getString("ExportStart", org.telegram.messenger.R.string.ExportStart), (dialog, which) -> {
            boolean photos = cbPhotos.isChecked();
            boolean videos = cbVideos.isChecked();
            boolean voice = cbVoice.isChecked();
            boolean stickers = cbStickers.isChecked();
            boolean htmlFormat = rbHtml.isChecked();
            String folderName = folderInput.getText().toString();

            if (folderName.trim().isEmpty()) {
                folderName = "ChatExport_" + dialogId;
            }

            startExportProcess(context, photos, videos, voice, stickers, htmlFormat, folderName);
        });

        setNegativeButton(LocaleController.getString("Cancel", org.telegram.messenger.R.string.Cancel), null);
    }

    private void startExportProcess(Context context, boolean photos, boolean videos, boolean voice, boolean stickers, boolean htmlFormat, String folderName) {
        Toast.makeText(context, "Экспорт начат в фоновом режиме...", Toast.LENGTH_SHORT).show();

        ChatExportManager.startExport(chatActivity.getCurrentAccount(), dialogId, photos, videos, voice, stickers, htmlFormat, folderName, new ChatExportManager.ExportCallback() {
            @Override
            public void onProgress(int progress, int total) {
                // UI progress is handled by system notifications now
            }

            @Override
            public void onComplete(String path) {
                // UI completion is handled by system notifications
            }

            @Override
            public void onError(String error) {
                // UI error is handled by system notifications
            }
        });
    }
}
