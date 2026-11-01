# Built-In Testing (`PBIT` / `CBIT`) & 4-Tier Self-Healing Reference (`LeafAnvil`)

`LeafAnvil` and `LcaRegionReader` (`api/anvil-api`, `anvil-api-ADR-003` Section 7) enforce Power-On Built-In Testing (`PBIT`), Continuous Built-In Testing (`CBIT T1..T8`), and 4-tier algebraic and structural bit-rot recovery on `.lca` (LeafCraft Anvil) region files.

---

## 1. Power-On Built-In Test (`PBIT`)

On startup (`LcaRegionReader.INSTANCE.executePbit()`), `LcaRegionReader` encodes and decodes a static 2-section micro-canary (1 inline uniform section `bit 15 == 1` + 1 non-uniform `01:H_3` Delta-Roaring section with `10:Y+H_2` Reflected Binary Gray-code `SkyLight`) in `< 2 ms`, verifying bit-exact voxel, biome, and light parity and `octreeOccupancyMask64` equivalence before registering `.lca` in `RegionFormatRegistry`.

---

## 2. Continuous Built-In Test Tripwires (`CBIT T1..T8`)

Every `.lca` read and probe executes eight $O(1)$ inline safety tripwires:

| Tripwire | Invariant Verified | Failure Action (`REQ-RTP-S-004`) |
|---|---|---|
| `CBIT T1` | Superblock magic `0x4C43415F56303031L` (`"LCA_V001"`) at `0x0000`, footer canary `0x4C4341315F454E44L` (`"LCA1_END"`) at `fileLength - 8`, `formatVersion == 0x0001`, `slabShift == 8` (`256B`), and `1 <= sectionCount <= 128`. | Throws `CorruptRegionEntryException` (or triggers `/leafanvil heal`). |
| `CBIT T2` | 64-bit SplitMix/XXH64 `headerChecksum` at `0x38..0x3F` matches computed checksum over superblock bytes `0x00..0x37`. | Rejects corrupted superblock header immediately. |
| `CBIT T3` | Chunk pointer bounds: `(slabOffset35 << 8) + exactByteLen20 <= fileLength - 8`. | Triggers Tier 1 SEC-DED or Tier 2 slab reverse-scan recovery. |
| `CBIT T3b` | Self-describing `ChunkFrame` header word `(rawDvWord & 0xFC00_0000) == 0x5800_0000` and embedded `((rawDvWord >>> 16) & 0x03FF) == chunkIdx`. | Catches pointer offset variation (pointing to the wrong chunk's slab) in $O(1)$. |
| `CBIT T4` | Per-chunk uncompressed allocation ceiling `<= 16 MiB` (`MAX_CHUNK_UNCOMPRESSED_BYTES`). | Prevents out-of-memory allocation on malformed length fields. |
| `CBIT T5` | `uint16 sectionOffsets[i]` (scaled by `4B` or `16B`) stays within `exactByteLen20`, and chained dictionary `nextSegmentLinkTrailer` traversal is acyclic (`<= 64` segments) and bounded within `fileLength - 8`. | Rejects truncated frames or cyclic dictionary chains. |
| `CBIT T6` | Per-state Delta-Roaring and Gray-light run cursor $h$ stays within $0 \le h \le 4,096$. | Isolates corrupt section container to Tier 3 quarantine. |
| `CBIT T6b` | 64-bit spatial syndrome check: `decodedOctreeMask64` accumulated from decoded minority voxels matches the stored 8-byte `octreeOccupancyMask64` section header word. | Catches silent interior run-byte bit flips even when $h \le 4,096$. |
| `CBIT T7` | `VarInt` palette index bounds stay strictly `< stateCount` (or `< biomeCount`). | Rejects out-of-range dictionary references. |
| `CBIT T8` | Lock-free concurrent read guard: pins `slabOffset35` in the 64-slot `PINNED_SLAB_STARTS` hazard-pointer table, reads `ptrBefore`, copies/decodes the slab, issues `VarHandle.loadLoadFence()`, and verifies `ptrBefore == ptrAfter`. | Retries automatically if a concurrent `CoW` save updated the pointer mid-read (`0` torn reads). |

---

## 3. 4-Tier Bit-Rot Self-Healing (`/leafanvil heal`)

Running `/leafanvil heal [world=<name|all>]` invokes `LcaRegionReader.healRegionBitRot(byte[])`, executing four progressive recovery tiers:

| Tier | Mechanism | What It Repairs |
|---|---|---|
| **Tier 1: Extended Hamming `(72, 64)` SEC-DED Pointer Healing** | Evaluates each 64-bit pointer in `0x0040..0x203F` against its 1-byte syndrome in `secDedTableOffset` (`LcaSectionCodec.healSingleBitFlip64`). | Algebraically repairs any single-bit flip in `slabOffset35`, `codec1`, `exactByteLen20`, or `generation8` in `< 10 ns`, and detects 2-bit errors (`DED`) for Tier 2. |
| **Tier 2: Self-Describing `256B` Slab Reverse-Scan** | Scans `256B`-aligned sub-slabs from `slabPoolBaseOffset` to `fileLength - 8` for the self-describing `ChunkFrame` canary `0x5800_0000 \| (chunkIdx << 16)`. | Reconstructs zeroed, multi-bit corrupted (`DED`), or offset-shifted chunk directory pointers and recomputes their SEC-DED syndrome bytes. |
| **Tier 3: Section-Granular `octreeOccupancyMask64` Quarantine** | Validates every section's `CBIT T6` cursor bound and `CBIT T6b` 64-bit `octreeOccupancyMask64` spatial syndrome (`readChunkViewWithRecovery`). | Isolates interior voxel bit rot to the single affected $16 \times 16 \times 16$ section, substituting its dominant stratum `palette[0]` (`90.8%` of voxels) while preserving the other 23 vertical sections of the chunk intact. |
| **Tier 4: Copy-on-Write (`CoW`) Prior-Generation Rollback** | Restores `prevChunkPointers[chunkIdx]` maintained by `LcaLiveArchive#rollbackCorruptChunkToPriorCoW`. | Rolls back a chunk in $O(1)$ to its previous verified `256B` CoW slab generation if an interrupted write is detected. |
