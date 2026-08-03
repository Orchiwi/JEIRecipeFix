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
                section.getBoolean("debug", d.debug()));
    }
}
