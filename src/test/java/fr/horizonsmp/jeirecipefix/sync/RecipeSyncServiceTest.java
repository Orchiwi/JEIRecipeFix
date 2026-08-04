package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.config.RecipeBookMode;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge.RecipeBookStats;
import fr.horizonsmp.jeirecipefix.nms.RecipePayload;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSyncServiceTest {

    private static final byte[] FABRIC_BYTES = {1, 2, 3};
    private static final RecipePayload FABRIC_PAYLOAD = new RecipePayload(FABRIC_BYTES, 7, 2, List.of(), 0);
    private static final RecipePayload NEOFORGE_PAYLOAD = new RecipePayload(new byte[] {9}, 7, 1, List.of(), 0);
    private static final RecipePayload EMPTY_PAYLOAD = new RecipePayload(new byte[0], 0, 0, List.of(), 0);
    /** One byte past what a single custom payload can carry; the client drops the connection decoding it. */
    private static final RecipePayload OVERSIZED_PAYLOAD =
            new RecipePayload(new byte[1024 * 1024 + 1], 90000, 21, List.of(), 0);

    /** One of JEI's own channels; the service only checks the namespace. */
    private static final String JEI = RecipeSyncService.JEI_CHANNEL_NAMESPACE + "cheat_permission";
    private static final String FABRIC_SYNC = RecipeSyncService.FABRIC_RECIPE_SYNC_CHANNEL;
    /** One of REI's own channels; the service only checks the namespace. */
    private static final String REI = RecipeSyncService.REI_CHANNEL_NAMESPACE + "sync_displays";

    private final AtomicInteger fabricBuilds = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();
    private final List<Player> notified = new ArrayList<>();

    private RecipeBridge bridge(boolean available, boolean canTrigger, RecipePayload fabric) {
        return new RecipeBridge() {
            @Override public boolean isAvailable() { return available; }
            @Override public int recipeCount() { return 7; }
            @Override public boolean canTriggerRecipeUpdate() { return canTrigger; }
            @Override public int failureCount() { return 0; }
            @Override public RecipePayload buildFabricPayload() { fabricBuilds.incrementAndGet(); return fabric; }
            @Override public RecipePayload buildNeoForgePayload() { return NEOFORGE_PAYLOAD; }

            @Override
            public boolean sendFabric(Player player, byte[] payload, boolean triggerRecipeUpdate) {
                calls.add("fabric:trigger=" + triggerRecipeUpdate);
                return true;
            }

            @Override
            public boolean sendNeoForge(Player player, byte[] payload) {
                calls.add("neoforge");
                return true;
            }

            @Override
            public boolean sendRecipeUpdate(Player player) {
                calls.add("trigger-only");
                return true;
            }

            @Override public boolean canSendRecipeBook() { return true; }
            @Override public RecipeBookStats recipeBookStats() { return new RecipeBookStats(7, 1, 100); }
            @Override public void invalidateRecipeBook() { }

            @Override
            public boolean sendRecipeBook(Player player) {
                calls.add("recipe-book");
                return true;
            }
        };
    }

    private RecipeSyncService service(boolean available, PluginConfig config) {
        return service(bridge(available, true, FABRIC_PAYLOAD), config);
    }

    private static PluginConfig withRecipeBook(RecipeBookMode mode) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), d.syncOnJoin(), d.syncOnDatapackReload(),
                d.recipeUpdateTrigger(), mode, d.explainJeiWarning(), d.debug());
    }

    private RecipeSyncService service(RecipeBridge bridge, PluginConfig config) {
        return new RecipeSyncService(bridge, () -> config, null, Logger.getAnonymousLogger(), notified::add);
    }

    /** A Player is far too wide to stub by hand; only these few methods are on the sync path. */
    private static Player player(String brand, Set<String> channels) {
        return player(brand, channels, UUID.randomUUID());
    }

    private static Player player(String brand, Set<String> channels, UUID id) {
        return (Player) Proxy.newProxyInstance(
                RecipeSyncServiceTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClientBrandName" -> brand;
                    case "getListeningPluginChannels" -> channels;
                    case "getUniqueId" -> id;
                    case "getName" -> "Tester";
                    case "isOnline" -> true;
                    case "toString" -> "Player[Tester]";
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player fabricPlayer(String... channels) {
        return player("fabric", Set.of(channels));
    }

    /** The same connection (same UUID) reporting more channels than it did before. */
    private static Player playerWithId(Player original, String... channels) {
        return player("fabric", Set.of(channels), original.getUniqueId());
    }

    @Test
    void shouldSyncOnlyWhenEnabledAvailableAndSupportedBrand() {
        RecipeSyncService enabled = service(true, PluginConfig.defaults());
        assertTrue(enabled.shouldSync(ClientBrand.FABRIC));
        assertTrue(enabled.shouldSync(ClientBrand.NEOFORGE));
        assertFalse(enabled.shouldSync(ClientBrand.OTHER));

        RecipeSyncService disabled = service(true, new PluginConfig(false, true, true, true, RecipeBookMode.OFF, true, false));
        assertFalse(disabled.shouldSync(ClientBrand.FABRIC));

        RecipeSyncService unavailable = service(false, PluginConfig.defaults());
        assertFalse(unavailable.shouldSync(ClientBrand.FABRIC));
    }

    @Test
    void cachesPayloadUntilInvalidated() {
        RecipeSyncService service = service(true, PluginConfig.defaults());

        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC).bytes());
        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC).bytes());
        assertEquals(1, fabricBuilds.get());

        service.invalidate();
        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC).bytes());
        assertEquals(2, fabricBuilds.get());
    }

    @Test
    void triggersRecipeUpdateOnlyWhenTheClientAdvertisesBothChannels() {
        RecipeSyncService service = service(true, PluginConfig.defaults());

        // Nothing advertised yet: sending the trigger would make other viewers reload for nothing.
        assertTrue(service.syncTo(fabricPlayer()));
        // Fabric API present but no JEI: REI reloads on the packet without ever reading the payload.
        assertTrue(service.syncTo(fabricPlayer(FABRIC_SYNC)));
        // JEI present but no Fabric recipe sync (MC below 1.21.10): the payload cannot be read at all.
        assertTrue(service.syncTo(fabricPlayer(JEI)));
        // Both: this is the client the trigger exists for.
        assertTrue(service.syncTo(fabricPlayer(FABRIC_SYNC, JEI)));

        assertEquals(List.of(
                "fabric:trigger=false",
                "fabric:trigger=false",
                "fabric:trigger=false",
                "fabric:trigger=true"), calls);
    }

    @Test
    void neverTriggersWhenDisabledInConfigOrUnsupportedByTheServer() {
        PluginConfig triggerOff = new PluginConfig(true, true, true, false, RecipeBookMode.OFF, true, false);
        service(true, triggerOff).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        RecipeBridge noTrigger = bridge(true, false, FABRIC_PAYLOAD);
        service(noTrigger, PluginConfig.defaults()).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        assertEquals(List.of("fabric:trigger=false", "fabric:trigger=false"), calls);
    }

    @Test
    void explainsJeiSWarningOnlyToClientsThatGotTheTrigger() {
        RecipeSyncService service = service(true, PluginConfig.defaults());

        service.syncTo(fabricPlayer(FABRIC_SYNC));
        assertEquals(List.of(), notified);

        Player jei = fabricPlayer(FABRIC_SYNC, JEI);
        service.syncTo(jei);
        assertEquals(List.of(jei), notified);
    }

    @Test
    void doesNotExplainJeiSWarningWhenTurnedOff() {
        PluginConfig noticeOff = new PluginConfig(true, true, true, true, RecipeBookMode.OFF, false, false);
        service(true, noticeOff).syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of(), notified);
    }

    @Test
    void sendsTheRecipeBookOnlyToReiClientsUnderAuto() {
        RecipeSyncService service = service(true, withRecipeBook(RecipeBookMode.AUTO));

        service.syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of("fabric:trigger=true"), calls, "a JEI-only client does not need it");

        calls.clear();
        service.syncTo(fabricPlayer(FABRIC_SYNC, REI));
        assertEquals(List.of("fabric:trigger=false", "recipe-book"), calls);
    }

    @Test
    void recipeBookModeAllSendsToEveryModdedClientAndOffToNone() {
        service(true, withRecipeBook(RecipeBookMode.ALL)).syncTo(fabricPlayer(FABRIC_SYNC));
        assertEquals(List.of("fabric:trigger=false", "recipe-book"), calls);

        calls.clear();
        service(true, withRecipeBook(RecipeBookMode.OFF)).syncTo(fabricPlayer(FABRIC_SYNC, REI));
        assertEquals(List.of("fabric:trigger=false"), calls);
    }

    @Test
    void sendsNothingWhenThePayloadIsEmpty() {
        RecipeSyncService service = service(bridge(true, true, EMPTY_PAYLOAD), PluginConfig.defaults());

        assertFalse(service.syncTo(fabricPlayer(FABRIC_SYNC, JEI)));
        assertEquals(List.of(), calls);
    }

    @Test
    void sendsNothingWhenThePayloadIsOverTheProtocolLimit() {
        RecipeSyncService service = service(bridge(true, true, OVERSIZED_PAYLOAD), PluginConfig.defaults());

        assertFalse(service.syncTo(fabricPlayer(FABRIC_SYNC, JEI)));
        assertEquals(List.of(), calls);
        assertEquals(List.of(), notified);
    }

    @Test
    void topsUpTheTriggerAndBookWhenTheClientReportsThemLate() {
        RecipeSyncService service = service(true, withRecipeBook(RecipeBookMode.AUTO));

        // The recipes go out before the client has said which viewer it runs.
        Player bare = fabricPlayer(FABRIC_SYNC);
        assertTrue(service.syncOnceTo(bare));
        assertEquals(List.of("fabric:trigger=false"), calls);

        // Same connection, now reporting JEI and REI: send what it has become eligible for, and do
        // not re-send the recipes.
        calls.clear();
        Player later = playerWithId(bare, FABRIC_SYNC, JEI, REI);
        assertTrue(service.syncOnceTo(later));
        assertEquals(List.of("trigger-only", "recipe-book"), calls);

        // Nothing left to do on a third pass.
        calls.clear();
        assertFalse(service.syncOnceTo(later));
        assertEquals(List.of(), calls);
    }

    @Test
    void syncOnceSendsOnlyOncePerConnectionButRetriesAfterAFailure() {
        RecipeSyncService service = service(true, PluginConfig.defaults());
        Player fabric = fabricPlayer(FABRIC_SYNC);

        assertTrue(service.syncOnceTo(fabric));
        assertFalse(service.syncOnceTo(fabric));
        assertEquals(1, calls.size());

        // A client whose brand has not arrived yet is not marked as done, so the fallback still runs.
        RecipeSyncService other = service(true, PluginConfig.defaults());
        Player unknown = player(null, Set.of());
        assertFalse(other.syncOnceTo(unknown));
        assertFalse(other.syncOnceTo(unknown));
    }
}
