package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    private static final String BUNDLED = """
            # Master switch.
            enabled: true
            sync-on-join: true
            # Added in a later version.
            recipe-update-trigger: true
            debug: false
            """;

    @Test
    void addsMissingKeysAndKeepsWhatTheOperatorSet(@TempDir Path dir) throws Exception {
        File file = dir.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), """
                # Master switch.
                enabled: false
                sync-on-join: true
                debug: true
                """, StandardCharsets.UTF_8);

        List<String> added = ConfigUpdater.addMissingKeys(new StringReader(BUNDLED), file);

        assertEquals(List.of("recipe-update-trigger"), added);

        YamlConfiguration updated = YamlConfiguration.loadConfiguration(file);
        assertTrue(updated.getBoolean("recipe-update-trigger"));
        assertFalse(updated.getBoolean("enabled"), "an operator's value must survive the update");
        assertTrue(updated.getBoolean("debug"));
        assertTrue(Files.readString(file.toPath()).contains("# Master switch."),
                "existing comments must survive the rewrite");
    }

    @Test
    void leavesAnUpToDateFileAlone(@TempDir Path dir) throws Exception {
        File file = dir.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), BUNDLED, StandardCharsets.UTF_8);
        long before = file.lastModified();

        List<String> added = ConfigUpdater.addMissingKeys(new StringReader(BUNDLED), file);

        assertEquals(List.of(), added);
        assertEquals(before, file.lastModified(), "an up-to-date file must not be rewritten");
    }

    @Test
    void createsNothingButStillReportsWhenTheFileIsEmpty(@TempDir Path dir) throws Exception {
        File file = dir.resolve("messages.yml").toFile();
        Files.writeString(file.toPath(), "", StandardCharsets.UTF_8);

        List<String> added = ConfigUpdater.addMissingKeys(new StringReader(BUNDLED), file);

        assertEquals(List.of("enabled", "sync-on-join", "recipe-update-trigger", "debug"), added);
        assertTrue(YamlConfiguration.loadConfiguration(file).getBoolean("enabled"));
    }
}
