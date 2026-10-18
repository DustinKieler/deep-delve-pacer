package com.deepdelvepacer;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DeepDelvePacerTest {

    @Mock
    @Bind
    private Client client;

    @Mock
    @Bind
    private ClientThread clientThread;

    @Mock
    @Bind
    private InfoBoxManager infoBoxManager;

    @Mock
    @Bind
    private ItemManager itemManager;

    @Mock
    @Bind
    private OverlayManager overlayManager;

    @Mock
    @Bind
    private DeepDelvePacerConfig deepDelvePacerConfig;

    @Inject
    private DeepDelvePacerPlugin plugin;

    /**
     * The message box shown when the player jumps down to the next delve level.
     */
    private final ChatMessage descendMessage = new ChatMessage(null, ChatMessageType.MESBOX, "", DeepDelvePacerPlugin.DESCEND_MESSAGE, "", 0);

    @Before
    public void setup() {
        Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);
        WorldView mockWorldMap = mock(WorldView.class);
        when(client.getTopLevelWorldView()).thenReturn(mockWorldMap);
        when(mockWorldMap.isInstance()).thenReturn(true);
        when(mockWorldMap.getMapRegions()).thenReturn(new int[]{DeepDelvePacerPlugin.DEEP_DELVE_REGION_ID});
    }

    @Test
    public void testDelveLevel1IsDelvingButLobbyIsNot() {
        when(client.getTopLevelWorldView().getMapRegions()).thenReturn(new int[] { DeepDelvePacerPlugin.FIRST_DELVE_REGION_ID });
        plugin.onGameStateChanged(createLoggedInEvent()); // Delve 1 is loaded when the lobby is, so rely on instance flag
        assertTrue(plugin.isDelving());

        when(client.getTopLevelWorldView().isInstance()).thenReturn(false);
        plugin.onGameStateChanged(createLoggedInEvent());
        assertFalse(plugin.isDelving());
    }

    @Test
    public void testNormalDelvesDoNotCreateInfoBox() {
        plugin.onChatMessage(createChatMessage(5));
        assertNull(plugin.delvePaceCounter);
        plugin.onChatMessage(createChatMessage(8)); // Last normal delve
        assertNull(plugin.delvePaceCounter);
    }

    @Test
    public void testFirstDeepDelve() {
        simulateTicks(10);
        plugin.onChatMessage(createChatMessage(8));
        simulateTicks(65);
        plugin.onChatMessage(createChatMessage(9));

        assertNotNull(plugin.delvePaceCounter);

        String[] times = plugin.delvePaceCounter.getTooltip().split("<br>");
        assertEquals("Previous: 00:39.00", times[0]);
        assertEquals("Average: 00:39.00", times[1]);
        assertEquals("Best: 00:39.00", times[2]);

        // (36000 - 65) / 65 => floor(552.84) => 552 + current level (9) => 561
        assertEquals(561, plugin.delvePaceCounter.getCount());
    }

    @Test
    public void testMultipleDeepDelves() {
        plugin.onChatMessage(createChatMessage(8));
        simulateTicks(152);
        plugin.onChatMessage(createChatMessage(9));
        simulateTicks(105);
        plugin.onChatMessage(createChatMessage(10));
        simulateTicks(160);
        plugin.onChatMessage(createChatMessage(11));

        assertNotNull(plugin.delvePaceCounter);

        String[] times = plugin.delvePaceCounter.getTooltip().split("<br>");
        assertEquals("Previous: 01:36.00", times[0]); // 160 * 0.6 = 96s
        assertEquals("Average: 01:23.40", times[1]); // (152 + 105 + 160) / 3 * 0.6 = 83.4s
        assertEquals("Best: 01:03.00", times[2]); // 105 * 0.6 = 63s

        // (36000 - (152 + 105 + 160)) / ((152 + 105 + 160) / 3) => floor(255.99) + current level (11) => 266
        assertEquals(266, plugin.delvePaceCounter.getCount());
    }

    @Test
    public void testLeavingAreaClearsInfoBox() {
        plugin.onChatMessage(createChatMessage(8));
        plugin.onChatMessage(createChatMessage(9));
        assertNotNull(plugin.delvePaceCounter);

        when(client.getTopLevelWorldView().getMapRegions()).thenReturn(new int[] { WorldView.TOPLEVEL });
        plugin.onGameStateChanged(createLoggedInEvent());
        assertNull(plugin.delvePaceCounter);
    }

    @Test
    public void testLeavingAreaClearsTextOverlay() {
        when(deepDelvePacerConfig.showCurrentDelveLevel()).thenReturn(true);
        plugin.delveLevelTextOverlay.updateDelve(1);
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

        plugin.onGameStateChanged(createLoggedInEvent()); // Entering Doom's lair loads a new map
        assertNotNull(plugin.delveLevelTextOverlay.render(graphics));

        when(client.getTopLevelWorldView().getMapRegions()).thenReturn(new int[] { WorldView.TOPLEVEL });
        plugin.onGameStateChanged(createLoggedInEvent()); // e.g., teleporting out loads a new map
        assertNull(plugin.delveLevelTextOverlay.render(graphics));
    }

    @Test
    public void testGameStateChangeResetsCounters() {
        plugin.onChatMessage(createChatMessage(8));
        simulateTicks(50);

        GameStateChanged loggingIn = new GameStateChanged();
        loggingIn.setGameState(GameState.LOGGING_IN);
        GameStateChanged loggedIn = new GameStateChanged();
        loggedIn.setGameState(GameState.LOGGED_IN);

        // ready -> true
        plugin.onGameStateChanged(loggingIn);
        // Initialize counters
        plugin.onGameStateChanged(loggedIn);

        // Simulate first deep delve completion
        simulateTicks(50);
        plugin.onChatMessage(createChatMessage(9)); // Occurs on tick 51

        // Verify the time does not include the ticks before reset
        assertNotNull(plugin.delvePaceCounter);
        String[] times = plugin.delvePaceCounter.getTooltip().split("<br>");
        assertEquals("Previous: 00:30.60", times[0]); // 51 ticks * 0.6 = 30.6s
        assertEquals("Average: 00:30.60", times[1]);
        assertEquals("Best: 00:30.60", times[2]);
        // floor(((36000 - 51) / 51)) + current level (9) => 713
        assertEquals(713, plugin.delvePaceCounter.getCount());
    }

    @Test
    public void testDelveLevelTextOverlayShowsNextLevelOnDescend() {
        when(client.getVarpValue(VarPlayerID.DOM_CURRENT_LEVEL_TEMP)).thenReturn(2); // 0-based so the player is leaving delve 3
        // Mimic the game sending the message twice on descent
        plugin.onChatMessage(descendMessage);
        plugin.onChatMessage(descendMessage);
        assertEquals("Delve level: 4", plugin.delveLevelTextOverlay.text);
    }

    @Test
    public void testDelveLevelTextOverlayUpdatesOnVarpChange() {
        when(client.getVarpValue(VarPlayerID.DOM_CURRENT_LEVEL_TEMP)).thenReturn(1); // 0-based so this is delve 2
        VarbitChanged event = new VarbitChanged();
        event.setVarpId(VarPlayerID.DOM_CURRENT_LEVEL_TEMP);
        plugin.onVarbitChanged(event);
        assertEquals("Delve level: 2", plugin.delveLevelTextOverlay.text);
    }

    @Test
    public void testLeavingAreaResetsDelveLevel() {
        when(client.getVarpValue(VarPlayerID.DOM_CURRENT_LEVEL_TEMP)).thenReturn(2);
        plugin.onChatMessage(descendMessage);
        assertEquals("Delve level: 4", plugin.delveLevelTextOverlay.text);

        when(client.getTopLevelWorldView().getMapRegions()).thenReturn(new int[] { WorldView.TOPLEVEL });
        plugin.onGameStateChanged(createLoggedInEvent()); // Leaving Doom's lair resets the delve level, so the next trip starts at 1
        assertEquals("Delve level: 1", plugin.delveLevelTextOverlay.text);
    }

    /**
     * Helper function to create the completion message for a delve level.
     * @param level The delve level to create the message for.
     * @return The created {@link ChatMessage}.
     */
    private ChatMessage createChatMessage(int level) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(ChatMessageType.GAMEMESSAGE);
        String levelText = level <= 8 ? String.valueOf(level) : "8+ (" + level + ")";
        chatMessage.setMessage("Delve level: " + levelText + " duration:");
        return chatMessage;
    }

    /**
     * Simulates a number of game tick events.
     * @param numberOfTicks The number of ticks to simulate.
     */
    private void simulateTicks(int numberOfTicks) {
        for (int i = 0; i < numberOfTicks; i++) {
            plugin.onGameTick(null);
        }
    }

    /**
     * Helper function to create an event that is switching the game state to LOGGED_IN.
     * E.g., can be used to imitate when new map regions load.
     * @return The created {@link GameStateChanged}.
     */
    private GameStateChanged createLoggedInEvent() {
        GameStateChanged gameStateChanged = new GameStateChanged();
        gameStateChanged.setGameState(GameState.LOGGED_IN);
        return gameStateChanged;
    }
}
