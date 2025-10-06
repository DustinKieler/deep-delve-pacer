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
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DeepDelvePacerTest {

    @Mock
    @Bind
    private Client client;

    @Mock
    @Bind
    private InfoBoxManager infoBoxManager;

    @Mock
    @Bind
    private ItemManager itemManager;

    @Inject
    private DeepDelvePacerPlugin plugin;

    @Before
    public void setup() {
       Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);
       plugin = Mockito.spy(plugin);
       WorldView mockWorldMap = mock(WorldView.class);
       when(client.getTopLevelWorldView()).thenReturn(mockWorldMap);
       when(mockWorldMap.getMapRegions()).thenReturn(new int[]{DeepDelvePacerPlugin.DEEP_DELVE_REGION_ID});
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
        plugin.onChatMessage(createChatMessage(8));
        simulateTicks(65);
        plugin.onChatMessage(createChatMessage(9));

        assertNotNull(plugin.delvePaceCounter);

        String[] times = plugin.delvePaceCounter.getTooltip().split("<br>");
        assertEquals("Average: 00:39.00", times[0]);
        assertEquals("Best: 00:39.00", times[1]);

        // (36000 - 65) / 65 => floor(552.84) => 552 + current level (9) => 560
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
        assertEquals("Average: 01:23.40", times[0]); // (152 + 105 + 160) / 3 * 0.6 = 83.4s
        assertEquals("Best: 01:03.00", times[1]); // 105 * 0.6 = 63s

        // (36000 - (152 + 105 + 160)) / ((152 + 105 + 160) / 3) => floor(255.99) + current level (11) => 266
        assertEquals(266, plugin.delvePaceCounter.getCount());
    }

    @Test
    public void testLeavingAreaClearsInfoBox() {
        plugin.onChatMessage(createChatMessage(8));
        plugin.onChatMessage(createChatMessage(9));
        assertNotNull(plugin.delvePaceCounter);

        when(client.getTopLevelWorldView().getMapRegions()).thenReturn(new int[] { -1 });
        plugin.onGameTick(null);
        assertNull(plugin.delvePaceCounter);
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
        assertEquals("Average: 00:30.60", times[0]); // 51 ticks * 0.6 = 30.6s
        assertEquals("Best: 00:30.60", times[1]);
        // floor(((36000 - 51) / 51)) + current level (9) => 713
        assertEquals(713, plugin.delvePaceCounter.getCount());
    }

    /**
     * Helper function to create a chat message for a delve level.
     * @param level The level to create the message for (8 or higher)
     * @return The created {@link ChatMessage}.
     */
    private ChatMessage createChatMessage(int level) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setType(ChatMessageType.GAMEMESSAGE);
        if (level == 8) {
            chatMessage.setMessage("Delve level: 8 duration:");
        } else {
            chatMessage.setMessage("Delve level: 8+ (" + level + ") duration:");
        }
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
}
