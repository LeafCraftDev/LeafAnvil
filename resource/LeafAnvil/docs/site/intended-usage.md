# LeafAnvil — Intended Usage

**LeafAnvil** replaces vanilla Minecraft's `4 KiB`-padded `.mca` region storage with the **`.lca` (`LCA_V001` / `v2`)** spatial voxel format (`256-byte` Copy-on-Write slabs, `0-LZ` direct bit-scatter or `LCD2` `0x4C44` Continuous Atomic Region Dictionary encoding, `ChunkPatchTail` / dirty-overlay deltas, and `(72, 64)` `SEC-DED` Hamming self-healing) and accelerates chunk networking via **LeafAnvil-Net** (`Mode A` vanilla-compatible packet caching and `Mode B` `LCD2` / `ODCT` wire framing).

---

## Primary Use Cases

1. **Drop-In World Storage Reduction (`50%–65%` smaller disks) on Paper, Folia, Spigot, Fabric, and NeoForge (`1.21.x` – `26.3`)**:
   - Install the universal `LeafAnvil-<version>.jar` in `plugins/` (or `mods/` on Fabric/NeoForge).
   - Newly saved chunks are written to `.lca` slabs automatically while existing `.mca` files remain readable in place (`dual-read`) until converted or touched.
2. **Online or Offline `.mca` / `.linear` <-> `.lca` Conversion**:
   - Run `/leafanvil convert <world> format=lca` to migrate existing `.mca` or `.linear` regions to `.lca`, or `/leafanvil convert <world> format=mca` to export back to vanilla `.mca` at any time.
3. **Self-Healing Bit-Rot Protection**:
   - Every `.lca` slab carries `CRC32C` and `[72, 64]` `SEC-DED` error-correcting parity so single-bit disk/RAM flips are corrected transparently and logged under `[LCA bit]`.
4. **High-Throughput Chunk Networking (`Mode A` & `Mode B`)**:
   - Keep `network.mode: AUTO` (or `VANILLA_SAFE`) so vanilla `1.21.x`–`26.3` clients receive pre-cached `Zlib` chunk packets while players with `LeafAnvil-Client` negotiate `LCD2` columnar delta streaming automatically.
