package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.CrossVersionMode;
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

    /** This server's protocol; OLD_PROTOCOL is a client ViaVersion is translating for. */
    private static final int SERVER_PROTOCOL = 776;
    private static final int OLD_PROTOCOL = 775;

    private final AtomicInteger fabricBuilds = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();
    private final List<Player> notified = new ArrayList<>();
    private final List<String> noticeKeys = new ArrayList<>();

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

    /** A gate that reports every client on the server's own version: the plain, no-ViaVersion case. */
    private static ProtocolGate nativeGate() {
        return gate(player -> SERVER_PROTOCOL);
    }

    private static ProtocolGate gate(java.util.function.ToIntFunction<Player> clientProtocol) {
        return new ProtocolGate(SERVER_PROTOCOL, clientProtocol, () -> true);
    }

    private static PluginConfig withRecipeBook(RecipeBookMode mode) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), d.syncOnJoin(), d.syncOnDatapackReload(),
                d.recipeUpdateTrigger(), mode, d.crossVersionSync(), d.crossVersionUnknownIsNative(),
                d.explainJeiWarning(), d.debug());
    }

    private RecipeSyncService service(RecipeBridge bridge, PluginConfig config) {
        return service(bridge, config, nativeGate());
    }

    private RecipeSyncService service(RecipeBridge bridge, PluginConfig config, ProtocolGate gate) {
        return new RecipeSyncService(bridge, gate, () -> config, null, Logger.getAnonymousLogger(),
                (player, key) -> {
                    notified.add(player);
                    noticeKeys.add(key);
                });
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

        RecipeSyncService disabled = service(true, new PluginConfig(false, true, true, true, RecipeBookMode.OFF, CrossVersionMode.SAFE, true, true, false));
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
        PluginConfig triggerOff = new PluginConfig(true, true, true, false, RecipeBookMode.OFF, CrossVersionMode.SAFE, true, true, false);
        service(true, triggerOff).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        RecipeBridge noTrigger = bridge(true, false, FABRIC_PAYLOAD);
        service(noTrigger, PluginConfig.defaults()).syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        assertEquals(List.of("fabric:trigger=false", "fabric:trigger=false"), calls);
    }

    @Test
    void notifiesOnlyClientsThatWereActuallyServed() {
        RecipeSyncService service = service(true, PluginConfig.defaults());

        service.syncTo(fabricPlayer(FABRIC_SYNC));
        assertEquals(List.of(), notified);

        Player jei = fabricPlayer(FABRIC_SYNC, JEI);
        service.syncTo(jei);
        assertEquals(List.of(jei), notified);

        // A REI client takes the recipe book rather than the trigger, and is just as much served.
        Player rei = fabricPlayer(FABRIC_SYNC, REI);
        service.syncTo(rei);
        assertEquals(List.of(jei, rei), notified);

        // Once per connection, however many times it is served.
        service.syncTo(rei);
        assertEquals(List.of(jei, rei), notified);
    }

    @Test
    void doesNotExplainJeiSWarningWhenTurnedOff() {
        PluginConfig noticeOff = new PluginConfig(true, true, true, true, RecipeBookMode.OFF, CrossVersionMode.SAFE, true, false, false);
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

    private static PluginConfig crossVersion(CrossVersionMode mode, RecipeBookMode book) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), d.syncOnJoin(), d.syncOnDatapackReload(),
                d.recipeUpdateTrigger(), book, mode, d.crossVersionUnknownIsNative(),
                d.explainJeiWarning(), d.debug());
    }

    private static PluginConfig unknownIsNative(boolean value) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), d.syncOnJoin(), d.syncOnDatapackReload(),
                d.recipeUpdateTrigger(), d.recipeBookSync(), d.crossVersionSync(), value,
                d.explainJeiWarning(), d.debug());
    }

    private RecipeSyncService crossVersionService(CrossVersionMode mode, RecipeBookMode book) {
        return service(bridge(true, true, FABRIC_PAYLOAD), crossVersion(mode, book),
                gate(player -> OLD_PROTOCOL));
    }

    @Test
    void withholdsThePayloadFromAClientOnAnotherVersionButStillSendsTheRecipeBook() {
        RecipeSyncService service = crossVersionService(CrossVersionMode.SAFE, RecipeBookMode.AUTO);

        assertTrue(service.syncTo(fabricPlayer(FABRIC_SYNC, REI)));
        // Neither the payload nor the re-read trigger: both are encoded for a version this client
        // does not speak, and ViaVersion cannot translate the payload at all.
        assertEquals(List.of("recipe-book"), calls);
    }

    @Test
    void sendsNothingToAClientOnAnotherVersionWhenCrossVersionSyncIsOff() {
        RecipeSyncService service = crossVersionService(CrossVersionMode.OFF, RecipeBookMode.AUTO);

        assertFalse(service.syncTo(fabricPlayer(FABRIC_SYNC, REI)));
        assertEquals(List.of(), calls);
    }

    @Test
    void forceSendsEverythingToAClientOnAnotherVersion() {
        RecipeSyncService service = crossVersionService(CrossVersionMode.FORCE, RecipeBookMode.AUTO);

        assertTrue(service.syncTo(fabricPlayer(FABRIC_SYNC, JEI)));
        assertEquals(List.of("fabric:trigger=true"), calls);
    }

    @Test
    void treatsAnUndeterminedVersionAsNativeUnlessToldOtherwise() {
        ProtocolGate undetermined = gate(player -> ProtocolGate.UNKNOWN_VERSION);

        service(bridge(true, true, FABRIC_PAYLOAD), PluginConfig.defaults(), undetermined)
                .syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of("fabric:trigger=true"), calls, "no ViaVersion here means no reason to hold back");

        calls.clear();
        service(bridge(true, true, FABRIC_PAYLOAD), unknownIsNative(false), undetermined)
                .syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of(), calls, "a proxy may be translating where this server cannot see it");
    }

    @Test
    void tellsAJeiClientOnAnotherVersionWhyItHasNoRecipes() {
        crossVersionService(CrossVersionMode.SAFE, RecipeBookMode.AUTO)
                .syncTo(fabricPlayer(FABRIC_SYNC, JEI));

        assertEquals(List.of("cross-version-notice"), noticeKeys);
    }

    @Test
    void tellsACrossVersionReiClientOnlyThatItsRecipesArrived() {
        crossVersionService(CrossVersionMode.SAFE, RecipeBookMode.AUTO)
                .syncTo(fabricPlayer(FABRIC_SYNC, REI));

        assertEquals(List.of("jei-warning-notice"), noticeKeys, "REI is served in full from the recipe book");
    }

    @Test
    void neverRetriesTheWithheldPayloadAndTopsUpOnlyTheRecipeBook() {
        RecipeSyncService service = crossVersionService(CrossVersionMode.SAFE, RecipeBookMode.AUTO);

        // Joins before saying which viewer it runs: nothing to send yet, but the payload question is
        // settled (withheld on purpose) and must not be reopened on every channel it announces.
        Player bare = fabricPlayer(FABRIC_SYNC);
        assertFalse(service.syncOnceTo(bare));
        assertEquals(List.of(), calls);

        // Now reports JEI and REI. The recipe book follows; the re-read trigger does not, because
        // there is no payload on that client for it to re-read.
        Player later = playerWithId(bare, FABRIC_SYNC, JEI, REI);
        assertTrue(service.syncOnceTo(later));
        assertEquals(List.of("recipe-book"), calls);

        calls.clear();
        assertFalse(service.syncOnceTo(later));
        assertEquals(List.of(), calls);
    }

    @Test
    void resendingTheRecipeBookHonoursTheCrossVersionGateAndTheClientBrand() {
        assertTrue(service(bridge(true, true, FABRIC_PAYLOAD), withRecipeBook(RecipeBookMode.ALL))
                .resendRecipeBook(fabricPlayer(FABRIC_SYNC)));

        // 'all' means every modded client; a vanilla client was never sent it on join either.
        assertFalse(service(bridge(true, true, FABRIC_PAYLOAD), withRecipeBook(RecipeBookMode.ALL))
                .resendRecipeBook(player("vanilla", Set.of())));

        // Off for clients on another version means off on respawn too.
        assertFalse(service(bridge(true, true, FABRIC_PAYLOAD),
                crossVersion(CrossVersionMode.OFF, RecipeBookMode.ALL), gate(player -> OLD_PROTOCOL))
                .resendRecipeBook(fabricPlayer(FABRIC_SYNC)));
    }

    /** Records what the service asks of the join packet hold, in the same list as the bridge's calls. */
    private final class RecordingHold implements JoinPacketHold {
        private boolean holding;

        RecordingHold(boolean holding) {
            this.holding = holding;
        }

        @Override
        public boolean isHolding(Player player) {
            return holding;
        }

        @Override
        public void release(Player player) {
            calls.add(holding ? "release-held" : "release");
            holding = false;
        }
    }

    private RecipeSyncService service(RecipeBridge bridge, PluginConfig config, JoinPacketHold hold) {
        return new RecipeSyncService(bridge, nativeGate(), () -> config, null, Logger.getAnonymousLogger(),
                (player, key) -> {
                    notified.add(player);
                    noticeKeys.add(key);
                }, hold);
    }

    private static PluginConfig withHold(boolean hold, boolean syncOnJoin) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), syncOnJoin, d.syncOnDatapackReload(), d.recipeUpdateTrigger(),
                d.recipeBookSync(), d.crossVersionSync(), d.crossVersionUnknownIsNative(), d.explainJeiWarning(),
                d.debug(), hold);
    }

    @Test
    void sendsTheHeldJoinPacketRightBehindThePayloadInsteadOfATrigger() {
        RecipeSyncService service = service(bridge(true, true, FABRIC_PAYLOAD), PluginConfig.defaults(),
                new RecordingHold(true));
        Player jei = fabricPlayer(FABRIC_SYNC, JEI);

        assertTrue(service.syncTo(jei));
        // The join's own packet is the re-read; a trigger as well would be a second recipe packet.
        assertEquals(List.of("fabric:trigger=false", "release-held", "release"), calls);
        assertEquals(List.of(), noticeKeys, "a client that got the recipes first had no warning to explain");

        // Reporting JEI again later does not add a trigger after the fact.
        calls.clear();
        assertFalse(service.syncOnceTo(playerWithId(jei, FABRIC_SYNC, JEI)));
        assertEquals(List.of("release"), calls);
    }

    @Test
    void triggersAsBeforeWhenNothingIsHeld() {
        RecipeSyncService service = service(bridge(true, true, FABRIC_PAYLOAD), PluginConfig.defaults(),
                new RecordingHold(false));

        service.syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of("fabric:trigger=true", "release"), calls);
        assertEquals(List.of("jei-warning-notice"), noticeKeys);
    }

    @Test
    void releasesTheHeldPacketWhenThereIsNothingToSend() {
        service(bridge(true, true, EMPTY_PAYLOAD), PluginConfig.defaults(), new RecordingHold(true))
                .syncTo(fabricPlayer(FABRIC_SYNC, JEI));
        assertEquals(List.of("release-held"), calls);

        calls.clear();
        service(bridge(true, true, FABRIC_PAYLOAD), PluginConfig.defaults(), new RecordingHold(true))
                .syncTo(player("vanilla", Set.of()));
        assertEquals(List.of("release-held"), calls);
    }

    @Test
    void holdsTheJoinPacketOnlyForAFabricClientThatCanBeServed() {
        RecipeSyncService service = service(bridge(true, true, FABRIC_PAYLOAD), PluginConfig.defaults(),
                JoinPacketHold.NONE);
        assertTrue(service.shouldHoldJoinPacket(player("fabric", Set.of())),
                "on joining the channels are not known yet; the brand is");
        assertFalse(service.shouldHoldJoinPacket(player("vanilla", Set.of())));
        assertFalse(service.shouldHoldJoinPacket(player(null, Set.of())));
        assertFalse(service.shouldHoldJoinPacket(player("neoforge", Set.of())));

        assertFalse(service(bridge(true, true, FABRIC_PAYLOAD), withHold(false, true), JoinPacketHold.NONE)
                .shouldHoldJoinPacket(player("fabric", Set.of())));
        assertFalse(service(bridge(true, true, FABRIC_PAYLOAD), withHold(true, false), JoinPacketHold.NONE)
                .shouldHoldJoinPacket(player("fabric", Set.of())));
        assertFalse(service(bridge(false, true, FABRIC_PAYLOAD), PluginConfig.defaults(), JoinPacketHold.NONE)
                .shouldHoldJoinPacket(player("fabric", Set.of())));
        assertFalse(service(bridge(true, true, EMPTY_PAYLOAD), PluginConfig.defaults(), JoinPacketHold.NONE)
                .shouldHoldJoinPacket(player("fabric", Set.of())));
        assertFalse(service(bridge(true, true, OVERSIZED_PAYLOAD), PluginConfig.defaults(), JoinPacketHold.NONE)
                .shouldHoldJoinPacket(player("fabric", Set.of())));
        assertFalse(crossVersionService(CrossVersionMode.SAFE, RecipeBookMode.OFF)
                .shouldHoldJoinPacket(player("fabric", Set.of())), "it would never be sent the payload");
    }
}
