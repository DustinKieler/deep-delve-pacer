package com.deepdelvepacer;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.Counter;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plugin intended for players doing extended deep delves (level 9+) at the Doom of Mokhaiotl.
 * (Yes, completing level 8 counts as a "deep delve" on the hiscores, but Doom has reduced HP for 9+.)
 *
 * This plugin will estimate the deep delve level that can be achieved before being 6-hour logged and display it in an info box.
 * The info box will also have a tooltip displaying the time required to complete the previous deep delve, the current average completion
 * time, and the best time achieved so far in the run. This info box will not display until the completion of delve level 9.
 *
 * Players can also toggle a text overlay that displays the current delve level.
 */
@Slf4j
@PluginDescriptor(
	name = "Deep Delve Pacer"
)
public class DeepDelvePacerPlugin extends Plugin
{
	private static final int MAX_LOGIN_TICKS = 36000;
	private static final int LAST_NORMAL_DELVE = 8;
	private static final int FIRST_DEEP_DELVE = 9;
	static final int FIRST_DELVE_REGION_ID = 5269; // Delve 1
	private static final int NORMAL_DELVE_REGION_ID = 13668; // Delve 2-7
	static final int DEEP_DELVE_REGION_ID = 14180; // Delve 8+
	static final String DESCEND_MESSAGE = "You jump further into the burrow...";
	/**
	 * First match will be on delve level 8's completion: "Delve level: 8 duration:"
	 * Next matches are on deep delve completions: "Delve level: 8+ (x) duration:" where x is the delve level.
	 */
	private static final Pattern LEVEL_END_PATTERN = Pattern.compile("^Delve level: 8(?:\\+ \\((9|\\d{2,})\\))? duration:");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	@VisibleForTesting
	DelveLevelTextOverlay delveLevelTextOverlay;

	/**
	 * Client::getGameTick() is off by 1 because it increments *after* the event is posted, so the first time the count will be 0.
	 * I don't want to work around that in case it's ever changed, so I'll just track my own tick count.
	 */
	private int ticksSinceLogin;
	private boolean ready;

	/**
	 * Caches whether the player is currently delving. Avoids checking map regions on each game tick.
	 */
	@Getter
    private boolean isDelving;

	/**
	 * The game tick (since login) when the player completed delve level 8.
	 */
	private int delve8CompletionTick;

	/**
	 * The game tick (since login) when the player completed the previous deep delve.
	 */
	private int previousDeepDelveCompletionTick;

	/**
	 * A running total of the number of ticks required to complete a deep delve - used for computing the average.
	 */
	private int deepDelveTicksSum;

	/**
	 * Number of ticks for the personal best deep delve of this run.
	 */
	private int bestDeepDelveTicks;

	/**
	 * A {@link Counter} to display the estimated achievable deep delve level and average deep delve time.
	 */
	@VisibleForTesting
	Counter delvePaceCounter;

	/**
	 * Provides the plugin's configuration to the injector.
	 * @param configManager The client's {@link ConfigManager}.
	 * @return The plugin's {@link DeepDelvePacerConfig}.
	 */
	@Provides
	DeepDelvePacerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeepDelvePacerConfig.class);
	}

	/**
	 * Called when the plugin starts up (e.g., enabling it).
	 * @throws Exception Generic exception.
	 */
	@Override
	protected void startUp() throws Exception {
		// Update the current delve overlay now in case the user turned on the plugin in the middle of a delve.
		// (The pacer info box is sort of pointless in that case - it's expected users have the plugin enabled prior to delving
		// if they are doing 6-hour runs, so we don't do that here.)
		overlayManager.add(delveLevelTextOverlay);
		clientThread.invoke(() -> {
			if (client.getGameState() == GameState.LOGGED_IN) { // Make sure map regions have been loaded
				updateIsDelvingFlag();
				delveLevelTextOverlay.updateDelve(getVarpDelveLevel());
			}
		});
	}

	/**
	 * Called when a player variable changes.
	 * @param event Info object for the event.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		// This is used to correct the delve level if the plugin is turned on in the middle of a run.
		if (event.getVarpId() == VarPlayerID.DOM_CURRENT_LEVEL_TEMP) {
			delveLevelTextOverlay.updateDelve(getVarpDelveLevel());
		}
	}

	/**
	 * Called when a chat message is received in the chat box.
	 * @param chatMessage Info object for the message received.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage chatMessage) {
		String message = chatMessage.getMessage();
		if (chatMessage.getType() == ChatMessageType.MESBOX && message.equals(DESCEND_MESSAGE)) {
			// This message is actually sent twice when descending - once right before the new level and one after.
			// However, the varp is still the level being left until Doom spawns, so this is safe to repeat.
			delveLevelTextOverlay.updateDelve(getVarpDelveLevel() + 1);
		} else if (chatMessage.getType() == ChatMessageType.GAMEMESSAGE) {
			Matcher matcher = LEVEL_END_PATTERN.matcher(message);
			if (matcher.find()) {
				String level = matcher.group(1);
				if (level == null) { // Regex matched on delve 8 completion
					delve8CompletionTick = ticksSinceLogin;
				} else { // Regex matched on delve 8+ completion which has the level in parentheses
					recomputeDeepDelvePace(Integer.parseInt(level));
					previousDeepDelveCompletionTick = ticksSinceLogin; // Make sure to update after the above recompute
				}
			}
		}
	}

	/**
	 * Called when the game state changes, e.g., logging in.
	 * @param event Info object for the event.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		switch (state) {
			case LOGGING_IN:
			case HOPPING:
				ready = true;
				break;
			case LOGGED_IN:
				if (ready) {
					ticksSinceLogin = 1; // Start at 1 since onGameTick will be called *after* all packets are processed (after chat messages, etc.)
					resetDeepDelveTracking();
					ready = false;
				}
				updateIsDelvingFlag(); // Check when loaded map regions change (LOADING -> LOGGED_IN)
				break;
		}
	}

	/**
	 * Called every game tick, after all packets have processed.
	 * @param gameTick Info object for the game tick.
	 */
	@Subscribe
	public void onGameTick(GameTick gameTick) {
		ticksSinceLogin++;
	}

	/**
	 * Called when the plugin shuts down (for example, disabling it).
	 * @throws Exception Generic exception.
	 */
	@Override
	protected void shutDown() throws Exception
	{
		removePacingDelveInfoBox();
		overlayManager.remove(delveLevelTextOverlay);
	}

    /**
	 * Updates the flag based on whether the player is currently in Doom's lair (at any delve level).
	 */
	private void updateIsDelvingFlag() {
		WorldView worldView = client.getTopLevelWorldView();
		isDelving = worldView.isInstance() && Arrays.stream(client.getTopLevelWorldView().getMapRegions())
		                                            .anyMatch(region -> region == FIRST_DELVE_REGION_ID || region == NORMAL_DELVE_REGION_ID || region == DEEP_DELVE_REGION_ID);

		if (!isDelving) {
			removePacingDelveInfoBox(); // Will only be present 9+
			resetDeepDelveTracking(); // Resets data stored as a part of completing 8
			delveLevelTextOverlay.updateDelve(1); // Set back to 1 for the next trip
		}
	}

	/**
	 * @return The player's current delve level based on the varp (updated when Doom spawns).
	 */
	private int getVarpDelveLevel() {
		return client.getVarpValue(VarPlayerID.DOM_CURRENT_LEVEL_TEMP) + 1; // 0-based
	}

	/**
	 * Recomputes the player's current deep delve pace and updates the info box.
	 * @param level The level that the player just completed.
	 */
	private void recomputeDeepDelvePace(int level) {
		int levelCompletionTicks;
		if (level == FIRST_DEEP_DELVE) {
			levelCompletionTicks = ticksSinceLogin - delve8CompletionTick;
			bestDeepDelveTicks = levelCompletionTicks;
		} else {
			levelCompletionTicks = ticksSinceLogin - previousDeepDelveCompletionTick;
			if (levelCompletionTicks < bestDeepDelveTicks) {
				bestDeepDelveTicks = levelCompletionTicks;
			}
		}

		// Compute average time to complete a deep delve
		deepDelveTicksSum += levelCompletionTicks;
		double avgDeepDelveTicks = (double) deepDelveTicksSum / (level - LAST_NORMAL_DELVE);

		// Compute the estimated deep delve level achievable before the forced logout, based on the above average.
		int ticksLeftBeforeLogout = MAX_LOGIN_TICKS - ticksSinceLogin;
		int estimatedEndLevel = level + (int) (ticksLeftBeforeLogout / avgDeepDelveTicks);

		updateDelveInfoBox(levelCompletionTicks, avgDeepDelveTicks, estimatedEndLevel);
	}

	/**
	 * Updates the plugin's info box with the current estimated achievable deep delve.
	 * The tooltip will also be updated with the player's previous, average, and best times required to complete a deep delve so far.
	 * @param previousDeepDelveTicks The number of ticks taken to complete the most recent deep delve.
	 * @param avgDeepDelveTicks The average number of ticks taken to complete a deep delve.
	 * @param estimatedEndLevel The estimated deep delve level the player will end on at the current pace.
	 */
	private void updateDelveInfoBox(int previousDeepDelveTicks, double avgDeepDelveTicks, int estimatedEndLevel) {
		String previousTooltip = "Previous: " + convertTicksToTimeDisplay(previousDeepDelveTicks);
		String avgTooltip = "Average: " + convertTicksToTimeDisplay(avgDeepDelveTicks);
		String bestTooltip = "Best: " + convertTicksToTimeDisplay(bestDeepDelveTicks);
		String tooltip = String.join("<br>", previousTooltip, avgTooltip, bestTooltip);

		if (delvePaceCounter != null) {
			delvePaceCounter.setCount(estimatedEndLevel);
			delvePaceCounter.setTooltip(tooltip);
			return;
		}

		delvePaceCounter = new Counter(itemManager.getImage(ItemID.DOM_TELEPORT_ITEM_5), this, estimatedEndLevel);
		delvePaceCounter.setTooltip(tooltip);
		infoBoxManager.addInfoBox(delvePaceCounter);
	}

	/**
	 * Converts a number of ticks (supporting doubles for averages) into a time format for display.
	 * @param ticks The number of ticks to display as a time.
	 * @return The number of ticks displayed in a time format: MM:SS.ss
	 */
	private String convertTicksToTimeDisplay(double ticks) {
		double totalSeconds = ticks * 0.6;
		return String.format("%02d:%05.2f", (int) (totalSeconds / 60), totalSeconds % 60);
	}

	/**
	 * If present, removes the pacing delve info box.
	 */
	private void removePacingDelveInfoBox() {
		if (delvePaceCounter != null) {
			infoBoxManager.removeInfoBox(delvePaceCounter);
			delvePaceCounter = null;
		}
	}

	/**
	 * Resets variables used for tracking deep delve completions.
	 */
	private void resetDeepDelveTracking() {
		delve8CompletionTick = 0;
		previousDeepDelveCompletionTick = 0;
		deepDelveTicksSum = 0;
	}
}
