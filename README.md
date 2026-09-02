# SpogTiers — backend branch

This branch carries **only the Door SMP backend**: the Discord grading bot and the HTTP API the mod
reads. See [backend/README.md](backend/README.md).

The mod itself lives on the version branches — `mc/1.21.11`, `mc/26.1.2`, `mc/26.2` — which is why
there is no `src/` here and no Fabric Loom in the build.

## Why it is a separate branch

The backend has no Minecraft dependency, so it would be byte-identical on all three version
branches. Those branches diverge heavily from each other (different mappings, renamed mixins), so
committing the backend to them would mean maintaining it three times and dragging it through
cherry-picks it has nothing to do with.

This branch is never merged into an `mc/*` branch, and they are never merged into it.

```
./gradlew :backend:shadowJar    # -> backend/build/libs/doorsmp-backend-all.jar
./gradlew :backend:test
```
