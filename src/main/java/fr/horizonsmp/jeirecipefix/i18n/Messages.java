package fr.horizonsmp.jeirecipefix.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private YamlConfiguration messages;
    private String prefix;

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        // Fall back to the bundled file so a key added in a later version still renders on a server
        // whose messages.yml predates it, instead of sending players the raw key.
        InputStream bundled = plugin.getResource("messages.yml");
        if (bundled != null) {
            try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(reader));
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not read the bundled messages.yml: " + e.getMessage());
            }
        }
        this.prefix = messages.getString("prefix", "");
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        String raw = messages.getString(key, key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("%" + e.getKey() + "%", e.getValue());
        }
        Component component = LEGACY.deserialize(prefix + raw);
        target.sendMessage(component);
    }

    public void send(CommandSender target, String key) {
        send(target, key, Map.of());
    }
}
