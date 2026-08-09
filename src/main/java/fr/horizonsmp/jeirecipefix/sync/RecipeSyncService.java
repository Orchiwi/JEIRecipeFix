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
import java.util.function.BiConsumer;
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
    /** REI's namespace. REI reads its recipes from the vanilla recipe book, not from the loader sync. */
    public static final String REI_CHANNEL_NAMESPACE = "roughlyenoughitems:";

    /**
     * The client refuses a custom payload larger than this while decoding it, and a decode failure
     * drops the connection. Fabric API can split oversized payloads over its own channel; a plugin
     * sending raw packets cannot, so past this the recipes simply cannot be delivered.
     */
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    /** Warn here, while there is still room to notice before hitting the hard limit. */
    private static final int PAYLOAD_WARN_BYTES = 800 * 1024;

    /** Sent to a client that received the recipes and runs a recipe viewer. */
    private static final String NOTICE_SYNCED = "jei-warning-notice";
    /** Sent to a client on another Minecraft version whose recipe viewer cannot be served. */
    private static final String NOTICE_CROSS_VERSION = "cross-version-notice";

    /** How much of the sync a given client can safely be given. */
    public enum Delivery {
        /** The client speaks the server's protocol: payload, trigger and recipe book. */
        EVERYTHING,
        /** Another version: only the vanilla recipe book, which ViaVersion knows how to translate. */
        RECIPE_BOOK_ONLY,
        /** Another version, and the operator asked for nothing to be sent to those clients. */
        NOTHING
    }

    private final RecipeBridge bridge;
    private final ProtocolGate gate;
    private final Supplier<PluginConfig> config;
    private final Plugin plugin;
    private final Logger logger;
    private final Lazy<RecipePayload> fabricPayload;
    private final Lazy<RecipePayload> neoForgePayload;

    /** Sends one of the {@code NOTICE_*} messages to a player. */
    private final BiConsumer<Player, String> notice;

    // Tracked per piece, not per player: a client announces its channels in bursts, so the recipe
    // book or the JEI trigger can become applicable a moment after the recipes themselves went out.
    // payloadSettled means the payload question is closed for this connection: sent, or deliberately
    // withheld because the client is on another version. Either way it must not be retried.
    private final Set<UUID> payloadSettled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sentTrigger = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sentRecipeBook = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private final Set<UUID> crossVersionLogged = ConcurrentHashMap.newKeySet();
    private final Set<ClientBrand> loggedBrands = EnumSet.noneOf(ClientBrand.class);

    public RecipeSyncService(RecipeBridge bridge, ProtocolGate gate, Supplier<PluginConfig> config, Plugin plugin,
                             Logger logger, BiConsumer<Player, String> notice) {
        this.bridge = bridge;
        this.gate = gate;
        this.config = config;
        this.plugin = plugin;
        this.logger = logger;
        this.notice = notice;
        this.fabricPayload = new Lazy<>(() -> describe(ClientBrand.FABRIC, bridge.buildFabricPayload()));
        this.neoForgePayload = new Lazy<>(() -> describe(ClientBrand.NEOFORGE, bridge.buildNeoForgePayload()));
    }

    public boolean shouldSync(ClientBrand brand) {
        return config.get().enabled() && bridge.isAvailable() && brand.isSupported();
    }

    /**
     * How much of the sync this client can take, given the Minecraft version it is really on.
     *
     * <p>The recipe payload is raw bytes on a channel ViaVersion has no schema for, so Via forwards
     * it untranslated, carrying this server's numeric item ids to a client that numbers items
     * differently. Decoding that throws on the client and drops the connection, which is why an
     * older client used to be kicked the instant it joined. The vanilla recipe book has no such
     * problem: Via parses and rewrites it properly, so REI can still be served.
     */
    public Delivery deliveryFor(Player player) {
        PluginConfig settings = config.get();
        ProtocolGate.Match match = gate.classify(player);
        boolean sameVersion = match == ProtocolGate.Match.NATIVE
                || (match == ProtocolGate.Match.UNKNOWN && settings.crossVersionUnknownIsNative());
        if (sameVersion) {
            return Delivery.EVERYTHING;
        }
        return switch (settings.crossVersionSync()) {
            case FORCE -> Delivery.EVERYTHING;
            case SAFE -> Delivery.RECIPE_BOOK_ONLY;
            case OFF -> Delivery.NOTHING;
        };
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
     * which is only registered from MC 1.21.10 on. Below that the payload is dropped and the extra
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

    /**
     * Whether to send this client the server's full recipe book. REI builds its displays straight
     * out of the vanilla recipe-book packet, and a plugin server only ever sends the handful of
     * recipes the player has unlocked, which is why REI looks empty. The cost is that the player's
     * own recipe book lists everything, so AUTO limits it to clients that report REI.
     */
    public boolean shouldSendRecipeBook(Player player) {
        if (!bridge.canSendRecipeBook()) {
            return false;
        }
        return switch (config.get().recipeBookSync()) {
            case OFF -> false;
            case ALL -> true;
            case AUTO -> player.getListeningPluginChannels().stream()
                    .anyMatch(channel -> channel.startsWith(REI_CHANNEL_NAMESPACE));
        };
    }

    /** Re-sends the recipe book only. Vanilla wipes it on respawn, taking REI's displays with it. */
    public boolean resendRecipeBook(Player player) {
        if (!player.isOnline() || !config.get().enabled()) {
            return false;
        }
        // The same two gates the join path applies. Without the brand check, 'all' mode reached every
        // client here, including vanilla ones, which never got the recipe book on join in the first place.
        if (!shouldSync(ClientBrand.fromBrand(player.getClientBrandName()))
                || deliveryFor(player) == Delivery.NOTHING
                || !shouldSendRecipeBook(player)) {
            return false;
        }
        boolean sent = bridge.sendRecipeBook(player);
        debug("Re-sent the recipe book to " + player.getName() + " (sent=" + sent + ")");
        return sent;
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
        Delivery delivery = deliveryFor(player);
        if (delivery != Delivery.EVERYTHING) {
            return syncCrossVersion(player, delivery);
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
        if (payload.size() > MAX_PAYLOAD_BYTES) {
            // Sending it anyway would disconnect the player mid-join. describe() already said so
            // loudly once; do not repeat it per join.
            debug("Not sending recipes to " + player.getName() + ": payload over the protocol limit.");
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
            payloadSettled.add(player.getUniqueId());
            if (trigger) {
                sentTrigger.add(player.getUniqueId());
            }
        }
        boolean recipeBook = false;
        if (sent && shouldSendRecipeBook(player)) {
            recipeBook = bridge.sendRecipeBook(player);
            if (recipeBook) {
                sentRecipeBook.add(player.getUniqueId());
            }
        }
        if (sent) {
            logSend(player, brand, payload, trigger, recipeBook);
            notify(player, Delivery.EVERYTHING, trigger || recipeBook);
        }
        return sent;
    }

    /**
     * Serves a client that is not on the server's Minecraft version.
     *
     * <p>The payload is withheld (sending it is what was disconnecting these players), but the
     * recipe book still goes out under {@code safe}, because ViaVersion translates it properly and
     * it is the whole of what REI reads. JEI cannot be served at all: the only thing it reads is the
     * payload, and there is no version-independent form of it.
     */
    private boolean syncCrossVersion(Player player, Delivery delivery) {
        logCrossVersionOnce(player);
        // Settled, not failed: the payload is deliberately withheld for this connection, so the join
        // fallback and every channel the client announces afterwards must not keep retrying it.
        payloadSettled.add(player.getUniqueId());
        boolean recipeBook = false;
        if (delivery == Delivery.RECIPE_BOOK_ONLY && shouldSendRecipeBook(player)) {
            recipeBook = bridge.sendRecipeBook(player);
            if (recipeBook) {
                sentRecipeBook.add(player.getUniqueId());
            }
        }
        notify(player, delivery, recipeBook);
        return recipeBook;
    }

    /** One line per player, not per packet: this decision is re-evaluated on every channel they announce. */
    private void logCrossVersionOnce(Player player) {
        if (!crossVersionLogged.add(player.getUniqueId())) {
            return;
        }
        logger.info(player.getName() + " is on protocol " + gate.clientProtocol(player) + " and this server is "
                + gate.serverProtocol() + ". The recipe payload is not being sent: ViaVersion cannot translate it, "
                + "and their client would be disconnected while decoding it. Their recipe viewer will show no "
                + "server recipes unless it reads the recipe book (REI does; JEI does not).");
    }

    /**
     * Join-path sync. The recipes themselves go out once per connection, but the pieces that depend
     * on what the client reported (the JEI re-read trigger and the recipe book) are topped up if
     * the client announces the channel for them later. Deciding once, at the moment the recipes were
     * sent, silently left those clients unserved until someone ran /jrf resync.
     */
    public boolean syncOnceTo(Player player) {
        if (!payloadSettled.contains(player.getUniqueId())) {
            return syncTo(player);
        }
        return topUp(player);
    }

    /**
     * Tells the player where their recipes stand, once per connection: the join path, the late
     * top-up and the settle pass can all deliver to the same player.
     *
     * <p>A client on another Minecraft version that runs JEI is told so, because that is the one
     * case the plugin cannot fix and the player would otherwise just see JEI's own warning and no
     * recipes. Everyone else only hears from us if they were actually served: a modded client with
     * no recipe viewer is not sent chat it has no use for.
     */
    private void notify(Player player, Delivery delivery, boolean servedAViewer) {
        if (!config.get().explainJeiWarning() || notice == null) {
            return;
        }
        if (delivery != Delivery.EVERYTHING && hasJei(player.getListeningPluginChannels())) {
            notifyOnce(player, NOTICE_CROSS_VERSION);
        } else if (servedAViewer) {
            notifyOnce(player, NOTICE_SYNCED);
        }
    }

    private void notifyOnce(Player player, String key) {
        if (notified.add(player.getUniqueId())) {
            notice.accept(player, key);
        }
    }

    /** Sends whatever this client has since become eligible for, without re-sending the recipes. */
    private boolean topUp(Player player) {
        UUID id = player.getUniqueId();
        Delivery delivery = deliveryFor(player);
        if (delivery == Delivery.NOTHING) {
            return false;
        }
        boolean did = false;
        // The trigger only exists to make a running JEI re-read the payload. On a client that never
        // received the payload it would just make other mods reload for nothing.
        if (delivery == Delivery.EVERYTHING && !sentTrigger.contains(id)
                && shouldTriggerRecipeUpdate(player) && bridge.sendRecipeUpdate(player)) {
            sentTrigger.add(id);
            did = true;
            debug("Sent the recipe-update trigger to " + player.getName() + " after it reported JEI");
        }
        if (!sentRecipeBook.contains(id) && shouldSendRecipeBook(player) && bridge.sendRecipeBook(player)) {
            sentRecipeBook.add(id);
            did = true;
            debug("Sent the recipe book to " + player.getName() + " after it reported a viewer that needs it");
        }
        notify(player, delivery, did);
        return did;
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        payloadSettled.remove(id);
        sentTrigger.remove(id);
        sentRecipeBook.remove(id);
        notified.remove(id);
        crossVersionLogged.remove(id);
    }

    public void resyncAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> syncTo(player), null);
        }
    }

    public void invalidate() {
        fabricPayload.invalidate();
        neoForgePayload.invalidate();
        bridge.invalidateRecipeBook();
        synchronized (loggedBrands) {
            loggedBrands.clear();
        }
    }

    public boolean available() {
        return bridge.isAvailable();
    }

    public ProtocolGate protocolGate() {
        return gate;
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
        if (payload.size() > MAX_PAYLOAD_BYTES) {
            logger.severe("The " + label + " recipe payload is " + payload.size() + " bytes, over the "
                    + MAX_PAYLOAD_BYTES + "-byte limit a single custom payload can carry. It will NOT "
                    + "be sent: a client would drop the connection while decoding it. This server has "
                    + "more recipes than this plugin can deliver.");
        } else if (payload.size() > PAYLOAD_WARN_BYTES) {
            logger.warning("The " + label + " recipe payload is " + payload.size() + " bytes, close to the "
                    + MAX_PAYLOAD_BYTES + "-byte protocol limit. Past that limit recipes cannot be sent at all.");
        }
        return payload;
    }

    private void logSend(Player player, ClientBrand brand, RecipePayload payload, boolean triggered,
                         boolean recipeBook) {
        String message = "Sent " + payload.recipes() + " recipes to " + player.getName()
                + " (" + brand + ", " + payload.size() + " bytes"
                + (brand == ClientBrand.FABRIC ? ", recipe-update trigger: " + (triggered ? "yes" : "no") : "")
                + (recipeBook ? ", recipe book: " + bridge.recipeBookStats().entries() + " entries" : "")
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
