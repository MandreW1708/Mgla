package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MglaAiChatService;
import org.telegram.messenger.MglaAiChatStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MenuDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;

/** Persistent, streamed AI chat used by the AI item in the bottom island. */
public class MglaAiChatActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int MENU_OVERFLOW = 1;
    private static final int MENU_NEW = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_CHATS = 4;

    private final boolean hasMainTabs;
    private SizeNotifierFrameLayout root;
    private ScrollView scroll;
    private LinearLayout messages;
    private EditText input;
    private TextView send;
    private TextView provider;
    private TextView attachmentBadge;
    private View composer;
    private View drawerScrim;
    private FrameLayout drawer;
    private MenuDrawable menuDrawable;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private int lastKeyboardHeight;
    private final ArrayList<String> attachedPhotos = new ArrayList<>();
    private MglaAiChatStorage.Chat chat;
    private MglaAiChatService.Request request;
    private TextView streamedBubble;
    private String streamedText = "";

    public MglaAiChatActivity(Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs");
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("ИИ");
        actionBar.setAllowOverlayTitle(true);
        if (hasMainTabs) {
            menuDrawable = new MenuDrawable();
            actionBar.setBackButtonDrawable(menuDrawable);
        } else {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }

        ActionBarMenuItem menu = actionBar.createMenu().addItem(MENU_OVERFLOW, R.drawable.ic_ab_other);
        menu.addSubItem(MENU_CHATS, R.drawable.msg_folders, "Чаты");
        menu.addSubItem(MENU_NEW, R.drawable.msg_add, "Новый чат");
        menu.addSubItem(MENU_SETTINGS, R.drawable.msg_settings, "Настройки ИИ");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (hasMainTabs) {
                        showHistory();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_CHATS) {
                    showHistory();
                } else if (id == MENU_NEW) {
                    newChat();
                } else if (id == MENU_SETTINGS) {
                    showSettings();
                }
            }
        });

        root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(12), AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(12), dp(12), dp(116));
        scroll.addView(messages, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        createComposer(context);
        root.setDelegate((keyboardHeight, isWidthGreater) -> applyKeyboardInset(keyboardHeight));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            applyKeyboardInset(Math.max(ime.bottom, root.measureKeyboardHeight()));
            return insets;
        });
        keyboardLayoutListener = () -> {
            if (root == null) {
                return;
            }
            applyKeyboardInset(Math.max(lastKeyboardHeight, root.measureKeyboardHeight()));
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);

        SharedPreferences p = prefs();
        ArrayList<MglaAiChatStorage.Chat> saved = MglaAiChatStorage.getChats(currentAccount);
        if (!saved.isEmpty()) {
            chat = saved.get(0);
        } else {
            chat = MglaAiChatStorage.newChat(
                    p.getString("ai_chat_provider", MglaAiChatService.PROVIDER_OPENROUTER),
                    p.getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL)
            );
        }
        renderChat(false);
        updateComposerInset(0);
        return fragmentView;
    }

    private void createComposer(Context c) {
        LinearLayout box = new LinearLayout(c);
        composer = box;
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(round(Theme.getColor(Theme.key_windowBackgroundWhite), 22));

        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        if (ColorUtils.calculateLuminance(accent) > 0.55f && ColorUtils.calculateLuminance(onAccent) > 0.55f) {
            onAccent = 0xFF17212B;
        } else if (ColorUtils.calculateLuminance(accent) < 0.35f && ColorUtils.calculateLuminance(onAccent) < 0.35f) {
            onAccent = Color.WHITE;
        }

        provider = new TextView(c);
        provider.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        provider.setTextColor(onAccent);
        provider.setGravity(Gravity.CENTER_VERTICAL);
        provider.setPadding(dp(12), 0, dp(12), 0);
        provider.setBackground(round(accent, 16));
        provider.setOnClickListener(v -> chooseProvider());
        box.addView(provider, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.LEFT, 0, 0, 0, 6));

        attachmentBadge = new TextView(c);
        attachmentBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        attachmentBadge.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        attachmentBadge.setPadding(dp(10), 0, dp(10), dp(5));
        attachmentBadge.setVisibility(View.GONE);
        box.addView(attachmentBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.BOTTOM);

        TextView attach = new TextView(c);
        attach.setText("📎");
        attach.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        attach.setGravity(Gravity.CENTER);
        attach.setTextColor(Theme.getColor(Theme.key_chat_messagePanelIcons));
        attach.setOnClickListener(v -> openPhotoPicker());
        row.addView(attach, LayoutHelper.createLinear(40, 44, Gravity.BOTTOM));

        input = new EditText(c);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint("Сообщение");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(5);
        input.setSingleLine(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        send = new TextView(c);
        send.setText("➤");
        send.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        send.setGravity(Gravity.CENTER);
        send.setTextColor(onAccent);
        send.setBackground(round(accent, 22));
        send.setOnClickListener(v -> {
            if (request == null) {
                sendMessage();
            } else {
                stopRequest();
            }
        });
        row.addView(send, LayoutHelper.createLinear(44, 44, Gravity.BOTTOM, dp(8), 0, 0, 0));
        box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(box, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM,
                dp(8), 0, dp(8), idleBottomMargin()
        ));
    }

    private int idleBottomMargin() {
        int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : dp(6);
        return AndroidUtilities.navigationBarHeight + tabs;
    }

    private void applyKeyboardInset(int keyboardHeight) {
        if (keyboardHeight < 0) {
            keyboardHeight = 0;
        }
        if (Math.abs(keyboardHeight - lastKeyboardHeight) < dp(2) && composer != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) composer.getLayoutParams();
            int expected = keyboardHeight > dp(20) ? keyboardHeight + dp(6) : idleBottomMargin();
            if (lp != null && lp.bottomMargin == expected) {
                return;
            }
        }
        lastKeyboardHeight = keyboardHeight;
        updateComposerInset(keyboardHeight);
    }

    private void updateComposerInset(int keyboardHeight) {
        if (composer == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) composer.getLayoutParams();
        if (lp == null) {
            return;
        }
        boolean keyboardVisible = keyboardHeight > dp(20);
        int bottom = keyboardVisible ? keyboardHeight + dp(6) : idleBottomMargin();
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            composer.setLayoutParams(lp);
        }
        int composerH = composer.getHeight();
        if (composerH <= 0) {
            composerH = dp(64);
        }
        messages.setPadding(
                dp(12),
                AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(12),
                dp(12),
                composerH + bottom + dp(12)
        );
        if (keyboardVisible) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void renderChat(boolean animate) {
        messages.removeAllViews();
        streamedBubble = null;
        streamedText = "";
        updateProvider();
        if (chat.messages.isEmpty()) {
            TextView title = text("Чем могу помочь?", 26, Theme.key_windowBackgroundWhiteBlackText);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(38), 0, dp(10));
            messages.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView hint = text("Новый диалог начинается с чистого контекста.\nИстория хранится только на устройстве.", 15, Theme.key_windowBackgroundWhiteGrayText);
            hint.setGravity(Gravity.CENTER);
            messages.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            for (MglaAiChatStorage.Message m : chat.messages) {
                addBubble(m.role, m.text, animate);
            }
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addBubble(String role, String value, boolean animate) {
        boolean mine = "user".equals(role);
        int bubbleColor = Theme.getColor(mine ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhite);
        TextView b = text(value, 16, Theme.key_windowBackgroundWhiteBlackText);
        b.setTextColor(ColorUtils.calculateLuminance(bubbleColor) < .42f ? Color.WHITE : 0xFF17212B);
        b.setLineSpacing(dp(2), 1);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(round(bubbleColor, mine ? 22 : 18));
        messages.addView(b, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                mine ? Gravity.RIGHT : Gravity.LEFT,
                mine ? dp(40) : 0, 0, mine ? 0 : dp(40), dp(10)
        ));
        if (animate) {
            b.setAlpha(0);
            b.setTranslationY(dp(10));
            b.animate().alpha(1).translationY(0).setDuration(180).start();
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void openPhotoPicker() {
        PhotoAlbumPickerActivity picker = new PhotoAlbumPickerActivity(PhotoAlbumPickerActivity.SELECT_TYPE_ALL, false, true, null);
        picker.setMaxSelectedPhotos(4, true);
        picker.setDelegate(new PhotoAlbumPickerActivity.PhotoAlbumPickerActivityDelegate() {
            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
                attachedPhotos.clear();
                for (SendMessagesHelper.SendingMediaInfo photo : photos) {
                    String path = !TextUtils.isEmpty(photo.path) ? photo.path : photo.imagePath;
                    if (!TextUtils.isEmpty(path)) {
                        attachedPhotos.add(path);
                    }
                }
                updateAttachmentBadge();
            }

            @Override
            public void startPhotoSelectActivity() {
            }
        });
        presentFragment(picker);
    }

    private void updateAttachmentBadge() {
        if (attachmentBadge == null) {
            return;
        }
        attachmentBadge.setVisibility(attachedPhotos.isEmpty() ? View.GONE : View.VISIBLE);
        attachmentBadge.setText("📷  Выбрано фото: " + attachedPhotos.size());
    }

    private void sendMessage() {
        if (request != null) {
            return;
        }
        String value = input.getText().toString().trim();
        if (TextUtils.isEmpty(value) && attachedPhotos.isEmpty()) {
            return;
        }
        input.setText("");
        if (!attachedPhotos.isEmpty()) {
            value = (TextUtils.isEmpty(value) ? "" : value + "\n") + "📷 Прикреплено фото: " + attachedPhotos.size();
        }
        ArrayList<String> imagesForRequest = new ArrayList<>(attachedPhotos);
        chat.messages.add(new MglaAiChatStorage.Message("user", value, System.currentTimeMillis()));
        addBubble("user", value, true);
        chat.messages.add(new MglaAiChatStorage.Message("assistant", "", System.currentTimeMillis()));
        addBubble("assistant", "Генерирую…", true);
        streamedBubble = (TextView) messages.getChildAt(messages.getChildCount() - 1);
        streamedText = "";
        send.setText("■");
        request = MglaAiChatService.send(
                chat.provider, chat.model, chat.messages, imagesForRequest,
                prefs().getFloat("ai_chat_temperature", .7f),
                prefs().getInt("ai_chat_max_tokens", 1024),
                new MglaAiChatService.Callback() {
                    @Override
                    public void onDelta(String token) {
                        streamedText += token;
                        if (streamedBubble != null) {
                            streamedBubble.setText(streamedText);
                        }
                        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                    }

                    @Override
                    public void onComplete() {
                        finishRequest();
                    }

                    @Override
                    public void onError(String error) {
                        if (streamedText.length() == 0) {
                            streamedText = "Ошибка: " + error;
                        }
                        if (streamedBubble != null) {
                            streamedBubble.setText(streamedText);
                        }
                        finishRequest();
                    }
                }
        );
        attachedPhotos.clear();
        updateAttachmentBadge();
    }

    private void finishRequest() {
        if (!chat.messages.isEmpty()) {
            chat.messages.get(chat.messages.size() - 1).text = streamedText;
        }
        request = null;
        if (send != null) {
            send.setText("➤");
        }
        MglaAiChatStorage.save(currentAccount, chat);
    }

    private void stopRequest() {
        if (request != null) {
            request.cancel();
        }
        request = null;
        if (send != null) {
            send.setText("➤");
        }
    }

    private void newChat() {
        stopRequest();
        SharedPreferences p = prefs();
        chat = MglaAiChatStorage.newChat(
                p.getString("ai_chat_provider", MglaAiChatService.PROVIDER_OPENROUTER),
                p.getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL)
        );
        renderChat(true);
    }

    private void chooseProvider() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Модель для этого чата")
                .setItems(new String[]{"OpenRouter", "Локальная GGUF"}, (d, n) -> {
                    chat.provider = n == 0 ? MglaAiChatService.PROVIDER_OPENROUTER : MglaAiChatService.PROVIDER_LOCAL;
                    prefs().edit().putString("ai_chat_provider", chat.provider).apply();
                    updateProvider();
                })
                .show();
    }

    private void updateProvider() {
        if (provider == null || chat == null) {
            return;
        }
        provider.setText(MglaAiChatService.PROVIDER_LOCAL.equals(chat.provider)
                ? "⌁  Локальная модель"
                : "✦  OpenRouter · " + shortModel(chat.model));
    }

    private String shortModel(String s) {
        if (TextUtils.isEmpty(s)) {
            return "Авто";
        }
        int i = s.lastIndexOf('/');
        return (i < 0 ? s : s.substring(i + 1)).replace(":free", "");
    }

    private void showHistory() {
        if (drawer != null) {
            closeDrawer();
            return;
        }
        Context c = getParentActivity();
        if (c == null || root == null) {
            return;
        }
        if (menuDrawable != null) {
            menuDrawable.setRotation(1f, true);
        }

        drawerScrim = new View(c);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setAlpha(0);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        drawer = new FrameLayout(c);
        drawer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        drawer.setTranslationX(-dp(320));
        drawer.setElevation(dp(8));

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, AndroidUtilities.statusBarHeight + dp(8), 0, dp(16) + idleBottomMargin());

        TextView header = text("Чаты", 24, Theme.key_windowBackgroundWhiteBlackText);
        header.setTypeface(AndroidUtilities.bold());
        header.setPadding(dp(20), dp(8), dp(20), dp(12));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View add = historyRow(c, "+  Новый чат", "Начать без предыдущего контекста", false);
        add.setOnClickListener(v -> {
            newChat();
            closeDrawer();
        });
        list.addView(add);

        ArrayList<MglaAiChatStorage.Chat> chats = MglaAiChatStorage.getChats(currentAccount);
        if (chats.isEmpty()) {
            TextView empty = text("Пока нет сохранённых диалогов", 14, Theme.key_windowBackgroundWhiteGrayText);
            empty.setPadding(dp(20), dp(16), dp(20), dp(8));
            list.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            for (MglaAiChatStorage.Chat saved : chats) {
                boolean selected = chat != null && saved.id.equals(chat.id);
                String subtitle = MglaAiChatService.PROVIDER_LOCAL.equals(saved.provider) ? "Локальная модель" : "OpenRouter";
                View row = historyRow(c, saved.title, subtitle, selected);
                row.setOnClickListener(v -> {
                    stopRequest();
                    chat = saved;
                    renderChat(true);
                    closeDrawer();
                });
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle(saved.title)
                            .setItems(new CharSequence[]{"Удалить"}, (d, which) -> {
                                MglaAiChatStorage.delete(currentAccount, saved.id);
                                if (chat != null && saved.id.equals(chat.id)) {
                                    newChat();
                                }
                                closeDrawer();
                                showHistory();
                            })
                            .show();
                    return true;
                });
                list.addView(row);
            }
        }

        ScrollView s = new ScrollView(c);
        s.addView(list);
        drawer.addView(s, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(drawer, LayoutHelper.createFrame(Math.min(dp(310), AndroidUtilities.displaySize.x - dp(56)), LayoutHelper.MATCH_PARENT, Gravity.LEFT));
        drawerScrim.animate().alpha(1).setDuration(180).start();
        drawer.animate().translationX(0).setDuration(220).start();
    }

    private void closeDrawer() {
        if (drawer == null) {
            return;
        }
        if (menuDrawable != null) {
            menuDrawable.setRotation(0f, true);
        }
        View panel = drawer;
        View scrim = drawerScrim;
        panel.animate().translationX(-panel.getWidth()).setDuration(180).withEndAction(() -> {
            if (drawer != null) {
                root.removeView(drawer);
            }
            if (drawerScrim != null) {
                root.removeView(drawerScrim);
            }
            drawer = null;
            drawerScrim = null;
        }).start();
        if (scrim != null) {
            scrim.animate().alpha(0).setDuration(180).start();
        }
    }

    private void showSettings() {
        Context c = getParentActivity();
        LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(4), dp(24), dp(6));
        EditText model = settingField(c, form, "Модель OpenRouter", prefs().getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL), "provider/model");
        EditText temperature = settingField(c, form, "Температура (0–2)", String.valueOf(prefs().getFloat("ai_chat_temperature", .7f)), "0.7");
        EditText tokens = settingField(c, form, "Максимум токенов ответа", String.valueOf(prefs().getInt("ai_chat_max_tokens", 1024)), "1024");
        EditText context = settingField(c, form, "Контекст локальной модели", String.valueOf(prefs().getInt("ai_local_context", 2048)), "2048");
        EditText threads = settingField(c, form, "Потоки CPU для локальной модели", String.valueOf(prefs().getInt("ai_local_threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1))), "4");
        EditText unload = settingField(c, form, "Выгружать модель через, сек.", String.valueOf(prefs().getInt("ai_local_unload_seconds", 120)), "120");
        new AlertDialog.Builder(c).setTitle("Настройки ИИ").setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (d, w) -> {
            try {
                prefs().edit()
                        .putString("ai_chat_model", model.getText().toString().trim())
                        .putFloat("ai_chat_temperature", Math.max(0, Math.min(2, Float.parseFloat(temperature.getText().toString()))))
                        .putInt("ai_chat_max_tokens", Math.max(32, Math.min(4096, Integer.parseInt(tokens.getText().toString()))))
                        .putInt("ai_local_context", Math.max(512, Math.min(8192, Integer.parseInt(context.getText().toString()))))
                        .putInt("ai_local_threads", Math.max(1, Math.min(8, Integer.parseInt(threads.getText().toString()))))
                        .putInt("ai_local_unload_seconds", Math.max(15, Math.min(3600, Integer.parseInt(unload.getText().toString()))))
                        .apply();
                chat.model = prefs().getString("ai_chat_model", MglaAiChatService.DEFAULT_MODEL);
                updateProvider();
            } catch (Throwable ignore) {
            }
        }).show();
    }

    private EditText settingField(Context c, LinearLayout form, String label, String value, String hint) {
        TextView title = text(label, 13, Theme.key_dialogTextGray2);
        title.setPadding(0, dp(10), 0, 0);
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        EditText field = new EditText(c);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        form.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        return field;
    }

    private View historyRow(Context c, String title, String sub, boolean selected) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        if (selected) {
            row.setBackgroundColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 28));
        }
        TextView t = text(title, 16, Theme.key_windowBackgroundWhiteBlackText);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView s = text(sub, 13, Theme.key_windowBackgroundWhiteGrayText);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private TextView text(String s, int size, int colorKey) {
        TextView v = new TextView(getParentActivity());
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        v.setTextColor(Theme.getColor(colorKey));
        return v;
    }

    private GradientDrawable round(int c, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c);
        d.setCornerRadius(dp(r));
        return d;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (drawer != null) {
            closeDrawer();
            return false;
        }
        if (request != null) {
            stopRequest();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        if (root != null && keyboardLayoutListener != null) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
            keyboardLayoutListener = null;
        }
        closeDrawer();
        stopRequest();
        super.onFragmentDestroy();
    }
}
