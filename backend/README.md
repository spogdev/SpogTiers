# Door SMP Backend

The Door SMP tierlist: a Discord bot that assigns grades, and the read-only HTTP API the SpogTiers
mod reads them from. **One jar, one process** — JDA and Javalin start from the same `main` and share
one grade store.

## Why this exists

The six tierlists SpogTiers already reads (PVPHQ, PvPTiers, SubTiers, MCTiers, MCPvP, CatPVP) are
all third-party, undocumented and reverse-engineered. This is the one list we control end to end:
staff grade a player from Discord, and the badge appears beside their region tag in game.

Grades are a single letter per player — no gamemode dimension:

| Grade | Colour | Grade | Colour |
|---|---|---|---|
| S | `FF7FFF` | C | `7FFF7F` |
| A+ | `FFBF7F` | D | `7FFFFF` |
| A | `FFDF7F` | F | `7F7FFF` |
| B+ | `FFFF7F` | | |
| B | `BFFF7F` | | |

`Grade.java` is the single source of truth for those colours: the API serves them to the mod and the
Discord embeds stripe themselves with the same values, so a badge in game and an embed in Discord
can never disagree.

## Security model

- **Grading is gated on Discord user ID**, listed in `graders.json` — the permanent snowflake, never
  the username, which a user can change at will. The check runs inside every write handler rather
  than being trusted from the call site.
- **Not reloadable at runtime.** Granting the ability to grade takes a file edit plus a restart, an
  action that already requires access to the server.
- **An unauthorised caller gets the same refusal** whether or not any graders are configured, so
  probing the bot tells them nothing.
- **There is no HTTP write route.** Grades are set from Discord in this same process, so the API's
  entire surface is read-only. Reads need no authentication: grades are public, and the mod ships no
  credential it could keep secret anyway.
- **Grades key on UUID, never name.** A rename can neither lose a player their grade nor hand it to
  whoever takes the name next. The `name` field on disk is a comment, refreshed opportunistically.
- **The bot token comes from the environment, never an argument** — command lines are world-readable
  in `ps`.

Known limits, stated plainly:
- The rate limiter is a fixed window per IP, so a caller can get up to twice the limit across a
  window boundary. It exists to stop a flood from exhausting a small VPS, not to meter usage.
- Behind a reverse proxy every request appears to come from the proxy, so the limiter is effectively
  global unless Caddy forwards the client IP and Javalin is configured to trust it.

## Build

```
./gradlew :backend:shadowJar
```

Produces `backend/build/libs/doorsmp-backend-all.jar` (self-contained, ~19 MB).

**Always test the fat jar, not `:backend:run`.** Jetty and JDA both ship `META-INF/services`
entries; the build merges them with `mergeServiceFiles()`, and a mistake there only shows up when
running the shaded jar.

## Run

```
DISCORD_TOKEN=... java -jar doorsmp-backend-all.jar --port 8081 --data-dir /opt/doorsmp
```

Flags: `--port` (default 8081), `--host` (default 0.0.0.0), `--data-dir` (default the working
directory). Needs a Java 25 runtime.

Without `DISCORD_TOKEN` the service starts **API-only** and logs a warning: the mod's lookups keep
working, only the grading commands are missing. That is deliberate — a bad token should not take
tier lookups down with it.

### Data files

Both live in `--data-dir`.

`graders.json` — who may grade. Seeded as an empty list on first run:
```json
[ { "id": "123456789012345678", "name": "Spoginator" } ]
```
`name` is a comment for whoever edits the file; only `id` is used. **Restart after editing.**

`grades.json` — the grades themselves, written by the bot:
```json
[ { "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "grade": "S",
    "gradedBy": "Spoginator", "gradedByDiscordId": "123456789012345678",
    "gradedAt": 1756800000 } ]
```
Writes stage through a temp file and move it into place, so a crash mid-write cannot leave a
truncated file behind.

## Discord commands

| Command | Who | Does |
|---|---|---|
| `/setgrade <player> <grade>` | graders | Sets a grade; reports the previous one if it is a change |
| `/removegrade <player>` | graders | Removes a grade |
| `/grade <player>` | anyone | Embed with the grade, striped in its colour |
| `/gradelist [grade]` | anyone | Every graded player, best first, optionally filtered |

`grade` is a choice list, not free text, so an invalid grade is unrepresentable. Failures reply
ephemerally so a mistyped name does not litter the channel.

The bot needs the `applications.commands` scope and no privileged intents — it only reacts to its
own slash commands.

## HTTP API

```
GET /api/v1/grade/{uuid}       dashed or undashed
GET /api/v1/grade/name/{name}
GET /health
```

**200** — graded:
```json
{ "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch",
  "grade": "S", "color": "FF7FFF", "gradedAt": 1756800000 }
```

**404** — the player exists but has no grade (`{"error":"not graded"}`), or no such account
(`{"error":"no such player"}`). This is the normal answer for most players, not an error: the mod
already treats 404 from a tierlist as "not on this list".

**400** malformed UUID · **429** rate limited · **502** Mojang unreachable on a name lookup.

Responses carry `Cache-Control: public, max-age=300`.

## Deploy

Runs alongside the TeamLocator relay on the same VPS, as its own unit and its own user. **Give it a
dedicated user** — a compromise of one service should not reach the other's data, and the bot token
is a real secret.

```bash
useradd -r -m -d /opt/doorsmp doorsmp
```

Upload, staging through `/tmp` and re-chowning — scp-ing straight into `/opt` as root leaves a jar
the service user cannot read, and it fails on restart:

```bash
scp backend/build/libs/doorsmp-backend-all.jar root@YOUR.VPS.IP:/tmp/
ssh root@YOUR.VPS.IP
mv /tmp/doorsmp-backend-all.jar /opt/doorsmp/
chown doorsmp:doorsmp /opt/doorsmp/doorsmp-backend-all.jar
```

The token goes in a mode-600 environment file, never the command line:

```bash
install -o doorsmp -g doorsmp -m 600 /dev/null /opt/doorsmp/backend.env
# then write:  DISCORD_TOKEN=...
```

```ini
# /etc/systemd/system/doorsmp-backend.service
[Unit]
Description=Door SMP Backend (Discord bot + grade API)
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/doorsmp/doorsmp-backend-all.jar --host 127.0.0.1 --port 8081
EnvironmentFile=/opt/doorsmp/backend.env
WorkingDirectory=/opt/doorsmp
Restart=always
User=doorsmp

[Install]
WantedBy=multi-user.target
```

```bash
systemctl enable --now doorsmp-backend
systemctl status doorsmp-backend    # want "active (running)"
```

`--host 127.0.0.1` keeps it off the public interface; Caddy is the only thing that reaches it. Port
8081 avoids the relay's 8080.

### TLS

Add a block to the existing Caddyfile — **do not touch the relay's**:

```
doorsmptl.spog.dev {
    reverse_proxy localhost:8081
}
```

`systemctl reload caddy`. Let's Encrypt issues on first request, provided `doorsmptl.spog.dev` has
an `A` record pointing at the VPS. Ports 80/443 are already open from the relay's setup, so no
firewall change is needed.

### Verify

```bash
journalctl -u doorsmp-backend -f
curl https://doorsmptl.spog.dev/health
```

Restarting drops nothing — the API is stateless per request and the bot reconnects on its own.

## Tests

```
./gradlew :backend:test
```

Covers the grade ladder and its colours, the store round-tripping through disk, renames keeping a
grade, malformed JSON failing loudly rather than silently emptying a file, and the grader check
failing closed.
