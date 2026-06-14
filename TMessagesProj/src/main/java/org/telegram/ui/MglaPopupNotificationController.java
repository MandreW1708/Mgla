package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

public class MglaPopupNotificationController {

    private final LaunchActivity activity;
    private Bulletin currentBulletin;
    private MessageObject currentMessage;

    public MglaPopupNotificationController(LaunchActivity activity) {
        this.activity = activity;
    }

    @SuppressWarnings("unchecked")
    public void onNewMessages(int account, Object... args) {
        if (args == null || args.length < 3 || ApplicationLoader.mainInterfacePaused) {
            return;
        }
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("mgla_popup_notifications_enabled", false)) {
            return;
        }
        boolean scheduled = (Boolean) args[2];
        if (scheduled || !(args[1] instanceof ArrayList)) {
            return;
        }
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        MessageObject message = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject object = messages.get(i);
            if (object != null && !object.isOutOwner()) {
                message = object;
                break;
            }
        }
        if (message == null) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(account);
        long dialogId = message.getDialogId();
        long topicId = message.getTopicId();
        if (messagesController.isDialogMuted(dialogId, topicId) || !messagesController.isDialogNotificationsSoundEnabled(dialogId, topicId)) {
            return;
        }
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment instanceof ChatActivity && ((ChatActivity) lastFragment).getDialogId() == dialogId) {
            return;
        }
        currentMessage = message;
        showPopup(message, prefs, lastFragment);
    }

    public void destroy() {
        hidePopup(false);
    }

    private void showPopup(MessageObject message, SharedPreferences prefs, BaseFragment fragment) {
        hidePopup(false);

        BulletinFactory factory = fragment != null
            ? BulletinFactory.of(fragment)
            : BulletinFactory.of(activity.drawerLayoutContainer, null);

        Bulletin.TwoLineLayout layout = new Bulletin.TwoLineLayout(activity, null);

        int alpha = Math.max(60, Math.min(100, prefs.getInt("mgla_popup_alpha", 90)));
        layout.setBackground(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_undo_background), 255 * alpha / 100), 16);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        setupAvatar(layout.imageView, avatarDrawable, message);
        layout.imageView.setRoundRadius(dp(15));

        layout.titleTextView.setText(getTitle(message));
        layout.subtitleTextView.setText(getMessagePreview(message));
        layout.subtitleTextView.setMaxLines(2);

        int duration = Math.max(1500, prefs.getInt("mgla_popup_duration_ms", 4000));
        layout.setButton(new Bulletin.UndoButton(activity, true, null)
            .setText("Открыть")
            .setUndoAction(this::openCurrentMessage));

        currentBulletin = factory.create(layout, duration)
            .setOnClickListener(v -> openCurrentMessage())
            .show(true);

        Bulletin.Layout bulletinLayout = currentBulletin.getLayout();
        if (bulletinLayout.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bulletinLayout.getLayoutParams();
            lp.width = AndroidUtilities.displaySize.x - dp(24);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            bulletinLayout.setLayoutParams(lp);
        }
    }

    private void setupAvatar(BackupImageView avatarView, AvatarDrawable avatarDrawable, MessageObject message) {
        MessagesController messagesController = MessagesController.getInstance(message.currentAccount);
        TLRPC.Peer fromId = message.messageOwner != null ? message.messageOwner.from_id : null;
        if (fromId != null && fromId.user_id != 0) {
            TLRPC.User user = messagesController.getUser(fromId.user_id);
            avatarDrawable.setInfo(message.currentAccount, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
            return;
        }
        long dialogId = message.getDialogId();
        if (dialogId > 0) {
            TLRPC.User user = messagesController.getUser(dialogId);
            avatarDrawable.setInfo(message.currentAccount, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
        } else {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            avatarDrawable.setInfo(message.currentAccount, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
        }
    }

    private String getTitle(MessageObject message) {
        TLRPC.Peer fromId = message.messageOwner != null ? message.messageOwner.from_id : null;
        if (fromId != null) {
            if (fromId.user_id != 0) {
                TLRPC.User user = MessagesController.getInstance(message.currentAccount).getUser(fromId.user_id);
                if (user != null) {
                    return UserObject.getUserName(user);
                }
            } else if (fromId.chat_id != 0 || fromId.channel_id != 0) {
                long chatId = fromId.chat_id != 0 ? fromId.chat_id : fromId.channel_id;
                TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(chatId);
                if (chat != null) {
                    return chat.title;
                }
            }
        }
        long dialogId = message.getDialogId();
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(-dialogId);
            return chat != null ? chat.title : "Сообщение";
        }
        TLRPC.User user = MessagesController.getInstance(message.currentAccount).getUser(dialogId);
        return user != null ? UserObject.getUserName(user) : "Сообщение";
    }

    private String getMessagePreview(MessageObject message) {
        CharSequence text = message.messageText;
        if (TextUtils.isEmpty(text)) {
            text = message.caption;
        }
        if (TextUtils.isEmpty(text) && message.messageOwner != null) {
            text = message.messageOwner.message;
        }
        if (TextUtils.isEmpty(text)) {
            return "Новое сообщение";
        }
        return text.toString().replace('\n', ' ').trim();
    }

    private void openCurrentMessage() {
        MessageObject message = currentMessage;
        if (message == null) {
            hidePopup(true);
            return;
        }
        Bundle args = new Bundle();
        long dialogId = message.getDialogId();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (message.getId() > 0) {
            args.putInt("message_id", message.getId());
        }
        activity.presentFragment(new ChatActivity(args));
        hidePopup(true);
    }

    private void hidePopup(boolean animated) {
        if (currentBulletin != null) {
            currentBulletin.hide(animated, 0);
            currentBulletin = null;
        }
    }
}
