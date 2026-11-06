# leafanvil-ADR-003: Negotiated Server-to-Client `.lca` Chunk Network Streaming, Batch `Zstd` Framing & `lastUpdateTick` Delta Sync (`Fabric` & `NeoForge`)

- **Status:** Proposed
- **Date:** 2026-11-05
- **Scope:** `:resource:LeafAnvil` (`leafanvil-common`, `leafanvil-fabric`, `leafanvil-neoforge`) & `:anvil-api` (`io.github.dailystruggle.anvil`)
- **Related ADRs & Specs:** [`leafanvil-ADR-001`](leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md), [`leafanvil-ADR-002`](leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md), [`anvil-api-ADR-002`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-002-multi-format-region-support.md), [`anvil-api-ADR-003`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`leafrtp-ADR-072`](../../../LeafRTP/docs/adr/leafrtp-ADR-072-zero-reallocation-flat-bitpacked-chunk-sections.md), [`LCA-ICD-1.0` (`api/anvil-api/docs/ICD.md`)](../../../../api/anvil-api/docs/ICD.md)

---

## Context

On **Fabric** (`:resource:LeafAnvil:leafanvil-fabric`, `FabricLeafAnvilMod`) and **NeoForge** (`:resource:LeafAnvil:leafanvil-neoforge`, `NeoForgeLeafAnvilMod`), `LeafAnvil` operates as a platform mod that can be installed on both the server and the client. While `leafanvil-ADR-001` replaces on-disk `.mca` / `.linear` region storage with `.lca` (`LcaRegionFileStorage`) and `leafanvil-ADR-002` keeps live server sections backed by compact `.lca` `baseFrame`s plus $O(1)$ `dirtyOverlay` buffers, outbound chunk synchronization through vanilla's `PlayerChunkSender` and `ClientboundLevelChunkWithLightPacket` still suffers from **three network-bandwidth and server-CPU bottlenecks**:

### 1. Boundary A — Block & Biome `PalettedContainer` Minimum-Bit Floor & Step Jumps (`ClientboundLevelChunkPacketData`)

- When `PlayerChunkSender` serializes a chunk column ($24$ vertical sections of $16 \times 16 \times 16 = 4,096$ voxels), each `LevelChunkSection#write(FriendlyByteBuf)` invokes `PalettedContainer#write`.
- Vanilla's `SimpleBitStorage` packs all $4,096$ voxel indices in `YZX` scanline order with a **minimum floor of $4\text{ bits/voxel}$ (`long[256]` = `2,048 B`)** even when a section contains only $2\text{–}4$ distinct block states (such as `stone` + `deepslate` or `water` + `air`), and steps the entire section to **$5\text{ bits/voxel}$ (`long[342]` = `2,736 B`)** as soon as a $17\text{th}$ block state enters the section palette.
- Across a single Overworld chunk column, the uncompressed section payload alone reaches **`~35–65 KiB`**.

### 2. Boundary B — Raw `2,048-Byte` `DataLayer` Lighting Arrays & Binary Nibble Entropy (`ClientboundLightUpdatePacketData`)

- For every section carrying sky light or block light, `ClientboundLightUpdatePacketData` writes a raw `byte[2048]` `DataLayer` nibble array.
- Uniform open-sky sections (`SkyLight = 15` across all $4,096$ voxels) and uniform underground sections (`~75%+` of all sections) still serialize **`2,048-byte`** arrays on the wire prior to `Zlib` framing.
- In non-uniform gradient sections, light decays by Manhattan distance ($15 \to 14 \to \dots \to 0$). Standard binary nibbles flip multiple bits at step boundaries (for example, $8 = 1000_2 \to 7 = 0111_2$ flips all $4$ bits), degrading downstream `DEFLATE` compression.

### 3. Boundary C — Server-Side `baseFrame` Re-Inflation, Stateless Per-Packet `Zlib` & Full Resends on Revisit

- Under `leafanvil-ADR-002` Section `5.B.2`, cold sections remain in server memory as compact `.lca` `SectionFrame`s (`~0–300 B`). Unpacking those curves (`00:XZY`, `01:H_3`, `10:Y+H_2`, `11:YZX`) back into Mojang's `SimpleBitStorage` `YZX` `long[256..342]` on the server CPU solely to feed Netty's stateless per-packet `Zlib` `CompressionEncoder` (`~6–14 KiB` compressed per chunk, or `~6–14 MB` per $32 \times 32$ region) wastes server CPU when the receiving client also has `LeafAnvil` installed.
- Furthermore, when a player leaves a chunk's tracking range and later returns, vanilla re-transmits the entire `6–14 KiB` `ClientboundLevelChunkWithLightPacket` even when `0–2` sections were modified since the client's previous visit.

---

## Decision

Introduce a **4-layer negotiated server-to-client `.lca` chunk streaming and delta-synchronization protocol** for Fabric (`:resource:LeafAnvil:leafanvil-fabric`) and NeoForge (`:resource:LeafAnvil:leafanvil-neoforge`), coordinated by `:resource:LeafAnvil:leafanvil-common` and backed by `:anvil-api` (`LcaSectionCodec`, `DynamicZstdBinding`):

```
Vanilla Packet Path (unmodified client fallback):
  LevelChunkSection (long[256..342] + DataLayer byte[2048])
    -> FriendlyByteBuf (~45-85 KiB raw)
    -> Per-packet Zlib Deflater (~6.0-14.0 KiB on wire)

LeafAnvil Negotiated .lca Stream Path (Fabric / NeoForge client with LeafAnvil):
  Disk .lca Slab
    -> Server RAM .lca (leafanvil-ADR-002 baseFrame + dirtyOverlay, O(1) slice)
    -> Paced Chunk-Batch Zstd Frame (~1.5-3.2 KiB on wire; 0.1-0.3 KiB on delta revisit)
    -> Client RAM .lca (leafanvil-ADR-002 baseFrame + dirtyOverlay on ClientLevel, O(1) attach)
```

---

### 1. Optional Capability Handshake & Safe Mixed-Client Fallback (`leafanvil:chunk_stream_handshake`)

1. **Optional Channel Registration:**
   - `:resource:LeafAnvil:leafanvil-fabric` (`FabricLeafAnvilMod`) registers custom configuration/play payloads (`leafanvil:chunk_stream_handshake`, `leafanvil:lca_region_dict_sync`, `leafanvil:lca_chunk_batch`, and `leafanvil:lca_chunk_digest`) via Fabric's `PayloadTypeRegistry` and `ServerPlayNetworking` / `ClientPlayNetworking`.
   - `:resource:LeafAnvil:leafanvil-neoforge` (`NeoForgeLeafAnvilMod`) registers identical payload channels via `RegisterPayloadHandlersEvent` marked `.optional()` so connections never fail when either side lacks `LeafAnvil`.
2. **Capability Negotiation (`LCA_NET_V1`):**
   - During connection setup, a `LeafAnvil`-equipped client sends `LcaChunkStreamHandshakePayload(int protocolVersion, int capabilityFlags, int clientCacheBudgetKiB)`.
   - `capabilityFlags` negotiates:
     - `CAP_LCA_SECTION_STREAM (1 << 0)` — `.lca` `SectionFrame` + Gray-code light bitplane wire encoding;
     - `CAP_BATCH_ZSTD (1 << 1)` — chunk-batch `Zstd` compression via `DynamicZstdBinding`;
     - `CAP_CLIENT_HEIGHTMAPS (1 << 2)` — client-side `Heightmap.primeHeightmaps` reconstruction (`0 B` heightmap wire cost);
     - `CAP_DELTA_REVISIT_CACHE (1 << 3)` — `lastUpdateTick` + SEC-DED syndrome revisit delta patching.
3. **Mixed-Client Fallback Invariant:**
   - Connections that do not complete the `leafanvil:chunk_stream_handshake` remain on the unmodified vanilla `ClientboundLevelChunkWithLightPacket` pipeline (still accelerated on the server by `leafanvil-ADR-002` Section `5.B.2`'s zero-allocation `ScratchArena` writer). A single server shall simultaneously serve `.lca` batch streams to `LeafAnvil` clients and vanilla packets to unmodified clients without shared-state contention.

---

### 2. Wire-Mode `.lca` `SectionFrame` Encoding & Network Registry ID Palette Substitution

Instead of serializing `SimpleBitStorage` `long[256..342]` arrays and `byte[2048]` `DataLayer` arrays, outbound chunks for negotiated clients use `.lca` `SectionFrame` representations (`LCA-ICD-1.0` Sections 6–8):

1. **Implicit Majority State (`palette[0]`) & Adaptive Delta-Roaring Containers:**
   - Uniform sections (`|P| = 1`, representing `66.4%` of natural sections) require **`0` section body bytes** and are encoded solely inside the `2-byte` `sectionOffsets[i]` directory word (with inline `skyLight2`).
   - Non-uniform sections omit `palette[0]` (`~90.8%` of voxels) and encode minority states ($p = 1 \dots |P|-1$) along the section's selected 3D space-filling curve (`00:XZY`, `01:H_3` 3D Hilbert, `10:Y+H_2` horizontal 2D Hilbert, `11:YZX`) using `TAG_SINGLETON` (`2 B`), `TAG_DELTA_RUN` (`4|4` or `6|2` gap/run bytes), `TAG_DELTA_ARRAY` (`Bounded240` gaps), or `TAG_BITMAP` (`512 B`), bounded by the `leafrtp-ADR-072` flat bit-packed ceiling (`~150–300 B` per non-uniform section).
2. **`1-Byte` Constant Light & 4-Bitplane Reflected Gray-Code Lighting (`LCA-ICD-1.0` Section 7.4):**
   - **`LIGHT_MODE_CONST` (`01`):** Sections with uniform `SkyLight` and `BlockLight` encode in **`1 byte`** (`constLightNibbles = (blkConst4 << 4) | skyConst4`), eliminating `2,048–4,096 B` of constant `DataLayer` arrays.
   - **`LIGHT_MODE_GRAY_PLANES` (`10`):** Non-uniform light arrays apply the Reflected Binary Gray Code bijection $G = L \oplus (L \gg 1)$ so unit-step Manhattan decay flips exactly 1 bit, then decompose into 4 bitplanes (`g0..g3`) along `10:Y+H_2` for `SkyLight` (`~89 B/chunk` average) and `01:H_3` for `BlockLight`.
3. **Numeric Network Registry ID Palette Substitution (`IdMapper<BlockState>` & Biome Registry):**
   - On disk, `.lca` stores string dictionaries (`STATIC_CORE_STATES` + property suffixes) for cross-version durability. Over a live Fabric or NeoForge session, server and client share identical numeric `Block.BLOCK_STATE_REGISTRY` (`IdMapper<BlockState>`) and biome registry IDs.
   - When a negotiated player enters region `(rx, rz)`, the server either:
     - Synchronizes a compact `LcaRegionDictSyncPayload` mapping the region's local dictionary IDs to delta-`VarInt` network `BlockState` and biome registry IDs (`int[] dictIdToBlockStateRegistryId`, cached per active `LcaLiveArchive`), or
     - Emits delta-`VarInt` sorted global `BlockState` registry IDs directly in each chunk's micro-palette header (`Part A` / `Part B`), eliminating string palette overhead on the wire.
4. **Client-Side Heightmap Reconstruction (`CAP_CLIENT_HEIGHTMAPS`):**
   - When `CAP_CLIENT_HEIGHTMAPS` is active, the server omits the `~600 B` raw `MOTION_BLOCKING` and `WORLD_SURFACE` `long[37]` bit-packed arrays from the chunk payload (or optionally sends `.lca` Section 8.1 compact heightmap deltas in `~96–160 B`), and the client decode worker reconstructs heightmaps locally via `Heightmap.primeHeightmaps(chunk, ...)` in `< 15 µs`.

---

### 3. End-to-End Zero-Transcode Pipeline with `leafanvil-ADR-002` (`(baseFrame, dirtyOverlay)`) & Chunk-Batch `Zstd` Framing

1. **Simpler In-Memory `leafanvil-ADR-002` Swap on Fabric & NeoForge (Server and Client):**
   - Unlike Paper/Folia (`Moonrise` `BlockCountingBitStorage`, which pins bitset-indexed ticking queues to raw `long[]` `BitStorage#getRaw()`), vanilla Fabric and NeoForge `LevelChunkSection` / `PalettedContainer` track only three scalar counters (`nonEmptyBlockCount`, `tickingBlockCount`, `tickingFluidCount`) and support native Mixin / class-transform hooks on both the dedicated server and client.
   - As a result, swapping `LevelChunkSection`'s in-memory backing to `leafanvil-ADR-002`'s `(baseFrame, dirtyOverlay)` representation applies cleanly on **both the Fabric/NeoForge server and the Fabric/NeoForge `ClientLevel`**.
2. **Trivial End-to-End CPU Cost (`Disk .lca -> Server RAM .lca -> Wire .lca -> Client RAM .lca`):**
   - Combining `leafanvil-ADR-002` (in-memory `.lca` representation) with `leafanvil-ADR-003` (wire `.lca` streaming) eliminates every intermediate voxel transcode between disk and client memory:
     - **Disk $\to$ Server RAM (`O(1)` slice):** `LcaRegionReader` maps the `.lca` `ChunkFrame` into server memory without unpacking `SectionFrame`s into `YZX` `long[256..342]` arrays (`leafanvil-ADR-002`).
     - **Server RAM $\to$ Wire (`O(1)` `System.arraycopy`):** For cold sections (`dirtyOverlay == null`), the server slices `baseFrame` bytes (`~0–300 B`) directly into the outbound batch buffer with **zero voxel iteration, zero bit-repacking, and zero palette rebuild**. For hot sections (`dirtyOverlay != null`), the server appends the compact `dirtyOverlay` (`int[16..64]` of packed `entry32 = ((pos12 + 1) << 19) | (stateId19 & 0x7FFFF)` at $\le 75\%$ load factor, typically `64–256 B`) alongside `baseFrame`.
     - **Wire $\to$ Client RAM (`O(1)` slab attachment):** On the client, incoming `.lca` `SectionFrame`s (and any `dirtyOverlay`) attach directly to the client `LevelChunkSection` as `leafanvil-ADR-002` `(baseFrame, dirtyOverlay)` slabs without eager $O(4,096)$ `SimpleBitStorage` inflation on packet receipt; subsequent single-block updates (`ClientboundBlockUpdatePacket` / `ClientboundSectionBlocksUpdatePacket`) write $O(1)$ entries into the client section's `dirtyOverlay`.
3. **Paced Chunk-Batch `Zstd` Framing (`PlayerChunkSender#sendNextChunks`) & Netty `CompressionEncoder` Bypass:**
   - Minecraft `1.20.2+` / `1.21.x` / `26.1+` paces chunk delivery in per-tick batches bracketed by `ClientboundChunkBatchStartPacket` and `ClientboundChunkBatchFinishedPacket`.
   - For negotiated players, `PlayerChunkSender#sendNextChunks` packs the tick window's $N$ outbound `.lca` chunk frames ($N = 1 \dots 16$, together with block-entity NBT sidecars and initial light masks) into a single `ClientboundLcaChunkBatchPayload` compressed in one pass via `:anvil-api`'s `DynamicZstdBinding` (`Zstd-1` or `Zstd-3`), amortizing palette, biome, and block-entity NBT redundancy across adjacent chunks in the batch.
   - To prevent vanilla Netty's `CompressionEncoder` (`Zlib`) from re-compressing the `Zstd` payload (and without fragmenting a `16–32 KiB` batch into `< 256 B` sub-slices), a lightweight Netty outbound handler before `"compress"` (or a 1-line Mixin in `CompressionEncoder#encode`) writes the uncompressed-length `VarInt = 0` header directly for `ClientboundLcaChunkBatchPayload`, sending the entire batch in a single frame with zero `Zlib` CPU overhead.

---

### 4. Revisited-Chunk `lastUpdateTick` Digest & Sub-Chunk Delta Patch Streaming (`ChunkPatchTail`)

1. **Bounded Client `.lca` Frame LRU Cache:**
   - Negotiated clients maintain a bounded off-heap/byte-slab LRU cache (configurable, default `32–64 MiB`, sufficient for `~10,000–20,000` compressed `.lca` chunk frames) keyed by `(dimension, chunkPos)` and storing the chunk's 8-byte `lastUpdateTick` and 64-bit Extended Hamming `(72, 64)` SEC-DED header syndrome (`LCA-ICD-1.0` Section 6.1).
2. **Digest Exchange & 3-Way Server Resolution:**
   - When a player moves across region boundaries or teleports back into a previously visited area, the client sends a compact `ServerboundLcaChunkDigestPayload` listing `(chunkPos, lastUpdateTick, secDedSyndrome64)` for cached chunks in the target region:
     - **Case A — Unmodified Chunk (`lastUpdateTick` & `secDedSyndrome64` match):** The server emits a **`2-byte` clean-ack entry** in `ClientboundLcaChunkBatchPayload`, instructing the client to restore the chunk directly from its local `.lca` cache (`> 99%` bandwidth reduction).
     - **Case B — Partially Mutated Chunk ($1\text{–}4$ sections dirty since cached `lastUpdateTick`):** The server transmits a **`ChunkPatchTail` (`HAS_CHUNK_PATCH_TAIL = 0x4000`, `LCA-ICD-1.0` Section 8.3) or section-delta frame (`~100–300 B`)** containing only the replaced `SectionFrame` slices and updated block-entity NBT entries.
     - **Case C — Cache Miss or Major Mutation:** The server streams the full `.lca` `ChunkFrame` (`~1.5–3.2 KiB`).

---

### 5. Client-Side Off-Thread Ingest, Lazy In-Memory Backing & Renderer Compatibility (`Sodium` / `Embeddium` / `Bobby` / `Distant Horizons`)

1. **Zero-Allocation Client Worker Ingest (`ScratchArena` & Client-Side `leafanvil-ADR-002`):**
   - Upon receiving `ClientboundLcaChunkBatchPayload`, the client decompresses the `Zstd` batch frame off the render thread using `LcaSectionCodec`'s thread-local `ScratchArena` (`128 KiB` pre-allocated scratch buffers, zero steady-state heap allocations).
   - When client-side `leafanvil-ADR-002` in-memory section backing is enabled, each `ClientLevel` `LevelChunkSection` retains the compact `.lca` `(baseFrame, dirtyOverlay)` directly (`0 B` body for `66.4%` uniform sections; `~150–300 B` for non-uniform sections), cutting client chunk heap footprint by `40%–65%` and making packet ingestion an $O(1)$ slice assignment; `ScratchArena` only expands a section when the chunk mesher (`SectionCompiler` / **Sodium** `ChunkBuilderMeshingTask`) actively builds a render mesh for a visible non-uniform section or when a Copy-on-Read caller requests `BitStorage#getRaw()`.
2. **Strict Netty Arrival-Order `ClientLevel` Commit Boundary (`ClientOrderedBatchCommitQueue`):**
   - While `Zstd` decompression (`DynamicZstdBinding`) and Extended Hamming `(72, 64)` SEC-DED verification run off the render/main thread, the hydrated `LcaSectionBacking` instances are committed onto `ClientLevel` on the client main thread in strict Netty arrival sequence order (`ClientOrderedBatchCommitQueue`) so any subsequent `ClientboundBlockUpdatePacket` or `ClientboundSectionBlocksUpdatePacket` that arrived on Netty after the batch is never overwritten by an out-of-order background decode.
   - Because the client-side `LevelChunk` (`MixinLevelChunkSection`), `PalettedContainer` (`MixinPalettedContainer` returning non-pinning ephemeral snapshots on `copy()`), and `LevelLightEngine` honour the standard contracts, client rendering and LOD mods (**Sodium**, **Embeddium**, **Lithium**, **Bobby**, **Distant Horizons**) receive standard chunk-load and light-update events without custom compatibility patches, and Paper/Folia servers (`PaperLeafAnvilPlugin`) can stream `LCA_NET_V1` batches to Fabric/NeoForge `LeafAnvil` clients over Bukkit plugin channels.

---

## Wire-Size & Server CPU Comparison

| Stream Component | Vanilla `ClientboundLevelChunkWithLightPacket` | `LeafAnvil` `.lca` Wire Stream (`Fabric` / `NeoForge`) | Architectural Mechanism |
| :--- | :--- | :--- | :--- |
| **Uniform / Air Sections (`66.4%` of sections)** | `~12–24 B` + `2,048 B` light if sky-lit | **`2 B`** (`sectionOffsets[i]` inline tag + `skyLight2`) | Inline constant block & `skyLight2` in directory word |
| **Non-Uniform Voxel Sections** | `2,048–2,736 B` raw (`SimpleBitStorage` `long[]`) | **`150–300 B`** (`Delta-Roaring` + `3D Hilbert`) | Implicit `palette[0]` (`90.8%` of voxels) + 1-pass curve selection |
| **Uniform Lighting (`~75%+` of sections)** | `2,048 B` raw (`DataLayer` `byte[2048]`) | **`1 B`** (`LIGHT_MODE_CONST`) | Constant nibble folding (`constLightNibbles`) |
| **Gradient Lighting (`SkyLight` / `BlockLight`)** | `2,048–4,096 B` raw nibbles | **`~89–210 B`** (`LIGHT_MODE_GRAY_PLANES`) | 4-bitplane Reflected Gray code along `Y+H_2` / `H_3` |
| **Heightmaps (`MOTION_BLOCKING`, `WORLD_SURFACE`)** | `~600 B` raw (`long[37]` $\times 2$) | **`0 B`** (`CAP_CLIENT_HEIGHTMAPS`) or **`~110 B`** (delta) | Client worker `Heightmap.primeHeightmaps` or `.lca` Section 8.1 stream |
| **Cold Section Server Serialization CPU** | Full `PalettedContainer#write` + `Zlib` `Deflater` | **Zero-copy `baseFrame` slice** (`leafanvil-ADR-002`) | Direct slice from in-memory `.lca` `baseFrame` |
| **Typical Initial Wire Size / Chunk** | **`~6.0 – 14.0 KiB`** (per-packet `Zlib`) | **`~1.5 – 3.2 KiB`** (`.lca` + batch `Zstd`) | **`~65%–78%` bandwidth reduction** on initial load |
| **Revisited Chunk ($0\text{–}2$ Sections Edited)** | **`~6.0 – 14.0 KiB`** (full chunk resend) | **`2 B` (clean-ack) – `0.3 KiB` (`ChunkPatchTail`)** | **`~95%–99%` bandwidth reduction** on revisit delta sync |

---

## Consequences, Invariants & Risks

### Positive Consequences
- **65%–78% Initial & 95%+ Revisit Bandwidth Reduction:** Dramatically lowers server egress bandwidth and accelerates chunk loading during fast flight, Elytra travel, and `LeafRTP` random teleports on Fabric and NeoForge servers.
- **Server CPU Offload (`leafanvil-ADR-002` Synergy):** Cold sections in server memory are sliced directly into the network batch without unpacking 3D Hilbert / Delta-Roaring containers on the server; client CPUs perform the $O(4,096)$ curve-to-`YZX` expansion in parallel.
- **Full Chunk-Batch Pacing Compatibility:** Preserves Minecraft's `PlayerChunkSender` RTT/bandwidth congestion window (`ClientboundChunkBatchStartPacket` / `ServerboundChunkBatchReceivedPacket`) so client frame rates and packet pacing remain smooth.

### Required Safety Invariants
1. **Zero Main-Thread Blocking (`REQ-RTP-S-005`):** Neither server batch `Zstd` framing nor client `.lca` decoding shall perform synchronous disk I/O on the server tick thread or client render thread.
2. **Mixed-Client Coexistence:** Unmodified vanilla clients and `LeafAnvil`-enabled clients shall coexist on the same Fabric or NeoForge server without protocol errors or registry desynchronization.
3. **SEC-DED & Registry Parity Verification:** Before applying a `lastUpdateTick` clean-ack or `ChunkPatchTail` delta from the client LRU cache, the client shall verify the 64-bit Extended Hamming `(72, 64)` SEC-DED header syndrome (`LCA-ICD-1.0` Section 4.3); on any mismatch, the client evicts the cached entry and requests a full `.lca` `ChunkFrame`.

### Risks & Mitigations
- **Double Compression in Netty `CompressionEncoder` & Reverse Proxy Compatibility (`VarInt = 0` Frame Bypass vs. `Deflater.NO_COMPRESSION` Stored-Zlib Framing):** Because `ClientboundLcaChunkBatchPayload` is already compressed with `DynamicZstdBinding`, passing a `16–32 KiB` batch through Netty's `Zlib` `CompressionEncoder` would waste CPU for zero size gain, while sub-slicing below `network-compression-threshold` (commonly `256 B`, or `64 B` behind proxies) would fragment a single batch into `64–128` custom payload packets per tick window. Furthermore, reverse proxies (**Velocity** and **BungeeCord**) may reject uncompressed frames (`dataLength == 0`) exceeding `network-compression-threshold` as an anti-DoS heuristic. *Mitigation:* Support both direct `VarInt = 0` (`dataLength = 0`) bypass (`encodeNettyVarIntZeroBypassFrame` / `MixinCompressionEncoder`) on direct connections and proxy-safe RFC 1950 / RFC 1951 `Deflater.NO_COMPRESSION` (`level = 0` stored-block) Zlib framing (`encodeProxySafeZlibStoredFrame`, `CAP_PROXY_SAFE_STORED_ZLIB`), which adds only `+11 B` per `< 64 KiB` batch while producing a valid non-zero `dataLength` header that passes Velocity, BungeeCord, and vanilla `CompressionDecoder` with zero LZ77/Huffman CPU overhead.
- **Dynamic Modded BlockState Registry Remaps:** If a server and client have mismatched mod lists, connection fails during standard Fabric/NeoForge configuration negotiation before play-stage chunk streaming begins. As defense in depth, `LcaChunkStreamHandshakePayload` includes a 64-bit hash of `Block.BLOCK_STATE_REGISTRY` size and biome registry count; if mismatched, the connection falls back to string dictionary palettes or standard vanilla packets.
