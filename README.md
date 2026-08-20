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

## Configuration

Written to `config/spogtiers.json` on first launch:

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `showNametags` | `true` | Tag above player heads |
| `showTabList` | `true` | Tag in the tab list |
| `displayMode` | `VANILLA` | Which gamemode's tier to show |
| `showBestTier` | `false` | Show best tier across all gamemodes instead |
| `apiBaseUrl` | *placeholder* | Base URL of your tier API |
| `cacheTtlSeconds` | `900` | How long a lookup stays fresh |
| `requestsPerSecond` | `5` | Client-side rate limit |

> **`apiBaseUrl` is a placeholder and must be set before the mod does anything
> useful.** No public tier API is hardcoded.

## Expected API shape

`GET {apiBaseUrl}/tiers/{uuid-without-dashes}` returning:

```json
{
  "name": "Notch",
  "rankings": {
    "vanilla": { "tier": 2, "pos": "HT", "retired": false },
    "uhc":     { "tier": 4, "pos": "LT", "retired": true }
  }
}
```

Unknown gamemodes and malformed entries are skipped rather than failing the
whole lookup. Adapt `TierService#parse` if your backend differs.

## Architecture

```
SpogTiersClient      entrypoint; owns config, cache, service
config/              JSON-backed settings
data/                Tier, Gamemode, PlayerTiers, TierCache, TierService
util/TagRenderer     builds the coloured badge Text
mixin/               the only version-sensitive code
```

Lookups run on a daemon thread pool at a bounded rate; the cache is a
concurrent map so render-thread reads never block. Failed lookups back off for
60s instead of retrying every tick.
