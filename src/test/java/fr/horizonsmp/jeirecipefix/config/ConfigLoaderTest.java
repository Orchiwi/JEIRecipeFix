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
                explain-jei-warning: false
                debug: true
                """);

        PluginConfig config = ConfigLoader.fromSection(yaml);

        assertFalse(config.enabled());
        assertFalse(config.syncOnJoin());
        assertTrue(config.syncOnDatapackReload());
        assertFalse(config.recipeUpdateTrigger());
        assertFalse(config.explainJeiWarning());
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
        assertTrue(fromEmpty.explainJeiWarning());
        // An existing config.yml predating cross-version support must keep the safe behaviour, not
        // fall back to sending the payload to everyone.
        assertEquals(CrossVersionMode.SAFE, fromEmpty.crossVersionSync());
        assertTrue(fromEmpty.crossVersionUnknownIsNative());
    }

    @Test
    void readsCrossVersionSettings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                cross-version-sync: force
                cross-version-unknown-is-native: false
                """);

        PluginConfig config = ConfigLoader.fromSection(yaml);

        assertEquals(CrossVersionMode.FORCE, config.crossVersionSync());
        assertFalse(config.crossVersionUnknownIsNative());
    }

    @Test
    void keepsTheConfiguredModeWhenTheValueIsNotOneWeKnow() {
        assertEquals(CrossVersionMode.SAFE, CrossVersionMode.parse("nonsense", CrossVersionMode.SAFE));
        assertEquals(CrossVersionMode.OFF, CrossVersionMode.parse("  OFF  ", CrossVersionMode.SAFE));
        assertEquals(CrossVersionMode.SAFE, CrossVersionMode.parse(null, CrossVersionMode.SAFE));
    }
}
