# JEIRecipeFix

**Makes JEI work again on Paper, Purpur and Folia servers.**

Since Minecraft **1.21.2**, servers no longer send recipe data to clients. On a plugin-based server (Paper / Purpur / Folia) this means **JEI** shows your client's built-in recipes instead of the server's — it simply can't see what the server can craft. JEI's own recommendation is to install JEI on the server, but JEI is a mod and cannot run on a plugin server.

**JEIRecipeFix fixes this from the server side.** It sends your server's recipes the same way a mod loader would — with **no client mod to install** and **no mod loader on the server**. Players just connect with JEI and it works.

## Features

- Restores recipe lookups in **JEI**, on **Fabric** and **NeoForge** clients.
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

> Recipes synced. Your recipe viewer is showing the recipes this server uses — ignore any warning above that says otherwise.

(Only JEI 30.8.0.51 / 29.14.0.43 and newer print that warning. Older builds pick the recipes up without complaining, and a server cannot see which version a player has — hence "any warning".)

**One warning followed by that line means everything worked.** If the warning appears *twice*, or JEI reports that the server sent unusable recipes, something did go wrong: turn on `debug` in `config.yml` and open an issue.

## Requirements

- A **Paper**, **Purpur** or **Folia** server on Minecraft **1.21.3–26.2**.
- Players use a **Fabric** or **NeoForge** client with **JEI** installed (as they already would).
- Fabric clients need **Minecraft 1.21.10 or newer**: Fabric API's recipe-sync channel does not exist on earlier versions, so there is nothing for the plugin to send recipes through.

### Other recipe viewers

- **REI** is not supported. It does not read the mod loader's recipe sync — it uses its own protocol, which only a REI server mod can speak.
- **EMI** has no published build for Minecraft 1.21.2 or newer yet, so it is untested.

## Commands

| Command | Description |
| --- | --- |
| `/jrf info` | Show status: recipe count, online players, sync state, send failures. |
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
explain-jei-warning: true
debug: false
```

`recipe-update-trigger` is what makes an already-running JEI re-read the recipes. It is only sent to clients that report both Fabric's recipe-sync channel and JEI, so other mods are left alone. Turning it off means recipes are still sent but JEI will keep showing your client's defaults.

`explain-jei-warning` sends the one-line notice above, to those same clients only. Its wording lives in `messages.yml` under `jei-warning-notice`.

## Note

This is an unofficial, third-party plugin. It is **not** affiliated with, or made by, the authors of JEI, REI or EMI.
