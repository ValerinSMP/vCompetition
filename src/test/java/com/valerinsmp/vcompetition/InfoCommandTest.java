package com.valerinsmp.vcompetition;

import com.valerinsmp.vcompetition.command.VCompetitionCommand;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers "/torneos info" (overview, one line per challenge type) and
 * "/torneos info <tipo>" (comma-separated breakdown built from live config, using
 * translatable components so item/mob names render in the client's own language).
 */
class InfoCommandTest {

    private ServerMock server;
    private VCompetitionPlugin plugin;
    private VCompetitionCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VCompetitionPlugin.class);
        command = new VCompetitionCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void infoOverviewSendsAHeaderAndOneLinePerChallengeType() {
        PlayerMock player = server.addPlayer();

        command.onCommand(player, null, "torneos", new String[]{"info"});

        int lineCount = 0;
        while (player.nextComponentMessage() != null) {
            lineCount++;
        }
        // 1 header line + 6 challenge types (MINING, WOODCUTTING, FISHING, SLAYER, FARMING, OTONO)
        assertTrue(lineCount >= 7, "expected a header line plus one line per challenge type, got " + lineCount);
    }

    @Test
    void infoDetailForMiningListsMultipleTranslatedOres() {
        PlayerMock player = server.addPlayer();

        command.onCommand(player, null, "torneos", new String[]{"info", "mining"});

        Component last = null;
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            last = message;
        }

        assertNotNull(last, "the mining breakdown must be sent");
        // Component.join interleaves a separator between each item, so N ores -> 2N-1 children.
        assertTrue(last.children().size() > 5,
                "config.yml lists many ore materials for MINING — the breakdown must list more than one");
    }

    @Test
    void infoDetailWithInvalidTypeDoesNotThrow() {
        PlayerMock player = server.addPlayer();
        command.onCommand(player, null, "torneos", new String[]{"info", "not-a-real-type"});
        assertNotNull(player.nextComponentMessage(), "an invalid type must still get a (error) response");
    }
}
