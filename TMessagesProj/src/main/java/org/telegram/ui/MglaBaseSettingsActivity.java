package org.telegram.ui;

import android.view.View;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Shared base for Mgla settings screens.
 * Keep this light — do not attach hardware layers here: they are destroyed when the
 * fragment view is detached, and rebuilding them on the way back (especially into the
 * Mgla settings hub) was causing ~1s freezes before back animations started.
 *
 * Bottom system panel matches Telegram settings (e.g. Privacy): edge-to-edge so
 * ActionBarLayout draws the navigation-bar gradient.
 */
public abstract class MglaBaseSettingsActivity extends BaseFragment {

    public MglaBaseSettingsActivity() {
        super();
    }

    public MglaBaseSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        final View root = getFragmentView();
        if (root != null) {
            MglaUi.attachPressSpringRecursive(root);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen) {
            final View root = getFragmentView();
            if (root != null) {
                MglaUi.attachPressSpringRecursive(root);
            }
        }
    }
}
