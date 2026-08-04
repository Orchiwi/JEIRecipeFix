# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added
- `unlock-recipes` (off by default) makes the craft-this button work — the one that moves a recipe's
  ingredients into the crafting grid, in the vanilla recipe book and in REI, which uses the same
  mechanism. The server only accepts that request for recipes it has marked as known for the player,
  so the button previously appeared but did nothing. The server still performs the move itself, with
  all its usual checks and events; the plugin adds no packet handling of its own.
- `/jrf revoke [player|all]` undoes it.

## [0.3.0] - 2026-08-04

### Added
- **REI support.** REI does not read the recipe sync JEI uses — it builds its list from the server's
  recipe book, and a server only ever sends the recipes a player has already unlocked, which is why
  REI showed nothing. The plugin now sends the full recipe book to clients that report REI, and REI
  shows the server's recipes, datapack and plugin ones included.
- `recipe-book-sync` in `config.yml` (`auto` / `all` / `off`) decides who receives it. `auto` limits
  it to clients reporting REI, because it also fills the player's own vanilla recipe book.
- New settings and messages are written into your existing `config.yml` and `messages.yml` on
  startup, with their comments; anything you have already set is left untouched.

### Fixed
- The confirmation in chat now reaches REI players too, not only JEI ones.
- The re-read trigger and the recipe book are sent even when the client reports its recipe viewer a
  moment after its recipes were delivered. Previously that decision was made once and never revisited,
  so whether a player was served came down to the order of a set on their client.
- Recipes are re-sent after respawn, where the server replaces the recipe book wholesale.

## [0.2.0] - 2026-08-03

### Fixed
- JEI showed *"This server does not provide recipes to JEI"* and kept using your client's own
  recipes. Since JEI 30.8.0.51 (26.2) and 29.14.0.43 (26.1), JEI builds its recipe list the moment
  the server's own recipe packet arrives — which is before any plugin can send anything — so the
  recipes this plugin sent a moment later were ignored for the rest of the session. The plugin now
  asks the client to re-read its recipes right after sending them, so JEI reloads with the server's
  recipes. JEI still prints its warning once at join; a silent JEI reload right after it means the
  recipes arrived.
- `/jrf resync` now reaches a running JEI on Fabric clients instead of quietly doing nothing.
- Recipes whose serializer is not a vanilla one are left out of the Fabric payload instead of making
  the client throw away every recipe.
- An empty recipe set is no longer sent; it made recipe viewers report unusable recipes.
- A recipe set too large for a single custom payload (over 1 MiB encoded) is no longer sent at all.
  Sending it disconnected the player while their client decoded it; the server log now says so.
- A message missing from an older `messages.yml` was sent to players as its own key
  (`jei-warning-notice`) instead of the actual text.
- `/jrf info` reported `active` even with `enabled: false`, which read as if everything was fine.

### Changed
- Recipes are now sent once the client has announced its plugin channels, so the plugin only sends
  what a given client can actually use.
- A single send failure no longer silences every later error for the lifetime of the server.
- The normal log now records the recipe count, payload size and whether the re-read trigger was sent.
- `/jrf info` reports the trigger state and the number of send failures.
- Removed 1.21.2 from the published version list: Paper never released a 1.21.2 build.

### Added
- `recipe-update-trigger` option in `config.yml` to turn the re-read trigger off.
- A one-line chat notice telling the player JEI's warning is out of date, sent only to clients that
  just received the recipes and are known to run JEI. Wording in `messages.yml`
  (`jei-warning-notice`), switch in `config.yml` (`explain-jei-warning`).
- `config.yml` and `messages.yml` are brought up to date on startup: settings and messages added in
  a newer version are written into your existing files, with their comments, and everything you have
  already set is left exactly as it was. The server log lists what was added.

## [0.1.0-beta.2] - 2026-06-23

### Added
- Support for Minecraft 26.2.

## [0.1.0-beta.1] - 2026-06-01

### Added
- Your server's recipes now show up again in JEI, REI and EMI on Paper, Purpur and Folia.
- Works automatically for Fabric and NeoForge clients — no client mod needed.
- Recipes update after a datapack reload.
- Admin commands to re-send recipes, reload settings, and check status.
