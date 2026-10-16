package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MglaYandexMusicApi;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Material Design 3 BottomSheet for Yandex ID authorization.
 * Supports both automatic in-app WebView OAuth and manual token entry.
 */
public class MglaYandexAuthSheet extends BottomSheet {

    private static final String CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    private static final String AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + CLIENT_ID;

    private final Runnable onSuccessCallback;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout manualLayout;
    private FrameLayout webViewContainer;
    private boolean isManualMode = false;

    public MglaYandexAuthSheet(Context context, Runnable onSuccess) {
        this(context, false, onSuccess);
    }

    public MglaYandexAuthSheet(Context context, boolean initialManual, Runnable onSuccess) {
        super(context, true);
        this.onSuccessCallback = onSuccess;
        this.isManualMode = initialManual;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        initView(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initView(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));

        // Top drag handle
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setCornerRadius(dp(3));
        handleBg.setColor(Theme.getColor(Theme.key_sheet_scrollUp));
        handle.setBackground(handleBg);
        root.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 8));

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(4), dp(16), dp(8));

        TextView title = new TextView(context);
        title.setText("Вход в Яндекс Музыку");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView switchBtn = new TextView(context);
        switchBtn.setText("Ввести токен");
        switchBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        switchBtn.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        switchBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        switchBtn.setOnClickListener(v -> toggleManualMode(switchBtn));
        header.addView(switchBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        ImageView closeBtn = new ImageView(context);
        closeBtn.setImageResource(R.drawable.ic_close_white);
        closeBtn.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        closeBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        closeBtn.setOnClickListener(v -> dismiss());
        header.addView(closeBtn, LayoutHelper.createLinear(32, 32, 8, 0, 0, 0));

        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Progress bar
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        root.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));

        // Container for WebView and Manual Form
        FrameLayout contentContainer = new FrameLayout(context);

        // 1. WebView layout
        webViewContainer = new FrameLayout(context);
        webView = new WebView(context);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSaveFormData(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                checkRedirectUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                checkRedirectUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    return checkRedirectUrl(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return checkRedirectUrl(url);
            }
        });

        webViewContainer.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentContainer.addView(webViewContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 2. Manual token layout
        manualLayout = new LinearLayout(context);
        manualLayout.setOrientation(LinearLayout.VERTICAL);
        manualLayout.setPadding(dp(24), dp(20), dp(24), dp(24));
        manualLayout.setVisibility(View.GONE);

        TextView manualDesc = new TextView(context);
        manualDesc.setText("Если вход через форму недоступен или вы хотите использовать готовый токен, вставьте OAuth-токен Яндекс Музыки ниже:");
        manualDesc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        manualDesc.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        manualLayout.addView(manualDesc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        EditText tokenInput = new EditText(context);
        tokenInput.setHint("y0_AgAAAA... или AQAAAA...");
        tokenInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        tokenInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        tokenInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(12));
        inputBg.setColor(Theme.getColor(Theme.key_dialogBackgroundGray));
        tokenInput.setBackground(inputBg);
        tokenInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        tokenInput.setSingleLine(true);
        manualLayout.addView(tokenInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        TextView saveBtn = new TextView(context);
        saveBtn.setText("Войти по токену");
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        saveBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(16));
        btnBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        saveBtn.setBackground(btnBg);
        saveBtn.setPadding(0, dp(14), 0, dp(14));
        saveBtn.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (TextUtils.isEmpty(token)) {
                Toast.makeText(context, "Введите токен", Toast.LENGTH_SHORT).show();
                return;
            }
            onTokenExtracted(token);
        });
        manualLayout.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        contentContainer.addView(manualLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int height = Math.min(dp(540), AndroidUtilities.displaySize.y - dp(100));
        root.addView(contentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, height));

        setCustomView(root);
        if (isManualMode) {
            webViewContainer.setVisibility(View.GONE);
            manualLayout.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            switchBtn.setText("Веб-форма");
        } else {
            webView.loadUrl(AUTH_URL);
        }
    }

    private void toggleManualMode(TextView switchBtn) {
        isManualMode = !isManualMode;
        if (isManualMode) {
            webViewContainer.setVisibility(View.GONE);
            manualLayout.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            switchBtn.setText("Веб-форма");
        } else {
            manualLayout.setVisibility(View.GONE);
            webViewContainer.setVisibility(View.VISIBLE);
            switchBtn.setText("Ввести токен");
        }
    }

    private boolean checkRedirectUrl(String url) {
        if (url == null) return false;
        if (url.contains("access_token=")) {
            try {
                int start = url.indexOf("access_token=") + "access_token=".length();
                int end = url.indexOf('&', start);
                String token = end != -1 ? url.substring(start, end) : url.substring(start);
                if (!TextUtils.isEmpty(token)) {
                    onTokenExtracted(token);
                    return true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    private void onTokenExtracted(String token) {
        MglaYandexMusicStore.getInstance().saveToken(token);
        MglaYandexMusicApi.fetchAccountStatus(new MglaYandexMusicApi.Callback<org.telegram.messenger.MglaYandexMusicModel.UserInfo>() {
            @Override
            public void onSuccess(org.telegram.messenger.MglaYandexMusicModel.UserInfo result) {
                Toast.makeText(getContext(), "Успешный вход в Яндекс ID!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception error) {
            }
        });

        dismiss();
        if (onSuccessCallback != null) {
            onSuccessCallback.run();
        }
    }

    @Override
    public void dismiss() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.dismiss();
    }
}
