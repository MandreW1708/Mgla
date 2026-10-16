package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.MglaYandexMusicApi;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Redesigned Yandex Music Collection screen matching the official dark aesthetics.
 * Features:
 * - "Коллекция" header with Profile, Equalizer/Settings, and Search action icons
 * - "Моя волна по коллекции" vibrant glowing wave banner with "У вашей музыки есть цвет" tagline
 * - "Мне нравится ›" card with 3D heart badge, dynamic count, top 3 preview tracks with [E] badge,
 *   download button, and options menu. Clicking header opens the full favorites list.
 * - "Любимые исполнители ›" horizontal carousel with circular artist photos fetched from the account.
 * - "₽ Отправить донат для вдохновения" pill button.
 * - Floating mini-player above the bottom navigation island.
 */
public class MglaMusicActivity extends BaseFragment implements
        MainTabsActivity.TabFragmentDelegate,
        MglaYandexMusicPlayer.PlayerListener,
        MglaYandexMusicStore.AuthListener {

    private boolean hasMainTabs;

    // Root views
    private FrameLayout rootLayout;
    private FrameLayout mainContainer;

    // Pages
    private ScrollView collectionScrollView;
    private LinearLayout fullFavoritesView;
    private LinearLayout searchView;
    private View authPromoView;

    // Collection Page Header
    private TextView collectionTitleView;
    private ImageView profileActionBtn;
    private ImageView equalizerActionBtn;
    private ImageView searchActionBtn;

    // Collection Page Content
    private FrameLayout collectionWaveCard;
    private TextView collectionWaveStatus;
    private TextView collectionWaveTitle;
    private TextView collectionWaveSubtitle;

    // Favorites Section
    private View favoritesHeaderRow;
    private HeartBadgeView heartBadgeView;
    private TextView favoritesSectionTitle;
    private TextView favoritesSectionCount;
    private LinearLayout favoritesPreviewContainer;
    private ProgressBar favoritesPreviewLoading;

    // Playlists Section
    private View playlistsHeaderRow;
    private TextView playlistsSectionTitle;
    private HorizontalScrollView playlistsScrollView;
    private LinearLayout playlistsContainer;
    private ProgressBar playlistsLoading;

    // Artists Section
    private View artistsHeaderRow;
    private TextView artistsSectionTitle;
    private HorizontalScrollView artistsScrollView;
    private LinearLayout artistsContainer;
    private ProgressBar artistsLoading;

    // Donate button
    private FrameLayout donateButton;

    // Full track-list page (favorites or playlist)
    private TextView fullFavoritesTitleText;
    private TextView fullFavoritesCountText;
    private RecyclerListView fullFavoritesListView;
    private FullTrackAdapter fullFavoritesAdapter;
    private ProgressBar fullFavoritesLoading;
    private final List<MglaYandexMusicModel.Track> detailTracks = new ArrayList<>();
    private String detailSourceTitle = "Мне нравится";

    // Search Page
    private EditText searchInput;
    private ImageView searchClearBtn;
    private RecyclerListView searchListView;
    private FullTrackAdapter searchAdapter;
    private final List<MglaYandexMusicModel.Track> searchResults = new ArrayList<>();
    private ProgressBar searchLoading;
    private TextView searchEmptyView;

    // Data lists
    private final List<MglaYandexMusicModel.Track> favoritesList = new ArrayList<>();
    private final List<MglaYandexMusicModel.Playlist> playlistsList = new ArrayList<>();
    private final List<MglaYandexMusicModel.Artist> artistsList = new ArrayList<>();

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    public MglaMusicActivity() {
        this(null);
    }

    public MglaMusicActivity(Bundle args) {
        super(args);
        if (args != null) {
            hasMainTabs = args.getBoolean("hasMainTabs", false);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        MglaYandexMusicPlayer.getInstance().addListener(this);
        MglaYandexMusicStore.getInstance().addAuthListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        MglaYandexMusicPlayer.getInstance().removeListener(this);
        MglaYandexMusicStore.getInstance().removeAuthListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(0xFF000000); // Pure dark background

        // Reserve space for main-tabs island + global glass music bar above it
        int bottomPad = AndroidUtilities.navigationBarHeight
                + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + MglaGlobalMusicBar.BAR_HEIGHT + 12) : dp(16));

        mainContainer = new FrameLayout(context);
        mainContainer.setPadding(0, AndroidUtilities.statusBarHeight, 0, bottomPad);

        // 1. Main "Коллекция" Page
        collectionScrollView = createCollectionPage(context);
        mainContainer.addView(collectionScrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 2. Full Favorites Page
        fullFavoritesView = createFullFavoritesPage(context);
        fullFavoritesView.setVisibility(View.GONE);
        mainContainer.addView(fullFavoritesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 3. Search Page
        searchView = createSearchPage(context);
        searchView.setVisibility(View.GONE);
        mainContainer.addView(searchView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 4. Auth Promo Page (shown when unauthorized)
        authPromoView = createAuthPromoPage(context);
        authPromoView.setVisibility(View.GONE);
        mainContainer.addView(authPromoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        rootLayout.addView(mainContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateAuthState();

        fragmentView = rootLayout;
        return fragmentView;
    }

    // ==========================================
    // MAIN COLLECTION PAGE
    // ==========================================

    private ScrollView createCollectionPage(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(64));

        // Top Action Bar ("Коллекция" + Profile, Sliders, Search)
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (!hasMainTabs) {
            ImageView backBtn = new ImageView(context);
            backBtn.setImageResource(R.drawable.ic_ab_back);
            backBtn.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            backBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
            backBtn.setOnClickListener(v -> finishFragment());
            topBar.addView(backBtn, LayoutHelper.createLinear(36, 36, 0, 0, 8, 0));
        }

        collectionTitleView = new TextView(context);
        collectionTitleView.setText("Коллекция");
        collectionTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        collectionTitleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        collectionTitleView.setTextColor(Color.WHITE);
        topBar.addView(collectionTitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Profile icon
        profileActionBtn = new ImageView(context);
        profileActionBtn.setImageResource(R.drawable.outline_profile_photo);
        profileActionBtn.setColorFilter(new PorterDuffColorFilter(0xFFEDEDED, PorterDuff.Mode.SRC_IN));
        profileActionBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        profileActionBtn.setOnClickListener(v -> onProfileClicked());
        topBar.addView(profileActionBtn, LayoutHelper.createLinear(32, 32, 0, 0, 16, 0));

        // Equalizer / Sliders icon
        equalizerActionBtn = new ImageView(context);
        equalizerActionBtn.setImageDrawable(new SlidersDrawable(0xFFEDEDED));
        equalizerActionBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        equalizerActionBtn.setOnClickListener(v -> onEqualizerClicked());
        topBar.addView(equalizerActionBtn, LayoutHelper.createLinear(32, 32, 0, 0, 16, 0));

        // Search icon
        searchActionBtn = new ImageView(context);
        searchActionBtn.setImageResource(R.drawable.outline_header_search);
        searchActionBtn.setColorFilter(new PorterDuffColorFilter(0xFFEDEDED, PorterDuff.Mode.SRC_IN));
        searchActionBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchActionBtn.setOnClickListener(v -> openSearch());
        topBar.addView(searchActionBtn, LayoutHelper.createLinear(32, 32));

        content.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // "Моя волна по коллекции" Wave Card
        FrameLayout waveCardWrapper = new FrameLayout(context);
        waveCardWrapper.setPadding(dp(16), dp(4), dp(16), dp(6));

        collectionWaveCard = new FrameLayout(context);
        GradientDrawable waveGradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF4500, 0xFFFF6A00, 0xFFFF8A00, 0xFFFF3D00}
        );
        waveGradient.setCornerRadius(dp(24));
        collectionWaveCard.setBackground(waveGradient);
        collectionWaveCard.setPadding(dp(20), dp(18), dp(20), dp(18));

        LinearLayout waveInner = new LinearLayout(context);
        waveInner.setOrientation(LinearLayout.VERTICAL);
        waveInner.setGravity(Gravity.CENTER);

        collectionWaveStatus = new TextView(context);
        collectionWaveStatus.setText("Моя волна по коллекции");
        collectionWaveStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        collectionWaveStatus.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.NORMAL);
        collectionWaveStatus.setTextColor(0xD9FFFFFF);
        waveInner.addView(collectionWaveStatus, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        collectionWaveTitle = new TextView(context);
        collectionWaveTitle.setText("▶ Коллекция");
        collectionWaveTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        collectionWaveTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        collectionWaveTitle.setTextColor(Color.WHITE);
        waveInner.addView(collectionWaveTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        collectionWaveCard.addView(waveInner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        collectionWaveCard.setOnClickListener(v -> {
            MglaYandexMusicPlayer.getInstance().playCollectionWave();
        });

        waveCardWrapper.addView(collectionWaveCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 84));
        content.addView(waveCardWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Subtitle: "У вашей музыки есть цвет"
        collectionWaveSubtitle = new TextView(context);
        SpannableStringBuilder span = new SpannableStringBuilder("У вашей музыки есть цвет");
        span.setSpan(new ForegroundColorSpan(0xFF8E8E93), 0, 20, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(0xFFFF7043), 20, 24, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        collectionWaveSubtitle.setText(span);
        collectionWaveSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        collectionWaveSubtitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.NORMAL);
        collectionWaveSubtitle.setGravity(Gravity.CENTER);
        content.addView(collectionWaveSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 20));

        // ==========================================
        // SECTION "Мне нравится ›"
        // ==========================================
        favoritesHeaderRow = createFavoritesHeader(context);
        content.addView(favoritesHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 6));

        favoritesPreviewLoading = new ProgressBar(context);
        favoritesPreviewLoading.setVisibility(View.GONE);
        content.addView(favoritesPreviewLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));

        favoritesPreviewContainer = new LinearLayout(context);
        favoritesPreviewContainer.setOrientation(LinearLayout.VERTICAL);
        favoritesPreviewContainer.setPadding(dp(16), 0, dp(16), 0);
        content.addView(favoritesPreviewContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ==========================================
        // SECTION "Мои плейлисты ›"
        // ==========================================
        playlistsHeaderRow = createPlaylistsHeader(context);
        content.addView(playlistsHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 24, 16, 12));

        playlistsLoading = new ProgressBar(context);
        playlistsLoading.setVisibility(View.GONE);
        content.addView(playlistsLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));

        playlistsScrollView = new HorizontalScrollView(context);
        playlistsScrollView.setHorizontalScrollBarEnabled(false);
        playlistsScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        playlistsScrollView.setClipToPadding(false);
        playlistsScrollView.setPadding(dp(16), 0, dp(16), 0);

        playlistsContainer = new LinearLayout(context);
        playlistsContainer.setOrientation(LinearLayout.HORIZONTAL);
        playlistsScrollView.addView(playlistsContainer, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        content.addView(playlistsScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ==========================================
        // SECTION "Любимые исполнители ›"
        // ==========================================
        artistsHeaderRow = createArtistsHeader(context);
        content.addView(artistsHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 24, 16, 12));

        artistsLoading = new ProgressBar(context);
        artistsLoading.setVisibility(View.GONE);
        content.addView(artistsLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));

        artistsScrollView = new HorizontalScrollView(context);
        artistsScrollView.setHorizontalScrollBarEnabled(false);
        artistsScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        artistsScrollView.setClipToPadding(false);
        artistsScrollView.setPadding(dp(16), 0, dp(16), 0);

        artistsContainer = new LinearLayout(context);
        artistsContainer.setOrientation(LinearLayout.HORIZONTAL);
        artistsScrollView.addView(artistsContainer, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        content.addView(artistsScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ==========================================
        // DONATE PILL BUTTON
        // ==========================================
        donateButton = createDonateButton(context);
        content.addView(donateButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 32, 24, 20));

        scroll.addView(content);
        return scroll;
    }

    private View createFavoritesHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        heartBadgeView = new HeartBadgeView(context);
        row.addView(heartBadgeView, LayoutHelper.createLinear(54, 54, 0, 0, 14, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        favoritesSectionTitle = new TextView(context);
        favoritesSectionTitle.setText("Мне нравится ›");
        favoritesSectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        favoritesSectionTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        favoritesSectionTitle.setTextColor(Color.WHITE);
        textCol.addView(favoritesSectionTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        favoritesSectionCount = new TextView(context);
        favoritesSectionCount.setText("Загрузка...");
        favoritesSectionCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        favoritesSectionCount.setTextColor(0xFF8E8E93);
        textCol.addView(favoritesSectionCount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> showFullFavorites());
        return row;
    }

    private View createPlaylistsHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        playlistsSectionTitle = new TextView(context);
        playlistsSectionTitle.setText("Мои плейлисты ›");
        playlistsSectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        playlistsSectionTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        playlistsSectionTitle.setTextColor(Color.WHITE);
        row.addView(playlistsSectionTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> loadPlaylists());
        return row;
    }

    private View createArtistsHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        artistsSectionTitle = new TextView(context);
        artistsSectionTitle.setText("Любимые исполнители ›");
        artistsSectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        artistsSectionTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        artistsSectionTitle.setTextColor(Color.WHITE);
        row.addView(artistsSectionTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            loadArtists();
        });
        return row;
    }

    private FrameLayout createDonateButton(Context context) {
        FrameLayout container = new FrameLayout(context);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(24));
        pillBg.setColor(0x1AFFFFFF);
        pillBg.setStroke(dp(1), 0x33FFFFFF);
        container.setBackground(pillBg);
        container.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER);

        TextView rubleIcon = new TextView(context);
        rubleIcon.setText("₽");
        rubleIcon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        rubleIcon.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        rubleIcon.setTextColor(0xFF8E8E93);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setStroke(dp(1), 0xFF8E8E93);
        rubleIcon.setBackground(circleBg);
        rubleIcon.setGravity(Gravity.CENTER);
        inner.addView(rubleIcon, LayoutHelper.createLinear(20, 20, 0, 0, 8, 0));

        TextView text = new TextView(context);
        text.setText("Отправить донат для вдохновения");
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.NORMAL);
        text.setTextColor(0xFFD6D6D6);
        inner.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        container.addView(inner, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        container.setOnClickListener(v -> {
            Toast.makeText(context, "Спасибо за поддержку проекта Mgla! 🎵", Toast.LENGTH_SHORT).show();
        });

        return container;
    }

    // ==========================================
    // PREVIEW UPDATERS
    // ==========================================

    private void updateFavoritesPreview() {
        if (favoritesSectionCount != null) {
            favoritesSectionCount.setText(formatTrackCount(favoritesList.size()));
        }

        if (favoritesPreviewContainer == null) return;
        favoritesPreviewContainer.removeAllViews();

        int previewCount = Math.min(3, favoritesList.size());
        if (previewCount == 0) {
            TextView empty = new TextView(getContext());
            empty.setText("В вашей медиатеке пока нет любимых треков");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(0xFF8E8E93);
            empty.setPadding(0, dp(10), 0, dp(10));
            favoritesPreviewContainer.addView(empty);
            return;
        }

        for (int i = 0; i < previewCount; i++) {
            final MglaYandexMusicModel.Track t = favoritesList.get(i);
            View row = createPreviewTrackRow(getContext(), t);
            favoritesPreviewContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private void updatePlaylistsCarousel() {
        if (playlistsContainer == null) return;
        playlistsContainer.removeAllViews();
        Context context = getContext();
        if (context == null) return;

        if (playlistsList.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Нет плейлистов");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(0xFF8E8E93);
            empty.setPadding(0, dp(10), 0, dp(10));
            playlistsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < playlistsList.size(); i++) {
            final MglaYandexMusicModel.Playlist playlist = playlistsList.get(i);
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, 0, dp(12), 0);

            ImageView cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setCornerRadius(dp(12));
            coverBg.setColor(0xFF2A2A2A);
            cover.setBackground(coverBg);
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            MglaMusicImageHelper.bindCover(cover, playlist.getCoverUrl(200), 12, R.drawable.bot_music);
            card.addView(cover, LayoutHelper.createLinear(120, 120));

            TextView title = new TextView(context);
            title.setText(playlist.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(8), 0, 0);
            card.addView(title, LayoutHelper.createLinear(120, LayoutHelper.WRAP_CONTENT));

            TextView count = new TextView(context);
            count.setText(formatTrackCount(playlist.trackCount));
            count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            count.setTextColor(0xFF8E8E93);
            count.setSingleLine(true);
            card.addView(count, LayoutHelper.createLinear(120, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            card.setOnClickListener(v -> openPlaylist(playlist));
            playlistsContainer.addView(card);
        }
    }

    private void openPlaylist(MglaYandexMusicModel.Playlist playlist) {
        if (playlist == null) return;
        detailSourceTitle = playlist.title;
        if (fullFavoritesTitleText != null) {
            fullFavoritesTitleText.setText(playlist.title);
        }
        if (fullFavoritesCountText != null) {
            fullFavoritesCountText.setText(formatTrackCount(playlist.trackCount));
        }
        detailTracks.clear();
        if (fullFavoritesAdapter != null) {
            fullFavoritesAdapter.notifyDataSetChanged();
        }
        showTrackDetailPage();
        if (fullFavoritesLoading != null) {
            fullFavoritesLoading.setVisibility(View.VISIBLE);
        }

        MglaYandexMusicApi.fetchPlaylistTracks(playlist.uid, playlist.kind, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (fullFavoritesLoading != null) fullFavoritesLoading.setVisibility(View.GONE);
                detailTracks.clear();
                if (tracks != null) {
                    detailTracks.addAll(tracks);
                }
                if (fullFavoritesCountText != null) {
                    fullFavoritesCountText.setText(formatTrackCount(detailTracks.size()));
                }
                if (fullFavoritesAdapter != null) {
                    fullFavoritesAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onError(Exception error) {
                if (fullFavoritesLoading != null) fullFavoritesLoading.setVisibility(View.GONE);
                Toast.makeText(getContext(), "Не удалось загрузить плейлист", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View createPreviewTrackRow(Context context, MglaYandexMusicModel.Track track) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        // Cover (48x48dp, 8dp radius)
        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        MglaMusicImageHelper.bindCover(cover, track.getCoverUrl(100), 8, R.drawable.bot_music);
        row.addView(cover, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));

        // Title + Explicit + Artist
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(track.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        if (track.isExplicit) {
            TextView explicitBadge = new TextView(context);
            explicitBadge.setText("E");
            explicitBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
            explicitBadge.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            explicitBadge.setTextColor(0xFFEDEDED);
            explicitBadge.setGravity(Gravity.CENTER);
            GradientDrawable eBg = new GradientDrawable();
            eBg.setCornerRadius(dp(3));
            eBg.setColor(0x33FFFFFF);
            explicitBadge.setBackground(eBg);
            titleRow.addView(explicitBadge, LayoutHelper.createLinear(14, 14, 6, 0, 0, 0));
        }

        textCol.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView artist = new TextView(context);
        artist.setText(track.artist);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        artist.setTextColor(0xFF8E8E93);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artist, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 10, 0));

        // Download Action Icon (green arrow down into tray)
        ImageView downloadBtn = new ImageView(context);
        downloadBtn.setImageDrawable(new DownloadDrawable(0xFF4CAF50));
        downloadBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        downloadBtn.setOnClickListener(v -> {
            Toast.makeText(context, "Трек сохранен: " + track.title, Toast.LENGTH_SHORT).show();
        });
        row.addView(downloadBtn, LayoutHelper.createLinear(34, 34, 0, 0, 6, 0));

        // 3 Dots Menu
        ImageView moreBtn = new ImageView(context);
        moreBtn.setImageResource(R.drawable.ic_ab_other);
        moreBtn.setColorFilter(new PorterDuffColorFilter(0xFF8E8E93, PorterDuff.Mode.SRC_IN));
        moreBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        moreBtn.setOnClickListener(v -> showTrackOptionsMenu(track));
        row.addView(moreBtn, LayoutHelper.createLinear(34, 34));

        row.setOnClickListener(v -> {
            MglaYandexMusicPlayer.getInstance().playTrack(track, favoritesList, "Мне нравится");
        });

        return row;
    }

    private void updateArtistsCarousel() {
        if (artistsContainer == null) return;
        artistsContainer.removeAllViews();

        if (artistsList.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Нет любимых исполнителей");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(0xFF8E8E93);
            empty.setPadding(0, dp(10), 0, dp(10));
            artistsContainer.addView(empty);
            return;
        }

        for (MglaYandexMusicModel.Artist artist : artistsList) {
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, 0, dp(16), 0);

            // Circular photo (100x100dp)
            ImageView photo = new ImageView(getContext());
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            MglaMusicImageHelper.bindCircleCover(photo, artist.getCoverUrl(300), R.drawable.bot_music);
            item.addView(photo, LayoutHelper.createLinear(100, 100));

            // Artist name below
            TextView name = new TextView(getContext());
            name.setText(artist.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            name.setTextColor(Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setWidth(dp(100));
            item.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

            item.setOnClickListener(v -> {
                // Open search for this artist
                openSearch();
                if (searchInput != null) {
                    searchInput.setText(artist.name);
                }
            });

            artistsContainer.addView(item);
        }
    }

    // ==========================================
    // FULL FAVORITES PAGE
    // ==========================================

    private LinearLayout createFullFavoritesPage(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), 0);

        // Header with Back button
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = new ImageView(context);
        backBtn.setImageResource(R.drawable.ic_ab_back);
        backBtn.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        backBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        backBtn.setOnClickListener(v -> closeFullFavorites());
        header.addView(backBtn, LayoutHelper.createLinear(36, 36, 0, 0, 10, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        fullFavoritesTitleText = new TextView(context);
        fullFavoritesTitleText.setText("Мне нравится");
        fullFavoritesTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        fullFavoritesTitleText.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        fullFavoritesTitleText.setTextColor(Color.WHITE);
        textCol.addView(fullFavoritesTitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fullFavoritesCountText = new TextView(context);
        fullFavoritesCountText.setText("0 треков");
        fullFavoritesCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fullFavoritesCountText.setTextColor(0xFF8E8E93);
        textCol.addView(fullFavoritesCountText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        header.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Action buttons: "Слушать всё" and "Перемешать"
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView playAllBtn = new TextView(context);
        playAllBtn.setText("▶ Слушать всё");
        playAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        playAllBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        playAllBtn.setTextColor(Color.WHITE);
        GradientDrawable playAllBg = new GradientDrawable();
        playAllBg.setCornerRadius(dp(18));
        playAllBg.setColor(0xFFFF4500);
        playAllBtn.setBackground(playAllBg);
        playAllBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        playAllBtn.setOnClickListener(v -> {
            if (!detailTracks.isEmpty()) {
                MglaYandexMusicPlayer.getInstance().playTrack(detailTracks.get(0), new ArrayList<>(detailTracks), detailSourceTitle);
            }
        });
        actionRow.addView(playAllBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        TextView shuffleBtn = new TextView(context);
        shuffleBtn.setText("🔀 Перемешать");
        shuffleBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        shuffleBtn.setTextColor(Color.WHITE);
        GradientDrawable shuffBg = new GradientDrawable();
        shuffBg.setCornerRadius(dp(18));
        shuffBg.setColor(0x22FFFFFF);
        shuffleBtn.setBackground(shuffBg);
        shuffleBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        shuffleBtn.setOnClickListener(v -> {
            if (!detailTracks.isEmpty()) {
                MglaYandexMusicStore.getInstance().setShuffle(true);
                MglaYandexMusicPlayer.getInstance().playTrack(detailTracks.get(0), new ArrayList<>(detailTracks), detailSourceTitle);
            }
        });
        actionRow.addView(shuffleBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        layout.addView(actionRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        fullFavoritesLoading = new ProgressBar(context);
        fullFavoritesLoading.setVisibility(View.GONE);
        layout.addView(fullFavoritesLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 20));

        fullFavoritesListView = new RecyclerListView(context);
        fullFavoritesListView.setLayoutManager(new LinearLayoutManager(context));
        fullFavoritesAdapter = new FullTrackAdapter(detailTracks, null);
        fullFavoritesListView.setAdapter(fullFavoritesAdapter);
        fullFavoritesListView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= detailTracks.size()) {
                return;
            }
            MglaYandexMusicModel.Track t = detailTracks.get(position);
            MglaYandexMusicPlayer.getInstance().playTrack(t, new ArrayList<>(detailTracks), detailSourceTitle);
        });
        layout.addView(fullFavoritesListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private void showFullFavorites() {
        detailSourceTitle = "Мне нравится";
        detailTracks.clear();
        detailTracks.addAll(favoritesList);
        if (fullFavoritesTitleText != null) {
            fullFavoritesTitleText.setText("Мне нравится");
        }
        if (fullFavoritesCountText != null) {
            fullFavoritesCountText.setText(formatTrackCount(detailTracks.size()));
        }
        if (fullFavoritesAdapter != null) {
            fullFavoritesAdapter.notifyDataSetChanged();
        }
        showTrackDetailPage();
    }

    private void showTrackDetailPage() {
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (searchView != null) searchView.setVisibility(View.GONE);
        if (fullFavoritesView != null) {
            fullFavoritesView.setVisibility(View.VISIBLE);
        }
    }

    private void closeFullFavorites() {
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.VISIBLE);
    }

    // ==========================================
    // SEARCH PAGE
    // ==========================================

    private LinearLayout createSearchPage(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), 0);

        // Search Bar with Back Button
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = new ImageView(context);
        backBtn.setImageResource(R.drawable.ic_ab_back);
        backBtn.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        backBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        backBtn.setOnClickListener(v -> closeSearch());
        bar.addView(backBtn, LayoutHelper.createLinear(36, 36, 0, 0, 8, 0));

        LinearLayout searchPill = new LinearLayout(context);
        searchPill.setOrientation(LinearLayout.HORIZONTAL);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(20));
        pillBg.setColor(0x26FFFFFF);
        searchPill.setBackground(pillBg);
        searchPill.setPadding(dp(14), 0, dp(10), 0);

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(0xFF8E8E93, PorterDuff.Mode.SRC_IN));
        searchPill.addView(searchIcon, LayoutHelper.createLinear(18, 18, 0, 0, 8, 0));

        searchInput = new EditText(context);
        searchInput.setHint("Трек или исполнитель");
        searchInput.setHintTextColor(0xFF8E8E93);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().trim();
                if (searchClearBtn != null) {
                    searchClearBtn.setVisibility(TextUtils.isEmpty(q) ? View.GONE : View.VISIBLE);
                }
                triggerSearch(q);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        searchPill.addView(searchInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        searchClearBtn = new ImageView(context);
        searchClearBtn.setImageResource(R.drawable.msg_close);
        searchClearBtn.setColorFilter(new PorterDuffColorFilter(0xFF8E8E93, PorterDuff.Mode.SRC_IN));
        searchClearBtn.setVisibility(View.GONE);
        searchClearBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchClearBtn.setOnClickListener(v -> searchInput.setText(""));
        searchPill.addView(searchClearBtn, LayoutHelper.createLinear(24, 24));

        bar.addView(searchPill, LayoutHelper.createLinear(0, 42, 1f));
        layout.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        searchLoading = new ProgressBar(context);
        searchLoading.setVisibility(View.GONE);
        layout.addView(searchLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 20));

        searchEmptyView = new TextView(context);
        searchEmptyView.setText("Введите название трека или артиста");
        searchEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        searchEmptyView.setTextColor(0xFF8E8E93);
        searchEmptyView.setGravity(Gravity.CENTER);
        searchEmptyView.setPadding(dp(32), dp(40), dp(32), dp(40));
        layout.addView(searchEmptyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        searchListView = new RecyclerListView(context);
        searchListView.setLayoutManager(new LinearLayoutManager(context));
        searchAdapter = new FullTrackAdapter(searchResults, null);
        searchListView.setAdapter(searchAdapter);
        searchListView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= searchResults.size()) {
                return;
            }
            MglaYandexMusicModel.Track track = searchResults.get(position);
            MglaYandexMusicPlayer.getInstance().playTrack(track, new ArrayList<>(searchResults), "Поиск");
        });
        layout.addView(searchListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private void openSearch() {
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (searchView != null) {
            searchView.setVisibility(View.VISIBLE);
            if (searchInput != null) {
                searchInput.requestFocus();
                AndroidUtilities.showKeyboard(searchInput);
            }
        }
    }

    private void closeSearch() {
        if (searchInput != null) {
            AndroidUtilities.hideKeyboard(searchInput);
        }
        if (searchView != null) searchView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.VISIBLE);
    }

    private void triggerSearch(String query) {
        searchHandler.removeCallbacks(searchRunnable);
        if (TextUtils.isEmpty(query)) {
            searchResults.clear();
            searchAdapter.notifyDataSetChanged();
            searchLoading.setVisibility(View.GONE);
            searchEmptyView.setVisibility(View.VISIBLE);
            searchEmptyView.setText("Введите название трека или артиста");
            return;
        }

        searchRunnable = () -> {
            searchLoading.setVisibility(View.VISIBLE);
            searchEmptyView.setVisibility(View.GONE);

            MglaYandexMusicApi.searchTracks(query, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
                @Override
                public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                    searchLoading.setVisibility(View.GONE);
                    searchResults.clear();
                    if (tracks != null) {
                        searchResults.addAll(tracks);
                    }
                    searchAdapter.notifyDataSetChanged();
                    searchEmptyView.setVisibility(searchResults.isEmpty() ? View.VISIBLE : View.GONE);
                    if (searchResults.isEmpty()) {
                        searchEmptyView.setText("Ничего не найдено по запросу \"" + query + "\"");
                    }
                }

                @Override
                public void onError(Exception error) {
                    searchLoading.setVisibility(View.GONE);
                }
            });
        };
        searchHandler.postDelayed(searchRunnable, 350);
    }

    // ==========================================
    // AUTH PROMO PAGE
    // ==========================================

    private View createAuthPromoPage(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF000000);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(28), dp(20), dp(28), dp(32));

        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF334B, 0xFFFF6A00}
        );
        badgeBg.setCornerRadius(dp(36));
        badge.setBackground(badgeBg);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.bot_music);
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        badge.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        layout.addView(badge, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 22));

        TextView title = new TextView(context);
        title.setText("Яндекс Музыка в Mgla");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView subtitle = new TextView(context);
        subtitle.setText("Слушайте персональную Мою Волну, любимые треки и личную коллекцию прямо в Telegram");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(0xFF8E8E93);
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        TextView loginBtn = new TextView(context);
        loginBtn.setText("Войти через Яндекс ID");
        loginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        loginBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        loginBtn.setTextColor(Color.WHITE);
        loginBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF334B, 0xFFFF6A00}
        );
        btnBg.setCornerRadius(dp(25));
        loginBtn.setBackground(btnBg);
        loginBtn.setPadding(dp(24), dp(14), dp(24), dp(14));
        loginBtn.setOnClickListener(v -> openAuthSheet(false));
        layout.addView(loginBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 12));

        TextView manualTokenBtn = new TextView(context);
        manualTokenBtn.setText("Ввести токен вручную");
        manualTokenBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        manualTokenBtn.setTextColor(0xFFFF7043);
        manualTokenBtn.setGravity(Gravity.CENTER);
        manualTokenBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        manualTokenBtn.setOnClickListener(v -> openAuthSheet(true));
        layout.addView(manualTokenBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return scroll;
    }

    // ==========================================
    // DATA LOADING
    // ==========================================

    private void loadFavorites() {
        List<MglaYandexMusicModel.Track> cached = MglaYandexMusicStore.getInstance().getCachedFavorites();
        if (cached != null && !cached.isEmpty()) {
            favoritesList.clear();
            favoritesList.addAll(cached);
            updateFavoritesPreview();
            if (fullFavoritesAdapter != null) fullFavoritesAdapter.notifyDataSetChanged();
        } else {
            if (favoritesPreviewLoading != null) favoritesPreviewLoading.setVisibility(View.VISIBLE);
        }

        MglaYandexMusicApi.fetchFavorites(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (favoritesPreviewLoading != null) favoritesPreviewLoading.setVisibility(View.GONE);
                if (fullFavoritesLoading != null) fullFavoritesLoading.setVisibility(View.GONE);
                favoritesList.clear();
                if (tracks != null) {
                    favoritesList.addAll(tracks);
                }
                updateFavoritesPreview();
                if ("Мне нравится".equals(detailSourceTitle)) {
                    detailTracks.clear();
                    detailTracks.addAll(favoritesList);
                    if (fullFavoritesCountText != null) {
                        fullFavoritesCountText.setText(formatTrackCount(detailTracks.size()));
                    }
                }
                if (fullFavoritesAdapter != null) fullFavoritesAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Exception error) {
                if (favoritesPreviewLoading != null) favoritesPreviewLoading.setVisibility(View.GONE);
                if (fullFavoritesLoading != null) fullFavoritesLoading.setVisibility(View.GONE);
            }
        });
    }

    private void loadArtists() {
        List<MglaYandexMusicModel.Artist> cached = MglaYandexMusicStore.getInstance().getCachedArtists();
        if (cached != null && !cached.isEmpty()) {
            artistsList.clear();
            artistsList.addAll(cached);
            updateArtistsCarousel();
        } else {
            if (artistsLoading != null) artistsLoading.setVisibility(View.VISIBLE);
        }

        MglaYandexMusicApi.fetchLikedArtists(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Artist>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Artist> artists) {
                if (artistsLoading != null) artistsLoading.setVisibility(View.GONE);
                artistsList.clear();
                if (artists != null) {
                    artistsList.addAll(artists);
                }
                updateArtistsCarousel();
            }

            @Override
            public void onError(Exception error) {
                if (artistsLoading != null) artistsLoading.setVisibility(View.GONE);
            }
        });
    }

    private void updateAuthState() {
        boolean auth = MglaYandexMusicStore.getInstance().isAuthorized();
        if (authPromoView != null) {
            authPromoView.setVisibility(auth ? View.GONE : View.VISIBLE);
        }
        if (collectionScrollView != null) {
            collectionScrollView.setVisibility(auth ? View.VISIBLE : View.GONE);
        }

        if (auth) {
            loadFavorites();
            loadPlaylists();
            loadArtists();
        }
    }

    private void loadPlaylists() {
        List<MglaYandexMusicModel.Playlist> cached = MglaYandexMusicStore.getInstance().getCachedPlaylists();
        if (cached != null && !cached.isEmpty()) {
            playlistsList.clear();
            for (MglaYandexMusicModel.Playlist p : cached) {
                if (p != null && p.kind != 3) {
                    playlistsList.add(p);
                }
            }
            updatePlaylistsCarousel();
        } else if (playlistsLoading != null) {
            playlistsLoading.setVisibility(View.VISIBLE);
        }

        MglaYandexMusicApi.fetchPlaylists(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Playlist>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Playlist> playlists) {
                if (playlistsLoading != null) playlistsLoading.setVisibility(View.GONE);
                playlistsList.clear();
                if (playlists != null) {
                    for (MglaYandexMusicModel.Playlist p : playlists) {
                        if (p == null || p.kind == 3) continue; // favorites shown separately
                        playlistsList.add(p);
                    }
                }
                updatePlaylistsCarousel();
            }

            @Override
            public void onError(Exception error) {
                if (playlistsLoading != null) playlistsLoading.setVisibility(View.GONE);
            }
        });
    }

    private void onProfileClicked() {
        if (!MglaYandexMusicStore.getInstance().isAuthorized()) {
            openAuthSheet(false);
            return;
        }

        MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
        String name = user != null ? user.displayName : "Пользователь";
        String login = user != null ? user.login : "";
        boolean hasPlus = user != null && user.hasPlus;

        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(name);
        String msg = (TextUtils.isEmpty(login) ? "" : "Логин: " + login + "\n")
                + "Подписка: " + (hasPlus ? "Яндекс Плюс активен ✨" : "Базовая");
        b.setMessage(msg);
        b.setPositiveButton("OK", null);
        b.setNegativeButton("Выйти", (dialog, which) -> {
            MglaYandexMusicStore.getInstance().clearAuth();
            Toast.makeText(getContext(), "Вы вышли из Яндекс ID", Toast.LENGTH_SHORT).show();
        });
        showDialog(b.create());
    }

    private void onEqualizerClicked() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Настройки звука");
        b.setItems(new CharSequence[]{
                "Качество: Высокое (320 kbps)",
                "Повтор: " + (MglaYandexMusicStore.getInstance().getRepeatMode() == 2 ? "Один трек" : MglaYandexMusicStore.getInstance().getRepeatMode() == 1 ? "Все" : "Выключен"),
                "Случайный порядок: " + (MglaYandexMusicStore.getInstance().isShuffle() ? "Включен" : "Выключен"),
                "Очистить кэш треков"
        }, (dialog, which) -> {
            if (which == 1) {
                MglaYandexMusicPlayer.getInstance().toggleRepeat();
            } else if (which == 2) {
                MglaYandexMusicPlayer.getInstance().toggleShuffle();
            } else if (which == 3) {
                Toast.makeText(getContext(), "Кэш очищен", Toast.LENGTH_SHORT).show();
            }
        });
        b.setPositiveButton("Готово", null);
        showDialog(b.create());
    }

    private void showTrackOptionsMenu(MglaYandexMusicModel.Track track) {
        if (track == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(track.title);
        b.setItems(new CharSequence[]{
                "Слушать следующим",
                "Удалить из Мне нравится",
                "Поделиться названием"
        }, (dialog, which) -> {
            if (which == 0) {
                Toast.makeText(getContext(), "Добавлено в очередь", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                MglaYandexMusicStore.getInstance().setLiked(track.id, false);
                favoritesList.remove(track);
                updateFavoritesPreview();
                if (fullFavoritesAdapter != null) fullFavoritesAdapter.notifyDataSetChanged();
                MglaYandexMusicApi.toggleLike(track.id, false, null);
            } else if (which == 2) {
                AndroidUtilities.addToClipboard(track.artist + " вЂ” " + track.title);
                Toast.makeText(getContext(), "Скопировано", Toast.LENGTH_SHORT).show();
            }
        });
        showDialog(b.create());
    }

    private void openAuthSheet(boolean initialManual) {
        MglaYandexAuthSheet sheet = new MglaYandexAuthSheet(getParentActivity(), initialManual, this::updateAuthState);
        sheet.show();
    }

    public static String formatTrackCount(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 19) {
            return count + " треков";
        }
        if (mod10 == 1) {
            return count + " трек";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return count + " трека";
        }
        return count + " треков";
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searchView != null && searchView.getVisibility() == View.VISIBLE) {
            closeSearch();
            return false;
        }
        if (fullFavoritesView != null && fullFavoritesView.getVisibility() == View.VISIBLE) {
            closeFullFavorites();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ==========================================
    // LISTENER CALLBACKS
    // ==========================================

    @Override
    public void onTrackChanged(MglaYandexMusicModel.Track track) {
        AndroidUtilities.runOnUIThread(() -> {
            if (fullFavoritesAdapter != null) fullFavoritesAdapter.notifyDataSetChanged();
            if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
    }

    @Override
    public void onAuthStateChanged(boolean isAuthorized) {
        AndroidUtilities.runOnUIThread(this::updateAuthState);
    }

    @Override
    public void onParentScrollToTop() {
        if (collectionScrollView != null && collectionScrollView.getVisibility() == View.VISIBLE) {
            collectionScrollView.smoothScrollTo(0, 0);
        } else if (fullFavoritesListView != null && fullFavoritesView.getVisibility() == View.VISIBLE) {
            fullFavoritesListView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    // ==========================================
    // ADAPTER FOR FULL LISTS
    // ==========================================

    private interface OnTrackClickListener {
        void onTrackClick(MglaYandexMusicModel.Track track, int position);
    }

    private class FullTrackAdapter extends RecyclerView.Adapter<FullTrackAdapter.Holder> {
        private final List<MglaYandexMusicModel.Track> items;
        private final OnTrackClickListener listener;

        FullTrackAdapter(List<MglaYandexMusicModel.Track> items, OnTrackClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(0x1AFFFFFF);
            row.setBackground(bg);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(cover, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            LinearLayout titleRow = new LinearLayout(ctx);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView explicitTag = new TextView(ctx);
            explicitTag.setText("E");
            explicitTag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
            explicitTag.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            explicitTag.setTextColor(0xFFEDEDED);
            explicitTag.setGravity(Gravity.CENTER);
            GradientDrawable eBg = new GradientDrawable();
            eBg.setCornerRadius(dp(3));
            eBg.setColor(0x33FFFFFF);
            explicitTag.setBackground(eBg);
            titleRow.addView(explicitTag, LayoutHelper.createLinear(14, 14, 6, 0, 0, 0));

            col.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView subtitle = new TextView(ctx);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(0xFF8E8E93);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            TextView duration = new TextView(ctx);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            duration.setTextColor(0xFF8E8E93);
            row.addView(duration, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

            ImageView like = new ImageView(ctx);
            like.setPadding(dp(6), dp(6), dp(6), dp(6));
            row.addView(like, LayoutHelper.createLinear(36, 36));

            return new Holder(row, cover, title, explicitTag, subtitle, duration, like);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MglaYandexMusicModel.Track t = items.get(position);
            holder.title.setText(t.title);
            holder.subtitle.setText(t.artist);
            holder.duration.setText(t.getFormattedDuration());
            holder.explicitTag.setVisibility(t.isExplicit ? View.VISIBLE : View.GONE);

            MglaMusicImageHelper.bindCover(holder.cover, t.getCoverUrl(100), 8, R.drawable.bot_music);

            MglaYandexMusicModel.Track current = MglaYandexMusicPlayer.getInstance().getCurrentTrack();
            boolean isThisPlaying = (current != null && current.id.equals(t.id));

            if (isThisPlaying) {
                holder.title.setTextColor(0xFFFF5722);
            } else {
                holder.title.setTextColor(Color.WHITE);
            }

            if (t.isLiked) {
                holder.like.setImageResource(R.drawable.media_like_active);
                holder.like.setColorFilter(new PorterDuffColorFilter(0xFFFF334B, PorterDuff.Mode.SRC_IN));
            } else {
                holder.like.setImageResource(R.drawable.media_like);
                holder.like.setColorFilter(new PorterDuffColorFilter(0xFF8E8E93, PorterDuff.Mode.SRC_IN));
            }

            holder.like.setOnClickListener(v -> {
                boolean nextLiked = !t.isLiked;
                t.isLiked = nextLiked;
                MglaYandexMusicStore.getInstance().setLiked(t.id, nextLiked);
                notifyItemChanged(position);
                MglaYandexMusicApi.toggleLike(t.id, nextLiked, null);
            });

            if (listener != null) {
                holder.itemView.setOnClickListener(v -> listener.onTrackClick(t, position));
            } else {
                holder.itemView.setOnClickListener(null);
                holder.itemView.setClickable(true);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView explicitTag;
            final TextView subtitle;
            final TextView duration;
            final ImageView like;

            Holder(View itemView, ImageView cover, TextView title, TextView explicitTag, TextView subtitle, TextView duration, ImageView like) {
                super(itemView);
                this.cover = cover;
                this.title = title;
                this.explicitTag = explicitTag;
                this.subtitle = subtitle;
                this.duration = duration;
                this.like = like;
            }
        }
    }

    // ==========================================
    // CUSTOM DRAWABLES & VIEWS
    // ==========================================

    /**
     * Pixel-perfect 3D coral heart badge matching the screenshot.
     */
    private static class HeartBadgeView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path heartPath = new android.graphics.Path();

        public HeartBadgeView(Context context) {
            super(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fillPaint.setShader(new android.graphics.LinearGradient(
                    0, 0, w, h,
                    new int[]{0xFFFF5252, 0xFFFF2D55, 0xFFE00034},
                    new float[]{0f, 0.55f, 1f},
                    android.graphics.Shader.TileMode.CLAMP
            ));
            glowPaint.setShader(new android.graphics.RadialGradient(
                    w * 0.35f, h * 0.3f, w * 0.45f,
                    new int[]{0x80FFFFFF, 0x00FFFFFF},
                    null,
                    android.graphics.Shader.TileMode.CLAMP
            ));

            heartPath.reset();
            float cx = w * 0.5f;
            float cy = h * 0.55f;
            float scale = Math.min(w, h) * 0.38f;

            heartPath.moveTo(cx, cy + scale * 0.85f);
            heartPath.cubicTo(
                    cx - scale * 1.35f, cy + scale * 0.15f,
                    cx - scale * 1.15f, cy - scale * 0.95f,
                    cx - scale * 0.55f, cy - scale * 0.95f
            );
            heartPath.cubicTo(
                    cx - scale * 0.2f, cy - scale * 0.95f,
                    cx, cy - scale * 0.5f,
                    cx, cy - scale * 0.4f
            );
            heartPath.cubicTo(
                    cx, cy - scale * 0.5f,
                    cx + scale * 0.2f, cy - scale * 0.95f,
                    cx + scale * 0.55f, cy - scale * 0.95f
            );
            heartPath.cubicTo(
                    cx + scale * 1.15f, cy - scale * 0.95f,
                    cx + scale * 1.35f, cy + scale * 0.15f,
                    cx, cy + scale * 0.85f
            );
            heartPath.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.rotate(-8f, getWidth() / 2f, getHeight() / 2f);
            canvas.drawPath(heartPath, fillPaint);
            canvas.drawPath(heartPath, glowPaint);
            canvas.restore();
        }
    }

    /**
     * Two-slider equalizer icon matching the top-right toolbar icon.
     */
    private static class SlidersDrawable extends Drawable {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SlidersDrawable(int color) {
            linePaint.setColor(color);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);

            circlePaint.setColor(color);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(dp(2));

            innerPaint.setColor(0xFF000000);
            innerPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float y1 = b.top + h * 0.33f;
            float y2 = b.top + h * 0.67f;
            float r = dp(3.5f);

            // Upper line with knob at 65%
            float knob1X = b.left + w * 0.65f;
            canvas.drawLine(b.left, y1, knob1X - r, y1, linePaint);
            canvas.drawLine(knob1X + r, y1, b.right, y1, linePaint);
            canvas.drawCircle(knob1X, y1, r, innerPaint);
            canvas.drawCircle(knob1X, y1, r, circlePaint);

            // Lower line with knob at 35%
            float knob2X = b.left + w * 0.35f;
            canvas.drawLine(b.left, y2, knob2X - r, y2, linePaint);
            canvas.drawLine(knob2X + r, y2, b.right, y2, linePaint);
            canvas.drawCircle(knob2X, y2, r, innerPaint);
            canvas.drawCircle(knob2X, y2, r, circlePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            linePaint.setAlpha(alpha);
            circlePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            linePaint.setColorFilter(colorFilter);
            circlePaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Download icon (arrow pointing down to horizontal tray).
     */
    private static class DownloadDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DownloadDrawable(int color) {
            paint.setColor(color);
            paint.setStrokeWidth(dp(1.8f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float cx = b.centerX();
            float cy = b.centerY();
            float arrowH = dp(4.5f);
            float arrowW = dp(3.5f);

            // Arrow vertical line
            canvas.drawLine(cx, cy - dp(5), cx, cy + dp(2), paint);
            // Arrow head
            canvas.drawLine(cx - arrowW, cy + dp(2) - arrowH, cx, cy + dp(2), paint);
            canvas.drawLine(cx + arrowW, cy + dp(2) - arrowH, cx, cy + dp(2), paint);
            // Bottom horizontal line
            canvas.drawLine(cx - dp(5.5f), cy + dp(6), cx + dp(5.5f), cy + dp(6), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
