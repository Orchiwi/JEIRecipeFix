# JEIRecipeFix

**Makes JEI and REI work again on Paper, Purpur and Folia servers.**

Since Minecraft **1.21.2**, servers no longer send recipe data to clients. On a plugin-based server (Paper / Purpur / Folia) this means **JEI** and **REI** show your client's built-in recipes instead of the server's — they simply can't see what the server can craft. Their own recommendation is to install them on the server, but they are mods and cannot run on a plugin server.

**JEIRecipeFix fixes this from the server side.** It sends your server's recipes the same way a mod loader would — with **no client mod to install** and **no mod loader on the server**. Players just connect and it works.

## Features

- Restores recipe lookups in **JEI** (Fabric and NeoForge) and **REI** (Fabric).
- **Zero setup** — install the plugin and players just connect; recipes are sent automatically on join.
- Covers **vanilla, datapack and other plugins'** recipes, and refreshes automatically after a datapack reload.
- A **single jar** for the whole **1.21.3 → 26.2** range.
- Lightweight and dependency-free; stays silent for vanilla clients.

## How it works

The plugin reads the recipes your server already knows and delivers them to the client the same way a mod loader normally would, then asks the client to re-read them. It does **not** change crafting, add content, or affect gameplay — it only restores the recipe information that recipe viewers need in order to display it.

## What you'll see on join

JEI builds its recipe list the instant the server's own recipe packet arrives, and that happens before any plugin is allowed to send anything. So JEI still prints its warning once when you join:

> This … server does not provide recipes to JEI. JEI is showing default recipes from your client…

A moment later JEI reloads by itself, silently, with your server's recipes — that reload is this plugin doing its job. Because that warning is confusing once it is no longer true, the plugin follows it with one line of its own:

> Recipes synced. Ignore the JEI/REI/EMI warning above if present.

(Only JEI 30.8.0.51 / 29.14.0.43 and newer print that warning. Older builds pick the recipes up without complaining, and a server cannot see which version a player has — hence "any warning".)

**One warning followed by that line means everything worked.** If the warning appears *twice*, or JEI reports that the server sent unusable recipes, something did go wrong: turn on `debug` in `config.yml` and open an issue.

## Requirements

- A **Paper**, **Purpur** or **Folia** server on Minecraft **1.21.3–26.2**.
- Players use a **Fabric** or **NeoForge** client with **JEI** installed (as they already would).
- Fabric clients need **Minecraft 1.21.10 or newer**: Fabric API's recipe-sync channel does not exist on earlier versions, so there is nothing for the plugin to send recipes through.

### Other recipe viewers

- **EMI** has no published build for Minecraft 1.21.2 or newer yet, so it is untested.

### A note for REI users

REI does not read the recipe sync JEI uses; it builds its list from the server's recipe book, and a server normally sends only the recipes you have already unlocked. So for REI the plugin sends the **whole** recipe book, which has one visible side effect: your vanilla recipe book will list every recipe as known. Nothing is actually unlocked server-side and it is not a crafting exploit — the server still checks what you know when you click a recipe.

By default this is sent only to clients that report REI, which a server can only detect on Fabric. A NeoForge client running REI needs `recipe-book-sync: all`. Set it to `off` if you would rather keep the vanilla recipe book untouched, at the cost of REI showing your client's own recipes instead of the server's.

### Servers running ViaVersion / ViaBackwards

If your server lets players join on a **different Minecraft version than the server itself**, those players are handled differently — and they have to be.

The recipes travel on a channel ViaVersion has no schema for, so it forwards them without translating them. The data carries the server's *internal item numbers*, and those numbers shift with every Minecraft release. A client that reads them against its own numbering fails to decode them and drops the connection. Before version 0.4.0 this disconnected such players the instant they joined.

The plugin now looks up each player's real Minecraft version and holds the recipes back from anyone who is not on the server's own version. What they get instead is set by `cross-version-sync`:

| Value | Effect |
| --- | --- |
| `safe` *(default)* | No recipe payload, but still the full recipe book. **REI works normally for them.** JEI reads only the payload, so it shows no server recipes — those players get one chat line explaining why. Nobody is disconnected. |
| `off` | Those players are sent nothing at all. |
| `force` | Send everything regardless. **This disconnects JEI players on another version.** Only for a server where every client is on the server's own version. |

There is no way to serve **JEI** across versions: the recipe data is tied to the exact version that encoded it, and ViaVersion offers no hook to translate it. This is a permanent limitation, not a bug awaiting a fix.

Detection needs **ViaVersion installed on the server itself**. If yours runs on a proxy instead, this server cannot see a player's real version — set `cross-version-unknown-is-native: false` so those players are treated as being on another version. One case stays invisible either way: a player using a client-side translator such as ViaFabricPlus looks native to the server, and will still be disconnected. They need to join on the server's version.

## Commands

| Command | Description |
| --- | --- |
| `/jrf info` | Show status: recipe count, online players, sync state, send failures, and the server's protocol / how many players are on another version. |
| `/jrf resync [player\|all]` | Re-send recipes (useful after datapack changes). |
| `/jrf reload` | Reload the configuration. |

`/jeirecipefix` is the full command; `/jrf` is the alias. All commands require the `jeirecipefix.admin` permission (operators by default).

## Configuration

`config.yml` offers a few simple toggles:

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

`recipe-update-trigger` is what makes an already-running JEI re-read the recipes. It is only sent to clients that report both Fabric's recipe-sync channel and JEI, so other mods are left alone. Turning it off means recipes are still sent but JEI will keep showing your client's defaults.

`recipe-book-sync` decides who receives the full recipe book: `auto` (only clients reporting REI), `all` (every modded client, for viewers this plugin cannot detect), or `off`.

`cross-version-sync` and `cross-version-unknown-is-native` only matter on a server running ViaVersion — see [above](#servers-running-viaversion--viabackwards).

`explain-jei-warning` sends the one-line notice above, only to players whose client actually received the recipes and was identified as running a recipe viewer. Its wording lives in `messages.yml` under `jei-warning-notice`.

New settings and messages are written into your existing `config.yml` and `messages.yml` when you update; anything you have already changed is left alone.

## Note

This is an unofficial, third-party plugin. It is **not** affiliated with, or made by, the authors of JEI, REI or EMI.
