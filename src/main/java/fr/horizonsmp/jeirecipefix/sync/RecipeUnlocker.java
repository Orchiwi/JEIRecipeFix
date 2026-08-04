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

    /** Why nothing was unlocked, so a silent zero is never indistinguishable from a broken setting. */
    public enum Outcome { DISABLED, LIMITED_CRAFTING, NO_RECIPES, ALREADY_KNOWN, UNLOCKED }

    public record Result(Outcome outcome, int count, int total) {
        public String describe(String player) {
            return switch (outcome) {
                case DISABLED -> "Not unlocking recipes for " + player + ": unlock-recipes is off.";
                case LIMITED_CRAFTING -> "Not unlocking recipes for " + player
                        + ": this world uses the doLimitedCrafting gamerule.";
                case NO_RECIPES -> "Not unlocking recipes for " + player
                        + ": the server reported no recipes to unlock.";
                case ALREADY_KNOWN -> "Nothing to unlock for " + player + ": all " + total
                        + " recipes were already known.";
                case UNLOCKED -> "Unlocked " + count + " of " + total + " recipes for " + player
                        + " so the craft-this button works.";
            };
        }
    }

    /** Unlocks every server recipe this player does not already know. */
    public Result unlockFor(Player player) {
        if (!config.get().unlockRecipes()) {
            return new Result(Outcome.DISABLED, 0, 0);
        }
        if (craftingIsLimited.test(player)) {
            // doLimitedCrafting means the server deliberately gates recipes behind progression.
            // Unlocking everything would quietly undo that, so refuse rather than obey the config.
            warnAboutLimitedCraftingOnce(player);
            return new Result(Outcome.LIMITED_CRAFTING, 0, 0);
        }
        Collection<NamespacedKey> keys = recipeKeys.get();
        if (keys.isEmpty()) {
            return new Result(Outcome.NO_RECIPES, 0, 0);
        }
        List<NamespacedKey> missing = new ArrayList<>();
        for (NamespacedKey key : keys) {
            if (!player.hasDiscoveredRecipe(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return new Result(Outcome.ALREADY_KNOWN, 0, keys.size());
        }
        // One batched call: it is a single event burst and a single packet, and reconnects are a
        // no-op because everything is already known by then.
        player.discoverRecipes(missing);
        return new Result(Outcome.UNLOCKED, missing.size(), keys.size());
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
