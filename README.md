# JEIRecipeFix

Makes JEI and REI show your server's recipes again, on Paper, Purpur and Folia.

Since Minecraft 1.21.2 the server no longer sends recipe data to clients. On a plugin server that leaves JEI and REI showing your client's built-in recipes instead of the server's. Both projects tell you to install them server-side, but they are mods and a plugin server cannot run them.

This plugin sends the recipes the way a mod loader would. No client mod, no mod loader on the server.

## Requirements

- Paper, Purpur or Folia on Minecraft 1.21.3 to 26.2.
- A Fabric or NeoForge client with JEI, as your players already have.
- Fabric clients need Minecraft 1.21.10 or newer. Fabric API's recipe-sync channel does not exist below that, so there is nothing to send recipes through.

EMI has no build for 1.21.2 or newer, so it is untested.

## On join

JEI builds its recipe list the instant the server's own recipe packet arrives, which is before any plugin may send anything. So it prints its warning once:

> This server does not provide recipes to JEI. JEI is showing default recipes from your client...

A moment later JEI reloads by itself with your server's recipes. That reload is the plugin. Since the warning is out of date by then, the plugin follows it with one line:

> Recipes synced. Ignore the JEI/REI/EMI warning above if present.

One warning then that line means it worked. Two warnings, or JEI reporting unusable recipes, means it did not: set `debug: true` and open an issue.

## REI

REI ignores the sync JEI uses and builds its list from the recipe book, which normally holds only what you have unlocked. So REI clients are sent the whole recipe book. The visible side effect is that your vanilla recipe book lists everything as known. Nothing is unlocked server-side and it is not an exploit; the server still checks what you know when you click a recipe.

`recipe-book-sync` controls who gets it: `auto` (clients reporting REI, the default), `all` (every modded client, for viewers the plugin cannot detect, including REI on NeoForge), or `off`.

## ViaVersion and ViaBackwards

Players on a different Minecraft version than the server are handled separately, and have to be. The recipes travel on a channel ViaVersion cannot translate, and they carry the server's internal item numbers, which shift with every release. A client reading them with its own numbering fails to decode and drops the connection. Before 0.4.0 this kicked those players on join.

The plugin now checks each player's real version and holds the recipes back from anyone not on the server's. `cross-version-sync` sets what they get instead:

- `safe` (default): no recipes, but still the recipe book, so REI works for them. JEI shows nothing and the player gets one line explaining why. Nobody is kicked.
- `off`: nothing at all.
- `force`: send anyway, which kicks JEI players on another version.

JEI cannot be served across versions. The data is tied to the exact version that encoded it, and ViaVersion offers no hook to translate it.

Detection needs ViaVersion on the server itself. If yours runs on a proxy, set `cross-version-unknown-is-native: false`. A player using a client-side translator such as ViaFabricPlus looks native to the server and will still be kicked; they have to join on the server's version.

## Commands

| Command | |
| --- | --- |
| `/jrf info` | Recipe count, players, sync state, failures, protocol |
| `/jrf resync [player\|all]` | Re-send recipes |
| `/jrf reload` | Reload the config |

`/jeirecipefix` is the full name, `/jrf` the alias. All require `jeirecipefix.admin`, which operators have by default.

## Configuration

```yaml
enabled: true
sync-on-join: true
sync-on-datapack-reload: true
recipe-update-trigger: true
recipe-book-sync: auto
cross-version-sync: safe
cross-version-unknown-is-native: true
explain-jei-warning: true
debug: false
```

`recipe-update-trigger` is what makes an already-running JEI re-read the recipes. Turn it off and recipes still arrive, but JEI keeps showing your client's defaults.

`explain-jei-warning` sends the one-line notice above, only to players who actually received the recipes and run a viewer. Wording lives in `messages.yml`.

Settings added in a newer version are written into your existing `config.yml` and `messages.yml` on startup, with their comments. Anything you changed is left alone.

## Note

Unofficial and third-party. Not affiliated with the authors of JEI, REI or EMI.
