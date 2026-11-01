# Commands & Permissions Reference (`LeafAnvil`)

`LeafAnvil` registers `/leafanvil` alongside the short alias `/lca`. All commands use explicit `name=value` parameter syntax (`CommandsAPI`, `commands-api-ADR-001`) and work identically on `Paper`, `Spigot`, `Purpur`, `Folia`, `Fabric`, and `NeoForge`.

---

## Root Command & Subcommands (5 Subcommands)

| Command | Parameters | Default Permission | Description |
|---|---|---|---|
| `/leafanvil` *(or `/lca`)* | *(none)* | `leafanvil.admin` (`op`) | Displays the live `.lca` world storage status (`enabled`, `autoMigrate`, `modeBZstd`, `attachedWorlds`, `interceptedStorages`) and per-world counters (`openRegions`, `migrated`, `intercepted`, `dimensionDirectory`). Alias for `/leafanvil status`. |
| `/leafanvil status` | *(none)* | `leafanvil.admin` (`op`) | Displays `.lca` runtime configuration and per-world open/migrated/intercepted `RegionFileStorage` counters across Overworld, Nether (`DIM-1`), The End (`DIM1`), and `26.1+` `dimensions/<namespace>/<dimension>/` directories. |
| `/leafanvil convert` | `[world=<name\|all>] [format=<lca\|mca>]` | `leafanvil.admin` (`op`) | Flushes target world storage sessions and asynchronously transcodes all region files (`region/`, `entities/`, `poi/`) to `.lca` (`format=lca`, default) or reversibly back to vanilla `.mca` (`format=mca`). Preserves original files as `.bak` when `backup-migrated-legacy-files: true`. |
| `/leafanvil compact` | `[world=<name\|all>]` | `leafanvil.admin` (`op`) | Asynchronously compacts open `.lca` region files whose `256-byte` Copy-on-Write (`CoW`) free-slab fragmentation ratio exceeds `compact-fragmentation-threshold` (`0.35` by default) and flushes them to disk. |
| `/leafanvil heal` | `[world=<name\|all>]` | `leafanvil.admin` (`op`) | Flushes target world sessions and runs the 4-tier `.lca` Extended Hamming `(72, 64)` SEC-DED + self-describing `256B` slab reverse-scan + section `octreeOccupancyMask64` quarantine + `CoW` rollback bit-rot healer across all `r.<x>.<z>.lca` files. |
| `/leafanvil reload` | *(none)* | `leafanvil.admin` (`op`) | Reloads `config.yml` and `messages.yml` from disk (`plugins/LeafAnvil/` on Paper/Folia/Spigot, `config/leafanvil/` on Fabric/NeoForge). |

---

## Parameter Reference

| Parameter | Subcommands | Valid Values | Default | Description |
|---|---|---|---|---|
| `world` | `convert`, `compact`, `heal` | Any attached world name (`world`, `overworld`, `the_nether`, `the_end`, `<ns>:<dim>`) or `all` | `all` | Selects a specific attached dimension or all attached dimensions. |
| `format` | `convert` | `lca`, `mca` | `lca` | Target region file format: `lca` transcodes `.mca` / `.linear` into `.lca`; `mca` reversibly transcodes `.lca` back into standard `4 KiB`-sector `.mca`. |

---

## Usage Examples

```text
# Inspect attached worlds, hooked RegionFileStorage count, and open .lca handles
/leafanvil status

# Convert all dimensions (region/, entities/, poi/) to .lca in the background
/leafanvil convert world=all format=lca

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
