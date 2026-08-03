package fr.horizonsmp.jeirecipefix.nms;

import org.bukkit.entity.Player;

public interface RecipeBridge {

    /** True when server internals were resolved and recipe sync can run. */
    boolean isAvailable();

    /** Number of recipes that will be sent (for status/logging). */
    int recipeCount();

    /**
     * True when the vanilla recipe-update packet could be rebuilt on this server version. Without it
     * a Fabric client that has already started its recipe viewer cannot be made to pick up the payload.
     */
    boolean canTriggerRecipeUpdate();

    /** Send failures observed since the plugin was enabled (for status/logging). */
    int failureCount();

    /** Encodes the full recipe set in the Fabric {@code fabric:recipe_sync} wire format. */
    RecipePayload buildFabricPayload();

    /** Encodes the full recipe set in the NeoForge {@code neoforge:recipe_content} wire format. */
    RecipePayload buildNeoForgePayload();

    /**
     * Sends a pre-built Fabric payload to the player.
     *
     * @param triggerRecipeUpdate also send a vanilla recipe-update packet afterwards, which is what
     *                            makes a already-running JEI restart and read the payload
     * @return true when everything was sent
     */
    boolean sendFabric(Player player, byte[] payload, boolean triggerRecipeUpdate);

    /** Sends a pre-built NeoForge payload (plus the tags packet) to the player. */
    boolean sendNeoForge(Player player, byte[] payload);

    /** True when the vanilla recipe-book packets could be built on this server version. */
    boolean canSendRecipeBook();

    /** Recipe-book entries that will be sent, and how many packets they are split over. */
    RecipeBookStats recipeBookStats();

    /** Marks the recipe-book packets stale, so the next send rebuilds them (datapack reload). */
    void invalidateRecipeBook();

    /**
     * Sends every recipe the server knows as vanilla recipe-book entries. REI reads its displays
     * straight out of this packet, so this is what makes REI show a plugin server's recipes.
     */
    boolean sendRecipeBook(Player player);

    /** @param entries recipe displays that will be sent @param packets batches they are split over */
    record RecipeBookStats(int entries, int packets, int bytes) {
    }
}
