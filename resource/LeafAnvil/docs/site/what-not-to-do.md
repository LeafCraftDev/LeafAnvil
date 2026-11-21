# LeafAnvil — What Not To Do

## 1. Do Not Delete `.mca` Files Manually While `dual-read` Migration Is In Progress
When `LeafAnvil` is first installed on an existing world, untouched chunks are read from `.mca` on demand and written to `.lca` as regions are modified or converted via `/leafanvil convert <world> format=lca`. Wait until `/leafanvil convert` reports `100%` completion (and verify with `/leafanvil verify <world>`) before archiving or removing legacy `.mca` backups.

## 2. Do Not Point Multiple Uncoordinated Servers at the Same `.lca` Folder Without `LeafShard`
A single `.lca` file uses an in-memory Copy-on-Write slab allocation bitmap and generation counter. Never mount the same `world/region/` directory over NFS/SMB across multiple active Paper/Folia backends unless **LeafShard** (`LCAS` single-writer region leases) is managing region ownership.

## 3. Do Not Force `network.require-client-mod: true` on Public Vanilla Servers
`Mode B` (`LCD2`) requires `LeafAnvil-Client` on the player's Fabric/NeoForge client to decode `leafanvil:chunk` payloads. Leave `network.mode: AUTO` (or `VANILLA_SAFE`) so vanilla `1.21.x`–`26.3` clients automatically use `Mode A` while modded clients upgrade to `Mode B`.

## 4. Do Not Ignore `LCA-CBIT-*` Integrity Warnings
If `/leafanvil status` or the console logs an `LCA-CBIT-*` parity/CRC finding, run `/leafanvil verify <world>` and check disk SMART health before restarting.
