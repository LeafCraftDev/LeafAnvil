package net.leaf.anvil.fabric;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.LcaSectionBacking.CompactionSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ChunkDigestEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientRevisitApplyResult;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryCleanAck;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;
import java.util.logging.Logger;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end real Minecraft `26.1.2` integration proof combining {@link LcaSectionBacking}
 * (`leafanvil-ADR-002`), {@link LcaWireStreamProtocol} (`leafanvil-ADR-003`), and
 * {@link LcaInMemoryChunkCoordinator} against real Mojang {@link LevelChunkSection},
 * {@link Block#BLOCK_STATE_REGISTRY}, {@link FriendlyByteBuf}, and {@link DataLayer} classes.
 *
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy rtp-fabric-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@DisplayName("leafanvil-ADR-002 + leafanvil-ADR-003: Real Minecraft 26.1.2 In-Memory LcaSectionBacking & Wire Streaming Proof")
public class FabricLcaInMemoryAndWireProofTest {

    private static final Logger LOGGER = Logger.getLogger(FabricLcaInMemoryAndWireProofTest.class.getName());
    private static final int OVERWORLD_SECTIONS = 24;
    private static final int SECTION_VOXELS = 4096;

    private static BlockState[] PALETTE_20_STATES;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        PALETTE_20_STATES = new BlockState[]{
                Blocks.STONE.defaultBlockState(),
                Blocks.GRANITE.defaultBlockState(),
                Blocks.DIORITE.defaultBlockState(),
                Blocks.ANDESITE.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.TUFF.defaultBlockState(),
                Blocks.CALCITE.defaultBlockState(),
                Blocks.DRIPSTONE_BLOCK.defaultBlockState(),
                Blocks.COAL_ORE.defaultBlockState(),
                Blocks.IRON_ORE.defaultBlockState(),
                Blocks.COPPER_ORE.defaultBlockState(),
                Blocks.GOLD_ORE.defaultBlockState(),
                Blocks.REDSTONE_ORE.defaultBlockState(),
                Blocks.LAPIS_ORE.defaultBlockState(),
                Blocks.DIAMOND_ORE.defaultBlockState(),
                Blocks.EMERALD_ORE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.GRAVEL.defaultBlockState(),
                Blocks.SAND.defaultBlockState()
        };
    }

    @Test
    @DisplayName("Proof 1: Real LevelChunkSection & Block.BLOCK_STATE_REGISTRY 17-State Step-Jump vs LcaSectionBacking (leafanvil-ADR-002)")
    void proof1RealLevelChunkSection17StateStepJumpVsLcaSectionBacking() throws Exception {
        long parityHash = FabricLeafAnvilMod.computeBlockStateRegistryParityHash();
        assertTrue(Block.BLOCK_STATE_REGISTRY.size() > 20_000,
                "Real Minecraft 26.1.2 Block.BLOCK_STATE_REGISTRY must be populated");

        HandshakePayload clientHandshake = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                32 * 1024,
                parityHash
        );
        NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                clientHandshake,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                parityHash
        );
        assertTrue(session.isLcaStreamActive());
        assertTrue(session.isClientHeightmapsEnabled());

        LevelChunkSection section16States = createRealLevelChunkSection();
        LevelChunkSection section17States = createRealLevelChunkSection();
        for (int i = 1; i < SECTION_VOXELS; i++) {
            int x = i & 15;
            int z = (i >>> 4) & 15;
            int y = (i >>> 8) & 15;
            BlockState s16 = (i <= 15) ? PALETTE_20_STATES[i - 1] : PALETTE_20_STATES[0];
            BlockState s17 = (i <= 16) ? PALETTE_20_STATES[i - 1] : PALETTE_20_STATES[0];
            section16States.setBlockState(x, y, z, s16, false);
            section17States.setBlockState(x, y, z, s17, false);
        }

        int mojang16StateBytes = measureMojangSectionWriteBytes(section16States);
        int mojang17StateBytes = measureMojangSectionWriteBytes(section17States);
        int stepJumpDeltaBytes = mojang17StateBytes - mojang16StateBytes;
        assertTrue(stepJumpDeltaBytes >= 680,
                "17th state must trigger vanilla SimpleBitStorage 4-bit (long[256]=2048 B) -> 5-bit (long[342]=2736 B) step jump, got delta="
                        + stepJumpDeltaBytes);

        RealOverworldColumn column = buildReal24SectionOverworldColumn(0, 0, new SplittableRandom(26_1_2_001L));

        assertEquals(LcaSectionBacking.STAGE_LINEAR_16, column.serverBackings[6].overlayStage());
        assertEquals(14, column.serverBackings[6].dirtyCount());
        assertEquals(LcaSectionBacking.STAGE_HASH_64, column.serverBackings[7].overlayStage());
        assertEquals(36, column.serverBackings[7].dirtyCount());
        assertTrue(column.serverBackings[7].maxProbeLengthInOverlay() <= 16);

        long t0Mojang = System.nanoTime();
        byte[] mojangRawBytes = serializeRealMojang24SectionColumn(column);
        byte[] mojangZlibBytes = compressZlib(mojangRawBytes, 6);
        long mojangSerializeNanos = System.nanoTime() - t0Mojang;

        long t0Lca = System.nanoTime();
        EntryFullChunk lcaFullEntry = column.toWireFullChunkEntry(100L);
        ClientboundLcaChunkBatchPayload singleBatch = new ClientboundLcaChunkBatchPayload(
                0, 0, List.of(lcaFullEntry)
        );
        byte[] lcaRawWireBytes = LcaWireStreamProtocol.serializeRawChunkBatch(singleBatch, session);
        byte[] lcaLcd2WireBytes = LcaWireStreamProtocol.encodeChunkBatchPayload(singleBatch, session);
        long lcaSerializeNanos = System.nanoTime() - t0Lca;

        double wireReductionPct = (1.0 - ((double) lcaLcd2WireBytes.length / (double) mojangZlibBytes.length)) * 100.0;

        LOGGER.info(String.format(
                "[MC 26.1.2 Proof 1] registrySize=%d, parityHash=0x%016XL | 16-state section=%d B -> 17-state section=%d B (+%d B SimpleBitStorage jump) | "
                        + "24-Section Column: mojangRaw=%d B, mojangZlib=%d B (%d us) vs lcaRaw=%d B, lcaLcd2=%d B (%d us) | reduction=%.2f%%",
                Block.BLOCK_STATE_REGISTRY.size(),
                parityHash,
                mojang16StateBytes,
                mojang17StateBytes,
                stepJumpDeltaBytes,
                mojangRawBytes.length,
                mojangZlibBytes.length,
                mojangSerializeNanos / 1_000L,
                lcaRawWireBytes.length,
                lcaLcd2WireBytes.length,
                lcaSerializeNanos / 1_000L,
                wireReductionPct
        ));

        assertTrue(wireReductionPct >= 65.0,
                "Expected >= 65% wire reduction vs real Mojang 26.1.2 Zlib chunk column, got " + wireReductionPct + "%");
    }

    @Test
    @DisplayName("Proof 2: End-to-End Zero-Transcode Disk .lca -> Server RAM LcaSectionBacking -> Wire Batch LCD2 0x4C44 (Netty VarInt=0 Bypass) -> Client RAM LcaSectionBacking (786,432 voxels)")
    void proof2EndToEndZeroTranscode8ChunkBatchVoxelForVoxelParity() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_002L);
        long parityHash = FabricLeafAnvilMod.computeBlockStateRegistryParityHash();
        NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        65_536,
                        parityHash
                ),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                parityHash
        );

        int batchChunkCount = 8;
        List<RealOverworldColumn> serverChunks = new ArrayList<>(batchChunkCount);
        List<BatchChunkEntry> batchEntries = new ArrayList<>(batchChunkCount);

        int totalMojangRawBytes = 0;
        int totalMojangZlibBytes = 0;

        for (int c = 0; c < batchChunkCount; c++) {
            RealOverworldColumn col = buildReal24SectionOverworldColumn(c, 0, rng);

            if (c == 0) {
                LcaSectionBacking sec8 = col.serverBackings[8];
                int baseStoneId = Block.getId(Blocks.STONE.defaultBlockState());
                int diamondOreId = Block.getId(Blocks.DIAMOND_ORE.defaultBlockState());
                int emeraldOreId = Block.getId(Blocks.EMERALD_ORE.defaultBlockState());

                sec8.setStateId(0, 0, 0, diamondOreId, 200L);
                sec8.setStateId(1, 1, 1, emeraldOreId, 201L);
                col.expectedStateIds[8][LcaSectionBacking.pos12(0, 0, 0)] = diamondOreId;
                col.expectedStateIds[8][LcaSectionBacking.pos12(1, 1, 1)] = emeraldOreId;

                CompactionSnapshot snap = sec8.beginCompactionSnapshot();
                sec8.setStateId(0, 0, 0, baseStoneId, 202L);
                col.expectedStateIds[8][LcaSectionBacking.pos12(0, 0, 0)] = baseStoneId;

                int[] compactedVoxels = decodeAllVoxelsFromCompactFrame(snap.baseFrame(), snap.uniformStateId());
                snap.frozenOverlay().forEachEntry((pos12, stateId19) -> compactedVoxels[pos12] = stateId19);
                byte[] newBaseFrame = encodeCompactSectionFrame(compactedVoxels, 15, 0);
                assertTrue(sec8.completeCompaction(snap.epoch(), newBaseFrame, snap.uniformStateId()));

                assertEquals(baseStoneId, sec8.getStateId(0, 0, 0, createCompactFrameDecoder(newBaseFrame, snap.uniformStateId())));
            }

            serverChunks.add(col);
            byte[] mojangRaw = serializeRealMojang24SectionColumn(col);
            byte[] mojangZlib = compressZlib(mojangRaw, 6);
            totalMojangRawBytes += mojangRaw.length;
            totalMojangZlibBytes += mojangZlib.length;

            batchEntries.add(col.toWireFullChunkEntry(300L + c));
        }

        ClientboundLcaChunkBatchPayload batchPayload = new ClientboundLcaChunkBatchPayload(
                0,
                0,
                batchEntries
        );
        byte[] uncompressedBatchBytes = LcaWireStreamProtocol.serializeRawChunkBatch(batchPayload, session);
        byte[] lcd2BatchBytes = LcaWireStreamProtocol.encodeChunkBatchPayload(batchPayload, session);
        byte[] nettyVarIntZeroBypassFrame = LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(lcd2BatchBytes);

        byte[] clientReceivedLcd2 = LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(nettyVarIntZeroBypassFrame);
        ClientboundLcaChunkBatchPayload decodedBatch =
                LcaWireStreamProtocol.decodeChunkBatchPayload(clientReceivedLcd2, session);
        assertEquals(batchChunkCount, decodedBatch.entries().size());

        int totalVerifiedVoxels = 0;
        for (int c = 0; c < batchChunkCount; c++) {
            RealOverworldColumn serverCol = serverChunks.get(c);
            EntryFullChunk fullChunk = assertInstanceOf(EntryFullChunk.class, decodedBatch.entries().get(c));
            assertEquals(OVERWORLD_SECTIONS, fullChunk.sections().size());
            assertEquals(0, fullChunk.heightmapDeltaBytes().length, "CAP_CLIENT_HEIGHTMAPS must omit heightmap bytes on wire");

            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                SectionWireSlice wireSlice = fullChunk.sections().get(s);
                LcaSectionBacking clientBacking = reconstructClientSectionBacking(wireSlice);

                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int pos12 = LcaSectionBacking.pos12(x, y, z);
                            int expectedId = serverCol.expectedStateIds[s][pos12];
                            int actualClientId = clientBacking.getStateId(x, y, z);
                            assertEquals(expectedId, actualClientId,
                                    "Voxel mismatch at chunk=" + c + " section=" + s + " (" + x + "," + y + "," + z + ")");
                            assertEquals(
                                    Block.stateById(expectedId),
                                    Block.stateById(actualClientId),
                                    "BlockState mismatch at chunk=" + c + " section=" + s
                            );
                            totalVerifiedVoxels++;
                        }
                    }
                }
            }
        }

        assertEquals(batchChunkCount * OVERWORLD_SECTIONS * SECTION_VOXELS, totalVerifiedVoxels);
        double batchReductionPct = (1.0 - ((double) lcd2BatchBytes.length / (double) totalMojangZlibBytes)) * 100.0;

        LOGGER.info(String.format(
                "[MC 26.1.2 Proof 2] 8-Chunk Batch (786,432 voxels verified): mojangRaw=%d B, mojangPerChunkZlib=%d B | "
                        + "lcaBatchRaw=%d B, lcaBatchLcd2=%d B, nettyVarInt0Frame=%d B | wireReduction=%.2f%%",
                totalMojangRawBytes,
                totalMojangZlibBytes,
                uncompressedBatchBytes.length,
                lcd2BatchBytes.length,
                nettyVarIntZeroBypassFrame.length,
                batchReductionPct
        ));

        assertTrue(batchReductionPct >= 65.0,
                "Expected >= 65% batch wire reduction vs real Mojang 26.1.2 per-chunk Zlib, got " + batchReductionPct + "%");
    }

    @Test
    @DisplayName("Proof 3: Real-Chunk Revisit Delta Sync (2 B ENTRY_CLEAN_ACK & 12-Edit 1-Section ENTRY_PATCH_DELTA)")
    void proof3RealChunkRevisitCleanAckAnd1SectionPatchDelta() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_003L);
        RealOverworldColumn column = buildReal24SectionOverworldColumn(3, 7, rng);

        byte[] mojangRaw = serializeRealMojang24SectionColumn(column);
        byte[] mojangZlib = compressZlib(mojangRaw, 6);

        long initialTick = 500L;
        EntryFullChunk initialFullEntry = column.toWireFullChunkEntry(initialTick);

        ClientChunkSlabCache clientCache = new ClientChunkSlabCache(32 * 1024 * 1024L);
        clientCache.put("minecraft:overworld", initialFullEntry);

        ServerboundLcaChunkDigestPayload clientDigest =
                clientCache.buildRegionDigest("minecraft:overworld", 0, 0);
        ChunkDigestEntry digestEntry = clientDigest.findChunk(initialFullEntry.localChunkIndex10());
        assertNotNull(digestEntry);

        BatchChunkEntry cleanRevisitEntry = LcaWireStreamProtocol.resolveServerRevisitEntry(
                initialFullEntry,
                digestEntry,
                initialFullEntry.secDedSyndrome64(),
                0
        );
        EntryCleanAck cleanAck = assertInstanceOf(EntryCleanAck.class, cleanRevisitEntry);
        byte[] cleanAckWireBytes = cleanAck.toBytes();
        assertEquals(2, cleanAckWireBytes.length, "ENTRY_CLEAN_ACK must be exactly 2 bytes");

        long updatedTick = 620L;
        LcaSectionBacking mutatedSec6 = column.serverBackings[6];
        int lanternStateId = Block.getId(Blocks.LANTERN.defaultBlockState());
        for (int edit = 0; edit < 12; edit++) {
            int x = (edit * 3) & 15;
            int y = (edit + 2) & 15;
            int z = (edit * 5) & 15;
            mutatedSec6.setStateId(x, y, z, lanternStateId, updatedTick);
            column.expectedStateIds[6][LcaSectionBacking.pos12(x, y, z)] = lanternStateId;
        }

        EntryFullChunk updatedFullEntry = column.toWireFullChunkEntry(updatedTick);
        int dirtyMask = 1 << 6;

        BatchChunkEntry patchDeltaEntry = LcaWireStreamProtocol.resolveServerRevisitEntry(
                updatedFullEntry,
                digestEntry,
                initialFullEntry.secDedSyndrome64(),
                dirtyMask
        );
        EntryPatchDelta patchDelta = assertInstanceOf(EntryPatchDelta.class, patchDeltaEntry);
        byte[] patchDeltaWireBytes = patchDelta.toBytes();

        ClientRevisitApplyResult applyResult = LcaWireStreamProtocol.applyClientRevisitEntry(
                clientCache,
                "minecraft:overworld",
                0,
                0,
                patchDelta,
                updatedFullEntry
        );
        assertFalse(applyResult.secDedCorruptedAndEvicted());
        assertFalse(applyResult.requestedFullChunkFallback());
        assertNotNull(applyResult.resolvedChunk());

        LcaSectionBacking clientSec6 = reconstructClientSectionBacking(applyResult.resolvedChunk().sections().get(6));
        for (int edit = 0; edit < 12; edit++) {
            int x = (edit * 3) & 15;
            int y = (edit + 2) & 15;
            int z = (edit * 5) & 15;
            assertEquals(
                    Blocks.LANTERN.defaultBlockState(),
                    Block.stateById(clientSec6.getStateId(x, y, z)),
                    "Patched client section 6 must reflect player LANTERN placement #" + edit
            );
        }

        double cleanAckReductionPct = (1.0 - (2.0 / (double) mojangZlib.length)) * 100.0;
        double patchDeltaReductionPct = (1.0 - ((double) patchDeltaWireBytes.length / (double) mojangZlib.length)) * 100.0;

        LOGGER.info(String.format(
                "[MC 26.1.2 Proof 3] Revisit vs Real Mojang Zlib (%d B): cleanAck=%d B (%.2f%% reduction), "
                        + "1Section12EditsPatchDelta=%d B (%.2f%% reduction)",
                mojangZlib.length,
                cleanAckWireBytes.length,
                cleanAckReductionPct,
                patchDeltaWireBytes.length,
                patchDeltaReductionPct
        ));

        assertTrue(cleanAckReductionPct > 99.9);
        assertTrue(patchDeltaReductionPct > 95.0,
                "Expected > 95% reduction for 1-section revisit patch delta, got " + patchDeltaReductionPct + "%");
    }

    @Test
    @DisplayName("Proof 4: Runtime Invariants — Coordinate Masking, Non-Pinning Sodium Copy-on-Read, Proxy-Safe Stored-Zlib, and Strict Netty Client Commit Order")
    void proof4RuntimeInvariantsMaskingSodiumEphemeralCopyProxyStoredZlibAndStrictNettyOrder() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_004L);
        long parityHash = FabricLeafAnvilMod.computeBlockStateRegistryParityHash();
        ClientChunkSlabCache clientCache = new ClientChunkSlabCache(32 * 1024 * 1024L);
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(clientCache);

        UUID playerId = UUID.randomUUID();
        NegotiatedSession session = coordinator.registerPlayerHandshake(
                playerId,
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        32_768,
                        parityHash
                ),
                parityHash
        );
        assertTrue(session.isLcaStreamActive());
        assertTrue(session.isProxySafeStoredZlibEnabled());

        RealOverworldColumn col = buildReal24SectionOverworldColumn(2, 4, rng);
        LcaInMemoryChunkCoordinator.TrackedChunkColumn trackedServerCol = coordinator.attachChunkColumn(
                "minecraft:overworld",
                2,
                4,
                col.serverBackings,
                col.mojangSections,
                1000L
        );

        // Invariant 1: Coordinate masking (x & 15), (y & 15), (z & 15) at hook entry + post-overlay release nonEmptyBlockCount
        int diamondBlockId = Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
        trackedServerCol.mutateVoxel(6, 259, -45, 87, diamondBlockId, 1005L);
        assertEquals(
                diamondBlockId,
                trackedServerCol.section(6).getStateId(259 & 15, (-45) & 15, 87 & 15),
                "Unmasked world coordinates must be masked & 15 at entry"
        );
        assertFalse(trackedServerCol.section(6).hasOnlyAir());

        // Invariant 2: Non-pinning Copy-on-Read for client meshing (Sodium WorldSlice / SectionCompiler)
        LcaSectionBacking sec6 = trackedServerCol.section(6);
        byte[] coldBaseFrameRefBefore = sec6.baseFrame();
        int coldBaseFrameLenBefore = coldBaseFrameRefBefore.length;
        int[] ephemeralMeshSnapshot = sec6.materializeEphemeralStateIds(null, null);
        assertEquals(SECTION_VOXELS, ephemeralMeshSnapshot.length);
        assertEquals(
                diamondBlockId,
                ephemeralMeshSnapshot[LcaSectionBacking.pos12(259 & 15, (-45) & 15, 87 & 15)]
        );
        // Materialize into an ephemeral Mojang PalettedContainer<BlockState> for background mesher thread
        PalettedContainer<BlockState> transientMesherContainer = createBlockStatePalettedContainer();
        for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
            int stateId = ephemeralMeshSnapshot[pos12];
            if (stateId != 0) {
                transientMesherContainer.set(
                        pos12 & 15,
                        (pos12 >>> 8) & 15,
                        (pos12 >>> 4) & 15,
                        Block.stateById(stateId)
                );
            }
        }
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                transientMesherContainer.get(259 & 15, (-45) & 15, 87 & 15)
        );
        // Verify the owning section's baseFrame was NOT inflated or replaced!
        assertSame(coldBaseFrameRefBefore, sec6.baseFrame());
        assertEquals(coldBaseFrameLenBefore, sec6.baseFrame().length);
        assertTrue(sec6.ephemeralCopyCount() >= 1);

        // Invariant 3 & Auditor Gate 2B: Proxy-safe Deflater.NO_COMPRESSION stored-block Zlib frame + Strict Netty arrival-order client commit
        byte[] proxySafeBatchFrame = coordinator.packOutboundChunkBatchForPlayer(
                playerId,
                "minecraft:overworld",
                0,
                0,
                List.of(trackedServerCol),
                true
        );
        assertNotNull(proxySafeBatchFrame);

        AtomicReference<HydratedClientChunkCommit> committedClientChunk = new AtomicReference<>();
        long batchSeq = coordinator.receiveClientChunkBatchOrdered(
                "minecraft:overworld",
                proxySafeBatchFrame,
                true,
                session,
                 FabricLcaInMemoryAndWireProofTest::reconstructClientSectionBacking,
                committedClientChunk::set
        );
        assertEquals(0L, batchSeq);

        // Enqueue a single-block update (ClientboundBlockUpdatePacket) and multi-block update (ClientboundSectionBlocksUpdatePacket)
        // on Netty BEFORE draining the main-thread commit queue
        int beaconId = Block.getId(Blocks.BEACON.defaultBlockState());
        int obsidianId = Block.getId(Blocks.OBSIDIAN.defaultBlockState());
        coordinator.clientCommitQueue().enqueueOrderedBlockMutation(() -> {
            HydratedClientChunkCommit committed = committedClientChunk.get();
            assertNotNull(committed, "Hydrated chunk batch (seq 0) must commit before BlockUpdate (seq 1)");
            committed.sectionBackings()[6].setStateId(1, 2, 3, beaconId, 2001L);
        });
        coordinator.clientCommitQueue().enqueueOrderedBlockMutation(() -> {
            HydratedClientChunkCommit committed = committedClientChunk.get();
            assertNotNull(committed, "Hydrated chunk batch (seq 0) must commit before SectionBlocksUpdate (seq 2)");
            committed.sectionBackings()[6].applySectionBlockUpdates(
                    new int[]{LcaSectionBacking.pos12(4, 5, 6), LcaSectionBacking.pos12(7, 8, 9)},
                    new int[]{obsidianId, obsidianId},
                    2002L,
                    null
            );
        });

        int drained = coordinator.clientCommitQueue().drainReadyInStrictNettyOrder();
        assertEquals(3, drained);
        assertNotNull(committedClientChunk.get());
        LcaSectionBacking clientSec6 = committedClientChunk.get().sectionBackings()[6];
        assertEquals(Blocks.BEACON.defaultBlockState(), Block.stateById(clientSec6.getStateId(1, 2, 3)));
        assertEquals(Blocks.OBSIDIAN.defaultBlockState(), Block.stateById(clientSec6.getStateId(4, 5, 6)));
        assertEquals(Blocks.OBSIDIAN.defaultBlockState(), Block.stateById(clientSec6.getStateId(7, 8, 9)));
        assertEquals(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                Block.stateById(clientSec6.getStateId(259 & 15, (-45) & 15, 87 & 15))
        );
    }

    // =========================================================================
    // Real Mojang 26.1.2 LevelChunkSection & LcaSectionBacking Builders
    // =========================================================================

    private static final class RealOverworldColumn {
        final int chunkX;
        final int chunkZ;
        final LevelChunkSection[] mojangSections = new LevelChunkSection[OVERWORLD_SECTIONS];
        final DataLayer[] skyLightLayers = new DataLayer[OVERWORLD_SECTIONS];
        final DataLayer[] blockLightLayers = new DataLayer[OVERWORLD_SECTIONS];
        final LcaSectionBacking[] serverBackings = new LcaSectionBacking[OVERWORLD_SECTIONS];
        final int[][] expectedStateIds = new int[OVERWORLD_SECTIONS][SECTION_VOXELS];
        final boolean[] isUniformSection = new boolean[OVERWORLD_SECTIONS];
        final int[] uniformSkyLight2 = new int[OVERWORLD_SECTIONS];

        RealOverworldColumn(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        SectionWireSlice exportSectionWireSlice(int s) {
            LcaSectionBacking backing = serverBackings[s];
            int[] dirtyEntries = backing.exportMergedDirtyOverlayEntries();
            if (isUniformSection[s] && dirtyEntries.length == 0) {
                return SectionWireSlice.uniform(s, backing.uniformStateId(), uniformSkyLight2[s]);
            }
            return SectionWireSlice.nonUniform(s, backing.baseFrame(), dirtyEntries);
        }

        EntryFullChunk toWireFullChunkEntry(long lastUpdateTick) {
            List<SectionWireSlice> slices = new ArrayList<>(OVERWORLD_SECTIONS);
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                slices.add(exportSectionWireSlice(s));
            }
            byte[] heightmaps = new byte[592];
            return EntryFullChunk.createWithComputedSyndrome(
                    chunkX,
                    chunkZ,
                    lastUpdateTick,
                    slices,
                    heightmaps
            );
        }
    }

    private static RealOverworldColumn buildReal24SectionOverworldColumn(
            int chunkX,
            int chunkZ,
            SplittableRandom rng
    ) throws Exception {
        RealOverworldColumn col = new RealOverworldColumn(chunkX, chunkZ);

        for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
            LevelChunkSection mojangSection = createRealLevelChunkSection();
            col.mojangSections[s] = mojangSection;

            if (s < 4 || s >= 12) {
                col.isUniformSection[s] = true;
                BlockState uniformState = (s < 2)
                        ? Blocks.BEDROCK.defaultBlockState()
                        : (s < 4 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                int uniformId = Block.getId(uniformState);
                Arrays.fill(col.expectedStateIds[s], uniformId);

                if (uniformId != 0) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                mojangSection.setBlockState(x, y, z, uniformState, false);
                            }
                        }
                    }
                }
                col.uniformSkyLight2[s] = (s >= 12) ? 3 : 0;
                col.skyLightLayers[s] = createRealDataLayer((s >= 12) ? 15 : 0, rng, false);
                col.blockLightLayers[s] = createRealDataLayer(0, rng, false);
                col.serverBackings[s] = new LcaSectionBacking(new byte[0], uniformId);
            } else {
                col.isUniformSection[s] = false;
                int distinctStatesInSection = (s >= 6) ? 19 : 14;
                int majorityStateId = Block.getId(PALETTE_20_STATES[0]);
                int[] baseVoxels = new int[SECTION_VOXELS];

                for (int idx = 0; idx < SECTION_VOXELS; idx++) {
                    int x = idx & 15;
                    int z = (idx >>> 4) & 15;
                    int y = (idx >>> 8) & 15;

                    BlockState chosen;
                    if (idx < distinctStatesInSection) {
                        chosen = PALETTE_20_STATES[idx];
                    } else if (rng.nextInt(100) < 92) {
                        chosen = PALETTE_20_STATES[0];
                    } else {
                        chosen = PALETTE_20_STATES[1 + rng.nextInt(distinctStatesInSection - 1)];
                    }
                    int stateId = Block.getId(chosen);
                    baseVoxels[idx] = stateId;
                    col.expectedStateIds[s][idx] = stateId;
                    mojangSection.setBlockState(x, y, z, chosen, false);
                }

                byte[] compactBaseFrame = encodeCompactSectionFrame(baseVoxels, (s >= 9) ? 15 : 0, 0);
                IntUnaryOperator decoder = createCompactFrameDecoder(compactBaseFrame, majorityStateId);
                LcaSectionBacking backing = new LcaSectionBacking(compactBaseFrame, majorityStateId, decoder);

                int dirtyEdits = (s == 6) ? 14 : (s == 7 ? 36 : 6);
                BlockState editState = Blocks.COBBLESTONE.defaultBlockState();
                int editStateId = Block.getId(editState);
                for (int e = 0; e < dirtyEdits; e++) {
                    int x = (e * 3 + s) & 15;
                    int y = (e >>> 2) & 15;
                    int z = (e * 7 + 1) & 15;
                    backing.setStateId(x, y, z, editStateId, 50L + e);
                    col.expectedStateIds[s][LcaSectionBacking.pos12(x, y, z)] = editStateId;
                    mojangSection.setBlockState(x, y, z, editState, false);
                }

                col.skyLightLayers[s] = createRealDataLayer((s >= 8) ? 15 : 0, rng, true);
                col.blockLightLayers[s] = createRealDataLayer(0, rng, true);
                col.serverBackings[s] = backing;
            }
        }
        return col;
    }

    private static byte[] encodeCompactSectionFrame(int[] voxels4096, int skyConst4, int blkConst4) {
        try {
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int stateId : voxels4096) {
                counts.merge(stateId, 1, Integer::sum);
            }
            int majorityStateId = 0;
            int maxCount = -1;
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    majorityStateId = entry.getKey();
                }
            }

            List<Integer> minorityStates = new ArrayList<>();
            for (int stateId : counts.keySet()) {
                if (stateId != majorityStateId) {
                    minorityStates.add(stateId);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            DataOutputStream out = new DataOutputStream(baos);
            writeVarIntLocal(out, majorityStateId);
            out.writeByte(((blkConst4 & 0xF) << 4) | (skyConst4 & 0xF));
            writeVarIntLocal(out, minorityStates.size());

            for (int stateId : minorityStates) {
                writeVarIntLocal(out, stateId);
                int count = counts.get(stateId);
                writeVarIntLocal(out, count);
                int prevPos = 0;
                for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                    if (voxels4096[pos] == stateId) {
                        writeVarIntLocal(out, pos - prevPos);
                        prevPos = pos;
                    }
                }
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] decodeAllVoxelsFromCompactFrame(byte[] baseFrame, int fallbackUniformId) {
        int[] voxels = new int[SECTION_VOXELS];
        if (baseFrame == null || baseFrame.length == 0) {
            Arrays.fill(voxels, fallbackUniformId);
            return voxels;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(baseFrame))) {
            int majorityStateId = readVarIntLocal(in);
            Arrays.fill(voxels, majorityStateId);
            in.readUnsignedByte();
            int minorityCount = readVarIntLocal(in);
            for (int m = 0; m < minorityCount; m++) {
                int stateId = readVarIntLocal(in);
                int occurrences = readVarIntLocal(in);
                int pos = 0;
                for (int k = 0; k < occurrences; k++) {
                    pos += readVarIntLocal(in);
                    voxels[pos] = stateId;
                }
            }
            return voxels;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeVarIntLocal(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    private static int readVarIntLocal(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    private static IntUnaryOperator createCompactFrameDecoder(byte[] baseFrame, int uniformStateId) {
        if (baseFrame == null || baseFrame.length == 0) {
            return pos12 -> uniformStateId;
        }
        int[] decoded = decodeAllVoxelsFromCompactFrame(baseFrame, uniformStateId);
        return pos12 -> decoded[pos12];
    }

    private static LcaSectionBacking reconstructClientSectionBacking(SectionWireSlice wireSlice) {
        if (wireSlice.isUniform()) {
            int majorityStateId = wireSlice.directoryWord16() & 0x1FFF;
            return new LcaSectionBacking(new byte[0], majorityStateId);
        }
        byte[] baseFrame = wireSlice.baseFrameSlice();
        int[] decodedBase = decodeAllVoxelsFromCompactFrame(baseFrame, 0);
        int majorityStateId = decodedBase[0];
        LcaSectionBacking clientBacking = new LcaSectionBacking(
                baseFrame,
                majorityStateId,
                pos12 -> decodedBase[pos12]
        );
        int[] overlayEntries = wireSlice.dirtyOverlayEntries();
        for (int i = 0; i < overlayEntries.length; i++) {
            int entry32 = overlayEntries[i];
            int pos12 = LcaSectionBacking.unpackPos12(entry32);
            int stateId19 = LcaSectionBacking.unpackStateId19(entry32);
            int x = pos12 & 15;
            int z = (pos12 >>> 4) & 15;
            int y = (pos12 >>> 8) & 15;
            clientBacking.setStateId(x, y, z, stateId19, 1000L + i);
        }
        return clientBacking;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static LevelChunkSection createRealLevelChunkSection() throws Exception {
        for (Constructor<?> ctor : LevelChunkSection.class.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2
                    && PalettedContainer.class.isAssignableFrom(params[0])
                    && PalettedContainerRO.class.isAssignableFrom(params[1])) {
                PalettedContainer<BlockState> states = createBlockStatePalettedContainer();
                PalettedContainerRO<Holder<Biome>> biomes = createDummyBiomeContainer(states);
                return (LevelChunkSection) ctor.newInstance(states, biomes);
            }
        }
        throw new IllegalStateException("Could not find (PalettedContainer, PalettedContainerRO) constructor on LevelChunkSection");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PalettedContainer<BlockState> createBlockStatePalettedContainer() throws Exception {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (Constructor<?> ctor : PalettedContainer.class.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0] == Object.class) {
                for (Method m : params[1].getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && m.getName().equals("createForBlockStates")
                            && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        Object strategyObj = m.invoke(null, Block.BLOCK_STATE_REGISTRY);
                        return (PalettedContainer<BlockState>) ctor.newInstance(air, strategyObj);
                    }
                }
            }
        }
        throw new IllegalStateException("Unable to instantiate real Mojang PalettedContainer<BlockState>");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PalettedContainerRO<Holder<Biome>> createDummyBiomeContainer(
            PalettedContainer<BlockState> template
    ) throws Exception {
        return (PalettedContainerRO) createBlockStatePalettedContainer();
    }

    private static int measureMojangSectionWriteBytes(LevelChunkSection section) throws Exception {
        ByteBuf rawBuf = Unpooled.buffer(4096);
        try {
            FriendlyByteBuf friendly = new FriendlyByteBuf(rawBuf);
            invokeSectionWrite(section, friendly);
            return rawBuf.readableBytes();
        } finally {
            rawBuf.release();
        }
    }

    private static void invokeSectionWrite(LevelChunkSection section, FriendlyByteBuf buf) throws Exception {
        for (Method m : LevelChunkSection.class.getDeclaredMethods()) {
            if (m.getName().equals("write")
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == FriendlyByteBuf.class) {
                m.setAccessible(true);
                m.invoke(section, buf);
                return;
            }
        }
        section.write(buf);
    }

    private static DataLayer createRealDataLayer(
            int baseNibble,
            SplittableRandom rng,
            boolean withPointLightDecay
    ) {
        if (!withPointLightDecay) {
            return new DataLayer(baseNibble);
        }
        byte[] bytes = new byte[2048];
        for (int i = 0; i < bytes.length; i++) {
            int n0 = (baseNibble > 0) ? Math.max(0, baseNibble - rng.nextInt(16)) : rng.nextInt(16);
            int n1 = (baseNibble > 0) ? Math.max(0, baseNibble - rng.nextInt(16)) : rng.nextInt(16);
            bytes[i] = (byte) (((n1 & 0xF) << 4) | (n0 & 0xF));
        }
        return new DataLayer(bytes);
    }

    private static byte[] serializeRealMojang24SectionColumn(RealOverworldColumn col) throws Exception {
        ByteBuf nettyBuf = Unpooled.buffer(131072);
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(nettyBuf);
            buf.writeInt(col.chunkX);
            buf.writeInt(col.chunkZ);
            for (int w = 0; w < 74; w++) {
                buf.writeLong(0x0102030405060708L ^ w);
            }
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                invokeSectionWrite(col.mojangSections[s], buf);
            }
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                if (!col.isUniformSection[s] || s >= 12) {
                    buf.writeByteArray(col.skyLightLayers[s].getData());
                }
                if (!col.isUniformSection[s]) {
                    buf.writeByteArray(col.blockLightLayers[s].getData());
                }
            }
            byte[] out = new byte[nettyBuf.readableBytes()];
            nettyBuf.readBytes(out);
            return out;
        } finally {
            nettyBuf.release();
        }
    }

    private static byte[] compressZlib(byte[] raw, int level) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length);
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                baos.write(buf, 0, count);
            }
            return baos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Test
    @DisplayName("Proof 5 — Live FabricLeafAnvilMod.buildSectionBackingFromLiveSection & DevTelemetrySnapshot JSON Report")
    void proof5LiveSectionEncoderAndDevTelemetryCollection() throws Exception {
        SplittableRandom rng = new SplittableRandom(0x26_3_DE_71L);
        FabricLeafAnvilMod mod = new FabricLeafAnvilMod();
        LcaInMemoryChunkCoordinator coordinator =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(16 * 1024 * 1024));
        AtomicReference<String> reportJson = new AtomicReference<>();

        for (int c = 0; c < 16; c++) {
            RealOverworldColumn col = buildReal24SectionOverworldColumn(200 + c, -100 + c, rng);
            LcaSectionBacking[] liveBackings = new LcaSectionBacking[OVERWORLD_SECTIONS];
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                liveBackings[s] = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
                        col.mojangSections[s],
                        s,
                        OVERWORLD_SECTIONS
                );
                for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                    int x = pos12 & 15;
                    int z = (pos12 >>> 4) & 15;
                    int y = (pos12 >>> 8) & 15;
                    assertEquals(
                            col.expectedStateIds[s][pos12],
                            liveBackings[s].getStateId(x, y, z),
                            "Mismatch in live encoded section " + s + " at pos12=" + pos12
                    );
                }
            }
            LcaInMemoryChunkCoordinator.TrackedChunkColumn tracked = coordinator.attachChunkColumn(
                    "minecraft:overworld",
                    col.chunkX,
                    col.chunkZ,
                    liveBackings,
                    col.mojangSections,
                    1000L + c
            );
            mod.recordColumnTelemetry(col.mojangSections, tracked, reportJson::set);
        }

        FabricLeafAnvilMod.DevTelemetrySnapshot snapshot = mod.flushTelemetryReport(reportJson::set, false);
        assertEquals(16L, snapshot.chunksSampled());
        assertEquals(16L * OVERWORLD_SECTIONS, snapshot.sectionsSampled());
        assertEquals(16L * 16L, snapshot.uniformSections());
        assertEquals(16L * 8L, snapshot.nonUniformSections());
        assertEquals(2L, snapshot.batchesSampled());
        assertTrue(snapshot.memoryReductionPct() >= 85.0, "Expected >=85% RAM reduction: " + snapshot.memoryReductionPct());
        assertTrue(snapshot.wireReductionPct() >= 80.0, "Expected >=80% wire reduction: " + snapshot.wireReductionPct());
        String json = reportJson.get();
        assertNotNull(json);
        assertTrue(json.contains("\"chunksSampled\": 16"));
        LOGGER.info(snapshot.toLogSummary());
    }

    @Test
    @DisplayName("Proof 6 — Fabric Client-to-Paper LCA_NET_V1 Handshake, ChunkBatch Receiver & Client Chunk Telemetry")
    void proof6ClientToPaperHandshakeAndChunkBatchTelemetryRoundTrip() throws Exception {
        SplittableRandom rng = new SplittableRandom(0x26_3_C1_1EL);
        FabricLeafAnvilMod clientMod = new FabricLeafAnvilMod();

        byte[] clientHsBytes = clientMod.buildClientHandshakeBytes();
        HandshakePayload parsedHs = HandshakePayload.fromBytes(clientHsBytes);
        assertEquals(LcaWireStreamProtocol.PROTOCOL_VERSION, parsedHs.protocolVersion());
        assertEquals(FabricLeafAnvilMod.computeBlockStateRegistryParityHash(), parsedHs.registryParityHash64());

        // Simulate Paper server replying with matching handshake ACK
        NegotiatedSession activeSession = clientMod.onClientHandshakeAckReceived(clientHsBytes);
        assertNotNull(activeSession);
        assertTrue(activeSession.isLcaStreamActive());

        // Build a server-side TrackedChunkColumn and encode a proxy-safe chunk_batch payload
        LcaInMemoryChunkCoordinator serverCoord =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(16 * 1024 * 1024));
        UUID playerUuid = UUID.randomUUID();
        serverCoord.registerPlayerHandshake(
                playerUuid,
                parsedHs,
                parsedHs.registryParityHash64()
        );
        RealOverworldColumn col = buildReal24SectionOverworldColumn(45, 21, rng);
        LcaSectionBacking[] serverBackings = new LcaSectionBacking[OVERWORLD_SECTIONS];
        for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
            serverBackings[s] = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
                    col.mojangSections[s], s, OVERWORLD_SECTIONS
            );
        }
        LcaInMemoryChunkCoordinator.TrackedChunkColumn serverCol = serverCoord.attachChunkColumn(
                "minecraft:overworld", col.chunkX, col.chunkZ, serverBackings, col.mojangSections, 777L
        );
        byte[] rawBatchPayload = serverCoord.packOutboundChunkBatchForPlayer(
                playerUuid, "minecraft:overworld", col.chunkX >> 5, col.chunkZ >> 5, List.of(serverCol), true
        );
        assertNotNull(rawBatchPayload);

        int hydratedCount = clientMod.onClientChunkBatchReceived("minecraft:overworld", rawBatchPayload, true);
        assertEquals(1, hydratedCount);
        FabricLeafAnvilMod.DevTelemetrySnapshot snap = clientMod.flushTelemetryReport(null, false);
        assertTrue(snap.chunksSampled() >= 1L, "Client chunk_batch reception must record telemetry");
    }

    @Test
    @DisplayName("Proof 7 — Tier 3 ZeroBitStorage Heap Eviction Frees Real Mojang SimpleBitStorage long[] Arrays")
    void proof7ZeroBitStorageHeapEvictionOnRealMojangPalettedContainer() throws Exception {
        SplittableRandom rng = new SplittableRandom(0x26_3_E7_1CL);
        RealOverworldColumn col = buildReal24SectionOverworldColumn(77, -88, rng);

        int totalBeforeBytes = 0;
        int totalFreedBytes = 0;
        for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
            LevelChunkSection sec = col.mojangSections[s];
            int beforeBytes = net.leaf.anvil.common.LcaSectionBridge.inspectPalettedContainerRawLongArrayBytes(sec);
            totalBeforeBytes += beforeBytes;
            if (!col.isUniformSection[s]) {
                assertTrue(
                        beforeBytes >= 2048,
                        "Non-uniform Mojang section " + s + " must hold >= 2048 B SimpleBitStorage long[] before eviction: " + beforeBytes
                );
            }
            LcaSectionBacking backing = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
                    sec, s, OVERWORLD_SECTIONS
            );
            int freed = net.leaf.anvil.common.LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                    sec,
                    backing.uniformStateId()
            );
            totalFreedBytes += freed;
            int afterBytes = net.leaf.anvil.common.LcaSectionBridge.inspectPalettedContainerRawLongArrayBytes(sec);
            assertEquals(
                    0,
                    afterBytes,
                    "Evicted Mojang section " + s + " must have 0 B (ZeroBitStorage long[0]) after shell swap"
            );
            assertEquals(
                    Block.stateById(backing.uniformStateId()),
                    sec.getStates().get(0, 0, 0),
                    "ZeroBitStorage shell SingleValuePalette must hold section majority/uniform state"
            );
            if (!col.isUniformSection[s]) {
                // Verify ephemeral snapshot materialization from majorityStateId 0-bit shell + minority-only traversal
                PalettedContainer<BlockState> ephemeral = sec.getStates().copy();
                assertTrue(backing.forEachNonMajorityAndOverlayVoxel((pos12, stateId) ->
                        ephemeral.set(
                                pos12 & 15,
                                (pos12 >>> 8) & 15,
                                (pos12 >>> 4) & 15,
                                Block.stateById(stateId)
                        )));
                int[] totalCounted = {0};
                backing.forEachStateCount((stateId, count) -> {
                    assertTrue(count > 0);
                    assertTrue(backing.testMaybeHas(id -> id == stateId));
                    totalCounted[0] += count;
                });
                assertEquals(SECTION_VOXELS, totalCounted[0], "Palette state counts must sum to 4096");
                for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                    int x = pos12 & 15;
                    int z = (pos12 >>> 4) & 15;
                    int y = (pos12 >>> 8) & 15;
                    assertEquals(
                            Block.stateById(col.expectedStateIds[s][pos12]),
                            ephemeral.get(x, y, z),
                            "Ephemeral copy from majorityStateId 0-bit shell must match at s=" + s + ", pos12=" + pos12
                    );
                }
            }
            for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                int x = pos12 & 15;
                int z = (pos12 >>> 4) & 15;
                int y = (pos12 >>> 8) & 15;
                assertEquals(
                        col.expectedStateIds[s][pos12],
                        backing.getStateId(x, y, z),
                        "Bit-exact voxel parity after ZeroBitStorage eviction at section=" + s + ", pos12=" + pos12
                );
            }
        }
        assertTrue(totalBeforeBytes >= 8 * 2048, "Expected >= 16 KiB raw long[] before eviction: " + totalBeforeBytes);
        assertEquals(totalBeforeBytes, totalFreedBytes, "All SimpleBitStorage long[] bytes must be freed");
    }

    @Test
    @DisplayName("Proof 6: Single-Pass Lazy baseFrame, Direct Mojang PalettedContainer#read Wire Round-Trip (Clean & Mutated) & Deferred Compaction Budget")
    void proof6SinglePassLazyBaseFrameDirectWireReadAndDeferredBudget() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_006L);
        RealOverworldColumn col = buildReal24SectionOverworldColumn(0, 0, rng);
        int globalBits = 32 - Integer.numberOfLeadingZeros(Math.max(1, Block.BLOCK_STATE_REGISTRY.size() - 1));

        for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
            LevelChunkSection sec = col.mojangSections[s];
            LcaSectionBacking backing = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(sec, s, OVERWORLD_SECTIONS);
            if (!col.isUniformSection[s]) {
                assertFalse(
                        backing.hasMaterializedBaseFrame(),
                        "buildSectionBackingFromLiveSection must use single-pass CompactBaseIndex without eager baseFrame[] at s=" + s
                );
            }

            // Mutate non-uniform section 4 to exercise mutated direct wire serialization without copy()
            if (s == 4) {
                int diamondId = Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
                int emeraldId = Block.getId(Blocks.EMERALD_BLOCK.defaultBlockState());
                backing.setStateId(0, 0, 0, diamondId, 10L);
                backing.setStateId(7, 8, 9, emeraldId, 11L);
                col.expectedStateIds[s][LcaSectionBacking.pos12(0, 0, 0)] = diamondId;
                col.expectedStateIds[s][LcaSectionBacking.pos12(7, 8, 9)] = emeraldId;
            }

            int expectedSize = backing.computeVanillaSerializedSize(globalBits);
            ByteBuf raw = Unpooled.buffer(expectedSize + 64);
            try {
                FriendlyByteBuf friendly = new FriendlyByteBuf(raw);
                backing.writeVanillaPalettedContainer(globalBits, new LcaSectionBacking.VanillaWireSink() {
                    @Override
                    public void writeByte(int b) {
                        friendly.writeByte(b);
                    }

                    @Override
                    public void writeVarInt(int v) {
                        friendly.writeVarInt(v);
                    }

                    @Override
                    public void writeLong(long word) {
                        friendly.writeLong(word);
                    }
                });
                assertEquals(expectedSize, raw.readableBytes(), "Serialized byte count must match computeVanillaSerializedSize at s=" + s);
                assertEquals(0, backing.ephemeralCopyCount(), "Direct wire serialization must never invoke ephemeral copy at s=" + s);

                PalettedContainer<BlockState> decoded = createBlockStatePalettedContainer();
                decoded.read(friendly);
                assertEquals(0, raw.readableBytes(), "Mojang PalettedContainer#read must consume all written bytes at s=" + s);
                for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                    int x = pos12 & 15;
                    int z = (pos12 >>> 4) & 15;
                    int y = (pos12 >>> 8) & 15;
                    assertEquals(
                            Block.stateById(col.expectedStateIds[s][pos12]),
                            decoded.get(x, y, z),
                            "Mojang PalettedContainer#read mismatch at s=" + s + ", pos12=" + pos12
                    );
                }
            } finally {
                raw.release();
            }
        }

        // Verify deferred chunk compaction queue & per-tick budget in LcaInMemoryChunkCoordinator
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(1024));
        for (int cx = 0; cx < 10; cx++) {
            coordinator.enqueuePendingChunkLoad("minecraft:overworld", cx, 0, col, 100L);
        }
        assertEquals(10, coordinator.pendingChunkCount());
        // Before quiet window (tick 105 < 100 + 10), 0 chunks drained
        AtomicInteger drainedEarly = new AtomicInteger();
        coordinator.tickDeferredChunkCompaction(105L, 10L, 4, p -> drainedEarly.incrementAndGet());
        assertEquals(0, drainedEarly.get());
        assertEquals(10, coordinator.pendingChunkCount());

        // At tick 110 (>= 100 + 10) with budget 4, exactly 4 chunks drained
        AtomicInteger drainedBudget = new AtomicInteger();
        coordinator.tickDeferredChunkCompaction(110L, 10L, 4, p -> drainedBudget.incrementAndGet());
        assertEquals(4, drainedBudget.get());
        assertEquals(6, coordinator.pendingChunkCount());
        coordinator.shutdownCompactionPool();
    }

    @Test
    @DisplayName("Proof 7: O(1) RawSectionSnapshot -> Background LcaCompactionExecutor -> Zero-Translation cleanVanillaWireSlice & Off-Thread Overlay Compaction")
    void proof7BackgroundCompactionPoolAndZeroTranslationWireSlices() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_007L);
        RealOverworldColumn col = buildReal24SectionOverworldColumn(2, -3, rng);
        int globalBits = 32 - Integer.numberOfLeadingZeros(Math.max(1, Block.BLOCK_STATE_REGISTRY.size() - 1));

        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(1024), true);
        try {
            assertTrue(coordinator.isC2meDetected());
            assertTrue(coordinator.compactionWorkerThreadCount() >= 1 && coordinator.compactionWorkerThreadCount() <= 2);

            net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot[] snapshots =
                    new net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot[OVERWORLD_SECTIONS];
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                LevelChunkSection sec = col.mojangSections[s];
                if (sec.hasOnlyAir()) {
                    snapshots[s] = new net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot(
                            s, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0
                    );
                } else {
                    snapshots[s] = net.leaf.anvil.common.LcaSectionBridge.captureRawSectionSnapshot(
                            s,
                            sec,
                            stateObj -> stateObj instanceof BlockState bs ? Block.getId(bs) : 0,
                            -1,
                            -1,
                            -1
                    );
                    assertNotNull(snapshots[s], "O(1) RawSectionSnapshot must succeed at s=" + s);
                    assertTrue(net.leaf.anvil.common.LcaSectionBridge.isRawSectionSnapshotUnmodified(sec, snapshots[s]));
                }
            }

            assertTrue(coordinator.submitChunkSnapshotForCompaction(
                    "minecraft:overworld",
                    2,
                    -3,
                    col,
                    col.mojangSections,
                    snapshots,
                    globalBits,
                    100L,
                    id -> id != 0,
                    id -> !Block.stateById(id).isAir() && Block.stateById(id).isRandomlyTicking(),
                    id -> !Block.stateById(id).getFluidState().isEmpty() && Block.stateById(id).getFluidState().isRandomlyTicking()
            ));
            assertTrue(coordinator.awaitPendingBackgroundCompactions(5000L));
            assertEquals(1, coordinator.completedCompactionQueueSize());

            AtomicReference<LcaInMemoryChunkCoordinator.CompletedChunkCompaction> committedRef = new AtomicReference<>();
            assertEquals(1, coordinator.drainCompletedChunkCompactions(4, committedRef::set));
            LcaInMemoryChunkCoordinator.CompletedChunkCompaction completed = committedRef.get();
            assertNotNull(completed);

            LcaSectionBacking[] backings = completed.builtBackings();
            assertEquals(OVERWORLD_SECTIONS, backings.length);
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                LcaSectionBacking backing = backings[s];
                byte[] cleanSlice = backing.cleanVanillaWireSlice(globalBits);
                assertNotNull(cleanSlice, "Background worker must pre-build cleanVanillaWireSlice at s=" + s);
                assertEquals(backing.computeVanillaSerializedSize(globalBits), cleanSlice.length);

                // Verify zero-translation writeBytes(cleanSlice) decodes identically via Mojang PalettedContainer#read
                ByteBuf raw = Unpooled.wrappedBuffer(cleanSlice);
                FriendlyByteBuf friendly = new FriendlyByteBuf(raw);
                PalettedContainer<BlockState> decoded = createBlockStatePalettedContainer();
                decoded.read(friendly);
                assertEquals(0, raw.readableBytes(), "Mojang PalettedContainer#read must consume entire cleanSlice at s=" + s);
                for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                    int x = pos12 & 15;
                    int z = (pos12 >>> 4) & 15;
                    int y = (pos12 >>> 8) & 15;
                    assertEquals(
                            Block.stateById(col.expectedStateIds[s][pos12]),
                            decoded.get(x, y, z),
                            "Zero-translation cleanSlice mismatch at s=" + s + ", pos12=" + pos12
                    );
                }
            }

            LcaInMemoryChunkCoordinator.TrackedChunkColumn tracked = coordinator.attachChunkColumn(
                    "minecraft:overworld", 2, -3, backings, col.mojangSections, 100L
            );
            int diamondId = Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
            tracked.mutateVoxel(4, 3, 4, 5, diamondId, 110L);
            assertEquals(1, backings[4].dirtyCount());
            org.junit.jupiter.api.Assertions.assertNull(backings[4].cleanVanillaWireSlice(globalBits));

            int scheduled = coordinator.scheduleBackgroundOverlayCompaction(130L, 10L, 8, globalBits);
            assertEquals(1, scheduled);
            assertTrue(coordinator.awaitPendingBackgroundCompactions(5000L));
            assertEquals(0, backings[4].dirtyCount());
            org.junit.jupiter.api.Assertions.assertNull(backings[4].frozenOverlay());
            assertEquals(diamondId, backings[4].getStateId(3, 4, 5));
            assertNotNull(backings[4].cleanVanillaWireSlice(globalBits), "cleanVanillaWireSlice must be rebuilt off-thread");
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Proof 8: Shadow-Tracked Native-Read Mode inside simulation-distance (0 ns read tax) -> Cold-Ring ZeroBitStorage Promotion")
    void proof8ShadowTrackedNativeReadModeAndColdRingPromotion() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_008L);
        RealOverworldColumn col = buildReal24SectionOverworldColumn(5, 5, rng);
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(512), true);
        try {
            LcaSectionBacking[] backings = new LcaSectionBacking[OVERWORLD_SECTIONS];
            net.leaf.anvil.common.LcaSectionBridge[] bridges = new net.leaf.anvil.common.LcaSectionBridge[OVERWORLD_SECTIONS];
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                final LevelChunkSection liveSec = col.mojangSections[s];
                backings[s] = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
                        liveSec, s, OVERWORLD_SECTIONS
                );
                bridges[s] = new net.leaf.anvil.common.LcaSectionBridge() {
                    private LcaSectionBacking attached;
                    private boolean evicted;

                    @Override
                    public LcaSectionBacking leafanvil$getBacking() {
                        return this.attached;
                    }

                    @Override
                    public void leafanvil$setBacking(LcaSectionBacking backing) {
                        leafanvil$setBacking(backing, true);
                    }

                    @Override
                    public void leafanvil$setBacking(LcaSectionBacking backing, boolean evictToZeroBitShell) {
                        this.attached = backing;
                        if (backing != null && evictToZeroBitShell) {
                            leafanvil$evictToZeroBitShell();
                        } else {
                            this.evicted = false;
                        }
                    }

                    @Override
                    public boolean leafanvil$isZeroBitShellEvicted() {
                        return this.evicted;
                    }

                    @Override
                    public int leafanvil$evictToZeroBitShell() {
                        if (this.attached != null && !this.evicted) {
                            int freed = net.leaf.anvil.common.LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                                    liveSec,
                                    Math.max(0, this.attached.uniformStateId())
                            );
                            this.evicted = true;
                            return freed;
                        }
                        return 0;
                    }
                };
            }

            // Attach in Shadow-Tracked Native-Read Mode (evictToZeroBitShell = false)
            LcaInMemoryChunkCoordinator.TrackedChunkColumn tracked = coordinator.attachChunkColumn(
                    "minecraft:overworld", 5, 5, backings, bridges, 200L, false
            );

            // Verify non-uniform sections keep their native SimpleBitStorage long[] intact for zero-overhead reads
            LevelChunkSection nonUniformSec = col.mojangSections[4];
            assertFalse(bridges[4].leafanvil$isZeroBitShellEvicted());
            assertTrue(
                    net.leaf.anvil.common.LcaSectionBridge.inspectPalettedContainerRawLongArrayBytes(nonUniformSec) > 0,
                    "Shadow-Tracked Native-Read Mode must keep SimpleBitStorage intact"
            );

            // Mirror write to both native SimpleBitStorage AND LcaSectionBacking dirtyOverlay
            BlockState emerald = Blocks.EMERALD_BLOCK.defaultBlockState();
            nonUniformSec.setBlockState(2, 3, 4, emerald, false);
            tracked.mutateVoxel(4, 2, 3, 4, Block.getId(emerald), 205L);
            assertEquals(emerald, nonUniformSec.getBlockState(2, 3, 4));
            assertEquals(Block.getId(emerald), backings[4].getStateId(2, 3, 4));
            assertEquals(1, backings[4].dirtyCount());

            // Promote chunk to Cold Evicted ZeroBitStorage mode when player moves outside hot radius
            int freedBytes = coordinator.evictShadowTrackedChunkToZeroBitShell(bridges);
            assertTrue(freedBytes > 0, "Promoting shadow-tracked chunk to cold ring must free SimpleBitStorage long[] bytes");
            assertTrue(bridges[4].leafanvil$isZeroBitShellEvicted());
            assertEquals(
                    0,
                    net.leaf.anvil.common.LcaSectionBridge.inspectPalettedContainerRawLongArrayBytes(nonUniformSec),
                    "Cold-ring section must have 0 long[] bytes after promotion"
            );
            assertEquals(Block.getId(emerald), backings[4].getStateId(2, 3, 4));
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Proof 9: Single-pass FriendlyByteBuf hydration reconstructs evicted PalettedContainer on clean and dirty sections")
    void proof9EphemeralHydrationAvoidsResizeCascadeOnCleanAndDirtySections() throws Exception {
        SplittableRandom rng = new SplittableRandom(26_1_2_009L);
        RealOverworldColumn col = buildReal24SectionOverworldColumn(9, -7, rng);
        int globalBits = 32 - Integer.numberOfLeadingZeros(Math.max(2, Block.BLOCK_STATE_REGISTRY.size()) - 1);

        for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
            LcaSectionBacking backing = FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
                    col.mojangSections[s], s, OVERWORLD_SECTIONS
            );
            // Pre-build clean vanilla wire slice on even sections; leave odd sections uncached + mutate s=5
            if ((s & 1) == 0 && !backing.isCleanUniform()) {
                assertNotNull(backing.cleanVanillaWireSlice(globalBits));
            }
            int mutatedId = -1;
            if (s == 5) {
                mutatedId = Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
                backing.setAndGetPreviousStateId(7, 8, 9, mutatedId, 300L, null);
            }

            PalettedContainer<BlockState> target = createBlockStatePalettedContainer();
            net.leaf.anvil.common.LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                    target, Math.max(0, backing.uniformStateId())
            );
            assertTrue(
                    FabricLeafAnvilMod.hydrateEphemeralPalettedContainerFromBacking(target, backing, globalBits),
                    "Single-pass hydration must succeed at section s=" + s
            );
            for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
                int x = pos12 & 15;
                int z = (pos12 >>> 4) & 15;
                int y = (pos12 >>> 8) & 15;
                int expectedId = (s == 5 && x == 7 && y == 8 && z == 9)
                        ? mutatedId
                        : col.expectedStateIds[s][pos12];
                assertEquals(
                        Block.stateById(expectedId),
                        target.get(x, y, z),
                        "Hydrated PalettedContainer voxel mismatch at s=" + s + ", pos12=" + pos12
                );
            }
        }
    }

    @Test
    @DisplayName("Proof 10: DirectCoWSaveTicket + MixinSerializableChunkData parity commits cold ChunkFrame + sparse dirty cell without CompoundTag voxel trees")
    void proof10DirectCoWSaveTicketAndMixinSerializableChunkDataParity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path regionDir = tempDir.resolve("region");
        java.nio.file.Files.createDirectories(regionDir);

        try (io.github.dailystruggle.anvil.LcaRegionFileStorage storage =
                new io.github.dailystruggle.anvil.LcaRegionFileStorage(regionDir, true)) {
            java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("DataVersion", 4671);
            root.put("xPos", 6);
            root.put("yPos", -4);
            root.put("zPos", -2);
            root.put("Status", "minecraft:full");
            java.util.List<Object> secs = new java.util.ArrayList<>(OVERWORLD_SECTIONS);
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                java.util.LinkedHashMap<String, Object> sec = new java.util.LinkedHashMap<>();
                sec.put("Y", (byte) (-4 + s));
                java.util.LinkedHashMap<String, Object> bs = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, Object> p0 = new java.util.LinkedHashMap<>();
                p0.put("Name", "minecraft:stone");
                java.util.LinkedHashMap<String, Object> p1 = new java.util.LinkedHashMap<>();
                p1.put("Name", "minecraft:deepslate");
                bs.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                        io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, java.util.List.of(p0, p1)));
                long[] words = new long[256];
                words[0] = 1L;
                words[1 + s] = 1L << 4;
                bs.put("data", words);
                sec.put("block_states", bs);
                secs.add(sec);
            }
            root.put("sections", new io.github.dailystruggle.anvil.Nbt.NbtList(
                    io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, secs));
            storage.writeChunkCompound(6, -2, root);

            LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(128));
            try {
                int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
                int deepslateId = Block.getId(Blocks.DEEPSLATE.defaultBlockState());
                int diamondId = Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState());
                coordinator.bindWorldVoxelStorage(
                        "minecraft:overworld",
                        storage,
                        name -> {
                            if ("minecraft:stone".equals(name)) return stoneId;
                            if ("minecraft:deepslate".equals(name)) return deepslateId;
                            if ("minecraft:diamond_block".equals(name)) return diamondId;
                            return 0;
                        },
                        id -> {
                            if (id == stoneId) return "minecraft:stone";
                            if (id == deepslateId) return "minecraft:deepslate";
                            if (id == diamondId) return "minecraft:diamond_block";
                            return "minecraft:air";
                        }
                );

                io.github.dailystruggle.anvil.RawChunkFrameSnapshot coldSnap = storage.readRawChunkFrame(
                        6, -2, name -> "minecraft:stone".equals(name) ? stoneId : ("minecraft:deepslate".equals(name) ? deepslateId : 0));
                assertNotNull(coldSnap);
                byte[][] beforeSlices = new byte[OVERWORLD_SECTIONS][];
                for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                    beforeSlices[s] = coldSnap.sliceSectionFrame(s);
                }

                LcaInMemoryChunkCoordinator.TrackedChunkColumn tracked = coordinator.attachColdChunkFrameColumn(
                        "minecraft:overworld", 6, -2, coldSnap, null, 100L, false);
                assertEquals(0, coordinator.dirtyChunkCount());

                tracked.recordDirtyVoxel(8, LcaSectionBacking.pos12(3, 4, 5), stoneId, diamondId, 101L);
                assertFalse(tracked.section(8).hasHydratedCompactBaseIndex(), "Direct sparse write must keep CompactBaseIndex == null");
                assertEquals(1, coordinator.dirtyChunkCount());

                LcaInMemoryChunkCoordinator.DirectCoWSaveTicket ticket =
                        coordinator.captureDirectCoWSaveTicket("minecraft:overworld", 6, -2);
                assertNotNull(ticket);
                assertTrue(coordinator.commitDirectCoWSaveTicketSync(ticket));
                assertEquals(0, coordinator.dirtyChunkCount(), "Direct CoW save commit must return chunk to cold state");

                io.github.dailystruggle.anvil.RawChunkFrameSnapshot afterSnap = storage.readRawChunkFrame(6, -2, null);
                assertNotNull(afterSnap);
                for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                    if (s != 8) {
                        org.junit.jupiter.api.Assertions.assertArrayEquals(
                                beforeSlices[s], afterSnap.sliceSectionFrame(s),
                                "Untouched section " + s + " must stay bit-for-bit identical");
                    }
                }
            } finally {
                coordinator.shutdownCompactionPool();
            }
        }
    }

    @Test
    @DisplayName("Proof 11 (Gate 1 Repro): Cold ChunkFrame load preserves LevelChunkSection.hasOnlyAir()==false and CAVE_AIR==0 so SkyLightSectionStorage never fills caves with sky light 15")
    void proof11ColdChunkFrameLoadPreservesHasOnlyAirAndCaveSkyLightZero(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path regionDir = tempDir.resolve("region");
        java.nio.file.Files.createDirectories(regionDir);

        try (io.github.dailystruggle.anvil.LcaRegionFileStorage storage =
                new io.github.dailystruggle.anvil.LcaRegionFileStorage(regionDir, true)) {
            java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("DataVersion", 4671);
            root.put("xPos", 2);
            root.put("yPos", -4);
            root.put("zPos", 3);
            root.put("Status", "minecraft:full");
            // Initially saved before light finished (isLightOn absent!)
            java.util.List<Object> secs = new java.util.ArrayList<>(OVERWORLD_SECTIONS);
            for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                java.util.LinkedHashMap<String, Object> sec = new java.util.LinkedHashMap<>();
                sec.put("Y", (byte) (-4 + s));
                java.util.LinkedHashMap<String, Object> bs = new java.util.LinkedHashMap<>();
                if (s == 2) {
                    // Uniform CAVE_AIR section underground!
                    java.util.LinkedHashMap<String, Object> pCaveAir = new java.util.LinkedHashMap<>();
                    pCaveAir.put("Name", "minecraft:cave_air");
                    bs.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                            io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, java.util.List.of(pCaveAir)));
                } else if (s < 8) {
                    // Mixed stone + cave_air underground cave section!
                    java.util.LinkedHashMap<String, Object> pStone = new java.util.LinkedHashMap<>();
                    pStone.put("Name", "minecraft:stone");
                    java.util.LinkedHashMap<String, Object> pCaveAir = new java.util.LinkedHashMap<>();
                    pCaveAir.put("Name", "minecraft:cave_air");
                    bs.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                            io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, java.util.List.of(pStone, pCaveAir)));
                    long[] words = new long[256];
                    words[0] = 1L; // pos12=0 is cave_air, 4095 voxels are stone!
                    bs.put("data", words);
                } else {
                    java.util.LinkedHashMap<String, Object> pAir = new java.util.LinkedHashMap<>();
                    pAir.put("Name", "minecraft:air");
                    bs.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                            io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, java.util.List.of(pAir)));
                }
                sec.put("block_states", bs);
                secs.add(sec);
            }
            root.put("sections", new io.github.dailystruggle.anvil.Nbt.NbtList(
                    io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, secs));
            storage.writeChunkCompound(2, 3, root);

            int stoneId = Block.getId(Blocks.STONE.defaultBlockState());
            int caveAirId = Block.getId(Blocks.CAVE_AIR.defaultBlockState());
            java.util.function.Function<String, Integer> toRuntime = name -> {
                if ("minecraft:stone".equals(name)) return stoneId;
                if ("minecraft:cave_air".equals(name)) return caveAirId;
                return 0;
            };
            java.util.function.IntFunction<String> toCanon = id -> {
                if (id == stoneId) return "minecraft:stone";
                if (id == caveAirId) return "minecraft:cave_air";
                return "minecraft:air";
            };

            LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(128));
            try {
                coordinator.bindWorldVoxelStorage("minecraft:overworld", storage, toRuntime, toCanon);
                io.github.dailystruggle.anvil.RawChunkFrameSnapshot coldSnap =
                        storage.readRawChunkFrame(2, 3, toRuntime);
                assertNotNull(coldSnap);
                assertFalse(coldSnap.hasIsLightOn(),
                        "Pre-light chunk frame must report hasIsLightOn() == false so DirectCoWSaveTicket falls through once lit");

                LevelChunkSection[] liveSections = new LevelChunkSection[OVERWORLD_SECTIONS];
                for (int s = 0; s < OVERWORLD_SECTIONS; s++) {
                    LevelChunkSection nmsSec = createRealLevelChunkSection();
                    if (s == 2) {
                        for (int i = 0; i < SECTION_VOXELS; i++) {
                            nmsSec.setBlockState(i & 15, (i >>> 8) & 15, (i >>> 4) & 15, Blocks.CAVE_AIR.defaultBlockState(), false);
                        }
                    } else if (s < 8) {
                        for (int i = 0; i < SECTION_VOXELS; i++) {
                            nmsSec.setBlockState(
                                    i & 15, (i >>> 8) & 15, (i >>> 4) & 15,
                                    i == 0 ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(),
                                    false);
                        }
                    }
                    liveSections[s] = nmsSec;
                }
                assertTrue(liveSections[2].hasOnlyAir(), "Vanilla CAVE_AIR section hasOnlyAir() must be true before mount");
                assertFalse(liveSections[3].hasOnlyAir(), "Vanilla stone+cave_air section hasOnlyAir() must be false before mount");

                LcaInMemoryChunkCoordinator.TrackedChunkColumn tracked = coordinator.attachColdChunkFrameColumn(
                        "minecraft:overworld", 2, 3, coldSnap, liveSections, 50L, false);

                // Critical Gate 1 check: cold mount MUST preserve nonEmptyBlockCount on liveSections!
                assertEquals(0, tracked.section(2).nonEmptyBlockCount(),
                        "Uniform CAVE_AIR section must preserve nonEmptyBlockCount == 0");
                assertEquals(4095, tracked.section(3).nonEmptyBlockCount(),
                        "Mixed stone+cave_air section must preserve nonEmptyBlockCount == 4095 (not 0!)");
                assertFalse(tracked.section(3).hasOnlyAir(),
                        "Mixed stone+cave_air section backing must NOT report hasOnlyAir() == true!");

                // Also verify standalone cold mount (nmsSections == null) resolves host !BlockState.isAir() predicate
                LcaInMemoryChunkCoordinator.TrackedChunkColumn standaloneTracked = coordinator.attachColdChunkFrameColumn(
                        "minecraft:overworld", 2, 3, coldSnap, null, 51L, false);
                assertEquals(0, standaloneTracked.section(2).nonEmptyBlockCount(),
                        "Standalone uniform CAVE_AIR section must resolve !BlockState.isAir() == 0");
                assertEquals(4095, standaloneTracked.section(3).nonEmptyBlockCount(),
                        "Standalone stone+cave_air section must count 4095 stone voxels and 0 cave_air voxels");

                // Verify DirectCoWSaveTicket with updated section light overrides persists new BlockLight & SkyLight
                standaloneTracked.recordDirtyVoxel(3, 0, caveAirId, stoneId, 52L);
                LcaInMemoryChunkCoordinator.DirectCoWSaveTicket baseTicket =
                        coordinator.captureDirectCoWSaveTicket("minecraft:overworld", 2, 3);
                assertNotNull(baseTicket);
                byte[][] skyOverrides = new byte[OVERWORLD_SECTIONS][];
                byte[][] blkOverrides = new byte[OVERWORLD_SECTIONS][];
                byte[] torchBlk = new byte[2048];
                torchBlk[0] = (byte) 0x0E; // nibble 14 at pos 0
                blkOverrides[3] = torchBlk;
                skyOverrides[3] = new byte[2048]; // all-zero dark cave sky light
                LcaInMemoryChunkCoordinator.DirectCoWSaveTicket lightTicket =
                        baseTicket.withSectionLightOverrides(skyOverrides, blkOverrides, 1 << 3);
                assertTrue(coordinator.commitDirectCoWSaveTicketSync(lightTicket));

                java.util.LinkedHashMap<String, Object> reloaded =
                        io.github.dailystruggle.anvil.Nbt.readRootCompound(storage.readChunkNbtBytes(2, 3));
                io.github.dailystruggle.anvil.Nbt.NbtList reloadedSecs =
                        (io.github.dailystruggle.anvil.Nbt.NbtList) reloaded.get("sections");
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> reloadedSec3 = (java.util.Map<String, Object>) reloadedSecs.items.get(3);
                byte[] savedBlk = (byte[]) reloadedSec3.get("BlockLight");
                assertNotNull(savedBlk, "Updated BlockLight layer must be spliced into .lca section on DirectCoWSave");
                assertEquals((byte) 0x0E, savedBlk[0]);
            } finally {
                coordinator.shutdownCompactionPool();
            }
        }
    }
}
