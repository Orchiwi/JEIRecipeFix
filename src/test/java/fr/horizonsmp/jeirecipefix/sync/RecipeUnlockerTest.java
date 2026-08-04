package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.config.RecipeBookMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeUnlockerTest {

    private static final List<NamespacedKey> KEYS = List.of(
            NamespacedKey.minecraft("stick"),
            NamespacedKey.minecraft("torch"),
            NamespacedKey.minecraft("chest"));

    private final Set<NamespacedKey> known = new LinkedHashSet<>();
    private final List<String> calls = new ArrayList<>();

    private static PluginConfig config(boolean unlock) {
        PluginConfig d = PluginConfig.defaults();
        return new PluginConfig(d.enabled(), d.syncOnJoin(), d.syncOnDatapackReload(),
                d.recipeUpdateTrigger(), RecipeBookMode.AUTO, unlock, d.explainJeiWarning(), d.debug());
    }

    private RecipeUnlocker unlocker(boolean unlock, boolean limitedCrafting) {
        return new RecipeUnlocker(() -> config(unlock), () -> KEYS, p -> limitedCrafting,
                Logger.getAnonymousLogger());
    }

    @SuppressWarnings("unchecked")
    private Player player() {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> "getName".equals(method.getName()) ? "world" : null);
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasDiscoveredRecipe" -> known.contains(args[0]);
                    case "discoverRecipes" -> {
                        calls.add("discover:" + ((Collection<NamespacedKey>) args[0]).size());
                        known.addAll((Collection<NamespacedKey>) args[0]);
                        yield ((Collection<NamespacedKey>) args[0]).size();
                    }
                    case "undiscoverRecipes" -> {
                        calls.add("undiscover:" + ((Collection<NamespacedKey>) args[0]).size());
                        known.removeAll((Collection<NamespacedKey>) args[0]);
                        yield ((Collection<NamespacedKey>) args[0]).size();
                    }
                    case "getWorld" -> world;
                    case "getName" -> "Tester";
                    case "toString" -> "Player[Tester]";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void doesNothingWhenTurnedOff() {
        assertEquals(0, unlocker(false, false).unlockFor(player()));
        assertEquals(List.of(), calls, "player data must not be written unless asked for");
    }

    @Test
    void refusesInAWorldThatLimitsCraftingOnPurpose() {
        assertEquals(0, unlocker(true, true).unlockFor(player()));
        assertEquals(List.of(), calls);
    }

    @Test
    void unlocksOnlyWhatThePlayerDoesNotAlreadyKnow() {
        known.add(NamespacedKey.minecraft("stick"));
        RecipeUnlocker unlocker = unlocker(true, false);

        assertEquals(2, unlocker.unlockFor(player()));
        assertEquals(List.of("discover:2"), calls);

        // A reconnect must not fire another burst of events and packets.
        calls.clear();
        assertEquals(0, unlocker.unlockFor(player()));
        assertEquals(List.of(), calls);
    }

    @Test
    void revokesEverythingItUnlocked() {
        RecipeUnlocker unlocker = unlocker(true, false);
        unlocker.unlockFor(player());
        calls.clear();

        assertEquals(3, unlocker.revokeFor(player()));
        assertEquals(List.of("undiscover:3"), calls);
        assertTrue(known.isEmpty());
    }
}
