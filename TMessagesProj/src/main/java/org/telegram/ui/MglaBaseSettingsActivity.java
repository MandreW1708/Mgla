package org.telegram.ui;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Shared base for Mgla settings screens.
 * Keep this light — do not attach hardware layers here: they are destroyed when the
 * fragment view is detached, and rebuilding them on the way back (especially into the
 * Mgla settings hub) was causing ~1s freezes before back animations started.
 */
public abstract class MglaBaseSettingsActivity extends BaseFragment {

    public MglaBaseSettingsActivity() {
        super();
    }

    public MglaBaseSettingsActivity(android.os.Bundle args) {
        super(args);
    }
}
