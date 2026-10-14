package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Заглушка экрана Mgla Premium — контент добавится отдельно.
 */
public class MglaPremiumActivity extends MglaBaseSettingsActivity {

    public MglaPremiumActivity() {
        this(null);
    }

    public MglaPremiumActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Mgla Premium");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        TextView empty = new TextView(context);
        empty.setText("Скоро");
        empty.setTextSize(16);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return fragmentView;
    }
}
