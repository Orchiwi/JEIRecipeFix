package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import fr.horizonsmp.jeirecipefix.nms.RecipePayload;
import fr.horizonsmp.jeirecipefix.util.Lazy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class RecipeSyncService {

    /** Fabric API's clientbound recipe-sync channel; a client advertises it once it can receive recipes. */
    public static final String FABRIC_RECIPE_SYNC_CHANNEL = "fabric:recipe_sync";
    /**
     * Any channel in JEI's namespace proves a JEI client. Matching the namespace rather than one
     * channel id keeps this working when JEI adds or renames one of its packets.
     */
    public static final String JEI_CHANNEL_NAMESPACE = "jei:";

    /** Warn above this: the vanilla clientbound payload cap is 1 MiB and the plugin cannot split. */
    private static final int PAYLOAD_WARN_BYTES = 800 * 1024;

    private final RecipeBridge bridge;
    private final Supplier<PluginConfig> config;
    private final Plugin plugin;
    private final Logger logger;
    private final Lazy<RecipePayload> fabricPayload;
    private final Lazy<RecipePayload> neoForgePayload;

    private final Set<UUID> synced = ConcurrentHashMap.newKeySet();
    private final Set<ClientBrand> loggedBrands = EnumSet.noneOf(ClientBrand.class);

    public RecipeSyncService(RecipeBridge bridge, Supplier<PluginConfig> config, Plugin plugin, Logger logger) {
        this.bridge = bridge;
        this.config = config;
        this.plugin = plugin;
        this.logger = logger;
        this.fabricPayload = new Lazy<>(() -> describe(ClientBrand.FABRIC, bridge.buildFabricPayload()));
        this.neoForgePayload = new Lazy<>(() -> describe(ClientBrand.NEOFORGE, bridge.buildNeoForgePayload()));
    }

    public boolean shouldSync(ClientBrand brand) {
        return config.get().enabled() && bridge.isAvailable() && brand.isSupported();
    }

    public RecipePayload payloadFor(ClientBrand brand) {
        return switch (brand) {
            case FABRIC -> fabricPayload.get();
            case NEOFORGE -> neoForgePayload.get();
            default -> null;
        };
    }

    /**
     * Whether the vanilla recipe-update packet should follow the Fabric payload for this client.
     *
     * <p>Two things have to be true. The client must advertise Fabric API's recipe-sync channel,
     * which is only registered from MC 1.21.10 on — below that the payload is dropped and the extra
     * packet would only make other mods reload for nothing. And it must advertise JEI's channel:
     * JEI is what needs the nudge, whereas REI reloads on the packet without ever reading the
     * payload, so sending it there is pure cost.
     */
    public boolean shouldTriggerRecipeUpdate(Player player) {
        if (!config.get().recipeUpdateTrigger() || !bridge.canTriggerRecipeUpdate()) {
            return false;
        }
        Set<String> channels = player.getListeningPluginChannels();
        return channels.contains(FABRIC_RECIPE_SYNC_CHANNEL) && hasJei(channels);
    }

    private static boolean hasJei(Set<String> channels) {
        return channels.stream().anyMatch(channel -> channel.startsWith(JEI_CHANNEL_NAMESPACE));
    }

    /** Sends recipes to one player if applicable. Returns true if a payload was sent. */
    public boolean syncTo(Player player) {
        if (!player.isOnline()) {
            return false;
        }
        ClientBrand brand = ClientBrand.fromBrand(player.getClientBrandName());
        if (!shouldSync(brand)) {
            debug("Skipping recipe sync for " + player.getName() + " (brand=" + brand + ")");
            return false;
        }
        RecipePayload payload = payloadFor(brand);
        if (payload == null) {
            return false;
        }
        if (payload.isEmpty()) {
            // An empty payload is worse than none: the viewer stores an empty recipe set and reports
            // that the server sent unusable recipes.
            logger.warning("Not sending recipes to " + player.getName()
                    + ": nothing left to encode (server recipe count: " + bridge.recipeCount() + ").");
            return false;
        }
        boolean sent;
        boolean trigger = false;
        switch (brand) {
            case FABRIC -> {
                trigger = shouldTriggerRecipeUpdate(player);
                sent = bridge.sendFabric(player, payload.bytes(), trigger);
            }
            case NEOFORGE -> sent = bridge.sendNeoForge(player, payload.bytes());
            default -> {
                return false;
            }
        }
        if (sent) {
            logSend(player, brand, payload, trigger);
        }
        return sent;
    }

    /**
     * Join-path sync: sends at most once per connection, so the join fallback and the channel path
     * cannot send the payload twice. A failed attempt does not count, so the fallback still retries.
     */
    public boolean syncOnceTo(Player player) {
        UUID id = player.getUniqueId();
        if (synced.contains(id)) {
            return false;
        }
        boolean sent = syncTo(player);
        if (sent) {
            synced.add(id);
        }
        return sent;
    }

    public void forget(Player player) {
        synced.remove(player.getUniqueId());
    }

    public void resyncAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> syncTo(player), null);
        }
    }

    public void invalidate() {
        fabricPayload.invalidate();
        neoForgePayload.invalidate();
        synchronized (loggedBrands) {
            loggedBrands.clear();
        }
    }

    public boolean available() {
        return bridge.isAvailable();
    }

    public boolean canTriggerRecipeUpdate() {
        return bridge.canTriggerRecipeUpdate();
    }

    public int recipeCount() {
        return bridge.recipeCount();
    }

    public int failureCount() {
        return bridge.failureCount();
    }

    /** Logs the shape of a freshly built payload once, so a broken sync is visible with the default config. */
    private RecipePayload describe(ClientBrand brand, RecipePayload payload) {
        String label = brand == ClientBrand.FABRIC ? "Fabric" : "NeoForge";
        logger.info("Encoded " + payload.recipes() + " recipes in " + payload.groups()
                + " groups (" + payload.size() + " bytes) for " + label + " clients.");
        if (!payload.skippedGroups().isEmpty()) {
            logger.warning("Left " + payload.skippedRecipes() + " recipe(s) out of the " + label
                    + " payload because their serializer is not vanilla and would make the client "
                    + "discard everything: " + String.join(", ", payload.skippedGroups()));
        }
        if (payload.size() > PAYLOAD_WARN_BYTES) {
            logger.warning("The " + label + " recipe payload is " + payload.size()
                    + " bytes, close to the 1 MiB protocol limit; clients may reject it.");
        }
        return payload;
    }

    private void logSend(Player player, ClientBrand brand, RecipePayload payload, boolean triggered) {
        String message = "Sent " + payload.recipes() + " recipes to " + player.getName()
                + " (" + brand + ", " + payload.size() + " bytes"
                + (brand == ClientBrand.FABRIC ? ", recipe-update trigger: " + (triggered ? "yes" : "no") : "")
                + ").";
        boolean first;
        synchronized (loggedBrands) {
            first = loggedBrands.add(brand);
        }
        if (first) {
            logger.info(message);
            if (brand == ClientBrand.FABRIC && !triggered) {
                logger.info("No recipe-update trigger was sent: the client did not report both "
                        + FABRIC_RECIPE_SYNC_CHANNEL + " and a " + JEI_CHANNEL_NAMESPACE
                        + " channel. JEI only re-reads recipes when asked, so it may keep showing "
                        + "its own until the player rejoins. Channels reported by this client: "
                        + player.getListeningPluginChannels());
            }
        } else {
            debug(message);
        }
    }

    private void debug(String message) {
        if (config.get().debug()) {
            logger.info(message);
        }
    }
}
