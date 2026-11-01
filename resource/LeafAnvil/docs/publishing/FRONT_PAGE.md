<!--
Single source for every LeafAnvil storefront page (BuiltByBit, Modrinth, Hangar, SpigotMC).
Follows docs/dev/RULES.md Rule D-006 (first-person plain voice, 3-Zone hierarchy,
ASCII punctuation only, concrete benchmark numbers and honest caveats).
  Title: "LeafAnvil"  tagline: "Zero-fork .lca (LeafCraft Anvil) world save format plugin and mod with lazy .mca migration and bit-rot self-healing for Paper, Folia, Fabric & NeoForge"
-->

<div align="center">

# {{name}}: Zero-Fork `.lca` World Save Format, `.mca` Converter & Self-Healing Storage

[![Build](https://github.com/LeafCraftDev/LeafAnvil/actions/workflows/gradle.yml/badge.svg)](https://github.com/LeafCraftDev/LeafAnvil/actions/workflows/gradle.yml)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Platforms](https://img.shields.io/badge/Platforms-Paper%20%7C%20Folia%20%7C%20Spigot%20%7C%20Fabric%20%7C%20NeoForge-orange)](#install)

</div>

## What it is

**{{name}} is a drop-in plugin and mod that switches your server's world storage (`region/`, `entities/`, and `poi/`) to the `.lca` (LeafCraft Anvil) format without needing a custom server fork.** Drop the jar into `plugins/` or `mods/`, start the server, and existing `.mca` (or `.linear`) region files migrate automatically as chunks are opened while keeping `.mca.bak` backups on disk.

I built it because vanilla `.mca` wastes roughly a third of every region file on 4 KiB sector padding and slow `Zlib` compression, while whole-region `.linear` files require a custom server fork, have to re-compress all 1,024 chunks in a region just to save one dirty chunk, and put all 1,024 chunks into a single compressed stream where one corrupted byte can break the whole region. Across 767 real region files (`293,317` chunks), `.lca` cuts world disk size from `2,527.9 MB` to `1,535.3 MB` (`-39.3%`, and `-8.2%` smaller than `.linear`) in pure zero-decompression Mode A or `1,269.8 MB` (`-49.8%` vs `.mca`, `-24.1%` vs `.linear`) in default per-chunk `Zstd-1` Mode B, finishes server shutdown world saves `2.2x to 15.5x` faster across Paper, Folia, Leaf `.linear`, Fabric (+ C2ME), and NeoForge (+ C2ME), reads cold chunks `2.53x` faster than `.mca` (`39.0x` faster than cold `.linear`), and updates individual chunks in `256-byte` Copy-on-Write slabs with 4-tier on-disk redundancy and self-healing against bit rot.

One jar for Paper and Spigot (1.20+), Folia (1.21+), Fabric (26.1+), and NeoForge (26.1+). While the `{{name}}` runtime plugin and mod is commercial software (`LicenseRef-LeafCraft-Commercial`), the `.lca` binary specification ([`ICD.md`](../../../../api/anvil-api/docs/ICD.md)) and standalone Java reader/writer codec ([`anvil-api`](../../../../api/anvil-api/README.md)) are open-source under the MIT License - so your worlds are never locked in, third-party tools can read `.lca` files directly, and you can convert worlds to `.lca` or back to vanilla `.mca` at any time (`/leafanvil convert`).

### The short version

**Replaces vanilla `.mca` and `.linear` region storage at runtime on stock Paper, Folia, Spigot, Fabric, and NeoForge servers - cutting world disk size by 39.3% to 49.8%, speeding up server shutdown saves 2.2x to 15.5x, isolating faults with 4-tier redundancy, and reversibly converting back to `.mca` whenever you want.**

- [**Easy**](#install): drop `{{jar}}` into `plugins/` or `mods/` and start the server; existing `.mca` regions migrate automatically on open (or in bulk via `/leafanvil convert`).
- [**Safe**](#resilience-redundancy-and-4-tier-self-healing): keeps `.mca.bak` backups during migration, writes updates to fresh `256-byte` Copy-on-Write slabs with prior-generation rollback so reads never tear, and embeds redundant Hamming parity and self-describing slab headers so bit rot heals automatically.
- [**Fast**](#performance): `2.2x to 15.5x` faster world save on server stop across all seven tested server configurations (`5.5s` vs `86.1s` on Paper, `5.0s` vs `47.7s` on Folia, `5.2s` vs `55.6s` on native `.linear`), `1.12x to 1.70x` faster median random teleport arrival across Paper, Folia, Fabric (+ C2ME), and NeoForge (`16.8x` faster than native `.linear` and `9.4x` faster on cold Folia teleports), `64.68 us` cold 1-chunk read (`2.53x` faster than `.mca`, `39.0x` faster than cold `.linear`), and `395.94 us` single dirty chunk save (`> 120x` faster than `.linear`).
- [**Reversible**](#features): run `/leafanvil convert world=all format=mca` at any time to convert every `.lca` file back to standard vanilla `.mca` files, or use the open-source MIT `anvil-api` codec offline without the plugin.
- [**Open spec**](#why-i-made-it): commercial runtime jar paired with an open MIT binary specification (`LCA-ICD-1.0`) and MIT reference reader/writer (`anvil-api`).
- [**One jar**](#install): runs on Paper, Spigot, Folia, Fabric, and NeoForge with native integration for `LeafRTP` and `LeafMap`.

<details>
<summary><b>More highlights</b></summary>

- **Intercepts all three dimension storage folders:** hooks `region/` (terrain chunks), `entities/` (mob/entity storage), and `poi/` (villager beds, job sites, portals) across Overworld, Nether (`DIM-1`), The End (`DIM1`), and `26.1+` `dimensions/<namespace>/<dimension>/` folders.
- **Zero-LZ Mode A & Zstd-1 Mode B:** ships with Mode B enabled by default (`1,269.8 MB` vs `2,527.9 MB` `.mca`, `-49.8%` disk size and `-24.1%` smaller than `.linear`, with Extended Hamming `(72, 64)` header parity), or set `mode-b-zstd: false` for Mode A pure zero-decompression reads (`1,535.3 MB`, `-39.3%` vs `.mca` and `-8.2%` smaller than `.linear Zstd-1`).
- **4-tier redundancy & bit-rot self-healing (`/leafanvil heal`):** pairs every 64-bit chunk pointer with a redundant Extended Hamming `(72, 64)` error-correcting parity byte, tags every `256B` slab with its own chunk coordinate so wiped directory pointers rebuild from disk, checks per-section 64-bit occupancy syndromes so a damaged `16x16x16` section falls back to its redundant dominant block (`90.8%` of voxels) while keeping the other 23 sections intact, and retains prior Copy-on-Write slab generations for instant rollback.
- **Eliminates external `.mcc` oversized chunk files:** chunks up to `16 MiB` are stored inline in the `256B` slab pool without spilling into sidecar `c.x.z.mcc` files.

</details>

## Common setups

These work out of the box with the default `config.yml`:

- **Gradual zero-downtime migration on an existing server:** Drop `{{jar}}` into `plugins/` or `mods/`. With `auto-migrate-legacy: true` and `backup-migrated-legacy-files: true` (the shipped defaults), each `r.x.z.mca` file is converted to `r.x.z.lca` on first access and the original is kept as `r.x.z.mca.bak`.
- **Full-world batch conversion (`/leafanvil convert`):** Run `/leafanvil convert world=all format=lca` from console to convert every existing `.mca` file across all dimensions in the background.
- **Reverting to vanilla `.mca` before removing the plugin/mod:** Run `/leafanvil convert world=all format=mca`, wait for the completion message in console, and remove `{{jar}}`.

---

## Install

**Requirements:** Paper or Spigot 1.20+, Folia 1.21+, Fabric 26.1+, or NeoForge 26.1+. Java 17+ (Java 21+ on 1.20.5+, Java 25 on 26.x).

1. Drop `{{jar}}` into `plugins/` (Paper, Spigot, Folia) or `mods/` (Fabric, NeoForge) and start the server.
2. Run `/leafanvil status` (or `/lca status`) to verify that `interceptedStorages` shows `3` per loaded dimension (`region/`, `entities/`, and `poi/`).
3. **Optional:** Run `/leafanvil convert world=all format=lca` to batch-convert all existing `.mca` files in the background, or let `auto-migrate-legacy: true` convert regions lazily as players explore.

---

## Features

- **Zero-fork runtime `RegionFileStorage` interception:** dynamically generates lightweight JVM bridge classes at startup to hook vanilla and Paper/Folia Moonrise `RegionFileStorage` caches (`region/`, `entities/`, `poi/`) without modifying your server jar.
- **Copy-on-Write (`CoW`) `256-byte` sub-slab allocator:** replaces Mojang's `4,096-byte` padded sectors with `256-byte` sub-slabs managed by an in-memory free-list and a 4-epoch Copy-on-Write grace ring, eliminating `4 KiB` trailing zero waste and preventing torn concurrent reads.
- **Inline uniform sections & slab-local micro-palettes:** `66.4%` of chunk sections in a typical world contain a single block state (`air`, `stone`, `deepslate`); `.lca` packs uniform sections directly into the 16-bit section directory (`0` body bytes) and encodes common vanilla blocks and property suffixes in 1 byte.
- **Reversible `.lca <-> .mca` batch transcoder:** `/leafanvil convert [world=<name|all>] [format=<lca|mca>]` converts all dimension folders (`region/`, `entities/`, `poi/`) between `.lca` and vanilla `.mca` on background worker threads using atomic `.tmp` file renames.
- **Online slab compaction & 4-tier redundant bit-rot healer:** `/leafanvil compact` defragments open region files whose free-slab ratio exceeds `compact-fragmentation-threshold`, and `/leafanvil heal` uses redundant Hamming `(72, 64)` parity bytes, self-describing `256B` slab headers, per-section 64-bit occupancy syndromes, and Copy-on-Write prior-generation pointers to repair pointer flips, rebuild wiped directories, and isolate damaged `16x16x16` sections.

---

## Performance

I measure both end-to-end live server behavior across seven platform configurations (**Paper `26.2`**, **Folia `26.2`**, **Leaf `26.2` native `.linear`**, **Fabric `26.1.2`**, **Fabric `26.1.2` + `C2ME`**, **NeoForge `26.1.2`**, and **NeoForge `26.1.2` + `C2ME`**, `111` completed runs) and isolated codec throughput against real `.mca` and `.linear` region files. Every run in the live server harness starts from an identical Btrfs `--reflink=auto` copy-on-write clone of the same frozen 3-dimension world template (`767` region files, `293,317` chunks across Overworld, Nether, and End; `2,527.9 MB` in `.mca`, `1,672.1 MB` in `.linear`, `1,535.3 MB` in `.lca` Mode A `0-LZ`, and `1,269.8 MB` in `.lca` Mode B `+ Zstd-1`; `100%` file and chunk parity verified before testing) with counterbalanced arm ordering (`without-lca`, `with-lca` Mode A, and `with-lca-b` Mode B) on alternating rounds, isolated Threadripper 7970X CPU cores (`nice -10`, `ionice -c2 -n0` on cores `0-23,32-55`), equalized `5x5` (`25`-chunk) view and simulation distance (`view-distance=2`, `simulation-distance=2`, `--view-distance 2` matching RTP's destination ticket), 24 live Mineflayer bots running a 4-stage `/rtpstress ramp` (`5, 10, 20, 40 TP/s`, `1,496` scheduled teleports per full run), and a timed `stop` world-save flush.

**Live Multi-Platform Server A/B/C Benchmark (`with-lca` Mode A `0-LZ` & `with-lca-b` Mode B `+ Zstd-1` vs `without-lca` `.mca` / `.linear`, `767` Region Files / `293,317` Chunks, `111` Runs):**

| Platform (`n` runs: `A/B/Base`, `pairs`) | World Disk (`Mode A` / `Mode B` vs Base) | Completed TPs (`Mode A` / `Mode B` vs Base, `1,496` max) | Stage 0 `p50` (Cold `p50`: `A` / `B` vs Base) | Stage 0 `p95` (`MSPT p95`: `A` / `B` vs Base) | Main CPU/Chunk (`Chunks/TP`: `A` / `B` vs Base) | World Save on Shutdown (`Mode A` / `Mode B` vs Base) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Paper `26.2`** (`6/6/6`, `n=6`) | **`1,545.2`** / **`1,278.8 MB`** vs `2,532.3 MB` (`-39.0%` / `-49.5%`) | **`1,487`** / **`1,479`** vs `1,442` (`1.03x`) | **`200 ms`** / **`200 ms`** vs `224 ms` (`200` / `200` vs `228 ms` cold, `1.13x` / `1.14x` faster) | **`221 ms`** (`31.0 ms`) / **`236 ms`** (`30.9 ms`) vs `466 ms` (`193.8 ms`, **`2.11x` / `1.98x` faster**) | **`1.45 ms`** (`75.6`) / **`1.43 ms`** (`75.2`) vs `5.79 ms` (`17.5`, **`4.01x` / `4.05x`**) | **`5.54 +- 0.21 s`** / **`5.55 +- 0.23 s`** vs `86.12 +- 72.67 s` (**`15.5x` faster**) |
| **Folia `26.2`** (`6/6/6`, `n=6`) | **`1,545.5`** / **`1,279.0 MB`** vs `2,536.1 MB` (`-39.1%` / `-49.6%`) | **`1,496`** / **`1,496`** vs `1,274` (**`1.17x`**) | **`103 ms`** / **`105 ms`** vs `164 ms` (**`103` / `105` vs `968 ms` cold, `9.40x` / `9.25x` faster**) | **`203 ms`** (`19.3 ms`) / **`203 ms`** (`18.5 ms`) vs `218 ms` (`96.3 ms`, `1.08x` faster) | **`0.73 ms`** (`97.9`) / **`0.78 ms`** (`98.0`) vs `3.29 ms` (`6.6`, **`4.53x` / `4.23x`**) | **`5.03 +- 0.01 s`** / **`5.02 +- 0.00 s`** vs `47.69 +- 36.44 s` (**`9.5x` faster**) |
| **Leaf `26.2` (`.linear`)** (`5/5/4`, `n=4`) | **`1,546.4`** / **`1,279.2 MB`** vs `1,672.2 MB` (`-7.5%` / `-23.5%`) | **`1,483`** / **`1,483`** vs `10` (**`152.1x`**; `4.95` vs `0.68 TP/s`) | **`200 ms`** / **`200 ms`** vs `3,369 ms` (**`201` / `201` vs `5,320 ms` cold, `16.8x` / `26.5x` faster**) | **`326 ms`** (`28.3 ms`) / **`314 ms`** (`29.6 ms`) vs `5,178 ms` (`2,731.4 ms`, **`15.9x` / `16.5x` faster**) | **`1.35 ms`** (`75.6`) / **`1.39 ms`** (`76.1`) vs `4.07 ms` (`26.6`, **`3.00x` / `2.93x`**) | **`5.18 +- 0.01 s`** / **`5.20 +- 0.04 s`** vs `55.56 +- 4.66 s` (**`10.7x` faster**) |
| **Fabric `26.1.2`** (`5/5/5`, `n=5`) | **`1,569.2`** / **`1,298.7 MB`** vs `2,530.7 MB` (`-38.0%` / `-48.7%`) | **`1,494`** / **`1,494`** vs `1,491` (`1.00x`) | **`8 ms`** / **`7 ms`** vs `12 ms` (`18` / `27` vs `19 ms` cold, **`1.58x` / `1.67x` faster**) | **`23 ms`** (`22.3 ms`) / **`19 ms`** (`20.8 ms`) vs `27 ms` (`22.4 ms`, **`1.18x` / `1.47x` faster**) | **`1.58 ms`** (`79.0`) / **`1.50 ms`** (`79.9`) vs `1.59 ms` (`79.4`, `1.00x` / `1.06x`) | **`4.33 +- 0.04 s`** / **`4.31 +- 0.14 s`** vs `11.60 +- 0.90 s` (**`2.7x` faster**) |
| **Fabric `26.1.2` + `C2ME`** (`5/5/5`, `n=5`) | **`1,576.0`** / **`1,304.0 MB`** vs `2,531.2 MB` (`-37.7%` / `-48.5%`) | **`1,496`** / **`1,494`** vs `1,496` (`1.00x`) | **`8 ms`** / **`7 ms`** vs `11 ms` (`19` / `14` vs `16 ms` cold, **`1.36x` / `1.47x` faster**) | **`23 ms`** (`20.7 ms`) / **`18 ms`** (`23.0 ms`) vs `22 ms` (`22.0 ms`, `0.96x` / **`1.22x` faster**) | **`1.17 ms`** (`79.6`) / **`1.17 ms`** (`79.6`) vs `1.16 ms` (`79.6`, `1.00x` / `0.99x`) | **`4.09 +- 0.37 s`** / **`3.93 +- 0.37 s`** vs `9.43 +- 0.42 s` (**`2.3x` / `2.4x` faster**) |
| **NeoForge `26.1.2`** (`5/5/5`, `n=5`) | **`1,569.6`** / **`1,298.5 MB`** vs `2,530.4 MB` (`-38.0%` / `-48.7%`) | **`1,486`** / **`1,494`** vs `1,493` (`1.00x`) | **`15 ms`** / **`21 ms`** vs `25 ms` (`16` / `21` vs `25 ms` cold, **`1.70x` / `1.19x` faster**) | **`42 ms`** (`21.2 ms`) / **`46 ms`** (`23.4 ms`) vs `47 ms` (`22.3 ms`, `1.12x` / `1.02x` faster) | **`1.56 ms`** (`78.8`) / **`1.58 ms`** (`79.5`) vs `1.57 ms` (`78.9`, `1.01x` / `0.99x`) | **`4.27 +- 0.10 s`** / **`4.72 +- 0.43 s`** vs `12.42 +- 0.89 s` (**`2.9x` / `2.6x` faster**) |
| **NeoForge `26.1.2` + `C2ME`** (`5/5/5`, `n=5`) | **`1,578.7`** / **`1,305.1 MB`** vs `2,531.1 MB` (`-37.6%` / `-48.4%`) | **`1,494`** / **`1,496`** vs `1,496` (`1.00x`) | **`12 ms`** / **`16 ms`** vs `16 ms` (`13` / `17` vs `17 ms` cold, **`1.28x` / `0.95x`**) | **`42 ms`** (`18.2 ms`) / **`49 ms`** (`22.6 ms`) vs `54 ms` (`19.3 ms`, **`1.28x` / `1.10x` faster**) | **`1.17 ms`** (`79.3`) / **`1.18 ms`** (`79.2`) vs `1.18 ms` (`79.8`, `1.01x` / `1.00x`) | **`4.14 +- 0.34 s`** / **`4.09 +- 0.38 s`** vs `9.09 +- 0.32 s` (**`2.2x` faster**) |

**What these numbers prove - and the engineering trade-offs to keep in mind:**

- **Universal disk & shutdown wins across all 7 server configurations (`111` runs):** On identical `293,317`-chunk worlds (`767` region files), `.lca` shrinks template disk usage from `2,527.9 MB` (`.mca`) and `1,672.1 MB` (`.linear`) to **`1,535.3 MB` in Mode A (`0-LZ`, `-39.3%` vs `.mca`, `-8.2%` vs `.linear`)** and **`1,269.8 MB` in Mode B (`+ Zstd-1`, `-49.8%` vs `.mca`, `-24.1%` vs `.linear`)**. On server `stop`, `.lca` finishes world saves in **`3.93 to 5.55 s`** across every platform - **`15.5x` faster on Paper** (`5.54 +- 0.21 s` vs `86.12 +- 72.67 s`), **`9.5x` faster on Folia** (`5.02 +- 0.00 s` vs `47.69 +- 36.44 s`), **`10.7x` faster than native `.linear`** (`5.18 +- 0.01 s` vs `55.56 +- 4.66 s`), and **`2.2x to 2.9x` faster on Fabric and NeoForge** (both stock and with `C2ME`).
- **Head-to-head against native `.linear` (`leaf-linear` `26.2`):** Because whole-region `.linear` packs all `1,024` chunks into one shared `Zstd` stream, uncached random teleports force the server to decompress entire region files on read and re-compress all `1,024` chunks whenever a newly ticked chunk dirties a region. Under the 24-bot `/rtpstress ramp`, native `leaf-linear` collapses to `0.68 TP/s` (`9.75 +- 2.63` completed teleports out of `1,496`, `3,369 ms` Stage 0 `p50`, `5,320 ms` cold `p50`, and `2,731.4 ms` `MSPT p95`). Replacing `.linear` with `.lca` on the same Leaf `26.2` server completes **`1,483` teleports (`152.1x` more)** at **`200 ms` `p50` (`16.8x` faster, `26.5x` faster cold)** and **`28.3 to 29.6 ms` `MSPT p95` (`92x` lower tail tick time)**.
- **`4x` lower main-thread CPU per chunk & full view-distance streaming on Paper and Folia:** With `view-distance=2` and `simulation-distance=2` locked to a `5x5` (`25`-chunk) square on both server and client, `.lca` streams **`75.2 to 75.6 chunks/teleport` on Paper** (vs `17.5` on `.mca`) and **`97.9 to 98.0 chunks/teleport` on Folia** (vs `6.6` on `.mca`), while cutting **Main CPU / Chunk by `4.01x to 4.05x` on Paper** (`1.43-1.45 ms` vs `5.79 ms`) and **`4.23x to 4.53x` on Folia** (`0.73-0.78 ms` vs `3.29 ms`). Tail tick latency (`Stage 0 MSPT p95`) drops from `193.85 +- 340.71 ms` to **`30.93 +- 2.06 ms` on Paper** (`6.3x` lower) and from `96.30 +- 226.96 ms` to **`18.50 +- 3.64 ms` on Folia** (`5.2x` lower, with cold `p50` arrival **`9.25x to 9.40x` faster**: `103-105 ms` vs `968 ms`).
- **Mode A (`0-LZ`) vs Mode B (`+ Zstd-1`) & `C2ME` compatibility (`Fabric` / `NeoForge`):** Across all 7 configurations, Mode B (`mode-b-zstd: true`, default) saves an extra `265.5 MB` (`-17.3%`) over Mode A with identical throughput (`1,479 to 1,496` completed TPs) and identical shutdown speed (`3.93 to 5.55 s`). On `Fabric` and `Fabric + C2ME`, Mode B edges out Mode A on `Stage 0 p50` (`7 ms` vs `8 ms`, **`1.67x` and `1.47x` faster** than `.mca`) and `p95` (`18-19 ms` vs `23 ms`). On `NeoForge` and `NeoForge + C2ME`, Mode A delivers the lowest teleport latency (`15 ms` vs `25 ms` on stock NeoForge, **`1.70x` faster**; `12 ms` vs `16 ms` with `C2ME`, **`1.28x` faster**), whereas Mode B trades `4-6 ms` of `Zstd-1` decompression for `48.7%` smaller disk usage (`1,298.5 MB` vs `2,530.4 MB`).
- **Isolated codec microbenchmarks (`overworld_mountain_r.0.0.mca`, 64 chunks):** Outside the server loop, `.lca` Mode A reads a cold random chunk in **`64.68 us`** (`2.53x` faster than `.mca` at `163.93 us`, `39.0x` faster than cold `.linear` at `2,519.12 us`; Mode B reads in `87.27 us`, `1.88x` vs `.mca`), sweeps all 64 chunks in **`3.67 ms`** (`2.12x` faster than `.mca`, `1.67x` faster than `.linear`), probes an RTP terrain window (`Y=[48..112]`) in **`8.55 us`** (`12.84x` faster than `.mca`), reads a single `16x16x16` section in **`1.31 us`** (`0.93 us` uniform, `118.6x` faster than `.mca`), saves a single dirty chunk in **`395.94 us`** (`1.12x` faster than `.mca`, `> 120x` faster than whole-region `.linear` at `49 to 206 ms`), and completes **10,000 concurrent Copy-on-Write saves** with **`100.0%` `256B` slab reuse, `0 B` file growth, and `0` torn reads**. Full multi-platform statistical tables (`2A`, `2B`, `2C`, and per-arm distributions), binary format layout, and reproduction commands: [`README.md`](../../README.md), [`ICD.md`](../../../../api/anvil-api/docs/ICD.md), and [`anvil-api`](../../../../api/anvil-api/README.md).

---

## How it works

All `.lca` region interception, lazy `.mca` migration, 3D space-filling curve selection, and `256-byte` Copy-on-Write slab allocation run automatically in the background from the shipped `config.yml` defaults - operators never configure space-filling curves or slab offsets by hand:

- **Runtime `RegionFileStorage` & Moonrise bridge:** When a world loads, `LeafAnvil` locates the server's `RegionFileStorage` instances for `region/`, `entities/`, and `poi/` (`9` storages across Overworld, Nether, and End), flushes any pre-opened vanilla `RegionFile` handles, clears Moonrise's `nonExistingRegionFiles` cache, and swaps `regionCache` with a generated `Long2ObjectLinkedOpenHashMap` subclass that routes all stream and `ByteBuffer` chunk I/O through `LcaRegionFileStorage`.
- **64-byte Superblock, `uint64[1024]` Pointer Directory & `256B` CoW Slabs:** Each `r.<rx>.<rz>.lca` file starts with a checksummed 64-byte `"LCA_V001"` superblock, an 8 KiB atomic pointer directory (`slabOffset35 | codec1 | exactByteLen20 | generation8`), a dynamically chained shared dictionary, and a `1,024-byte` Extended Hamming `(72, 64)` SEC-DED syndrome table. Live chunk saves allocate a fresh `256-byte`-aligned slab interval from an in-memory `TreeMap` free-list while pinning active reader threads in a 64-slot hazard-pointer table.
- **Self-describing ChunkFrames & 1-pass section encoding:** Under the hood, each chunk frame stores a self-describing `0x5800_0000 | (chunkIdx << 16) | DataVersion` canary, a `uint16[N]` vertical section offset table (inlining uniform sections in 0 body bytes), a slab-local micro-palette (`Part A` 1-byte static core block/biome tokens + `Part B` 1-byte property suffixes), a binary `MOTION_BLOCKING_NO_LEAVES` heightmap prefix, and compact `boilerplateFlags16` residual NBT (preserving container `block_entities`, `block_ticks`, and `fluid_ticks`). Non-uniform `16x16x16` sections automatically select the best traversal order (`YZX`, 3D Hilbert `H_3`, horizontal 2D Hilbert `Y+H_2`, or vertical `XZY` columns) in `< 150 ns` and encode minority states into Delta-Roaring containers with 4-bitplane Reflected Binary Gray-code lighting.

### Resilience, redundancy, and 4-tier self-healing

In whole-region `.linear` files, all `1,024` chunks share a single `Zstd` stream (so 1 corrupted byte can render the entire `512x512`-block region unreadable), and in vanilla `.mca`, 1 flipped bit in a sector pointer or `Zlib` stream destroys all `24` vertical sections of that chunk. `.lca` eliminates single points of failure with four layers of built-in redundancy ([`BIT_CODES.md`](../dev/BIT_CODES.md), `/leafanvil heal`):

- **Tier 1 - Redundant Extended Hamming `(72, 64)` SEC-DED pointer parity (`< 10 ns`):** Every 64-bit chunk pointer in `0x0040..0x203F` is paired with a dedicated 1-byte parity syndrome in `secDedTableOffset` (`1,024 bytes` per region). Any single-bit flip in `slabOffset35`, `codec1`, `exactByteLen20`, or `generation8` is repaired algebraically in `O(1)`, and any 2-bit error (`DED`) is flagged for Tier 2.
- **Tier 2 - Redundant self-describing `256B` slab headers (reverse-scan directory rebuild):** Every chunk slab redundantly embeds its own chunk index and version (`0x5800_0000 | (chunkIdx << 16) | DataVersion`) along with self-contained section and tail lengths. If a pointer in the 8 KiB directory is wiped, suffers multi-bit corruption, or points to the wrong chunk's slab (`CBIT T3b` offset variation), `LeafAnvil` reverse-scans the `256B` slab pool and reconstructs the exact 64-bit directory pointer and SEC-DED parity byte from the slabs on disk.
- **Tier 3 - Redundant per-section `octreeOccupancyMask64` syndrome & dominant `palette[0]` quarantine:** Every non-uniform `16x16x16` section stores a redundant 8-byte octant occupancy mask (`octreeOccupancyMask64`, 1 bit per `4x4x4` octant) and its frequency-sorted dominant block state `palette[0]` (`90.8%` of voxels) in the section header. If bit rot hits interior container bytes (`CBIT T6` cursor bound or `CBIT T6b` syndrome mismatch), recovery quarantines only that single `16x16x16` section, substitutes its redundant `palette[0]` dominant stratum, and preserves the other **23 vertical sections** of the chunk 100% intact.
- **Tier 4 - Copy-on-Write (`CoW`) prior-generation retention, header/footer canaries & `.bak` backups:** Live saves never overwrite an active chunk in place; new frames write to fresh `256-byte` sub-slabs while a 4-epoch CoW grace ring and `prevChunkPointers[1024]` retain the previous verified generation for instant `O(1)` rollback if a write is interrupted. Every region file is bracketed by a 64-bit checksummed `"LCA_V001"` superblock (`CBIT T2`) and trailing `"LCA1_END"` footer canary (`CBIT T1`), and lazy or batch migrations write via atomic `.tmp` renames while keeping `.mca.bak` backups on disk (`backup-migrated-legacy-files: true`).

---

## Testing and quality gates

Every build is verified against real Minecraft world fixtures and live multi-loader server instances:

- **Live cold-restart persistence verification (block properties, containers & persistent mobs):** Verified both in automated JUnit Copy-on-Write reopen tests and on a live server (`--verify-persistence`: `Boot #1` in-game mutation -> `save-all flush` -> `stop` -> cold `Boot #2` from `767` `.lca` files and `0` `.mca` files -> RCON/bot inspection) that multi-property directional blocks (`oak_stairs[facing=east,half=top,waterlogged=true]`, `repeater[delay=3,facing=south,locked=true]`), container `block_entities` item stacks and components (`barrel` with `diamond_sword[damage=37]` + `19x golden_apple`, `chest` with `42x emerald_block`), and persistent mobs in `entities/r.x.z.lca` (`villager` and `sheep[Color=11b]` with `PersistenceRequired: 1b`) survive `100%` intact (`6/6 PASS`).
- **100% bit-exact `.mca <-> .lca` round-trip parity:** verified across all voxels, block state `Properties`, biomes, heightmaps, tile entities, `entities/` compounds, `poi/` compounds, and light nibbles on real `.mca` fixtures from Minecraft `1.20.4`, `1.21.4`, `26.1`, and `26.3`.
- **Real Mojang `RegionFileStorage` & Moonrise bytecode interception tests:** verified against both synthetic server hierarchies and real Minecraft `26.1.2` `net.minecraft.world.level.chunk.storage.RegionFileStorage` instances across all 9 dimension storage hooks (`region/`, `entities/`, `poi/` in Overworld, Nether, and End) on Paper, Folia, Fabric, and NeoForge.
- **10,000-save concurrent Copy-on-Write & 4-tier bit-rot torture suite:** verifies `0` torn reads under concurrent reader/writer threads, `100%` `256B` slab reuse across 10,000 dirty chunk saves, and automatic repair of injected 1-bit pointer flips, zeroed pointers, and corrupted section containers.
- **Reproducible multi-platform A/B/C server benchmark harness:** `Paper`, `Folia`, `Leaf (.linear)`, `Fabric (+ C2ME)`, and `NeoForge (+ C2ME)` servers are continuously benchmarked head-to-head (`without-lca` vs `with-lca` Mode A vs `with-lca-b` Mode B, `111` completed runs) under 24-bot `/rtpstress ramp` workloads with Btrfs `--reflink=auto` template resets and full `runs.jsonl` / `*-phases.csv` telemetry ([`README.md`](../../README.md)).

---

## Why I made it

While profiling `LeafRTP` and `LeafMap` against vanilla `.mca` and `.linear` worlds, I kept hitting a fundamental storage trade-off: vanilla `.mca` wastes 31% of disk space on 4 KiB sector padding and spends 45% to 60% of chunk load CPU inside `Zlib` `inflate()` and sequential NBT parsing, while `.linear` shrinks files with whole-region `Zstd` but stalls cold 1-chunk reads (`2.52 ms` to `26.4 ms`), re-compresses the entire region on every dirty chunk save (`49 ms` to `206 ms`), and puts all 1,024 chunks into one shared `Zstd` stream where a single corrupted byte can wipe out the whole region. Worse, using `.linear` forced server owners onto custom server forks.

I built `{{name}}` and the `.lca` format so server owners can get smaller worlds, faster random chunk access, and self-healing redundancy on standard Paper, Folia, Spigot, Fabric, and NeoForge servers:
- **No custom fork required:** Works as a standard plugin or mod jar on your existing server.
- **Smaller than `.linear` even with zero LZ compression:** By skipping dominant section strata (`90.8%` of voxels), inlining uniform sections into 2-byte directory pointers, and deduplicating block/property tokens, `.lca` Mode A (`0-LZ`) shrinks the 767-region world template to `1,535.3 MB` (`-8.2%` smaller than `.linear Zstd-1` at `1,672.1 MB`, and `-24.1%` smaller in Mode B at `1,269.8 MB`) while reading cold chunks `39.0x` faster in microbenchmarks and `26.5x` faster under live random teleports.
- **Redundant and fault-isolated by design:** Every 64-bit chunk pointer is backed by Hamming `(72, 64)` parity, every `256B` slab carries a self-describing header so wiped directories can be rebuilt from disk, and every `16x16x16` section is isolated with its own 64-bit occupancy syndrome and dominant-block fallback.
- **Always reversible & open spec (`MIT` codec, commercial runtime):** `{{name}}` is a commercial runtime plugin/mod, while the `.lca` binary specification ([`ICD.md`](../../../../api/anvil-api/docs/ICD.md), `LCA-ICD-1.0`) and the standalone `io.github.dailystruggle.anvil` reader/writer/transcoder library ([`anvil-api`](../../../../api/anvil-api/README.md)) are open-source under the MIT License. Your world data is never held hostage: `/leafanvil convert world=all format=mca` converts every `.lca` file back to vanilla `.mca` in-game, and `LcaRegionWriter.transcodeToMca(byte[])` in the public MIT `anvil-api` module converts `.lca` files offline even without `{{jar}}`.

---

## Commands, configuration, and FAQ

Operational commands, permissions, configuration keys, common operator questions, and support channels:

<details>
<summary><b>Commands, permissions, configuration files</b></summary>

**Operational commands** (alias: `/lca`; uses `CommandsAPI` `name=value` syntax):

| Command | Description | Permission |
| --- | --- | --- |
| `/leafanvil` / `/leafanvil status` | Show `.lca` runtime state, hooked `RegionFileStorage` count, open handles, and migrated regions | `leafanvil.admin` |
| `/leafanvil convert [world=<name\|all>] [format=<lca\|mca>]` | Asynchronously convert world region files (`region/`, `entities/`, `poi/`) to `.lca` or back to `.mca` | `leafanvil.admin` |
| `/leafanvil compact [world=<name\|all>]` | Compact open `.lca` region files above `compact-fragmentation-threshold` and flush to disk | `leafanvil.admin` |
| `/leafanvil heal [world=<name\|all>]` | Run 4-tier SEC-DED, slab reverse-scan, and section quarantine bit-rot healing across `.lca` files | `leafanvil.admin` |
| `/leafanvil reload` | Reload `config.yml` and `messages.yml` from disk | `leafanvil.admin` |

**Permissions** (`plugin.yml` / `paper-plugin.yml` / Brigadier):

| Permission | Default | Grants |
| --- | --- | --- |
| `leafanvil.admin` | `op` (level 4) | Access to all `/leafanvil` and `/lca` commands |

**Configuration (`plugins/LeafAnvil/config.yml` or `config/leafanvil/config.yml`):**

| Key | Default | Purpose |
| --- | --- | --- |
| `enabled` | `true` | Intercept live `RegionFileStorage` instances (`region/`, `entities/`, `poi/`) on world load |
| `auto-migrate-legacy` | `true` | Automatically transcode existing `.mca` / `.linear` files to `.lca` on first access |
| `backup-migrated-legacy-files` | `true` | Preserve original `.mca` / `.linear` files as `.mca.bak` after migration |
| `mode-b-zstd` | `true` | Use Mode B (`+ Zstd-1` per-chunk compression + header SEC-DED parity) instead of Mode A (`0-LZ` direct walk) |
| `compact-fragmentation-threshold` | `0.35` | Free-slab fragmentation ratio (`0.0..1.0`) triggering compaction |
| `default-data-version` | `4671` | Fallback Minecraft `DataVersion` for newly created empty region headers |

</details>

<details>
<summary><b>FAQ</b></summary>

**Q: Do I need a custom server fork like LinearPaper or Leaves to use `.lca`?**
A: No. `LeafAnvil` runs as a standard plugin or mod jar on stock Paper, Folia, Spigot, Fabric, and NeoForge servers by hooking `RegionFileStorage` in memory at world load.

**Q: What happens to my existing `.mca` region files when I install `LeafAnvil`?**
A: With the default settings (`auto-migrate-legacy: true` and `backup-migrated-legacy-files: true`), whenever the server opens an existing `r.x.z.mca` file, `LeafAnvil` transcodes it to `r.x.z.lca` via an atomic temporary file rename and renames the original to `r.x.z.mca.bak`. You can also convert the entire world at once with `/leafanvil convert world=all format=lca`.

**Q: How do I switch back to vanilla `.mca` if I want to uninstall `LeafAnvil`?**
A: Run `/leafanvil convert world=all format=mca` from the console or in-game, wait for the completion message, stop the server, and remove `LeafAnvil.jar`. Every `region/`, `entities/`, and `poi/` file will be back in standard vanilla `.mca` format.

**Q: Should I use Mode B (`mode-b-zstd: true`) or Mode A (`mode-b-zstd: false`)?**
A: Keep the default Mode B (`mode-b-zstd: true`) for general server deployments: it cuts world disk usage nearly in half (`1,269.8 MB` vs `2,527.9 MB` `.mca`, `-49.8%`, and `-24.1%` smaller than `.linear`), protects the `72-byte` `ChunkFrame` header with Extended Hamming `(72, 64)` `SEC-DED` parity, and matches or beats Mode A on teleport throughput and save-shutdown speed across Paper, Folia, Leaf, and Fabric (`7 ms` median Stage 0 latency on Fabric and Fabric + `C2ME`). Switch to Mode A (`mode-b-zstd: false`) if you want zero `Zstd` decompression CPU on NeoForge (`15 ms` vs `21 ms` Stage 0 `p50`, `1.70x` faster than `.mca`) or sub-chunk random-access probes (`1,535.3 MB`, `-39.3%` smaller than `.mca`, `64.68 us` cold read).

**Q: What happens if a disk sector or region file experiences bit rot or an interrupted save?**
A: Unlike whole-region `.linear` files (where one corrupted byte in the shared `Zstd` stream can break all `1,024` chunks) or vanilla `.mca` (where a damaged `Zlib` stream loses the entire 384-block chunk column), `.lca` builds in four layers of redundancy: a `1,024-byte` Extended Hamming `(72, 64)` parity table that repairs single-bit pointer flips in `< 10 ns`, self-describing `256B` slab headers that rebuild wiped or multi-bit damaged directory pointers via reverse scan, per-section 64-bit occupancy syndromes that quarantine corruption to a single `16x16x16` section (falling back to its dominant block while keeping the other 23 sections intact), and Copy-on-Write prior-generation pointers for instant rollback. You can run `/leafanvil heal world=all` at any time to scan and repair all region files in the background.

**Q: Does `LeafAnvil` work with `LeafRTP` and `LeafMap`?**
A: Yes. `LeafRTP`'s off-thread region pre-filter (`anvil-api`) and `LeafMap`'s 3D world streamer natively detect and read `r.x.z.lca` files at `8.55 us` per terrain probe (`12.84x` faster than `.mca`) and `1.31 us` per single-section read (`0.93 us` uniform, `118.6x` faster than `.mca`).

**Q: Is the `.lca` file format open, or are my worlds locked into a commercial plugin?**
A: The format and codec are open-source; only the live server interceptor plugin/mod (`{{name}}`) is commercial (`LicenseRef-LeafCraft-Commercial`). You keep 100% ownership of your world files, and both the byte-level `.lca` Interface Control Document ([`ICD.md`](../../../../api/anvil-api/docs/ICD.md), `LCA-ICD-1.0`, also extracted to `<config-dir>/ICD.md`) and the standalone `io.github.dailystruggle.anvil` reader/writer/transcoder library ([`anvil-api`](../../../../api/anvil-api/README.md)) are published under the MIT License. Any developer, map tool, backup script, or server admin can read, write, or convert `.lca <-> .mca` files offline using open-source MIT code without `{{jar}}` installed.

</details>

<details>
<summary><b>Support</b></summary>

Support comes from me, the person who wrote the code.

- For command or config questions, check [`COMMANDS.md`](../admin/COMMANDS.md), [`CONFIGURATION.md`](../admin/CONFIGURATION.md), or open a thread in the `LeafCraft Plugin Support` Discord `#support` Forum (or GitHub Discussions).
- A bug report in GitHub Issues or the `#support` Forum needs: `/version`, `/leafanvil status` output, `config.yml`, and the server log uploaded to `mclo.gs`.
- Feature requests go in GitHub Discussions first.

</details>

---

## Links

- [**`.lca` Binary Interface Control Document (`ICD.md`, MIT)**](../../../../api/anvil-api/docs/ICD.md): self-contained byte-exact `.lca` specification (`LCA-ICD-1.0`), curve formulas, checksum/Hamming algorithms, and static token tables (also packed inside `{{jar}}` and extracted to `<config-dir>/ICD.md`)
- [**Open-Source `.lca` Codec & Prefilter Library (`anvil-api`, MIT)**](../../../../api/anvil-api/README.md): public MIT Java reader, writer, `.lca <-> .mca` transcoder (`LcaRegionReader`, `LcaRegionWriter`), and Maven/JitPack coordinates
- [**Commands & Permissions Reference (`COMMANDS.md`)**](../admin/COMMANDS.md): `/leafanvil` (`/lca`) subcommands, parameters, and permission nodes
- [**Configuration & Benchmark Reference (`CONFIGURATION.md`)**](../admin/CONFIGURATION.md): `config.yml`, `messages.yml`, and the 111-run multi-platform `.lca` vs `.mca` / `.linear` statistical summary
- [**Built-In Testing & 4-Tier Self-Healing (`BIT_CODES.md`)**](../dev/BIT_CODES.md): `PBIT` canary, `CBIT T1..T8` tripwires, and 4-tier bit-rot recovery
- [**`.lca` Architecture Decision Records**](../adr/leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md): runtime bytecode interception, [3D Hilbert / Delta-Roaring / Gray-code light containers](../../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), and [multi-format `.mca` / `.linear` / `.lca` reader architecture](../../../../api/anvil-api/docs/adr/anvil-api-ADR-002-multi-format-region-support.md)
- [**Public Spec, Codec & Issue Tracker (`LeafCraftDev/LeafAnvil`)**](https://github.com/LeafCraftDev/LeafAnvil): public specification, open-source MIT `anvil-api` codec, benchmark documentation, and issue tracker
