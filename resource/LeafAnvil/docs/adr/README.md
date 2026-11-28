# `LeafAnvil` Architecture Decision Records

| ID | Title | Status |
|---|---|---|
| [`leafanvil-ADR-001`](leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md) | Multi-Loader `.lca` (LeafCraft Anvil) World Storage Carrier, Runtime `RegionFileStorage` Bytecode Interception & Reversible Migration (`LeafAnvil`) | Accepted |
| [`leafanvil-ADR-002`](leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md) | In-Memory `.lca` Chunk Representation, $O(1)$ Dirty-Overlay Buffer & Non-Overlapping Slab Compaction (Zero-Translation Network & `LeafShard` Delta Foundation) | Accepted |
| [`leafanvil-ADR-003`](leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md) | Negotiated Server-to-Client `.lca` Chunk Network Streaming, Batch `LCD2` Framing, `lastUpdateTick` Delta Sync & Inter-Server `LCAS` Streaming | Accepted |
| [`leafanvil-ADR-004`](leafanvil-ADR-004-pumpkinmc-rust-lca-direct-section-bridge-and-golden-fixtures.md) | PumpkinMC Pure-Rust `.lca` Codec (`lca-format`), Direct `SectionFrame` Bridge, Cross-Language Golden Fixture Suite & Performance Proof Contract | Retired (`0x0002` Prototype) |
| [`leafanvil-ADR-005`](leafanvil-ADR-005-dual-mode-javaagent-direct-section-bridge-and-netty-wire-pipeline.md) | Dual-Mode `-javaagent` Direct Section Bridge, Tier 1 Streaming NBT & Pool Concurrency, `LeafGen` Zero-Re-Encode Pass-Through & Paper/Folia Netty `LCA_NET_V1` Pipeline | Accepted |
| [`leafanvil-ADR-006`](leafanvil-ADR-006-aimd-edit-scored-3d-chunk-slab-lru-cache.md) | Cold-by-Default `.lca` 3D Chunk Slabs & Bounded AIMD Edit-Count-Scored 3D Slab LRU Cache | Accepted |
| [`leafanvil-ADR-007`](leafanvil-ADR-007-progressive-3d-subterranean-occlusion-streaming-and-exposure-culling.md) | Progressive 3D Subterranean Occlusion Streaming, Monotone-Octant Sightline Reachability, Zero-Bloat Exposure Culling & Fail-Closed Handshake Gate | Proposed |
| [`leafanvil-ADR-008`](leafanvil-ADR-008-source-available-personal-testing-and-commercial-dual-licensing.md) | Public Source-Available Dual Licensing (`PolyForm-Noncommercial-1.0.0` for Personal & Private Testing + `LicenseRef-LeafCraft-Commercial`) | Accepted |
| [`anvil-api-ADR-002`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-002-multi-format-region-support.md) | Multi-Format Region Support: Pluggable Region Readers (`.mca`, `.linear`, `.lca`) | Accepted |
| [`anvil-api-ADR-003`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md) | `.lca` (LeafCraft Anvil) Binary Specification: 3D Hilbert, Adaptive Delta-Roaring, Gray-Code Light & Octree Streaming | Accepted |
| [`anvil-api-ADR-004`](../../../../api/anvil-api/docs/adr/anvil-api-ADR-004-overlaid-eikonal-light-columnar-residuals-and-adaptive-rank-exclusion.md) | Overlaid Eikonal Light Residuals, Columnar Tick/BlockEntity Tails & Chunk-Adaptive Rank-Exclusion `.lca` Layers | Proposed |
| [`ADR-126`](../../../../docs/adr/ADR-126-multi-product-monorepo-release-architecture.md) | Multi-Product Monorepo Release Architecture | Accepted |
