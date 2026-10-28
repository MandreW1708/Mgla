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
        final int playerIcon = R.drawable.files_music;
        final int cameraIcon = R.drawable.filled_premium_camera;
        final int connectionIcon = R.drawable.mgla_dpi_shield;
        final int aiIcon = R.drawable.input_ai;

        final String general = "Общие настройки";
        final String chats = "Чаты";
        final String notif = "Уведомления";
        final String appearance = "Внешний вид";
        final String cleanHeader = "Настройки чистой шапки";
        final String sideMenu = "Боковое меню";
        final String player = "Плеер";
        final String camera = "Камера";
        final String connection = "Подключение без VPN";
        final String ai = "Искусственный интеллект";
        final String transcribe = "ИИ-расшифровка";

        final Utilities.Callback0Return<BaseFragment> hubScreen = MglaSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> generalScreen = MglaMainSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> hapticScreen = MglaHapticSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> chatsScreen = MglaChatsSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> notifScreen = MglaNotificationsSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> appearanceScreen = MglaAppearanceSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> cleanHeaderScreen = MglaCleanHeaderSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> sideMenuScreen = MglaSideMenuSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> animCommonScreen = () -> MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_COMMON);
        final Utilities.Callback0Return<BaseFragment> animChatsScreen = () -> MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_CHATS);
        final Utilities.Callback0Return<BaseFragment> animDialogsScreen = () -> MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_DIALOGS);
        final Utilities.Callback0Return<BaseFragment> playerScreen = MglaPlayerSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> cameraScreen = MglaCameraSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> connectionScreen = MglaDpiBypassActivity::new;
        final Utilities.Callback0Return<BaseFragment> aiScreen = MglaAiSettingsActivity::new;
        final Utilities.Callback0Return<BaseFragment> transcribeScreen = MglaAiTranscribeActivity::new;

        ArrayList<Entry> l = new ArrayList<>();

        l.add(new Entry(1000, hub, null, null, hubIcon, hubScreen, null, ""));

        l.add(new Entry(1134, "Mgla Premium", hub, null, R.drawable.settings_mgla_premium, MglaPremiumActivity::new, null, "premium"));
        l.add(new Entry(1001, general, hub, null, generalIcon, generalScreen, null, "general"));
        l.add(new Entry(1002, chats, hub, null, chatsIcon, chatsScreen, null, "chats"));
        l.add(new Entry(1003, notif, hub, null, notifIcon, notifScreen, null, "notifications"));
        l.add(new Entry(1004, appearance, hub, null, appearanceIcon, appearanceScreen, null, "appearance"));
        l.add(new Entry(1160, player, hub, null, playerIcon, playerScreen, null, "player"));
        l.add(new Entry(1005, camera, hub, null, cameraIcon, cameraScreen, null, "camera"));
        l.add(new Entry(1161, "Большая обложка", hub, player, playerIcon, playerScreen, "Большая обложка", "player/large-cover"));
        l.add(new Entry(1162, "Динамические цвета из обложки", hub, player, playerIcon, playerScreen, "Динамические цвета из обложки", "player/dynamic-colors"));
        l.add(new Entry(1163, "Анимация появления", hub, player, playerIcon, playerScreen, "Анимация появления", "player/anim/open"));
        l.add(new Entry(1164, "Смена трека", hub, player, playerIcon, playerScreen, "Смена трека", "player/anim/track"));
        l.add(new Entry(1165, "Пульсация обложки", hub, player, playerIcon, playerScreen, "Пульсация обложки", "player/anim/pulse"));
        l.add(new Entry(1166, "Анимация кнопок", hub, player, playerIcon, playerScreen, "Анимация кнопок", "player/anim/buttons"));
        l.add(new Entry(1167, "Движение фона", hub, player, playerIcon, playerScreen, "Движение фона", "player/anim/ambient"));
        l.add(new Entry(1168, "Анимированный таймлайн", hub, player, playerIcon, playerScreen, "Анимированный таймлайн", "player/anim/seekbar"));
        l.add(new Entry(1170, "Волна таймлайна", hub, player, playerIcon, playerScreen, "Волна таймлайна", "player/anim/seekbar-wave"));
        l.add(new Entry(1169, "Скорость анимаций", hub, player, playerIcon, playerScreen, "Скорость анимаций", "player/anim/speed"));
        l.add(new Entry(1006, connection, hub, null, connectionIcon, connectionScreen, null, "connection"));
        l.add(new Entry(1007, ai, hub, null, aiIcon, aiScreen, null, "ai"));

        l.add(new Entry(1010, "Прокси в шапке", hub, general, generalIcon, generalScreen, "Прокси в шапке", "general/proxy-header"));
        l.add(new Entry(1011, "Загрузки в шапке", hub, general, generalIcon, generalScreen, "Загрузки в шапке", "general/downloads-header"));
        final String haptic = "Виброэффекты";
        l.add(new Entry(1012, haptic, hub, general, generalIcon, hapticScreen, null, "general/haptic"));
        l.add(new Entry(1126, "Виброотклик", hub, haptic, generalIcon, hapticScreen, "Виброотклик", "general/haptic/enable"));
        l.add(new Entry(1013, "Сила вибрации", hub, haptic, generalIcon, hapticScreen, "Сила вибрации", "general/haptic/strength"));
        l.add(new Entry(1127, "Эффект по умолчанию", hub, haptic, generalIcon, hapticScreen, "Эффект по умолчанию", "general/haptic/default"));
        l.add(new Entry(1128, "Клик", hub, haptic, generalIcon, hapticScreen, "Клик", "general/haptic/click"));
        l.add(new Entry(1129, "Отправка", hub, haptic, generalIcon, hapticScreen, "Отправка", "general/haptic/send"));
        l.add(new Entry(1130, "Долгое нажатие", hub, haptic, generalIcon, hapticScreen, "Долгое нажатие", "general/haptic/long-press"));
        l.add(new Entry(1131, "Меню", hub, haptic, generalIcon, hapticScreen, "Меню", "general/haptic/menu"));
        l.add(new Entry(1132, "Назад", hub, haptic, generalIcon, hapticScreen, "Назад", "general/haptic/back"));
        l.add(new Entry(1014, "Последний онлайн", hub, general, generalIcon, generalScreen, "Последний онлайн", "general/last-online"));
        l.add(new Entry(1015, "Сохранение удаленных", hub, general, generalIcon, generalScreen, "Сохранение удаленных", "general/save-deleted"));
        l.add(new Entry(1016, "Режим призрака", hub, general, generalIcon, generalScreen, "Режим призрака", "general/ghost-mode"));
        l.add(new Entry(1017, "Ускорение загрузки", hub, general, generalIcon, generalScreen, "Ускорение загрузки", "general/download-speed"));
        l.add(new Entry(1018, "Ускорение отправки", hub, general, generalIcon, generalScreen, "Ускорение отправки", "general/upload-speed"));
        l.add(new Entry(1019, "Автопауза", hub, general, generalIcon, generalScreen, "Автопауза", "general/autopause"));
        l.add(new Entry(1030, "Элементы меню сообщения", hub, chats, chatsIcon, chatsScreen, "Элементы меню сообщения", "chats/message-menu"));
        l.add(new Entry(1031, "Время с секундами", hub, chats, chatsIcon, chatsScreen, "Время с секундами", "chats/time-seconds"));
        l.add(new Entry(1032, "Автоперевод чатов", hub, chats, chatsIcon, chatsScreen, "Автоперевод чатов", "chats/auto-translate"));
        l.add(new Entry(1033, "Переводчик", hub, chats, chatsIcon, chatsScreen, "Переводчик Google Яндекс", "chats/auto-translate-provider"));
        l.add(new Entry(1034, "Язык перевода", hub, chats, chatsIcon, chatsScreen, "Язык перевода чатов", "chats/auto-translate-lang"));
        l.add(new Entry(1032, "Количество недавних стикеров", hub, chats, chatsIcon, chatsScreen, "Количество недавних стикеров", "chats/recent-stickers"));
        l.add(new Entry(1033, "Убрать время на стикерах", hub, chats, chatsIcon, chatsScreen, "Убрать время на стикерах", "chats/hide-sticker-time"));
        l.add(new Entry(1034, "Исходящее сообщение", hub, chats, chatsIcon, chatsScreen, "Исходящее сообщение", "chats/double-tap-out"));
        l.add(new Entry(1035, "Входящее сообщение", hub, chats, chatsIcon, chatsScreen, "Входящее сообщение", "chats/double-tap-in"));
        l.add(new Entry(1036, "Нижняя кнопка", hub, chats, chatsIcon, chatsScreen, "Нижняя кнопка", "chats/bottom-button"));
        l.add(new Entry(1037, "Скрывать клавиатуру при прокрутке", hub, chats, chatsIcon, chatsScreen, "Скрывать клавиатуру при прокрутке", "chats/hide-keyboard"));
        l.add(new Entry(1038, "Запятая после упоминания", hub, chats, chatsIcon, chatsScreen, "Запятая после упоминания", "chats/comma-mention"));
        l.add(new Entry(1039, "Скрытые чаты", hub, chats, chatsIcon, chatsScreen, "Открыть скрытые чаты", "chats/hidden"));
        l.add(new Entry(1040, "Код-пароль скрытых чатов", hub, chats, chatsIcon, chatsScreen, "Код-пароль", "chats/hidden/passcode"));
        l.add(new Entry(1041, "Разблокировка скрытых чатов отпечатком", hub, chats, chatsIcon, chatsScreen, "Разблокировка отпечатком", "chats/hidden/fingerprint"));
        l.add(new Entry(1042, "Уведомления скрытых чатов", hub, chats, chatsIcon, chatsScreen, "Уведомления", "chats/hidden/notifications"));

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
        l.add(new Entry(1133, "Значок уведомлений", hub, appearance, appearanceIcon, appearanceScreen, "Значок уведомлений", "appearance/notification-icon"));
        l.add(new Entry(1124, "Папки", hub, appearance, appearanceIcon, appearanceScreen, "Папки", "appearance/folders"));
        l.add(new Entry(1125, "Обводка активной папки", hub, appearance, appearanceIcon, appearanceScreen, "Обводка активной папки", "appearance/folders/active-outline"));
        l.add(new Entry(1150, "Нижний островок", hub, appearance, appearanceIcon, appearanceScreen, "Нижний островок", "appearance/main-tabs"));
        l.add(new Entry(1151, "Вкладки", hub, appearance, appearanceIcon, appearanceScreen, "Вкладки", "appearance/main-tabs/tabs"));
        l.add(new Entry(1152, "Кнопка поиска", hub, appearance, appearanceIcon, appearanceScreen, "Кнопка поиска", "appearance/main-tabs/search"));
        l.add(new Entry(1069, sideMenu, hub, appearance, appearanceIcon, sideMenuScreen, null, "appearance/side-menu"));
        l.add(new Entry(1070, "Включить боковое меню", hub, sideMenu, appearanceIcon, sideMenuScreen, "Включить боковое меню", "appearance/side-menu/enable"));
        l.add(new Entry(1071, "Настроить элементы", hub, sideMenu, appearanceIcon, sideMenuScreen, "Настроить элементы", "appearance/side-menu/elements"));
        l.add(new Entry(1072, "Жидкое стекло", hub, sideMenu, appearanceIcon, sideMenuScreen, "Жидкое стекло", "appearance/side-menu/glass"));
        l.add(new Entry(1073, "Непрозрачность фона", hub, sideMenu, appearanceIcon, sideMenuScreen, "Непрозрачность фона", "appearance/side-menu/opacity"));
        final String animCommon = "Общие анимации";
        final String animChats = "В чатах";
        final String animDialogs = "В списке чатов";
        l.add(new Entry(1072, animCommon, hub, appearance, appearanceIcon, animCommonScreen, null, "appearance/animation/common"));
        l.add(new Entry(1115, "Анимация назад", hub, animCommon, appearanceIcon, animCommonScreen, "Анимация назад", "appearance/animation/common/back"));
        l.add(new Entry(1076, "В стиле MD3", hub, animCommon, appearanceIcon, animCommonScreen, "В стиле MD3", "appearance/animation/common/md3"));
        l.add(new Entry(1077, "Страницы", hub, animCommon, appearanceIcon, animCommonScreen, "Страницы", "appearance/animation/common/pages"));
        l.add(new Entry(1078, "Выталкивание", hub, animCommon, appearanceIcon, animCommonScreen, "Выталкивание", "appearance/animation/common/push"));
        l.add(new Entry(1134, "Поверхность", hub, animCommon, appearanceIcon, animCommonScreen, "Поверхность", "appearance/animation/common/surface"));
        l.add(new Entry(1135, "Отрыв", hub, animCommon, appearanceIcon, animCommonScreen, "Отрыв", "appearance/animation/common/shared"));
        l.add(new Entry(1136, "Проявление", hub, animCommon, appearanceIcon, animCommonScreen, "Проявление", "appearance/animation/common/fade-through"));
        l.add(new Entry(1137, "Ось X", hub, animCommon, appearanceIcon, animCommonScreen, "Ось X", "appearance/animation/common/axis-x"));
        l.add(new Entry(1138, "Ось Y", hub, animCommon, appearanceIcon, animCommonScreen, "Ось Y", "appearance/animation/common/axis-y"));
        l.add(new Entry(1139, "Ось Z", hub, animCommon, appearanceIcon, animCommonScreen, "Ось Z", "appearance/animation/common/axis-z"));
        l.add(new Entry(1141, "Глубина", hub, animCommon, appearanceIcon, animCommonScreen, "Глубина", "appearance/animation/common/depth"));
        l.add(new Entry(1142, "Зум", hub, animCommon, appearanceIcon, animCommonScreen, "Зум", "appearance/animation/common/zoom"));
        l.add(new Entry(1143, "3D страницы", hub, animCommon, appearanceIcon, animCommonScreen, "3D страницы", "appearance/animation/common/cube"));
        l.add(new Entry(1145, "Укрытие", hub, animCommon, appearanceIcon, animCommonScreen, "Укрытие", "appearance/animation/common/cover"));
        l.add(new Entry(1147, "Карточки", hub, animCommon, appearanceIcon, animCommonScreen, "Карточки", "appearance/animation/common/cards"));
        l.add(new Entry(1149, "Исчезновение", hub, animCommon, appearanceIcon, animCommonScreen, "Исчезновение", "appearance/animation/common/fade"));
        l.add(new Entry(1118, "Открытие экранов как назад", hub, animCommon, appearanceIcon, animCommonScreen, "Открытие экранов как назад", "appearance/animation/common/reverse-open"));
        l.add(new Entry(1122, "Вырастание фото", hub, animCommon, appearanceIcon, animCommonScreen, "Вырастание фото", "appearance/animation/common/media-grow"));
        l.add(new Entry(1123, "Пружина при тапе", hub, animCommon, appearanceIcon, animCommonScreen, "Пружина при тапе", "appearance/animation/common/press-spring"));
        l.add(new Entry(1116, animChats, hub, appearance, appearanceIcon, animChatsScreen, null, "appearance/animation/chats"));
        l.add(new Entry(1073, "Анимация текста", hub, animChats, appearanceIcon, MglaTextAnimationActivity::new, null, "appearance/text-animation"));
        l.add(new Entry(1074, "Эффект анимации текста", hub, "Анимация текста", appearanceIcon, MglaTextAnimationActivity::new, "Эффект", "appearance/text-animation/effect"));
        l.add(new Entry(1075, "Каскад при вставке", hub, "Анимация текста", appearanceIcon, MglaTextAnimationActivity::new, "Каскад", "appearance/text-animation/cascade"));
        l.add(new Entry(1119, "Морф кнопки отправки", hub, animChats, appearanceIcon, animChatsScreen, "Морф кнопки отправки", "appearance/animation/chats/send-morph"));
        l.add(new Entry(1120, "Пружина реакций", hub, animChats, appearanceIcon, animChatsScreen, "Пружина реакций", "appearance/animation/chats/reaction-spring"));
        l.add(new Entry(1117, animDialogs, hub, appearance, appearanceIcon, animDialogsScreen, null, "appearance/animation/dialogs"));
        l.add(new Entry(1079, "Перелёт аватара", hub, animDialogs, appearanceIcon, animDialogsScreen, "Перелёт аватара", "appearance/dialogs-avatar-hero"));
        l.add(new Entry(1113, "Раскрытие поиска", hub, animDialogs, appearanceIcon, animDialogsScreen, "Раскрытие поиска", "appearance/dialogs-search-expand"));
        l.add(new Entry(1114, "Кнопка «Создать»", hub, animDialogs, appearanceIcon, animDialogsScreen, "Кнопка «Создать»", "appearance/dialogs-fab-morph"));

        l.add(new Entry(1080, "API", hub, camera, cameraIcon, cameraScreen, "API", "camera/api"));

        l.add(new Entry(1090, "Авто-обход", hub, connection, connectionIcon, connectionScreen, "Авто-обход", "connection/websocket"));
        l.add(new Entry(1091, "Ultra-обход", hub, connection, connectionIcon, connectionScreen, "Ultra-обход", "connection/ultra"));
        l.add(new Entry(1092, "Пинг", hub, connection, connectionIcon, connectionScreen, "Пинг", "connection/ping"));
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
        l.add(new Entry(1109, "Gemini (облако)", hub, transcribe, aiIcon, transcribeScreen, "Gemini", "ai/transcribe/gemini"));
        l.add(new Entry(1110, "Локальный Whisper", hub, transcribe, aiIcon, transcribeScreen, "Локальный Whisper", "ai/transcribe/whisper"));
        l.add(new Entry(1111, "Модели Whisper", hub, transcribe, aiIcon, MglaWhisperModelsActivity::new, null, "ai/transcribe/whisper/models"));
        l.add(new Entry(1112, "Язык Whisper", hub, transcribe, aiIcon, transcribeScreen, "Язык", "ai/transcribe/whisper/language"));
        l.add(new Entry(1107, "Главные темы Chat DNA", hub, ai, aiIcon, aiScreen, "Главные темы Chat DNA", "ai/chat-dna"));
        l.add(new Entry(1108, "Локальные модели", hub, ai, aiIcon, aiScreen, "Локальные модели", "ai/models"));
        l.add(new Entry(1135, "Memory", hub, ai, aiIcon, MglaMemoryActivity::new, null, "ai/memory"));
        l.add(new Entry(1140, "Живая память", hub, ai, aiIcon, MglaMemoryActivity::new, null, "ai/living-memory"));
        l.add(new Entry(1136, "Включить память", hub, "Живая память", aiIcon, aiScreen, "Включить живую память", "ai/memory/enable"));
        l.add(new Entry(1137, "Провайдер памяти", hub, "Живая память", aiIcon, aiScreen, "Провайдер памяти", "ai/memory/provider"));
        l.add(new Entry(1138, "Интервал обновления памяти", hub, "Живая память", aiIcon, aiScreen, "Интервал обновления", "ai/memory/interval"));
        l.add(new Entry(1139, "Лимит запросов памяти", hub, "Живая память", aiIcon, aiScreen, "Лимит запросов памяти", "ai/memory/limit"));

        return l;
    }
}
