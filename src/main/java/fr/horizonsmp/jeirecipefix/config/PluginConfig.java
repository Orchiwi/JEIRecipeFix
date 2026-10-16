package fr.horizonsmp.jeirecipefix.config;

public record PluginConfig(
        boolean enabled,
        boolean syncOnJoin,
        boolean syncOnDatapackReload,
        boolean recipeUpdateTrigger,
        RecipeBookMode recipeBookSync,
        CrossVersionMode crossVersionSync,
        boolean crossVersionUnknownIsNative,
        boolean explainJeiWarning,
        boolean debug,
        boolean holdJoinRecipePacket) {

    /** Every setting but the join packet hold, which is then on, as it is by default. */
    public PluginConfig(boolean enabled, boolean syncOnJoin, boolean syncOnDatapackReload,
                        boolean recipeUpdateTrigger, RecipeBookMode recipeBookSync,
                        CrossVersionMode crossVersionSync, boolean crossVersionUnknownIsNative,
                        boolean explainJeiWarning, boolean debug) {
        this(enabled, syncOnJoin, syncOnDatapackReload, recipeUpdateTrigger, recipeBookSync, crossVersionSync,
                crossVersionUnknownIsNative, explainJeiWarning, debug, true);
    }

    public static PluginConfig defaults() {
        return new PluginConfig(true, true, true, true, RecipeBookMode.AUTO,
                CrossVersionMode.SAFE, true, true, false, true);
    }
}
