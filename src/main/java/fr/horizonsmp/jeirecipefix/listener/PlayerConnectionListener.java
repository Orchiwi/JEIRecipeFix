package fr.horizonsmp.jeirecipefix.listener;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.sync.ClientBrand;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

public final class PlayerConnectionListener implements Listener {

    /** Late enough for a client that never announces its channels; the channel path usually wins first. */
    private static final long FALLBACK_DELAY_TICKS = 40L;
    /** The client announces all its channels in one burst, so let the rest of it land before deciding. */
    private static final long CHANNEL_SETTLE_TICKS = 1L;
    /**
     * REI runs its own plugin reload a few seconds after join, which throws away every display it
     * holds. One repeat pass afterwards restores ours; it is free of duplicates because a send
     * removes its own previous entries first.
     */
    private static final long REI_SETTLE_TICKS = 200L;

    private final Plugin plugin;
    private final RecipeSyncService syncService;
    private final Supplier<PluginConfig> config;

    public PlayerConnectionListener(Plugin plugin, RecipeSyncService syncService, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.syncService = syncService;
        this.config = config;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.get().syncOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        if (ClientBrand.fromBrand(player.getClientBrandName()) == ClientBrand.NEOFORGE) {
            syncService.syncOnceTo(player);
            return;
        }
        // Fabric clients (and clients whose brand has not arrived yet) are handled once the client
        // has announced its plugin channels, because that is what tells us whether it can receive
        // the payload at all and whether the recipe-update trigger is wanted. This delayed attempt
        // is only the fallback for a client that never announces anything.
        player.getScheduler().runDelayed(plugin, task -> syncService.syncOnceTo(player), null, FALLBACK_DELAY_TICKS);
        player.getScheduler().runDelayed(plugin, task -> syncService.resendRecipeBook(player), null, REI_SETTLE_TICKS);
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!config.get().syncOnJoin()) {
            return;
        }
        if (!isInteresting(event.getChannel())) {
            return;
        }
        Player player = event.getPlayer();
        // On some versions this event can fire while the connection is still being configured, with
        // a player that is not in a world yet.
        if (!player.isOnline()) {
            return;
        }
        // A tick of slack: the client announces its channels one at a time, so the rest of the burst
        // — JEI's own channel among them — has not been recorded yet when this fires.
        player.getScheduler().runDelayed(plugin, task -> syncService.syncOnceTo(player), null, CHANNEL_SETTLE_TICKS);
    }

    /**
     * Channels that change what this client should be sent: the one that carries the recipes, and
     * the ones that identify which recipe viewer is installed. They can arrive in separate bursts,
     * so any of them is a reason to re-check.
     */
    private static boolean isInteresting(String channel) {
        return RecipeSyncService.FABRIC_RECIPE_SYNC_CHANNEL.equals(channel)
                || channel.startsWith(RecipeSyncService.JEI_CHANNEL_NAMESPACE)
                || channel.startsWith(RecipeSyncService.REI_CHANNEL_NAMESPACE);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!config.get().syncOnJoin()) {
            return;
        }
        // Vanilla re-sends the recipe book on respawn with replace=true, which drops everything this
        // plugin added and empties REI until the player rejoins.
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> syncService.resendRecipeBook(player), null,
                CHANNEL_SETTLE_TICKS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        syncService.forget(event.getPlayer());
    }
}
