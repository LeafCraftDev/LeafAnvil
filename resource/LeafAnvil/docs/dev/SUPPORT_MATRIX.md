# LeafAnvil Support Matrix

Canonical platform, Minecraft version, Java runtime, and storage/network compatibility matrix for **LeafAnvil** (`LeafAnvil`, `LeafAnvil-Net`, and `LeafAnvil-Client`).

---

## Minecraft Version & Platform Compatibility (1.21.x – 26.3)

Yes — **LeafAnvil supports Minecraft `1.21.x` (`1.21.1`–`1.21.11`), `26.1`, `26.2`, and `26.3`** out of the box across Paper, Folia, Spigot, Fabric, and NeoForge without waiting for hard-coded NMS package bumps.

| Platform / Loader | Supported Minecraft Versions | Java Requirement | Region Interception & Wire Mode | Status |
|---|---|---|---|---|
| **Paper / Purpur / Pufferfish** | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | Moonrise (`RegionFileStorage`) + Vanilla reflection bridge (`overworld`, `DIM-1`, `DIM1`, `poi`, `entities`) + Mode A / Mode B (`LCD2`) | **Tier-1 Supported** |
| **Folia** | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | Regionized-thread safe; zero main-thread synchronous chunk I/O (`S-005`) | **Tier-1 Supported** |
| **Spigot / CraftBukkit** | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | `ServerLevel` / `ChunkMap` `RegionFileStorage` interception + `.lca` / `.mca` dual-read | **Supported** |
| **Fabric** (`fabric-loader`) | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | Server & client mod (`LeafAnvil` + `LeafAnvil-Client` for Mode B `LCD2` decoding) | **Supported** |
| **NeoForge** | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | Server & client mod (`LeafAnvil` + `LeafAnvil-Client` for Mode B `LCD2` decoding) | **Supported** |
| **PumpkinMC (`crates/pumpkin-world`)** | `1.20.x`, `1.21.x`, `26.1`–`26.3` | None (Native Rust) | Pure-Rust `lca-format` + Direct `SectionFrame` Bridge (`[world.chunk] type = "lca"`, [`leafanvil-ADR-004`](../adr/leafanvil-ADR-004-pumpkinmc-rust-lca-direct-section-bridge-and-golden-fixtures.md)) | **Spec & Golden Fixtures Verified** |
| **Velocity / BungeeCord** | `1.21.x`, `26.1`, `26.2`, `26.3` | Java 21+ | Pass-through for `leafanvil:cap` / `leafanvil:chunk` (`LCD2`) plugin-messaging channels; pair with **LeafShard** for cross-backend region leases | **Compatible** |

### Version-Independent Storage Discovery (`26.2` / `26.3`)

`LeafAnvil` (`NmsRegionStorageInterceptor`) discovers `RegionFileStorage` instances structurally at runtime by scanning `CraftWorld` / `ServerLevel` fields up to depth 5 across both Moonrise (`ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO`) and vanilla `ChunkMap` / `EntityPersistentStorage` handles. Because it binds to field types and `RegionFile` method descriptors rather than brittle versioned craftbukkit package strings, **Minecraft `26.2` and `26.3` work with the same universal JAR**.

---

## Region File Format & Migration Matrix

| Format | Extension | Read Support | Write Support | Migration Command |
|---|---|---|---|---|
| **LeafCraft Anvil (`V_LCA1`)** | `.lca` | Native (`O(1)` 256 B slab + `SEC-DED` Hamming ECC) | Default (`storage.format: LCA`) | `/leafanvil convert <world> format=lca` |
| **Vanilla Anvil** | `.mca` | Dual-read on open (zero downtime) | Rollback / export target (`format=mca`) | `/leafanvil convert <world> format=mca` |
| **Linear Region (`Linear v1/v2`)** | `.linear` | Streaming import | Converts to `.lca` or `.mca` | `/leafanvil convert <world> format=lca` |

---

## Wire Transport Compatibility (`LeafAnvil-Net` & `LeafAnvil-Client`)

| Client Configuration | Server Mode | Framing | Client Mod Required? |
|---|---|---|---|
| **Vanilla Minecraft Client (`1.21.x` – `26.3`)** | **Mode A** (`network.mode: VANILLA_SAFE`, default) | Standard vanilla chunk packet framing with pre-compressed `Zlib` section caching | **No** (100% vanilla clients) |
| **Fabric / NeoForge Client with `LeafAnvil-Client`** | **Mode B** (`network.mode: LCD2` or `AUTO` after `leafanvil:cap` handshake) | `LCD2` (`0x4C44`) Continuous Atomic Region Dictionary + `EntryPatchDelta` over `leafanvil:lca_chunk_batch` (automatic fallback to Mode A for vanilla clients) | Optional (auto-negotiated per player) |

---

## LeafCraft Wheelhouse Integration

- **LeafShard (`resource/LeafShard`)**: Coordinates distributed single-writer `.lca` leases (`LCAS`), cross-shard border halo replication (`LSHD`), and region-boundary player handoffs across multi-node Paper/Folia clusters.
- **LeafMap (`resource/LeafMap`)**: Reads `.lca` archives directly off-thread using `LeafAnvil`'s `64 B` superblock and `256 B` slab directory to stream 2D/3D web map tiles without loading chunks on the server tick thread.
- **LeafRTP (`resource/LeafRTP`)**: Uses `anvil-api` (`RegionFileReader`) to pre-screen candidate teleport coordinates against `.lca` and `.mca` region headers with zero main-thread chunk loads (`S-005`).
