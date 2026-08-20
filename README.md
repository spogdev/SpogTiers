# SpogTiers

A client-side Fabric mod that shows PvP tier tags (e.g. `[HT2]`) next to player
names, in the tab list and above player heads.

This branch targets **Minecraft 26.1.2** on **Java 25**, built against official
Mojang names (26.x ships deobfuscated). Other versions live on their own
branches — see [PORTING.md](PORTING.md).

| Branch | Minecraft | Java | Mappings |
| --- | --- | --- | --- |
| `mc/26.1.2` | 26.1.2 | 25 | mojmap (identity) |
| `mc/1.21.11` | 1.21.11 | 21 | yarn |

## Building

```bash
./gradlew build
```

The jar lands in **`dist/`**, named per Minecraft version
(`spogtiers-1.0.0+mc26.1.2.jar`), so builds from different version branches sit
side by side instead of overwriting each other. `build/libs/` still holds the
raw Gradle output including the `-sources` jar.

`dist/` is deliberately outside `build/`, so `./gradlew clean` does not wipe it.
To clear just this version's jars:

```bash
./gradlew cleanDist
```

### First-time setup on this branch

26.x needs a synthesised identity mapping jar (see [PORTING.md](PORTING.md) for
why). It is committed, but regenerate it with:

```bash
python mappings/generate_identity_mappings.py --version 26.1.2
```

## Running a dev client

```bash
./gradlew runClient
```

## Usage

Press **R** while looking at a player to open their profile panel (rebindable in
Controls). Tabs along the bottom switch between tier lists; **Update** forces a
re-fetch for that player.

## Tier lists

Four sources, each queried independently so one being down never blocks the
others. A 404 means "not on this list" and is treated as a normal answer, not an
error.

| List | Endpoint | UUID format |
| --- | --- | --- |
| PVPHQ Ranked | `pvphq.com/api/v1/players/{uuid}` | **dashed** |
| PvPTiers | `pvptiers.com/api/profile/{uuid}` | undashed |
| SubTiers | `subtiers.net/api/profile/{uuid}` | undashed |
| MCTiers | `mctiers.com/api/profile/{uuid}` | undashed |

None of these services publish API docs, so the endpoints above were determined
by inspection and may change without warning.

PvPTiers, SubTiers and MCTiers share one response shape, where `pos` encodes the
prefix (`0` = HT, `1` = LT):

```json
{ "name":"Krisinat0r", "region":"EU", "points":18, "overall":9622,
  "rankings": { "sword": { "tier":4, "pos":1, "retired":false } } }
```

PVPHQ is ELO-based and returns rendered labels and colours instead, including an
**MT (mid) tier** the others do not have. Its colours are used verbatim so the
panel matches the site:

```json
{ "ranked": [ { "gametype":"sword", "tier":"MT3", "tierColor":"#BF6C3D",
               "unranked":false } ] }
```

A ranking whose gamemode is not modelled yet still shows up in the panel under
its raw key, so a provider adding a mode degrades gracefully instead of hiding
data.

## Configuration

Written to `config/spogtiers.json` on first launch:

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `showNametags` | `true` | Tag above player heads |
| `showTabList` | `true` | Tag in the tab list |
| `displayList` | `PVPTIERS` | Which list the badge comes from |
| `displayMode` | `VANILLA` | Which gamemode the badge shows |
| `showBestTier` | `true` | Show best tier across modes instead |
| `enabledLists` | all `true` | Per-list query toggles |
| `panelOpacity` | `190` | Panel background alpha, 0-255 |
| `rotateSkin` | `true` | Spin the skin model (else follow cursor) |
| `cacheTtlSeconds` | `900` | How long a lookup stays fresh |
| `requestsPerSecond` | `5` | Client-side rate limit |

## Architecture

```
SpogTiersClient      entrypoint; owns config, cache, service, keybind
client/gui/          ProfileScreen - the frosted panel
config/              JSON-backed settings
data/                Tier, Gamemode, TierList, PlayerTiers, TierCache, TierService
util/TagRenderer     builds the coloured badge Component
mixin/               the only version-sensitive code
```

Lookups run on a daemon thread pool at a bounded rate; the cache is a
concurrent map so render-thread reads never block. Failed lookups back off for
60s instead of retrying every tick.
