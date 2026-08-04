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
    /** Well under the protocol frame limit, so even a heavy datapack never produces an oversized packet. */
    private static final int RECIPE_BOOK_BATCH_BYTES = 512 * 1024;

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

    // Vanilla recipe-book packets. REI builds its displays from these, so sending every recipe here
    // is what makes REI work on a plugin server. Never register REI's own channels to go with it:
    // if the server answers roughlyenoughitems:create_item and friends, REI decides the server runs
    // REI, stops reading this packet, and waits for a sync it will never get.
    private Method listDisplaysForRecipe;    // RecipeManager#listDisplaysForRecipe(ResourceKey, Consumer)
    private Constructor<?> recipeBookAddCtor; // ClientboundRecipeBookAddPacket(List, boolean)
    private Constructor<?> recipeBookEntryCtor; // Entry(RecipeDisplayEntry, boolean notification, boolean highlight)
    private Object recipeBookEntryStreamCodec; // Entry.STREAM_CODEC, used to measure a batch
    private Constructor<?> displayEntryCtor;  // RecipeDisplayEntry(id, display, group, category, craftingRequirements)
    private Method displayEntryId;
    private Method displayEntryDisplay;
    private Method displayEntryGroup;
    private Method displayEntryCategory;
    private boolean recipeBookAvailable;
    private Constructor<?> recipeBookRemoveCtor; // ClientboundRecipeBookRemovePacket(List<RecipeDisplayId>)
    private volatile List<Object> recipeBookRemovePackets = List.of();
    private volatile List<Object> recipeBookPackets = List.of();
    private volatile RecipeBookStats recipeBookStats = new RecipeBookStats(0, 0, 0);
    private volatile boolean recipeBookDirty;

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
        resolveRecipeBook(recipeManager);
    }

    /**
     * Resolves the vanilla recipe-book packet. Kept out of the fatal path like the update trigger:
     * losing it costs REI support, not the plugin.
     */
    private void resolveRecipeBook(Object recipeManager) {
        try {
            Class<?> recipeManagerClass = Reflect.clazz("net.minecraft.world.item.crafting.RecipeManager");
            Class<?> displayEntry = Reflect.clazz("net.minecraft.world.item.crafting.display.RecipeDisplayEntry");
            Class<?> resourceKey = Reflect.clazz("net.minecraft.resources.ResourceKey");
            this.listDisplaysForRecipe = Reflect.method(recipeManagerClass, "listDisplaysForRecipe",
                    resourceKey, java.util.function.Consumer.class);
            Class<?> addPacket = Reflect.clazz("net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket");
            Class<?> entry = Reflect.clazz("net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket$Entry");
            this.recipeBookAddCtor = Reflect.ctor(addPacket, List.class, boolean.class);
            this.recipeBookEntryCtor = Reflect.ctor(entry, displayEntry, boolean.class, boolean.class);
            this.recipeBookEntryStreamCodec = Reflect.staticField(entry, "STREAM_CODEC");

            // Needed to rebuild each display without its crafting requirements; see
            // withoutCraftingRequirements. Resolved here so that if it ever fails, the recipe book
            // is disabled rather than sent in a form that can disconnect a player.
            Class<?> recipeDisplay = Reflect.clazz("net.minecraft.world.item.crafting.display.RecipeDisplay");
            Class<?> recipeDisplayId = Reflect.clazz("net.minecraft.world.item.crafting.display.RecipeDisplayId");
            Class<?> bookCategory = Reflect.clazz("net.minecraft.world.item.crafting.RecipeBookCategory");
            this.displayEntryCtor = Reflect.ctor(displayEntry, recipeDisplayId, recipeDisplay,
                    java.util.OptionalInt.class, bookCategory, java.util.Optional.class);
            this.displayEntryId = Reflect.method(displayEntry, "id");
            this.displayEntryDisplay = Reflect.method(displayEntry, "display");
            this.displayEntryGroup = Reflect.method(displayEntry, "group");
            this.displayEntryCategory = Reflect.method(displayEntry, "category");
            this.recipeBookRemoveCtor = Reflect.ctor(
                    Reflect.clazz("net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket"),
                    List.class);

            this.recipeBookAvailable = true;
            buildRecipeBookPackets(recipeManager);
            RecipeBookStats stats = recipeBookStats;
            plugin.getLogger().info("Prepared " + stats.entries() + " recipe-book entries in "
                    + stats.packets() + " packet(s) (" + stats.bytes() + " bytes) for REI clients.");
        } catch (RuntimeException e) {
            this.recipeBookAvailable = false;
            plugin.getLogger().warning("Could not resolve the vanilla recipe-book packet ("
                    + e.getMessage() + "). JEI is unaffected, but REI will only show the recipes your "
                    + "client already knows.");
        }
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
    public boolean sendRecipeUpdate(Player player) {
        if (!recipeUpdateTriggerAvailable) {
            return false;
        }
        Object connection = connectionOf(player);
        return connection != null && sendRecipeUpdateTrigger(player, connection);
    }

    @Override
    public boolean canSendRecipeBook() {
        return recipeBookAvailable;
    }

    @Override
    public RecipeBookStats recipeBookStats() {
        return recipeBookStats;
    }

    @Override
    public void invalidateRecipeBook() {
        this.recipeBookDirty = true;
    }

    /**
     * Turns every recipe on the server into vanilla recipe-book entries, batched so no single packet
     * approaches the frame limit. Built once and reused for every player: the entries are immutable
     * and carry nothing player-specific.
     */
    @SuppressWarnings("unchecked")
    private void buildRecipeBookPackets(Object recipeManager) {
        List<Object> displays = new ArrayList<>();
        for (Object holder : (Collection<?>) Reflect.call(getRecipes, recipeManager)) {
            Object id = Reflect.call(holderId, holder);
            Reflect.call(listDisplaysForRecipe, recipeManager, id,
                    (java.util.function.Consumer<Object>) d -> displays.add(withoutCraftingRequirements(d)));
        }

        List<Object> packets = new ArrayList<>();
        List<Object> batch = new ArrayList<>();
        int total = 0;
        Object buf = newRegistryBuf();
        try {
            int batchStart = 0;
            for (Object display : displays) {
                Object entry = newRecipeBookEntry(display);
                // Measure as we go: entry sizes vary wildly, so a fixed entry count per packet would
                // either waste packets or overshoot the frame limit on a heavy datapack.
                Reflect.call(streamCodecEncode, recipeBookEntryStreamCodec, buf, entry);
                batch.add(entry);
                int size = ((ByteBuf) buf).readableBytes();
                if (size - batchStart >= RECIPE_BOOK_BATCH_BYTES) {
                    packets.add(newRecipeBookPacket(batch));
                    batch = new ArrayList<>();
                    batchStart = size;
                }
            }
            total = ((ByteBuf) buf).readableBytes();
        } finally {
            ((ByteBuf) buf).release();
        }
        if (!batch.isEmpty()) {
            packets.add(newRecipeBookPacket(batch));
        }
        this.recipeBookPackets = List.copyOf(packets);
        this.recipeBookRemovePackets = buildRemovePackets(displays);
        this.recipeBookStats = new RecipeBookStats(displays.size(), packets.size(), total);
        this.recipeBookDirty = false;
    }

    /**
     * Returns the display with its crafting requirements dropped.
     *
     * <p>That field is the only part of the recipe-book packet whose decoding makes the client
     * resolve an item tag, and it does so with no fallback: an unknown tag throws, and the client
     * drops the connection. Vanilla gets away with it because it only ever sends the handful of
     * recipes a player has unlocked; sending every recipe on the server means the client has to
     * resolve every tag any recipe mentions, and REI's own local-recipe fallback replaces the
     * client's tag set with one built from its own files, so server-only tags stop existing.
     *
     * <p>Nothing is lost: the field only drives the vanilla recipe book's "can I craft this"
     * shading, and recipe viewers read the display itself.
     */
    private Object withoutCraftingRequirements(Object display) {
        try {
            return displayEntryCtor.newInstance(
                    Reflect.call(displayEntryId, display),
                    Reflect.call(displayEntryDisplay, display),
                    Reflect.call(displayEntryGroup, display),
                    Reflect.call(displayEntryCategory, display),
                    java.util.Optional.empty());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot rebuild a recipe display", e);
        }
    }

    /**
     * Remove packets covering exactly the displays we are about to add. REI keeps no per-display
     * identity of its own and does not deduplicate, so without removing first, a second send would
     * show every recipe twice; with it, sending any number of times leaves exactly one copy.
     */
    private List<Object> buildRemovePackets(List<Object> displays) {
        List<Object> ids = new ArrayList<>(displays.size());
        for (Object display : displays) {
            ids.add(Reflect.call(displayEntryId, display));
        }
        List<Object> packets = new ArrayList<>();
        // The same batching budget as the adds: an id is a varint, so these are far smaller.
        int perBatch = Math.max(1, ids.size() / Math.max(1, recipeBookPackets.size()));
        for (int from = 0; from < ids.size(); from += perBatch) {
            List<Object> batch = List.copyOf(ids.subList(from, Math.min(from + perBatch, ids.size())));
            try {
                packets.add(recipeBookRemoveCtor.newInstance(batch));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot build ClientboundRecipeBookRemovePacket", e);
            }
        }
        return List.copyOf(packets);
    }

    private Object newRecipeBookEntry(Object display) {
        try {
            // (notification, highlight) both false: no toast popup, no "new recipe" glow.
            return recipeBookEntryCtor.newInstance(display, false, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build a recipe-book entry", e);
        }
    }

    private Object newRecipeBookPacket(List<Object> batch) {
        try {
            // replace=false: add to whatever the server already sent, never wipe the player's book.
            return recipeBookAddCtor.newInstance(List.copyOf(batch), false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build ClientboundRecipeBookAddPacket", e);
        }
    }

    @Override
    public boolean sendRecipeBook(Player player) {
        if (!recipeBookAvailable) {
            return false;
        }
        try {
            if (recipeBookDirty) {
                buildRecipeBookPackets(Reflect.call(getRecipeManager, minecraftServer));
            }
            List<Object> packets = recipeBookPackets;
            if (packets.isEmpty()) {
                return false;
            }
            Object connection = connectionOf(player);
            if (connection == null) {
                return false;
            }
            // Clear our own previous entries first so repeat sends cannot duplicate anything.
            for (Object packet : recipeBookRemovePackets) {
                sendPacket(connection, packet);
            }
            for (Object packet : packets) {
                sendPacket(connection, packet);
            }
            // REI queues each batch as a job and only runs the queue during a reload, with its
            // fillers populated. Left alone, entries delivered during the join storm are queued while
            // the fillers are cleared and quietly amount to nothing. A recipe-update packet right
            // after gives REI a clean reload to consume them in, which is what /jrf resync was
            // accidentally providing.
            if (recipeUpdateTriggerAvailable) {
                sendRecipeUpdateTrigger(player, connection);
            }
            return true;
        } catch (RuntimeException e) {
            logFailure("recipe-book", "Failed to send recipe-book entries to " + player.getName(), e);
            return false;
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
