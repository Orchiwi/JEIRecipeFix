package fr.horizonsmp.jeirecipefix.config;

import java.util.Locale;

/**
 * Who gets the server's full recipe book. It is what REI reads its displays from, but it also fills
 * the player's vanilla recipe book, so it is not something to send to everyone by default.
 */
public enum RecipeBookMode {

    /** Only clients that report a recipe viewer needing it (REI). */
    AUTO,
    /** Every modded client, for viewers this plugin cannot detect. */
    ALL,
    /** Never. */
    OFF;

    public static RecipeBookMode parse(String value, RecipeBookMode fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto", "true" -> AUTO;
            case "all" -> ALL;
            case "off", "none", "false" -> OFF;
            default -> fallback;
        };
    }
}
