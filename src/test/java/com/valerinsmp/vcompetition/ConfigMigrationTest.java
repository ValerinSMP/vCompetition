package com.valerinsmp.vcompetition;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulates upgrading an existing install across a real server restart: the plugin's data
 * folder (and its config.yml) persists, but the jar was replaced by a newer version that
 * added keys (e.g. fancy-npcs.skin-refresh.top-npcs-special) the old file doesn't have yet.
 * onEnable must backfill those keys without touching values the admin already customized.
 */
class ConfigMigrationTest {

    @Test
    void missingConfigKeysAreAddedWithoutOverwritingExisting() throws Exception {
        ServerMock server = MockBukkit.mock();
        try {
            VCompetitionPlugin plugin = MockBukkit.load(VCompetitionPlugin.class);
            File configFile = new File(plugin.getDataFolder(), "config.yml");

            // Overwrite with a stripped-down "old" config: only the customized value,
            // missing everything else (including keys added in later versions) — then
            // re-run onEnable against the SAME data folder, exactly as a real restart would.
            Files.writeString(configFile.toPath(), "challenge:\n  duration-minutes: 45\n");
            plugin.onEnable();

            assertEquals(45, plugin.getConfig().getLong("challenge.duration-minutes"),
                    "an existing customized value must survive the merge");
            assertTrue(plugin.getConfig().contains("fancy-npcs.skin-refresh.top-npcs-special.top1"),
                    "a key added in a later version must be filled in from the bundled default");
        } finally {
            MockBukkit.unmock();
        }
    }
}
