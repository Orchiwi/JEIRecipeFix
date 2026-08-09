# JEIRecipeFix Design Spec

- **Status:** Approved (design), pending implementation plan
- **Date:** 2026-06-01
- **Author:** Orchiwi
- **Type:** Paper/Purpur/Folia plugin
- **Initial version:** `0.1.0-beta.1`

## 1. Problem

Since Minecraft 1.21.2, the vanilla server no longer sends full recipe data to clients. It only sends recipe *property sets* (input restrictions) and recipe-book *displays* for unlocked recipes, not the ingredient-to-result definitions. As a result, the client recipe store is effectively empty, and client-side recipe viewers (JEI, REI, EMI) show nothing.

JEI's official fix is to install JEI on the server. JEI is a mod, so it cannot run on plugin-based servers (Paper/Spigot/Purpur/Folia). Those servers therefore have no way to feed recipes to the client viewers.

## 2. Goal

A single Paper-family plugin that makes the unmodified client recipe viewers work again by sending the server's recipes to each modded client over the network, with no client-side install and no server mod loader.

The plugin must run on **Paper, Purpur, and Folia** across **MC 1.21.2 to 26.1.2** (1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2) from a **single jar**.

### Non-goals
- Spigot support (different mappings, churns per version, no working reference; Paper is a drop-in replacement).
- The `jei:network` side channel (recipe-transfer "+" button, JEI cheat-mode give/delete). Independent of recipe display; deferred.
- Per-recipe-category toggles or any GUI.

## 3. Key insight (how it actually works)

JEI has **no** recipe-sync packet of its own. It reads recipes from the client recipe store that the **mod loader's** recipe-sync API normally populates. To make JEI/REI/EMI work, the plugin impersonates the **loader**, not JEI:

- **Fabric** clients -> clientbound custom payload on channel `fabric:recipe_sync`.
- **NeoForge** clients -> clientbound custom payload on channel `neoforge:recipe_content`, followed by a vanilla tags update packet.

The client loader's receiver decodes whatever arrives on its channel; the plugin does not need to answer the loader's serializer-list handshake; it simply sends all vanilla serializers, which every client supports.

Because this populates the loader's shared recipe store, it fixes **JEI, REI, and EMI** at once.

### What is synced
Everything in the server's recipe manager: crafting (shaped/shapeless), cooking (furnace/blast/smoker/campfire), stonecutting, smithing (transform + trim), i.e. vanilla + datapack + recipes registered by other plugins.

### What is deliberately not synced
Brewing, anvil, and furnace-fuel are **not** recipe-manager recipes in vanilla; JEI generates them client-side and still displays them. They neither need nor can be synced. Coverage is complete regardless.

## 4. Locked decisions

| Decision | Choice |
|---|---|
| Recipe scope | All server recipes (vanilla + datapacks + other plugins) |
| Control surface | Automatic for every JEI client + light `config.yml` + one admin re-sync command (permission-gated) |
| Loaders | Paper / Purpur / Folia |
| Distribution | Single jar, all versions in one file |
| Version strategy | One jar; recipe encoding delegated to the server's own codecs at runtime (no per-version build) |
| Name | JEIRecipeFix (Modrinth slug `jeirecipefix`, subject to availability) |

## 5. Version strategy (the single-jar enabler)

The recipe **wire envelope** (channel id + `serializer-id, count, {recipe-key, recipe-bytes}`) is stable across the range. The recipe **body encoding** changes per MC version.

The plugin embeds **no** recipe encoder. At runtime it calls the **server's own** stream codecs to encode each recipe into a `RegistryFriendlyByteBuf`. The server is always the running version, so the bytes are always correct for that version. This is what lets one jar cover 1.21.2 to 26.1.2.

This requires reflection into server internals (no paperweight / no compile-time NMS). It is viable on the Paper family because:
- The CraftBukkit classes use **unversioned packages** (`org.bukkit.craftbukkit.*`) since 1.20.5.
- The runtime is **Mojang-mapped**, so method names are stable across versions.

The only version-sensitive part is navigating the recipe-manager internals (1 to 2 known shapes across the range), handled by defensive reflection and confirmed by runtime testing on each version.

## 6. Architecture

Single jar. No third-party runtime dependencies (Paper API + JDK only). Manual DI in `onEnable()`.

```
fr.horizonsmp.jeirecipefix
├── JEIRecipeFix.java                  # JavaPlugin entry; wires everything
├── config/
│   ├── PluginConfig.java              # immutable record
│   └── ConfigLoader.java              # reads config.yml -> PluginConfig
├── command/JEIRecipeFixCommand.java   # /jeirecipefix (alias /jrf): resync, reload, info
├── listener/
│   ├── PlayerConnectionListener.java  # PlayerJoinEvent -> sync
│   └── ResourceReloadListener.java    # ServerResourcesReloadedEvent -> invalidate cache + resync
├── sync/
│   ├── RecipeSyncService.java         # orchestration + payload cache
│   ├── ClientBrand.java               # FABRIC / NEOFORGE / OTHER + detection
│   ├── FabricPayload.java             # builds the fabric:recipe_sync byte payload
│   └── NeoForgePayload.java           # builds the neoforge:recipe_content byte payload
├── nms/RecipeAccess.java              # reflection layer: recipes, codecs, buffer, packet send
└── i18n/Messages.java                 # messages.yml + Adventure
```

### Component responsibilities

- **JEIRecipeFix**: load config, register listeners and the command, build the service; expose `reload()`.
- **PluginConfig / ConfigLoader**: immutable view of `config.yml`; reload swaps it via `AtomicReference`.
- **RecipeSyncService**: decides who is synced, when; holds the cached encoded payloads (one per loader) in `AtomicReference<byte[]>`; rebuilds them lazily and on reload. Single place that talks to `RecipeAccess`.
- **ClientBrand**: maps `Player#getClientBrandName()` to FABRIC / NEOFORGE / OTHER.
- **FabricPayload / NeoForgePayload**: pure payload construction using `RecipeAccess` primitives.
- **RecipeAccess**: the only class touching server internals; isolates all reflection and the version-sensitive recipe-manager navigation.
- **Messages**: all player/console strings, loaded from `messages.yml`, rendered with Adventure (legacy `&` codes).

## 7. Data flow

1. Player joins (or an admin runs `resync`, or a datapack reload fires) -> `RecipeSyncService.syncTo(player)`.
2. Detect brand via `Player#getClientBrandName()` -> FABRIC / NEOFORGE / OTHER.
3. OTHER or null -> no-op (debug log; optional ~1s retry if the brand has not arrived yet).
4. FABRIC -> send cached payload on `fabric:recipe_sync`. NEOFORGE -> send cached payload on `neoforge:recipe_content`, then a tags update packet.
5. Payloads are built **once** and cached per loader. Recipes only change on datapack reload, so there is no re-encoding per connection. The cache is invalidated on `ServerResourcesReloadedEvent` and on `resync`.

## 8. Folia

Folia uses regionized threads. Packets are sent on the player's scheduler (`Entity#getScheduler()`), never on an arbitrary thread. Folia is detected at startup (presence of the regionized server class) and the appropriate scheduling path is chosen. The reference plugin JEIRecipeBridge runs on Folia, confirming feasibility.

## 9. Config, command, permissions

`config.yml`:

```yaml
# Master switch.
enabled: true
# Send recipes automatically when a player joins.
sync-on-join: true
# Re-send recipes to online players after a datapack reload.
sync-on-datapack-reload: true
# Verbose logging for troubleshooting (per-player brand + recipe counts).
debug: false
```

Command `/jeirecipefix` (alias `/jrf`):
- `resync [player|all]`: re-send recipes (useful after a datapack change).
- `reload`: reload `config.yml` and `messages.yml`.
- `info`: show status: detected loaders, recipe count, number of synced players.

Permissions (hierarchical, `op: true` on the parent):
- `jeirecipefix.admin` -> `jeirecipefix.command.resync`, `jeirecipefix.command.reload`, `jeirecipefix.command.info`.

All displayable strings live in `messages.yml` (English, `&` codes via `LegacyComponentSerializer.legacyAmpersand()`).

## 10. Error handling / robustness

- **Brand null at join**: clean skip + optional ~1s retry (the brand sometimes arrives just after join).
- **Unrecognized server internals**: log a single clear warning ("unsupported server internals, recipe sync disabled") then stay dormant; no spam, no crash, rest of the server unaffected.
- **Client not listening on the channel**: dropped client-side, harmless.
- **Payload size**: vanilla/datapack scale is a few hundred KB at most, well under limits; large payloads are supported by the custom-payload mechanism.

## 11. Build, versioning, Modrinth

- Gradle Kotlin DSL, skeleton adapted from GeoBlock. No third-party runtime deps -> no Shadow relocations to manage.
- Bytecode target **Java 21** (`options.release = 21`) so the single jar loads on 1.21.2 (Java 21) and on 26.1.2 (Java 25, forward-compatible). `api-version: '1.21'`.
- Version source of truth: `gradle.properties`. SemVer; `beta` marker stays until validated in production across the full version matrix.
- Modrinth (Minotaur): `projectId = "jeirecipefix"`, `loaders = listOf("paper", "purpur", "folia")`, `gameVersions` = the full list 1.21.2 to 26.1.2, no declared server dependency (JEI/REI/EMI are client-side).
- First Modrinth upload is manual (project creation by Orchiwi); subsequent releases via GitHub Actions on tag push.
- README is the Modrinth body; it states the plugin is an unofficial third-party plugin (not by the JEI author) and lists JEI/REI/EMI support.

### Changelog style
User-facing and feature-oriented, never build/protocol jargon. Example entry:

> Your server's recipes now show up again in JEI, REI and EMI.

## 12. Testing / done criteria

- `./gradlew build` passes.
- `./gradlew runServer` starts; log shows the plugin enabling with no plugin-side exceptions.
- Manual: a Fabric client with JEI shows server recipes again; same with REI and EMI; a NeoForge client with JEI shows recipes.
- A vanilla client triggers no error on join (no-op).
- The reflection layer is exercised at the range boundaries (at least 1.21.2, a mid 1.21.x, and 26.1.2).
- `resync` re-sends after a datapack change.

## 13. Risks

1. **Recipe-manager reflection across versions**: the dominant risk; mitigated by isolating it in `RecipeAccess`, defensive multi-shape navigation, and per-version runtime testing. Encoding itself is delegated to the server, so recipe-body churn is not a risk.
2. **Loader channel/wire changes**: `fabric:recipe_sync`, `fabric:recipe_sync/supported_serializers`, and `neoforge:recipe_content` are stable across the checked range; monitor future Fabric API revisions.
3. **Brand timing**: handled by null-check + retry.

## 14. References

- JEIRecipeBridge (primary reference, MIT, both loaders, Folia): https://github.com/Mrbysco/JEIRecipeBridge: Modrinth https://modrinth.com/plugin/jei-recipe-bridge
- JustEnoughPaper (Fabric-only reference): https://github.com/DerSimeon/JustEnoughPaper
- Fabric recipe sync API: https://github.com/FabricMC/fabric-api/tree/26.1.2/fabric-recipe-api-v1/src/main/java/net/fabricmc/fabric/api/recipe/v1/sync
- NeoForge recipe payload: https://github.com/neoforged/NeoForge/blob/1.21.x/src/main/java/net/neoforged/neoforge/network/payload/RecipeContentPayload.java: docs https://docs.neoforged.net/docs/resources/server/recipes/
- JEI repo (no recipe-sync packet of its own): https://github.com/mezz/JustEnoughItems
