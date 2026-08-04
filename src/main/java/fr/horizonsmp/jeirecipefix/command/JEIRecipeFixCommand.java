package fr.horizonsmp.jeirecipefix.command;

import fr.horizonsmp.jeirecipefix.JEIRecipeFix;
import fr.horizonsmp.jeirecipefix.i18n.Messages;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class JEIRecipeFixCommand implements CommandExecutor, TabCompleter {

    private final JEIRecipeFix plugin;
    private final RecipeSyncService syncService;
    private final Messages messages;

    public JEIRecipeFixCommand(JEIRecipeFix plugin, RecipeSyncService syncService, Messages messages) {
        this.plugin = plugin;
        this.syncService = syncService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        CommandRouter.Action action = CommandRouter.route(args);
        switch (action.type()) {
            case HELP, UNKNOWN -> messages.send(sender, action.type() == CommandRouter.Type.HELP ? "help" : "unknown-subcommand");
            case RELOAD -> {
                if (denied(sender, "jeirecipefix.command.reload")) return true;
                plugin.reloadAll();
                messages.send(sender, "reload-success");
            }
            case INFO -> {
                if (denied(sender, "jeirecipefix.command.info")) return true;
                messages.send(sender, "info-line", Map.of(
                        "recipes", String.valueOf(syncService.recipeCount()),
                        "players", String.valueOf(Bukkit.getOnlinePlayers().size()),
                        "state", syncState(),
                        "trigger", syncService.canTriggerRecipeUpdate() ? "available" : "unavailable",
                        "failures", String.valueOf(syncService.failureCount())));
            }
            case RESYNC -> handleResync(sender, action.target());
            case REVOKE -> handleRevoke(sender, action.target());
        }
        return true;
    }

    /** "active" used to be reported even with the master switch off, which read as everything is fine. */
    private String syncState() {
        if (!plugin.pluginConfig().enabled()) {
            return "disabled in config";
        }
        return syncService.available() ? "active" : "dormant";
    }

    private void handleResync(CommandSender sender, String target) {
        if (denied(sender, "jeirecipefix.command.resync")) return;
        if (!syncService.available()) {
            messages.send(sender, "recipe-sync-dormant");
            return;
        }
        if ("all".equalsIgnoreCase(target)) {
            syncService.resyncAll();
            messages.send(sender, "resync-all", Map.of("count", String.valueOf(Bukkit.getOnlinePlayers().size())));
            return;
        }
        if ("self".equalsIgnoreCase(target)) {
            if (sender instanceof Player player) {
                player.getScheduler().run(plugin, t -> syncService.syncTo(player), null);
                messages.send(sender, "resync-self");
            } else {
                messages.send(sender, "player-not-found", Map.of("player", "console"));
            }
            return;
        }
        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            messages.send(sender, "player-not-found", Map.of("player", target));
            return;
        }
        player.getScheduler().run(plugin, t -> syncService.syncTo(player), null);
        messages.send(sender, "resync-player", Map.of("player", player.getName()));
    }

    private void handleRevoke(CommandSender sender, String target) {
        if (denied(sender, "jeirecipefix.command.resync")) return;
        if (syncService.unlocker() == null) {
            messages.send(sender, "revoke-limited");
            return;
        }
        if ("all".equalsIgnoreCase(target)) {
            int players = 0;
            for (Player online : Bukkit.getOnlinePlayers()) {
                syncService.unlocker().revokeFor(online);
                players++;
            }
            messages.send(sender, "revoke-all", Map.of("count", String.valueOf(players)));
            return;
        }
        Player player = "self".equalsIgnoreCase(target)
                ? (sender instanceof Player p ? p : null)
                : Bukkit.getPlayerExact(target);
        if (player == null) {
            messages.send(sender, "player-not-found", Map.of("player", target));
            return;
        }
        int revoked = syncService.unlocker().revokeFor(player);
        messages.send(sender, "revoke-player",
                Map.of("player", player.getName(), "count", String.valueOf(revoked)));
    }

    private boolean denied(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            messages.send(sender, "no-permission");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("resync", "reload", "info", "revoke", "help")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("resync") || args[0].equalsIgnoreCase("revoke"))) {
            return Stream.concat(Stream.of("all", "self"),
                            Bukkit.getOnlinePlayers().stream().map(Player::getName))
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
