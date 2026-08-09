# Changelog

All notable changes to this project are documented here.

## [0.4.0] - 2026-08-09

### Fixed
- Players on a different Minecraft version than the server were kicked the moment they joined, on
  servers running ViaVersion/ViaBackwards. The recipes carry the server's internal item numbers,
  which shift with every release, and ViaVersion cannot translate the channel they travel on. The
  plugin now checks each player's real version and holds the recipes back from anyone not on the
  server's.

### Added
- `cross-version-sync` (`safe` / `off` / `force`) sets what those players get. `safe` still sends the
  recipe book, so REI works for them; JEI shows nothing and the player is told why.
- `cross-version-unknown-is-native`, for servers whose ViaVersion runs on the proxy.
- `/jrf info` reports the server protocol, whether ViaVersion was found, and how many players are on
  another version.

### Changed
- Recipe-book packets are batched at 128 KiB instead of 512 KiB. ViaBackwards rewrites them on the
  way out and can only make them bigger.
- `recipe-book-sync: all` no longer re-sends the recipe book on respawn to clients that never got it,
  such as vanilla ones.

## [0.3.0] - 2026-08-04

### Added
- REI support. REI ignores the sync JEI uses and builds its list from the recipe book, which only
  holds what a player has unlocked, so REI showed nothing. The full recipe book is now sent to
  clients reporting REI, datapack and plugin recipes included.
- `recipe-book-sync` (`auto` / `all` / `off`) decides who receives it. `auto` limits it to REI
  clients, since it also fills the player's own recipe book.
- New settings and messages are added to your existing `config.yml` and `messages.yml` on startup,
  with their comments. Anything you set is left untouched.

### Fixed
- The chat confirmation now reaches REI players, not only JEI ones.
- The re-read trigger and the recipe book are sent even when the client reports its viewer just after
  its recipes were delivered. That decision used to be made once and never revisited.
- Recipes are re-sent after respawn, where the server replaces the recipe book wholesale.

## [0.2.0] - 2026-08-03

### Fixed
- JEI showed *"This server does not provide recipes to JEI"* and kept using your client's recipes.
  Since JEI 30.8.0.51 (26.2) and 29.14.0.43 (26.1) it builds its list the moment the server's own
  recipe packet arrives, which is before any plugin can send anything, so what the plugin sent a
  moment later was ignored for the session. The client is now asked to re-read its recipes right
  after they are sent. JEI still prints the warning once; a silent reload after it means they landed.
- `/jrf resync` now reaches a running JEI on Fabric instead of quietly doing nothing.
- Recipes with a non-vanilla serializer are left out of the Fabric payload rather than making the
  client discard every recipe.
- An empty recipe set is no longer sent; it made viewers report unusable recipes.
- A recipe set over 1 MiB encoded is no longer sent at all. Sending it disconnected the player while
  their client decoded it. The log now says so.
- A message missing from an older `messages.yml` was sent to players as its own key.
- `/jrf info` reported `active` even with `enabled: false`.

### Changed
- Recipes are sent once the client has announced its plugin channels, so only what it can use is sent.
- One send failure no longer silences every later error for the life of the server.
- The log records the recipe count, payload size and whether the re-read trigger was sent.
- `/jrf info` reports the trigger state and send failures.
- Dropped 1.21.2 from the published version list: Paper never released a 1.21.2 build.

### Added
- `recipe-update-trigger` in `config.yml` to turn the re-read trigger off.
- A one-line chat notice telling the player JEI's warning is out of date, sent only to clients that
  just received the recipes and are known to run JEI. Wording in `messages.yml`, switch in
  `config.yml` (`explain-jei-warning`).
- `config.yml` and `messages.yml` are brought up to date on startup, with comments, leaving your own
  values alone. The log lists what was added.

## [0.1.0-beta.2] - 2026-06-23

### Added
- Support for Minecraft 26.2.

## [0.1.0-beta.1] - 2026-06-01

### Added
- Your server's recipes now show up again in JEI, REI and EMI on Paper, Purpur and Folia.
- Works automatically for Fabric and NeoForge clients, with no client mod needed.
- Recipes update after a datapack reload.
- Admin commands to re-send recipes, reload settings, and check status.
