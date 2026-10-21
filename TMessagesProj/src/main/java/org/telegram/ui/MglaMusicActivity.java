package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.MglaYandexMusicApi;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.VpnMonitor;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Yandex Music collection tab — calm theme-tinted surfaces.
 * Keeps: favorites playlist preview, playlists carousel, favorite artists.
 */
public class MglaMusicActivity extends BaseFragment implements
        MainTabsActivity.TabFragmentDelegate,
        MglaYandexMusicPlayer.PlayerListener,
        MglaYandexMusicStore.AuthListener {

    /** Theme-derived palette (soft accent, not neon). */
    private int MD3_SURFACE;
    private int MD3_SURFACE_CONTAINER;
    private int MD3_SURFACE_CONTAINER_HIGH;
    private int MD3_ON_SURFACE;
    private int MD3_ON_SURFACE_VARIANT;
    private int MD3_PRIMARY;
    private int MD3_PRIMARY_CONTAINER;
    private int MD3_PRIMARY_CONTAINER_MID;
    private int MD3_PRIMARY_CONTAINER_DEEP;
    private int MD3_ON_PRIMARY_CONTAINER;
    private int MD3_SECONDARY_CONTAINER;
    private int MD3_OUTLINE_VARIANT;
    private int MD3_ROW_SCRIM;

    private boolean hasMainTabs;

    // Root views
    private FrameLayout rootLayout;
    private FrameLayout mainContainer;

    /* Liquid glass source for MainTabs bottom island + player */
    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private IBlur3Capture iBlur3Capture;
    private boolean iBlur3Invalidated;
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();
    private final RectF iBlur3PositionMainTabs = new RectF();

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
    private LinearLayout favoritesCard;
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

    // Full track-list page (favorites or playlist)
    private TextView fullFavoritesTitleText;
    private TextView fullFavoritesCountText;
    private RecyclerListView fullFavoritesListView;
    private FullTrackAdapter fullFavoritesAdapter;
    private ProgressBar fullFavoritesLoading;
    private final List<MglaYandexMusicModel.Track> detailTracks = new ArrayList<>();
    private String detailSourceTitle = "Мне нравится";

    // Full playlists list page
    private LinearLayout fullPlaylistsView;
    private TextView fullPlaylistsCountText;
    private RecyclerListView fullPlaylistsListView;
    private FullPlaylistAdapter fullPlaylistsAdapter;
    private ProgressBar fullPlaylistsLoading;
    private boolean openedPlaylistFromFullList;

    // Full artists list page
    private LinearLayout fullArtistsView;
    private TextView fullArtistsCountText;
    private RecyclerListView fullArtistsListView;
    private FullArtistAdapter fullArtistsAdapter;
    private ProgressBar fullArtistsLoading;

    // Artist detail page
    private ScrollView artistPageView;
    private ImageView artistPageCover;
    private TextView artistPageName;
    private TextView artistPageSubtitle;
    private LinearLayout artistPopularContainer;
    private LinearLayout artistAlbumsContainer;
    private ProgressBar artistPageLoading;
    private TextView artistShowAllTracksBtn;
    private MglaYandexMusicModel.Artist currentArtist;
    private final List<MglaYandexMusicModel.Track> artistPopularTracks = new ArrayList<>();
    private final List<MglaYandexMusicModel.Track> artistAllTracks = new ArrayList<>();
    private final List<MglaYandexMusicModel.Album> artistAlbums = new ArrayList<>();
    private boolean openedArtistFromFullList;
    private boolean openedDetailFromArtist;
    private static final int ARTIST_POPULAR_PREVIEW = 8;

    // Search Page
    private EditText searchInput;
    private ImageView searchClearBtn;
    private LinearLayout searchTabsRow;
    private TextView searchTabTracks;
    private TextView searchTabAlbums;
    private TextView searchTabArtists;
    private RecyclerListView searchListView;
    private SearchAdapter searchAdapter;
    private final List<SearchRow> searchRows = new ArrayList<>();
    private MglaYandexMusicApi.SearchPage lastSearchPage;
    private int searchTab = SEARCH_TAB_TRACKS;
    private static final int SEARCH_TAB_TRACKS = 0;
    private static final int SEARCH_TAB_ALBUMS = 1;
    private static final int SEARCH_TAB_ARTISTS = 2;
    private ProgressBar searchLoading;
    private TextView searchEmptyView;
    private boolean openedFromSearch;

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
        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
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
    public void onResume() {
        super.onResume();
        updateCollectionWaveSubtitle();
        updateCollectionWaveButton();
    }

    /** Under «Моя волна»: VPN warning when active, otherwise the color tagline. */
    private void updateCollectionWaveSubtitle() {
        if (collectionWaveSubtitle == null) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            collectionWaveSubtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
            collectionWaveSubtitle.setText("При включённом VPN может не работать");
            return;
        }
        SpannableStringBuilder span = new SpannableStringBuilder("У вашей музыки есть цвет");
        span.setSpan(new ForegroundColorSpan(MD3_ON_SURFACE_VARIANT), 0, 20, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(MD3_PRIMARY), 20, 24, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        collectionWaveSubtitle.setText(span);
    }

    private void updateCollectionWaveButton() {
        if (collectionWaveTitle == null) {
            return;
        }
        MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
        boolean pausing = player.isCollectionWaveActive() && player.isPlaying();
        String label = pausing ? "  Пауза" : "  Слушать";
        SpannableStringBuilder span = new SpannableStringBuilder("\u200B" + label);
        WaveControlDrawable icon = new WaveControlDrawable(MD3_ON_PRIMARY_CONTAINER, pausing);
        icon.setBounds(0, 0, dp(18), dp(18));
        span.setSpan(new android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        collectionWaveTitle.setText(span);
    }

    /** Soft palette from the active Telegram theme — muted accent, quiet surfaces. */
    private void resolveThemeColors() {
        final int bg = getThemedColor(Theme.key_windowBackgroundGray);
        final int card = getThemedColor(Theme.key_windowBackgroundWhite);
        final int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int text2 = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        MD3_SURFACE = bg;
        MD3_SURFACE_CONTAINER = card;
        MD3_SURFACE_CONTAINER_HIGH = ColorUtils.blendARGB(card, text, 0.06f);
        MD3_ON_SURFACE = text;
        MD3_ON_SURFACE_VARIANT = text2;
        MD3_PRIMARY = ColorUtils.blendARGB(accent, text, 0.18f);
        MD3_PRIMARY_CONTAINER = ColorUtils.blendARGB(card, accent, 0.20f);
        MD3_PRIMARY_CONTAINER_MID = ColorUtils.blendARGB(card, accent, 0.30f);
        MD3_PRIMARY_CONTAINER_DEEP = ColorUtils.blendARGB(card, accent, 0.14f);
        MD3_ON_PRIMARY_CONTAINER = ColorUtils.blendARGB(text, accent, 0.28f);
        MD3_SECONDARY_CONTAINER = ColorUtils.blendARGB(card, text, 0.08f);
        MD3_OUTLINE_VARIANT = Theme.multAlpha(text, 0.12f);
        MD3_ROW_SCRIM = Theme.multAlpha(text, 0.05f);
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        resolveThemeColors();
        iBlur3SourceColor.setColor(MD3_SURFACE);

        rootLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    final boolean drawGlassContent = SharedConfig.chatBlurEnabled()
                            || LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(MD3_SURFACE);
                        if (drawGlassContent) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(MD3_SURFACE);
                        if (drawGlassContent) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                    iBlur3Invalidated = false;
                    MainTabsActivity.notifyTabGlassFrom(MglaMusicActivity.this);
                }
                super.dispatchDraw(canvas);
            }
        };
        rootLayout.setBackgroundColor(MD3_SURFACE);

        mainContainer = new FrameLayout(context);
        // Background fills to the physical bottom; scrollables reserve space for islands via their own padding.
        mainContainer.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        // 1. Main "Коллекция" Page
        collectionScrollView = createCollectionPage(context);
        mainContainer.addView(collectionScrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 2. Full Favorites / playlist tracks Page
        fullFavoritesView = createFullFavoritesPage(context);
        fullFavoritesView.setVisibility(View.GONE);
        mainContainer.addView(fullFavoritesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 3. Full playlists list
        fullPlaylistsView = createFullPlaylistsPage(context);
        fullPlaylistsView.setVisibility(View.GONE);
        mainContainer.addView(fullPlaylistsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 4. Full artists list
        fullArtistsView = createFullArtistsPage(context);
        fullArtistsView.setVisibility(View.GONE);
        mainContainer.addView(fullArtistsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 5. Artist detail page
        artistPageView = createArtistPage(context);
        artistPageView.setVisibility(View.GONE);
        mainContainer.addView(artistPageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 6. Search Page
        searchView = createSearchPage(context);
        searchView.setVisibility(View.GONE);
        mainContainer.addView(searchView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 7. Auth Promo Page (shown when unauthorized)
        authPromoView = createAuthPromoPage(context);
        authPromoView.setVisibility(View.GONE);
        mainContainer.addView(authPromoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        rootLayout.addView(mainContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // FrameLayout.drawChild is protected — draw children explicitly for the glass capture.
        iBlur3Capture = new ViewGroupPartRenderer(mainContainer, rootLayout, (canvas, child, drawingTime) -> {
            int save = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            child.draw(canvas);
            canvas.restoreToCount(save);
            return true;
        });

        updateAuthState();

        fragmentView = rootLayout;
        return fragmentView;
    }

    private ScrollView createBlurAwareScrollView(Context context) {
        return new ScrollView(context) {
            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(l - oldl, t - oldt);
                    iBlur3Invalidated = true;
                    if (rootLayout != null) {
                        rootLayout.invalidate();
                    }
                }
            }
        };
    }

    private void attachBlurScrollListener(RecyclerView list) {
        if (list == null || scrollableViewNoiseSuppressor == null) {
            return;
        }
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                    if (rootLayout != null) {
                        rootLayout.invalidate();
                    }
                }
            }
        });
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null) {
            return;
        }
        final int additionalList = dp(48);
        final int nav = AndroidUtilities.navigationBarHeight;
        final int mainTabBottom = fragmentView.getMeasuredHeight() - nav - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT)
                - dp(MglaGlobalMusicBar.blurExclusionExtraDp());

        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(),
                AndroidUtilities.statusBarHeight + additionalList);
        if (hasMainTabs) {
            iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
            iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));
        } else {
            iBlur3PositionMainTabs.setEmpty();
        }
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(
                iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    // ==========================================
    // MAIN COLLECTION PAGE
    // ==========================================

    private int islandBottomInset() {
        return AndroidUtilities.navigationBarHeight
                + (hasMainTabs
                ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + MglaGlobalMusicBar.BAR_HEIGHT + 12)
                : dp(16));
    }

    private ScrollView createCollectionPage(Context context) {
        ScrollView scroll = createBlurAwareScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, islandBottomInset());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));

        // Top app bar
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(10), dp(12), dp(8));

        if (!hasMainTabs) {
            topBar.addView(createMd3IconButton(context, R.drawable.ic_ab_back, v -> finishFragment()),
                    LayoutHelper.createLinear(40, 40, 0, 0, 8, 0));
        }

        collectionTitleView = new TextView(context);
        collectionTitleView.setText("Коллекция");
        collectionTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        collectionTitleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        collectionTitleView.setTextColor(MD3_ON_SURFACE);
        topBar.addView(collectionTitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        profileActionBtn = createMd3IconButton(context, R.drawable.settings_account, v -> onProfileClicked());
        topBar.addView(profileActionBtn, LayoutHelper.createLinear(40, 40, 0, 0, 6, 0));

        equalizerActionBtn = createMd3IconButton(context, null, v -> onEqualizerClicked());
        equalizerActionBtn.setImageDrawable(new SlidersDrawable(MD3_ON_SURFACE, MD3_SURFACE));
        topBar.addView(equalizerActionBtn, LayoutHelper.createLinear(40, 40, 0, 0, 6, 0));

        searchActionBtn = createMd3IconButton(context, R.drawable.outline_header_search, v -> openSearch());
        topBar.addView(searchActionBtn, LayoutHelper.createLinear(40, 40));

        content.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Hero: My Wave (primary container)
        FrameLayout waveCardWrapper = new FrameLayout(context);
        waveCardWrapper.setPadding(dp(16), dp(8), dp(16), dp(4));

        collectionWaveCard = new FrameLayout(context);
        GradientDrawable waveGradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{MD3_PRIMARY_CONTAINER_MID, MD3_PRIMARY_CONTAINER, MD3_PRIMARY_CONTAINER_DEEP}
        );
        waveGradient.setCornerRadius(dp(28));
        collectionWaveCard.setBackground(waveGradient);
        collectionWaveCard.setPadding(dp(22), dp(20), dp(22), dp(20));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            collectionWaveCard.setElevation(dp(2));
        }

        LinearLayout waveInner = new LinearLayout(context);
        waveInner.setOrientation(LinearLayout.VERTICAL);
        waveInner.setGravity(Gravity.CENTER);

        collectionWaveStatus = new TextView(context);
        collectionWaveStatus.setText("Моя волна по коллекции");
        collectionWaveStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        collectionWaveStatus.setLetterSpacing(0.02f);
        collectionWaveStatus.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.NORMAL);
        collectionWaveStatus.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        waveInner.addView(collectionWaveStatus, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        collectionWaveTitle = new TextView(context);
        collectionWaveTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        collectionWaveTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        collectionWaveTitle.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        collectionWaveTitle.setGravity(Gravity.CENTER_VERTICAL);
        waveInner.addView(collectionWaveTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        collectionWaveCard.addView(waveInner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        collectionWaveCard.setClickable(true);
        collectionWaveCard.setFocusable(true);
        collectionWaveCard.setOnClickListener(v -> MglaYandexMusicPlayer.getInstance().toggleCollectionWave());

        waveCardWrapper.addView(collectionWaveCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 96));
        content.addView(waveCardWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        collectionWaveSubtitle = new TextView(context);
        collectionWaveSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        collectionWaveSubtitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.NORMAL);
        collectionWaveSubtitle.setGravity(Gravity.CENTER);
        updateCollectionWaveSubtitle();
        updateCollectionWaveButton();
        content.addView(collectionWaveSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 16));

        // Favorites playlist preview — surface card
        favoritesCard = new LinearLayout(context);
        favoritesCard.setOrientation(LinearLayout.VERTICAL);
        favoritesCard.setBackground(md3SurfaceDrawable(MD3_SURFACE_CONTAINER, 28));
        favoritesCard.setPadding(dp(16), dp(14), dp(12), dp(12));

        favoritesHeaderRow = createFavoritesHeader(context);
        favoritesCard.addView(favoritesHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        favoritesPreviewLoading = new ProgressBar(context);
        favoritesPreviewLoading.setVisibility(View.GONE);
        favoritesCard.addView(favoritesPreviewLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));

        favoritesPreviewContainer = new LinearLayout(context);
        favoritesPreviewContainer.setOrientation(LinearLayout.VERTICAL);
        favoritesCard.addView(favoritesPreviewContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(favoritesCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // Playlists
        playlistsHeaderRow = createPlaylistsHeader(context);
        content.addView(playlistsHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 28, 16, 12));

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

        // Favorite artists
        artistsHeaderRow = createArtistsHeader(context);
        content.addView(artistsHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 28, 16, 12));

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

        scroll.addView(content);
        return scroll;
    }

    private static GradientDrawable md3SurfaceDrawable(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private ImageView createMd3IconButton(Context context, Integer iconRes, View.OnClickListener listener) {
        ImageView iv = new ImageView(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        iv.setBackground(bg);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (iconRes != null) {
            iv.setImageResource(iconRes);
            iv.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE, PorterDuff.Mode.SRC_IN));
        }
        iv.setOnClickListener(listener);
        return iv;
    }

    private View createFavoritesHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        FrameLayout badgeWrap = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(MD3_SECONDARY_CONTAINER);
        badgeWrap.setBackground(badgeBg);
        heartBadgeView = new HeartBadgeView(context);
        badgeWrap.addView(heartBadgeView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        row.addView(badgeWrap, LayoutHelper.createLinear(52, 52, 0, 0, 14, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        favoritesSectionTitle = new TextView(context);
        favoritesSectionTitle.setText("Мне нравится");
        favoritesSectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        favoritesSectionTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        favoritesSectionTitle.setTextColor(MD3_ON_SURFACE);
        textCol.addView(favoritesSectionTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        favoritesSectionCount = new TextView(context);
        favoritesSectionCount.setText("Загрузка...");
        favoritesSectionCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        favoritesSectionCount.setTextColor(MD3_ON_SURFACE_VARIANT);
        textCol.addView(favoritesSectionCount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.msg_arrowright);
        chevron.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        chevron.setPadding(dp(8), dp(8), dp(4), dp(8));
        row.addView(chevron, LayoutHelper.createLinear(36, 36));

        row.setOnClickListener(v -> showFullFavorites());
        return row;
    }

    private View createPlaylistsHeader(Context context) {
        LinearLayout row = createMd3SectionHeaderRow(context);
        playlistsSectionTitle = new TextView(context);
        styleMd3SectionTitle(playlistsSectionTitle, "Мои плейлисты");
        row.addView(playlistsSectionTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.addView(createMd3Chevron(context), LayoutHelper.createLinear(32, 32));
        row.setOnClickListener(v -> showFullPlaylists());
        return row;
    }

    private View createArtistsHeader(Context context) {
        LinearLayout row = createMd3SectionHeaderRow(context);
        artistsSectionTitle = new TextView(context);
        styleMd3SectionTitle(artistsSectionTitle, "Любимые исполнители");
        row.addView(artistsSectionTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.addView(createMd3Chevron(context), LayoutHelper.createLinear(32, 32));
        row.setOnClickListener(v -> showFullArtists());
        return row;
    }

    private LinearLayout createMd3SectionHeaderRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void styleMd3SectionTitle(TextView title, String text) {
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
    }

    private ImageView createMd3Chevron(Context context) {
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.msg_arrowright);
        chevron.setColorFilter(new PorterDuffColorFilter(MD3_PRIMARY, PorterDuff.Mode.SRC_IN));
        chevron.setPadding(dp(6), dp(6), dp(2), dp(6));
        return chevron;
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
            empty.setTextColor(MD3_ON_SURFACE_VARIANT);
            empty.setPadding(0, dp(10), 0, dp(10));
            favoritesPreviewContainer.addView(empty);
            return;
        }

        for (int i = 0; i < previewCount; i++) {
            final MglaYandexMusicModel.Track t = favoritesList.get(i);
            View row = createPreviewTrackRow(getContext(), t);
            favoritesPreviewContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, i < previewCount - 1 ? 4 : 0));
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
            empty.setTextColor(MD3_ON_SURFACE_VARIANT);
            empty.setPadding(0, dp(10), 0, dp(10));
            playlistsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < playlistsList.size(); i++) {
            final MglaYandexMusicModel.Playlist playlist = playlistsList.get(i);
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(12));
            card.setBackground(md3SurfaceDrawable(MD3_SURFACE_CONTAINER, 20));
            LinearLayout.LayoutParams cardLp = LayoutHelper.createLinear(148, LayoutHelper.WRAP_CONTENT);
            if (i < playlistsList.size() - 1) {
                cardLp.rightMargin = dp(10);
            }

            ImageView cover = new ImageView(context);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setCornerRadius(dp(16));
            coverBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
            cover.setBackground(coverBg);
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            MglaMusicImageHelper.bindCover(cover, playlist.getCoverUrl(200), 16, R.drawable.bot_music);
            card.addView(cover, LayoutHelper.createLinear(128, 128, Gravity.CENTER_HORIZONTAL));

            TextView title = new TextView(context);
            title.setText(playlist.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(10), 0, 0);
            card.addView(title, LayoutHelper.createLinear(128, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            TextView count = new TextView(context);
            count.setText(formatTrackCount(playlist.trackCount));
            count.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            count.setTextColor(MD3_ON_SURFACE_VARIANT);
            count.setSingleLine(true);
            card.addView(count, LayoutHelper.createLinear(128, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

            card.setOnClickListener(v -> openPlaylist(playlist));
            playlistsContainer.addView(card, cardLp);
        }
    }

    private void openPlaylist(MglaYandexMusicModel.Playlist playlist) {
        if (playlist == null) return;
        openedPlaylistFromFullList = fullPlaylistsView != null && fullPlaylistsView.getVisibility() == View.VISIBLE;
        openedDetailFromArtist = false;
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
        row.setPadding(dp(8), dp(8), dp(4), dp(8));
        row.setBackground(md3SurfaceDrawable(MD3_SURFACE_CONTAINER_HIGH, 16));

        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        MglaMusicImageHelper.bindCover(cover, track.getCoverUrl(100), 12, R.drawable.bot_music);
        row.addView(cover, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(track.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        if (track.isExplicit) {
            TextView explicitBadge = new TextView(context);
            explicitBadge.setText("E");
            explicitBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
            explicitBadge.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            explicitBadge.setTextColor(MD3_ON_SURFACE);
            explicitBadge.setGravity(Gravity.CENTER);
            GradientDrawable eBg = new GradientDrawable();
            eBg.setCornerRadius(dp(4));
            eBg.setColor(MD3_SECONDARY_CONTAINER);
            explicitBadge.setBackground(eBg);
            titleRow.addView(explicitBadge, LayoutHelper.createLinear(16, 16, 6, 0, 0, 0));
        }

        textCol.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView artist = new TextView(context);
        artist.setText(track.artist);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        artist.setTextColor(MD3_ON_SURFACE_VARIANT);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artist, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));

        ImageView moreBtn = new ImageView(context);
        moreBtn.setImageResource(R.drawable.ic_ab_other);
        moreBtn.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        moreBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        moreBtn.setOnClickListener(v -> showTrackOptionsMenu(track));
        row.addView(moreBtn, LayoutHelper.createLinear(36, 36));

        row.setOnClickListener(v -> MglaYandexMusicPlayer.getInstance().playTrack(track, favoritesList, "Мне нравится"));
        return row;
    }

    private void updateArtistsCarousel() {
        if (artistsContainer == null) return;
        artistsContainer.removeAllViews();

        if (artistsList.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Нет любимых исполнителей");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(MD3_ON_SURFACE_VARIANT);
            empty.setPadding(0, dp(10), 0, dp(10));
            artistsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < artistsList.size(); i++) {
            final MglaYandexMusicModel.Artist artist = artistsList.get(i);
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(8), dp(8), dp(8), dp(10));
            item.setBackground(md3SurfaceDrawable(MD3_SURFACE_CONTAINER, 20));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            if (i < artistsList.size() - 1) {
                lp.rightMargin = dp(10);
            }

            ImageView photo = new ImageView(getContext());
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            MglaMusicImageHelper.bindCircleCover(photo, artist.getCoverUrl(300), R.drawable.bot_music);
            item.addView(photo, LayoutHelper.createLinear(96, 96));

            TextView name = new TextView(getContext());
            name.setText(artist.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            name.setTextColor(MD3_ON_SURFACE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setWidth(dp(96));
            item.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

            item.setOnClickListener(v -> openArtist(artist));

            artistsContainer.addView(item, lp);
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

        ImageView backBtn = createMd3IconButton(context, R.drawable.ic_ab_back, v -> closeFullFavorites());
        header.addView(backBtn, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        fullFavoritesTitleText = new TextView(context);
        fullFavoritesTitleText.setText("Мне нравится");
        fullFavoritesTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        fullFavoritesTitleText.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        fullFavoritesTitleText.setTextColor(MD3_ON_SURFACE);
        textCol.addView(fullFavoritesTitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fullFavoritesCountText = new TextView(context);
        fullFavoritesCountText.setText("0 треков");
        fullFavoritesCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fullFavoritesCountText.setTextColor(MD3_ON_SURFACE_VARIANT);
        textCol.addView(fullFavoritesCountText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        header.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Action buttons: "Слушать всё" and "Перемешать"
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView playAllBtn = new TextView(context);
        playAllBtn.setText("▶  Слушать всё");
        playAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        playAllBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        playAllBtn.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        GradientDrawable playAllBg = new GradientDrawable();
        playAllBg.setCornerRadius(dp(20));
        playAllBg.setColor(MD3_PRIMARY_CONTAINER);
        playAllBtn.setBackground(playAllBg);
        playAllBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        playAllBtn.setOnClickListener(v -> {
            if (!detailTracks.isEmpty()) {
                MglaYandexMusicPlayer.getInstance().playTrack(detailTracks.get(0), new ArrayList<>(detailTracks), detailSourceTitle);
            }
        });
        actionRow.addView(playAllBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        TextView shuffleBtn = new TextView(context);
        shuffleBtn.setText("Перемешать");
        shuffleBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        shuffleBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        shuffleBtn.setTextColor(MD3_ON_SURFACE);
        GradientDrawable shuffBg = new GradientDrawable();
        shuffBg.setCornerRadius(dp(20));
        shuffBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        shuffleBtn.setBackground(shuffBg);
        shuffleBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
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
        fullFavoritesListView.setClipToPadding(false);
        fullFavoritesListView.setPadding(0, 0, 0, islandBottomInset());
        fullFavoritesAdapter = new FullTrackAdapter(detailTracks, (track, position) ->
                MglaYandexMusicPlayer.getInstance().playTrack(track, new ArrayList<>(detailTracks), detailSourceTitle));
        fullFavoritesListView.setAdapter(fullFavoritesAdapter);
        attachBlurScrollListener(fullFavoritesListView);
        layout.addView(fullFavoritesListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private void showFullFavorites() {
        openedPlaylistFromFullList = false;
        openedDetailFromArtist = false;
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
        if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
        if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
        if (artistPageView != null) artistPageView.setVisibility(View.GONE);
        if (fullFavoritesView != null) {
            fullFavoritesView.setVisibility(View.VISIBLE);
        }
    }

    private void closeFullFavorites() {
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (openedDetailFromArtist) {
            openedDetailFromArtist = false;
            if (artistPageView != null) {
                artistPageView.setVisibility(View.VISIBLE);
                return;
            }
        }
        if (openedFromSearch) {
            openedFromSearch = false;
            if (searchView != null) {
                searchView.setVisibility(View.VISIBLE);
                return;
            }
        }
        if (openedPlaylistFromFullList) {
            openedPlaylistFromFullList = false;
            if (fullPlaylistsView != null) {
                fullPlaylistsView.setVisibility(View.VISIBLE);
                return;
            }
        }
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.VISIBLE);
    }

    // ==========================================
    // FULL PLAYLISTS LIST PAGE
    // ==========================================

    private LinearLayout createFullPlaylistsPage(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), 0);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = createMd3IconButton(context, R.drawable.ic_ab_back, v -> closeFullPlaylists());
        header.addView(backBtn, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("Мои плейлисты");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
        textCol.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fullPlaylistsCountText = new TextView(context);
        fullPlaylistsCountText.setText("0 плейлистов");
        fullPlaylistsCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fullPlaylistsCountText.setTextColor(MD3_ON_SURFACE_VARIANT);
        textCol.addView(fullPlaylistsCountText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        header.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        fullPlaylistsLoading = new ProgressBar(context);
        fullPlaylistsLoading.setVisibility(View.GONE);
        layout.addView(fullPlaylistsLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 20));

        fullPlaylistsListView = new RecyclerListView(context);
        fullPlaylistsListView.setLayoutManager(new LinearLayoutManager(context));
        fullPlaylistsListView.setClipToPadding(false);
        fullPlaylistsListView.setPadding(0, 0, 0, islandBottomInset());
        fullPlaylistsAdapter = new FullPlaylistAdapter();
        fullPlaylistsListView.setAdapter(fullPlaylistsAdapter);
        fullPlaylistsListView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= playlistsList.size()) {
                return;
            }
            openPlaylist(playlistsList.get(position));
        });
        attachBlurScrollListener(fullPlaylistsListView);
        layout.addView(fullPlaylistsListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private void showFullPlaylists() {
        if (searchView != null) searchView.setVisibility(View.GONE);
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
        if (artistPageView != null) artistPageView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (fullPlaylistsView != null) {
            fullPlaylistsView.setVisibility(View.VISIBLE);
        }
        refreshFullPlaylistsUi();
        if (playlistsList.isEmpty()) {
            if (fullPlaylistsLoading != null) {
                fullPlaylistsLoading.setVisibility(View.VISIBLE);
            }
        }
        loadPlaylists();
    }

    private void closeFullPlaylists() {
        if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.VISIBLE);
    }

    private void refreshFullPlaylistsUi() {
        if (fullPlaylistsCountText != null) {
            fullPlaylistsCountText.setText(formatPlaylistCount(playlistsList.size()));
        }
        if (fullPlaylistsAdapter != null) {
            fullPlaylistsAdapter.notifyDataSetChanged();
        }
    }

    // ==========================================
    // FULL ARTISTS LIST PAGE
    // ==========================================

    private LinearLayout createFullArtistsPage(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), 0);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = createMd3IconButton(context, R.drawable.ic_ab_back, v -> closeFullArtists());
        header.addView(backBtn, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("Любимые исполнители");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
        textCol.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fullArtistsCountText = new TextView(context);
        fullArtistsCountText.setText("0 исполнителей");
        fullArtistsCountText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fullArtistsCountText.setTextColor(MD3_ON_SURFACE_VARIANT);
        textCol.addView(fullArtistsCountText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        header.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        fullArtistsLoading = new ProgressBar(context);
        fullArtistsLoading.setVisibility(View.GONE);
        layout.addView(fullArtistsLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 20));

        fullArtistsListView = new RecyclerListView(context);
        fullArtistsListView.setLayoutManager(new LinearLayoutManager(context));
        fullArtistsListView.setClipToPadding(false);
        fullArtistsListView.setPadding(0, 0, 0, islandBottomInset());
        fullArtistsAdapter = new FullArtistAdapter();
        fullArtistsListView.setAdapter(fullArtistsAdapter);
        fullArtistsListView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= artistsList.size()) {
                return;
            }
            openArtist(artistsList.get(position));
        });
        attachBlurScrollListener(fullArtistsListView);
        layout.addView(fullArtistsListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private void showFullArtists() {
        if (searchView != null) searchView.setVisibility(View.GONE);
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
        if (artistPageView != null) artistPageView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (fullArtistsView != null) {
            fullArtistsView.setVisibility(View.VISIBLE);
        }
        refreshFullArtistsUi();
        if (artistsList.isEmpty()) {
            if (fullArtistsLoading != null) {
                fullArtistsLoading.setVisibility(View.VISIBLE);
            }
        }
        loadArtists();
    }

    private void closeFullArtists() {
        if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.VISIBLE);
    }

    private void refreshFullArtistsUi() {
        if (fullArtistsCountText != null) {
            fullArtistsCountText.setText(formatArtistCount(artistsList.size()));
        }
        if (fullArtistsAdapter != null) {
            fullArtistsAdapter.notifyDataSetChanged();
        }
    }

    private void openArtist(MglaYandexMusicModel.Artist artist) {
        if (artist == null || TextUtils.isEmpty(artist.id)) {
            return;
        }
        openedArtistFromFullList = fullArtistsView != null && fullArtistsView.getVisibility() == View.VISIBLE;
        if (searchView != null && searchView.getVisibility() == View.VISIBLE) {
            openedFromSearch = true;
        }
        openedDetailFromArtist = false;
        currentArtist = artist;
        artistPopularTracks.clear();
        artistAllTracks.clear();
        artistAlbums.clear();

        if (artistPageName != null) {
            artistPageName.setText(artist.name);
        }
        if (artistPageSubtitle != null) {
            artistPageSubtitle.setText("Популярные треки и альбомы");
        }
        if (artistPageCover != null) {
            MglaMusicImageHelper.bindCircleCover(artistPageCover, artist.getCoverUrl(300), R.drawable.bot_music);
        }
        if (artistPopularContainer != null) {
            artistPopularContainer.removeAllViews();
        }
        if (artistAlbumsContainer != null) {
            artistAlbumsContainer.removeAllViews();
        }
        if (artistShowAllTracksBtn != null) {
            artistShowAllTracksBtn.setVisibility(View.GONE);
        }

        hideAllContentPages();
        if (artistPageView != null) {
            artistPageView.setVisibility(View.VISIBLE);
        }
        if (artistPageLoading != null) {
            artistPageLoading.setVisibility(View.VISIBLE);
        }

        final String artistId = artist.id;
        MglaYandexMusicApi.fetchArtistBrief(artistId, new MglaYandexMusicApi.Callback<MglaYandexMusicModel.ArtistBrief>() {
            @Override
            public void onSuccess(MglaYandexMusicModel.ArtistBrief brief) {
                if (currentArtist == null || !TextUtils.equals(currentArtist.id, artistId)) {
                    return;
                }
                if (artistPageLoading != null) {
                    artistPageLoading.setVisibility(View.GONE);
                }
                if (brief != null && brief.artist != null) {
                    currentArtist = brief.artist;
                    if (artistPageName != null) {
                        artistPageName.setText(brief.artist.name);
                    }
                    if (artistPageCover != null) {
                        MglaMusicImageHelper.bindCircleCover(artistPageCover, brief.artist.getCoverUrl(300), R.drawable.bot_music);
                    }
                }
                artistPopularTracks.clear();
                if (brief != null && brief.popularTracks != null) {
                    artistPopularTracks.addAll(brief.popularTracks);
                }
                artistAlbums.clear();
                if (brief != null && brief.albums != null) {
                    artistAlbums.addAll(brief.albums);
                }
                bindArtistPopularPreview();
                bindArtistAlbums();
                if (artistAlbums.isEmpty()) {
                    loadArtistAlbumsFallback(artistId);
                }
            }

            @Override
            public void onError(Exception error) {
                if (artistPageLoading != null) {
                    artistPageLoading.setVisibility(View.GONE);
                }
                Toast.makeText(getContext(), "Не удалось загрузить артиста", Toast.LENGTH_SHORT).show();
                loadArtistTracksFallback(artistId);
            }
        });
    }

    private ScrollView createArtistPage(Context context) {
        ScrollView scroll = createBlurAwareScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, islandBottomInset());
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), dp(24));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = createMd3IconButton(context, R.drawable.ic_ab_back, v -> closeArtistPage());
        header.addView(backBtn, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        TextView title = new TextView(context);
        title.setText("Исполнитель");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));

        artistPageCover = new ImageView(context);
        artistPageCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layout.addView(artistPageCover, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        artistPageName = new TextView(context);
        artistPageName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        artistPageName.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        artistPageName.setTextColor(MD3_ON_SURFACE);
        artistPageName.setGravity(Gravity.CENTER);
        layout.addView(artistPageName, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        artistPageSubtitle = new TextView(context);
        artistPageSubtitle.setText("Популярные треки и альбомы");
        artistPageSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        artistPageSubtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
        artistPageSubtitle.setGravity(Gravity.CENTER);
        layout.addView(artistPageSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView playPopularBtn = new TextView(context);
        playPopularBtn.setText("▶  Слушать популярное");
        playPopularBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        playPopularBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        playPopularBtn.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        playPopularBtn.setGravity(Gravity.CENTER);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setCornerRadius(dp(22));
        playBg.setColor(MD3_PRIMARY_CONTAINER);
        playPopularBtn.setBackground(playBg);
        playPopularBtn.setPadding(dp(18), dp(12), dp(18), dp(12));
        playPopularBtn.setOnClickListener(v -> {
            if (!artistPopularTracks.isEmpty()) {
                String titleText = currentArtist != null ? currentArtist.name : "Исполнитель";
                MglaYandexMusicPlayer.getInstance().playTrack(
                        artistPopularTracks.get(0),
                        new ArrayList<>(artistPopularTracks),
                        titleText);
            }
        });
        layout.addView(playPopularBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));

        artistPageLoading = new ProgressBar(context);
        artistPageLoading.setVisibility(View.GONE);
        layout.addView(artistPageLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 10));

        TextView popularHeader = new TextView(context);
        popularHeader.setText("Популярные треки");
        popularHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        popularHeader.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        popularHeader.setTextColor(MD3_ON_SURFACE);
        layout.addView(popularHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        artistPopularContainer = new LinearLayout(context);
        artistPopularContainer.setOrientation(LinearLayout.VERTICAL);
        layout.addView(artistPopularContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistShowAllTracksBtn = new TextView(context);
        artistShowAllTracksBtn.setText("Все треки");
        artistShowAllTracksBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        artistShowAllTracksBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        artistShowAllTracksBtn.setTextColor(MD3_PRIMARY);
        artistShowAllTracksBtn.setGravity(Gravity.CENTER);
        GradientDrawable allTracksBg = new GradientDrawable();
        allTracksBg.setCornerRadius(dp(18));
        allTracksBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        artistShowAllTracksBtn.setBackground(allTracksBg);
        artistShowAllTracksBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        artistShowAllTracksBtn.setVisibility(View.GONE);
        artistShowAllTracksBtn.setOnClickListener(v -> openArtistAllTracks());
        layout.addView(artistShowAllTracksBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        TextView albumsHeader = new TextView(context);
        albumsHeader.setText("Альбомы");
        albumsHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        albumsHeader.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        albumsHeader.setTextColor(MD3_ON_SURFACE);
        layout.addView(albumsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 0, 12));

        HorizontalScrollView albumsScroll = new HorizontalScrollView(context);
        albumsScroll.setHorizontalScrollBarEnabled(false);
        albumsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        albumsScroll.setClipToPadding(false);

        artistAlbumsContainer = new LinearLayout(context);
        artistAlbumsContainer.setOrientation(LinearLayout.HORIZONTAL);
        albumsScroll.addView(artistAlbumsContainer, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        layout.addView(albumsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scroll;
    }

    private void bindArtistPopularPreview() {
        if (artistPopularContainer == null) {
            return;
        }
        artistPopularContainer.removeAllViews();
        if (artistPopularTracks.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Популярные треки не найдены");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(MD3_ON_SURFACE_VARIANT);
            artistPopularContainer.addView(empty);
            if (artistShowAllTracksBtn != null) {
                artistShowAllTracksBtn.setVisibility(View.GONE);
            }
            return;
        }

        int preview = Math.min(ARTIST_POPULAR_PREVIEW, artistPopularTracks.size());
        String source = currentArtist != null ? currentArtist.name : "Исполнитель";
        for (int i = 0; i < preview; i++) {
            final MglaYandexMusicModel.Track track = artistPopularTracks.get(i);
            View row = createPreviewTrackRow(getContext(), track);
            row.setOnClickListener(v -> MglaYandexMusicPlayer.getInstance().playTrack(
                    track, new ArrayList<>(artistPopularTracks), source));
            artistPopularContainer.addView(row, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, i < preview - 1 ? 4 : 0));
        }
        if (artistShowAllTracksBtn != null) {
            artistShowAllTracksBtn.setVisibility(View.VISIBLE);
            artistShowAllTracksBtn.setText(artistPopularTracks.size() > ARTIST_POPULAR_PREVIEW
                    ? "Все треки"
                    : "Показать все треки");
        }
    }

    private void bindArtistAlbums() {
        if (artistAlbumsContainer == null) {
            return;
        }
        artistAlbumsContainer.removeAllViews();
        if (artistAlbums.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Альбомы не найдены");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(MD3_ON_SURFACE_VARIANT);
            artistAlbumsContainer.addView(empty);
            return;
        }
        for (int i = 0; i < artistAlbums.size(); i++) {
            final MglaYandexMusicModel.Album album = artistAlbums.get(i);
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(10));
            card.setBackground(md3SurfaceDrawable(MD3_SURFACE_CONTAINER, 16));
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            if (i < artistAlbums.size() - 1) {
                lp.rightMargin = dp(10);
            }

            ImageView cover = new ImageView(getContext());
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            MglaMusicImageHelper.bindCover(cover, album.getCoverUrl(200), 12, R.drawable.bot_music);
            card.addView(cover, LayoutHelper.createLinear(120, 120));

            TextView title = new TextView(getContext());
            title.setText(album.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(8), 0, 0);
            card.addView(title, LayoutHelper.createLinear(120, LayoutHelper.WRAP_CONTENT));

            TextView meta = new TextView(getContext());
            String year = album.year > 0 ? String.valueOf(album.year) : "";
            String count = album.trackCount > 0 ? formatTrackCount(album.trackCount) : "";
            meta.setText(TextUtils.isEmpty(year) ? count : (TextUtils.isEmpty(count) ? year : year + " · " + count));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            meta.setTextColor(MD3_ON_SURFACE_VARIANT);
            meta.setSingleLine(true);
            card.addView(meta, LayoutHelper.createLinear(120, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            card.setOnClickListener(v -> openAlbum(album));
            artistAlbumsContainer.addView(card, lp);
        }
    }

    private void loadArtistAlbumsFallback(String artistId) {
        MglaYandexMusicApi.fetchArtistAlbums(artistId, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Album>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Album> albums) {
                if (currentArtist == null || !TextUtils.equals(currentArtist.id, artistId)) {
                    return;
                }
                artistAlbums.clear();
                if (albums != null) {
                    artistAlbums.addAll(albums);
                }
                bindArtistAlbums();
            }

            @Override
            public void onError(Exception error) {
            }
        });
    }

    private void loadArtistTracksFallback(String artistId) {
        MglaYandexMusicApi.fetchArtistTracks(artistId, 0, 40, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (currentArtist == null || !TextUtils.equals(currentArtist.id, artistId)) {
                    return;
                }
                artistPopularTracks.clear();
                if (tracks != null) {
                    artistPopularTracks.addAll(tracks);
                }
                bindArtistPopularPreview();
            }

            @Override
            public void onError(Exception error) {
            }
        });
    }

    private void openArtistAllTracks() {
        if (currentArtist == null || TextUtils.isEmpty(currentArtist.id)) {
            return;
        }
        openedDetailFromArtist = true;
        detailSourceTitle = currentArtist.name;
        detailTracks.clear();
        if (!artistAllTracks.isEmpty()) {
            detailTracks.addAll(artistAllTracks);
        } else if (!artistPopularTracks.isEmpty()) {
            detailTracks.addAll(artistPopularTracks);
        }
        if (fullFavoritesTitleText != null) {
            fullFavoritesTitleText.setText(currentArtist.name);
        }
        if (fullFavoritesCountText != null) {
            fullFavoritesCountText.setText(formatTrackCount(detailTracks.size()));
        }
        if (fullFavoritesAdapter != null) {
            fullFavoritesAdapter.notifyDataSetChanged();
        }
        showTrackDetailPage();
        if (fullFavoritesLoading != null) {
            fullFavoritesLoading.setVisibility(View.VISIBLE);
        }

        final String artistId = currentArtist.id;
        final String artistName = currentArtist.name;
        artistAllTracks.clear();
        loadArtistTracksPages(artistId, artistName, 0);
    }

    private void loadArtistTracksPages(String artistId, String artistName, int page) {
        MglaYandexMusicApi.fetchArtistTracks(artistId, page, 50, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (currentArtist == null || !TextUtils.equals(currentArtist.id, artistId)) {
                    return;
                }
                if (tracks != null && !tracks.isEmpty()) {
                    artistAllTracks.addAll(tracks);
                    if (tracks.size() >= 50 && page < 8) {
                        loadArtistTracksPages(artistId, artistName, page + 1);
                        return;
                    }
                }
                if (fullFavoritesLoading != null) {
                    fullFavoritesLoading.setVisibility(View.GONE);
                }
                detailTracks.clear();
                detailTracks.addAll(artistAllTracks.isEmpty() ? artistPopularTracks : artistAllTracks);
                detailSourceTitle = artistName;
                if (fullFavoritesCountText != null) {
                    fullFavoritesCountText.setText(formatTrackCount(detailTracks.size()));
                }
                if (fullFavoritesAdapter != null) {
                    fullFavoritesAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onError(Exception error) {
                if (fullFavoritesLoading != null) {
                    fullFavoritesLoading.setVisibility(View.GONE);
                }
                if (detailTracks.isEmpty()) {
                    Toast.makeText(getContext(), "Не удалось загрузить треки", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void openAlbum(MglaYandexMusicModel.Album album) {
        if (album == null || TextUtils.isEmpty(album.id)) {
            return;
        }
        boolean fromArtist = artistPageView != null && artistPageView.getVisibility() == View.VISIBLE;
        boolean fromSearch = searchView != null && searchView.getVisibility() == View.VISIBLE;
        openedDetailFromArtist = fromArtist;
        if (fromSearch) {
            openedFromSearch = true;
        } else if (fromArtist) {
            openedFromSearch = false;
        }
        detailSourceTitle = album.title;
        detailTracks.clear();
        if (fullFavoritesTitleText != null) {
            fullFavoritesTitleText.setText(album.title);
        }
        if (fullFavoritesCountText != null) {
            fullFavoritesCountText.setText(album.trackCount > 0 ? formatTrackCount(album.trackCount) : "Загрузка...");
        }
        if (fullFavoritesAdapter != null) {
            fullFavoritesAdapter.notifyDataSetChanged();
        }
        showTrackDetailPage();
        if (fullFavoritesLoading != null) {
            fullFavoritesLoading.setVisibility(View.VISIBLE);
        }

        MglaYandexMusicApi.fetchAlbumTracks(album.id, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (fullFavoritesLoading != null) {
                    fullFavoritesLoading.setVisibility(View.GONE);
                }
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
                if (fullFavoritesLoading != null) {
                    fullFavoritesLoading.setVisibility(View.GONE);
                }
                Toast.makeText(getContext(), "Не удалось загрузить альбом", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void closeArtistPage() {
        if (artistPageView != null) {
            artistPageView.setVisibility(View.GONE);
        }
        if (openedFromSearch) {
            openedFromSearch = false;
            if (searchView != null) {
                searchView.setVisibility(View.VISIBLE);
                return;
            }
        }
        if (openedArtistFromFullList) {
            openedArtistFromFullList = false;
            if (fullArtistsView != null) {
                fullArtistsView.setVisibility(View.VISIBLE);
                return;
            }
        }
        if (collectionScrollView != null) {
            collectionScrollView.setVisibility(View.VISIBLE);
        }
    }

    private void hideAllContentPages() {
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
        if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
        if (artistPageView != null) artistPageView.setVisibility(View.GONE);
        if (searchView != null) searchView.setVisibility(View.GONE);
    }

    private static String formatArtistCount(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 19) {
            return count + " исполнителей";
        }
        if (mod10 == 1) {
            return count + " исполнитель";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return count + " исполнителя";
        }
        return count + " исполнителей";
    }

    private static String formatPlaylistCount(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 19) {
            return count + " плейлистов";
        }
        if (mod10 == 1) {
            return count + " плейлист";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return count + " плейлиста";
        }
        return count + " плейлистов";
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

        ImageView backBtn = createMd3IconButton(context, R.drawable.ic_ab_back, v -> closeSearch());
        bar.addView(backBtn, LayoutHelper.createLinear(40, 40, 0, 0, 10, 0));

        LinearLayout searchPill = new LinearLayout(context);
        searchPill.setOrientation(LinearLayout.HORIZONTAL);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(28));
        pillBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        searchPill.setBackground(pillBg);
        searchPill.setPadding(dp(14), 0, dp(10), 0);

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        searchPill.addView(searchIcon, LayoutHelper.createLinear(18, 18, 0, 0, 8, 0));

        searchInput = new EditText(context);
        searchInput.setHint("Трек или исполнитель");
        searchInput.setHintTextColor(MD3_ON_SURFACE_VARIANT);
        searchInput.setTextColor(MD3_ON_SURFACE);
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
        searchClearBtn.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        searchClearBtn.setVisibility(View.GONE);
        searchClearBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchClearBtn.setOnClickListener(v -> searchInput.setText(""));
        searchPill.addView(searchClearBtn, LayoutHelper.createLinear(24, 24));

        bar.addView(searchPill, LayoutHelper.createLinear(0, 42, 1f));
        layout.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        searchTabsRow = new LinearLayout(context);
        searchTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        searchTabsRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tabsBg = new GradientDrawable();
        tabsBg.setCornerRadius(dp(22));
        tabsBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        searchTabsRow.setBackground(tabsBg);
        searchTabsRow.setPadding(dp(4), dp(4), dp(4), dp(4));

        searchTabTracks = createSearchTab(context, "Треки", SEARCH_TAB_TRACKS);
        searchTabAlbums = createSearchTab(context, "Альбомы", SEARCH_TAB_ALBUMS);
        searchTabArtists = createSearchTab(context, "Исполнители", SEARCH_TAB_ARTISTS);
        searchTabsRow.addView(searchTabTracks, LayoutHelper.createLinear(0, 36, 1f));
        searchTabsRow.addView(searchTabAlbums, LayoutHelper.createLinear(0, 36, 1f, 4, 0, 4, 0));
        searchTabsRow.addView(searchTabArtists, LayoutHelper.createLinear(0, 36, 1f));
        layout.addView(searchTabsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        updateSearchTabsUi();

        searchLoading = new ProgressBar(context);
        searchLoading.setVisibility(View.GONE);
        layout.addView(searchLoading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 20));

        searchEmptyView = new TextView(context);
        searchEmptyView.setText("Введите название трека, альбома или артиста");
        searchEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        searchEmptyView.setTextColor(MD3_ON_SURFACE_VARIANT);
        searchEmptyView.setGravity(Gravity.CENTER);
        searchEmptyView.setPadding(dp(32), dp(40), dp(32), dp(40));
        layout.addView(searchEmptyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        searchListView = new RecyclerListView(context);
        searchListView.setLayoutManager(new LinearLayoutManager(context));
        searchListView.setClipToPadding(false);
        searchListView.setPadding(0, 0, 0, islandBottomInset());
        searchAdapter = new SearchAdapter();
        searchListView.setAdapter(searchAdapter);
        searchListView.setOnItemClickListener((view, position) -> onSearchRowClicked(position));
        attachBlurScrollListener(searchListView);
        layout.addView(searchListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        return layout;
    }

    private TextView createSearchTab(Context context, String title, int tab) {
        TextView tabView = new TextView(context);
        tabView.setText(title);
        tabView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tabView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        tabView.setGravity(Gravity.CENTER);
        tabView.setPadding(dp(8), 0, dp(8), 0);
        tabView.setOnClickListener(v -> setSearchTab(tab));
        return tabView;
    }

    private void setSearchTab(int tab) {
        if (searchTab == tab) {
            return;
        }
        searchTab = tab;
        updateSearchTabsUi();
        bindSearchRowsFromPage(lastSearchPage, searchInput != null ? searchInput.getText().toString().trim() : "");
    }

    private void updateSearchTabsUi() {
        styleSearchTab(searchTabTracks, searchTab == SEARCH_TAB_TRACKS);
        styleSearchTab(searchTabAlbums, searchTab == SEARCH_TAB_ALBUMS);
        styleSearchTab(searchTabArtists, searchTab == SEARCH_TAB_ARTISTS);
    }

    private void styleSearchTab(TextView tab, boolean selected) {
        if (tab == null) {
            return;
        }
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(MD3_PRIMARY_CONTAINER);
            tab.setBackground(bg);
            tab.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        } else {
            tab.setBackground(null);
            tab.setTextColor(MD3_ON_SURFACE_VARIANT);
        }
    }

    private void bindSearchRowsFromPage(MglaYandexMusicApi.SearchPage page, String query) {
        searchRows.clear();
        if (page != null) {
            if (searchTab == SEARCH_TAB_TRACKS && page.tracks != null) {
                for (MglaYandexMusicModel.Track track : page.tracks) {
                    searchRows.add(SearchRow.track(track));
                }
            } else if (searchTab == SEARCH_TAB_ALBUMS && page.albums != null) {
                for (MglaYandexMusicModel.Album album : page.albums) {
                    searchRows.add(SearchRow.album(album));
                }
            } else if (searchTab == SEARCH_TAB_ARTISTS && page.artists != null) {
                for (MglaYandexMusicModel.Artist artist : page.artists) {
                    searchRows.add(SearchRow.artist(artist));
                }
            }
        }
        if (searchAdapter != null) {
            searchAdapter.notifyDataSetChanged();
        }
        boolean emptyQuery = TextUtils.isEmpty(query);
        boolean empty = searchRows.isEmpty();
        if (searchEmptyView != null) {
            if (emptyQuery) {
                searchEmptyView.setVisibility(View.VISIBLE);
                searchEmptyView.setText("Введите название трека, альбома или артиста");
            } else if (empty) {
                searchEmptyView.setVisibility(View.VISIBLE);
                String kind = searchTab == SEARCH_TAB_ALBUMS ? "альбомов"
                        : searchTab == SEARCH_TAB_ARTISTS ? "исполнителей" : "треков";
                searchEmptyView.setText("Нет " + kind + " по запросу \"" + query + "\"");
            } else {
                searchEmptyView.setVisibility(View.GONE);
            }
        }
    }

    private void onSearchRowClicked(int position) {
        if (position < 0 || position >= searchRows.size()) {
            return;
        }
        SearchRow row = searchRows.get(position);
        if (row.type == SearchRow.TYPE_TRACK && row.track != null) {
            AndroidUtilities.hideKeyboard(searchInput);
            MglaYandexMusicPlayer.getInstance().playTrackThenWave(row.track);
        } else if (row.type == SearchRow.TYPE_ALBUM && row.album != null) {
            AndroidUtilities.hideKeyboard(searchInput);
            openAlbum(row.album);
        } else if (row.type == SearchRow.TYPE_ARTIST && row.artist != null) {
            AndroidUtilities.hideKeyboard(searchInput);
            openArtist(row.artist);
        }
    }

    private void openSearch() {
        if (collectionScrollView != null) collectionScrollView.setVisibility(View.GONE);
        if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
        if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
        if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
        if (artistPageView != null) artistPageView.setVisibility(View.GONE);
        openedPlaylistFromFullList = false;
        openedDetailFromArtist = false;
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
            lastSearchPage = null;
            searchLoading.setVisibility(View.GONE);
            bindSearchRowsFromPage(null, "");
            return;
        }

        searchRunnable = () -> {
            searchLoading.setVisibility(View.VISIBLE);
            searchEmptyView.setVisibility(View.GONE);

            MglaYandexMusicApi.searchAll(query, new MglaYandexMusicApi.Callback<MglaYandexMusicApi.SearchPage>() {
                @Override
                public void onSuccess(MglaYandexMusicApi.SearchPage page) {
                    searchLoading.setVisibility(View.GONE);
                    lastSearchPage = page;
                    bindSearchRowsFromPage(page, query);
                }

                @Override
                public void onError(Exception error) {
                    searchLoading.setVisibility(View.GONE);
                    lastSearchPage = null;
                    searchRows.clear();
                    if (searchAdapter != null) {
                        searchAdapter.notifyDataSetChanged();
                    }
                    searchEmptyView.setVisibility(View.VISIBLE);
                    searchEmptyView.setText("Ошибка поиска. Попробуйте ещё раз");
                }
            });
        };
        searchHandler.postDelayed(searchRunnable, 350);
    }

    // ==========================================
    // AUTH PROMO PAGE
    // ==========================================

    private View createAuthPromoPage(Context context) {
        ScrollView scroll = createBlurAwareScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MD3_SURFACE);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, islandBottomInset());

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(28), dp(20), dp(28), dp(32));

        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{MD3_PRIMARY_CONTAINER_MID, MD3_PRIMARY_CONTAINER}
        );
        badgeBg.setCornerRadius(dp(36));
        badge.setBackground(badgeBg);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.bot_music);
        icon.setColorFilter(new PorterDuffColorFilter(MD3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        badge.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        layout.addView(badge, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 22));

        TextView title = new TextView(context);
        title.setText("Яндекс Музыка в Mgla");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(MD3_ON_SURFACE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView subtitle = new TextView(context);
        subtitle.setText("Слушайте персональную Мою Волну, любимые треки и личную коллекцию прямо в Telegram");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        TextView loginBtn = new TextView(context);
        loginBtn.setText("Войти через Яндекс ID");
        loginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        loginBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        loginBtn.setTextColor(MD3_ON_PRIMARY_CONTAINER);
        loginBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{MD3_PRIMARY_CONTAINER_MID, MD3_PRIMARY_CONTAINER}
        );
        btnBg.setCornerRadius(dp(28));
        loginBtn.setBackground(btnBg);
        loginBtn.setPadding(dp(24), dp(14), dp(24), dp(14));
        loginBtn.setOnClickListener(v -> openAuthSheet(false));
        layout.addView(loginBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 12));

        TextView manualTokenBtn = new TextView(context);
        manualTokenBtn.setText("Ввести токен вручную");
        manualTokenBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        manualTokenBtn.setTextColor(MD3_PRIMARY);
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
            refreshFullArtistsUi();
            if (fullArtistsLoading != null && !artistsList.isEmpty()) {
                fullArtistsLoading.setVisibility(View.GONE);
            }
        } else {
            if (artistsLoading != null) artistsLoading.setVisibility(View.VISIBLE);
            if (fullArtistsView != null && fullArtistsView.getVisibility() == View.VISIBLE
                    && fullArtistsLoading != null) {
                fullArtistsLoading.setVisibility(View.VISIBLE);
            }
        }

        MglaYandexMusicApi.fetchLikedArtists(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Artist>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Artist> artists) {
                if (artistsLoading != null) artistsLoading.setVisibility(View.GONE);
                if (fullArtistsLoading != null) fullArtistsLoading.setVisibility(View.GONE);
                artistsList.clear();
                if (artists != null) {
                    artistsList.addAll(artists);
                }
                updateArtistsCarousel();
                refreshFullArtistsUi();
            }

            @Override
            public void onError(Exception error) {
                if (artistsLoading != null) artistsLoading.setVisibility(View.GONE);
                if (fullArtistsLoading != null) fullArtistsLoading.setVisibility(View.GONE);
            }
        });
    }

    private void updateAuthState() {
        boolean auth = MglaYandexMusicStore.getInstance().isAuthorized();
        if (authPromoView != null) {
            authPromoView.setVisibility(auth ? View.GONE : View.VISIBLE);
        }
        if (!auth) {
            if (fullPlaylistsView != null) fullPlaylistsView.setVisibility(View.GONE);
            if (fullArtistsView != null) fullArtistsView.setVisibility(View.GONE);
            if (artistPageView != null) artistPageView.setVisibility(View.GONE);
            if (fullFavoritesView != null) fullFavoritesView.setVisibility(View.GONE);
            if (searchView != null) searchView.setVisibility(View.GONE);
        }
        if (collectionScrollView != null) {
            boolean showCollection = auth
                    && (fullPlaylistsView == null || fullPlaylistsView.getVisibility() != View.VISIBLE)
                    && (fullArtistsView == null || fullArtistsView.getVisibility() != View.VISIBLE)
                    && (artistPageView == null || artistPageView.getVisibility() != View.VISIBLE)
                    && (fullFavoritesView == null || fullFavoritesView.getVisibility() != View.VISIBLE)
                    && (searchView == null || searchView.getVisibility() != View.VISIBLE);
            collectionScrollView.setVisibility(showCollection ? View.VISIBLE : View.GONE);
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
            refreshFullPlaylistsUi();
            if (fullPlaylistsLoading != null && !playlistsList.isEmpty()) {
                fullPlaylistsLoading.setVisibility(View.GONE);
            }
        } else {
            if (playlistsLoading != null) {
                playlistsLoading.setVisibility(View.VISIBLE);
            }
            if (fullPlaylistsView != null && fullPlaylistsView.getVisibility() == View.VISIBLE
                    && fullPlaylistsLoading != null) {
                fullPlaylistsLoading.setVisibility(View.VISIBLE);
            }
        }

        MglaYandexMusicApi.fetchPlaylists(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Playlist>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Playlist> playlists) {
                if (playlistsLoading != null) playlistsLoading.setVisibility(View.GONE);
                if (fullPlaylistsLoading != null) fullPlaylistsLoading.setVisibility(View.GONE);
                playlistsList.clear();
                if (playlists != null) {
                    for (MglaYandexMusicModel.Playlist p : playlists) {
                        if (p == null || p.kind == 3) continue; // favorites shown separately
                        playlistsList.add(p);
                    }
                }
                updatePlaylistsCarousel();
                refreshFullPlaylistsUi();
            }

            @Override
            public void onError(Exception error) {
                if (playlistsLoading != null) playlistsLoading.setVisibility(View.GONE);
                if (fullPlaylistsLoading != null) fullPlaylistsLoading.setVisibility(View.GONE);
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
        if (fullPlaylistsView != null && fullPlaylistsView.getVisibility() == View.VISIBLE) {
            closeFullPlaylists();
            return false;
        }
        if (artistPageView != null && artistPageView.getVisibility() == View.VISIBLE) {
            closeArtistPage();
            return false;
        }
        if (fullArtistsView != null && fullArtistsView.getVisibility() == View.VISIBLE) {
            closeFullArtists();
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
            updateCollectionWaveButton();
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
        AndroidUtilities.runOnUIThread(this::updateCollectionWaveButton);
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
        } else if (fullArtistsListView != null && fullArtistsView != null
                && fullArtistsView.getVisibility() == View.VISIBLE) {
            fullArtistsListView.smoothScrollToPosition(0);
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

    private class FullPlaylistAdapter extends RecyclerView.Adapter<FullPlaylistAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(8), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(MD3_SURFACE_CONTAINER);
            row.setBackground(bg);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setCornerRadius(dp(12));
            coverBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
            cover.setBackground(coverBg);
            cover.setClipToOutline(true);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            row.addView(cover, LayoutHelper.createLinear(56, 56, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView subtitle = new TextView(ctx);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            ImageView chevron = new ImageView(ctx);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
            row.addView(chevron, LayoutHelper.createLinear(24, 24));

            return new Holder(row, cover, title, subtitle);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MglaYandexMusicModel.Playlist p = playlistsList.get(position);
            holder.title.setText(p.title);
            String owner = !TextUtils.isEmpty(p.ownerName) ? p.ownerName + " · " : "";
            holder.subtitle.setText(owner + formatTrackCount(p.trackCount));
            MglaMusicImageHelper.bindCover(holder.cover, p.getCoverUrl(100), 12, R.drawable.bot_music);
        }

        @Override
        public int getItemCount() {
            return playlistsList.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView subtitle;

            Holder(View itemView, ImageView cover, TextView title, TextView subtitle) {
                super(itemView);
                this.cover = cover;
                this.title = title;
                this.subtitle = subtitle;
            }
        }
    }

    private static class SearchRow {
        static final int TYPE_HEADER = 0;
        static final int TYPE_TRACK = 1;
        static final int TYPE_ALBUM = 2;
        static final int TYPE_ARTIST = 3;

        final int type;
        final String header;
        final MglaYandexMusicModel.Track track;
        final MglaYandexMusicModel.Album album;
        final MglaYandexMusicModel.Artist artist;

        private SearchRow(int type, String header, MglaYandexMusicModel.Track track,
                          MglaYandexMusicModel.Album album, MglaYandexMusicModel.Artist artist) {
            this.type = type;
            this.header = header;
            this.track = track;
            this.album = album;
            this.artist = artist;
        }

        static SearchRow header(String title) {
            return new SearchRow(TYPE_HEADER, title, null, null, null);
        }

        static SearchRow track(MglaYandexMusicModel.Track track) {
            return new SearchRow(TYPE_TRACK, null, track, null, null);
        }

        static SearchRow album(MglaYandexMusicModel.Album album) {
            return new SearchRow(TYPE_ALBUM, null, null, album, null);
        }

        static SearchRow artist(MglaYandexMusicModel.Artist artist) {
            return new SearchRow(TYPE_ARTIST, null, null, null, artist);
        }
    }

    private class SearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return searchRows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            if (viewType == SearchRow.TYPE_HEADER) {
                TextView header = new TextView(ctx);
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                header.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
                header.setTextColor(MD3_ON_SURFACE);
                header.setPadding(dp(4), dp(14), dp(4), dp(8));
                return new RecyclerView.ViewHolder(header) {};
            }

            // Root must stay non-clickable: RecyclerListView only delivers taps via OnItemClickListener.
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setClickable(false);
            row.setFocusable(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(MD3_ROW_SCRIM);
            row.setBackground(bg);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView subtitle = new TextView(ctx);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            return new SearchItemHolder(row, cover, title, subtitle);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            SearchRow row = searchRows.get(position);
            if (row.type == SearchRow.TYPE_HEADER) {
                ((TextView) holder.itemView).setText(row.header);
                return;
            }
            SearchItemHolder h = (SearchItemHolder) holder;
            if (row.type == SearchRow.TYPE_TRACK && row.track != null) {
                h.title.setText(row.track.title);
                h.subtitle.setText(row.track.artist);
                MglaMusicImageHelper.bindCover(h.cover, row.track.getCoverUrl(100), 8, R.drawable.bot_music);
                MglaYandexMusicModel.Track current = MglaYandexMusicPlayer.getInstance().getCurrentTrack();
                boolean isThisPlaying = current != null && current.id != null && current.id.equals(row.track.id);
                h.title.setTextColor(isThisPlaying ? MD3_PRIMARY : MD3_ON_SURFACE);
            } else if (row.type == SearchRow.TYPE_ALBUM && row.album != null) {
                h.title.setText(row.album.title);
                h.title.setTextColor(MD3_ON_SURFACE);
                String meta = !TextUtils.isEmpty(row.album.artists) ? row.album.artists : "Альбом";
                if (row.album.year > 0) {
                    meta = meta + " · " + row.album.year;
                }
                h.subtitle.setText(meta);
                MglaMusicImageHelper.bindCover(h.cover, row.album.getCoverUrl(100), 8, R.drawable.bot_music);
            } else if (row.type == SearchRow.TYPE_ARTIST && row.artist != null) {
                h.title.setText(row.artist.name);
                h.title.setTextColor(MD3_ON_SURFACE);
                h.subtitle.setText("Исполнитель");
                MglaMusicImageHelper.bindCircleCover(h.cover, row.artist.getCoverUrl(100), R.drawable.bot_music);
            }
        }

        @Override
        public int getItemCount() {
            return searchRows.size();
        }

        class SearchItemHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView subtitle;

            SearchItemHolder(View itemView, ImageView cover, TextView title, TextView subtitle) {
                super(itemView);
                this.cover = cover;
                this.title = title;
                this.subtitle = subtitle;
            }
        }
    }

    private class FullArtistAdapter extends RecyclerView.Adapter<FullArtistAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(8), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(MD3_SURFACE_CONTAINER);
            row.setBackground(bg);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, LayoutHelper.createLinear(56, 56, 0, 0, 12, 0));

            TextView title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            ImageView chevron = new ImageView(ctx);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
            row.addView(chevron, LayoutHelper.createLinear(24, 24));

            return new Holder(row, cover, title);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MglaYandexMusicModel.Artist artist = artistsList.get(position);
            holder.title.setText(artist.name);
            MglaMusicImageHelper.bindCircleCover(holder.cover, artist.getCoverUrl(100), R.drawable.bot_music);
        }

        @Override
        public int getItemCount() {
            return artistsList.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;

            Holder(View itemView, ImageView cover, TextView title) {
                super(itemView);
                this.cover = cover;
                this.title = title;
            }
        }
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
            // Root stays non-clickable so Telegram RecyclerListView doesn't swallow touches.
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setClickable(false);
            row.setFocusable(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(MD3_ROW_SCRIM);
            row.setBackground(bg);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);

            // Click target is a direct child — RecyclerListView forwards taps to clickable children.
            LinearLayout playArea = new LinearLayout(ctx);
            playArea.setOrientation(LinearLayout.HORIZONTAL);
            playArea.setGravity(Gravity.CENTER_VERTICAL);
            playArea.setClickable(true);
            playArea.setFocusable(true);

            ImageView cover = new ImageView(ctx);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setClickable(false);
            playArea.addView(cover, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClickable(false);

            LinearLayout titleRow = new LinearLayout(ctx);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setClickable(false);

            TextView title = new TextView(ctx);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            title.setTextColor(MD3_ON_SURFACE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setClickable(false);
            titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView explicitTag = new TextView(ctx);
            explicitTag.setText("E");
            explicitTag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
            explicitTag.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
            explicitTag.setTextColor(MD3_ON_SURFACE);
            explicitTag.setGravity(Gravity.CENTER);
            explicitTag.setClickable(false);
            GradientDrawable eBg = new GradientDrawable();
            eBg.setCornerRadius(dp(4));
            eBg.setColor(MD3_SECONDARY_CONTAINER);
            explicitTag.setBackground(eBg);
            titleRow.addView(explicitTag, LayoutHelper.createLinear(16, 16, 6, 0, 0, 0));

            col.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView subtitle = new TextView(ctx);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(MD3_ON_SURFACE_VARIANT);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setClickable(false);
            col.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            playArea.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            TextView duration = new TextView(ctx);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            duration.setTextColor(MD3_ON_SURFACE_VARIANT);
            duration.setClickable(false);
            playArea.addView(duration, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

            row.addView(playArea, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            ImageView like = new ImageView(ctx);
            like.setPadding(dp(6), dp(6), dp(6), dp(6));
            row.addView(like, LayoutHelper.createLinear(36, 36));

            return new Holder(row, playArea, cover, title, explicitTag, subtitle, duration, like);
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
            boolean isThisPlaying = (current != null && current.id != null && current.id.equals(t.id));

            if (isThisPlaying) {
                holder.title.setTextColor(MD3_PRIMARY);
            } else {
                holder.title.setTextColor(MD3_ON_SURFACE);
            }

            if (t.isLiked) {
                holder.like.setImageResource(R.drawable.media_like_active);
                holder.like.setColorFilter(new PorterDuffColorFilter(0xFFFF334B, PorterDuff.Mode.SRC_IN));
            } else {
                holder.like.setImageResource(R.drawable.media_like);
                holder.like.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
            }

            holder.playArea.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTrackClick(t, position);
                }
            });

            holder.like.setOnClickListener(v -> {
                boolean nextLiked = !t.isLiked;
                t.isLiked = nextLiked;
                MglaYandexMusicStore.getInstance().setLiked(t.id, nextLiked);
                notifyItemChanged(position);
                MglaYandexMusicApi.toggleLike(t.id, nextLiked, null);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final View playArea;
            final ImageView cover;
            final TextView title;
            final TextView explicitTag;
            final TextView subtitle;
            final TextView duration;
            final ImageView like;

            Holder(View itemView, View playArea, ImageView cover, TextView title, TextView explicitTag, TextView subtitle, TextView duration, ImageView like) {
                super(itemView);
                this.playArea = playArea;
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

    /** Play triangle / pause bars — same filled geometric style for the wave card. */
    private static class WaveControlDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean pause;
        private final android.graphics.Path triangle = new android.graphics.Path();

        WaveControlDrawable(int color, boolean pause) {
            this.pause = pause;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float l = b.left;
            float t = b.top;
            float w = b.width();
            float h = b.height();
            if (pause) {
                float barW = w * 0.22f;
                float gap = w * 0.18f;
                float top = t + h * 0.12f;
                float bottom = t + h * 0.88f;
                float x1 = l + (w - barW * 2 - gap) / 2f;
                canvas.drawRoundRect(x1, top, x1 + barW, bottom, barW / 2f, barW / 2f, paint);
                canvas.drawRoundRect(x1 + barW + gap, top, x1 + barW * 2 + gap, bottom, barW / 2f, barW / 2f, paint);
            } else {
                triangle.reset();
                float left = l + w * 0.22f;
                float right = l + w * 0.88f;
                float top = t + h * 0.12f;
                float bottom = t + h * 0.88f;
                float mid = t + h * 0.5f;
                triangle.moveTo(left, top);
                triangle.lineTo(right, mid);
                triangle.lineTo(left, bottom);
                triangle.close();
                canvas.drawPath(triangle, paint);
            }
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

        @Override
        public int getIntrinsicWidth() {
            return dp(18);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(18);
        }
    }

    /**
     * Two-slider equalizer icon matching the top-right toolbar icon.
     */
    private static class SlidersDrawable extends Drawable {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SlidersDrawable(int color, int surfaceFill) {
            linePaint.setColor(color);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);

            circlePaint.setColor(color);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(dp(2));

            innerPaint.setColor(surfaceFill);
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

}
