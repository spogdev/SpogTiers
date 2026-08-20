# Porting SpogTiers to other Minecraft versions

## Branch model

One branch per Minecraft version, named `mc/<version>`:

```
main          -> current dev, tracks the newest supported version
mc/26.1.2     -> release branch for 26.1.2 (mojmap, deobfuscated)
mc/1.21.11    -> release branch for 1.21.11 (yarn)
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
- **26.x** — the big one. See the dedicated section below.

## The 1.21.11 -> 26.x wall

Minecraft 26.x is **not** an incremental port. Three things change at once:

**1. The jar ships deobfuscated.** Yarn stopped at 1.21.11 (there are zero 26.x
builds on maven) and Fabric's intermediary for 26.1.2 is an empty `0.0.0` stub
whose `mappings.tiny` contains only a header line. Mojang also publishes no
`client_mappings` for 26.1.2, so `loom.officialMojangMappings()` fails with
"Failed to find official mojang mappings". The class names are already readable
in the vanilla jar.

**2. Loom still needs a mapping set.** An empty one fails with
`srcNamespace is null`, and a loose `.tiny` file fails with
`Provider "jar" not found`. The working answer is a synthesised **identity
mapping jar** — every class mapped to its own name across
`official/intermediary/named`. Regenerate it with:

```bash
python mappings/generate_identity_mappings.py --version 26.1.2
```

**3. The runtime namespace must be forced.** With identity mappings the loader
computes its runtime namespace as `official`, but Fabric API's class tweakers
are authored in `named`, so startup dies with
`Namespace (named) does not match current runtime namespace (official)`.
The loader reads `fabric.runtimeMappingNamespace` first (see
`MappingConfiguration#computeRuntimeNamespace`), so the run config sets:

```groovy
vmArg "-Dfabric.runtimeMappingNamespace=named"
```

Note this is *not* `fabric.defaultModDistributionNamespace`, which Loom writes
itself and which has no effect here.

**Java 25 is required** (26.1.2 requests `java-runtime-epsilon`, major 25).

### Yarn -> mojmap rename table

Every Minecraft-facing name changes. The ones this mod touches:

| yarn (1.21.x) | mojmap (26.x) |
| --- | --- |
| `Text` | `Component` |
| `MutableText` | `MutableComponent` |
| `Formatting` | `ChatFormatting` |
| `MinecraftClient` | `Minecraft` |
| `PlayerListEntry` | `PlayerInfo` |
| `PlayerListHud` | `PlayerTabOverlay` |
| `PlayerListHud#getPlayerName` | `PlayerTabOverlay#getNameForDisplay` |
| `EntityRenderer#updateRenderState` | `EntityRenderer#extractRenderState` |
| `EntityRenderState#displayName` | `EntityRenderState#nameTag` |
| `getNetworkHandler()` | `getConnection()` |
| `getPlayerList()` | `getOnlinePlayers()` |
| `PlayerEntity#getUuid()` | `Player#getUUID()` |
| `net.minecraft.entity.*` | `net.minecraft.world.entity.*` |
| `client.render.entity.*` | `client.renderer.entity.*` |

Package roots differ too: `net.minecraft.text` becomes
`net.minecraft.network.chat`.

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

## Fabric API submodules on 26.x

Fabric API submodules (e.g. `fabric-key-binding-api-v1`) are published compiled
against **intermediary** names. Our identity mappings have no intermediary
entries, so referencing one fails with `cannot access class_304`.

Until yarn/intermediary exist for 26.x, prefer vanilla hooks over Fabric API
submodules. This bites more than once:

- `fabric-key-binding-api-v1` -- `KeyBindingHelper` takes `class_304`.
- `fabric-command-api-v2` -- `FabricClientCommandSource` extends `class_2172`.

`ClientPacketListenerMixin` is the worked example: it registers `/tiers` by
intercepting `ClientPacketListener#sendCommand` and cancelling the callback,
which covers both multiplayer and singleplayer since `ChatScreen` routes every
command through that method.

## GUI notes (26.x)

- `GuiGraphics` is now **`GuiGraphicsExtractor`**, and `Screen`/`Renderable`
  render through `extractRenderState(...)` rather than `render(...)`.
- Text is drawn with `text(...)` / `centeredText(...)`.
- `blurBeforeThisStratum()` gives real frosted-glass blur, but vanilla permits
  **exactly one blur per frame** and throws `Can only blur once per frame` on a
  second call. The title screen blurs its own panorama, so any screen that can
  open over it must guard the call (see `ProfileScreen#blurAllowed`).
- The player model is drawn with
  `InventoryScreen.extractEntityInInventoryFollowsMouse(...)`.
- Skins can be rendered without an entity: `Minecraft#getSkinManager()` plus
  `SkinManager#createLookup(GameProfile, boolean)` feeds vanilla's
  `PlayerSkinWidget`, which handles wide/slim models and drag-rotation. This is
  what lets the profile screen show players who are not on the server.
