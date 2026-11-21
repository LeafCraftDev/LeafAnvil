# LeafAnvil — Frequently Asked Questions (FAQ)

## Does LeafAnvil work on Minecraft 26.3 as well as 26.2 and 1.21.x?
Yes. **LeafAnvil supports Minecraft `1.21.x`, `26.1`, `26.2`, and `26.3`** across **Paper**, **Folia**, **Spigot**, **Fabric**, and **NeoForge**. `NmsRegionStorageInterceptor` binds to `RegionFileStorage` and Paper Moonrise storage handles structurally at runtime, so `26.2` and `26.3` work with the same JAR without waiting for versioned NMS package updates. See [SUPPORT_MATRIX.md](../dev/SUPPORT_MATRIX.md).

## Does LeafAnvil support Folia?
Yes. All `.lca` slab reads, `LCD2` (`0x4C44`) Continuous Atomic Region Dictionary encoding, `ChunkPatchTail` / dirty-overlay delta compaction, and `/leafanvil convert` batch jobs are regionized-thread safe and respect Folia's region scheduler (`S-005`).

## How do I convert `.lca` region files back to vanilla `.mca`?
Run `/leafanvil convert <world> format=mca` (or use the standalone CLI converter on an offline world folder) and verify with `/leafanvil verify <world>`. `LeafAnvil` supports lossless bidirectional `.mca` <-> `.lca` conversion so your world data is never locked in.

## Do players need a client mod to join a server running LeafAnvil?
No. By default, `LeafAnvil` runs in `Mode A` (`VANILLA_SAFE` / `AUTO`), which is 100% compatible with unmodified vanilla Minecraft `1.21.x`–`26.3` clients. Players who optionally install `LeafAnvil-Client` on Fabric or NeoForge automatically negotiate `Mode B` (`LCD2` columnar wire compression) via the `leafanvil:cap` channel.

## What diagnostics should I share if I hit a runtime issue?
Run `/leafanvil status` and `/leafanvil verify <world>`, check your server `/version` output, and upload `logs/latest.log` to <https://mclo.gs/>.
