package net.leaf.anvil.common.net;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.ByteBudgetPackedTickPlan;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.LeafAnvilConfig;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.ConsolidatedMultiTargetFrame;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.DecodedMultiTargetFrame;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.KinematicDeltaRow;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.TargetRecipient;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.DictSlotEntry;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.KnownSlotBitSet;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.OrderlessEncodedBatch;
import net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.DifferentialSnapshot;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification tests for {@link LcaPacketDifferentialProfiler}, {@link LcaOrderlessDictTable},
 * {@link LcaMultiTargetConsolidator}, and the `500 kbps` (`350 kbps` usable) low-bandwidth upload
 * pacer in {@link LcaInMemoryChunkCoordinator} (`leafanvil-ADR-003`, `leafanvil-ADR-004`).
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-004, leafanvil-ADR-002, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-004
 * @governedBy leafanvil-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@DisplayName("leafanvil-ADR-003: 500 kbps (350 kbps Usable) Differential, Orderless Dict & Multi-Target Consolidation")
class LcaLowBandwidth500kTest {

    private static final long REGISTRY_HASH_64 = 0x6A09E667F3BCC908L;
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    @DisplayName("Packet differential profiler tracks L7 payloads, L3/L4 52B TCP headers, and 350 kbps usable upload budget")
    void packetDifferentialProfilerMeasuresL7AndTcpFramingSavingsUnder350Kbps() {
        LcaPacketDifferentialProfiler profiler = new LcaPacketDifferentialProfiler();
        assertEquals(2_187, LcaPacketDifferentialProfiler.PRACTICAL_500K_BYTES_PER_TICK);
        assertEquals(
                LcaPacketDifferentialProfiler.BUCKET_CHUNK_STREAM,
                LcaPacketDifferentialProfiler.classifyPacketName("ClientboundLevelChunkWithLightPacket")
        );
        assertEquals(
                LcaPacketDifferentialProfiler.BUCKET_ENTITY_KINEMATICS,
                LcaPacketDifferentialProfiler.classifyPacketName("MoveEntityPacketPosRot")
        );
        assertEquals(
                LcaPacketDifferentialProfiler.BUCKET_ENTITY_METADATA_NBT,
                LcaPacketDifferentialProfiler.classifyPacketName("ClientboundSetEntityDataPacket")
        );
        assertEquals(
                LcaPacketDifferentialProfiler.BUCKET_WORLD_BLOCK_DELTA,
                LcaPacketDifferentialProfiler.classifyPacketName("ClientboundSectionBlocksUpdatePacket")
        );
        assertEquals(
                LcaPacketDifferentialProfiler.BUCKET_TRANSIENT_FX_UI,
                LcaPacketDifferentialProfiler.classifyPacketName("ClientboundSoundPacket")
        );

        // Simulate 20 ticks (1 second) for 5 co-op players on a 500 kbps (350 kbps usable) uplink
        for (int tick = 0; tick < 20; tick++) {
            // 1. Entity kinematics: Vanilla sends 20 packets (680B + 20 flushes), Optimized sends 1 coalesced table (90B + 1 flush)
            profiler.recordPacketDifferential(
                    LcaPacketDifferentialProfiler.BUCKET_ENTITY_KINEMATICS,
                    20, 680, 680, 20,
                    1, 90, 1
            );
            // 2. Entity metadata & sounds: Vanilla 8 packets (420B + 8 flushes), Optimized orderless dict (48B + 0 extra flush)
            profiler.recordPacketDifferential(
                    LcaPacketDifferentialProfiler.BUCKET_ENTITY_METADATA_NBT,
                    8, 420, 420, 8,
                    1, 48, 0
            );
            // 3. Remaining tick chunk budget must deduct non-chunk L7 + L3/L4 header bytes
            int remainingChunkBytes = profiler.remainingTickChunkBudgetBytes(
                    LcaPacketDifferentialProfiler.PRACTICAL_500K_USABLE_KBPS
            );
            assertTrue(remainingChunkBytes > 1_800 && remainingChunkBytes < 2_187);

            // 4. Chunk stream: Vanilla 1 chunk/tick (9,500B), Optimized orderless-dict + revisit mix (950B + 1 flush)
            profiler.recordPacketDifferential(
                    LcaPacketDifferentialProfiler.BUCKET_CHUNK_STREAM,
                    3, 28_000, 9_500, 7,
                    1, 950, 1
            );
            profiler.advanceTickWindow();
        }

        DifferentialSnapshot snap = profiler.snapshot(5);
        assertEquals(20L, snap.sampledTicks());
        assertTrue(snap.vanillaTotalKbps() > 1_500.0, "Vanilla 5-player traffic must exceed 1,500 kbps");
        assertTrue(
                snap.optimizedTotalKbps() <= LcaPacketDifferentialProfiler.PRACTICAL_500K_USABLE_KBPS,
                "Optimized 5-player traffic must fit inside 350 kbps usable ceiling, got " + snap.optimizedTotalKbps()
        );
        assertTrue(snap.fitsPractical500kUplink());
        assertTrue(snap.physicalWireReductionRatio() >= 0.85, "Total physical wire reduction must be >= 85%");
        assertTrue(snap.estimatedMaxPlayersAt350Kbps() >= 5);
    }

    @Test
    @DisplayName("Shared orderless dictionary learns patterns atomically, emits 2-byte references, and fails closed on tag mismatch")
    void orderlessDictionaryTableRoundTripsSlicesAndVerifiesTags() throws IOException {
        LcaOrderlessDictTable serverDict = new LcaOrderlessDictTable(2);
        LcaOrderlessDictTable clientDict = new LcaOrderlessDictTable(2);
        KnownSlotBitSet clientKnown = new KnownSlotBitSet();

        byte[] recurringPaletteA = "minecraft:stone|minecraft:deepslate|minecraft:iron_ore|minecraft:granite"
                .getBytes(StandardCharsets.UTF_8);
        byte[] recurringPaletteB = "minecraft:oak_log|minecraft:oak_leaves[distance=1,persistent=false]"
                .getBytes(StandardCharsets.UTF_8);
        byte[] oneOffLiteral = "unique-sign-text-987654321".getBytes(StandardCharsets.UTF_8);

        // First observation primes candidate count (threshold = 2)
        List<byte[]> tick1Slices = List.of(recurringPaletteA, recurringPaletteA, recurringPaletteB, oneOffLiteral);
        OrderlessEncodedBatch batch1 = serverDict.encodeSlicesForTarget(
                LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                tick1Slices,
                clientKnown
        );
        assertEquals(1, batch1.inlineLearnEntryCount(), "recurringPaletteA seen twice in tick 1 must promote and emit 1 inline learn entry");
        assertEquals(1, batch1.slotReferenceHitCount());
        assertEquals(1, clientKnown.knownSlotCount());

        List<byte[]> decoded1 = clientDict.decodeSlicesFromWire(batch1.wireBytes());
        assertEquals(tick1Slices.size(), decoded1.size());
        for (int i = 0; i < tick1Slices.size(); i++) {
            assertArrayEquals(tick1Slices.get(i), decoded1.get(i));
        }

        // Second tick: recurringPaletteA is already known by client (0 inline learn bytes, 2-byte ref only!);
        // recurringPaletteB crosses threshold=2 and is learned inline.
        List<byte[]> tick2Slices = List.of(recurringPaletteA, recurringPaletteA, recurringPaletteB, recurringPaletteB);
        OrderlessEncodedBatch batch2 = serverDict.encodeSlicesForTarget(
                LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                tick2Slices,
                clientKnown
        );
        assertEquals(1, batch2.inlineLearnEntryCount(), "Only recurringPaletteB needs an inline learn entry on tick 2");
        assertEquals(4, batch2.slotReferenceHitCount(), "All 4 slices on tick 2 must use 2-byte slot references");
        assertTrue(
                batch2.wireBytes().length < batch2.originalRawBytes() / 2,
                "Orderless dictionary batch on tick 2 must compress > 50%"
        );

        List<byte[]> decoded2 = clientDict.decodeSlicesFromWire(batch2.wireBytes());
        for (int i = 0; i < tick2Slices.size(); i++) {
            assertArrayEquals(tick2Slices.get(i), decoded2.get(i));
        }

        // Third tick: both patterns are already in clientKnown -> 0 inline learn entries, pure 2-byte references!
        OrderlessEncodedBatch batch3 = serverDict.encodeSlicesForTarget(
                LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                tick2Slices,
                clientKnown
        );
        assertEquals(0, batch3.inlineLearnEntryCount());
        assertEquals(4, batch3.slotReferenceHitCount());
        assertTrue(
                batch3.wireBytes().length <= 24,
                "4 known slices totaling ~280B must collapse to <= 24 bytes on wire, got " + batch3.wireBytes().length
        );
        List<byte[]> decoded3 = clientDict.decodeSlicesFromWire(batch3.wireBytes());
        for (int i = 0; i < tick2Slices.size(); i++) {
            assertArrayEquals(tick2Slices.get(i), decoded3.get(i));
        }

        // Corrupting the 4-bit verification tag in a 16-bit slot reference must fail closed (REQ-RTP-S-004)
        DictSlotEntry entryA = serverDict.observeOrLookup(
                LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                recurringPaletteA
        );
        assertNotNull(entryA);
        int corruptedRefWord16 = entryA.refWord16() ^ 0x01;
        assertThrows(
                IOException.class,
                () -> clientDict.resolveVerifiedReference(corruptedRefWord16),
                "Mismatched 4-bit tag must fail closed with IOException"
        );
    }

    @Test
    @DisplayName("SectionWireSlice dictionary compression deduplicates repeated non-uniform terrain motifs across chunks")
    void sectionWireSlicesRoundTripThroughOrderlessDict() throws IOException {
        LcaOrderlessDictTable serverDict = new LcaOrderlessDictTable(1);
        LcaOrderlessDictTable clientDict = new LcaOrderlessDictTable(1);
        KnownSlotBitSet clientKnown = new KnownSlotBitSet();

        byte[] commonSubsurfaceFrame = new byte[220];
        new SplittableRandom(777L).nextBytes(commonSubsurfaceFrame);

        List<SectionWireSlice> sections = List.of(
                SectionWireSlice.uniform(0, 0, 0),
                SectionWireSlice.nonUniform(1, commonSubsurfaceFrame, new int[]{LcaWireStreamProtocol.packDirtyOverlayEntry(10, 5)}),
                SectionWireSlice.nonUniform(2, commonSubsurfaceFrame, new int[0]),
                SectionWireSlice.nonUniform(3, commonSubsurfaceFrame, new int[0]),
                SectionWireSlice.uniform(4, 1, 15)
        );

        // First chunk teaches commonSubsurfaceFrame once and references it 3 times
        byte[] wireChunk1 = serverDict.encodeSectionWireSlicesWithDict(sections, clientKnown);
        List<SectionWireSlice> decoded1 = clientDict.decodeSectionWireSlicesWithDict(wireChunk1);
        assertEquals(sections.size(), decoded1.size());
        for (int i = 0; i < sections.size(); i++) {
            assertArrayEquals(sections.get(i).baseFrameSlice(), decoded1.get(i).baseFrameSlice());
            assertArrayEquals(sections.get(i).dirtyOverlayEntries(), decoded1.get(i).dirtyOverlayEntries());
        }

        // Second chunk with the same motif already in clientKnown needs 0 inline learn bytes
        byte[] wireChunk2 = serverDict.encodeSectionWireSlicesWithDict(sections, clientKnown);
        assertTrue(
                wireChunk2.length < 48,
                "3 repeated 220B non-uniform sections (660B) must compress to < 48B once slot is known, got " + wireChunk2.length
        );
        List<SectionWireSlice> decoded2 = clientDict.decodeSectionWireSlicesWithDict(wireChunk2);
        for (int i = 0; i < sections.size(); i++) {
            assertArrayEquals(sections.get(i).baseFrameSlice(), decoded2.get(i).baseFrameSlice());
        }
    }

    @Test
    @DisplayName("Multi-target consolidator intersects KnownSlotBitSets across players, synchronizes new joiners in 1 tick, and packs 4-6B kinematics")
    void multiTargetConsolidatorMergesRecipientsAndKinematics() throws IOException {
        LcaOrderlessDictTable serverDict = new LcaOrderlessDictTable(1);
        LcaOrderlessDictTable client1Dict = new LcaOrderlessDictTable(1);
        LcaOrderlessDictTable client2Dict = new LcaOrderlessDictTable(1);
        LcaOrderlessDictTable client3NewJoinerDict = new LcaOrderlessDictTable(1);

        byte[] sharedEntityArchetype = "minecraft:villager{profession:librarian,level:3,offers:standard}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] sharedSoundSymbol = "minecraft:entity.villager.ambient|volume=1.0|pitch=1.0"
                .getBytes(StandardCharsets.UTF_8);
        List<byte[]> sharedSlices = List.of(sharedEntityArchetype, sharedSoundSymbol, sharedEntityArchetype);

        // Prime client 1 and client 2 first
        TargetRecipient p1 = new TargetRecipient(0, UUID.randomUUID(), new KnownSlotBitSet());
        TargetRecipient p2 = new TargetRecipient(2, UUID.randomUUID(), new KnownSlotBitSet());

        List<KinematicDeltaRow> kinematics = List.of(
                KinematicDeltaRow.positionOnly(1, 0.25, 0.0, -0.5),
                KinematicDeltaRow.positionAndRotation(2, -0.125, 0.0625, 0.375, 90.0f, -15.0f),
                KinematicDeltaRow.positionOnly(3, 0.03125, 0.0, 0.03125)
        );
        byte[] sharedChunkAck = new LcaWireStreamProtocol.EntryCleanAck(42).toBytes();

        ConsolidatedMultiTargetFrame frameTick1 = LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                List.of(p1, p2),
                serverDict,
                LcaOrderlessDictTable.KIND_ENTITY_METADATA_ARCHETYPE,
                sharedSlices,
                kinematics,
                sharedChunkAck,
                14
        );
        assertTrue(frameTick1.targetsPlayerSlot(0));
        assertFalse(frameTick1.targetsPlayerSlot(1));
        assertTrue(frameTick1.targetsPlayerSlot(2));
        assertEquals(2, frameTick1.inlineDictLearnCount());
        assertEquals(3, frameTick1.dictSlotReferenceHits());
        assertEquals(2, p1.knownSlots().knownSlotCount());
        assertEquals(2, p2.knownSlots().knownSlotCount());

        DecodedMultiTargetFrame d1 = LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(
                frameTick1.sharedWireFrameBytes(),
                client1Dict
        );
        DecodedMultiTargetFrame d2 = LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(
                frameTick1.sharedWireFrameBytes(),
                client2Dict
        );
        assertEquals(3, d1.decodedKinematics().size());
        assertEquals(0.25, LcaMultiTargetConsolidator.dequantizeBlockDelta8(d1.decodedKinematics().get(0).dxFixed8()), 1e-6);
        assertArrayEquals(sharedEntityArchetype, d2.decodedRecurringSlices().get(0));

        // Tick 2: p1 and p2 already know both slots -> commonKnownSlots has 2 bits set -> 0 inline learn entries!
        ConsolidatedMultiTargetFrame frameTick2 = LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                List.of(p1, p2),
                serverDict,
                LcaOrderlessDictTable.KIND_ENTITY_METADATA_ARCHETYPE,
                sharedSlices,
                kinematics,
                sharedChunkAck,
                14
        );
        assertEquals(0, frameTick2.inlineDictLearnCount());
        assertTrue(frameTick2.directHostCoalescedPhysicalBytes() < frameTick2.unConsolidatedVanillaPhysicalBytes() / 4);
        assertTrue(frameTick2.edgeRelayMulticastPhysicalBytes() < frameTick2.directHostCoalescedPhysicalBytes());

        // Tick 3: A 3rd player (p3) with an empty KnownSlotBitSet joins the spatial group.
        // The intersection drops to 0 for that tick, re-emitting the inline learn entries once so p3 catches up!
        TargetRecipient p3 = new TargetRecipient(5, UUID.randomUUID(), new KnownSlotBitSet());
        ConsolidatedMultiTargetFrame frameTick3 = LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                List.of(p1, p2, p3),
                serverDict,
                LcaOrderlessDictTable.KIND_ENTITY_METADATA_ARCHETYPE,
                sharedSlices,
                kinematics,
                sharedChunkAck,
                14
        );
        assertEquals(2, frameTick3.inlineDictLearnCount());
        assertEquals(2, p3.knownSlots().knownSlotCount(), "New joiner p3 must converge to full knownSlots parity in 1 tick");
        DecodedMultiTargetFrame d3 = LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(
                frameTick3.sharedWireFrameBytes(),
                client3NewJoinerDict
        );
        assertArrayEquals(sharedEntityArchetype, d3.decodedRecurringSlices().get(2));
    }

    @Test
    @DisplayName("500 kbps (350 kbps usable) coordinator prioritizes Ring 0 (r <= 2) chunks first and enforces global tick upload cap")
    void lowBandwidth500kCoordinatorPrioritizesRing0AndCapsTickBytes(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("config.yml"),
                "enabled: true\nlcd2-level: 1\nmax-server-upload-kbps: 350\ncoalesce-tick-flushes: true\n",
                StandardCharsets.UTF_8
        );
        LeafAnvilConfig cfg = LeafAnvilConfig.loadOrCreate(tempDir);
        assertEquals(350, cfg.maxServerUploadKbps());
        assertTrue(cfg.coalesceTickFlushes());

        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(
                new ClientChunkSlabCache(512 * 1024L),
                true
        );
        try {
            coordinator.setMaxServerUploadKbps(cfg.maxServerUploadKbps());
            UUID playerId = UUID.randomUUID();
            NegotiatedSession session = coordinator.registerPlayerHandshake(
                    playerId,
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            16 * 1024,
                            REGISTRY_HASH_64
                    ),
                    REGISTRY_HASH_64
            );
            assertTrue(session.isLcaStreamActive());
            assertTrue(session.isOrderlessDictAndMulticastEnabled());

            // Attach horizon chunks (distance 9 and 7) BEFORE immediate Ring 0 chunks (distance 0 and 1)
            TrackedChunkColumn horizonFar = attachCompactColumn(coordinator, 9, 9);
            TrackedChunkColumn horizonMid = attachCompactColumn(coordinator, 5, 5);
            TrackedChunkColumn ring0Edge = attachCompactColumn(coordinator, 1, 1);
            TrackedChunkColumn ring0Center = attachCompactColumn(coordinator, 0, 0);

            List<TrackedChunkColumn> unOrderedCandidates = List.of(horizonFar, horizonMid, ring0Edge, ring0Center);

            // Record some non-chunk traffic in the current tick (180 B kinematics + 1 segment)
            coordinator.packetDifferentialProfiler().recordTickNonChunkTraffic(
                    LcaPacketDifferentialProfiler.BUCKET_ENTITY_KINEMATICS,
                    180,
                    1
            );

            ByteBudgetPackedTickPlan plan = coordinator.packLowBandwidth500kOutboundBatchesForPlayer(
                    playerId,
                    OVERWORLD,
                    0,
                    0,
                    unOrderedCandidates,
                    4,
                    2,
                    false
            );
            assertFalse(plan.packedColumns().isEmpty());
            // The very first packed column MUST be the Ring 0 center (0, 0), followed by Ring 0 (1, 1), never horizon (9, 9) first!
            assertEquals(0, plan.packedColumns().get(0).chunkX(), "Ring 0 center chunk (0,0) must stream first on 500 kbps links");
            assertEquals(0, plan.packedColumns().get(0).chunkZ());
            if (plan.packedColumns().size() >= 2) {
                assertEquals(1, plan.packedColumns().get(1).chunkX(), "Ring 0 immediate neighbor (1,1) must precede horizon chunks");
                assertEquals(1, plan.packedColumns().get(1).chunkZ());
            }

            // Verify multi-player Ring 0 vs Ring 1/2 budget allocation
            int[] budgets = LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(
                    350,
                    200L,
                    2L,
                    new int[]{12, 0},
                    new int[]{25, 20}
            );
            assertTrue(
                    budgets[0] > budgets[1] * 2,
                    "Player 0 waiting on Ring 0 immediate terrain must receive 3x the tick byte share of Player 1 on horizon chunks"
            );
            assertTrue(
                    budgets[0] + budgets[1] <= LcaPacketDifferentialProfiler.PRACTICAL_500K_BYTES_PER_TICK,
                    "Combined player chunk budgets must never exceed the 350 kbps (2,187 B/tick) usable ceiling"
            );
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Generic uplink extrapolation (500 kbps at 2/3..3/4 to 10 Mbps) and LeafShard mega-event O(M^2) -> O(M) -> O(R) broadcast scaling")
    void genericUplinkExtrapolationAndMegaEventLeafShardBroadcastScaling() {
        LcaInMemoryChunkCoordinator coordinator =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(8 * 1024 * 1024));
        try {
            UUID playerId = UUID.randomUUID();
            coordinator.registerPlayerHandshake(
                    playerId,
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            32_768,
                            REGISTRY_HASH_64
                    ),
                    REGISTRY_HASH_64
            );
            List<TrackedChunkColumn> cols = List.of(
                    attachCompactColumn(coordinator, 0, 0),
                    attachCompactColumn(coordinator, 1, 0),
                    attachCompactColumn(coordinator, 0, 1)
            );
            ByteBudgetPackedTickPlan plan = coordinator.packOptimizedOutboundBatchesForPlayer(
                    playerId,
                    OVERWORLD,
                    0,
                    0,
                    cols,
                    2,
                    1,
                    true
            );
            assertFalse(plan.packedColumns().isEmpty());
            DifferentialSnapshot snap = coordinator.packetDifferentialProfiler().snapshot(1);
            assertTrue(snap.toJson().contains("\"maxPlayers500kAt67Pct\":"));
            assertTrue(snap.estimateMaxPlayersForNominalKbps(1000.0, 0.70)
                    >= snap.estimateMaxPlayersForNominalKbps(500.0, 0.70));

            LcaMultiTargetConsolidator.MegaEventBroadcastProjection p200 =
                    LcaMultiTargetConsolidator.projectMegaEventBroadcastScaling(
                            200,
                            3,
                            200,
                            1_200,
                            240
                    );
            assertEquals(40_000L, p200.vanillaCpuEncodeOpsPerTick());
            assertEquals(200L, p200.consolidatedCpuEncodeOpsPerTick());
            assertTrue(p200.directHostBandwidthReductionPct() > 65.0,
                    "Expected > 65% direct host broadcast savings for 200 co-located players: "
                            + p200.directHostBandwidthReductionPct());
            assertTrue(p200.leafShardUpstreamBandwidthReductionPct() > 99.0,
                    "Expected > 99% LeafShard authoritative upstream savings across R=3 edge proxies: "
                            + p200.leafShardUpstreamBandwidthReductionPct());
            assertTrue(p200.leafShardAuthoritativeUpstreamMbps() < 1.0,
                    "Authoritative LeafShard upstream for 200 players across 3 proxies must stay < 1 Mbps: "
                            + p200.leafShardAuthoritativeUpstreamMbps());
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("REQ-NET-ODCT-001/002: Physical ODCT wire substitution shrinks framed LCAB bytes on recurring chunks and verifies SEC-DED-64 on client")
    void physicalOdctWireShrinkageAndClientSecDed64Hydration() throws IOException {
        LcaInMemoryChunkCoordinator serverTx =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(2 * 1024 * 1024), true);
        LcaClientWireReceiver clientRx = new LcaClientWireReceiver();
        try {
            UUID playerId = UUID.randomUUID();
            byte[] clientHsBytes = clientRx.buildClientHandshakeBytes(REGISTRY_HASH_64);
            NegotiatedSession serverSession = serverTx.registerPlayerHandshake(
                    playerId,
                    HandshakePayload.fromBytes(clientHsBytes),
                    REGISTRY_HASH_64
            );
            assertTrue(serverSession.isOrderlessDictAndMulticastEnabled());
            clientRx.onServerHandshakeAck(clientHsBytes, REGISTRY_HASH_64);

            // Create 4 distinct non-uniform terrain motifs (240 B each) shared across neighboring chunks
            byte[][] sharedMotifs = new byte[4][240];
            SplittableRandom rng = new SplittableRandom(9001L);
            for (int m = 0; m < 4; m++) {
                rng.nextBytes(sharedMotifs[m]);
            }

            TrackedChunkColumn chunk1 = attachColumnWithMotifs(serverTx, 0, 0, sharedMotifs, 100L);
            TrackedChunkColumn chunk2 = attachColumnWithMotifs(serverTx, 1, 0, sharedMotifs, 101L);
            TrackedChunkColumn chunk3 = attachColumnWithMotifs(serverTx, 2, 0, sharedMotifs, 102L);

            // Baseline without ODCT (legacy session without CAP_ORDERLESS_DICT_AND_MULTICAST)
            NegotiatedSession noOdctSession = new NegotiatedSession(
                    true,
                    LcaWireStreamProtocol.ALL_CAPABILITIES & ~LcaWireStreamProtocol.CAP_ORDERLESS_DICT_AND_MULTICAST,
                    32 * 1024,
                    REGISTRY_HASH_64
            );
            byte[] legacyRawChunk2 = LcaWireStreamProtocol.serializeRawChunkBatch(
                    new LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(
                            0,
                            0,
                            List.of(chunk2.toWireFullChunkEntry(101L))
                    ),
                    noOdctSession
            );

            // Pack Chunk 1: teaches the 4 motifs to playerId's KnownSlotBitSet and emits inline DictLearnEntry
            byte[] framedChunk1 = serverTx.packOutboundChunkBatchForPlayer(
                    playerId,
                    OVERWORLD,
                    0,
                    0,
                    List.of(chunk1),
                    false
            );
            assertNotNull(framedChunk1);

            // Pack Chunk 2 & Chunk 3: motifs are now known in playerId's KnownSlotBitSet -> 2-byte ODCT refs on the wire!
            byte[] framedChunk2 = serverTx.packOutboundChunkBatchForPlayer(
                    playerId,
                    OVERWORLD,
                    0,
                    0,
                    List.of(chunk2),
                    false
            );
            byte[] framedChunk3 = serverTx.packOutboundChunkBatchForPlayer(
                    playerId,
                    OVERWORLD,
                    0,
                    0,
                    List.of(chunk3),
                    false
            );
            assertNotNull(framedChunk2);
            assertNotNull(framedChunk3);

            assertTrue(
                    framedChunk2.length < framedChunk1.length / 2,
                    "Second chunk sharing motifs must physically shrink by > 50% on the wire: chunk1="
                            + framedChunk1.length + " B, chunk2=" + framedChunk2.length + " B"
            );
            assertTrue(
                    framedChunk2.length < legacyRawChunk2.length / 4,
                    "ODCT-substituted chunk2 (" + framedChunk2.length
                            + " B) must be < 25% of non-ODCT raw batch (" + legacyRawChunk2.length + " B)"
            );
            assertEquals(framedChunk2.length, framedChunk3.length);

            // Client RX: expand ODCT references into canonical SectionWireSlices and verify SEC-DED-64 syndromes!
            List<LcaWireStreamProtocol.HydratedClientChunkCommit> commits = new ArrayList<>();
            assertEquals(1, clientRx.onChunkBatchPayloadReceived(OVERWORLD, framedChunk1, false, REGISTRY_HASH_64, commits::add));
            assertEquals(1, clientRx.onChunkBatchPayloadReceived(OVERWORLD, framedChunk2, false, REGISTRY_HASH_64, commits::add));
            assertEquals(1, clientRx.onChunkBatchPayloadReceived(OVERWORLD, framedChunk3, false, REGISTRY_HASH_64, commits::add));
            assertEquals(3, commits.size());
            assertEquals(4, clientRx.clientOrderlessDictTable().populatedSlotCount());
            for (int m = 0; m < 4; m++) {
                assertArrayEquals(sharedMotifs[m], commits.get(1).sectionBackings()[4 + m].baseFrame());
                assertArrayEquals(sharedMotifs[m], commits.get(2).sectionBackings()[4 + m].baseFrame());
            }
        } finally {
            serverTx.shutdownCompactionPool();
            clientRx.coordinator().shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("REQ-NET-MTCF-001: 1,000-player multi-word targetBitmapWords and live MultiTargetTcpFanOutRelay 1-to-M socket fan-out")
    void multiWordMtcf1000RecipientsAndLiveMultiSocketTcpRelayFanOut() throws Exception {
        LcaInMemoryChunkCoordinator serverTx =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(2 * 1024 * 1024), true);
        try {
            // 1. Address 1,000 concurrent recipients (slots 0..999 across 16 long words)
            List<TargetRecipient> recipients1000 = new ArrayList<>(1000);
            for (int slot = 0; slot < 1000; slot++) {
                recipients1000.add(new TargetRecipient(slot, UUID.randomUUID(), new KnownSlotBitSet()));
            }
            UUID originSessionId = UUID.randomUUID();
            serverTx.registerPlayerHandshake(
                    originSessionId,
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            32 * 1024,
                            REGISTRY_HASH_64
                    ),
                    REGISTRY_HASH_64
            );
            byte[][] motifs = new byte[4][200];
            new SplittableRandom(7701L).nextBytes(motifs[0]);
            new SplittableRandom(7702L).nextBytes(motifs[1]);
            new SplittableRandom(7703L).nextBytes(motifs[2]);
            new SplittableRandom(7704L).nextBytes(motifs[3]);
            TrackedChunkColumn sharedCol = attachColumnWithMotifs(serverTx, 0, 0, motifs, 500L);
            byte[] framedChunkBatch = serverTx.packOutboundChunkBatchForPlayer(
                    originSessionId,
                    OVERWORLD,
                    0,
                    0,
                    List.of(sharedCol),
                    false
            );
            assertNotNull(framedChunkBatch);

            List<KinematicDeltaRow> kinematics = List.of(
                    KinematicDeltaRow.positionAndRotation(1, 0.25, 0.0, -0.5, 45.0f, 0.0f),
                    KinematicDeltaRow.positionOnly(2, -0.125, 0.0625, 0.25)
            );
            List<byte[]> sharedMetaSlices = List.of(
                    "minecraft:zombie{health:20.0}".getBytes(StandardCharsets.UTF_8)
            );
            ConsolidatedMultiTargetFrame mtcf1000 = LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                    recipients1000,
                    serverTx.orderlessDictTable(),
                    LcaOrderlessDictTable.KIND_ENTITY_METADATA_ARCHETYPE,
                    sharedMetaSlices,
                    kinematics,
                    framedChunkBatch,
                    20
            );
            assertEquals(16, mtcf1000.targetBitmapWords().length);
            assertTrue(mtcf1000.targetsPlayerSlot(0));
            assertTrue(mtcf1000.targetsPlayerSlot(63));
            assertTrue(mtcf1000.targetsPlayerSlot(64));
            assertTrue(mtcf1000.targetsPlayerSlot(512));
            assertTrue(mtcf1000.targetsPlayerSlot(999));
            assertFalse(mtcf1000.targetsPlayerSlot(1000));

            // DSN-1 immutability guard: mutating returned array must not mutate frame state
            long[] clonedWords = mtcf1000.targetBitmapWords();
            clonedWords[0] = 0L;
            assertTrue(mtcf1000.targetsPlayerSlot(0));

            // 2. Start live MultiTargetTcpFanOutRelay and connect 24 real TCP client sockets across all 16 words
            int[] sampledSlots = new int[]{
                0, 1, 31, 63, 64, 65, 127, 128, 199, 255, 256, 332,
                333, 400, 511, 512, 600, 665, 666, 750, 850, 950, 998, 999
            };
            try (LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay relay =
                         new LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay("127.0.0.1", 0)) {
                relay.start();
                int relayPort = relay.boundPort();
                List<Socket> clientSockets = new ArrayList<>(sampledSlots.length);
                List<LcaClientWireReceiver> clientReceivers = new ArrayList<>(sampledSlots.length);
                try {
                    for (int slot : sampledSlots) {
                        clientSockets.add(
                                LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay.connectRegisteredClientSocket(
                                        "127.0.0.1",
                                        relayPort,
                                        slot
                                )
                        );
                        clientReceivers.add(new LcaClientWireReceiver());
                    }
                    assertEquals(sampledSlots.length, relay.connectedClientCount());

                    try (Socket upstreamOrigin =
                                 LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay.connectUpstreamOriginSocket(
                                         "127.0.0.1",
                                         relayPort
                                 )) {
                        OutputStream upOut = upstreamOrigin.getOutputStream();
                        LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay.writeUpstreamMtcfFrame(
                                upOut,
                                mtcf1000.sharedWireFrameBytes()
                        );
                    }

                    for (int i = 0; i < sampledSlots.length; i++) {
                        int slot = sampledSlots[i];
                        DataInputStream clientIn = new DataInputStream(clientSockets.get(i).getInputStream());
                        byte[] receivedMtcf =
                                LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay.readNextDownstreamFrame(clientIn);
                        assertArrayEquals(mtcf1000.sharedWireFrameBytes(), receivedMtcf);

                        LcaClientWireReceiver rx = clientReceivers.get(i);
                        List<KinematicDeltaRow> rxKin = new ArrayList<>();
                        List<LcaWireStreamProtocol.HydratedClientChunkCommit> rxChunks = new ArrayList<>();
                        DecodedMultiTargetFrame decoded = rx.onMultiTargetTickFrameReceived(
                                OVERWORLD,
                                slot,
                                receivedMtcf,
                                false,
                                REGISTRY_HASH_64,
                                rxKin::addAll,
                                rxChunks::add
                        );
                        assertNotNull(decoded);
                        assertEquals(2, rxKin.size());
                        assertEquals(1, rxChunks.size());
                        assertArrayEquals(motifs[0], rxChunks.get(0).sectionBackings()[4].baseFrame());
                    }

                    assertEquals(1L, relay.upstreamFramesReceived());
                    assertEquals(mtcf1000.sharedWireFrameBytes().length, relay.upstreamBytesReceived());
                    assertEquals((long) sampledSlots.length, relay.downstreamFramesDelivered());
                    assertEquals(
                            (long) sampledSlots.length * mtcf1000.sharedWireFrameBytes().length,
                            relay.downstreamBytesDelivered()
                    );
                } finally {
                    for (Socket s : clientSockets) {
                        s.close();
                    }
                    for (LcaClientWireReceiver rx : clientReceivers) {
                        rx.coordinator().shutdownCompactionPool();
                    }
                }
            }
        } finally {
            serverTx.shutdownCompactionPool();
        }
    }

    private static TrackedChunkColumn attachColumnWithMotifs(
            LcaInMemoryChunkCoordinator coordinator,
            int chunkX,
            int chunkZ,
            byte[][] motifs,
            long tick
    ) {
        LcaSectionBacking[] sections = new LcaSectionBacking[24];
        for (int i = 0; i < 24; i++) {
            if (i >= 4 && i < 4 + motifs.length) {
                sections[i] = new LcaSectionBacking(motifs[i - 4].clone(), -1);
            } else {
                sections[i] = new LcaSectionBacking(new byte[0], 1);
            }
        }
        return coordinator.attachChunkColumn(OVERWORLD, chunkX, chunkZ, sections, null, tick, false);
    }

    private static TrackedChunkColumn attachCompactColumn(
            LcaInMemoryChunkCoordinator coordinator,
            int chunkX,
            int chunkZ
    ) {
        byte[] nonUniformSlice = new byte[140];
        new SplittableRandom(chunkX * 31L + chunkZ).nextBytes(nonUniformSlice);
        LcaSectionBacking[] sections = new LcaSectionBacking[24];
        for (int i = 0; i < 24; i++) {
            if (i >= 4 && i < 8) {
                sections[i] = new LcaSectionBacking(nonUniformSlice.clone(), -1);
            } else {
                sections[i] = new LcaSectionBacking(new byte[0], 1);
            }
        }
        return coordinator.attachChunkColumn(OVERWORLD, chunkX, chunkZ, sections, null, 100L, false);
    }
}
