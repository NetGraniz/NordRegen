# NordRegen

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

Minimal Paper plugin for regenerating exactly one selected chunk on Nord Fjell.

## Folia limitation

This release remains Paper-only. Regeneration creates and unloads a temporary world; the [official Folia documentation](https://github.com/PaperMC/Folia#current-broken-api) lists runtime world loading/unloading as unsupported. Adding a compatibility flag does not make this safe. Do not install this release on Folia. An alternative regeneration workflow, such as offline regeneration, must be chosen before adapting this plugin. No worlds or production data are migrated here.

## Usage

1. Stand in the chunk that should be restored and run `/regenchunk`.
2. Leave that chunk.
3. Run `/regenchunk confirm` within 45 seconds.

The operation is refused while a player remains in the selected chunk. Only one
regeneration can run at a time. Blocks are copied in bounded batches so the
server is not forced to replace the whole chunk in one tick.

Permission: `nordregen.use` (operators by default).

Chunk regeneration is inherently expensive: Minecraft still has to generate a
fresh chunk from the world's seed. NordRegen removes WorldEdit's unrelated
features, but it cannot make world generation free.
