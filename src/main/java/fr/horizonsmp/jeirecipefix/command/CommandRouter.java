package fr.horizonsmp.jeirecipefix.command;

import java.util.Locale;

public final class CommandRouter {

    public enum Type { RESYNC, RELOAD, INFO, REVOKE, HELP, UNKNOWN }

    public record Action(Type type, String target) {
    }

    private CommandRouter() {
    }

    public static Action route(String[] args) {
        if (args.length == 0) {
            return new Action(Type.HELP, null);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "resync" -> new Action(Type.RESYNC, args.length >= 2 ? args[1] : "self");
            case "reload" -> new Action(Type.RELOAD, null);
            case "info" -> new Action(Type.INFO, null);
            case "revoke" -> new Action(Type.REVOKE, args.length >= 2 ? args[1] : "self");
            case "help" -> new Action(Type.HELP, null);
            default -> new Action(Type.UNKNOWN, sub);
        };
    }
}
