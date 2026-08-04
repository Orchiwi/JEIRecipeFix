package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Marks the server's recipes as known for a player, which is what lets the recipe-book "craft this"
 * button — and REI's transfer button, which falls back to it — actually move items into the grid.
 *
 * <p>The server refuses a placement request for a recipe the player has not discovered, and this
 * plugin sends recipe-book entries over the network without ever touching that server-side list, so
 * the button looked available and did nothing. Unlocking closes exactly that gap and leaves the item
 * movement to the server's own code, which validates the container, the recipe and the player, and
 * fires the usual cancellable events.
 *
 * <p>Off by default: unlike everything else here, it writes to the player's saved data.
 */
public final class RecipeUnlocker {

    private final Supplier<PluginConfig> config;
    private final Supplier<Collection<NamespacedKey>> recipeKeys;
    private final Predicate<Player> craftingIsLimited;
    private final Logger logger;

    private boolean limitedCraftingLogged;

    public RecipeUnlocker(Supplier<PluginConfig> config,
                          Supplier<Collection<NamespacedKey>> recipeKeys,
                          Predicate<Player> craftingIsLimited,
                          Logger logger) {
        this.config = config;
        this.recipeKeys = recipeKeys;
        this.craftingIsLimited = craftingIsLimited;
        this.logger = logger;
    }

    /**
     * Unlocks every server recipe this player does not already know.
     *
     * @return the number newly unlocked; 0 when disabled, when the player already knew them all, or
     *         when the world limits crafting
     */
    public int unlockFor(Player player) {
        if (!config.get().unlockRecipes()) {
            return 0;
        }
        if (craftingIsLimited.test(player)) {
            // doLimitedCrafting means the server deliberately gates recipes behind progression.
            // Unlocking everything would quietly undo that, so refuse rather than obey the config.
            warnAboutLimitedCraftingOnce(player);
            return 0;
        }
        List<NamespacedKey> missing = new ArrayList<>();
        for (NamespacedKey key : recipeKeys.get()) {
            if (!player.hasDiscoveredRecipe(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        // One batched call: it is a single event burst and a single packet, and reconnects are a
        // no-op because everything is already known by then.
        player.discoverRecipes(missing);
        return missing.size();
    }

    /** Undoes {@link #unlockFor}, for an operator who turned the setting on and changed their mind. */
    public int revokeFor(Player player) {
        List<NamespacedKey> known = new ArrayList<>();
        for (NamespacedKey key : recipeKeys.get()) {
            if (player.hasDiscoveredRecipe(key)) {
                known.add(key);
            }
        }
        if (!known.isEmpty()) {
            player.undiscoverRecipes(known);
        }
        return known.size();
    }

    private void warnAboutLimitedCraftingOnce(Player player) {
        if (limitedCraftingLogged) {
            return;
        }
        limitedCraftingLogged = true;
        logger.warning("unlock-recipes is on, but " + player.getWorld().getName()
                + " has doLimitedCrafting enabled. Not unlocking anything there: that gamerule exists"
                + " to gate recipes behind progression, and unlocking them all would defeat it."
                + " Recipe viewers still show the server's recipes; only the craft-this button is affected.");
    }
}
