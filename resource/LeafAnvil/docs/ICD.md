# `.lca` (LeafCraft Anvil) Binary Format Interface Control Document (ICD) — Router

> **Single Source of Truth:** The canonical, normative `.lca` Binary Format Interface Control Document (`LCA-ICD-1.0`, MIT License) is maintained exclusively in **[`api/anvil-api/docs/ICD.md`](../../../api/anvil-api/docs/ICD.md)**.
>
> Do not duplicate byte tables, bitfield definitions, or static lookup tables in this file. During builds, `:resource:LeafAnvil:leafanvil-common` (`processResources`) packages [`api/anvil-api/docs/ICD.md`](../../../api/anvil-api/docs/ICD.md) directly into `LeafAnvil-<version>.jar` at both `docs/ICD.md` and `META-INF/leafanvil/ICD.md`, and `LeafAnvilConfig` extracts that canonical specification to `<config-dir>/ICD.md` on server startup.

---

## Canonical Specification & Section Index ([`api/anvil-api/docs/ICD.md`](../../../api/anvil-api/docs/ICD.md))

| Section | Topic | Canonical Link |
| :--- | :--- | :--- |
| **Section 1** | Purpose, Scope & Open Format Interoperability (`MIT`) | [`api/anvil-api/docs/ICD.md#1-purpose--scope`](../../../api/anvil-api/docs/ICD.md#1-purpose--scope) |
| **Section 2** | Primitive Data Types, Big-Endian Conventions, `VarInt` & `Bounded240` | [`api/anvil-api/docs/ICD.md#2-primitive-data-types--endianness-conventions`](../../../api/anvil-api/docs/ICD.md#2-primitive-data-types--endianness-conventions) |
| **Section 3** | Top-Level File Layout (`r.<rx>.<rz>.lca`) | [`api/anvil-api/docs/ICD.md#3-top-level-file-layout-rrxrzlca`](../../../api/anvil-api/docs/ICD.md#3-top-level-file-layout-rrxrzlca) |
| **Section 4** | Layer 1: Superblock (`64 B`), Pointer Directory (`uint64[1024]`) & Hamming `(72, 64)` `SEC-DED` Table (`1,024 B`) | [`api/anvil-api/docs/ICD.md#4-layer-1-superblock-64-b-pointer-directory-8192-b--sec-ded-table-1024-b`](../../../api/anvil-api/docs/ICD.md#4-layer-1-superblock-64-b-pointer-directory-8192-b--sec-ded-table-1024-b) |
| **Section 5** | Layer 2: Chained Region Shared Dictionary (`0x2040` & `nextSegmentLinkTrailer`) | [`api/anvil-api/docs/ICD.md#5-layer-2-chained-region-shared-dictionary-0x2040`](../../../api/anvil-api/docs/ICD.md#5-layer-2-chained-region-shared-dictionary-0x2040) |
| **Section 6** | Layer 3: Self-Describing `ChunkFrame` Header, Mode B Wrapper, Order-Independent `sectionOffsets[i]` & Slab-Local Micro-Palette (`Part A` + `Part B`) | [`api/anvil-api/docs/ICD.md#6-layer-3-self-describing-chunkframe-header-mode-b-wrapper--section-directory`](../../../api/anvil-api/docs/ICD.md#6-layer-3-self-describing-chunkframe-header-mode-b-wrapper--section-directory) |
| **Section 7** | Layer 4: $16 \times 16 \times 16$ Non-Uniform `SectionFrame` Layout (`YZX`/`H_3`/`Y+H_2`/`XZY` Curves, Delta-Roaring, Flat Bit-Pack & Gray-Code Light Bitplanes) | [`api/anvil-api/docs/ICD.md#7-layer-4-16-times-16-times-16-non-uniform-sectionframe-layout`](../../../api/anvil-api/docs/ICD.md#7-layer-4-16-times-16-times-16-non-uniform-sectionframe-layout) |
| **Section 8** | Layer 3c: Residual Tail (`MOTION_BLOCKING_NO_LEAVES`, `boilerplateFlags16` Bits `0..14`, and `ChunkPatchTail` `HAS_CHUNK_PATCH_TAIL = 0x4000`) | [`api/anvil-api/docs/ICD.md#8-layer-3c-residual-tail-motion_blocking_no_leaves--boilerplateflags16`](../../../api/anvil-api/docs/ICD.md#8-layer-3c-residual-tail-motion_blocking_no_leaves--boilerplateflags16) |
| **Section 9** | Static Core Lookup Tables (`STATIC_CORE_STATES` `160`, `STATIC_CORE_BIOMES` `65`, `STATIC_CORE_PROP_SUFFIXES` `128`) | [`api/anvil-api/docs/ICD.md#9-static-core-lookup-tables-static_core_`](../../../api/anvil-api/docs/ICD.md#9-static-core-lookup-tables-static_core_) |
| **Section 10** | Reader & 4-Tier Bit-Rot Recovery Procedures (`healRegionBitRot`) | [`api/anvil-api/docs/ICD.md#10-reader--recovery-procedures`](../../../api/anvil-api/docs/ICD.md#10-reader--recovery-procedures) |

---

## Related Governance & Implementation Sources

- **Canonical Binary Specification (`LCA-ICD-1.0`):** [`api/anvil-api/docs/ICD.md`](../../../api/anvil-api/docs/ICD.md)
- **Format Architecture & Rationale:** [`anvil-api-ADR-003`](../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md)
- **Multi-Format Region Reader SPI:** [`anvil-api-ADR-002`](../../../api/anvil-api/docs/adr/anvil-api-ADR-002-multi-format-region-support.md)
- **Multi-Loader Runtime Carrier & Bytecode Interceptor:** [`leafanvil-ADR-001`](adr/leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md)
- **Reference Codec Implementation (`MIT`):** `io.github.dailystruggle.anvil.LcaRegionReader`, `LcaRegionWriter`, `LcaSectionCodec` ([`api/anvil-api`](../../../api/anvil-api/README.md))
