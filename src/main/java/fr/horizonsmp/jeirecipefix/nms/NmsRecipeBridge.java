package fr.horizonsmp.jeirecipefix.nms;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class NmsRecipeBridge implements RecipeBridge {

    private static final String FABRIC_CHANNEL = "fabric:recipe_sync";
    private static final String NEOFORGE_CHANNEL = "neoforge:recipe_content";
    /** Fabric's client only accepts serializers a recipe viewer opted into, and JEI opts into exactly these. */
    private static final String VANILLA_PREFIX = "minecraft:";
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Plugin plugin;
    private boolean available;

    private Object minecraftServer;
    private Object registryAccess;
    private Object serializerRegistry;
    private Method registryGetKey;
    private Method getRecipeManager;
    private Method getRecipes;
    private Method holderId;
    private Method holderValue;
    private Method recipeGetSerializer;
    private Method serializerStreamCodec;
    private Method streamCodecEncode;
    private Method bufWriteVarInt;
    private Method bufWriteResourceLocation;
    private Method resourceKeyLocation;
    private Constructor<?> registryBufCtor;
    private volatile Object recipesCache;
    private volatile int recipeCount;

    private Object recipeTypeRegistry;       // BuiltInRegistries.RECIPE_TYPE
    private Method recipeGetType;            // Recipe#getType()
    private Method recipeTypeRegistryGetId;  // Registry#getId(Object) -> int
    private Object recipeHolderStreamCodec;  // RecipeHolder.STREAM_CODEC (static field)
    private Object serverRegistries;         // MinecraftServer#registries() -> LayeredRegistryAccess<RegistryLayer>
    private Method serializeTagsToNetwork;   // TagNetworkSerialization#serializeTagsToNetwork(LayeredRegistryAccess) -> Map
    private Constructor<?> updateTagsPacketCtor; // ClientboundUpdateTagsPacket(Map)

    // Vanilla recipe-update packet, rebuilt exactly the way PlayerList#placeNewPlayer builds it.
    private Constructor<?> updateRecipesPacketCtor; // ClientboundUpdateRecipesPacket(Map, SelectableRecipe$SingleInputSet)
    private Method syncedItemProperties;            // RecipeManager#getSynchronizedItemProperties()
    private Method syncedStonecutterRecipes;        // RecipeManager#getSynchronizedStonecutterRecipes()
    private boolean recipeUpdateTriggerAvailable;

    private Method getHandle;
    private Method connectionSend;
    private Constructor<?> customPayloadPacketCtor;
    private Constructor<?> discardedPayloadCtor;
    private boolean discardedPayloadTakesByteBuf; // 1.21.2/1.21.3 take ByteBuf; 1.21.4+ take byte[].
    private Object fabricPayloadId;
    private Object neoForgePayloadId;

    private final Map<String, Long> lastFailureLog = new ConcurrentHashMap<>();
    private final AtomicInteger failures = new AtomicInteger();

    public NmsRecipeBridge(Plugin plugin) {
        this.plugin = plugin;
        try {
            resolveHandles();
            this.available = true;
        } catch (RuntimeException e) {
            this.available = false;
            plugin.getLogger().warning("Could not resolve server internals: " + e.getMessage());
        }
    }

    private void resolveHandles() {
        Object craftServer = org.bukkit.Bukkit.getServer();
        Method getServer = Reflect.method(craftServer.getClass(), "getServer");
        this.minecraftServer = Reflect.call(getServer, craftServer);

        Method registryAccessMethod = Reflect.method(minecraftServer.getClass(), "registryAccess");
        this.registryAccess = Reflect.call(registryAccessMethod, minecraftServer);

        this.getRecipeManager = Reflect.method(minecraftServer.getClass(), "getRecipeManager");
        Object recipeManager = Reflect.call(getRecipeManager, minecraftServer);
        this.getRecipes = Reflect.method(recipeManager.getClass(), "getRecipes");
        Collection<?> recipes = (Collection<?>) Reflect.call(getRecipes, recipeManager);
        this.recipesCache = recipes;
        this.recipeCount = recipes.size();

        Class<?> builtInRegistries = Reflect.clazz("net.minecraft.core.registries.BuiltInRegistries");
        this.serializerRegistry = Reflect.staticField(builtInRegistries, "RECIPE_SERIALIZER");
        this.registryGetKey = Reflect.method(serializerRegistry.getClass(), "getKey", Object.class);

        // Resolve holder/recipe/serializer/codec handles from their declaring types (not a sample
        // concrete instance) so the cached Method handles dispatch virtually across every
        // implementation and no sample recipe is needed (an empty recipe set must not throw).
        Class<?> recipeHolder = Reflect.clazz("net.minecraft.world.item.crafting.RecipeHolder");
        this.holderId = Reflect.method(recipeHolder, "id");
        this.holderValue = Reflect.method(recipeHolder, "value");

        Class<?> recipeClass = Reflect.clazz("net.minecraft.world.item.crafting.Recipe");
        this.recipeGetSerializer = Reflect.method(recipeClass, "getSerializer");
        Class<?> recipeSerializerClass = Reflect.clazz("net.minecraft.world.item.crafting.RecipeSerializer");
        this.serializerStreamCodec = Reflect.method(recipeSerializerClass, "streamCodec");
        Class<?> streamEncoderClass = Reflect.clazz("net.minecraft.network.codec.StreamEncoder");
        this.streamCodecEncode = Reflect.method(streamEncoderClass, "encode", Object.class, Object.class);

        // NeoForge path: recipe types written as a registry collection, holders via RecipeHolder.STREAM_CODEC.
        this.recipeTypeRegistry = Reflect.staticField(builtInRegistries, "RECIPE_TYPE");
        this.recipeTypeRegistryGetId = Reflect.method(recipeTypeRegistry.getClass(), "getId", Object.class);
        this.recipeGetType = Reflect.method(recipeClass, "getType");
        // RecipeHolder.STREAM_CODEC is a StreamCodec (which extends StreamEncoder), so streamCodecEncode applies to it.
        this.recipeHolderStreamCodec = Reflect.staticField(recipeHolder, "STREAM_CODEC");

        // NeoForge clients also expect tags: build a vanilla ClientboundUpdateTagsPacket from the server's frozen
        // registries. serializeTagsToNetwork(LayeredRegistryAccess) yields exactly the Map the packet constructor needs.
        Method serverRegistriesMethod = Reflect.method(minecraftServer.getClass(), "registries");
        this.serverRegistries = Reflect.call(serverRegistriesMethod, minecraftServer);
        Class<?> tagNetworkSerialization = Reflect.clazz("net.minecraft.tags.TagNetworkSerialization");
        Class<?> layeredRegistryAccess = Reflect.clazz("net.minecraft.core.LayeredRegistryAccess");
        this.serializeTagsToNetwork = Reflect.method(tagNetworkSerialization, "serializeTagsToNetwork", layeredRegistryAccess);
        Class<?> updateTagsPacket = Reflect.clazz("net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket");
        this.updateTagsPacketCtor = Reflect.ctor(updateTagsPacket, Map.class);

        Class<?> friendlyByteBuf = Reflect.clazz("net.minecraft.network.FriendlyByteBuf");
        this.bufWriteVarInt = Reflect.method(friendlyByteBuf, "writeVarInt", int.class);
        // 26.x renamed ResourceLocation to Identifier (with FriendlyByteBuf#writeResourceLocation ->
        // writeIdentifier and ResourceKey#location -> identifier). Resolve each across both name eras.
        Class<?> resourceLocation = Reflect.clazzAny(
                "net.minecraft.resources.ResourceLocation",
                "net.minecraft.resources.Identifier");
        this.bufWriteResourceLocation = Reflect.methodAny(friendlyByteBuf,
                new String[] {"writeResourceLocation", "writeIdentifier"}, resourceLocation);
        Class<?> resourceKey = Reflect.clazz("net.minecraft.resources.ResourceKey");
        this.resourceKeyLocation = Reflect.methodAny(resourceKey, new String[] {"location", "identifier"});

        Class<?> registryBuf = Reflect.clazz("net.minecraft.network.RegistryFriendlyByteBuf");
        Class<?> registryAccessClass = Reflect.clazz("net.minecraft.core.RegistryAccess");
        this.registryBufCtor = Reflect.ctor(registryBuf, ByteBuf.class, registryAccessClass);

        Class<?> customPayloadPacket = Reflect.clazz("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
        Class<?> customPacketPayload = Reflect.clazz("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
        this.customPayloadPacketCtor = Reflect.ctor(customPayloadPacket, customPacketPayload);
        Class<?> discardedPayload = Reflect.clazz("net.minecraft.network.protocol.common.custom.DiscardedPayload");
        // DiscardedPayload carries raw bytes for a channel the server has no codec for. 1.21.4+ takes
        // (ResourceLocation/Identifier, byte[]); 1.21.2/1.21.3 take (ResourceLocation, ByteBuf). Try byte[] first.
        this.discardedPayloadCtor = Reflect.ctorAny(discardedPayload,
                new Class<?>[] {resourceLocation, byte[].class},
                new Class<?>[] {resourceLocation, ByteBuf.class});
        this.discardedPayloadTakesByteBuf =
                discardedPayloadCtor.getParameterTypes()[1] == ByteBuf.class;
        this.fabricPayloadId = newResourceLocation(resourceLocation, FABRIC_CHANNEL);
        this.neoForgePayloadId = newResourceLocation(resourceLocation, NEOFORGE_CHANNEL);

        Class<?> craftPlayer = Reflect.clazz("org.bukkit.craftbukkit.entity.CraftPlayer");
        this.getHandle = Reflect.method(craftPlayer, "getHandle");
        // Resolved eagerly: a lazily-cached Method on a non-volatile field can be published to another
        // region thread (Folia) without its setAccessible flag, which surfaces as a random send failure.
        this.connectionSend = Reflect.method(
                Reflect.clazz("net.minecraft.server.network.ServerGamePacketListenerImpl"),
                "send", Reflect.clazz("net.minecraft.network.protocol.Packet"));

        resolveRecipeUpdateTrigger(recipeManager);
    }

    /**
     * Resolves the handles needed to rebuild the vanilla recipe-update packet. Kept out of the fatal
     * path on purpose: without it the plugin still delivers recipes, it just cannot make a running
     * recipe viewer re-read them, so a rename here should degrade rather than disable the plugin.
     */
    private void resolveRecipeUpdateTrigger(Object recipeManager) {
        try {
            Class<?> recipeManagerClass = Reflect.clazz("net.minecraft.world.item.crafting.RecipeManager");
            this.syncedItemProperties = Reflect.method(recipeManagerClass, "getSynchronizedItemProperties");
            this.syncedStonecutterRecipes = Reflect.methodAny(recipeManagerClass,
                    new String[] {"getSynchronizedStonecutterRecipes", "stonecutterRecipes"});
            Class<?> singleInputSet = Reflect.clazz("net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet");
            this.updateRecipesPacketCtor = Reflect.ctor(
                    Reflect.clazz("net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket"),
                    Map.class, singleInputSet);
            // Build one now so a signature mismatch surfaces at startup instead of on the first join.
            buildRecipeUpdatePacket(recipeManager);
            this.recipeUpdateTriggerAvailable = true;
        } catch (RuntimeException e) {
            this.recipeUpdateTriggerAvailable = false;
            plugin.getLogger().warning("Could not resolve the vanilla recipe-update packet ("
                    + e.getMessage() + "). Recipes will still be sent, but a recipe viewer that has "
                    + "already started will not re-read them until the player rejoins.");
        }
    }

    private void refreshRecipes() {
        Object recipeManager = Reflect.call(getRecipeManager, minecraftServer);
        Collection<?> recipes = (Collection<?>) Reflect.call(getRecipes, recipeManager);
        this.recipesCache = recipes;
        this.recipeCount = recipes.size();
    }

    private Object newResourceLocation(Class<?> resourceLocation, String id) {
        try {
            // ResourceLocation.parse(String): the 1.21 static factory (the old 2-arg constructor was removed).
            Method parse = resourceLocation.getMethod("parse", String.class);
            return parse.invoke(null, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build ResourceLocation " + id, e);
        }
    }

    private Object newRegistryBuf() {
        try {
            return registryBufCtor.newInstance(Unpooled.buffer(), registryAccess);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create RegistryFriendlyByteBuf", e);
        }
    }

    private static byte[] toBytes(Object friendlyByteBuf) {
        ByteBuf buf = (ByteBuf) friendlyByteBuf;
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int recipeCount() {
        return recipeCount;
    }

    @Override
    public boolean canTriggerRecipeUpdate() {
        return recipeUpdateTriggerAvailable;
    }

    @Override
    public int failureCount() {
        return failures.get();
    }

    @Override
    public RecipePayload buildFabricPayload() {
        refreshRecipes();
        Map<Object, List<Object>> bySerializer = new LinkedHashMap<>();
        for (Object holder : (Collection<?>) recipesCache) {
            Object recipe = Reflect.call(holderValue, holder);
            Object serializer = Reflect.call(recipeGetSerializer, recipe);
            bySerializer.computeIfAbsent(serializer, s -> new ArrayList<>()).add(holder);
        }

        // Fabric's decoder rejects the WHOLE payload if it meets a serializer the client did not opt
        // into, and viewers only opt into the minecraft: namespace. Dropping a foreign group costs
        // those recipes; keeping it costs every recipe.
        Map<Object, List<Object>> included = new LinkedHashMap<>();
        List<String> skippedGroups = new ArrayList<>();
        int skippedRecipes = 0;
        int includedRecipes = 0;
        for (Map.Entry<Object, List<Object>> entry : bySerializer.entrySet()) {
            String id = String.valueOf(Reflect.call(registryGetKey, serializerRegistry, entry.getKey()));
            if (id.startsWith(VANILLA_PREFIX)) {
                included.put(entry.getKey(), entry.getValue());
                includedRecipes += entry.getValue().size();
            } else {
                skippedGroups.add(id);
                skippedRecipes += entry.getValue().size();
            }
        }

        Object buf = newRegistryBuf();
        try {
            Reflect.call(bufWriteVarInt, buf, included.size());
            for (Map.Entry<Object, List<Object>> entry : included.entrySet()) {
                Object serializer = entry.getKey();
                List<Object> holders = entry.getValue();
                Object serializerId = Reflect.call(registryGetKey, serializerRegistry, serializer);
                Reflect.call(bufWriteResourceLocation, buf, serializerId);
                Reflect.call(bufWriteVarInt, buf, holders.size());
                Object codec = Reflect.call(serializerStreamCodec, serializer);
                for (Object holder : holders) {
                    Object id = Reflect.call(holderId, holder);
                    Object location = Reflect.call(resourceKeyLocation, id);
                    Reflect.call(bufWriteResourceLocation, buf, location);
                    Object recipe = Reflect.call(holderValue, holder);
                    Reflect.call(streamCodecEncode, codec, buf, recipe);
                }
            }
            return new RecipePayload(toBytes(buf), includedRecipes, included.size(), skippedGroups, skippedRecipes);
        } finally {
            ((io.netty.buffer.ByteBuf) buf).release();
        }
    }

    @Override
    public RecipePayload buildNeoForgePayload() {
        refreshRecipes();
        LinkedHashSet<Object> types = new LinkedHashSet<>();
        List<Object> holders = new ArrayList<>();
        for (Object holder : (Collection<?>) recipesCache) {
            holders.add(holder);
            Object recipe = Reflect.call(holderValue, holder);
            types.add(Reflect.call(recipeGetType, recipe));
        }

        Object buf = newRegistryBuf();
        try {
            Reflect.call(bufWriteVarInt, buf, types.size());
            for (Object type : types) {
                int id = (int) Reflect.call(recipeTypeRegistryGetId, recipeTypeRegistry, type);
                Reflect.call(bufWriteVarInt, buf, id);
            }
            Reflect.call(bufWriteVarInt, buf, holders.size());
            for (Object holder : holders) {
                Reflect.call(streamCodecEncode, recipeHolderStreamCodec, buf, holder);
            }
            return new RecipePayload(toBytes(buf), holders.size(), types.size(), List.of(), 0);
        } finally {
            ((io.netty.buffer.ByteBuf) buf).release();
        }
    }

    @Override
    public boolean sendFabric(Player player, byte[] payload, boolean triggerRecipeUpdate) {
        Object connection = connectionOf(player);
        if (connection == null || !send(player, connection, fabricPayloadId, payload)) {
            return false;
        }
        if (triggerRecipeUpdate && recipeUpdateTriggerAvailable) {
            // Order is load-bearing. Fabric's copyPreviousRecipes carries the PREVIOUS recipe
            // container's synchronized recipes onto the new one, so the payload must already have
            // been handled when this arrives; sending it first would carry an empty set forward.
            sendRecipeUpdateTrigger(player, connection);
        }
        // The payload is the sync; a failed trigger is logged and counted, but the recipes did go out.
        return true;
    }

    @Override
    public boolean sendNeoForge(Player player, byte[] payload) {
        Object connection = connectionOf(player);
        if (connection == null || !send(player, connection, neoForgePayloadId, payload)) {
            return false;
        }
        sendTagsPacket(player, connection);
        return true;
    }

    /** Builds the vanilla tags packet from the server's frozen registries (no player needed; unit-testable). */
    Object buildTagsPacket() {
        try {
            Object tags = Reflect.call(serializeTagsToNetwork, null, serverRegistries);
            return updateTagsPacketCtor.newInstance(tags);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build ClientboundUpdateTagsPacket", e);
        }
    }

    /**
     * Rebuilds the packet exactly as {@code PlayerList#placeNewPlayer} does. Built fresh every time:
     * it captures the recipe manager's current property sets, which change on a datapack reload.
     */
    private Object buildRecipeUpdatePacket(Object recipeManager) {
        try {
            return updateRecipesPacketCtor.newInstance(
                    Reflect.call(syncedItemProperties, recipeManager),
                    Reflect.call(syncedStonecutterRecipes, recipeManager));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build ClientboundUpdateRecipesPacket", e);
        }
    }

    private boolean sendRecipeUpdateTrigger(Player player, Object connection) {
        try {
            Object recipeManager = Reflect.call(getRecipeManager, minecraftServer);
            sendPacket(connection, buildRecipeUpdatePacket(recipeManager));
            return true;
        } catch (RuntimeException e) {
            logFailure("recipe-update", "Failed to send the recipe-update packet to " + player.getName(), e);
            return false;
        }
    }

    private boolean sendTagsPacket(Player player, Object connection) {
        try {
            sendPacket(connection, buildTagsPacket());
            return true;
        } catch (RuntimeException e) {
            logFailure("tags", "Failed to send the tags packet to " + player.getName(), e);
            return false;
        }
    }

    private Object connectionOf(Player player) {
        try {
            Object serverPlayer = Reflect.call(getHandle, player);
            // ServerPlayer.connection (Mojang-mapped, stable on the Paper family).
            return Reflect.getField(serverPlayer, "connection");
        } catch (RuntimeException e) {
            logFailure("connection", "Could not reach the connection of " + player.getName(), e);
            return null;
        }
    }

    private boolean send(Player player, Object connection, Object payloadId, byte[] payload) {
        try {
            // 1.21.2/1.21.3 want the data as a ByteBuf; 1.21.4+ want a byte[]. Match the resolved constructor.
            Object data = discardedPayloadTakesByteBuf ? Unpooled.wrappedBuffer(payload) : payload;
            Object discarded = discardedPayloadCtor.newInstance(payloadId, data);
            Object packet = customPayloadPacketCtor.newInstance(discarded);
            sendPacket(connection, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailure("payload", "Failed to send the recipe payload to " + player.getName(), e);
            return false;
        }
    }

    private void sendPacket(Object connection, Object packet) {
        Reflect.call(connectionSend, connection, packet);
    }

    /**
     * Logs at most one stack trace per failure kind per interval. The previous behaviour — one latch
     * for the whole plugin, set forever by the first failure — is what let a broken sync look healthy.
     */
    private void logFailure(String kind, String message, Throwable error) {
        failures.incrementAndGet();
        long now = System.nanoTime();
        Long previous = lastFailureLog.get(kind);
        if (previous != null && now - previous < FAILURE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastFailureLog.put(kind, now);
        plugin.getLogger().log(Level.SEVERE, message + " (further '" + kind
                + "' errors are suppressed for 5 minutes)", error);
    }
}
