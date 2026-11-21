# Commands & Permissions Reference (`LeafAnvil`)

`LeafAnvil` registers `/leafanvil` alongside the short alias `/lca`. All commands use explicit `name=value` parameter syntax (`CommandsAPI`, `commands-api-ADR-001`) and work identically on `Paper`, `Spigot`, `Purpur`, `Folia`, `Fabric`, and `NeoForge`.

---

## Root Command & Subcommands (9 Subcommands)

| Command | Parameters | Default Permission | Description |
|---|---|---|---|
| `/leafanvil` *(or `/lca`)* | *(none)* | `leafanvil.admin` (`op`) | Displays the live `.lca` world storage status (`enabled`, `autoMigrate`, `lcd2Level`, `modeBLcd2`, `convertWorkers`, `dutyCycle`, `attachedWorlds`, `bitHealth`), any active batch task progress, and per-world counters (`openRegions`, `migrated`, `dimensionDirectory`). Alias for `/leafanvil status`. |
| `/leafanvil status` | *(none)* | `leafanvil.admin` (`op`) | Displays `.lca` runtime configuration, BIT health status, active batch conversion/healing progress snapshot, and per-world open/migrated `RegionFileStorage` counters across Overworld, Nether (`DIM-1`), The End (`DIM1`), and `26.1+` `dimensions/<namespace>/<dimension>/` directories. |
| `/leafanvil bit` | *(none)* | `leafanvil.admin` (`op`) | Executes the Built-In Test (`[LCA bit]`) PBIT and CBIT codec, `RegionFileStorage` interception, section bridge, and network carrier compatibility diagnostics (`LCA_CODEC_WIRE_PBIT`, `LCA_DISK_STORAGE_COMPAT`, `LCA_SECTION_BRIDGE_COMPAT`, `LCA_NETWORK_CARRIER_COMPAT`). |
| `/leafanvil convert` | `[world=<name\|all>] [format=<lca\|mca>] [workers=<n\|auto>]` | `leafanvil.admin` (`op`) | Flushes target world storage sessions and asynchronously transcodes all region files (`region/`, `entities/`, `poi/`) to `.lca` (`format=lca`, default) or reversibly back to vanilla `.mca` (`format=mca`) on a bounded low-priority worker pool with `BudgetPacer` duty-cycle pulses, TPS/overtime backoff, and `5s` rate/ETA telemetry. Preserves original files as `.bak` when `backup-migrated-legacy-files: true`. |
| `/leafanvil compact` | `[world=<name\|all>]` | `leafanvil.admin` (`op`) | Asynchronously compacts open `.lca` region files whose `256-byte` Copy-on-Write (`CoW`) free-slab fragmentation ratio exceeds `compact-fragmentation-threshold` (`0.35` by default) and flushes them to disk. |
| `/leafanvil heal` | `[world=<name\|all>] [workers=<n\|auto>]` | `leafanvil.admin` (`op`) | Flushes target world sessions and runs the paced 4-tier `.lca` Extended Hamming `(72, 64)` SEC-DED + self-describing `256B` slab reverse-scan + section `octreeOccupancyMask64` quarantine + `CoW` rollback bit-rot healer across all `r.<x>.<z>.lca` files. |
| `/leafanvil pause` | *(none)* | `leafanvil.admin` (`op`) | Cooperatively pauses the active `/leafanvil convert` or `/leafanvil heal` batch task once in-flight region files complete. |
| `/leafanvil resume` | *(none)* | `leafanvil.admin` (`op`) | Resumes a paused `/leafanvil convert` or `/leafanvil heal` batch task. |
| `/leafanvil cancel` | *(none)* | `leafanvil.admin` (`op`) | Cooperatively cancels the active `/leafanvil convert` or `/leafanvil heal` batch task after in-flight atomic region writes finish safely. |
| `/leafanvil reload` | *(none)* | `leafanvil.admin` (`op`) | Reloads `config.yml` and `messages.yml` from disk (`plugins/LeafAnvil/` on Paper/Folia/Spigot, `config/leafanvil/` on Fabric/NeoForge). |

---

## Parameter Reference

| Parameter | Subcommands | Valid Values | Default | Description |
|---|---|---|---|---|
| `world` | `convert`, `compact`, `heal` | Any attached world name (`world`, `overworld`, `the_nether`, `the_end`, `<ns>:<dim>`) or `all` | `all` | Selects a specific attached dimension or all attached dimensions. |
| `format` | `convert` | `lca`, `mca` | `lca` | Target region file format: `lca` transcodes `.mca` / `.linear` into `.lca`; `mca` reversibly transcodes `.lca` back into standard `4 KiB`-sector `.mca`. |
| `workers` | `convert`, `heal` | `auto` or positive integer (`1`..`64`, capped at `availableProcessors()`) | `auto` (`config.yml` `convert-worker-threads`) | Overrides the concurrent `LeafAnvil-Converter-*` worker core cap for the batch run. |

---

## Usage Examples

```text
# Inspect attached worlds, BIT health, hooked RegionFileStorage count, and open .lca handles
/leafanvil status

# Run [LCA bit] PBIT/CBIT codec and runtime compatibility self-tests
/leafanvil bit

# Convert all dimensions (region/, entities/, poi/) to .lca in the background (paced default or explicit worker cap)
/leafanvil convert world=all format=lca
/leafanvil convert world=world format=lca workers=2

# Pause, resume, or cancel an active conversion mid-run
/leafanvil pause
/leafanvil resume
/leafanvil cancel

# Reversibly convert all .lca files back to vanilla .mca
/leafanvil convert world=all format=mca

# Compact fragmented 256B CoW slabs in the overworld
/leafanvil compact world=world

# Verify and self-heal bit rot across all .lca region files
/leafanvil heal world=all

# Hot-reload config.yml and messages.yml
/leafanvil reload
```

---

## Permission Nodes

| Permission Node | Default | Behavior |
|---|---|---|
| `leafanvil.admin` | `op` (Permission Level 4 on Fabric/NeoForge) | Grants access to all `/leafanvil` and `/lca` status, conversion, compaction, bit-rot healing, and reload commands. |
