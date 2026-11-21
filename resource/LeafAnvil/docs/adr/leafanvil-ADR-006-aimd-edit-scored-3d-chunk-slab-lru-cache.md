# leafanvil-ADR-006: Unified Zero-Copy Cold `.lca` `ChunkFrame` Residency & Bounded AIMD Sparse Dirty Block-Change Write-Back LRU Cache

- **Status:** Accepted
- **Date:** 2026-11-14
- **Scope:** `:anvil-api` (`io.github.dailystruggle.anvil.{LcaSectionBacking,LcaRegionFileStorage,LcaRegionReader,LcaRegionWriter,RawChunkFrameSnapshot}`) & `:resource:LeafAnvil` (`net.leaf.anvil.common.LcaInMemoryChunkCoordinator`)
- **Related ADRs & Specs:** [`leafanvil-ADR-002`](leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md), [`leafanvil-ADR-003`](leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md), [`leafanvil-ADR-005`](leafanvil-ADR-005-dual-mode-javaagent-direct-section-bridge-and-netty-wire-pipeline.md), [`anvil-api-ADR-003`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`ADR-115`](../../../../docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)

---

## Context

In live Minecraft servers (`Paper`, `Folia`, `Fabric`, `NeoForge`), `> 95%` of loaded chunks and vertical sections are never mutated while resident in memory—they are read from `r.<rx>.<rz>.lca` (or synthesized by `LeafGen` Stage K3 GPU kernels), streamed to vanilla and `LCA_NET_V1` clients, and eventually unloaded.

Treating the in-memory write cache like a cache of hydrated 4,096-voxel 3D chunk slabs (`CompactBaseIndex` hydration on write, `STAGE_BULK_4096` `16 KiB` `int[4096]` promotion above 48 edits, `4x4x4` octant sub-cubes, and retaining clean post-compaction slabs in an LRU map) introduces three avoidable costs:
1. **Storage Round-Trip Detour:** Decoding `.lca` `ChunkFrame`s into Mojang NBT on load only to re-encode clean NMS `PalettedContainer`s back into `.lca` `baseFrame`s wastes CPU and allocations when the on-disk `.lca` `ChunkFrame` already uses section-local indices `0..K-1` for `~98%` of every `SectionFrame`.
2. **Write-Time Slab Hydration & `16 KiB` Inflation:** Block writes only need an instant $O(1)$ `(pos -> runtimeStateId)` modifier in front of the cold `.lca` baseline—hydrating `CompactBaseIndex` on write or inflating `> 48` edits into `int[4096]` (`16 KiB`) over-indexes on dense slab geometry.
3. **Clean Slab Pollution & Unsynchronized Access-Ordered `LinkedHashMap` Gets:** Retaining clean post-flush entries in an access-ordered `LinkedHashMap` wastes cache capacity and causes structural linked-list corruption if `.get()` runs outside a common lock (`ACCESS_ORDERED_LRU_UNSYNCHRONIZED_GET`).

---

## Decision

We unify disk, server memory, GPU worldgen, and conditional network streaming around **Cold-by-Default Immutable `.lca` `ChunkFrame` Buffers** paired with a **Bounded Thread-Safe Sparse Dirty Block-Change Write-Back LRU Cache** (`DirtyChunkLruCache`), governed by `REQ-RTP-S-002`, `REQ-RTP-S-004`, and `REQ-RTP-S-005`:

### 1. Unified Cold `.lca` `ChunkFrame` Residency (`0` Dirty LRU Slots)
- Off-thread (`REQ-RTP-S-005`), `LcaRegionFileStorage#readRawChunkFrame` reads the chunk slab via positional `FileChannel` I/O guarded by `LcaRegionReader#pinHazardSlab`'s 4-epoch grace ring (avoiding Windows NTFS `FileChannel#map` file-lock leaks per `JDK-4724038` / `REQ-RTP-S-002`): Mode A (`codec1 == 0`) reads the raw `ChunkFrame` bytes directly; Mode B (`codec1 == 1`) performs one pure-Java `LCD2` (`0x4C44` `"LD"` Atomic Dictionary + 12-bit Huffman) unpack into `RawChunkFrameSnapshot`.
- Each open `LcaLiveArchive` caches `int[] dictIdToRuntimeId` and `int[] runtimeIdToDictId` translation arrays incrementally alongside its region dictionary.
- `TrackedChunkColumn` mounts `volatile byte[] chunkFrame` and its 24-section offset directory directly in memory with **zero** entries in `DirtyChunkLruCache` and **zero** background NMS-to-`.lca` voxel re-encoding.

### 2. Instant $O(1)$ Sparse `(pos12 -> runtimeStateId)` Cell Writes (`SparseDirtyCellMap`)
- `LcaSectionBacking` stores uncompacted block changes in `SparseDirtyCellMap`—a flat power-of-two open-addressed primitive `int[]` table packing `((pos12 + 1) << 19) | (runtimeStateId & 0x7FFFF)` (`0 == empty`), resizing `0 -> 16 -> 64 -> 256 -> 1024 -> 8192` at `<= 75%` load factor.
- Voxel writes (`recordDirtyVoxel` and `setAndGetPreviousStateId`) probe/insert in $O(1)$ (`~2 ns`) **without** calling `hydrateCompactBaseIndexIfNeeded()`, **without** allocating `4x4x4` octant sub-cubes, and **without** inflating to `int[4096]` (`16 KiB`).
- Every write increments the section's lock-free AIMD edit score (`+additiveIncrement`, bounded by `MAX_AIMD_EDIT_SCORE`) and admits/touches only the mutated chunk in `LcaInMemoryChunkCoordinator`'s `DirtyChunkLruCache` under `synchronized (dirtyCacheLock)`.

### 3. Bounded Synchronized Write-Back `DirtyChunkLruCache` & Section-Spliced CoW Flush (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`)
- **Membership Invariant:** Only chunks with uncompacted dirty block changes reside in `DirtyChunkLruCache`; all reads, touches, iterations, and removals on the access-ordered `LinkedHashMap` execute strictly inside `synchronized (dirtyCacheLock)`.
- **AIMD + LRU Write-Back Eviction & CoW Splice:** When `dirtyChunkCount() > maxDirtyChunks`, or on quiet-window sweeps / autosave:
  1. The coordinator selects the dirty chunk with the lowest aggregate AIMD edit score (tie-broken by least-recently-used order in `DirtyChunkLruCache`) that is not already flushing, never dropping uncompacted edits (`REQ-RTP-S-004`).
  2. An off-thread worker snapshots `dirtySectionMask`, translates the $O(D)$ sparse `runtimeStateId` modifiers via `runtimeIdToDictId`, re-encodes **only** the touched `SectionFrame`(s), copies all untouched `SectionFrame` byte slices verbatim via `System.arraycopy`, and commits a `256 B` Copy-on-Write slab via `LcaRegionFileStorage#commitSplicedChunkFrameCoW` (`LcaLiveArchive#commitPreEncodedChunkCoW`).
  3. The worker atomically swaps `TrackedChunkColumn`'s `volatile byte[] chunkFrame` reference, advances `(lastUpdateTick, baselineSecDed64)`, and removes the clean chunk from `DirtyChunkLruCache` (`0` slots once clean).
- **Deterministic Unload Cleanup (`REQ-RTP-S-002`):** `markChunkUnloaded` and `clearWorld` immediately fence in-flight tasks (`UNLOADED_BIT` + `EpochCommitFence`) and purge matching entries from `DirtyChunkLruCache`.

---

## Consequences

### Positive
- **Zero-Copy Disk $\leftrightarrow$ Memory $\leftrightarrow$ Wire Symmetry:** Cold chunks share one immutable `.lca` `ChunkFrame` layout across disk slabs, server RAM, `LeafGen` Stage K3 GPU output, and `LCA_NET_V1` wire slices (rewriting only the `4–32 B` `VarInt[K]` section palette header on wire).
- **Instant Sparse Cell Writes:** Memory scales strictly with unique modified blocks (`64 B` for `1..16` edits, `256 B` for `17..48`, `1 KiB` for `49..192`, `4 KiB` for `193..768`), eliminating write-time `CompactBaseIndex` hydration and `16 KiB` slab promotions.
- **Zero Clean-Cache Overhead & Zero LRU Race Hazards:** Clean chunks consume `0` slots in `DirtyChunkLruCache`, and `dirtyCacheLock` eliminates concurrent `LinkedHashMap#get` structural hazards.

### Trade-Offs & Mitigations
- **Simultaneous Dirty Burst Backpressure (`REQ-RTP-S-004`):** If a single-tick `WorldEdit` or explosion burst dirties more chunks than `maxDirtyChunks`, excess dirty entries schedule immediate off-thread CoW flushes and drain back below `maxDirtyChunks` as workers complete rather than dropping block changes.

---

## Verification

- `LcaSectionBackingAndCompactionTest` (`:anvil-api`): Verifies instant $O(1)$ writes across `1`, `50`, and `500` cells with `CompactBaseIndex == null` and zero `int[4096]` allocation, in-place single-slot overwrites, and cold post-compaction state.
- `LcaRegionFileStorageTest` (`:anvil-api`): Verifies Mode A (`0-LZ`) and Mode B (`LCD2` `0x4C44`) `readRawChunkFrame` + `commitSplicedChunkFrameCoW` bit-for-bit preservation of all 23 untouched `SectionFrame` slices.
- `LcaAimd3dSlabCacheTest` (`:resource:LeafAnvil:leafanvil-common`): Verifies `0`-slot cold `ChunkFrame` residency, sparse dirty-only LRU admission, AIMD + LRU victim CoW flush back to cold (`dirtyChunkCount() == 0`), concurrent multi-threaded LRU safety, and deterministic `markChunkUnloaded` / `clearWorld` cleanup (`REQ-RTP-S-002`).
