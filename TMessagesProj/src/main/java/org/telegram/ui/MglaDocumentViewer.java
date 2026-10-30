package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MglaDocumentHelper;
import org.telegram.messenger.MglaOfficeHtml;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Material Design 3 built-in viewer for PDF, Office, text and related formats.
 */
public class MglaDocumentViewer extends BaseFragment {

    private static final String ARG_PATH = "path";
    private static final String ARG_NAME = "name";
    private static final String ARG_MIME = "mime";

    private File file;
    private String fileName;
    private String mimeType;
    private int kind;

    private int MD3_SURFACE;
    private int MD3_SURFACE_CONTAINER;
    private int MD3_SURFACE_CONTAINER_HIGH;
    private int MD3_ON_SURFACE;
    private int MD3_ON_SURFACE_VARIANT;
    private int MD3_PRIMARY;
    private int MD3_PRIMARY_CONTAINER;
    private int MD3_ON_PRIMARY_CONTAINER;
    private int MD3_OUTLINE;
    private int MD3_ERROR_CONTAINER;
    private int MD3_ON_ERROR;

    private FrameLayout root;
    private FrameLayout contentHost;
    private LinearLayout topBar;
    private LinearLayout searchBar;
    private FrameLayout bottomIsland;
    private ProgressBar loadingView;
    private TextView errorView;
    private TextView titleView;
    private TextView subtitleView;
    private EditTextBoldCursor searchField;
    private TextView searchCountView;
    private TextView pageLabel;
    private ImageView invertBtn;
    private ImageView searchBtn;

    private RecyclerListView pdfList;
    private PdfAdapter pdfAdapter;
    private ZoomFrame pdfZoomHost;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;
    private final ConcurrentHashMap<Integer, Bitmap> pdfCache = new ConcurrentHashMap<>();
    private final Object pdfLock = new Object();
    private int pdfPageCount;
    private boolean pdfInvert;
    private float pdfScale = 1f;

    private WebView webView;
    private String plainText = "";
    private String htmlContent = "";
    private boolean legacyUnsupported;
    private File mediaDir;
    private File cacheDir;

    private boolean searchOpen;
    private int searchIndex;
    private int searchTotal;
    private boolean chromeVisible = true;
    private ValueAnimator chromeAnimator;

    public static MglaDocumentViewer create(File file, String fileName, String mimeType) {
        Bundle args = new Bundle();
        args.putString(ARG_PATH, file.getAbsolutePath());
        args.putString(ARG_NAME, fileName);
        args.putString(ARG_MIME, mimeType);
        return new MglaDocumentViewer(args);
    }

    public MglaDocumentViewer(Bundle args) {
        super(args);
    }

    public MglaDocumentViewer() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        Bundle args = getArguments();
        if (args == null) {
            return false;
        }
        String path = args.getString(ARG_PATH);
        fileName = args.getString(ARG_NAME, "document");
        mimeType = args.getString(ARG_MIME, "");
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        file = new File(path);
        if (!file.exists()) {
            return false;
        }
        kind = MglaDocumentHelper.kindOf(file, fileName, mimeType);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        closePdf();
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        clearPdfCache();
        super.onFragmentDestroy();
    }

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
        MD3_PRIMARY = ColorUtils.blendARGB(accent, text, 0.12f);
        MD3_PRIMARY_CONTAINER = ColorUtils.blendARGB(card, accent, 0.22f);
        MD3_ON_PRIMARY_CONTAINER = ColorUtils.blendARGB(text, accent, 0.28f);
        MD3_OUTLINE = Theme.multAlpha(text, 0.14f);
        MD3_ERROR_CONTAINER = ColorUtils.blendARGB(card, 0xFFB3261E, 0.18f);
        MD3_ON_ERROR = ColorUtils.blendARGB(text, 0xFFB3261E, 0.55f);
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        resolveThemeColors();
        hasOwnBackground = true;

        cacheDir = new File(context.getCacheDir(), "mgla_docs");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();

        root = new FrameLayout(context);
        root.setBackgroundColor(MD3_SURFACE);
        fragmentView = root;

        contentHost = new FrameLayout(context);
        root.addView(contentHost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadingView = new ProgressBar(context);
        loadingView.getIndeterminateDrawable().setColorFilter(new PorterDuffColorFilter(MD3_PRIMARY, PorterDuff.Mode.SRC_IN));
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setTextColor(MD3_ON_ERROR);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorView.setBackground(md3Surface(MD3_ERROR_CONTAINER, 24));
        errorView.setVisibility(View.GONE);
        root.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        buildTopBar(context);
        buildSearchBar(context);
        buildBottomIsland(context);

        root.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        root.setClipChildren(false);
        // Document draws edge-to-edge under the floating bottom island.
        contentHost.setClipToPadding(false);
        contentHost.setClipChildren(false);
        contentHost.setPadding(0, dp(64), 0, 0);

        loadDocument();
        return fragmentView;
    }

    private void buildTopBar(Context context) {
        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        topBar.setBackground(md3Surface(ColorUtils.setAlphaComponent(MD3_SURFACE_CONTAINER, 0xF2), 0));
        topBar.setElevation(dp(2));

        ImageView back = iconButton(context, R.drawable.ic_ab_back, "Назад", v -> finishFragment());
        topBar.addView(back, LayoutHelper.createLinear(44, 44));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(4), 0, dp(4), 0);

        titleView = new TextView(context);
        titleView.setText(fileName);
        titleView.setTextColor(MD3_ON_SURFACE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setText(buildSubtitle());
        subtitleView.setTextColor(MD3_ON_SURFACE_VARIANT);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitleView.setSingleLine(true);
        titles.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        topBar.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        searchBtn = iconButton(context, R.drawable.msg_search, "Поиск", v -> toggleSearch(true));
        topBar.addView(searchBtn, LayoutHelper.createLinear(44, 44));

        invertBtn = iconButton(context, R.drawable.msg_theme, "Инверсия", v -> toggleInvert());
        invertBtn.setVisibility(kind == MglaDocumentHelper.KIND_PDF ? View.VISIBLE : View.GONE);
        topBar.addView(invertBtn, LayoutHelper.createLinear(44, 44));

        ImageView more = iconButton(context, R.drawable.ic_ab_other, "Ещё", v -> showMoreMenu());
        topBar.addView(more, LayoutHelper.createLinear(44, 44));

        root.addView(topBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }

    private void buildSearchBar(Context context) {
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        searchBar.setBackground(md3Surface(MD3_SURFACE_CONTAINER, 0));
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        searchBar.setTranslationY(-dp(12));

        ImageView close = iconButton(context, R.drawable.ic_ab_back, "Закрыть поиск", v -> toggleSearch(false));
        searchBar.addView(close, LayoutHelper.createLinear(40, 40));

        FrameLayout fieldWrap = new FrameLayout(context);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(dp(28));
        fieldBg.setColor(MD3_SURFACE_CONTAINER_HIGH);
        fieldWrap.setBackground(fieldBg);
        fieldWrap.setPadding(dp(14), 0, dp(14), 0);

        searchField = new EditTextBoldCursor(context);
        searchField.setHint("Найти в документе");
        searchField.setHintTextColor(MD3_ON_SURFACE_VARIANT);
        searchField.setTextColor(MD3_ON_SURFACE);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setCursorColor(MD3_PRIMARY);
        searchField.setCursorSize(dp(20));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                runSearch(s != null ? s.toString() : "", true);
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                jumpSearch(1);
                return true;
            }
            return false;
        });
        fieldWrap.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44));

        searchBar.addView(fieldWrap, LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));

        searchCountView = new TextView(context);
        searchCountView.setTextColor(MD3_ON_SURFACE_VARIANT);
        searchCountView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        searchCountView.setMinWidth(dp(48));
        searchCountView.setGravity(Gravity.CENTER);
        searchBar.addView(searchCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        ImageView prev = iconButton(context, R.drawable.msg_arrowright, "Предыдущее", v -> jumpSearch(-1));
        prev.setRotation(180f);
        searchBar.addView(prev, LayoutHelper.createLinear(40, 40));
        ImageView next = iconButton(context, R.drawable.msg_arrowright, "Следующее", v -> jumpSearch(1));
        searchBar.addView(next, LayoutHelper.createLinear(40, 40));

        root.addView(searchBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
    }

    private void buildBottomIsland(Context context) {
        bottomIsland = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        // Semi-opaque pill so pages remain visible around/under the island.
        bg.setColor(ColorUtils.setAlphaComponent(MD3_SURFACE_CONTAINER, 0xE6));
        bottomIsland.setBackground(bg);
        bottomIsland.setElevation(dp(8));
        bottomIsland.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottomIsland.setClipToOutline(false);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView zoomOut = tonalChip(context, "−", v -> adjustZoom(-0.15f));
        row.addView(zoomOut, LayoutHelper.createLinear(44, 44));

        pageLabel = new TextView(context);
        pageLabel.setTextColor(MD3_ON_SURFACE);
        pageLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pageLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setSingleLine(true);
        row.addView(pageLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView zoomIn = tonalChip(context, "+", v -> adjustZoom(0.15f));
        row.addView(zoomIn, LayoutHelper.createLinear(44, 44));

        ImageView share = iconButton(context, R.drawable.msg_share, "Поделиться", v -> shareFile());
        row.addView(share, LayoutHelper.createLinear(44, 44));

        ImageView openExt = iconButton(context, R.drawable.msg_openin, "Открыть вовне", v -> openExternal());
        row.addView(openExt, LayoutHelper.createLinear(44, 44));

        bottomIsland.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(bottomIsland, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 20, 0, 20, 18));
        updatePageLabel();
    }

    private ImageView iconButton(Context context, int resId, String desc, View.OnClickListener click) {
        ImageView iv = new ImageView(context);
        Drawable d = context.getResources().getDrawable(resId).mutate();
        d.setColorFilter(new PorterDuffColorFilter(MD3_ON_SURFACE, PorterDuff.Mode.SRC_IN));
        iv.setImageDrawable(d);
        iv.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(MD3_ON_SURFACE, 0.10f), 1));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setContentDescription(desc);
        iv.setOnClickListener(click);
        MglaUi.attachPressSpring(iv);
        return iv;
    }

    private ImageView tonalChip(Context context, String symbol, View.OnClickListener click) {
        ImageView iv = new ImageView(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                t.setTextAlign(Paint.Align.CENTER);
                t.setTextSize(dp(20));
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            }
            @Override
            protected void onDraw(Canvas canvas) {
                p.setColor(MD3_PRIMARY_CONTAINER);
                float r = Math.min(getWidth(), getHeight()) / 2f;
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r - dp(2), p);
                t.setColor(MD3_ON_PRIMARY_CONTAINER);
                canvas.drawText(symbol, getWidth() / 2f, getHeight() / 2f - (t.descent() + t.ascent()) / 2f, t);
            }
        };
        iv.setOnClickListener(click);
        MglaUi.attachPressSpring(iv);
        return iv;
    }

    private GradientDrawable md3Surface(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        if (radiusDp > 0) {
            g.setCornerRadius(dp(radiusDp));
        }
        return g;
    }

    private String buildSubtitle() {
        String kindLabel = MglaDocumentHelper.kindLabel(kind);
        String size = AndroidUtilities.formatFileSize(file.length());
        return kindLabel + "  ·  " + size;
    }

    private void loadDocument() {
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        if (kind == MglaDocumentHelper.KIND_PDF) {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    openPdfRenderer();
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingView.setVisibility(View.GONE);
                        showPdfUi();
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> showError("Не удалось открыть PDF"));
                }
            });
        } else {
            final boolean dark = !isLightTheme();
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    MglaOfficeHtml.Result result = MglaOfficeHtml.convert(file, fileName, mimeType, cacheDir, dark);
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingView.setVisibility(View.GONE);
                        plainText = result.plainText;
                        htmlContent = result.html;
                        mediaDir = result.mediaDir;
                        legacyUnsupported = result.legacyUnsupported;
                        showWebUi();
                        if (legacyUnsupported) {
                            pageLabel.setText("Внешнее приложение");
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> showError("Не удалось прочитать файл"));
                }
            });
        }
    }

    private boolean isLightTheme() {
        return ColorUtils.calculateLuminance(MD3_SURFACE) > 0.5f;
    }

    private void showError(String msg) {
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(msg);
        pageLabel.setText("Ошибка");
    }

    private void openPdfRenderer() throws Exception {
        closePdf();
        pdfFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pdfFd);
        pdfPageCount = pdfRenderer.getPageCount();
    }

    private void closePdf() {
        synchronized (pdfLock) {
            if (pdfRenderer != null) {
                try {
                    pdfRenderer.close();
                } catch (Exception ignored) {
                }
                pdfRenderer = null;
            }
            if (pdfFd != null) {
                try {
                    pdfFd.close();
                } catch (Exception ignored) {
                }
                pdfFd = null;
            }
        }
    }

    private void clearPdfCache() {
        for (Bitmap b : pdfCache.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        pdfCache.clear();
    }

    private void showPdfUi() {
        Context context = getContext();
        if (context == null) return;

        pdfZoomHost = new ZoomFrame(context);
        pdfZoomHost.setOnScaleChanged(scale -> {
            pdfScale = scale;
            updatePageLabel();
        });
        pdfZoomHost.setOnSingleTap(() -> setChromeVisible(!chromeVisible));

        pdfList = new RecyclerListView(context);
        pdfList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        pdfList.setClipToPadding(false);
        // Extra bottom inset so the last page can scroll past the floating island.
        pdfList.setPadding(dp(12), dp(8), dp(12), dp(96));
        pdfAdapter = new PdfAdapter();
        pdfList.setAdapter(pdfAdapter);
        pdfList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updatePageLabel();
            }
        });
        pdfZoomHost.addView(pdfList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentHost.addView(pdfZoomHost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updatePageLabel();
        subtitleView.setText(buildSubtitle() + "  ·  " + pdfPageCount + " стр.");
    }

    private void showWebUi() {
        Context context = getContext();
        if (context == null) return;
        webView = new WebView(context);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setDomStorageEnabled(false);
        s.setBlockNetworkLoads(true);
        if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(isLightTheme() ? WebSettings.FORCE_DARK_OFF : WebSettings.FORCE_DARK_ON);
        }
        webView.setBackgroundColor(MD3_SURFACE);
        webView.addJavascriptInterface(new SearchBridge(), "MglaSearch");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // block navigation
            }
        });
        webView.setOnTouchListener(new View.OnTouchListener() {
            private long downAt;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downAt = System.currentTimeMillis();
                } else if (event.getAction() == MotionEvent.ACTION_UP && System.currentTimeMillis() - downAt < 220) {
                    if (event.getPointerCount() == 1) {
                        setChromeVisible(!chromeVisible);
                    }
                }
                return false;
            }
        });
        String base = "file://" + (mediaDir != null ? mediaDir.getAbsolutePath() : cacheDir.getAbsolutePath()) + "/";
        webView.loadDataWithBaseURL(base, htmlContent, "text/html", "utf-8", null);
        contentHost.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pageLabel.setText(MglaDocumentHelper.kindLabel(kind));
        if (legacyUnsupported) {
            searchBtn.setVisibility(View.GONE);
        }
    }

    private void updatePageLabel() {
        if (pageLabel == null) return;
        if (kind == MglaDocumentHelper.KIND_PDF && pdfList != null) {
            LinearLayoutManager lm = (LinearLayoutManager) pdfList.getLayoutManager();
            int first = lm != null ? lm.findFirstVisibleItemPosition() : 0;
            if (first < 0) first = 0;
            int pct = Math.round(pdfScale * 100f);
            pageLabel.setText(String.format(Locale.getDefault(), "%d / %d   ·   %d%%", first + 1, Math.max(pdfPageCount, 1), pct));
        } else if (kind != MglaDocumentHelper.KIND_PDF) {
            pageLabel.setText(MglaDocumentHelper.kindLabel(kind) + "   ·   " + Math.round(pdfScale * 100f) + "%");
        }
    }

    private void adjustZoom(float delta) {
        if (kind == MglaDocumentHelper.KIND_PDF && pdfZoomHost != null) {
            pdfZoomHost.setScale(Utilities.clamp(pdfZoomHost.getScale() + delta, 3.5f, 0.6f), true);
        } else if (webView != null) {
            pdfScale = Utilities.clamp(pdfScale + delta, 2.5f, 0.7f);
            webView.setInitialScale(Math.round(pdfScale * 100f));
            // Prefer CSS zoom via JS for smoother feel
            webView.evaluateJavascript("document.body.style.zoom='" + pdfScale + "';", null);
            updatePageLabel();
        }
    }

    private void toggleInvert() {
        if (kind != MglaDocumentHelper.KIND_PDF) return;
        pdfInvert = !pdfInvert;
        invertBtn.setColorFilter(new PorterDuffColorFilter(pdfInvert ? MD3_PRIMARY : MD3_ON_SURFACE, PorterDuff.Mode.SRC_IN));
        if (pdfAdapter != null) {
            pdfAdapter.notifyDataSetChanged();
        }
    }

    private void toggleSearch(boolean open) {
        searchOpen = open;
        if (open) {
            searchBar.setVisibility(View.VISIBLE);
            searchBar.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            topBar.animate().alpha(0f).translationY(-dp(8)).setDuration(180).withEndAction(() -> topBar.setVisibility(View.INVISIBLE)).start();
            searchField.requestFocus();
            AndroidUtilities.showKeyboard(searchField);
            contentHost.setPadding(0, dp(64), 0, 0);
        } else {
            AndroidUtilities.hideKeyboard(searchField);
            searchBar.animate().alpha(0f).translationY(-dp(12)).setDuration(180).withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            clearSearchHighlights();
            searchField.setText("");
            searchCountView.setText("");
        }
    }

    private void runSearch(String query, boolean resetIndex) {
        if (TextUtils.isEmpty(query)) {
            clearSearchHighlights();
            searchCountView.setText("");
            searchTotal = 0;
            searchIndex = 0;
            return;
        }
        if (kind == MglaDocumentHelper.KIND_PDF) {
            // PDF text layer unavailable via PdfRenderer — search plain filename/page jump by number
            if (TextUtils.isDigitsOnly(query.trim())) {
                try {
                    int page = Integer.parseInt(query.trim());
                    if (page >= 1 && page <= pdfPageCount && pdfList != null) {
                        pdfList.smoothScrollToPosition(page - 1);
                        searchCountView.setText(page + "/" + pdfPageCount);
                    }
                } catch (Exception ignored) {
                }
            } else {
                searchCountView.setText("№ стр.");
            }
            return;
        }
        if (webView == null) return;
        String q = query.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){" +
                "var q='" + q + "';" +
                "if(window.__mglaClear)window.__mglaClear();" +
                "if(!q){MglaSearch.onCount(0,0);return;}" +
                "var marks=[];" +
                "function walk(n){if(n.nodeType===3){var t=n.nodeValue;var i=t.toLowerCase().indexOf(q.toLowerCase());" +
                "if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+q.length);" +
                "var m=document.createElement('mark');m.className='search-hit';r.surroundContents(m);marks.push(m);}}" +
                "else if(n.nodeType===1&&n.childNodes&&n.tagName!=='SCRIPT'&&n.tagName!=='STYLE'){" +
                "for(var c=0;c<n.childNodes.length;c++)walk(n.childNodes[c]);}}" +
                "walk(document.body);" +
                "window.__mglaMarks=marks;window.__mglaIdx=0;" +
                "window.__mglaClear=function(){for(var i=0;i<marks.length;i++){var p=marks[i].parentNode;if(!p)continue;" +
                "while(marks[i].firstChild)p.insertBefore(marks[i].firstChild,marks[i]);p.removeChild(marks[i]);p.normalize();}" +
                "marks=[];};" +
                "window.__mglaJump=function(d){if(!marks.length){MglaSearch.onCount(0,0);return;}" +
                "marks[window.__mglaIdx].classList.remove('current');" +
                "window.__mglaIdx=(window.__mglaIdx+d+marks.length)%marks.length;" +
                "marks[window.__mglaIdx].classList.add('current');" +
                "marks[window.__mglaIdx].scrollIntoView({behavior:'smooth',block:'center'});" +
                "MglaSearch.onCount(window.__mglaIdx+1,marks.length);};" +
                "if(marks.length){marks[0].classList.add('current');marks[0].scrollIntoView({behavior:'smooth',block:'center'});}" +
                "MglaSearch.onCount(marks.length?1:0,marks.length);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void jumpSearch(int dir) {
        if (webView != null) {
            webView.evaluateJavascript("if(window.__mglaJump)window.__mglaJump(" + dir + ");", null);
        }
    }

    private void clearSearchHighlights() {
        if (webView != null) {
            webView.evaluateJavascript("if(window.__mglaClear)window.__mglaClear();", null);
        }
    }

    private class SearchBridge {
        @JavascriptInterface
        public void onCount(final int index, final int total) {
            AndroidUtilities.runOnUIThread(() -> {
                searchIndex = index;
                searchTotal = total;
                if (total <= 0) {
                    searchCountView.setText("0");
                } else {
                    searchCountView.setText(index + "/" + total);
                }
            });
        }
    }

    private void setChromeVisible(boolean visible) {
        if (chromeVisible == visible) return;
        chromeVisible = visible;
        if (chromeAnimator != null) {
            chromeAnimator.cancel();
        }
        float from = visible ? 0f : 1f;
        float to = visible ? 1f : 0f;
        chromeAnimator = ValueAnimator.ofFloat(from, to);
        chromeAnimator.setDuration(240);
        chromeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        chromeAnimator.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            View bar = searchOpen ? searchBar : topBar;
            if (bar != null && bar.getVisibility() != View.GONE) {
                bar.setAlpha(v);
                bar.setTranslationY((1f - v) * -dp(12));
            }
            if (bottomIsland != null) {
                bottomIsland.setAlpha(v);
                bottomIsland.setTranslationY((1f - v) * dp(24));
            }
            // Keep bottom at 0 so pages continue under the island; only top bar reserves space.
            int topPad = (int) (dp(64) * v);
            contentHost.setPadding(0, Math.max(topPad, dp(8)), 0, 0);
        });
        chromeAnimator.start();
    }

    private void showMoreMenu() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        CharSequence[] items;
        if (kind == MglaDocumentHelper.KIND_PDF) {
            items = new CharSequence[]{
                    "Поделиться",
                    "Открыть в другом приложении",
                    "Печать",
                    "Копировать путь",
                    "Перейти к странице…"
            };
        } else {
            items = new CharSequence[]{
                    "Поделиться",
                    "Открыть в другом приложении",
                    "Копировать текст",
                    "Копировать путь"
            };
        }
        b.setItems(items, (d, which) -> {
            if (kind == MglaDocumentHelper.KIND_PDF) {
                switch (which) {
                    case 0: shareFile(); break;
                    case 1: openExternal(); break;
                    case 2: printPdf(); break;
                    case 3: copyPath(); break;
                    case 4: promptGotoPage(); break;
                }
            } else {
                switch (which) {
                    case 0: shareFile(); break;
                    case 1: openExternal(); break;
                    case 2: copyAllText(); break;
                    case 3: copyPath(); break;
                }
            }
        });
        showDialog(b.create());
    }

    private void promptGotoPage() {
        if (getParentActivity() == null || pdfPageCount <= 0) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Перейти к странице");
        final EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setHint("1 – " + pdfPageCount);
        input.setInputType(EditorInfo.TYPE_CLASS_NUMBER);
        input.setTextColor(MD3_ON_SURFACE);
        input.setHintTextColor(MD3_ON_SURFACE_VARIANT);
        input.setBackground(md3Surface(MD3_SURFACE_CONTAINER_HIGH, 12));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        FrameLayout wrap = new FrameLayout(getParentActivity());
        wrap.setPadding(dp(20), dp(8), dp(20), dp(4));
        wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        b.setView(wrap);
        b.setPositiveButton("OK", (d, w) -> {
            try {
                int page = Integer.parseInt(input.getText().toString().trim());
                if (page >= 1 && page <= pdfPageCount && pdfList != null) {
                    pdfList.smoothScrollToPosition(page - 1);
                }
            } catch (Exception ignored) {
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(input), 100);
    }

    private void shareFile() {
        try {
            Activity a = getParentActivity();
            if (a == null) return;
            Intent intent = new Intent(Intent.ACTION_SEND);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(a, ApplicationLoader.getApplicationId() + ".provider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            intent.setType(MglaDocumentHelper.guessMime(fileName, mimeType));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, fileName);
            a.startActivity(Intent.createChooser(intent, "Поделиться"));
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin("Не удалось поделиться").show();
        }
    }

    private void openExternal() {
        try {
            AndroidUtilities.openForView(file, fileName, mimeType, getParentActivity(), getResourceProvider(), false, false);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin("Нет приложения для этого файла").show();
        }
    }

    private void copyPath() {
        AndroidUtilities.addToClipboard(file.getAbsolutePath());
        BulletinFactory.of(this).createCopyBulletin("Путь скопирован").show();
    }

    private void copyAllText() {
        if (TextUtils.isEmpty(plainText)) {
            BulletinFactory.of(this).createErrorBulletin("Нет текста для копирования").show();
            return;
        }
        AndroidUtilities.addToClipboard(plainText);
        BulletinFactory.of(this).createCopyBulletin("Текст скопирован").show();
    }

    private void printPdf() {
        Activity a = getParentActivity();
        if (a == null || kind != MglaDocumentHelper.KIND_PDF) return;
        try {
            PrintManager pm = (PrintManager) a.getSystemService(Context.PRINT_SERVICE);
            if (pm == null) return;
            String job = fileName != null ? fileName : "document.pdf";
            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override
                public void onWrite(android.print.PageRange[] pages, android.os.ParcelFileDescriptor destination,
                                    android.os.CancellationSignal cancellationSignal,
                                    WriteResultCallback callback) {
                    try (FileInputStream in = new FileInputStream(file);
                         FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (cancellationSignal.isCanceled()) {
                                callback.onWriteCancelled();
                                return;
                            }
                            out.write(buf, 0, n);
                        }
                        callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                    } catch (Exception e) {
                        callback.onWriteFailed(e.getMessage());
                    }
                }

                @Override
                public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                     android.os.CancellationSignal cancellationSignal,
                                     LayoutResultCallback callback, Bundle extras) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onLayoutCancelled();
                        return;
                    }
                    android.print.PrintDocumentInfo info = new android.print.PrintDocumentInfo.Builder(job)
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(pdfPageCount > 0 ? pdfPageCount : android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build();
                    callback.onLayoutFinished(info, true);
                }
            };
            pm.print(job, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(a, "Печать недоступна", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searchOpen) {
            if (invoked) {
                toggleSearch(false);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private Bitmap renderPdfPage(int index, int targetWidth) {
        Bitmap cached = pdfCache.get(index);
        if (cached != null && !cached.isRecycled() && cached.getWidth() >= targetWidth - 8) {
            return cached;
        }
        synchronized (pdfLock) {
            if (pdfRenderer == null) return null;
            PdfRenderer.Page page = null;
            try {
                page = pdfRenderer.openPage(index);
                float ratio = page.getHeight() / (float) page.getWidth();
                int w = Math.max(targetWidth, 1);
                int h = Math.max(1, Math.round(w * ratio));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                Bitmap old = pdfCache.put(index, bmp);
                if (old != null && old != bmp && !old.isRecycled()) {
                    old.recycle();
                }
                // Keep cache bounded
                if (pdfCache.size() > 12) {
                    Integer victim = null;
                    for (Integer k : pdfCache.keySet()) {
                        if (Math.abs(k - index) > 4) {
                            victim = k;
                            break;
                        }
                    }
                    if (victim != null) {
                        Bitmap b = pdfCache.remove(victim);
                        if (b != null && !b.isRecycled()) b.recycle();
                    }
                }
                return bmp;
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private class PdfAdapter extends RecyclerListView.SelectionAdapter {
        private final ColorMatrixColorFilter invertFilter = new ColorMatrixColorFilter(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return pdfPageCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PageView page = new PageView(parent.getContext());
            page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            PageView page = (PageView) holder.itemView;
            page.bind(position);
            if (pdfInvert) {
                page.image.setColorFilter(invertFilter);
            } else {
                page.image.clearColorFilter();
            }
        }
    }

    private class PageView extends FrameLayout {
        final ImageView image;
        final TextView label;
        final ProgressBar progress;
        int pageIndex = -1;

        PageView(Context context) {
            super(context);
            setPadding(0, dp(6), 0, dp(6));

            FrameLayout card = new FrameLayout(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(16));
            card.setBackground(bg);
            card.setElevation(dp(3));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            image = new ImageView(context);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            progress = new ProgressBar(context);
            card.addView(progress, LayoutHelper.createFrame(36, 36, Gravity.CENTER));

            label = new TextView(context);
            label.setTextColor(MD3_ON_SURFACE_VARIANT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            label.setGravity(Gravity.CENTER);
            addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

            setOnClickListener(v -> setChromeVisible(!chromeVisible));
        }

        void bind(int index) {
            pageIndex = index;
            label.setText("стр. " + (index + 1));
            image.setImageBitmap(null);
            progress.setVisibility(View.VISIBLE);
            final int width = Math.max(AndroidUtilities.displaySize.x - dp(48), dp(280));
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap bmp = renderPdfPage(index, width);
                AndroidUtilities.runOnUIThread(() -> {
                    if (pageIndex != index) return;
                    progress.setVisibility(View.GONE);
                    if (bmp != null) {
                        image.setImageBitmap(bmp);
                    }
                });
            });
        }
    }

    /** Pinch-zoom + pan host for the PDF list. */
    private static class ZoomFrame extends FrameLayout {
        private float scale = 1f;
        private float tx, ty;
        private float focusX, focusY;
        private final ScaleGestureDetector detector;
        private OnScale onScale;
        private Runnable onSingleTap;
        private float lastX, lastY;
        private boolean panning;
        private long downAt;
        private float downX, downY;

        interface OnScale {
            void onScale(float scale);
        }

        ZoomFrame(Context context) {
            super(context);
            setClipChildren(false);
            detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    focusX = d.getFocusX();
                    focusY = d.getFocusY();
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float next = Utilities.clamp(scale * d.getScaleFactor(), 3.5f, 0.6f);
                    float factor = next / scale;
                    tx = focusX - factor * (focusX - tx);
                    ty = focusY - factor * (focusY - ty);
                    scale = next;
                    applyTransform();
                    if (onScale != null) onScale.onScale(scale);
                    return true;
                }
            });
        }

        void setOnScaleChanged(OnScale l) {
            onScale = l;
        }

        void setOnSingleTap(Runnable r) {
            onSingleTap = r;
        }

        float getScale() {
            return scale;
        }

        void setScale(float s, boolean animate) {
            scale = s;
            if (scale <= 1.01f) {
                tx = ty = 0;
            }
            applyTransform();
            if (onScale != null) onScale.onScale(scale);
        }

        private void applyTransform() {
            View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child == null) return;
            child.setPivotX(0);
            child.setPivotY(0);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setTranslationX(tx);
            child.setTranslationY(ty);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return scale > 1.01f || ev.getPointerCount() > 1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downAt = System.currentTimeMillis();
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
                    panning = scale > 1.01f;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (panning && event.getPointerCount() == 1 && !detector.isInProgress()) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        tx += dx;
                        ty += dy;
                        applyTransform();
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (System.currentTimeMillis() - downAt < 220
                            && Math.hypot(event.getX() - downX, event.getY() - downY) < dp(8)
                            && onSingleTap != null) {
                        onSingleTap.run();
                    }
                    break;
            }
            return true;
        }
    }
}
