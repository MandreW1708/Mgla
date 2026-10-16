package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Stub page for future main-tabs (Tasks / AI). */
public class MglaPlaceholderTabActivity extends BaseFragment {

    private final String title;
    private final String subtitle;

    public MglaPlaceholderTabActivity(String title, String subtitle) {
        this(null, title, subtitle);
    }

    public MglaPlaceholderTabActivity(Bundle args, String title, String subtitle) {
        super(args);
        this.title = title;
        this.subtitle = subtitle;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(0);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
        actionBar.setOccupyStatusBar(true);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView hint = new TextView(context);
        hint.setText(subtitle);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(32), 0, dp(32), AndroidUtilities.navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
        root.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        return fragmentView;
    }
}
