package com.deepdelvepacer;

import com.google.common.annotations.VisibleForTesting;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.*;

public class DelveLevelTextOverlay extends OverlayPanel {
    private final DeepDelvePacerPlugin plugin;
    private final DeepDelvePacerConfig config;

    /**
     * The text for the current delve level, e.g., "Delve: 10".
     */
    @VisibleForTesting
    String text;

    /**
     * Creates the overlay.
     * @param plugin The instance of the {@link DeepDelvePacerPlugin} that owns this overlay.
     * @param config The plugin's {@link DeepDelvePacerConfig}.
     */
    @Inject
    DelveLevelTextOverlay(DeepDelvePacerPlugin plugin, DeepDelvePacerConfig config) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        panelComponent.setBackgroundColor(null);
    }

    /**
     * Renders the overlay (every frame).
     * @param graphics The graphics context used to render the overlay.
     * @return The dimensions of the rendered overlay, or null if the overlay should not render.
     */
    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showCurrentDelveLevel() || text == null || !plugin.isDelving()) {
            return null;
        }

        LineComponent textComponent = LineComponent.builder().left(text).build();
        Dimension dimension = new Dimension(graphics.getFontMetrics().stringWidth(text) + 10, 0);
        panelComponent.getChildren().add(textComponent);
        panelComponent.setPreferredSize(dimension);

        return super.render(graphics);
    }

    /**
     * Updates the delve level to display.
     * @param delve The player's current delve level.
     */
    public void updateDelve(int delve) {
        text = "Delve level: " + delve;
    }
}
