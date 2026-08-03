package fr.horizonsmp.jeirecipefix.config;

public record PluginConfig(
        boolean enabled,
        boolean syncOnJoin,
        boolean syncOnDatapackReload,
        boolean recipeUpdateTrigger,
        RecipeBookMode recipeBookSync,
        boolean explainJeiWarning,
        boolean debug) {

    public static PluginConfig defaults() {
        return new PluginConfig(true, true, true, true, RecipeBookMode.AUTO, true, false);
    }
}
