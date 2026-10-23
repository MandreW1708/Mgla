package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaHiddenChats;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaPopupNotificationView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

public class MglaPopupNotificationController {

    private final LaunchActivity activity;
    private MglaPopupNotificationView currentPopup;
    private MessageObject currentMessage;
    private Runnable hideRunnable;
    private boolean hiding;

    public MglaPopupNotificationController(LaunchActivity activity) {
        this.activity = activity;
    }

    @SuppressWarnings("unchecked")
    public void onNewMessages(int account, Object... args) {
        if (args == null || args.length < 3 || ApplicationLoader.mainInterfacePaused) {
            return;
        }
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        if (!MglaFeatureFlags.isAllowed("mgla_popup_notifications_enabled")
                || !prefs.getBoolean("mgla_popup_notifications_enabled", false)) {
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
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat == null || chat.min || ChatObject.isNotInChat(chat) || ChatObject.isCommunity(chat)) {
                return;
            }
        }
        if (MglaHiddenChats.isHidden(account, dialogId)) {
            return;
        }
        if (messagesController.isDialogMuted(dialogId, topicId) || !messagesController.isDialogNotificationsSoundEnabled(dialogId, topicId)) {
            return;
        }
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment instanceof ChatActivity && ((ChatActivity) lastFragment).getDialogId() == dialogId) {
            return;
        }
        currentMessage = message;
        showPopup(message, prefs);
    }

    public void destroy() {
        cancelHide();
        removePopupImmediate();
    }

    private void showPopup(MessageObject message, SharedPreferences prefs) {
        if (activity.drawerLayoutContainer == null) {
            return;
        }
        hidePopup(false);

        final int alpha = Math.max(60, Math.min(100, prefs.getInt("mgla_popup_alpha", 90)));
        final int duration = Math.max(1500, prefs.getInt("mgla_popup_duration_ms", 4000));

        MglaPopupNotificationView popup = new MglaPopupNotificationView(activity);
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        setupAvatar(popup.getAvatarView(), avatarDrawable, message);
        final boolean pulseEnabled = prefs.getBoolean("mgla_popup_pulse_enabled", true);
        BaseFragment glassFragment = LaunchActivity.getLastFragment();
        BlurredBackgroundDrawableViewFactory glassFactory = resolveGlassFactory(glassFragment);
        if (glassFactory != null) {
            View sourceRoot = glassFragment != null ? glassFragment.getFragmentView() : null;
            if (sourceRoot == null && glassFragment instanceof MainTabsActivity) {
                DialogsActivity dialogs = ((MainTabsActivity) glassFragment).getDialogsActivity();
                if (dialogs != null) {
                    sourceRoot = dialogs.getFragmentView();
                }
            }
            popup.attachGlass(glassFactory, sourceRoot);
        }
        popup.bind(
                getTitle(message),
                getMessagePreview(message),
                alpha,
                pulseEnabled,
                avatarDrawable.getColor(),
                avatarDrawable.getColor2(),
                this::openCurrentMessage
        );

        FrameLayout.LayoutParams lp = LayoutHelper.createFrameMarginPx(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                dp(12), AndroidUtilities.statusBarHeight + dp(8), dp(12), 0
        );
        activity.drawerLayoutContainer.addView(popup, lp);
        currentPopup = popup;
        hiding = false;
        popup.showAnimated();

        cancelHide();
        hideRunnable = () -> hidePopup(true);
        AndroidUtilities.runOnUIThread(hideRunnable, duration);
    }

    private BlurredBackgroundDrawableViewFactory resolveGlassFactory(BaseFragment last) {
        // Prefer the visible fragment's glass source (chat wallpaper / dialogs list).
        // MainTabs factory alone samples tab content and looks flat over ChatActivity.
        BlurredBackgroundDrawableViewFactory fromLast = liquidGlassFromFragment(last);
        if (fromLast != null) {
            return fromLast;
        }
        if (last instanceof MainTabsActivity) {
            fromLast = liquidGlassFromFragment(((MainTabsActivity) last).getDialogsActivity());
            if (fromLast != null) {
                return fromLast;
            }
            fromLast = ((MainTabsActivity) last).getLiquidGlassFactory();
            if (fromLast != null) {
                return fromLast;
            }
        }
        MainTabsActivity tabs = LaunchActivity.findFragment(MainTabsActivity.class);
        if (tabs != null) {
            BlurredBackgroundDrawableViewFactory factory = liquidGlassFromFragment(tabs.getDialogsActivity());
            if (factory != null) {
                return factory;
            }
            return tabs.getLiquidGlassFactory();
        }
        return null;
    }

    private static BlurredBackgroundDrawableViewFactory liquidGlassFromFragment(BaseFragment fragment) {
        if (fragment instanceof ChatActivity) {
            return ((ChatActivity) fragment).getLiquidGlassFactory();
        }
        if (fragment instanceof DialogsActivity) {
            return ((DialogsActivity) fragment).getLiquidGlassFactory();
        }
        return null;
    }

    private void setupAvatar(org.telegram.ui.Components.BackupImageView avatarView, AvatarDrawable avatarDrawable, MessageObject message) {
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
        cancelHide();
        final MglaPopupNotificationView popup = currentPopup;
        if (popup == null) {
            return;
        }
        if (!animated || hiding) {
            removePopupImmediate();
            return;
        }
        hiding = true;
        popup.hideAnimated(this::removePopupImmediate);
    }

    private void removePopupImmediate() {
        hiding = false;
        final MglaPopupNotificationView popup = currentPopup;
        currentPopup = null;
        if (popup == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) popup.getParent();
        if (parent != null) {
            parent.removeView(popup);
        }
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(hideRunnable);
            hideRunnable = null;
        }
    }
}
