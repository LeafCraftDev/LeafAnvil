package net.leaf.anvil.common.net;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.ByteBudgetPackedTickPlan;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.RegionPackedBatchFrame;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ChunkDigestEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryCleanAck;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies deterministic Mode A (`0-LZ`) `.lca` wire-byte accounting, saved-upload-space multi-chunk
 * packing per vanilla congestion credit, revisit-to-new-terrain whip-saw capping, and cross-region
 * batch grouping in {@link LcaInMemoryChunkCoordinator} (`leafanvil-ADR-003`, `leafanvil-ADR-002`).
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-002, anvil-api-ADR-003, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
class LcaByteBudgetWirePackingTest {

    private static final long REGISTRY_HASH_64 = 0x6A09E667F3BCC908L;
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    @DisplayName("O(1) pre-serialization wire byte estimators match raw batch and Netty framed bytes exactly")
    void preSerializationWireByteEstimatorsMatchExactSerializedBytes() {
        EntryCleanAck cleanAck = new EntryCleanAck(LcaWireStreamProtocol.toLocalChunkIndex10(3, 7));
        assertEquals(2, LcaInMemoryChunkCoordinator.computeEntryWireBytes(cleanAck, true));
        assertEquals(2, cleanAck.toBytes().length);

        byte[] baseSliceA = new byte[184];
        byte[] baseSliceB = new byte[240];
        new SplittableRandom(42L).nextBytes(baseSliceA);
        new SplittableRandom(43L).nextBytes(baseSliceB);

        SectionWireSlice dirtySecA = SectionWireSlice.nonUniform(
                4,
                baseSliceA,
                new int[]{LcaWireStreamProtocol.packDirtyOverlayEntry(17, 9), LcaWireStreamProtocol.packDirtyOverlayEntry(204, 12)}
        );
        SectionWireSlice dirtySecB = SectionWireSlice.nonUniform(
                9,
                baseSliceB,
                new int[]{LcaWireStreamProtocol.packDirtyOverlayEntry(511, 3)}
        );
        EntryPatchDelta patchDelta = new EntryPatchDelta(
                LcaWireStreamProtocol.toLocalChunkIndex10(5, 11),
                12_345L,
                0x1122334455667788L,
                (1 << 4) | (1 << 9),
                List.of(dirtySecA, dirtySecB)
        );

        List<SectionWireSlice> fullSections = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            if (i >= 4 && i < 12) {
                fullSections.add(SectionWireSlice.nonUniform(
                        i,
                        baseSliceA,
                        i == 5 ? new int[]{LcaWireStreamProtocol.packDirtyOverlayEntry(12, 44)} : new int[0]
                ));
            } else {
                fullSections.add(SectionWireSlice.uniform(i, 3, 1));
            }
        }
        byte[] heightmaps = new byte[128];
        EntryFullChunk fullChunk = EntryFullChunk.createWithComputedSyndrome(
                -19,
                42,
                99_887L,
                fullSections,
                heightmaps
        );

        List<BatchChunkEntry> entries = List.of(cleanAck, patchDelta, fullChunk);
        ClientboundLcaChunkBatchPayload batch = new ClientboundLcaChunkBatchPayload(-1, 1, entries);

        NegotiatedSession sessionOmitHm = new NegotiatedSession(
                true,
                LcaWireStreamProtocol.CAP_LCA_SECTION_STREAM
                        | LcaWireStreamProtocol.CAP_CLIENT_HEIGHTMAPS
                        | LcaWireStreamProtocol.CAP_DELTA_REVISIT_CACHE
                        | LcaWireStreamProtocol.CAP_PROXY_SAFE_STORED_ZLIB,
                32 * 1024,
                REGISTRY_HASH_64
        );
        NegotiatedSession sessionWithHm = new NegotiatedSession(
                true,
                LcaWireStreamProtocol.CAP_LCA_SECTION_STREAM
                        | LcaWireStreamProtocol.CAP_DELTA_REVISIT_CACHE
                        | LcaWireStreamProtocol.CAP_PROXY_SAFE_STORED_ZLIB,
                32 * 1024,
                REGISTRY_HASH_64
        );

        byte[] rawOmitHm = LcaWireStreamProtocol.serializeRawChunkBatch(batch, sessionOmitHm);
        int estimatedRawOmitHm = LcaInMemoryChunkCoordinator.computeRawBatchWireBytes(-1, 1, entries, true);
        assertEquals(rawOmitHm.length, estimatedRawOmitHm, "Estimated raw batch bytes (omitHeightmaps=true) must match exact serialized length");

        byte[] rawWithHm = LcaWireStreamProtocol.serializeRawChunkBatch(batch, sessionWithHm);
        int estimatedRawWithHm = LcaInMemoryChunkCoordinator.computeRawBatchWireBytes(-1, 1, entries, false);
        assertEquals(rawWithHm.length, estimatedRawWithHm, "Estimated raw batch bytes (omitHeightmaps=false) must match exact serialized length");

        byte[] bypassFrame = LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(rawOmitHm);
        assertEquals(
                bypassFrame.length,
                LcaInMemoryChunkCoordinator.computeFramedBatchWireBytes(rawOmitHm.length, false),
                "VarInt=0 bypass frame size estimate must match exact frame length"
        );

        byte[] storedZlibFrame = LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(rawOmitHm);
        assertEquals(
                storedZlibFrame.length,
                LcaInMemoryChunkCoordinator.computeFramedBatchWireBytes(rawOmitHm.length, true),
                "Proxy-safe stored-Zlib frame size estimate must match exact frame length"
        );
    }

    @Test
    @DisplayName("Saved-upload-space packing fits 3-4 full .lca chunks into 1 vanilla chunk credit (10 KiB) in raw 0-LZ mode")
    void savedUploadSpacePacksMultipleFullChunksIntoSingleVanillaChunkCredit() throws IOException {
        LcaInMemoryChunkCoordinator serverTx = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(512 * 1024L), true);
        LcaInMemoryChunkCoordinator clientRx = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(512 * 1024L), true);
        try {
            UUID playerId = UUID.randomUUID();
            NegotiatedSession session = registerRawBatchSession(serverTx, playerId);

            // Attach 6 full chunks (~2.6 KiB each on wire: 10 non-uniform 240B sections + 14 uniform sections)
            List<TrackedChunkColumn> candidates = new ArrayList<>();
            SplittableRandom rng = new SplittableRandom(1001L);
            for (int c = 0; c < 6; c++) {
                candidates.add(attachRealisticChunk(serverTx, c, 2, 500L + c, 10, 240, rng));
            }

            // Pace with just 1 vanilla chunk credit (10,240 B budget)
            ByteBudgetPackedTickPlan plan = serverTx.packByteBudgetedOutboundBatchesForPlayer(
                    playerId,
                    OVERWORLD,
                    candidates,
                    1,
                    true
            );

            assertEquals(1, plan.vanillaChunkCredits());
            assertEquals(LcaInMemoryChunkCoordinator.DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT, plan.targetWireBudgetBytes());
            assertEquals(1, plan.regionFrames().size());
            assertTrue(
                    plan.totalPackedChunks() >= 3 && plan.totalPackedChunks() <= 4,
                    "1 vanilla chunk credit (10 KiB) must pack 3-4 full .lca chunks into saved space, got " + plan.totalPackedChunks()
            );
            assertEquals(6 - plan.totalPackedChunks(), plan.totalDeferredChunks());
            assertTrue(
                    plan.totalFramedWireBytes() <= LcaInMemoryChunkCoordinator.DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT,
                    "Total framed wire bytes (" + plan.totalFramedWireBytes() + ") must stay within 10 KiB budget"
            );
            assertTrue(plan.effectiveChunksPerVanillaCredit() >= 3.0);

            // Verify client can ingest the raw 0-LZ framed batch directly via receiveClientChunkBatchOrdered
            List<HydratedClientChunkCommit> committed = new ArrayList<>();
            for (RegionPackedBatchFrame frame : plan.regionFrames()) {
                clientRx.receiveClientChunkBatchOrdered(
                        OVERWORLD,
                        frame.framedWireBytes(),
                        true,
                        session,
                        null,
                        committed::add
                );
                clientRx.clientCommitQueue().drainReadyInStrictNettyOrder();
            }
            assertEquals(plan.totalPackedChunks(), committed.size());
        } finally {
            serverTx.shutdownCompactionPool();
            clientRx.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Whip-saw guard caps per-tick wire egress at 32 KiB when crossing from cached terrain into 16 new full chunks")
    void whipSawGuardCapsWireEgressWhenCrossingIntoNewTerrainAtHighVanillaQuota() throws IOException {
        LcaInMemoryChunkCoordinator serverTx = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(512 * 1024L), true);
        try {
            UUID playerId = UUID.randomUUID();
            registerRawBatchSession(serverTx, playerId);

            // Create 16 full chunks (~3.0 KiB each -> ~48 KiB if all 16 were sent unbudgeted)
            List<TrackedChunkColumn> candidates = new ArrayList<>(16);
            SplittableRandom rng = new SplittableRandom(2002L);
            for (int c = 0; c < 16; c++) {
                candidates.add(attachRealisticChunk(serverTx, c, 0, 900L + c, 12, 240, rng));
            }

            // PlayerChunkSender ramped up to 16 chunks/tick during prior cached traversal
            ByteBudgetPackedTickPlan plan = serverTx.packByteBudgetedOutboundBatchesForPlayer(
                    playerId,
                    OVERWORLD,
                    candidates,
                    16,
                    false
            );

            assertEquals(
                    LcaInMemoryChunkCoordinator.DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES,
                    plan.targetWireBudgetBytes(),
                    "16 vanilla credits must clamp at the 32 KiB per-tick whip-saw ceiling"
            );
            assertTrue(
                    plan.totalFramedWireBytes() <= LcaInMemoryChunkCoordinator.DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES,
                    "Framed wire bytes (" + plan.totalFramedWireBytes() + ") must not exceed 32 KiB whip-saw ceiling"
            );
            assertTrue(plan.totalPackedChunks() >= 9 && plan.totalPackedChunks() < 16);
            assertEquals(16 - plan.totalPackedChunks(), plan.totalDeferredChunks());
        } finally {
            serverTx.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Mixed revisit (2 B CleanAck + PatchDelta) and cross-region chunks pack into region-scoped frames under shared byte budget")
    void mixedRevisitAndCrossRegionChunksPackUnderSharedByteBudget() throws IOException {
        LcaInMemoryChunkCoordinator serverTx = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(512 * 1024L), true);
        try {
            UUID playerId = UUID.randomUUID();
            registerRawBatchSession(serverTx, playerId);

            SplittableRandom rng = new SplittableRandom(3003L);
            List<TrackedChunkColumn> candidates = new ArrayList<>();
            List<ChunkDigestEntry> regionZeroDigests = new ArrayList<>();

            // 8 unmodified revisit chunks in region (0, 0) -> 2 B EntryCleanAck each
            for (int c = 0; c < 8; c++) {
                TrackedChunkColumn col = attachRealisticChunk(serverTx, c, 1, 1000L, 8, 200, rng);
                candidates.add(col);
                regionZeroDigests.add(new ChunkDigestEntry(
                        LcaWireStreamProtocol.toLocalChunkIndex10(c, 1),
                        1000L,
                        col.baselineSecDed64()
                ));
            }

            // 1 partially mutated revisit chunk in region (0, 0) -> EntryPatchDelta
            TrackedChunkColumn deltaCol = attachRealisticChunk(serverTx, 8, 1, 1000L, 8, 200, rng);
            long priorSyndrome = deltaCol.baselineSecDed64();
            deltaCol.mutateVoxel(4, 2, 2, 2, 55, 1005L);
            candidates.add(deltaCol);
            regionZeroDigests.add(new ChunkDigestEntry(
                    LcaWireStreamProtocol.toLocalChunkIndex10(8, 1),
                    1000L,
                    priorSyndrome
            ));

            serverTx.recordPlayerRegionDigest(
                    playerId,
                    new ServerboundLcaChunkDigestPayload(OVERWORLD, 0, 0, regionZeroDigests)
            );

            // 2 new full chunks across the region boundary in region (1, 0) (chunkX = 32, 33)
            candidates.add(attachRealisticChunk(serverTx, 32, 1, 1010L, 8, 200, rng));
            candidates.add(attachRealisticChunk(serverTx, 33, 1, 1011L, 8, 200, rng));

            // 1 vanilla credit (10 KiB) should fit all 8 CleanAcks (16 B) + 1 PatchDelta (~220 B) in region (0,0)
            // AND both new full chunks (~3.4 KiB) in region (1,0)
            ByteBudgetPackedTickPlan plan = serverTx.packByteBudgetedOutboundBatchesForPlayer(
                    playerId,
                    OVERWORLD,
                    candidates,
                    1,
                    true
            );

            assertEquals(11, plan.totalPackedChunks(), "All 9 revisit chunks in region (0,0) + 2 full chunks in region (1,0) must fit inside 10 KiB");
            assertEquals(0, plan.totalDeferredChunks());
            assertEquals(2, plan.regionFrames().size(), "Chunks across region (0,0) and (1,0) must group into 2 region frames");

            RegionPackedBatchFrame reg0 = plan.regionFrames().get(0);
            assertEquals(0, reg0.regionX());
            assertEquals(0, reg0.regionZ());
            assertEquals(8, reg0.cleanAckCount());
            assertEquals(1, reg0.patchDeltaCount());
            assertEquals(0, reg0.fullChunkCount());

            RegionPackedBatchFrame reg1 = plan.regionFrames().get(1);
            assertEquals(1, reg1.regionX());
            assertEquals(0, reg1.regionZ());
            assertEquals(0, reg1.cleanAckCount());
            assertEquals(0, reg1.patchDeltaCount());
            assertEquals(2, reg1.fullChunkCount());

            assertFalse(plan.regionFrames().isEmpty());
        } finally {
            serverTx.shutdownCompactionPool();
        }
    }

    private static NegotiatedSession registerRawBatchSession(
            LcaInMemoryChunkCoordinator coordinator,
            UUID playerId
    ) {
        int rawBatchCaps = LcaWireStreamProtocol.CAP_LCA_SECTION_STREAM
                | LcaWireStreamProtocol.CAP_CLIENT_HEIGHTMAPS
                | LcaWireStreamProtocol.CAP_DELTA_REVISIT_CACHE
                | LcaWireStreamProtocol.CAP_PROXY_SAFE_STORED_ZLIB;
        HandshakePayload hs = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                rawBatchCaps,
                32 * 1024,
                REGISTRY_HASH_64
        );
        return coordinator.registerPlayerHandshake(playerId, hs, REGISTRY_HASH_64);
    }

    private static TrackedChunkColumn attachRealisticChunk(
            LcaInMemoryChunkCoordinator coordinator,
            int chunkX,
            int chunkZ,
            long tick,
            int nonUniformCount,
            int nonUniformBytes,
            SplittableRandom rng
    ) {
        LcaSectionBacking[] sections = new LcaSectionBacking[24];
        for (int i = 0; i < 24; i++) {
            if (i < nonUniformCount) {
                byte[] frame = new byte[nonUniformBytes];
                rng.nextBytes(frame);
                sections[i] = new LcaSectionBacking(frame, -1);
            } else {
                sections[i] = new LcaSectionBacking(new byte[0], 1);
            }
        }
        return coordinator.attachChunkColumn(OVERWORLD, chunkX, chunkZ, sections, null, tick, false);
    }
}
