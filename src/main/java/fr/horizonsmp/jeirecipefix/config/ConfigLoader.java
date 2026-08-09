package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.ConfigurationSection;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginConfig fromSection(ConfigurationSection section) {
        PluginConfig d = PluginConfig.defaults();
        if (section == null) {
            return d;
        }
        return new PluginConfig(
                section.getBoolean("enabled", d.enabled()),
                section.getBoolean("sync-on-join", d.syncOnJoin()),
                section.getBoolean("sync-on-datapack-reload", d.syncOnDatapackReload()),
                section.getBoolean("recipe-update-trigger", d.recipeUpdateTrigger()),
                RecipeBookMode.parse(section.getString("recipe-book-sync"), d.recipeBookSync()),
                CrossVersionMode.parse(section.getString("cross-version-sync"), d.crossVersionSync()),
                section.getBoolean("cross-version-unknown-is-native", d.crossVersionUnknownIsNative()),
                section.getBoolean("explain-jei-warning", d.explainJeiWarning()),
                section.getBoolean("debug", d.debug()));
    }
}
