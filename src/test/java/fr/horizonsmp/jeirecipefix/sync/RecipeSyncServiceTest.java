package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
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

    /** One of JEI's own channels; the service only checks the namespace. */
    private static final String JEI = RecipeSyncService.JEI_CHANNEL_NAMESPACE + "cheat_permission";
    private static final String FABRIC_SYNC = RecipeSyncService.FABRIC_RECIPE_SYNC_CHANNEL;

    private final AtomicInteger fabricBuilds = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();

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
        };
    }

    private RecipeSyncService service(boolean available, PluginConfig config) {
        return service(bridge(available, true, FABRIC_PAYLOAD), config);
    }

    private RecipeSyncService service(RecipeBridge bridge, PluginConfig config) {
        return new RecipeSyncService(bridge, () -> config, null, Logger.getAnonymousLogger());
    }

    /** A Player is far too wide to stub by hand; only these few methods are on the sync path. */
    private static Player player(String brand, Set<String> channels) {
        UUID id = UUID.randomUUID();
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

    @Test
    void shouldSyncOnlyWhenEnabledAvailableAndSupportedBrand() {
        RecipeSyncService enabled = service(true, PluginConfig.defaults());
        assertTrue(enabled.shouldSync(ClientBrand.FABRIC));
        assertTrue(enabled.shouldSync(ClientBrand.NEOFORGE));
        assertFalse(enabled.shouldSync(ClientBrand.OTHER));

        RecipeSyncService disabled = service(true, new PluginConfig(false, true, true, true, false));
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
        PluginConfig triggerOff = new PluginConfig(true, true, true, false, false);
        service(true, triggerOff).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        RecipeBridge noTrigger = bridge(true, false, FABRIC_PAYLOAD);
        service(noTrigger, PluginConfig.defaults()).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        assertEquals(List.of("fabric:trigger=false", "fabric:trigger=false"), calls);
    }

    @Test
    void sendsNothingWhenThePayloadIsEmpty() {
        RecipeSyncService service = service(bridge(true, true, EMPTY_PAYLOAD), PluginConfig.defaults());

        assertFalse(service.syncTo(fabricPlayer(FABRIC_SYNC, JEI)));
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
