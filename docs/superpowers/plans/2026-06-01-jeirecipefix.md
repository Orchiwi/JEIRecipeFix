# JEIRecipeFix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Paper/Purpur/Folia plugin (MC 1.21.2 to 26.1.2) that re-sends the server's recipes to modded clients so JEI/REI/EMI work again, with no client mod and no server mod loader.

**Architecture:** The plugin impersonates the client's mod *loader* recipe-sync (Fabric `fabric:recipe_sync` / NeoForge `neoforge:recipe_content`), detected per player via the client brand. Recipes are read from the server's own recipe manager and encoded with the server's own stream codecs through a reflection layer, so one jar adapts to every version in range. Pure logic (brand detection, config, command routing, payload caching) is TDD-unit-tested; the reflection/wire layer is verified at runtime against a real modded client.

**Tech Stack:** Java 21 (release target), Gradle Kotlin DSL, Paper API (compileOnly, baseline 1.21.2), Netty buffer (compileOnly), JUnit 5, run-paper, Modrinth Minotaur. No third-party runtime dependencies, so no shading.

**Branch:** Implement on `feature/jeirecipefix`. Each task ends with an atomic commit on that branch. Author identity is already configured (Orchiwi). Never add a Claude co-author trailer. Never `--no-verify`.

**Deviations from the spec (intentional, YAGNI/cohesion):**
- The spec listed `sync/FabricPayload.java` and `sync/NeoForgePayload.java` as separate classes. They are consolidated as private `buildFabricPayload()` / `buildNeoForgePayload()` methods inside `nms/NmsRecipeBridge.java`, because they share the entire reflection context; splitting would only add a context-passing abstraction with no payoff.
- A tiny generic `util/Lazy.java` provides the per-loader payload cache instead of bare `AtomicReference` plumbing in the service, so the caching is unit-testable in isolation.

**Testing reality:** Reflection into server internals and exact loader wire formats cannot be meaningfully unit-tested without a running server; their acceptance test is a live modded client showing recipes (Tasks 7, 8, 12). Everything that *can* be unit-tested is, TDD-first (Tasks 2 to 4, 6, 10).

---

## File Structure

Created files and their single responsibility:

- `settings.gradle.kts`: project name.
- `build.gradle.kts`: build, test, runServer, Modrinth publishing.
- `gradle.properties`: version source of truth.
- `gradle/libs.versions.toml`: dependency/plugin versions.
- `gradle/wrapper/*`, `gradlew`, `gradlew.bat`: Gradle wrapper (copied from GeoBlock).
- `.gitignore`, `LICENSE`: copied from GeoBlock (LICENSE year/owner already Orchiwi).
- `README.md`: Modrinth body; what it does, supported loaders/versions, unofficial-plugin notice.
- `CHANGELOG.md`: Keep a Changelog format; user-facing wording.
- `.github/workflows/release.yml`: tag-triggered Modrinth release.
- `src/main/resources/plugin.yml`: name, command, permission tree, Folia flag.
- `src/main/resources/config.yml`: the 4 light toggles.
- `src/main/resources/messages.yml`: all displayable strings.
- `src/main/java/fr/horizonsmp/jeirecipefix/JEIRecipeFix.java`: JavaPlugin entry; manual DI.
- `.../config/PluginConfig.java`: immutable config record.
- `.../config/ConfigLoader.java`: `ConfigurationSection` -> `PluginConfig`.
- `.../sync/ClientBrand.java`: brand string -> FABRIC/NEOFORGE/OTHER.
- `.../sync/RecipeSyncService.java`: orchestration, per-loader cached payloads, resync.
- `.../util/Lazy.java`: thread-safe memoize-with-invalidate.
- `.../nms/RecipeBridge.java`: interface the service depends on (fakeable in tests).
- `.../nms/NmsRecipeBridge.java`: reflection impl: read recipes, encode via server codecs, send.
- `.../nms/Reflect.java`: small reflection helper.
- `.../listener/PlayerConnectionListener.java`: sync on join (+ one delayed retry).
- `.../listener/ResourceReloadListener.java`: datapack reload -> invalidate + resync.
- `.../command/CommandRouter.java`: pure args -> action.
- `.../command/JEIRecipeFixCommand.java`: command executor + tab completion glue.
- `.../i18n/Messages.java`: messages.yml + Adventure legacy `&`.
- `src/test/java/...`: JUnit tests mirroring the testable classes.

---

## Task 1: Project bootstrap (build + skeleton + empty plugin enables)

**Files:**
- Create (copy from `~/Minecraft_Plugins/GeoBlock`): `gradle/wrapper/`, `gradlew`, `gradlew.bat`, `.gitignore`, `LICENSE`.
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `src/main/resources/plugin.yml`, `src/main/java/fr/horizonsmp/jeirecipefix/JEIRecipeFix.java`.

- [ ] **Step 1: Create the feature branch**

Run:
```bash
git checkout -b feature/jeirecipefix
```

- [ ] **Step 2: Copy the reusable skeleton from GeoBlock**

Run:
```bash
cd /home/orchiwi/Minecraft_Plugins/Fix_JEI_recipes
cp -r ../GeoBlock/gradle/wrapper gradle/wrapper
cp ../GeoBlock/gradlew ../GeoBlock/gradlew.bat .
cp ../GeoBlock/.gitignore ../GeoBlock/LICENSE .
chmod +x gradlew
```
The GeoBlock `LICENSE` is already MIT / Orchiwi; leave it. (If its year is older than 2026, update the year line to `2026`.)

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "JEIRecipeFix"
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
group=fr.horizonsmp
version=0.1.0-beta.1

org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 5: Write `gradle/libs.versions.toml`**

```toml
[versions]
paper-api = "1.21.2-R0.1-SNAPSHOT"
minecraft = "1.21.4"
netty = "4.1.115.Final"
junit = "5.11.3"
junit-launcher = "1.11.3"

run-task = "3.0.2"
minotaur = "2.8.7"

[libraries]
paper-api = { module = "io.papermc.paper:paper-api", version.ref = "paper-api" }
netty-buffer = { module = "io.netty:netty-buffer", version.ref = "netty" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit-launcher" }

[plugins]
run-paper = { id = "xyz.jpenilla.run-paper", version.ref = "run-task" }
minotaur = { id = "com.modrinth.minotaur", version.ref = "minotaur" }
```

Rationale: compile against the **floor** paper-api (1.21.2) so we never accidentally call an API added later; `release = 21` makes the jar load on every server in range. `runServer` uses a mid version (1.21.4) for local testing. No shadow plugin: we have no runtime deps.

- [ ] **Step 6: Write `build.gradle.kts`**

```kotlin
plugins {
    id("java-library")
    alias(libs.plugins.run.paper)
    alias(libs.plugins.minotaur)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.netty.buffer)

    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    // JDK 25 is the installed toolchain; options.release = 21 below emits Java 21
    // bytecode so the single jar still loads on 1.21.2 servers (which run on Java 21).
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.release = 21
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("")
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set("jeirecipefix")
    versionNumber.set(project.version.toString())
    versionType.set(resolveVersionType(project.version.toString()))
    uploadFile.set(tasks.jar.flatMap { it.archiveFile })
    gameVersions.addAll(
        "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7",
        "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2"
    )
    loaders.addAll("paper", "purpur", "folia")
    changelog.set(provider { extractChangelogSection(project.version.toString()) })
    syncBodyFrom.set(provider { file("README.md").readText() })
}

fun resolveVersionType(version: String): String = when {
    version.contains("alpha") -> "alpha"
    version.contains("beta") -> "beta"
    else -> "release"
}

fun extractChangelogSection(version: String): String {
    val file = file("CHANGELOG.md")
    if (!file.exists()) return ""
    val out = StringBuilder()
    var capture = false
    for (line in file.readLines()) {
        if (line.startsWith("## ")) {
            if (capture) break
            capture = line.contains(version)
            continue
        }
        if (capture) out.appendLine(line)
    }
    return out.toString().trim()
}
```

- [ ] **Step 7: Write `src/main/resources/plugin.yml`**

```yaml
name: JEIRecipeFix
version: '${version}'
description: Sends server recipes to JEI, REI and EMI clients so recipe lookups work again.
author: Orchiwi

main: fr.horizonsmp.jeirecipefix.JEIRecipeFix
api-version: '1.21'
load: POSTWORLD
folia-supported: true

commands:
  jeirecipefix:
    description: JEIRecipeFix administration commands.
    usage: /<command> [resync|reload|info|help]
    aliases: [jrf]
    permission: jeirecipefix.admin

permissions:
  jeirecipefix.admin:
    description: Full access to JEIRecipeFix commands.
    default: op
    children:
      jeirecipefix.command.resync: true
      jeirecipefix.command.reload: true
      jeirecipefix.command.info: true
  jeirecipefix.command.resync:
    description: Allows re-sending recipes to players.
    default: op
  jeirecipefix.command.reload:
    description: Allows reloading the JEIRecipeFix configuration.
    default: op
  jeirecipefix.command.info:
    description: Allows viewing JEIRecipeFix status.
    default: op
```

- [ ] **Step 8: Write the minimal `JEIRecipeFix.java`**

```java
package fr.horizonsmp.jeirecipefix;

import org.bukkit.plugin.java.JavaPlugin;

public final class JEIRecipeFix extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("JEIRecipeFix enabled.");
    }
}
```

- [ ] **Step 9: Verify the build passes**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. A jar appears at `build/libs/JEIRecipeFix-0.1.0-beta.1.jar`.

- [ ] **Step 10: Verify the plugin enables at runtime**

Run: `./gradlew runServer` (first run: set `run/eula.txt` to `eula=true`, then re-run).
Expected: log contains `Done (` and `[JEIRecipeFix] JEIRecipeFix enabled.` with no plugin-side exception. Stop with `pgrep -af "paper.*\.jar"` then `kill -TERM <pid>` (never `kill -9`). The trailing `> Task :runServer FAILED` after SIGTERM is normal.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "build: bootstrap JEIRecipeFix plugin skeleton" -m "Gradle Kotlin DSL build targeting Java 21 bytecode against the 1.21.2 Paper API floor, single jar, no shading; minimal plugin that enables cleanly on Paper."
```

---

## Task 2: ClientBrand detection (TDD)

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/sync/ClientBrand.java`
- Test: `src/test/java/fr/horizonsmp/jeirecipefix/sync/ClientBrandTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.horizonsmp.jeirecipefix.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBrandTest {

    @Test
    void detectsFabric() {
        assertEquals(ClientBrand.FABRIC, ClientBrand.fromBrand("fabric"));
        assertEquals(ClientBrand.FABRIC, ClientBrand.fromBrand("Fabric"));
    }

    @Test
    void detectsNeoForge() {
        assertEquals(ClientBrand.NEOFORGE, ClientBrand.fromBrand("neoforge"));
    }

    @Test
    void treatsVanillaAndNullAsOther() {
        assertEquals(ClientBrand.OTHER, ClientBrand.fromBrand("vanilla"));
        assertEquals(ClientBrand.OTHER, ClientBrand.fromBrand(null));
    }

    @Test
    void onlyFabricAndNeoForgeAreSupported() {
        assertTrue(ClientBrand.FABRIC.isSupported());
        assertTrue(ClientBrand.NEOFORGE.isSupported());
        assertFalse(ClientBrand.OTHER.isSupported());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.sync.ClientBrandTest"`
Expected: FAIL, `ClientBrand` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

```java
package fr.horizonsmp.jeirecipefix.sync;

import java.util.Locale;

public enum ClientBrand {
    FABRIC,
    NEOFORGE,
    OTHER;

    public static ClientBrand fromBrand(String brand) {
        if (brand == null) {
            return OTHER;
        }
        String normalized = brand.toLowerCase(Locale.ROOT);
        if (normalized.contains("fabric")) {
            return FABRIC;
        }
        if (normalized.contains("neoforge")) {
            return NEOFORGE;
        }
        return OTHER;
    }

    public boolean isSupported() {
        return this == FABRIC || this == NEOFORGE;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.sync.ClientBrandTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: detect client loader brand for recipe sync"
```

---

## Task 3: Config record + loader (TDD)

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/config/PluginConfig.java`
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/config/ConfigLoader.java`
- Create: `src/main/resources/config.yml`
- Test: `src/test/java/fr/horizonsmp/jeirecipefix/config/ConfigLoaderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void readsValuesFromSection() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enabled: false
                sync-on-join: false
                sync-on-datapack-reload: true
                debug: true
                """);

        PluginConfig config = ConfigLoader.fromSection(yaml);

        assertFalse(config.enabled());
        assertFalse(config.syncOnJoin());
        assertTrue(config.syncOnDatapackReload());
        assertTrue(config.debug());
    }

    @Test
    void fallsBackToDefaultsForMissingKeysAndNullSection() {
        PluginConfig fromNull = ConfigLoader.fromSection(null);
        assertEquals(PluginConfig.defaults(), fromNull);

        YamlConfiguration empty = new YamlConfiguration();
        PluginConfig fromEmpty = ConfigLoader.fromSection(empty);
        assertTrue(fromEmpty.enabled());
        assertTrue(fromEmpty.syncOnJoin());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.config.ConfigLoaderTest"`
Expected: FAIL, `PluginConfig` / `ConfigLoader` do not exist.

- [ ] **Step 3: Write `PluginConfig.java`**

```java
package fr.horizonsmp.jeirecipefix.config;

public record PluginConfig(
        boolean enabled,
        boolean syncOnJoin,
        boolean syncOnDatapackReload,
        boolean debug) {

    public static PluginConfig defaults() {
        return new PluginConfig(true, true, true, false);
    }
}
```

- [ ] **Step 4: Write `ConfigLoader.java`**

```java
package fr.horizonsmp.jeirecipefix.config;

import org.bukkit.configuration.ConfigurationSection;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginConfig fromSection(ConfigurationSection section) {
        PluginConfig d = PluginConfig.defaults();
        if (section == null) {
            return d;
        }
        return new PluginConfig(
                section.getBoolean("enabled", d.enabled()),
                section.getBoolean("sync-on-join", d.syncOnJoin()),
                section.getBoolean("sync-on-datapack-reload", d.syncOnDatapackReload()),
                section.getBoolean("debug", d.debug()));
    }
}
```

- [ ] **Step 5: Write `src/main/resources/config.yml`**

```yaml
# JEIRecipeFix configuration.

# Master switch. When false, no recipes are sent.
enabled: true

# Send recipes automatically when a player joins.
sync-on-join: true

# Re-send recipes to online players after a datapack reload.
sync-on-datapack-reload: true

# Verbose logging for troubleshooting (per-player brand + recipe counts).
debug: false
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.config.ConfigLoaderTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: load plugin configuration from config.yml"
```

---

## Task 4: Lazy payload cache (TDD)

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/util/Lazy.java`
- Test: `src/test/java/fr/horizonsmp/jeirecipefix/util/LazyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.horizonsmp.jeirecipefix.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LazyTest {

    @Test
    void computesOnceUntilInvalidated() {
        AtomicInteger calls = new AtomicInteger();
        Lazy<Integer> lazy = new Lazy<>(calls::incrementAndGet);

        assertEquals(1, lazy.get());
        assertEquals(1, lazy.get());
        assertEquals(1, calls.get());

        lazy.invalidate();

        assertEquals(2, lazy.get());
        assertEquals(2, calls.get());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.util.LazyTest"`
Expected: FAIL, `Lazy` does not exist.

- [ ] **Step 3: Write `Lazy.java`**

```java
package fr.horizonsmp.jeirecipefix.util;

import java.util.function.Supplier;

public final class Lazy<T> {

    private final Supplier<T> supplier;
    private volatile boolean computed;
    private volatile T value;

    public Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public T get() {
        if (!computed) {
            synchronized (this) {
                if (!computed) {
                    value = supplier.get();
                    computed = true;
                }
            }
        }
        return value;
    }

    public synchronized void invalidate() {
        value = null;
        computed = false;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.util.LazyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add lazy memoize-with-invalidate helper"
```

---

## Task 5: RecipeBridge interface (no behavior yet)

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/nms/RecipeBridge.java`

This is the seam that keeps the service testable. No test of its own (it is an interface); it is exercised through the service tests (Task 6) with a fake and at runtime (Task 7) with the real impl.

- [ ] **Step 1: Write the interface**

```java
package fr.horizonsmp.jeirecipefix.nms;

import org.bukkit.entity.Player;

public interface RecipeBridge {

    /** True when server internals were resolved and recipe sync can run. */
    boolean isAvailable();

    /** Number of recipes that will be sent (for status/logging). */
    int recipeCount();

    /** Encodes the full recipe set in the Fabric {@code fabric:recipe_sync} wire format. */
    byte[] buildFabricPayload();

    /** Encodes the full recipe set in the NeoForge {@code neoforge:recipe_content} wire format. */
    byte[] buildNeoForgePayload();

    /** Sends a pre-built Fabric payload to the player on the correct thread. */
    void sendFabric(Player player, byte[] payload);

    /** Sends a pre-built NeoForge payload (plus the tags packet) to the player on the correct thread. */
    void sendNeoForge(Player player, byte[] payload);
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: define recipe bridge seam between service and server internals"
```

---

## Task 6: RecipeSyncService (TDD with a fake bridge)

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/sync/RecipeSyncService.java`
- Test: `src/test/java/fr/horizonsmp/jeirecipefix/sync/RecipeSyncServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeSyncServiceTest {

    private static final byte[] FABRIC_BYTES = {1, 2, 3};

    private final AtomicInteger fabricBuilds = new AtomicInteger();

    private RecipeBridge bridge(boolean available) {
        return new RecipeBridge() {
            @Override public boolean isAvailable() { return available; }
            @Override public int recipeCount() { return 7; }
            @Override public byte[] buildFabricPayload() { fabricBuilds.incrementAndGet(); return FABRIC_BYTES; }
            @Override public byte[] buildNeoForgePayload() { return new byte[]{9}; }
            @Override public void sendFabric(Player player, byte[] payload) { }
            @Override public void sendNeoForge(Player player, byte[] payload) { }
        };
    }

    private RecipeSyncService service(boolean available, PluginConfig config) {
        return new RecipeSyncService(bridge(available), () -> config, null, Logger.getAnonymousLogger());
    }

    @Test
    void shouldSyncOnlyWhenEnabledAvailableAndSupportedBrand() {
        RecipeSyncService enabled = service(true, PluginConfig.defaults());
        assertTrue(enabled.shouldSync(ClientBrand.FABRIC));
        assertTrue(enabled.shouldSync(ClientBrand.NEOFORGE));
        assertFalse(enabled.shouldSync(ClientBrand.OTHER));

        RecipeSyncService disabled = service(true, new PluginConfig(false, true, true, false));
        assertFalse(disabled.shouldSync(ClientBrand.FABRIC));

        RecipeSyncService unavailable = service(false, PluginConfig.defaults());
        assertFalse(unavailable.shouldSync(ClientBrand.FABRIC));
    }

    @Test
    void cachesPayloadUntilInvalidated() {
        RecipeSyncService service = service(true, PluginConfig.defaults());

        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC));
        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC));
        assertTrue(fabricBuilds.get() == 1);

        service.invalidate();
        assertArrayEquals(FABRIC_BYTES, service.payloadFor(ClientBrand.FABRIC));
        assertTrue(fabricBuilds.get() == 2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.sync.RecipeSyncServiceTest"`
Expected: FAIL, `RecipeSyncService` does not exist.

- [ ] **Step 3: Write `RecipeSyncService.java`**

```java
package fr.horizonsmp.jeirecipefix.sync;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import fr.horizonsmp.jeirecipefix.util.Lazy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;
import java.util.logging.Logger;

public final class RecipeSyncService {

    private final RecipeBridge bridge;
    private final Supplier<PluginConfig> config;
    private final Plugin plugin;
    private final Logger logger;
    private final Lazy<byte[]> fabricPayload;
    private final Lazy<byte[]> neoForgePayload;

    public RecipeSyncService(RecipeBridge bridge, Supplier<PluginConfig> config, Plugin plugin, Logger logger) {
        this.bridge = bridge;
        this.config = config;
        this.plugin = plugin;
        this.logger = logger;
        this.fabricPayload = new Lazy<>(bridge::buildFabricPayload);
        this.neoForgePayload = new Lazy<>(bridge::buildNeoForgePayload);
    }

    public boolean shouldSync(ClientBrand brand) {
        return config.get().enabled() && bridge.isAvailable() && brand.isSupported();
    }

    public byte[] payloadFor(ClientBrand brand) {
        return switch (brand) {
            case FABRIC -> fabricPayload.get();
            case NEOFORGE -> neoForgePayload.get();
            default -> null;
        };
    }

    /** Sends recipes to one player if applicable. Returns true if a payload was sent. */
    public boolean syncTo(Player player) {
        ClientBrand brand = ClientBrand.fromBrand(player.getClientBrandName());
        if (!shouldSync(brand)) {
            if (config.get().debug()) {
                logger.info("Skipping recipe sync for " + player.getName() + " (brand=" + brand + ")");
            }
            return false;
        }
        byte[] payload = payloadFor(brand);
        if (payload == null) {
            return false;
        }
        switch (brand) {
            case FABRIC -> bridge.sendFabric(player, payload);
            case NEOFORGE -> bridge.sendNeoForge(player, payload);
            default -> {
                return false;
            }
        }
        if (config.get().debug()) {
            logger.info("Synced " + bridge.recipeCount() + " recipes to " + player.getName() + " (brand=" + brand + ")");
        }
        return true;
    }

    public void resyncAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> syncTo(player), null);
        }
    }

    public void invalidate() {
        fabricPayload.invalidate();
        neoForgePayload.invalidate();
    }

    public boolean available() {
        return bridge.isAvailable();
    }

    public int recipeCount() {
        return bridge.recipeCount();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.sync.RecipeSyncServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: orchestrate recipe sync with per-loader payload caching"
```

---

## Task 7: NMS Fabric path + join listener + wiring first end-to-end sync (MVP)

This is the core and the highest-risk task. The acceptance test is a **live Fabric client with JEI** showing recipes, not a unit test. While implementing `NmsRecipeBridge`, keep the reference open: **`Mrbysco/JEIRecipeBridge`** payload classes (linked in the spec) show the exact Fabric framing in typed NMS; this task reproduces it via reflection. The Fabric wire format (from `fabric-recipe-api-v1` `ClientboundRecipeSyncPayload`): a `List<Entry>` where each `Entry` = `Identifier serializerId` + `VarInt count` + `count * { ResourceKey<Recipe> id, recipe via that serializer's stream codec }`.

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/nms/Reflect.java`
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/nms/NmsRecipeBridge.java`
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/listener/PlayerConnectionListener.java`
- Modify: `src/main/java/fr/horizonsmp/jeirecipefix/JEIRecipeFix.java`

- [ ] **Step 1: Write the reflection helper `Reflect.java`**

```java
package fr.horizonsmp.jeirecipefix.nms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {

    private Reflect() {
    }

    static Class<?> clazz(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing class " + name, e);
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored2) {
                    // keep walking up
                }
            }
            throw new IllegalStateException("Missing method " + owner.getName() + "#" + name);
        }
    }

    static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Invoke failed: " + m, e);
        }
    }

    static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Missing field " + name + " on " + target.getClass());
    }

    static Object staticField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Missing static field " + owner.getName() + "#" + name, e);
        }
    }
}
```

- [ ] **Step 2: Write `NmsRecipeBridge.java` (Fabric path + send)**

The class resolves all handles in the constructor; any failure sets `available=false` so the rest of the server is unaffected. Method/field names are Mojang-mapped (valid on Paper/Purpur/Folia). Lines marked `// VERIFY` are the version-sensitive points to confirm against the running server and the JEIRecipeBridge reference; the runtime acceptance test (Step 6) is what actually validates the bytes.

```java
package fr.horizonsmp.jeirecipefix.nms;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NmsRecipeBridge implements RecipeBridge {

    private static final String FABRIC_CHANNEL = "fabric:recipe_sync";
    private static final String NEOFORGE_CHANNEL = "neoforge:recipe_content";

    private final Plugin plugin;
    private boolean available;

    // Resolved handles.
    private Object minecraftServer;        // net.minecraft.server.MinecraftServer
    private Object registryAccess;         // net.minecraft.core.RegistryAccess (frozen)
    private Object serializerRegistry;     // BuiltInRegistries.RECIPE_SERIALIZER
    private Method registryGetKey;         // Registry#getKey(Object) -> ResourceLocation
    private Method getRecipes;             // RecipeManager#getRecipes() -> Collection<RecipeHolder>
    private Method holderId;               // RecipeHolder#id()
    private Method holderValue;            // RecipeHolder#value()
    private Method recipeGetSerializer;    // Recipe#getSerializer()
    private Method serializerStreamCodec;  // RecipeSerializer#streamCodec()
    private Method streamCodecEncode;      // StreamCodec#encode(Object buf, Object value)
    private Method bufWriteVarInt;         // FriendlyByteBuf#writeVarInt(int)
    private Method bufWriteResourceLocation; // FriendlyByteBuf#writeResourceLocation(ResourceLocation)
    private Method resourceKeyLocation;    // ResourceKey#location() -> ResourceLocation
    private Constructor<?> registryBufCtor; // RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)
    private Object recipesCache;           // cached Collection<RecipeHolder>
    private int recipeCount;

    // For sending.
    private Method getHandle;              // CraftPlayer#getHandle()
    private Object connectionField;        // resolved per player at send time
    private Constructor<?> customPayloadPacketCtor;
    private Constructor<?> discardedPayloadCtor;
    private Object fabricPayloadId;         // ResourceLocation for FABRIC_CHANNEL
    private Object neoForgePayloadId;       // ResourceLocation for NEOFORGE_CHANNEL

    public NmsRecipeBridge(Plugin plugin) {
        this.plugin = plugin;
        try {
            resolveHandles();
            this.available = true;
        } catch (RuntimeException e) {
            this.available = false;
            plugin.getLogger().warning("Could not resolve server internals: " + e.getMessage());
        }
    }

    private void resolveHandles() {
        Object craftServer = org.bukkit.Bukkit.getServer();
        Method getServer = Reflect.method(craftServer.getClass(), "getServer");
        this.minecraftServer = Reflect.call(getServer, craftServer);

        Method registryAccessMethod = Reflect.method(minecraftServer.getClass(), "registryAccess"); // VERIFY name
        this.registryAccess = Reflect.call(registryAccessMethod, minecraftServer);

        Method getRecipeManager = Reflect.method(minecraftServer.getClass(), "getRecipeManager"); // VERIFY name
        Object recipeManager = Reflect.call(getRecipeManager, minecraftServer);
        this.getRecipes = Reflect.method(recipeManager.getClass(), "getRecipes"); // VERIFY: returns Collection<RecipeHolder>
        java.util.Collection<?> recipes = (java.util.Collection<?>) Reflect.call(getRecipes, recipeManager);
        this.recipesCache = recipes;
        this.recipeCount = recipes.size();

        Class<?> builtInRegistries = Reflect.clazz("net.minecraft.core.registries.BuiltInRegistries");
        this.serializerRegistry = Reflect.staticField(builtInRegistries, "RECIPE_SERIALIZER"); // VERIFY field name
        this.registryGetKey = Reflect.method(serializerRegistry.getClass(), "getKey", Object.class);

        Object sampleHolder = recipes.iterator().next();
        this.holderId = Reflect.method(sampleHolder.getClass(), "id");
        this.holderValue = Reflect.method(sampleHolder.getClass(), "value");
        Object sampleRecipe = Reflect.call(holderValue, sampleHolder);
        this.recipeGetSerializer = Reflect.method(sampleRecipe.getClass(), "getSerializer");
        Object sampleSerializer = Reflect.call(recipeGetSerializer, sampleRecipe);
        this.serializerStreamCodec = Reflect.method(sampleSerializer.getClass(), "streamCodec");
        Object sampleCodec = Reflect.call(serializerStreamCodec, sampleSerializer);
        this.streamCodecEncode = Reflect.method(sampleCodec.getClass(), "encode", Object.class, Object.class);

        Class<?> friendlyByteBuf = Reflect.clazz("net.minecraft.network.FriendlyByteBuf");
        this.bufWriteVarInt = Reflect.method(friendlyByteBuf, "writeVarInt", int.class);
        Class<?> resourceLocation = Reflect.clazz("net.minecraft.resources.ResourceLocation");
        this.bufWriteResourceLocation = Reflect.method(friendlyByteBuf, "writeResourceLocation", resourceLocation);
        Class<?> resourceKey = Reflect.clazz("net.minecraft.resources.ResourceKey");
        this.resourceKeyLocation = Reflect.method(resourceKey, "location");

        Class<?> registryBuf = Reflect.clazz("net.minecraft.network.RegistryFriendlyByteBuf");
        Class<?> registryAccessClass = Reflect.clazz("net.minecraft.core.RegistryAccess");
        this.registryBufCtor = registryBuf.getConstructor(ByteBuf.class, registryAccessClass);

        // Send path.
        Class<?> customPayloadPacket = Reflect.clazz("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
        Class<?> customPacketPayload = Reflect.clazz("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
        this.customPayloadPacketCtor = customPayloadPacket.getConstructor(customPacketPayload);
        Class<?> discardedPayload = Reflect.clazz("net.minecraft.network.protocol.common.custom.DiscardedPayload");
        // VERIFY against JEIRecipeBridge: outgoing DiscardedPayload(ResourceLocation, ByteBuf) constructor.
        this.discardedPayloadCtor = discardedPayload.getConstructor(resourceLocation, ByteBuf.class);
        this.fabricPayloadId = newResourceLocation(resourceLocation, FABRIC_CHANNEL);
        this.neoForgePayloadId = newResourceLocation(resourceLocation, NEOFORGE_CHANNEL);
    }

    private Object newResourceLocation(Class<?> resourceLocation, String id) {
        try {
            Method parse = resourceLocation.getMethod("parse", String.class); // VERIFY: ResourceLocation.parse
            return parse.invoke(null, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build ResourceLocation " + id, e);
        }
    }

    private Object newRegistryBuf() {
        try {
            return registryBufCtor.newInstance(Unpooled.buffer(), registryAccess);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create RegistryFriendlyByteBuf", e);
        }
    }

    private static byte[] toBytes(Object friendlyByteBuf) {
        ByteBuf buf = (ByteBuf) friendlyByteBuf; // FriendlyByteBuf extends ByteBuf wrapper
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public int recipeCount() {
        return recipeCount;
    }

    @Override
    public byte[] buildFabricPayload() {
        // Group recipes by serializer id (Identifier), preserving a stable order.
        Map<Object, List<Object>> bySerializer = new LinkedHashMap<>();
        for (Object holder : recipesCache) {
            Object recipe = Reflect.call(holderValue, holder);
            Object serializer = Reflect.call(recipeGetSerializer, recipe);
            bySerializer.computeIfAbsent(serializer, s -> new ArrayList<>()).add(holder);
        }

        Object buf = newRegistryBuf();
        Reflect.call(bufWriteVarInt, buf, bySerializer.size()); // List<Entry> size
        for (Map.Entry<Object, List<Object>> entry : bySerializer.entrySet()) {
            Object serializer = entry.getKey();
            List<Object> holders = entry.getValue();
            Object serializerId = Reflect.call(registryGetKey, serializerRegistry, serializer);
            Reflect.call(bufWriteResourceLocation, buf, serializerId); // Identifier serializerId
            Reflect.call(bufWriteVarInt, buf, holders.size());          // VarInt count
            Object codec = Reflect.call(serializerStreamCodec, serializer);
            for (Object holder : holders) {
                Object id = Reflect.call(holderId, holder);             // ResourceKey<Recipe>
                Object location = Reflect.call(resourceKeyLocation, id);
                Reflect.call(bufWriteResourceLocation, buf, location);  // writeResourceKey == writeResourceLocation(key.location())
                Object recipe = Reflect.call(holderValue, holder);
                Reflect.call(streamCodecEncode, codec, buf, recipe);    // recipe body via server codec
            }
        }
        return toBytes(buf);
    }

    @Override
    public byte[] buildNeoForgePayload() {
        // Implemented in Task 8.
        throw new UnsupportedOperationException("NeoForge payload not implemented yet");
    }

    @Override
    public void sendFabric(Player player, byte[] payload) {
        send(player, fabricPayloadId, payload);
    }

    @Override
    public void sendNeoForge(Player player, byte[] payload) {
        // Tags packet added in Task 8; for now just send the content payload.
        send(player, neoForgePayloadId, payload);
    }

    private void send(Player player, Object payloadId, byte[] payload) {
        try {
            if (getHandle == null) {
                getHandle = Reflect.method(player.getClass(), "getHandle");
            }
            Object serverPlayer = Reflect.call(getHandle, player);
            Object connection = Reflect.getField(serverPlayer, "connection"); // VERIFY field name (Mojang: connection)
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            Object discarded = discardedPayloadCtor.newInstance(payloadId, buf);
            Object packet = customPayloadPacketCtor.newInstance(discarded);
            Method send = Reflect.method(connection.getClass(), "send",
                    Reflect.clazz("net.minecraft.network.protocol.Packet"));
            Reflect.call(send, connection, packet);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Failed to send recipe payload to " + player.getName() + ": " + e.getMessage());
        }
    }
}
```

> Implementation note: `FriendlyByteBuf` wraps a Netty `ByteBuf` (it implements the buffer interface), so casting the registry buffer to `ByteBuf` to read its bytes is valid; if a given version rejects the direct cast, read `bufField = Reflect.getField(buf, "source")`/equivalent (confirm at runtime). The `// VERIFY` lines (`registryAccess`, `getRecipeManager`, `getRecipes`, `RECIPE_SERIALIZER`, `DiscardedPayload` ctor, `ResourceLocation.parse`, `connection` field) are the only version-sensitive points; everything else is delegated to the server's own codecs.

- [ ] **Step 3: Write `PlayerConnectionListener.java`**

```java
package fr.horizonsmp.jeirecipefix.listener;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

public final class PlayerConnectionListener implements Listener {

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
        boolean synced = syncService.syncTo(player);
        if (!synced) {
            // The client brand can arrive a moment after join; retry once shortly after (Folia-safe).
            player.getScheduler().runDelayed(plugin, task -> syncService.syncTo(player), null, 20L);
        }
    }
}
```

- [ ] **Step 4: Wire everything in `JEIRecipeFix.java`**

```java
package fr.horizonsmp.jeirecipefix;

import fr.horizonsmp.jeirecipefix.config.ConfigLoader;
import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.listener.PlayerConnectionListener;
import fr.horizonsmp.jeirecipefix.nms.NmsRecipeBridge;
import fr.horizonsmp.jeirecipefix.nms.RecipeBridge;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public final class JEIRecipeFix extends JavaPlugin {

    private final AtomicReference<PluginConfig> config = new AtomicReference<>(PluginConfig.defaults());
    private RecipeSyncService syncService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        RecipeBridge bridge = new NmsRecipeBridge(this);
        if (!bridge.isAvailable()) {
            getLogger().warning("Unsupported server internals; recipe sync disabled. JEIRecipeFix will stay dormant.");
        }
        this.syncService = new RecipeSyncService(bridge, config::get, this, getLogger());

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, syncService, config::get), this);

        getLogger().info("JEIRecipeFix enabled (recipe sync "
                + (bridge.isAvailable() ? "active" : "dormant") + ").");
    }

    public void reloadAll() {
        reloadConfig();
        reloadPluginConfig();
        if (syncService != null) {
            syncService.invalidate();
        }
    }

    public RecipeSyncService syncService() {
        return syncService;
    }

    public PluginConfig pluginConfig() {
        return config.get();
    }

    private void reloadPluginConfig() {
        config.set(ConfigLoader.fromSection(getConfig()));
    }
}
```

- [ ] **Step 5: Verify build + unit tests still pass**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all prior unit tests green.

- [ ] **Step 6: Runtime acceptance: Fabric client shows recipes**

1. `./gradlew runServer` (1.21.4).
2. Join with a **Fabric** client running **JEI** (and Fabric API).
3. Open JEI and search an item (e.g. a stick): its crafting/cooking recipes appear.
4. Repeat with **REI** and **EMI** clients: recipes appear there too (same loader store).
5. Log shows no plugin-side exception; with `debug: true`, a `Synced N recipes to <player> (brand=FABRIC)` line.

If recipes do not appear, compare the byte framing against `Mrbysco/JEIRecipeBridge`'s Fabric payload class and the Fabric `ClientboundRecipeSyncPayload` codec, and fix the `// VERIFY` points. This is the validation gate for the wire format.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: sync recipes to Fabric clients on join" -m "Read recipes from the server recipe manager via reflection and re-encode them with the server's own stream codecs into the fabric:recipe_sync payload, restoring JEI/REI/EMI recipe lookups on Fabric clients. Retry once on join in case the client brand arrives late; Folia-safe scheduling."
```

---

## Task 8: NeoForge path

**Files:**
- Modify: `src/main/java/fr/horizonsmp/jeirecipefix/nms/NmsRecipeBridge.java`

NeoForge `RecipeContentPayload` = `Set<RecipeType>` (written as a registry collection: VarInt size + each as VarInt registry id) followed by `List<RecipeHolder>` (each via `RecipeHolder.STREAM_CODEC`, which writes the recipe key + the recipe body). The plugin follows the content payload with a vanilla `ClientboundUpdateTagsPacket` (NeoForge clients expect tags). Cross-check exact framing against `Mrbysco/JEIRecipeBridge`'s NeoForge payload class.

- [ ] **Step 1: Resolve the extra handles in `resolveHandles()`**

Add these resolutions at the end of `resolveHandles()` (place after the send-path block):

```java
        // NeoForge extras.
        Class<?> builtInRegistriesClass = Reflect.clazz("net.minecraft.core.registries.BuiltInRegistries");
        this.recipeTypeRegistry = Reflect.staticField(builtInRegistriesClass, "RECIPE_TYPE"); // VERIFY field name
        Method recipeGetType = Reflect.method(
                Reflect.call(holderValue, ((java.util.Collection<?>) recipesCache).iterator().next()).getClass(),
                "getType"); // Recipe#getType()
        this.recipeGetType = recipeGetType;
        // RecipeHolder.STREAM_CODEC static field on the RecipeHolder class.
        Class<?> recipeHolderClass = ((java.util.Collection<?>) recipesCache).iterator().next().getClass();
        this.recipeHolderStreamCodec = findStreamCodec(recipeHolderClass); // VERIFY: static STREAM_CODEC field
```

Add fields near the other handles:

```java
    private Object recipeTypeRegistry;       // BuiltInRegistries.RECIPE_TYPE
    private Method recipeGetType;            // Recipe#getType()
    private Object recipeHolderStreamCodec;  // RecipeHolder.STREAM_CODEC
```

Add the helper (RecipeHolder may be a record or sealed class; locate its static `STREAM_CODEC`):

```java
    private Object findStreamCodec(Class<?> holderClass) {
        for (Class<?> c = holderClass; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("STREAM_CODEC");
                f.setAccessible(true);
                return f.get(null);
            } catch (ReflectiveOperationException ignored) {
                // try interfaces
            }
        }
        // RecipeHolder is typically net.minecraft.world.item.crafting.RecipeHolder
        Class<?> rh = Reflect.clazz("net.minecraft.world.item.crafting.RecipeHolder");
        return Reflect.staticField(rh, "STREAM_CODEC");
    }
```

- [ ] **Step 2: Implement `buildNeoForgePayload()`**

Replace the `throw new UnsupportedOperationException(...)` body with:

```java
    @Override
    public byte[] buildNeoForgePayload() {
        java.util.LinkedHashSet<Object> types = new java.util.LinkedHashSet<>();
        java.util.List<Object> holders = new java.util.ArrayList<>();
        for (Object holder : (java.util.Collection<?>) recipesCache) {
            holders.add(holder);
            Object recipe = Reflect.call(holderValue, holder);
            types.add(Reflect.call(recipeGetType, recipe));
        }

        Object buf = newRegistryBuf();
        // Set<RecipeType> as a registry collection: VarInt size + each as VarInt registry id.
        Reflect.call(bufWriteVarInt, buf, types.size());
        Method typeGetId = Reflect.method(recipeTypeRegistry.getClass(), "getId", Object.class); // Registry#getId -> int
        for (Object type : types) {
            int id = (int) Reflect.call(typeGetId, recipeTypeRegistry, type);
            Reflect.call(bufWriteVarInt, buf, id);
        }
        // List<RecipeHolder> via RecipeHolder.STREAM_CODEC: VarInt size + each element encoded.
        Reflect.call(bufWriteVarInt, buf, holders.size());
        Method holderCodecEncode = Reflect.method(recipeHolderStreamCodec.getClass(), "encode", Object.class, Object.class);
        for (Object holder : holders) {
            Reflect.call(holderCodecEncode, recipeHolderStreamCodec, buf, holder);
        }
        return toBytes(buf);
    }
```

- [ ] **Step 3: Send the tags packet after the NeoForge content payload**

Replace `sendNeoForge` with:

```java
    @Override
    public void sendNeoForge(Player player, byte[] payload) {
        send(player, neoForgePayloadId, payload);
        sendTagsPacket(player);
    }

    private void sendTagsPacket(Player player) {
        try {
            Object serverPlayer = Reflect.call(getHandle, player);
            Object connection = Reflect.getField(serverPlayer, "connection");
            // Build ClientboundUpdateTagsPacket from the server's frozen registries.
            Class<?> updateTags = Reflect.clazz("net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket");
            // VERIFY against JEIRecipeBridge: it constructs this from registryAccess tag snapshots.
            java.lang.reflect.Method tagsFactory = pickTagsFactory(updateTags);
            Object packet = buildTagsPacket(updateTags, tagsFactory);
            Method send = Reflect.method(connection.getClass(), "send",
                    Reflect.clazz("net.minecraft.network.protocol.Packet"));
            Reflect.call(send, connection, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to send tags packet to " + player.getName() + ": " + e.getMessage());
        }
    }
```

> The exact `ClientboundUpdateTagsPacket` construction (the `pickTagsFactory`/`buildTagsPacket` helpers) is the most version-variable NMS detail; port it directly from `Mrbysco/JEIRecipeBridge`'s NeoForge sender, which already does this for the 1.21.x and 26.1.x lines. Implement those two private helpers from that reference. If you must ship NeoForge support incrementally, it is acceptable to land Fabric first (Task 7) and keep this task open, since Fabric covers the majority of clients.

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Runtime acceptance: NeoForge client shows recipes**

1. `./gradlew runServer`.
2. Join with a **NeoForge** client running **JEI**.
3. JEI shows server recipes; no plugin-side exception in the log.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: sync recipes to NeoForge clients"
```

---

## Task 9: Datapack reload -> invalidate cache + resync

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/listener/ResourceReloadListener.java`
- Modify: `src/main/java/fr/horizonsmp/jeirecipefix/JEIRecipeFix.java`

- [ ] **Step 1: Write `ResourceReloadListener.java`**

```java
package fr.horizonsmp.jeirecipefix.listener;

import fr.horizonsmp.jeirecipefix.config.PluginConfig;
import fr.horizonsmp.jeirecipefix.sync.RecipeSyncService;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.function.Supplier;

public final class ResourceReloadListener implements Listener {

    private final RecipeSyncService syncService;
    private final Supplier<PluginConfig> config;

    public ResourceReloadListener(RecipeSyncService syncService, Supplier<PluginConfig> config) {
        this.syncService = syncService;
        this.config = config;
    }

    @EventHandler
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        syncService.invalidate();
        if (config.get().syncOnDatapackReload()) {
            syncService.resyncAll();
        }
    }
}
```

> Note: `NmsRecipeBridge` caches the recipe collection at construction. For the resync to reflect datapack changes, the bridge must re-read recipes when its payloads are rebuilt. Update `NmsRecipeBridge.buildFabricPayload()` / `buildNeoForgePayload()` to re-fetch the recipe collection at the start of each build (call `getRecipes` again and refresh `recipesCache`/`recipeCount`) rather than reusing the constructor-time snapshot. Make that change in this task.

- [ ] **Step 2: Refresh recipes on each build in `NmsRecipeBridge`**

Add a private method and call it at the top of both build methods:

```java
    private void refreshRecipes() {
        Method getRecipeManager = Reflect.method(minecraftServer.getClass(), "getRecipeManager");
        Object recipeManager = Reflect.call(getRecipeManager, minecraftServer);
        java.util.Collection<?> recipes = (java.util.Collection<?>) Reflect.call(getRecipes, recipeManager);
        this.recipesCache = recipes;
        this.recipeCount = recipes.size();
    }
```

Call `refreshRecipes();` as the first line of `buildFabricPayload()` and `buildNeoForgePayload()`.

- [ ] **Step 3: Register the listener in `JEIRecipeFix.onEnable()`**

Add after the existing `registerEvents(...)` call:

```java
        getServer().getPluginManager().registerEvents(
                new fr.horizonsmp.jeirecipefix.listener.ResourceReloadListener(syncService, config::get), this);
```

- [ ] **Step 4: Verify build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Runtime acceptance: datapack reload refreshes recipes**

1. `./gradlew runServer`, join with a Fabric+JEI client.
2. Add/modify a datapack recipe, run `/minecraft:reload`.
3. JEI reflects the change without rejoining (with `sync-on-datapack-reload: true`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: refresh recipes for online players after datapack reload"
```

---

## Task 10: Command + router (TDD routing) + messages

**Files:**
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/command/CommandRouter.java`
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/command/JEIRecipeFixCommand.java`
- Create: `src/main/java/fr/horizonsmp/jeirecipefix/i18n/Messages.java`
- Create: `src/main/resources/messages.yml`
- Modify: `src/main/java/fr/horizonsmp/jeirecipefix/JEIRecipeFix.java`
- Test: `src/test/java/fr/horizonsmp/jeirecipefix/command/CommandRouterTest.java`

- [ ] **Step 1: Write the failing router test**

```java
package fr.horizonsmp.jeirecipefix.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandRouterTest {

    @Test
    void noArgsIsHelp() {
        assertEquals(CommandRouter.Type.HELP, CommandRouter.route(new String[]{}).type());
    }

    @Test
    void resyncDefaultsToSelf() {
        CommandRouter.Action a = CommandRouter.route(new String[]{"resync"});
        assertEquals(CommandRouter.Type.RESYNC, a.type());
        assertEquals("self", a.target());
    }

    @Test
    void resyncWithTargetKeepsTarget() {
        CommandRouter.Action a = CommandRouter.route(new String[]{"reSync", "Notch"});
        assertEquals(CommandRouter.Type.RESYNC, a.type());
        assertEquals("Notch", a.target());
    }

    @Test
    void reloadAndInfoAndUnknown() {
        assertEquals(CommandRouter.Type.RELOAD, CommandRouter.route(new String[]{"reload"}).type());
        assertEquals(CommandRouter.Type.INFO, CommandRouter.route(new String[]{"info"}).type());
        CommandRouter.Action unknown = CommandRouter.route(new String[]{"bogus"});
        assertEquals(CommandRouter.Type.UNKNOWN, unknown.type());
        assertNull(CommandRouter.route(new String[]{"reload"}).target());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.command.CommandRouterTest"`
Expected: FAIL, `CommandRouter` does not exist.

- [ ] **Step 3: Write `CommandRouter.java`**

```java
package fr.horizonsmp.jeirecipefix.command;

import java.util.Locale;

public final class CommandRouter {

    public enum Type { RESYNC, RELOAD, INFO, HELP, UNKNOWN }

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
            case "help" -> new Action(Type.HELP, null);
            default -> new Action(Type.UNKNOWN, sub);
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "fr.horizonsmp.jeirecipefix.command.CommandRouterTest"`
Expected: PASS.

- [ ] **Step 5: Write `src/main/resources/messages.yml`**

```yaml
prefix: "&8[&bJEIRecipeFix&8]&r "
no-permission: "&cYou don't have permission to do that."
reload-success: "&aConfiguration reloaded."
resync-self: "&aRecipes re-sent to you."
resync-player: "&aRecipes re-sent to &e%player%&a."
resync-all: "&aRecipes re-sent to &e%count%&a player(s)."
player-not-found: "&cPlayer &e%player% &cnot found."
recipe-sync-dormant: "&cRecipe sync is dormant (unsupported server internals)."
info-line: "&7Recipes: &f%recipes% &7| Online players: &f%players% &7| Sync: &f%state%"
unknown-subcommand: "&cUnknown subcommand. Use &e/jrf help&c."
help: "&bJEIRecipeFix: &e/jrf resync [player|all]&7, &e/jrf reload&7, &e/jrf info"
```

- [ ] **Step 6: Write `Messages.java`**

```java
package fr.horizonsmp.jeirecipefix.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;

public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private YamlConfiguration messages;
    private String prefix;

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.prefix = messages.getString("prefix", "");
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        String raw = messages.getString(key, key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("%" + e.getKey() + "%", e.getValue());
        }
        Component component = LEGACY.deserialize(prefix + raw);
        target.sendMessage(component);
    }

    public void send(CommandSender target, String key) {
        send(target, key, Map.of());
    }
}
```

- [ ] **Step 7: Write `JEIRecipeFixCommand.java`**

```java
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
                        "state", syncService.available() ? "active" : "dormant"));
            }
            case RESYNC -> handleResync(sender, action.target());
        }
        return true;
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
            return Stream.of("resync", "reload", "info", "help")
                    .filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resync")) {
            return Stream.concat(Stream.of("all", "self"),
                            Bukkit.getOnlinePlayers().stream().map(Player::getName))
                    .filter(s -> s.toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
```

- [ ] **Step 8: Register the command + messages in `JEIRecipeFix.onEnable()`**

Add a `Messages` field and instantiate it; register the executor. Insert into `onEnable()` after the listeners are registered:

```java
        Messages messages = new Messages(this);
        JEIRecipeFixCommand command = new JEIRecipeFixCommand(this, syncService, messages);
        getCommand("jeirecipefix").setExecutor(command);
        getCommand("jeirecipefix").setTabCompleter(command);
```

Add the imports `fr.horizonsmp.jeirecipefix.command.JEIRecipeFixCommand` and `fr.horizonsmp.jeirecipefix.i18n.Messages`.

- [ ] **Step 9: Verify build + tests**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 10: Runtime acceptance: command works**

1. `./gradlew runServer`.
2. As console/op: `/jrf info` shows the status line; `/jrf reload` reports success; `/jrf resync all` reports the player count; `/jrf bogus` shows the unknown-subcommand message.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: add admin command (resync/reload/info) with messages"
```

---

## Task 11: README, CHANGELOG, release workflow

**Files:**
- Create: `README.md`, `CHANGELOG.md`, `.github/workflows/release.yml`

- [ ] **Step 1: Write `README.md` (Modrinth body)**

```markdown
# JEIRecipeFix

Makes recipe-viewer mods work again on Paper, Purpur and Folia servers.

Since Minecraft 1.21.2, servers no longer send recipes to clients, so **JEI**, **REI** and **EMI** show nothing on a plugin-based server. JEIRecipeFix sends the server's recipes to those mods so recipe lookups work again, with **no client mod** and **no server mod loader**.

## Features

- Restores recipe lookups in JEI, REI and EMI.
- Works automatically: install the plugin, players just connect.
- Supports Fabric and NeoForge clients.
- Covers vanilla, datapack and other plugins' recipes; updates after a datapack reload.

## Supported versions

Minecraft 1.21.2 to 26.1.2, on Paper / Purpur / Folia.

## Commands

- `/jrf resync [player|all]`: re-send recipes.
- `/jrf reload`: reload the configuration.
- `/jrf info`: show status.

## Notes

This is an unofficial, third-party plugin. It is not made by the authors of JEI, REI or EMI.
```

- [ ] **Step 2: Write `CHANGELOG.md` (user-facing wording)**

```markdown
# Changelog

All notable changes to this project are documented here.

## [0.1.0-beta.1] - 2026-06-01

### Added
- Your server's recipes now show up again in JEI, REI and EMI on Paper, Purpur and Folia.
- Works automatically for Fabric and NeoForge clients: no client mod needed.
- Recipes update after a datapack reload.
- Admin commands to re-send recipes, reload settings, and check status.
```

> The Modrinth changelog for each release is extracted from the matching `## [version]` section by the build. Keep entries about what players/admins experience, never about build or protocol internals.

- [ ] **Step 3: Write `.github/workflows/release.yml`**

```yaml
name: Release to Modrinth

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: read

jobs:
  publish:
    name: Build and publish
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Setup Java 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Verify version matches tag
        run: |
          tag="${GITHUB_REF#refs/tags/v}"
          version=$(grep '^version=' gradle.properties | cut -d= -f2)
          if [ "$tag" != "$version" ]; then
            echo "Tag $tag does not match gradle.properties version $version" >&2
            exit 1
          fi

      - name: Publish to Modrinth
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
        run: ./gradlew modrinth --no-daemon --stacktrace
```

- [ ] **Step 4: Verify the changelog extraction works**

Run: `./gradlew modrinth --dry-run` (or inspect), then actually run `./gradlew build` and confirm no Gradle script error in the `modrinth`/`extractChangelogSection` configuration.
Expected: `BUILD SUCCESSFUL` (no upload without a token; that is fine).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: add README, changelog, and Modrinth release workflow"
```

---

## Task 12: Version-matrix verification + finalize

No new code. This task proves the single jar works across the range and that nothing regressed. Capture results in the commit message of any fix made here.

- [ ] **Step 1: Full build + all unit tests**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`; all tests pass.

- [ ] **Step 2: Runtime smoke at the version boundaries**

For each of `1.21.2`, `1.21.6`, `26.1.2` (edit `gradle/libs.versions.toml` `minecraft` value, run, then revert):
1. `./gradlew runServer`.
2. Confirm the plugin enables with `recipe sync active` and no exception (the `// VERIFY` reflection points resolve on that version).
3. With a Fabric+JEI client, confirm recipes appear.
Stop the server with SIGTERM each time. Record any version where a reflection point needed a fallback and ensure `NmsRecipeBridge` handles it (defensive resolution), then re-test.

- [ ] **Step 3: Confirm dormant-mode safety**

Temporarily force a resolution failure (e.g. point one `Reflect.clazz` at a bogus name in a scratch build) and confirm: a single warning is logged, the plugin still enables, joins cause no exceptions, and `/jrf info` shows `dormant`. Revert the scratch change.

- [ ] **Step 4: Final commit (if any fixes were made)**

```bash
git add -A
git commit -m "fix: harden reflection across the supported version range"
```

---

## Self-Review

**1. Spec coverage**: every spec section maps to a task:
- Problem / goal / single-jar via server codecs -> Task 7 (`NmsRecipeBridge`, encode via server stream codecs), Task 1 (release 21, floor API).
- Fabric `fabric:recipe_sync` -> Task 7. NeoForge `neoforge:recipe_content` + tags -> Task 8.
- What is synced (manager recipes) / not synced (brewing/anvil/fuel) -> Task 7 reads the recipe manager; brewing/anvil/fuel are simply absent there, no code needed (as designed).
- Brand detection -> Task 2. All server recipes (vanilla+datapack+plugins) -> Task 7 reads the live manager; Task 9 refreshes on reload.
- Control surface: config -> Task 3; command + permissions -> Task 10 + plugin.yml in Task 1; resync -> Tasks 9/10.
- Payload caching -> Tasks 4 + 6. Folia scheduling -> Tasks 6/7/10 (`player.getScheduler()`), `folia-supported` in Task 1.
- Error handling (brand null + retry, dormant on unsupported internals, harmless drop) -> Tasks 7 (retry, dormant) and 6 (skip).
- Build/versions/Modrinth -> Task 1; README/changelog/workflow -> Task 11.
- Testing/done criteria -> Tasks 7/8/9/10/12 runtime steps.

**2. Placeholder scan**: no "TBD/TODO/handle edge cases" left. The `// VERIFY` markers are concrete, named version-sensitive points with a stated validation method (runtime client + JEIRecipeBridge reference), not vague placeholders. Task 8's tags-packet helpers are explicitly delegated to the named reference implementation rather than left blank. The two deliberate "read, don't paste" guards (the `assertФalse` typo note in Task 6, and the LICENSE year check in Task 1) are called out inline.

**3. Type consistency**: `RecipeBridge` methods (`isAvailable`, `recipeCount`, `buildFabricPayload`, `buildNeoForgePayload`, `sendFabric`, `sendNeoForge`) match across the interface (Task 5), the fake (Task 6), and `NmsRecipeBridge` (Tasks 7/8). `RecipeSyncService` ctor `(RecipeBridge, Supplier<PluginConfig>, Plugin, Logger)` is identical in Task 6 test, Task 6 impl, and Task 7 wiring. `ClientBrand` / `PluginConfig` / `Lazy` / `CommandRouter.Action`/`Type` names are used consistently. `Messages` ctor and `send(...)` signatures match Task 10 usage.

Known open item (acceptable): the exact Fabric/NeoForge byte framing and a handful of NMS accessor names are validated at runtime, not by unit test; this is inherent to NMS reflection and is gated by the Task 7/8/12 acceptance steps and the JEIRecipeBridge reference.
