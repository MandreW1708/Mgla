package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.MglaStats;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Search index + deep links ({@code tg://settings/mgla/...}) for Mgla settings.
 * One table drives both, so a search result and its copied link always land on
 * the same screen and flash the same row.
 */
public final class MglaSettingsSearch {

    public static final String HUB = "Настройки Mgla";
    private static final String LINK_PREFIX = "tg://settings/mgla";

    private static final class Entry {
        final int guid;
        final String title;
        final String path1;
        final String path2;
        final int icon;
        final Utilities.Callback0Return<BaseFragment> screen;
        /** Exact on-screen label of the row to flash; null = just open the screen. */
        final String highlight;
        /** Path after {@code tg://settings/mgla}, e.g. "general/ghost-mode"; "" for the hub. */
        final String path;

        Entry(int guid, String title, String path1, String path2, int icon,
              Utilities.Callback0Return<BaseFragment> screen, String highlight, String path) {
            this.guid = guid;
            this.title = title;
            this.path1 = path1;
            this.path2 = path2;
            this.icon = icon;
            this.screen = screen;
            this.highlight = highlight;
            this.path = path;
        }

        String link() {
            return path.isEmpty() ? LINK_PREFIX : LINK_PREFIX + "/" + path;
        }
    }

    private static List<Entry> entries;

    private MglaSettingsSearch() {
    }

    public static SearchResult[] mergeInto(SearchResult[] stock, BaseFragment f) {
        SearchResult[] extra = createEntries(f);
        SearchResult[] out = Arrays.copyOf(stock, stock.length + extra.length);
        System.arraycopy(extra, 0, out, stock.length, extra.length);
        return out;
    }

    public static SearchResult[] createEntries(final BaseFragment f) {
        List<Entry> list = getEntries();
        SearchResult[] out = new SearchResult[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Entry e = list.get(i);
            out[i] = new SearchResult(e.guid, e.title, null, e.path1, e.path2, e.icon,
                () -> openEntry(fragment -> f.presentFragment(fragment), e)).withLink(e.link());
        }
        return out;
    }

    /**
     * @param segments path after {@code tg://settings/mgla} (may be empty)
     */
    public static boolean open(Utilities.Callback<BaseFragment> present, List<String> segments) {
        ArrayList<String> parts = new ArrayList<>();
        if (segments != null) {
            for (String s : segments) {
                if (!TextUtils.isEmpty(s)) {
                    parts.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!parts.isEmpty() && "dpi".equals(parts.get(0))) {
            parts.set(0, "connection");
        }
        MglaStats.count("deeplink:mgla/" + TextUtils.join("/", parts));
        // Exact match first, then fall back to the closest parent screen.
        for (int len = parts.size(); len >= 0; len--) {
            Entry entry = findByPath(TextUtils.join("/", parts.subList(0, len)));
            if (entry != null) {
                openEntry(present, entry);
                return true;
            }
        }
        present.run(new MglaSettingsActivity());
        return true;
    }

    private static void openEntry(Utilities.Callback<BaseFragment> present, Entry e) {
        BaseFragment fragment = e.screen.run();
        present.run(fragment);
        MglaSettingsHighlight.highlight(fragment, e.highlight);
    }

    private static Entry findByPath(String path) {
        for (Entry e : getEntries()) {
            if (e.path.equals(path)) {
                return e;
            }
        }
        return null;
    }

    private static List<Entry> getEntries() {
        if (entries == null) {
            entries = buildEntries();
        }
        return entries;
    }

    private static List<Entry> buildEntries() {
        final String hub = HUB;
        final int hubIcon = R.drawable.settings_account;
        final int generalIcon = R.drawable.settings_features;
        final int chatsIcon = R.drawable.filled_chatlist2;
        final int notifIcon = R.drawable.settings_sounds;
        final int appearanceIcon = R.drawable.msg_palette;
        final int cameraIcon = R.drawable.filled_premium_camera;
        final int connectionIcon = R.drawable.mgla_dpi_shield;
        final int aiIcon = R.drawable.input_ai;

        final String general = "Общие настройки";
        final String chats = "Чаты";
        final String notif = "Уведомления";
        final String appearance = "Внешний вид";
        final String cleanHeader = "Настройки чистой шапки";
        final String sideMenu = "Боковое меню";
        final String camera = "Камера";
        final String connection = "Подключение";
        final String ai = "Искусственный интеллект";
        final String transcribe = "ИИ-расшифровка";

        final Utilities.Callback0Return<BaseFragment> hubScreen = MglaSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> generalScreen = MglaMainSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> chatsScreen = MglaChatsSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> notifScreen = MglaNotificationsSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> appearanceScreen = MglaAppearanceSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> cleanHeaderScreen = MglaCleanHeaderSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> sideMenuScreen = MglaSideMenuSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> cameraScreen = MglaCameraSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> connectionScreen = MglaDpiBypassActivity::new;
        final Utilities.Callback0Return<BaseFragment> aiScreen = MglaAiSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> transcribeScreen = MglaAiTranscribeActivity::new;

        ArrayList<Entry> l = new ArrayList<>();

        l.add(new Entry(1000, hub, null, null, hubIcon, hubScreen, null, ""));

        l.add(new Entry(1001, general, hub, null, generalIcon, generalScreen, null, "general"));
        l.add(new Entry(1002, chats, hub, null, chatsIcon, chatsScreen, null, "chats"));
        l.add(new Entry(1003, notif, hub, null, notifIcon, notifScreen, null, "notifications"));
        l.add(new Entry(1004, appearance, hub, null, appearanceIcon, appearanceScreen, null, "appearance"));
        l.add(new Entry(1005, camera, hub, null, cameraIcon, cameraScreen, null, "camera"));
        l.add(new Entry(1006, connection, hub, null, connectionIcon, connectionScreen, null, "connection"));
        l.add(new Entry(1007, ai, hub, null, aiIcon, aiScreen, null, "ai"));

        l.add(new Entry(1010, "Прокси в шапке", hub, general, generalIcon, generalScreen, "Прокси в шапке", "general/proxy-header"));
        l.add(new Entry(1011, "Загрузки в шапке", hub, general, generalIcon, generalScreen, "Загрузки в шапке", "general/downloads-header"));
        l.add(new Entry(1012, "Виброотклик", hub, general, generalIcon, generalScreen, "Виброотклик", "general/haptic"));
        l.add(new Entry(1013, "Сила вибрации", hub, general, generalIcon, generalScreen, "Сила вибрации", "general/haptic-strength"));
        l.add(new Entry(1014, "Последний онлайн", hub, general, generalIcon, generalScreen, "Последний онлайн", "general/last-online"));
        l.add(new Entry(1015, "Сохранение удаленных", hub, general, generalIcon, generalScreen, "Сохранение удаленных", "general/save-deleted"));
        l.add(new Entry(1016, "Режим призрака", hub, general, generalIcon, generalScreen, "Режим призрака", "general/ghost-mode"));
        l.add(new Entry(1017, "Ускорение загрузки", hub, general, generalIcon, generalScreen, "Ускорение загрузки", "general/download-speed"));
        l.add(new Entry(1018, "Ускорение отправки", hub, general, generalIcon, generalScreen, "Ускорение отправки", "general/upload-speed"));
        l.add(new Entry(1019, "Автопауза", hub, general, generalIcon, generalScreen, "Автопауза", "general/autopause"));
        l.add(new Entry(1030, "Элементы меню сообщения", hub, chats, chatsIcon, chatsScreen, "Элементы меню сообщения", "chats/message-menu"));
        l.add(new Entry(1031, "Время с секундами", hub, chats, chatsIcon, chatsScreen, "Время с секундами", "chats/time-seconds"));
        l.add(new Entry(1032, "Количество недавних стикеров", hub, chats, chatsIcon, chatsScreen, "Количество недавних стикеров", "chats/recent-stickers"));
        l.add(new Entry(1033, "Убрать время на стикерах", hub, chats, chatsIcon, chatsScreen, "Убрать время на стикерах", "chats/hide-sticker-time"));
        l.add(new Entry(1034, "Исходящее сообщение", hub, chats, chatsIcon, chatsScreen, "Исходящее сообщение", "chats/double-tap-out"));
        l.add(new Entry(1035, "Входящее сообщение", hub, chats, chatsIcon, chatsScreen, "Входящее сообщение", "chats/double-tap-in"));
        l.add(new Entry(1036, "Нижняя кнопка", hub, chats, chatsIcon, chatsScreen, "Нижняя кнопка", "chats/bottom-button"));
        l.add(new Entry(1037, "Скрывать клавиатуру при прокрутке", hub, chats, chatsIcon, chatsScreen, "Скрывать клавиатуру при прокрутке", "chats/hide-keyboard"));
        l.add(new Entry(1038, "Запятая после упоминания", hub, chats, chatsIcon, chatsScreen, "Запятая после упоминания", "chats/comma-mention"));

        l.add(new Entry(1050, "Всплывающие уведомления", hub, notif, notifIcon, notifScreen, "Всплывающие уведомления", "notifications/popup"));
        l.add(new Entry(1051, "Время отображения", hub, notif, notifIcon, notifScreen, "Время отображения", "notifications/duration"));
        l.add(new Entry(1052, "Прозрачность", hub, notif, notifIcon, notifScreen, "Прозрачность", "notifications/alpha"));

        l.add(new Entry(1060, "Чистая шапка", hub, appearance, appearanceIcon, appearanceScreen, "Чистая", "appearance/clean-header"));
        l.add(new Entry(1061, cleanHeader, hub, appearance, appearanceIcon, cleanHeaderScreen, null, "appearance/clean-header/configure"));
        l.add(new Entry(1062, "Кнопка назад", hub, cleanHeader, appearanceIcon, cleanHeaderScreen, "Кнопка назад", "appearance/clean-header/back"));
        l.add(new Entry(1063, "Блок с ником", hub, cleanHeader, appearanceIcon, cleanHeaderScreen, "Блок с ником", "appearance/clean-header/title"));
        l.add(new Entry(1064, "Блок с закрепом", hub, cleanHeader, appearanceIcon, cleanHeaderScreen, "Блок с закрепом", "appearance/clean-header/pinned"));
        l.add(new Entry(1065, "Панель перевода", hub, cleanHeader, appearanceIcon, cleanHeaderScreen, "Панель перевода", "appearance/clean-header/translation"));
        l.add(new Entry(1066, "Затемнение стекла", hub, appearance, appearanceIcon, appearanceScreen, "Затемнение стекла", "appearance/glass-darkening"));
        l.add(new Entry(1067, "Переключатели MD3", hub, appearance, appearanceIcon, appearanceScreen, "Переключатели MD3", "appearance/md3-switches"));
        l.add(new Entry(1068, "Значок вместо \"изменено\"", hub, appearance, appearanceIcon, appearanceScreen, "Значок вместо \"изменено\"", "appearance/edited-icon"));
        l.add(new Entry(1069, sideMenu, hub, appearance, appearanceIcon, sideMenuScreen, null, "appearance/side-menu"));
        l.add(new Entry(1070, "Включить боковое меню", hub, sideMenu, appearanceIcon, sideMenuScreen, "Включить боковое меню", "appearance/side-menu/enable"));
        l.add(new Entry(1071, "Настроить элементы", hub, sideMenu, appearanceIcon, sideMenuScreen, "Настроить элементы", "appearance/side-menu/elements"));
        l.add(new Entry(1072, "В стиле MD3", hub, appearance, appearanceIcon, appearanceScreen, "В стиле MD3", "appearance/md3-predictive-back"));

        l.add(new Entry(1080, "API", hub, camera, cameraIcon, cameraScreen, "API", "camera/api"));
        l.add(new Entry(1081, "60 FPS", hub, camera, cameraIcon, cameraScreen, "60 FPS", "camera/60fps"));

        l.add(new Entry(1090, "Обход через WebSocket", hub, connection, connectionIcon, connectionScreen, "Обход через WebSocket", "connection/websocket"));
        l.add(new Entry(1091, "Обход через ByeDPI", hub, connection, connectionIcon, connectionScreen, "Обход через ByeDPI", "connection/byedpi"));
        l.add(new Entry(1092, "Выбрать стратегию", hub, connection, connectionIcon, connectionScreen, "Выбрать стратегию", "connection/strategy"));
        l.add(new Entry(1093, "SNI фейк-пакетов", hub, connection, connectionIcon, connectionScreen, "SNI фейк-пакетов", "connection/sni"));
        l.add(new Entry(1094, "Подбор стратегий", hub, connection, connectionIcon, connectionScreen, "Подбор стратегий", "connection/tester"));

        l.add(new Entry(1100, "Включение AI", hub, ai, aiIcon, aiScreen, "Включение AI", "ai/enabled"));
        l.add(new Entry(1101, "Краткая Сводка", hub, ai, aiIcon, aiScreen, "Краткая Сводка", "ai/summary"));
        l.add(new Entry(1102, "Пересказ сообщений", hub, ai, aiIcon, aiScreen, "Пересказ сообщений", "ai/retell"));
        l.add(new Entry(1103, "AI-редактор", hub, ai, aiIcon, aiScreen, "AI-редактор", "ai/editor"));
        l.add(new Entry(1104, "Лимит запросов к AI", hub, ai, aiIcon, aiScreen, "Лимит запросов к AI", "ai/limit"));
        l.add(new Entry(1105, transcribe, hub, ai, aiIcon, aiScreen, transcribe, "ai/transcribe"));
        l.add(new Entry(1106, "Включить расшифровку", hub, transcribe, aiIcon, transcribeScreen, "Включить расшифровку", "ai/transcribe/enable"));
        l.add(new Entry(1107, "Главные темы Chat DNA", hub, ai, aiIcon, aiScreen, "Главные темы Chat DNA", "ai/chat-dna"));
        l.add(new Entry(1108, "Локальные модели", hub, ai, aiIcon, aiScreen, "Локальные модели", "ai/models"));

        return l;
    }
}
