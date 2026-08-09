package fr.horizonsmp.jeirecipefix.config;

import java.util.Locale;

/**
 * What to send to a player whose client is not on the server's own Minecraft version.
 *
 * <p>The recipe payload cannot be translated by ViaVersion and disconnects such a client while it
 * decodes it, so the only question is how much of the rest they still get.
 */
public enum CrossVersionMode {

    /** Skip the payload, still send the vanilla recipe book: no disconnect, and REI keeps working. */
    SAFE,
    /** Send them nothing at all. */
    OFF,
    /** Send everything regardless. This disconnects recipe-viewer clients on another version. */
    FORCE;

    public static CrossVersionMode parse(String value, CrossVersionMode fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "safe", "auto", "true" -> SAFE;
            case "off", "none", "false" -> OFF;
            case "force", "all" -> FORCE;
            default -> fallback;
        };
    }
}
