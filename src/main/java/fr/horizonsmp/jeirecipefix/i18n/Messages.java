package fr.horizonsmp.jeirecipefix.i18n;

import fr.horizonsmp.jeirecipefix.config.ConfigUpdater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class Messages {

    private static final String FILE_NAME = "messages.yml";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private YamlConfiguration messages;
    private String prefix;

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        addKeysFromNewerVersions(file);
        this.messages = YamlConfiguration.loadConfiguration(file);
        // Belt and braces: if the file could not be written, unknown keys still render from the jar.
        withBundled(reader -> messages.setDefaults(YamlConfiguration.loadConfiguration(reader)));
        this.prefix = messages.getString("prefix", "");
    }

    private void addKeysFromNewerVersions(File file) {
        withBundled(reader -> {
            List<String> added = ConfigUpdater.addMissingKeys(reader, file);
            if (!added.isEmpty()) {
                plugin.getLogger().info("Added " + added.size() + " new message(s) to " + FILE_NAME
                        + ": " + String.join(", ", added));
            }
        });
    }

    private void withBundled(BundledReader action) {
        InputStream bundled = plugin.getResource(FILE_NAME);
        if (bundled == null) {
            return;
        }
        try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            action.accept(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read " + FILE_NAME + " from the plugin jar: " + e.getMessage());
        }
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        // getString(key) consults the defaults; getString(key, fallback) would not, and would send
        // the raw key to the player.
        String raw = messages.getString(key);
        if (raw == null) {
            plugin.getLogger().warning("Missing message '" + key + "' in " + FILE_NAME + " and in the plugin jar.");
            return;
        }
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("%" + e.getKey() + "%", e.getValue());
        }
        Component component = LEGACY.deserialize(prefix + raw);
        target.sendMessage(component);
    }

    public void send(CommandSender target, String key) {
        send(target, key, Map.of());
    }

    @FunctionalInterface
    private interface BundledReader {
        void accept(Reader reader) throws IOException;
    }
}
