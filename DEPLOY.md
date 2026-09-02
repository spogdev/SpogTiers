# Deploying Door SMP

From built jars to a working badge in game. Four parts: **(A)** test locally, **(B)** create the
Discord bot, **(C)** the VPS, **(D)** ship the mod.

Do Part A first. It takes ten minutes and it means that when something breaks on the VPS you already
know the jar itself is fine.

> **Naming.** The service is `doorsmp`, it listens on **8081**, and the relay already on this box is
> `teamlocator` on **8080**. Nothing below touches the relay.

---

## Part A — Test on your PC first

**A1. Build both sides.**

The backend lives on the `backend` branch, the mod on the three `mc/*` branches. They are separate
checkouts of the same repo:

```bash
git checkout backend
./gradlew :backend:build
```

That produces `dist/doorsmp-backend-all.jar` (~19 MB, self-contained), alongside the mod jars.

(`:backend:shadowJar` builds the same jar but leaves it in `backend/build/libs/` without copying it
to `dist/`.)

**A2. Run it.** Needs a **Java 25** runtime. If plain `java` says `UnsupportedClassVersionError`,
your PATH points at an older Java — call JDK 25 directly (IntelliJ keeps its JDKs under
`%USERPROFILE%\.jdks`):

```powershell
& "$env:USERPROFILE\.jdks\openjdk-25.0.2\bin\java" -jar dist\doorsmp-backend-all.jar --port 8081 --data-dir .
```

You want to see:

```
Door SMP API listening on 0.0.0.0:8081
DISCORD_TOKEN is not set; running API-only, no grading commands
```

That warning is expected here — you have no token yet. The API works without one; only the grading
commands are missing. Leave it running.

**A3. Prove the API works.** In another terminal:

```bash
curl -i http://localhost:8081/health
```

Then look up an ungraded player. **404 is the correct answer** — it is what almost every player
returns, and the mod treats it as "no badge", not an error:

```bash
curl -i http://localhost:8081/api/v1/grade/name/Notch
```

**A4. Fake a grade** to see the whole path work before Discord exists. Stop the service, and put
this in `grades.json` next to the jar:

```json
[
  { "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "grade": "S",
    "gradedBy": "you", "gradedByDiscordId": "0", "gradedAt": 1756800000 }
]
```

Start it again — the log should say `loaded 1 grade(s)` — and re-run the curl. You should now get:

```json
{"uuid":"069a79f4-...","name":"Notch","grade":"S","color":"FF7FFF","gradedAt":1756800000}
```

If that works, the service is sound and anything that goes wrong later is configuration.

---

## Part B — Create the Discord bot

**B1.** Go to <https://discord.com/developers/applications> → **New Application**. Name it whatever
you like.

**B2.** Open the **Bot** tab → **Reset Token** → copy it. **This is a password.** Anyone holding it
controls the bot. Do not paste it into a file you commit, and do not put it on a command line (see
B5).

**B3.** No privileged intents are needed — leave *Presence*, *Server Members* and *Message Content*
all **off**. The bot only ever reacts to its own slash commands.

**B4.** Open **OAuth2 → URL Generator**, tick **`bot`** and **`applications.commands`**, and under
bot permissions tick **Send Messages** and **Embed Links**. Open the generated URL and invite the bot
to your server.

**B5. Find your own Discord user ID** — this is what authorises you to grade, and it is *not* your
username. In Discord: **Settings → Advanced → Developer Mode: on**, then right-click your name →
**Copy User ID**. It is an 18–19 digit number.

**B6. Test the bot locally** before the VPS. Run the jar with the token in the environment:

```powershell
$env:DISCORD_TOKEN = "your-token-here"
& "$env:USERPROFILE\.jdks\openjdk-25.0.2\bin\java" -jar dist\doorsmp-backend-all.jar --port 8081 --data-dir .
```

First run creates an empty `graders.json` (`[]`). Stop the service, add yourself, and **restart** —
the grader list is deliberately read only at startup, so a file edit plus a restart is what grants
grading:

```json
[
  { "id": "123456789012345678", "name": "YourName" }
]
```

The `name` is a comment for whoever reads the file; only `id` is used.

Now in Discord try `/settier Notch S`. You should get a pink confirmation. Then `/tier Notch` for
the embed. If `/settier` says you lack permission, your ID is wrong or you didn't restart.

---

## Part C — The VPS

This runs alongside the TeamLocator relay on the same box. **Nothing here touches the relay.**

**C1. DNS.** Add an `A` record for `doorsmptl.spog.dev` pointing at the VPS's public IPv4. Let's
Encrypt cannot issue a certificate until this resolves, so do it first and let it propagate.

Check from your PC:

```bash
nslookup doorsmptl.spog.dev
```

**C2. A dedicated user.** SSH in as root and create one. Do **not** reuse `teamlocator` — a
compromise of one service should not reach the other's data, and the bot token is a real secret:

```bash
useradd -r -m -d /opt/doorsmp doorsmp
```

**C3. Java 25** is already installed if the relay is running (it needs 25 too). Confirm:

```bash
java -version
```

If it is missing, install Temurin:

```bash
apt install -y wget gpg apt-transport-https
mkdir -p /etc/apt/keyrings
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list
apt update && apt install -y temurin-25-jre
```

**C4. Upload the jar.** From your PC:

```bash
scp dist/doorsmp-backend-all.jar root@YOUR.VPS.IP:/tmp/
```

Then on the VPS, move it into place and hand it to the service user:

```bash
mv /tmp/doorsmp-backend-all.jar /opt/doorsmp/
chown doorsmp:doorsmp /opt/doorsmp/doorsmp-backend-all.jar
```

> **Stage through `/tmp` and re-chown.** scp-ing straight into `/opt/doorsmp/` as root leaves a jar
> the service user cannot read, and it fails on restart with a confusing error.

**C5. The token file.** The token goes in a mode-600 environment file, **never on the command
line** — command lines are world-readable in `ps`, so `--token` would leak it to every user on the
box:

```bash
install -o doorsmp -g doorsmp -m 600 /dev/null /opt/doorsmp/backend.env
nano /opt/doorsmp/backend.env
```

One line, no quotes, no spaces around the `=`:

```
DISCORD_TOKEN=your-token-here
```

**C6. The service.**

```bash
cat > /etc/systemd/system/doorsmp-backend.service <<'EOF'
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
EOF

systemctl daemon-reload
systemctl enable --now doorsmp-backend
systemctl status doorsmp-backend --no-pager
```

You want **`active (running)`**.

`--host 127.0.0.1` means it only listens locally — the outside world reaches it through Caddy.
`WorkingDirectory` is why no `--data-dir` is needed: `grades.json` and `graders.json` land in
`/opt/doorsmp/`.

**C7. Authorise yourself.** The first start created `/opt/doorsmp/graders.json` as `[]`. Add your
Discord ID from B5 and restart:

```bash
nano /opt/doorsmp/graders.json
systemctl restart doorsmp-backend
```

**C8. Caddy.** **Append** a block — do not edit the relay's:

```bash
cat >> /etc/caddy/Caddyfile <<'EOF'

doorsmptl.spog.dev {
    reverse_proxy localhost:8081
}
EOF

systemctl reload caddy
```

Caddy fetches the certificate automatically on the first request. Ports 80 and 443 are already open
from the relay's setup, so there is no firewall change.

**C9. Verify end to end.** From anywhere:

```bash
curl https://doorsmptl.spog.dev/health
```

`{"status":"ok","grades":0}` over **https** means DNS, Caddy, the certificate and the service are
all working. Then in Discord, `/settier <someone> A` and re-run:

```bash
curl https://doorsmptl.spog.dev/api/v1/grade/name/<someone>
```

If that returns the grade, the backend is done.

---

## Part D — The mod

The jars in `dist/` already point at `https://doorsmptl.spog.dev`. Pick the one matching your
Minecraft version:

| Minecraft | Jar |
|---|---|
| 1.21.11 | `spogtiers-0.1.2+1.21.11.jar` |
| 26.1.2 | `spogtiers-0.1.2+26.1.2.jar` |
| 26.2+ | `spogtiers-0.1.2+26.2.jar` |

Drop it in `mods/` alongside **Fabric API** (and Mod Menu for the config screen), start the game,
and open a graded player's profile. The grade appears as a coloured tag **right after the region
tag**; hovering it reads `Door SMP Tierlist: S`.

Players you have not graded show no badge at all — that is the normal case and nothing is wrong.

---

## Day-to-day

**Grading** (any listed grader, in Discord):

| Command | Does |
|---|---|
| `/settier <player> <tier>` | Sets a tier. Tells you the old one if it is a change. |
| `/removetier <player>` | Removes it. |
| `/tier <player>` | Looks one up. Anyone can run this. |
| `/tierlist [tier]` | The whole list, grouped by tier. |

**Adding a grader:** edit `/opt/doorsmp/graders.json`, then `systemctl restart doorsmp-backend`. The
restart is required — the list is read at startup on purpose, so granting the ability to grade takes
server access rather than anything reachable over the network.

**Watching it:**

```bash
journalctl -u doorsmp-backend -f
```

**Updating the jar later:**

```bash
scp dist/doorsmp-backend-all.jar root@YOUR.VPS.IP:/tmp/
ssh root@YOUR.VPS.IP
mv /tmp/doorsmp-backend-all.jar /opt/doorsmp/
chown doorsmp:doorsmp /opt/doorsmp/doorsmp-backend-all.jar
systemctl restart doorsmp-backend
systemctl status doorsmp-backend --no-pager
```

A restart drops nothing — grades are on disk and the bot reconnects on its own.

**Backing up:** `/opt/doorsmp/grades.json` is the only irreplaceable file. Copy it somewhere.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `UnsupportedClassVersionError` | Java older than 25. `java -version`. |
| Service won't start, "Unable to access jarfile" | The jar is owned by root. `chown doorsmp:doorsmp` it (C4). |
| `curl https://…` certificate error | DNS not resolving yet, or port 80 blocked so Let's Encrypt can't issue. `nslookup doorsmptl.spog.dev`. |
| `/settier` says "You do not have permission" | Your ID isn't in `graders.json`, or you added it but didn't restart. Copy **User ID**, not username. |
| Bot is offline in Discord | Token wrong or missing. `journalctl -u doorsmp-backend` — it says `DISCORD_TOKEN is not set` or fails to log in. |
| Slash commands don't appear | Bot invited without the `applications.commands` scope. Re-invite with B4's URL. |
| API works, no badge in game | Wrong jar for your MC version, or the player genuinely has no grade. Check with `curl .../api/v1/grade/name/<them>`. |
| Everything works, then 429s | Rate limit (60/min per IP). Behind Caddy every request looks like it comes from the proxy, so a busy server can trip it — raise `RATE_LIMIT` in `HttpApi.java` if it bites. |
| Relay stopped working | Nothing here should touch it. Check `systemctl status teamlocator-relay` and that you *appended* to the Caddyfile rather than replacing it. |
