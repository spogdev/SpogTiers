# SpogTiers — backend branch

This branch carries the two server-side services, which are separate programs that happen to share a
repository:

- **`backend/`** — the Door SMP tierlist: the Discord grading bot and the grade API. See
  [backend/README.md](backend/README.md).
- **`resolver/`** — the Discord name resolver, which turns a Minecraft UUID into the Discord account
  a player linked on MCTiers or SubTiers.

They share no code, no token and no process. Either can be deployed or restarted without touching
the other, and the tierlist bot going down does not take player names with it.

The mod itself lives on the version branches — `mc/1.21.11`, `mc/26.1.2`, `mc/26.2` — which is why
there is no `src/` here and no Fabric Loom in the build.

## Why it is a separate branch

The backend has no Minecraft dependency, so it would be byte-identical on all three version
branches. Those branches diverge heavily from each other (different mappings, renamed mixins), so
committing the backend to them would mean maintaining it three times and dragging it through
cherry-picks it has nothing to do with.

This branch is never merged into an `mc/*` branch, and they are never merged into it.

```
./gradlew :backend:build        # -> dist/doorsmp-backend-all.jar
./gradlew :backend:test
./gradlew :resolver:build       # -> dist/spogtiers-resolver-all.jar
```
