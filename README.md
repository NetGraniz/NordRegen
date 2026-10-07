# NordRegen 2.0.1 — chunk cleanup

One JAR for Paper 26.2 and Folia 26.2, JDK 25. **Breaking behavior change:** this plugin no longer regenerates terrain. It removes configured mechanisms and entities in one selected chunk. No temporary worlds, seed-based generation, network calls or WorldEdit dependency.

## Commands

Stand in the problem chunk and use `/regenchunk`. Review the coordinates and deletion list in chat. Use `/regenchunk confirm` within 45 seconds while still in the same chunk. You do NOT need to leave. `/regenchunk cancel` cancels a pending selection, not an already-running operation. Leaving and returning before confirmation is allowed; confirming in another chunk is refused. Permission `nordregen.use` defaults to operators and is also checked in the executor. No command aliases are registered.

**Destructive operation:** matching containers/vehicles and their contents are permanently deleted without drops or automatic undo. Back up the world first. Legitimate machines matching the config are also removed: this is an administrator-selected cleanup, not automatic lag detection. Removing a trapdoor/block under a player may cause a fall. Players themselves are never deleted, even if config attempts to include PLAYER (invalid config disables the plugin).

## Configuration

`config.yml` controls exact block/entity types, inclusion of trapdoors/buttons/pressure plates, confirmation duration and budgets. Defaults target redstone mechanisms, pistons, observers, hoppers, dispensers/droppers, trapdoors, all minecart variants and armor stands. Other terrain, chests, mobs and neighboring chunks are not targeted by the default list. Restart after editing config; no hot reload.

Only one cleanup runs per server. The default pass checks at most 256 blocks and 8 entity entries, with a soft 750-microsecond time budget. The scan covers the full chunk height; it can take tens of seconds. Slow owning-region tick intervals over 150 ms skip a pass without catch-up. Block work uses RegionScheduler, entity removals use EntityScheduler and recheck the entity's current chunk; pending entity callbacks are capped at 32. No forced chunk generation. A block replacement suppresses neighbor physics and item drops; subsequent lighting/game processing still has costs.

The public `Chunk.getEntities()` enumeration occurs once and is **not** time-sliced. This is not a hard no-freeze guarantee for an already pathological chunk, nor a 1000-player load test. Entities entering after that snapshot and blocks placed behind the scan cursor may survive; repeat a confirmed cleanup if needed. Disable/shutdown interrupts remaining work; removed objects are not restored. Hot-loading/unloading is unsupported; use a normal stopped-server upgrade.

## Build and tests

See [BUILDING.md](BUILDING.md). Maven tests cover configuration bounds, types, immutable sets and forbidden player deletion. `test-support/CleanupProbe.java` and `cleanup.cjs` provide synthetic loopback-only integration tests: executor permissions, cancellation, explicit confirmation while remaining in the chunk, paced work, targeted block/entity deletion, neighboring chunk preservation and untouched terrain/player/inventory. The helper must NEVER be installed on production. Runtime evidence is saved outside this repository.

Only the release JAR belongs on the real server. Keep production config/world data private. Existing `regenchunk` command allowlists remain valid. Old 1.x releases implement regeneration and must not be kept alongside 2.x.
