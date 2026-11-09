# Configuration Reference (`LeafAnvil`)

Configuration files are generated automatically on first server startup under `plugins/LeafAnvil/` on `Paper`, `Spigot`, `Purpur`, and `Folia` (or `config/leafanvil/` on `Fabric` and `NeoForge`).

---

## File Overview

| File | Purpose | Key Settings |
|---|---|---|
| `config.yml` | Runtime `.lca` interception toggle, lazy `.mca`/`.linear`/`.b_linear` and `.lca` `v1` -> `v2` migration, `.bak` backup retention, `.lca` Mode B toggle (`0` = Mode A `0-LZ`, `1` = default Mode B `LCD2` Continuous Atomic Region Dictionary), `256B` slab compaction threshold, and default `DataVersion` | `enabled`, `auto-migrate-legacy`, `backup-migrated-legacy-files`, `compact-fragmentation-threshold`, `default-data-version` |
| `messages.yml` | Configurable operator feedback strings for single-flight busy state, invalid command/parameter syntax, and missing permissions (`REQ-RTP-S-007`) | `busy`, `invalid-command`, `no-permission` |
| `ICD.md` | Automatically extracted `.lca` Binary Format Interface Control Document (`LCA-ICD-1.0`, bundled inside `LeafAnvil.jar` at `docs/ICD.md` and `META-INF/leafanvil/ICD.md`) | Byte offsets, bitfields, curve formulas, checksum/Hamming parity, and static core token tables (`160` blocks, `65` biomes, `128` properties) |

---

## `config.yml` Settings

```yaml
# LeafAnvil (.lca LeafCraft Anvil World Save Format Plugin & Mod)
enabled: true
# Automatically transcode existing .mca / .linear / .b_linear regions to .lca on first open
auto-migrate-legacy: true
# Preserve original .mca / .linear / .b_linear files as .bak after migration
backup-migrated-legacy-files: true
# Free-slab fragmentation ratio triggering automatic compaction on region close
compact-fragmentation-threshold: 0.35
# Default Minecraft DataVersion for newly created empty region headers
default-data-version: 4671
```

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | When `true`, `NmsRegionStorageInterceptor` hooks all `RegionFileStorage` instances (`region/`, `entities/`, `poi/`) on world load so chunk reads and writes use `.lca`. When `false`, worlds attach in passive mode (allowing manual `/leafanvil convert` or `/leafanvil heal` without live interception). |
| `auto-migrate-legacy` | Boolean | `true` | When `true`, opening a region `(rx, rz)` that has no `r.<rx>.<rz>.lca` file but has an existing `r.<rx>.<rz>.mca` (or registered `r.<rx>.<rz>.linear` / `r.<rx>.<rz>.b_linear` `BufferedLinear`) file automatically transcodes it to `.lca` via atomic `.lca.tmp` rename on first access (and upgrades any `.lca` `v1` `0x0001` archive to `v2` `0x0002`). |
| `backup-migrated-legacy-files` | Boolean | `true` | When `true`, lazy migration and `/leafanvil convert` rename the source `.mca` / `.linear` / `.b_linear` / `.lca` file to `<filename>.bak` (for example `r.0.0.mca.bak` or `r.0.0.lca.v1.bak`) instead of deleting it. |
| `compact-fragmentation-threshold` | Double | `0.35` | Free-slab fragmentation ratio (`0.0 .. 1.0`) above which `/leafanvil compact` rewrites a region's `256B` sub-slab pool contiguously and truncates trailing slack. |
| `default-data-version` | Integer | `4671` | Fallback Mojang `DataVersion` written into newly created empty `.lca` superblocks before the first chunk with an explicit `DataVersion` tag is saved (`3953` = 1.21, `4671` = 1.21.4/26.1). |

---

## `messages.yml` Settings (`REQ-RTP-S-007`)

```yaml
# LeafAnvil configurable user feedback messages (REQ-RTP-S-007)
busy: "[LeafAnvil] Another storage task is currently running; please wait."
invalid-command: "[LeafAnvil] Invalid command or parameter: %arg%"
no-permission: "[LeafAnvil] You do not have permission to execute this command."
```

| Key | Default | Description |
|---|---|---|
| `busy` | `"[LeafAnvil] Another storage task is currently running; please wait."` | Sent when `/leafanvil convert`, `/leafanvil compact`, or `/leafanvil heal` is invoked while another asynchronous batch task is already running (`REQ-RTP-S-007`). |
| `invalid-command` | `"[LeafAnvil] Invalid command or parameter: %arg%"` | Sent and logged at `WARNING` level when an unrecognized subcommand or invalid `key=value` parameter is supplied (`%arg%` is replaced with the offending token, `REQ-RTP-S-004`, `REQ-RTP-S-007`). |
| `no-permission` | `"[LeafAnvil] You do not have permission to execute this command."` | Sent and logged at `WARNING` level when a caller lacks `leafanvil.admin`. |

---

## Optional JVM System Properties

| System Property | Default | Description |
|---|---|---|
| `-Dleafanvil.fsync=true` | `false` | When `false` (default), positional `FileChannel#write` updates rely on the OS page cache and LRU region eviction uses `LcaLiveArchive#closeWithoutForce()` to avoid blocking hardware `fsync` stalls. Set `-Dleafanvil.fsync=true` to issue an explicit `FileChannel#force(false)` barrier on every `flush()`. |
