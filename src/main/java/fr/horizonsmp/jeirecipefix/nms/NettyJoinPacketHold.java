package fr.horizonsmp.jeirecipefix.nms;

import fr.horizonsmp.jeirecipefix.sync.JoinPacketHold;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Puts a {@link JoinPacketHoldHandler} in every new connection's pipeline, through Paper's own hook
 * for that ({@code ChannelInitializeListenerHolder}), so it is in place before the server sends the
 * join's recipe-update packet. Resolved reflectively like the rest of this package; when anything is
 * missing, {@link #install} returns {@link JoinPacketHold#NONE} and the plugin works as it did.
 */
public final class NettyJoinPacketHold implements JoinPacketHold {

    static final String HANDLER_NAME = "jeirecipefix_join_hold";
    /** Mojang's name for the handler that hands packets to the game; ours goes right in front of it. */
    private static final String PACKET_HANDLER = "packet_handler";
    /**
     * Long enough for a lagging server to reach the plugin's own fallback sync (40 ticks), short
     * enough that a client nobody serves is not left without the packet for long.
     */
    private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long TIMEOUT_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Plugin plugin;
    private final Class<?> recipeUpdatePacket;
    private final Class<?> startConfigurationPacket;
    private final Method getHandle;
    private final Class<?> holderClass;
    private final Object listener;
    private final Key key;
    private final AtomicLong lastTimeoutLog = new AtomicLong(System.nanoTime() - TIMEOUT_LOG_INTERVAL_NANOS);

    private NettyJoinPacketHold(Plugin plugin) {
        this.plugin = plugin;
        this.recipeUpdatePacket = Reflect.clazz("net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket");
        this.startConfigurationPacket = optionalClass(
                "net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket");
        this.getHandle = Reflect.method(Reflect.clazz("org.bukkit.craftbukkit.entity.CraftPlayer"), "getHandle");
        this.holderClass = Reflect.clazz("io.papermc.paper.network.ChannelInitializeListenerHolder");
        Class<?> listenerType = Reflect.clazz("io.papermc.paper.network.ChannelInitializeListener");
        this.listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "afterInitChannel" -> {
                        addTo((Channel) args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "JEIRecipeFix join packet hold";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        this.key = Key.key("jeirecipefix", "join_packet_hold");
        Method addListener = Reflect.method(holderClass, "addListener", Key.class, listenerType);
        Reflect.call(addListener, null, key, listener);
    }

    /** Hooks into every connection made from now on, or returns {@link JoinPacketHold#NONE} if it cannot. */
    public static JoinPacketHold install(Plugin plugin) {
        try {
            return new NettyJoinPacketHold(plugin);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Cannot hold back the join's recipe packet on this server ("
                    + e.getMessage() + "). Recipes still arrive, but JEI may warn that the server sends none.");
            return JoinPacketHold.NONE;
        }
    }

    /** Stops hooking new connections and takes the handler out of the open ones, letting anything held go. */
    public void uninstall() {
        try {
            Method removeListener = Reflect.method(holderClass, "removeListener", Key.class);
            Reflect.call(removeListener, null, key);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not unhook from new connections: " + e.getMessage());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = channelOf(player);
            if (channel != null) {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                });
            }
        }
    }

    private void addTo(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER_NAME) != null) {
            return;
        }
        ChannelHandler handler = new JoinPacketHoldHandler(recipeUpdatePacket, startConfigurationPacket,
                TIMEOUT_MILLIS, this::logTimeout);
        if (pipeline.get(PACKET_HANDLER) != null) {
            pipeline.addBefore(PACKET_HANDLER, HANDLER_NAME, handler);
        } else {
            pipeline.addLast(HANDLER_NAME, handler);
        }
    }

    @Override
    public boolean isHolding(Player player) {
        JoinPacketHoldHandler handler = handlerOf(player);
        return handler != null && handler.isHolding();
    }

    @Override
    public void release(Player player) {
        JoinPacketHoldHandler handler = handlerOf(player);
        if (handler != null) {
            handler.release();
        }
    }

    private JoinPacketHoldHandler handlerOf(Player player) {
        Channel channel = channelOf(player);
        if (channel == null) {
            return null;
        }
        return channel.pipeline().get(HANDLER_NAME) instanceof JoinPacketHoldHandler handler ? handler : null;
    }

    private Channel channelOf(Player player) {
        try {
            Object serverPlayer = Reflect.call(getHandle, player);
            // ServerPlayer.connection -> ServerCommonPacketListenerImpl.connection -> Connection.channel
            // (Mojang-mapped, stable on the Paper family).
            Object listener = Reflect.getField(serverPlayer, "connection");
            Object connection = Reflect.getField(listener, "connection");
            return Reflect.getField(connection, "channel") instanceof Channel channel ? channel : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void logTimeout() {
        long now = System.nanoTime();
        long previous = lastTimeoutLog.get();
        if (now - previous >= TIMEOUT_LOG_INTERVAL_NANOS && lastTimeoutLog.compareAndSet(previous, now)) {
            plugin.getLogger().warning("Sent a joining player's recipe packet after holding it for "
                    + TimeUnit.MILLISECONDS.toSeconds(TIMEOUT_MILLIS) + " seconds without the recipes being"
                    + " sent first. JEI may warn that the server sends none. (Logged at most every 5 minutes.)");
        }
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
