# Porting SpogTiers to other Minecraft versions

## Branch model

One branch per Minecraft version, named `mc/<version>`:

```
main          -> current dev, tracks the newest supported version (1.21.11)
mc/1.21.11    -> release branch for 1.21.11+
mc/1.21.8     -> release branch for 1.21.8
...
```

Fixes land on the **oldest** affected branch, then merge forward
(`mc/1.21.8` -> `mc/1.21.11` -> `main`). Merging forward rather than
cherry-picking backward keeps history linear and makes it obvious which
branches already carry a fix.

## What actually changes between versions

Almost everything in this mod is version-agnostic on purpose. Only two areas
touch Minecraft internals:

| Area | Files | Risk |
| --- | --- | --- |
| Version metadata | `gradle.properties`, `fabric.mod.json` | trivial |
| Mixins | `src/main/java/com/spog/tiers/mixin/` | **this is where ports break** |

`data/`, `config/`, and `util/TagRenderer` only touch `Text`/`Style`, which
have been stable for many versions.

## Port checklist

1. Branch from the closest existing version.
2. Update `gradle.properties`. Get exact values from
   <https://fabricmc.net/develop/> or the meta API:
   ```bash
   curl -s https://meta.fabricmc.net/v2/versions/yarn/<MCVER> | head -20
   ```
3. Update the `minecraft` dependency range in `fabric.mod.json`.
4. Run `./gradlew build` and fix mixin breakage (see below).
5. Run `./gradlew runClient` and confirm tags render in tab list and nametags.

## Known version boundaries

These are the breaking changes that matter for this mod:

- **1.21.5+** — Entity rendering moved to the *render state* system.
  `EntityRendererMixin` targets `updateRenderState` and mutates
  `EntityRenderState.displayName`. On **1.21.4 and below** this class does not
  exist; target `EntityRenderer.renderLabelIfPresent` and modify the `Text`
  argument instead.
- **1.21.9+** — Significant HUD/GUI refactors. Verify `PlayerListHud.getPlayerName`
  still exists and still returns `Text`.
- **1.21.11** — Java 21 required. `GameProfile` is a **record**: use
  `entry.getProfile().id()`, not `getId()`. Older versions use the getter,
  so this is a guaranteed edit when porting backward.

## Verifying a mixin target before you write it

Do not guess mapping names. Download the yarn mappings and grep them:

```bash
curl -sLo yarn.jar https://maven.fabricmc.net/net/fabricmc/yarn/1.21.11+build.6/yarn-1.21.11+build.6-mergedv2.jar
unzip -o yarn.jar mappings/mappings.tiny
grep "net/minecraft/client/gui/hud/PlayerListHud" mappings/mappings.tiny
```

The third column is the intermediary name, the fourth is the yarn name you
write in code. To list a class's methods, note its obfuscated name from column
two and filter the block that follows it.

## Verifying a port actually works

Compiling is *not* proof. A mixin can compile fine and silently fail to apply
if the target method moved. Always run:

```bash
./gradlew runClient
```

The dev run configs set `-Dmixin.debug.verify` and `-Dmixin.debug.export`, so
failures are loud and the transformed classes are written to
`run/.mixin.out/class/`. To prove an injection landed:

```bash
javap -p -c run/.mixin.out/class/net/minecraft/client/gui/hud/PlayerListHud.class | grep spogtiers
```

Seeing your `handler$...$spogtiers$...` method in the output is the only real
confirmation.
