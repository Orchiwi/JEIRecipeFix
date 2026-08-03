package fr.horizonsmp.jeirecipefix;

import fr.horizonsmp.jeirecipefix.command.JEIRecipeFixCommand;
import fr.horizonsmp.jeirecipefix.config.ConfigLoader;
import fr.horizonsmp.jeirecipefix.config.ConfigUpdater;
import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.i18n.Messages;
import fr.horizonsmp.jeirecipefix.listener.PlayerConnectionListener;
import fr.horizonsmp.jeirecipefix.listener.ResourceReloadListener;
import fr.horizonsmp.jeirecipefix.nms.NmsRecipeBridge;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import fr.horizonsmp.jeirecipefix.sync.ClientBrand;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class JEIRecipeFix extends JavaPlugin {

    private final AtomicReference<PluginConfig> config = new AtomicReference<>(PluginConfig.defaults());
    private RecipeSyncService syncService;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addSettingsFromNewerVersions();
        reloadConfig();
        reloadPluginConfig();

        RecipeBridge bridge = new NmsRecipeBridge(this);
        if (!bridge.isAvailable()) {
            getLogger().warning("Unsupported server internals; recipe sync disabled. JEIRecipeFix will stay dormant.");
        }
        this.messages = new Messages(this);
        this.syncService = new RecipeSyncService(bridge, config::get, this, getLogger(),
                player -> messages.send(player, "jei-warning-notice"));

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, syncService, config::get), this);
        getServer().getPluginManager().registerEvents(
                new ResourceReloadListener(syncService, config::get), this);

        JEIRecipeFixCommand command = new JEIRecipeFixCommand(this, syncService, messages);
        getCommand("jeirecipefix").setExecutor(command);
        getCommand("jeirecipefix").setTabCompleter(command);

        getLogger().info("JEIRecipeFix enabled (recipe sync "
                + (bridge.isAvailable() ? "active" : "dormant") + ").");

        // Encode the payloads now rather than on the first join. They are what actually breaks when
        // a Minecraft update moves something, and building them here turns that into a startup
        // error in the log instead of a silent no-op that only players notice.
        warmPayloads();
    }

    /** Brings an older config.yml up to date with the settings this version knows about. */
    private void addSettingsFromNewerVersions() {
        InputStream bundled = getResource("config.yml");
        if (bundled == null) {
            return;
        }
        try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            List<String> added = ConfigUpdater.addMissingKeys(reader, new File(getDataFolder(), "config.yml"));
            if (!added.isEmpty()) {
                getLogger().info("Added " + added.size() + " new setting(s) to config.yml: "
                        + String.join(", ", added));
            }
        } catch (IOException e) {
            getLogger().warning("Could not bring config.yml up to date: " + e.getMessage());
        }
    }

    private void warmPayloads() {
        if (syncService == null || !syncService.available()) {
            return;
        }
        try {
            syncService.payloadFor(ClientBrand.FABRIC);
            syncService.payloadFor(ClientBrand.NEOFORGE);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE,
                    "Could not encode this server's recipes; clients will not receive any.", e);
        }
    }

    public void reloadAll() {
        reloadConfig();
        reloadPluginConfig();
        if (messages != null) messages.reload();
        if (syncService != null) {
            syncService.invalidate();
            warmPayloads();
        }
    }

    public RecipeSyncService syncService() {
        return syncService;
    }

    public PluginConfig pluginConfig() {
        return config.get();
    }

    private void reloadPluginConfig() {
        config.set(ConfigLoader.fromSection(getConfig()));
    }
}
