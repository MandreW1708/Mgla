package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.concurrent.atomic.AtomicReference;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.SoftBreakAddsNewLinePlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * Full Markdown rendering for Mgla AI chat bubbles
 * (headings, lists, code, quotes, tables, strikethrough, HTML, autolinks).
 */
public final class MglaAiMarkdown {

    private static final AtomicReference<Markwon> CACHED = new AtomicReference<>();

    private MglaAiMarkdown() {
    }

    @NonNull
    public static Markwon get(@NonNull Context context) {
        Markwon cached = CACHED.get();
        if (cached != null) {
            return cached;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Markwon created = Markwon.builder(app)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(app))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        int link = Theme.getColor(Theme.key_chat_messageLinkIn);
                        int codeBg = ColorUtils.setAlphaComponent(
                                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0x18);
                        builder
                                .linkColor(link)
                                .isLinkUnderlined(true)
                                .codeTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                                .codeBackgroundColor(codeBg)
                                .codeBlockTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                                .codeBlockBackgroundColor(codeBg)
                                .blockMargin(AndroidUtilities.dp(8))
                                .blockQuoteWidth(AndroidUtilities.dp(3))
                                .blockQuoteColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
                                .headingBreakHeight(0)
                                .thematicBreakColor(ColorUtils.setAlphaComponent(
                                        Theme.getColor(Theme.key_divider), 0xAA))
                                .thematicBreakHeight(AndroidUtilities.dp(1));
                    }
                })
                .build();
        if (CACHED.compareAndSet(null, created)) {
            return created;
        }
        return CACHED.get();
    }

    /** Apply Markdown into a bubble TextView. Falls back to plain text on parse errors. */
    public static void setMarkdown(@Nullable TextView view, @Nullable String markdown, boolean enableLinks) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(markdown)) {
            view.setText("");
            return;
        }
        try {
            Markwon markwon = get(view.getContext());
            markwon.setMarkdown(view, markdown);
            if (enableLinks) {
                view.setMovementMethod(LinkMovementMethod.getInstance());
                view.setLinksClickable(true);
            }
        } catch (Throwable ignore) {
            view.setText(markdown);
        }
    }

    /** Invalidate cached Markwon after theme change so colors refresh. */
    public static void clearCache() {
        CACHED.set(null);
    }
}
