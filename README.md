# Trample Grass

Trample Grass is a NeoForge mod for Minecraft 1.21.1 that tracks grass blocks recently stepped on by entities. When another entity steps onto a watched grass block before the watch expires, the block has a configurable chance to become a dirt path. Failed rolls still refresh the watch duration.

## Configuration

Config file: `config/trample_grass-common.toml`

- `trampling.watchDurationTicks`: How long a grass block stays watched after an entity steps on it. `20` ticks is roughly one second.
- `trampling.pathChancePercent`: Percent chance that a qualifying later step turns watched grass into a dirt path. `0` disables conversion; `100` makes qualifying steps always convert.
- `regrowth.pathRegrowthSlowdown`: How much slower dirt paths regrow than normal random-tick grass spreading. The default `4` makes regrowth four times less likely than normal grass spread.
- `debug.debugLogging`: Enables detailed debug logging for watch and conversion decisions.

## Behavior Notes

- The mod runs the gameplay logic server-side.
- Any entity with a footprint on the ground can start or trigger watched grass, including players, mobs, and non-living entities.
- Standing still on one block does not roll every tick. A roll happens when an entity enters a grass block that is already being watched by earlier traffic.
- Larger entities can affect every grass block under their collision box.
- Any dirt path in a loaded, ticking chunk is checked like a grass block on a random tick: if grass could not survive there, it becomes dirt; if conditions are bright and valid, it can regrow itself into grass. Regrowth follows the world's `randomTickSpeed` and defaults to four times slower than normal grass spread.

## Compatibility

- Minecraft: `1.21.1`
- Loader: NeoForge `21.1.228+`
- Java: `21`
- Side: Both client and server. Gameplay logic runs server-side.

## Development Checklist

- Local `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build` passes.
- GitHub Actions build passes on push and pull request.
- CI uploads `build/libs/*.jar` artifacts from workflow runs.
- Runtime smoke test: start a server or single-player world, set a low `watchDurationTicks` and high `pathChancePercent`, then walk multiple entities over the same grass block and confirm it can become a dirt path.

## License

Trample Grass is dedicated to the public domain under CC0 1.0 Universal.

NeoForge MDK template files remain under their original MIT license; see `TEMPLATE_LICENSE.txt`.
