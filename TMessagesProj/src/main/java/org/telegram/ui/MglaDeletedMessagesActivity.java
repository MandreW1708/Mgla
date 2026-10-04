package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Per-chat archive of deleted messages: everything that was deleted in this dialog, by anyone,
 * including the user's own messages. Rendered with the regular chat cells so media behaves as
 * it does in the chat itself.
 */
public class MglaDeletedMessagesActivity extends BaseFragment {

    private static final int PAGE_SIZE = 50;
    private static final int menu_clear = 1;
    private static final int menu_more = 2;

    private static final int[] MSG_TYPE_ICONS = {
        R.drawable.msg_voicechat,   // voice
        R.drawable.input_video_story, // round
        R.drawable.msg_message,     // text
        R.drawable.msg_photos,      // photo
        R.drawable.msg_video,       // video
    };
    private static final int[] MSG_TYPE_ICON_COLORS = {
        0xFF9A8CFF, // voice — purple
        0xFFFF8A65, // round — coral
        0xFF5AC8FA, // text — cyan
        0xFF5FA8D3, // photo — blue
        0xFFD3585F, // video — red
    };

    private final long dialogId;
    private final long topicId;

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private TextView emptyView;

    /** Oldest first, so the newest message sits at the bottom like in a chat. */
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private int loadedCount;
    private boolean allLoaded;
    private boolean loading;

    public MglaDeletedMessagesActivity(long dialogId, long topicId) {
        this.dialogId = dialogId;
        this.topicId = topicId;
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        Theme.createChatResources(context, false);

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("Удалённые");
        actionBar.setSubtitle(buildSubtitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == menu_clear) {
                    showClearAlert();
                } else if (id == menu_more) {
                    showSaveFiltersSheet();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(menu_clear, R.drawable.msg_clear);
        menu.addItem(menu_more, R.drawable.ic_ab_other).setContentDescription("Ещё");

        contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());
        fragmentView = contentView;

        emptyView = new TextView(context);
        emptyView.setText("Здесь пока нет удалённых сообщений");
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        emptyView.setBackground(Theme.createServiceDrawable(dp(16), emptyView, contentView, Theme.chat_actionBackgroundPaint));
        emptyView.setPadding(dp(12), dp(6), dp(12), dp(7));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        listView = new RecyclerListView(context);
        listView.setAdapter(adapter = new ListAdapter(context));
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        listView.setVerticalScrollBarEnabled(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        listView.setOnItemLongClickListener((view, position) -> {
            MessageObject message = adapter.getMessage(position);
            if (message == null || message.isDateObject) {
                return false;
            }
            showDeleteAlert(message);
            return true;
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!loading && !allLoaded && layoutManager.findFirstVisibleItemPosition() <= 4) {
                    loadNextPage();
                }
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadNextPage();
        return fragmentView;
    }

    private String buildSubtitle() {
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : "";
        }
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        return chat != null ? chat.title : "";
    }

    // region data

    private void loadNextPage() {
        if (loading || allLoaded || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            updateEmptyView();
            return;
        }
        loading = true;
        final int offset = loadedCount;
        getMessagesStorage().loadMglaDeletedMessages(dialogId, topicId, PAGE_SIZE, offset, loaded -> {
            loading = false;
            if (loaded == null || loaded.isEmpty()) {
                allLoaded = true;
                updateEmptyView();
                return;
            }
            if (loaded.size() < PAGE_SIZE) {
                allLoaded = true;
            }
            loadedCount += loaded.size();

            // Storage returns newest first; prepend in reverse so the list stays oldest-first.
            ArrayList<MessageObject> page = new ArrayList<>(loaded.size());
            for (int i = loaded.size() - 1; i >= 0; i--) {
                TLRPC.Message tl = loaded.get(i);
                if (tl == null) {
                    continue;
                }
                MessageObject obj = new MessageObject(currentAccount, tl, true, true);
                obj.mglaDeleted = true;
                obj.deleted = false;
                obj.setIsRead();
                page.add(obj);
            }
            messages.addAll(0, page);
            rebuildDateSeparators();
            getFileLoader().checkMediaExistance(messages);
            adapter.notifyDataSetChanged();
            if (offset == 0) {
                layoutManager.scrollToPosition(adapter.getItemCount() - 1);
            }
            actionBar.setSubtitle(buildSubtitle());
            updateEmptyView();
        });
    }

    /** Rebuilds the day dividers over the current (oldest-first) message list. */
    private void rebuildDateSeparators() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isDateObject) {
                messages.remove(i);
            }
        }
        int prevDateKey = Integer.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message.dateKeyInt != prevDateKey) {
                prevDateKey = message.dateKeyInt;
                messages.add(i, createDateObject(message.messageOwner.date));
                i++;
            }
        }
    }

    private MessageObject createDateObject(int date) {
        TLRPC.Message dateMsg = new TLRPC.TL_message();
        dateMsg.message = LocaleController.formatDateChat(date);
        dateMsg.id = 0;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(date * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        dateMsg.date = (int) (calendar.getTimeInMillis() / 1000);

        MessageObject dateObj = new MessageObject(currentAccount, dateMsg, false, false);
        dateObj.type = MessageObject.TYPE_DATE;
        dateObj.contentType = 1;
        dateObj.isDateObject = true;
        return dateObj;
    }

    private void updateEmptyView() {
        boolean empty = true;
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).isDateObject) {
                empty = false;
                break;
            }
        }
        emptyView.setVisibility(empty && !loading ? View.VISIBLE : View.GONE);
    }

    // endregion

    // region actions

    private void showSaveFiltersSheet() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, getResourceProvider());

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, dp(8));

        HeaderCell header = new HeaderCell(context, getResourceProvider());
        header.setText("Сохранять при удалении");
        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView hint = new TextView(context);
        hint.setText("Выберите типы сообщений для этого чата");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
        hint.setPadding(dp(22), 0, dp(22), dp(8));
        container.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        for (int i = 0; i < MglaSpyConfig.MSG_TYPE_ORDER.length; i++) {
            final int msgType = MglaSpyConfig.MSG_TYPE_ORDER[i];
            boolean checked = MglaSpyConfig.isSaveDeletedMsgTypeEnabled(dialogId, msgType);
            boolean divider = i < MglaSpyConfig.MSG_TYPE_ORDER.length - 1;

            TextCheckCell cell = new TextCheckCell(context, 21, true, getResourceProvider());
            cell.setTextAndCheck(MglaSpyConfig.MSG_TYPE_NAMES[msgType], checked, divider);
            cell.setColorfullIcon(MSG_TYPE_ICON_COLORS[msgType], MSG_TYPE_ICONS[msgType]);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 2));
            cell.setOnClickListener(v -> {
                boolean newVal = !cell.isChecked();
                MglaSpyConfig.setSaveDeletedMsgTypeEnabled(dialogId, msgType, newVal);
                cell.setChecked(newVal);
            });
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        builder.setCustomView(container);
        showDialog(builder.create());
    }

    private void showDeleteAlert(MessageObject message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Убрать из удалённых");
        builder.setMessage("Удалить сохранённую копию этого сообщения?");
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            getMessagesStorage().deleteMglaDeletedMessage(dialogId, message.getId(), null);
            messages.remove(message);
            rebuildDateSeparators();
            loadedCount = Math.max(0, loadedCount - 1);
            adapter.notifyDataSetChanged();
            updateEmptyView();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void showClearAlert() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Очистить удалённые");
        builder.setMessage("Удалить все сохранённые удалённые сообщения этого чата?");
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
            getMessagesStorage().clearMglaDeletedMessages(dialogId, topicId, () -> {
                messages.clear();
                loadedCount = 0;
                allLoaded = true;
                adapter.notifyDataSetChanged();
                updateEmptyView();
            })
        );
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void openMedia(MessageObject message) {
        if (message == null || getParentActivity() == null) {
            return;
        }
        if (message.isVoice() || message.isRoundVideo() || message.isMusic()) {
            MediaController.getInstance().playMessage(message);
            return;
        }
        if (message.isVideo() || message.type == MessageObject.TYPE_PHOTO || message.isGif()
                || message.type == MessageObject.TYPE_TEXT && !message.isWebpageDocument()) {
            PhotoViewer.getInstance().setParentActivity(this);
            PhotoViewer.getInstance().openPhoto(message, null, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
        }
    }

    private void openProfile(long userId) {
        if (userId == 0) {
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        args.putLong("user_id", userId);
        presentFragment(new ProfileActivity(args));
    }

    // endregion

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        MessageObject getMessage(int position) {
            if (position < 0 || position >= messages.size()) {
                return null;
            }
            return messages.get(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 0;
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            MessageObject message = getMessage(position);
            return message != null ? message.contentType : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                ChatMessageCell cell = new ChatMessageCell(mContext, currentAccount);
                cell.setFullyDraw(true);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public void didPressImage(ChatMessageCell cell, float x, float y, boolean fullPreview) {
                        openMedia(cell.getMessageObject());
                    }

                    @Override
                    public void didPressOther(ChatMessageCell cell, float otherX, float otherY) {
                        openMedia(cell.getMessageObject());
                    }

                    @Override
                    public void didPressUserAvatar(ChatMessageCell cell, TLRPC.User user, float touchX, float touchY, boolean asForward) {
                        if (user != null) {
                            openProfile(user.id);
                        }
                    }
                });
                view = cell;
            } else {
                ChatActionCell actionCell = new ChatActionCell(mContext);
                actionCell.setDelegate(new ChatActionCell.ChatActionCellDelegate() {
                });
                view = actionCell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            MessageObject message = getMessage(position);
            if (message == null) {
                return;
            }
            if (holder.itemView instanceof ChatMessageCell) {
                ChatMessageCell cell = (ChatMessageCell) holder.itemView;
                cell.isChat = dialogId < 0;
                cell.isMegagroup = isMegagroup();
                int parentW = listView.getMeasuredWidth();
                int parentH = listView.getMeasuredHeight();
                if (parentW <= 0) {
                    parentW = contentView.getMeasuredWidth();
                }
                if (parentH <= 0) {
                    parentH = contentView.getMeasuredHeight();
                }
                if (parentW <= 0) {
                    parentW = AndroidUtilities.displaySize.x;
                }
                if (parentH <= 0) {
                    parentH = Math.max(1, AndroidUtilities.displaySize.y);
                }
                cell.setParentViewSize(parentW, parentH);
                cell.setFullyDraw(true);

                boolean pinnedTop = false;
                boolean pinnedBottom = false;
                MessageObject prev = getMessage(position - 1);
                MessageObject next = getMessage(position + 1);
                if (prev != null && !prev.isDateObject) {
                    pinnedTop = prev.isOutOwner() == message.isOutOwner()
                        && prev.getFromChatId() == message.getFromChatId()
                        && Math.abs(prev.messageOwner.date - message.messageOwner.date) <= 5 * 60;
                }
                if (next != null && !next.isDateObject) {
                    pinnedBottom = next.isOutOwner() == message.isOutOwner()
                        && next.getFromChatId() == message.getFromChatId()
                        && Math.abs(next.messageOwner.date - message.messageOwner.date) <= 5 * 60;
                }
                cell.setMessageObject(message, null, pinnedBottom, pinnedTop, false);
                cell.setHighlighted(false);
                cell.setCheckPressed(true, false);
            } else if (holder.itemView instanceof ChatActionCell) {
                ((ChatActionCell) holder.itemView).setMessageObject(message);
            }
        }
    }

    private boolean isMegagroup() {
        if (dialogId >= 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat != null && ChatObject.isMegagroup(chat);
    }
}
