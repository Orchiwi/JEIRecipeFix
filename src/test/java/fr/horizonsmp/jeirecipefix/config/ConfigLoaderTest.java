package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void readsValuesFromSection() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: false
                sync-on-join: false
                sync-on-datapack-reload: true
                recipe-update-trigger: false
                debug: true
                """);

        PluginConfig config = ConfigLoader.fromSection(yaml);

        assertFalse(config.enabled());
        assertFalse(config.syncOnJoin());
        assertTrue(config.syncOnDatapackReload());
        assertFalse(config.recipeUpdateTrigger());
        assertTrue(config.debug());
    }

    @Test
    void fallsBackToDefaultsForMissingKeysAndNullSection() {
        PluginConfig fromNull = ConfigLoader.fromSection(null);
        assertEquals(PluginConfig.defaults(), fromNull);

        YamlConfiguration empty = new YamlConfiguration();
        PluginConfig fromEmpty = ConfigLoader.fromSection(empty);
        assertTrue(fromEmpty.enabled());
        assertTrue(fromEmpty.syncOnJoin());
        assertTrue(fromEmpty.recipeUpdateTrigger());
    }
}
