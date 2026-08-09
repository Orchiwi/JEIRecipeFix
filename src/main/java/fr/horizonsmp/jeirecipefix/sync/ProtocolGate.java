package fr.horizonsmp.jeirecipefix.sync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

/**
 * Tells whether a player's client speaks this server's own protocol.
 *
 * <p>ViaVersion and ViaBackwards let a player on an older Minecraft version join, and translate the
 * vanilla packets on the way out. They cannot translate this plugin's recipe payload: it travels on
 * a channel Via has no schema for, so its bytes are forwarded untouched — and those bytes carry this
 * server's <em>numeric</em> item and data-component ids, which shift on every Minecraft release. A
 * client that reads them against its own numbering throws while decoding and drops the connection,
 * which is why an older client was being kicked the moment it joined.
 *
 * <p>Detection goes through ViaVersion's own API, reflectively. The plugin has no compile-time
 * dependency on it and behaves exactly as before when it is absent, and only primitives cross the
 * boundary, so no ViaVersion class is ever loaded by this plugin's classloader.
 */
public final class ProtocolGate {

    /** No answer: either the server's own protocol could not be read, or Via does not know this player. */
    public static final int UNKNOWN_VERSION = -1;

    private static final String VIA_PLUGIN = "ViaVersion";

    /** How a client's protocol version compares to the server's. */
    public enum Match {
        /** Same protocol as the server: everything can be sent. */
        NATIVE,
        /** A different protocol, reached through ViaVersion: the recipe payload would disconnect them. */
        TRANSLATED,
        /** Could not be determined — ViaVersion is absent, or it does not know this player yet. */
        UNKNOWN
    }

    private final int serverProtocol;
    private final ToIntFunction<Player> clientProtocol;
    private final BooleanSupplier viaDetected;

    ProtocolGate(int serverProtocol, ToIntFunction<Player> clientProtocol, BooleanSupplier viaDetected) {
        this.serverProtocol = serverProtocol;
        this.clientProtocol = clientProtocol;
        this.viaDetected = viaDetected;
    }

    public static ProtocolGate create(Logger logger) {
        ViaLookup lookup = new ViaLookup(logger);
        return new ProtocolGate(resolveServerProtocol(logger), lookup, ViaLookup::isPresent);
    }

    public Match classify(Player player) {
        if (serverProtocol == UNKNOWN_VERSION) {
            return Match.UNKNOWN;
        }
        int client = clientProtocol.applyAsInt(player);
        if (client == UNKNOWN_VERSION) {
            return Match.UNKNOWN;
        }
        return client == serverProtocol ? Match.NATIVE : Match.TRANSLATED;
    }

    /** The protocol Via reports for this player, or {@link #UNKNOWN_VERSION}. For logging and {@code /jrf info}. */
    public int clientProtocol(Player player) {
        return clientProtocol.applyAsInt(player);
    }

    /** This server's own protocol number, or {@link #UNKNOWN_VERSION} if it could not be read. */
    public int serverProtocol() {
        return serverProtocol;
    }

    /** Whether ViaVersion is installed on this server, which is what makes per-player detection possible. */
    public boolean viaDetected() {
        return viaDetected.getAsBoolean();
    }

    /**
     * Reads the server's protocol number out of the server jar.
     *
     * <p>{@code SharedConstants#getProtocolVersion()} has kept its name and signature across every
     * version this plugin supports; the {@code WorldVersion} route is the fallback in case that ever
     * stops being true. Failing both is not fatal — it only means every client is treated as
     * indeterminate, which the {@code cross-version-unknown-is-native} setting then decides.
     */
    private static int resolveServerProtocol(Logger logger) {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            try {
                return (int) shared.getMethod("getProtocolVersion").invoke(null);
            } catch (NoSuchMethodException ignored) {
                Object version = shared.getMethod("getCurrentVersion").invoke(null);
                return (int) version.getClass().getMethod("protocolVersion").invoke(version);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Could not read this server's protocol version (" + e
                    + "). Players on another Minecraft version cannot be told apart from players on this one; "
                    + "see cross-version-unknown-is-native in config.yml.");
            return UNKNOWN_VERSION;
        }
    }

    /**
     * Asks ViaVersion what protocol a player is really on.
     *
     * <p>{@code ViaAPI#getPlayerVersion(UUID)} is used rather than the {@code Player} overload or
     * {@code getPlayerProtocolVersion}: it takes a UUID and returns a primitive {@code int}, so
     * nothing from ViaVersion crosses into this plugin and there is nothing to shade or soft-link.
     * It answers {@code -1} for a player Via has not registered, which is <em>not</em> the same as
     * "on the server's version" and must not be read as such.
     */
    private static final class ViaLookup implements ToIntFunction<Player> {

        private final Logger logger;
        private volatile Method isLoaded;
        private volatile Method getApi;
        private volatile Method getPlayerVersion;
        /** The API is not the shape we expect; asking again cannot help. */
        private volatile boolean broken;
        private volatile boolean warned;

        ViaLookup(Logger logger) {
            this.logger = logger;
        }

        static boolean isPresent() {
            Plugin via = Bukkit.getPluginManager().getPlugin(VIA_PLUGIN);
            return via != null && via.isEnabled();
        }

        @Override
        public int applyAsInt(Player player) {
            if (broken) {
                return UNKNOWN_VERSION;
            }
            Plugin via = Bukkit.getPluginManager().getPlugin(VIA_PLUGIN);
            if (via == null || !via.isEnabled()) {
                return UNKNOWN_VERSION;
            }
            if (getPlayerVersion == null) {
                try {
                    resolve(via.getClass().getClassLoader());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    broken = true;
                    warnOnce("ViaVersion is installed but its API is not the shape this plugin expects ("
                            + e + ").");
                    return UNKNOWN_VERSION;
                }
            }
            try {
                if (isLoaded != null && !(boolean) isLoaded.invoke(null)) {
                    return UNKNOWN_VERSION;
                }
                return (int) getPlayerVersion.invoke(getApi.invoke(null), player.getUniqueId());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // Not latched: this can be a connection ViaVersion has not finished setting up, and
                // the next player may well answer normally.
                warnOnce("ViaVersion did not answer what Minecraft version " + player.getName()
                        + " is on (" + e + ").");
                return UNKNOWN_VERSION;
            }
        }

        /** This runs on every join, so a recurring failure must not fill the log. */
        private void warnOnce(String message) {
            if (warned) {
                return;
            }
            warned = true;
            logger.warning(message + " Players on another Minecraft version cannot be told apart from "
                    + "players on this one; what they are sent is decided by cross-version-unknown-is-native "
                    + "in config.yml. Further warnings are suppressed.");
        }

        private void resolve(ClassLoader viaLoader) throws ReflectiveOperationException {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, viaLoader);
            Class<?> apiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, viaLoader);
            Method api = viaClass.getMethod("getAPI");
            // getPlayerVersion(UUID) has been on ViaAPI throughout; isLoaded() was added later, so it
            // is used when present and skipped when not. Requiring it would leave every player
            // unclassified on an older ViaVersion, which is the case that gets people disconnected.
            Method version = apiClass.getMethod("getPlayerVersion", UUID.class);
            Method loaded;
            try {
                loaded = viaClass.getMethod("isLoaded");
            } catch (NoSuchMethodException absentBefore5_12) {
                loaded = null;
            }
            this.isLoaded = loaded;
            this.getApi = api;
            // Published last: it is what the read path checks to decide the rest is ready.
            this.getPlayerVersion = version;
        }
    }
}
