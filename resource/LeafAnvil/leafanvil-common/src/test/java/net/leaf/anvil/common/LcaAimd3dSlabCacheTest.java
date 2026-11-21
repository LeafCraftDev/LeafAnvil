package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.Nbt;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.CompletedChunkCompaction;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification suite for unified zero-copy cold `.lca` `ChunkFrame` residency, instant $O(1)$
 * sparse `(pos -> runtimeStateId)` cell writes, synchronized `DirtyChunkLruCache` AIMD + LRU
 * write-back eviction, and section-spliced Copy-on-Write flushes in {@link LcaInMemoryChunkCoordinator}.
 *
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaAimd3dSlabCacheTest {

    private static final String[] PALETTE_STATES = {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:granite",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:coal_ore",
            "minecraft:iron_ore",
            "minecraft:copper_ore",
            "minecraft:gold_ore",
            "minecraft:deepslate",
            "minecraft:diamond_block",
            "minecraft:emerald_block"
    };

    private static Map<String, Integer> buildRuntimeMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < PALETTE_STATES.length; i++) {
            map.put(PALETTE_STATES[i], 200 + i);
        }
        return map;
    }

    private static LinkedHashMap<String, Object> build24SectionChunkNbt(int chunkX, int chunkZ) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", chunkX);
        root.put("yPos", -4);
        root.put("zPos", chunkZ);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 50L);
        root.put("InhabitedTime", 12L);

        List<Object> sections = new ArrayList<>(24);
        for (int s = 0; s < 24; s++) {
            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) (-4 + s));

            LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
            List<Object> pal = new ArrayList<>(3);
            LinkedHashMap<String, Object> p0 = new LinkedHashMap<>();
            p0.put("Name", PALETTE_STATES[1 + (s % 8)]);
            pal.add(p0);
            LinkedHashMap<String, Object> p1 = new LinkedHashMap<>();
            p1.put("Name", PALETTE_STATES[1 + ((s + 1) % 8)]);
            pal.add(p1);
            LinkedHashMap<String, Object> p2 = new LinkedHashMap<>();
            p2.put("Name", "minecraft:deepslate");
            pal.add(p2);
            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, pal));

            long[] words = new long[256];
            words[0] = (1L) | (2L << 4) | (1L << 12);
            words[1 + s * 5] = (2L << 8) | (1L << 16);
            words[255] = (1L << 60);
            bs.put("data", words);
            sec.put("block_states", bs);

            LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
            biomes.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, List.of("minecraft:plains")));
            sec.put("biomes", biomes);

            byte[] sky = new byte[2048];
            Arrays.fill(sky, (byte) ((s * 13 + 7) & 0xFF));
            sec.put("SkyLight", sky);
            sections.add(sec);
        }
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sections));
        return root;
    }

    private static LcaSectionBacking buildColdNonUniformSection(int minorityState) {
        int[] voxels = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        Arrays.fill(voxels, 1);
        voxels[LcaSectionBacking.pos12(1, 2, 3)] = minorityState;
        voxels[LcaSectionBacking.pos12(10, 11, 12)] = minorityState + 1;
        LcaSectionBacking backing = LcaSectionBacking.fromDirectVoxels(
                voxels,
                15,
                0,
                LcaSectionBacking.BULK_4096_CAPACITY,
                0,
                0,
                id -> id != 0,
                id -> false,
                id -> false
        );
        assertTrue(backing.evictCompactBaseIndexToColdBaseFrame());
        return backing;
    }

    @Test
    @DisplayName("Cold .lca ChunkFrame loads with 0 dirty LRU slots, skips NMS re-encode, writes 1/50/500 sparse cells with CompactBaseIndex == null, and CoW-splices 23 clean sections bit-for-bit")
    void zeroCopyColdChunkFrameSparseDirtyWritesAndCoWSpliceFlush(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Map<String, Integer> runtimeIds = buildRuntimeMap();

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, true)) {
            storage.writeChunkCompound(4, -7, build24SectionChunkNbt(4, -7));

            LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(128));
            try {
                coordinator.bindWorldVoxelStorage(
                        "minecraft:overworld",
                        storage,
                        runtimeIds::get,
                        id -> {
                            int idx = id - 200;
                            return (idx >= 0 && idx < PALETTE_STATES.length) ? PALETTE_STATES[idx] : "minecraft:air";
                        }
                );

                RawChunkFrameSnapshot diskSnap = storage.readRawChunkFrame(4, -7, runtimeIds::get);
                assertNotNull(diskSnap);
                byte[][] initialSectionSlices = new byte[24][];
                for (int s = 0; s < 24; s++) {
                    initialSectionSlices[s] = diskSnap.sliceSectionFrame(s);
                    assertTrue(initialSectionSlices[s].length > 0);
                }

                TrackedChunkColumn col = coordinator.attachColdChunkFrameColumn(
                        "minecraft:overworld", 4, -7, diskSnap, null, 60L, false);
                assertEquals(0, coordinator.dirtyChunkCount(), "Clean cold ChunkFrame must occupy 0 DirtyChunkLruCache slots");
                assertNotNull(col.coldChunkFrame());

                // Submitting NMS snapshots for an already cold-mounted ChunkFrame must skip background re-encoding!
                LcaSectionBridge.RawSectionSnapshot[] dummySnapshots = new LcaSectionBridge.RawSectionSnapshot[24];
                assertFalse(
                        coordinator.submitChunkSnapshotForCompaction(
                                "minecraft:overworld", 4, -7, new Object(), null, dummySnapshots,
                                15, 61L, id -> id != 0, id -> false, id -> false),
                        "Already cold-mounted ChunkFrame column must bypass background NMS-to-.lca re-encoding"
                );

                // Also verify zero-flag plugin mode reuses readRawChunkFrame off-thread on a second chunk (5, -7)
                storage.writeChunkCompound(5, -7, build24SectionChunkNbt(5, -7));
                for (int s = 0; s < 24; s++) {
                    dummySnapshots[s] = new LcaSectionBridge.RawSectionSnapshot(s, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0);
                }
                assertTrue(coordinator.submitChunkSnapshotForCompaction(
                        "minecraft:overworld", 5, -7, new Object(), null, dummySnapshots,
                        15, 62L, id -> id != 0, id -> false, id -> false));
                assertTrue(coordinator.awaitPendingBackgroundCompactions(5_000L));
                AtomicReference<CompletedChunkCompaction> completedRef = new AtomicReference<>();
                assertEquals(1, coordinator.drainCompletedChunkCompactions(4, completedRef::set));
                assertNotNull(completedRef.get().coldSnapshotOrNull(),
                        "Off-thread load path must reuse disk RawChunkFrameSnapshot directly instead of re-encoding dummy NMS snapshots");

                // Wire export of cold chunk neither hydrates CompactBaseIndex nor pollutes DirtyChunkLruCache
                EntryFullChunk wireChunk = col.toWireFullChunkEntry(60L);
                assertEquals(24, wireChunk.sections().size());
                assertEquals(0, coordinator.dirtyChunkCount());

                // Mutate 2 blocks in section y = 4 (sectionIndex = 8) via instant O(1) sparse writes
                int diamondRuntimeId = runtimeIds.get("minecraft:diamond_block");
                int emeraldRuntimeId = runtimeIds.get("minecraft:emerald_block");
                col.recordDirtyVoxel(8, LcaSectionBacking.pos12(2, 3, 4), 201, diamondRuntimeId, 70L);
                col.recordDirtyVoxel(8, LcaSectionBacking.pos12(14, 15, 9), 201, emeraldRuntimeId, 71L);

                assertEquals(1, coordinator.dirtyChunkCount(), "Mutated chunk must occupy 1 slot in DirtyChunkLruCache");
                assertTrue(coordinator.isChunkInDirtyLru("minecraft:overworld", 4, -7));
                assertFalse(col.section(8).hasHydratedCompactBaseIndex(), "Sparse cell writes must leave CompactBaseIndex == null");
                assertEquals(16, col.section(8).activeOverlayCapacity(), "2 sparse edits must occupy 16-slot L1 line (64 B)");

                // Verify wire slice exports cold baseFrame + 2 packed 4-byte dirty modifiers without flushing
                EntryFullChunk dirtyWire = col.toWireFullChunkEntry(71L);
                assertEquals(2, dirtyWire.sections().get(8).dirtyOverlayEntries().length);

                // Flush dirty chunk via off-thread section-spliced CoW commit
                byte[] priorColdRef = col.coldChunkFrame();
                assertTrue(coordinator.flushDirtyChunkCoWAsync("minecraft:overworld", 4, -7));
                assertTrue(coordinator.awaitPendingBackgroundCompactions(5_000L));

                assertEquals(0, coordinator.dirtyChunkCount(), "Flushed clean chunk must immediately drop out of DirtyChunkLruCache");
                assertFalse(coordinator.isChunkInDirtyLru("minecraft:overworld", 4, -7));
                assertNotSame(priorColdRef, col.coldChunkFrame(), "CoW flush must atomically swap TrackedChunkColumn.chunkFrame");
                assertFalse(col.section(8).hasHydratedCompactBaseIndex(), "CoW flush must keep section cold (CompactBaseIndex == null)");

                RawChunkFrameSnapshot afterDiskSnap = storage.readRawChunkFrame(4, -7, runtimeIds::get);
                assertNotNull(afterDiskSnap);
                for (int s = 0; s < 24; s++) {
                    if (s != 8) {
                        assertArrayEquals(
                                initialSectionSlices[s],
                                afterDiskSnap.sliceSectionFrame(s),
                                "Untouched section " + s + " must remain bit-for-bit identical after CoW flush");
                    }
                }
                storage.flush();
                AnvilChunkView view = LcaRegionReader.readChunkViewFromFile(
                        regionDir.resolve("r.0.-1.lca"), 4 & 31, (-7) & 31);
                assertNotNull(view);
                assertEquals("minecraft:diamond_block", view.blockIdAt(2, 64 + 3, 4));
                assertEquals("minecraft:emerald_block", view.blockIdAt(14, 64 + 15, 9));
            } finally {
                coordinator.shutdownCompactionPool();
            }
        }
    }

    @Test
    @DisplayName("DirtyChunkLruCache AIMD + LRU victim write-back eviction, 500-cell sparse scaling, concurrent thread safety, and S-002 unload cleanup")
    void dirtyLruAimdVictimEvictionConcurrentThreadSafetyAndUnloadPurge() throws Exception {
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(128));
        try {
            coordinator.setMaxDirtyChunks(2);
            coordinator.setAimdParameters(16, 1, 1);

            TrackedChunkColumn[] cols = new TrackedChunkColumn[4];
            for (int c = 0; c < 4; c++) {
                LcaSectionBacking[] secs = new LcaSectionBacking[4];
                for (int i = 0; i < 4; i++) {
                    secs[i] = buildColdNonUniformSection(30 + (c * 8) + i);
                }
                cols[c] = coordinator.attachChunkColumn("world", c, 0, secs, null, 10L);
            }
            assertEquals(0, coordinator.dirtyChunkCount(), "Attached clean columns must occupy 0 dirty LRU slots");

            // Chunk 0: 4 edits -> high AIMD score (64)
            for (int x = 0; x < 4; x++) {
                cols[0].recordDirtyVoxel(1, LcaSectionBacking.pos12(x, 0, 0), 1, 101 + x, 11L + x);
            }
            assertEquals(64, cols[0].totalAimdEditScore());

            // Chunk 1: 1 edit -> low AIMD score (16), older LRU than Chunk 2
            cols[1].recordDirtyVoxel(2, LcaSectionBacking.pos12(0, 0, 0), 1, 120, 15L);
            assertEquals(16, cols[1].totalAimdEditScore());
            assertEquals(2, coordinator.dirtyChunkCount());

            // Chunk 2: 500 sparse edits -> capacity overflow (3 > maxDirtyChunks=2) schedules lowest-AIMD/LRU victim (Chunk 1)
            for (int p = 0; p < 500; p++) {
                cols[2].recordDirtyVoxel(0, p, 1, 200 + (p & 63), 20L);
            }
            assertFalse(cols[2].section(0).hasHydratedCompactBaseIndex(), "500 sparse writes must leave CompactBaseIndex == null");
            assertEquals(1024, cols[2].section(0).activeOverlayCapacity(), "500 sparse writes must use 1024 slots, never int[4096]");

            assertTrue(coordinator.awaitPendingBackgroundCompactions(5_000L));
            assertEquals(2, coordinator.dirtyChunkCount(), "DirtyChunkLruCache must drain back to maxDirtyChunks=2 after off-thread victim flush");
            assertFalse(coordinator.isChunkInDirtyLru("world", 1, 0), "Lowest-AIMD/oldest-LRU victim Chunk 1 must flush and drop to cold");
            assertEquals(120, cols[1].section(2).getStateId(0, 0, 0), "Flushed victim Chunk 1 must preserve its block edit in cold baseFrame");
            assertTrue(coordinator.isChunkInDirtyLru("world", 0, 0));
            assertTrue(coordinator.isChunkInDirtyLru("world", 2, 0));

            // Concurrent multi-threaded writes & LRU touches under dirtyCacheLock (0 race hazards)
            ExecutorService racePool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final int tid = t;
                    tasks.add(racePool.submit(() -> {
                        for (int iter = 0; iter < 64; iter++) {
                            cols[tid].recordDirtyVoxel(
                                    iter & 3,
                                    (tid * 97 + iter) & 4095,
                                    1,
                                    300 + (iter & 31),
                                    100L + iter
                            );
                            coordinator.touchDirtyChunkLru("world", tid, 0);
                            coordinator.isChunkInDirtyLru("world", (tid + 1) & 3, 0);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : tasks) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } finally {
                racePool.shutdownNow();
            }
            assertTrue(coordinator.awaitPendingBackgroundCompactions(5_000L));
            assertTrue(coordinator.dirtyChunkCount() <= 2, "DirtyChunkLruCache must drain back <= maxDirtyChunks=2 after concurrent burst");

            // Deterministic S-002 purge on chunk/world unload
            cols[3].recordDirtyVoxel(0, 7, 1, 333, 500L);
            coordinator.markChunkUnloaded("world", 3, 0);
            assertFalse(coordinator.isChunkInDirtyLru("world", 3, 0), "markChunkUnloaded must immediately purge DirtyChunkLruCache");

            coordinator.clearWorld("world");
            assertEquals(0, coordinator.dirtyChunkCount(), "clearWorld must deterministically purge all dirty cache entries");
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }
}
