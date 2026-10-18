package com.deepdelvepacer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Configuration for the {@link DeepDelvePacerPlugin}.
 */
@ConfigGroup("deepdelvepacer")
public interface DeepDelvePacerConfig extends Config {
    /**
     * @return Whether to display the {@link DelveLevelTextOverlay}.
     */
    @ConfigItem(
            keyName = "showCurrentDelveLevel",
            name = "Current delve level overlay",
            description = "Shows a separate text overlay that displays your current delve level."
    )
    default boolean showCurrentDelveLevel() {
        return false;
    }
}
