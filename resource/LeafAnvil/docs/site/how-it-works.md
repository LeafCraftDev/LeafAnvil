# LeafAnvil — How It Works

## 1. Runtime `RegionFileStorage` Interception (`1.21.x` – `26.3`)

On startup, `NmsRegionStorageInterceptor` traverses loaded `ServerLevel` / `CraftWorld` instances (up to field depth 5) and wraps all `RegionFileStorage` maps across terrain (`region/`), POI (`poi/`), and entities (`entities/`) in both Paper Moonrise (`MoonriseRegionFileIO`) and vanilla `ChunkMap` pipelines. Because interception is type-driven rather than tied to versioned package strings, Minecraft `1.21.x`, `26.1`, `26.2`, and `26.3` work identically.

## 2. The `.lca` (`LCA_V001` / `v2`) Region Container

Each `32x32` region file begins with a **`64-byte` Superblock** (`magic = "LCA_V001"`, `formatVersion = 0x0002`, SplitMix/XXH64 checksum, chained dictionary offset, and `SEC-DED` offset) followed by a **`1,024-entry` Atomic Slab Pointer Directory**:
- **256-Byte Slabs (vs. 4,096-Byte `.mca` Sectors)**: Eliminates the average `2 KiB` internal tail-padding waste per chunk in vanilla Anvil.
- **3D Spatial Curves, Delta-Roaring & Gray-Code Bitplanes**: Encodes `16x16x16` sections along selectable 3D space-filling curves (`YZX`, `3D Hilbert H_3`, `Y+2D Hilbert H_2`, `XZY`) with `octreeOccupancyMask64` compaction, per-state Delta-Roaring containers, and 4-bitplane Reflected Binary Gray-code lighting in `Mode A` (`0-LZ` direct slabs) or `Mode B` (`LCD2` `0x4C44` Continuous Atomic Layer 2 Region Dictionary, 100% pure Java with zero native JNI dependencies).
- **`ChunkPatchTail` (`0x4000`) & Dirty Overlay**: Minor block edits stage in an $O(1)$ `dirtyOverlay` (`int[16..64]`) or append a compact `ChunkPatchTail` / replacement `SectionFrame` in the `256B` sub-slab slack instead of rewriting the full chunk.
- **Copy-on-Write (`CoW`) & `(72, 64)` `SEC-DED` ECC**: Slab updates allocate free `256B` intervals atomically and verify 4-tier Extended Hamming `(72, 64)` parity + self-describing `0x5800_0000` slab canaries on read (`CBIT T1..T8`).

## 3. Two-Tier Wire Protocol (`Mode A` vs. `Mode B`)

- **Mode A (`VANILLA_SAFE`)**: Caches pre-deflated vanilla chunk section buffers keyed by `(chunkKey, slabGeneration)` so repeated chunk views skip redundant `deflate()` CPU work on `100%` vanilla clients.
- **Mode B (`LCD2` / `LCA_NET_V1`)**: When `LeafAnvil` / `LeafAnvil-Net` completes the `leafanvil:chunk_stream_handshake`, the server transmits `LCD2` (`0x4C44`) Continuous Atomic Dictionary + `ODCT` Orderless Dictionary & `EntryPatchDelta` frames over `leafanvil:lca_chunk_batch`, cutting initial chunk bandwidth by `65%–78%` (`95%+` on revisit).
