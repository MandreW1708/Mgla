package org.telegram.ui.Components.blur3.drawable.color;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

public class BlurredBackgroundColorProviderAccent implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private int backgroundColor, shadowColor, strokeColorTop, strokeColorBottom;

    public BlurredBackgroundColorProviderAccent(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        updateColors();
    }

    public void updateColors() {
        final int accent = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
        final float alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.62f : 0.52f;
        backgroundColor = Theme.multAlpha(accent, alpha);

        if (AndroidUtilities.computePerceivedBrightness(accent) < .721f) {
            strokeColorTop = 0x40FFFFFF;
            strokeColorBottom = 0x28FFFFFF;
            shadowColor = 0;
        } else {
            strokeColorTop = 0x55FFFFFF;
            strokeColorBottom = 0x38FFFFFF;
            shadowColor = 0x28000000;
        }
    }

    @Override
    public int getShadowColor() {
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        return strokeColorBottom;
    }
}
