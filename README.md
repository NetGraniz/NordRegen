# NordRegen 2.0.1

Removes configured mechanisms and entities from one selected chunk on Paper 26.2 or Folia 26.2. Both platforms use one JAR on JDK 25.

Version 2.x changes the behavior of `/regenchunk`: it cleans up a chunk; it does not regenerate terrain. There are no temporary worlds, seed-based generation or network calls.

## Commands

Stand in the problem chunk and run `/regenchunk`. Review the coordinates and deletion list in chat, then run `/regenchunk confirm` within 45 seconds while in the same chunk. You do not need to leave it.

`/regenchunk cancel` cancels a pending selection, not a running cleanup. You may leave and return before confirming; confirmation from another chunk is refused. No command aliases are registered.

## Permissions

| Permission | Allows | Default |
| --- | --- | --- |
| `nordregen.use` | Select, confirm or cancel a cleanup with `/regenchunk` | Operators |

The executor also checks this permission. A command allowlist entry alone does not authorize deletion.

## Deletion and backups

Matching containers and vehicles, including their contents, are permanently deleted without drops or automatic undo. Back up the world first.

Legitimate machines matching the configuration are removed too. This is an administrator-selected cleanup, not automatic lag detection. Removing a block or trapdoor beneath a player can cause a fall.

Players are never deleted. A configuration that includes `PLAYER` is invalid and disables the plugin.

## Configuration

`config.yml` controls exact block and entity types, trapdoors, buttons, pressure plates, the confirmation window and work budgets. Restart after editing it; hot reload is unsupported.

Defaults target redstone mechanisms, pistons, observers, hoppers, dispensers, droppers, trapdoors, all minecart variants and armor stands. Other terrain, chests, mobs and neighboring chunks are not targeted by the default list.

Only one cleanup runs per server. Each default pass checks at most 256 blocks and 8 entity entries, with a soft 750-microsecond time budget. A full-height scan can take tens of seconds.

Owning-region tick intervals over 150 ms skip a pass without catch-up. Blocks use `RegionScheduler`; entity removals use `EntityScheduler` and recheck the entity's current chunk. Pending entity callbacks are capped at 32. The plugin does not force chunk generation.

Block replacement suppresses neighbor physics and item drops. Lighting and later game processing still have costs.

## Limits

The one-time `Chunk.getEntities()` enumeration is not time-sliced. A pathological chunk can still stall during enumeration; the pass budget is not a hard no-freeze guarantee or evidence of 1000-player capacity.

Entities entering after the snapshot and blocks placed behind the scan cursor may survive. Repeat the selection and confirmation if another cleanup is needed.

Disable or shutdown interrupts remaining work without restoring anything already removed. Use a stopped-server upgrade, not hot loading or unloading.

## Build and tests

See [BUILDING.md](BUILDING.md). Maven tests cover configuration bounds, types, immutable sets and forbidden player deletion.

`test-support/CleanupProbe.java` and `cleanup.cjs` provide synthetic loopback integration checks: executor permissions, cancellation, confirmation while standing in the chunk, paced work, targeted block/entity deletion and preservation of neighboring chunks, unrelated terrain, players and player inventories.

Never install the probe on production. Runtime evidence is stored outside this repository.

Install only the release JAR and keep production configuration and worlds private. Existing `regenchunk` command allowlists remain valid. Do not keep a 1.x regeneration JAR alongside 2.x.
