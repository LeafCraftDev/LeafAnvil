package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaModeABlockSection;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link LcaInMemoryChunkCoordinator} zero-copy off-heap load attachment, telemetry
 * counters, and lazy {@code coldSnapshot()} materialization (`anvil-api-ADR-007`).
 *
 * @governedBy anvil-api-ADR-007
 * @governedBy leafanvil-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LeafAnvilDirectLoadTest {

    private static final String[] BLOCKS = new String[] {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:dirt"
    };
    private static final String[] BIOMES = new String[] {
            "minecraft:plains"
    };

    private static long[] pack4BitLongs(int[] ordinals4096) {
        long[] longs = new long[256];
        for (int i = 0; i < 4096; i++) {
            int wordIdx = i >>> 4;
            int shift = (i & 15) << 2;
            longs[wordIdx] |= ((long) (ordinals4096[i] & 0xF)) << shift;
        }
        return longs;
    }

    @Test
    @DisplayName("attachDirectOffHeapColumn mounts prebuilt off-heap backings with zero staging copies and lazy CoW snapshot")
    void attachDirectOffHeapColumnAndTelemetry(@TempDir Path tempDir) throws Exception {
        Map<String, Integer> runtime = new HashMap<>();
        runtime.put("minecraft:air", 0);
        runtime.put("minecraft:stone", 1);
        runtime.put("minecraft:dirt", 2);
        Function<String, Integer> lookup = name -> runtime.getOrDefault(name, 0);

        int[] ords = new int[4096];
        for (int i = 0; i < 4096; i++) {
            ords[i] = (i % 41 == 0) ? 1 : ((i % 73 == 0) ? 2 : 0);
        }

        int sectionCount = 24;
        String[][] blockPal = new String[sectionCount][];
        int[][] preBlockIds = new int[sectionCount][];
        int[][] preBiomeIds = new int[sectionCount][];
        long[][] blockData = new long[sectionCount][];
        long[][] biomeData = new long[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blkLight = new byte[sectionCount][];

        preBlockIds[0] = new int[] {0, 1, 2};
        blockPal[0] = BLOCKS;
        blockData[0] = pack4BitLongs(ords);
        preBiomeIds[0] = new int[] {0};
        for (int s = 1; s < sectionCount; s++) {
            preBlockIds[s] = new int[] {0};
            preBiomeIds[s] = new int[] {0};
        }

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            writer.bindPrioritizedDictionary(BLOCKS, BIOMES);
            writer.writeFreshChunkDirectOffHeapModeA(
                    1, 2, 4790, 10L, 5L, "minecraft:full",
                    blockPal, null, null, null, null, preBlockIds, null, null,
                    blockData, null, preBiomeIds, biomeData, skyLight, blkLight, null, null
            );
            writer.flush();
        }

        LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                new LcaWireStreamProtocol.ClientChunkSlabCache(16), false
        );
        coord.resetDirectLoadTelemetryForTests();
        long stagingBefore = LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies();

        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(tempDir, false)) {
            reader.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            coord.bindWorldVoxelStorage("minecraft:overworld", reader, lookup, id -> BLOCKS[Math.min(id, 2)]);

            LcaRegionReader.DirectOffHeapChunkView view = reader.readDirectOffHeapChunkView(1, 2, lookup);
            assertNotNull(view);
            view.bind();
            coord.recordDirectOffHeapRead();
            coord.recordDirectBindNanos(1234L);
            coord.recordFastNbtBypassed();

            LcaSectionBacking[] backings = new LcaSectionBacking[sectionCount];
            LcaModeABlockSection.OffHeapModeASlice slice0 = view.trySliceOffHeapModeASection(0);
            assertNotNull(slice0);
            backings[0] = LcaSectionBacking.fromOffHeapModeASlice(slice0, id -> id != 0, id -> false, id -> false);
            for (int s = 1; s < sectionCount; s++) {
                backings[s] = new LcaSectionBacking(new byte[0], 0, 0, 0, 0, id -> id != 0, id -> false, id -> false, null);
            }

            LcaInMemoryChunkCoordinator.DirectOffHeapLoadCarrier carrier =
                    new LcaInMemoryChunkCoordinator.DirectOffHeapLoadCarrier(view, reader, "minecraft:overworld", 1, 2);
            carrier.setBoundMeta(view.directMeta());
            carrier.setPrebuiltBackings(backings);
            assertSame(view, carrier.view());
            assertSame(backings, carrier.prebuiltBackings());

            LcaInMemoryChunkCoordinator.TrackedChunkColumn col = coord.attachDirectOffHeapColumn(
                    "minecraft:overworld", 1, 2, view, backings, null, 100L, false
            );
            coord.recordDirectOffHeapActivation(5678L);
            coord.recordRecalcBlockCountsSkipped();

            assertSame(view, col.directOffHeapView());
            assertTrue(col.section(0).isOffHeapModeABacked());
            assertEquals(
                    stagingBefore,
                    LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies(),
                    "Attaching direct off-heap column must not materialize heap staging copies"
            );

            LcaInMemoryChunkCoordinator.DirectLoadTelemetrySnapshot snap = coord.directLoadTelemetrySnapshot();
            assertEquals(1L, snap.directOffHeapReads());
            assertEquals(1L, snap.directOffHeapActivations());
            assertEquals(1L, snap.fastNbtBypassed());
            assertEquals(1L, snap.recalcBlockCountsSkipped());
            assertEquals(1234L, snap.directBindNanos());
            assertEquals(5678L, snap.directActivateNanos());

            // Lazy CoW snapshot materialization still works on demand
            assertNotNull(col.coldSnapshot());
        }
    }
}
