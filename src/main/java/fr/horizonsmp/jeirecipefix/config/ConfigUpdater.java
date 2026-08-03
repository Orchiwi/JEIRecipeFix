package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds keys a newer version introduced to a file the server already has, leaving every value the
 * operator set alone. Without this, upgrading only ever gets you the old file, and a new key either
 * silently falls back to a default or, worse, shows up as its own placeholder.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * Copies keys present in {@code bundled} but missing from {@code file}, with their comments,
     * and rewrites the file only if something was actually added.
     *
     * @return the keys that were added, in file order; empty when the file was already up to date
     */
    public static List<String> addMissingKeys(Reader bundled, File file) throws IOException {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(bundled);
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            // Parent sections appear as their own keys; setting a child creates them anyway.
            if (defaults.get(key) instanceof ConfigurationSection || current.contains(key)) {
                continue;
            }
            current.set(key, defaults.get(key));
            current.setComments(key, defaults.getComments(key));
            added.add(key);
        }
        if (!added.isEmpty()) {
            // Keep long values on one line; the 80-column default folds them across lines, which is
            // valid YAML but makes a hand-edited file look mangled.
            current.options().width(1000);
            current.save(file);
        }
        return added;
    }
}
