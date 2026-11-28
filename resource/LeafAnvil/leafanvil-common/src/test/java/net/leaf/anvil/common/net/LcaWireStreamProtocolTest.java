package net.leaf.anvil.common.net;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ChunkDigestEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientOrderedBatchCommitQueue;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientRevisitApplyResult;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryCleanAck;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.LcaRegionDictSyncPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification tests for {@link LcaWireStreamProtocol} (`leafanvil-ADR-003`, `leafanvil-ADR-002`,
 * `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-002, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaWireStreamProtocolTest {

    private static final Logger LOGGER = Logger.getLogger(LcaWireStreamProtocolTest.class.getName());
    private static final long REGISTRY_HASH_64 = 0x6A09E667F3BCC908L;

    @Test
    @DisplayName("Section 1: Handshake negotiation, CAP_CLIENT_HEIGHTMAPS omission, and vanilla fallback")
    void handshakeNegotiationAndMixedClientFallback() throws IOException {
        assertEquals("leafanvil:chunk_stream_handshake", LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
        assertEquals("leafanvil:lca_region_dict_sync", LcaWireStreamProtocol.CHANNEL_REGION_DICT_SYNC);
        assertEquals("leafanvil:lca_chunk_batch", LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
        assertEquals("leafanvil:lca_chunk_digest", LcaWireStreamProtocol.CHANNEL_CHUNK_DIGEST);

        HandshakePayload clientHandshake = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                49_152,
                REGISTRY_HASH_64
        );
        byte[] handshakeBytes = clientHandshake.toBytes();
        assertEquals(20, handshakeBytes.length);
        HandshakePayload decodedHandshake = HandshakePayload.fromBytes(handshakeBytes);
        assertEquals(clientHandshake, decodedHandshake);

        NegotiatedSession negotiated = LcaWireStreamProtocol.negotiate(
                decodedHandshake,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );
        assertTrue(negotiated.isLcaStreamActive());
        assertTrue(negotiated.isBatchLcd2Enabled());
        assertTrue(negotiated.isClientHeightmapsEnabled());
        assertTrue(negotiated.isDeltaRevisitCacheEnabled());
        assertTrue(negotiated.isProxySafeStoredZlibEnabled());
        assertEquals(49_152, negotiated.negotiatedCacheBudgetKiB());

        // Mismatched registryParityHash64 must fall back to vanilla
        NegotiatedSession registryMismatch = LcaWireStreamProtocol.negotiate(
                decodedHandshake,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64 ^ 1L
        );
        assertFalse(registryMismatch.isLcaStreamActive());
        assertEquals(NegotiatedSession.VANILLA_FALLBACK, registryMismatch);

        // Mismatched protocolVersion must fall back to vanilla
        NegotiatedSession versionMismatch = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(2, LcaWireStreamProtocol.ALL_CAPABILITIES, 32_768, REGISTRY_HASH_64),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );
        assertFalse(versionMismatch.isLcaStreamActive());

        // Unmodified vanilla client (null handshake or missing CAP_LCA_SECTION_STREAM) falls back
        assertFalse(LcaWireStreamProtocol.negotiate(
                null, LcaWireStreamProtocol.ALL_CAPABILITIES, REGISTRY_HASH_64).isLcaStreamActive());
        assertFalse(LcaWireStreamProtocol.negotiate(
                new HandshakePayload(LcaWireStreamProtocol.PROTOCOL_VERSION, 0, 0, REGISTRY_HASH_64),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64).isLcaStreamActive());

        // Verify CAP_CLIENT_HEIGHTMAPS omits heightmap bytes (0 B wire cost)
        byte[] heightmaps592B = new byte[592]; // 2 x long[37] raw heightmaps
        Arrays.fill(heightmaps592B, (byte) 0x5A);
        EntryFullChunk chunkWithHeightmaps = buildSyntheticLcaChunk(0, 0, 100L, heightmaps592B, 220, new SplittableRandom(11L));
        ClientboundLcaChunkBatchPayload singleBatch = new ClientboundLcaChunkBatchPayload(0, 0, List.of(chunkWithHeightmaps));

        NegotiatedSession sessionWithClientHm = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.CAP_LCA_SECTION_STREAM | LcaWireStreamProtocol.CAP_CLIENT_HEIGHTMAPS,
                        0,
                        REGISTRY_HASH_64
                ),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );
        NegotiatedSession sessionWithoutClientHm = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.CAP_LCA_SECTION_STREAM,
                        0,
                        REGISTRY_HASH_64
                ),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );

        byte[] rawOmittedHm = LcaWireStreamProtocol.serializeRawChunkBatch(singleBatch, sessionWithClientHm);
        byte[] rawIncludedHm = LcaWireStreamProtocol.serializeRawChunkBatch(singleBatch, sessionWithoutClientHm);
        int savedHeightmapWireBytes = rawIncludedHm.length - rawOmittedHm.length;
        assertEquals(
                heightmaps592B.length + LcaWireStreamProtocol.varIntByteLength(heightmaps592B.length),
                savedHeightmapWireBytes,
                "CAP_CLIENT_HEIGHTMAPS must omit the entire heightmap payload (0 B wire cost)"
        );

        ClientboundLcaChunkBatchPayload decodedOmitted = LcaWireStreamProtocol.decodeChunkBatchPayload(rawOmittedHm, sessionWithClientHm);
        EntryFullChunk restoredOmitted = assertInstanceOf(EntryFullChunk.class, decodedOmitted.entries().get(0));
        assertEquals(0, restoredOmitted.heightmapDeltaBytes().length);

        ClientboundLcaChunkBatchPayload decodedIncluded = LcaWireStreamProtocol.decodeChunkBatchPayload(rawIncludedHm, sessionWithoutClientHm);
        EntryFullChunk restoredIncluded = assertInstanceOf(EntryFullChunk.class, decodedIncluded.entries().get(0));
        assertArrayEquals(heightmaps592B, restoredIncluded.heightmapDeltaBytes());

        // Verify LcaRegionDictSyncPayload delta-VarInt round-trip
        LcaRegionDictSyncPayload dictSync = new LcaRegionDictSyncPayload(
                3, -2,
                new int[]{1, 2, 14, 15, 108, 109, 2048},
                new int[]{0, 4, 12, 39}
        );
        LcaRegionDictSyncPayload decodedDictSync = LcaRegionDictSyncPayload.fromBytes(dictSync.toBytes());
        assertEquals(3, decodedDictSync.regionX());
        assertEquals(-2, decodedDictSync.regionZ());
        assertArrayEquals(dictSync.dictIdToBlockStateRegistryId(), decodedDictSync.dictIdToBlockStateRegistryId());
        assertArrayEquals(dictSync.dictIdToBiomeRegistryId(), decodedDictSync.dictIdToBiomeRegistryId());
    }

    @Test
    @DisplayName("Section 2 & 3.3: 16-chunk batch LCD2 (0x4C44) framing vs per-chunk Zlib vanilla SimpleBitStorage + DataLayer (65%-80%+ reduction)")
    void batchLcd2CompressionVsSimulatedVanillaPerChunkZlib() throws IOException {
        SplittableRandom rng = new SplittableRandom(0x4C43415F41445233L);
        int regionX = 1;
        int regionZ = -1;
        List<BatchChunkEntry> lcaBatchEntries = new ArrayList<>(LcaWireStreamProtocol.MAX_BATCH_CHUNKS);

        int vanillaRawTotalBytes = 0;
        int vanillaZlibTotalBytes = 0;

        byte[] sharedSectionDictionaryTemplate = new byte[64];
        rng.nextBytes(sharedSectionDictionaryTemplate);

        for (int i = 0; i < LcaWireStreamProtocol.MAX_BATCH_CHUNKS; i++) {
            int chunkX = (regionX << 5) + (i & 3);
            int chunkZ = (regionZ << 5) + (i >>> 2);

            byte[] vanillaRawChunk = buildSimulatedVanillaChunkPacketBytes(rng);
            byte[] vanillaZlibChunk = compressZlibPerChunk(vanillaRawChunk);
            vanillaRawTotalBytes += vanillaRawChunk.length;
            vanillaZlibTotalBytes += vanillaZlibChunk.length;

            EntryFullChunk lcaChunk = buildBenchmarkLcaChunk(
                    chunkX,
                    chunkZ,
                    1_000L + i,
                    sharedSectionDictionaryTemplate,
                    rng
            );
            lcaBatchEntries.add(lcaChunk);
        }

        NegotiatedSession negotiated = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        65_536,
                        REGISTRY_HASH_64
                ),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );

        ClientboundLcaChunkBatchPayload batchPayload =
                new ClientboundLcaChunkBatchPayload(regionX, regionZ, lcaBatchEntries);
        byte[] lcaRawBatchBytes = LcaWireStreamProtocol.serializeRawChunkBatch(batchPayload, negotiated);
        byte[] lcaLcd2BatchBytes = LcaWireStreamProtocol.encodeChunkBatchPayload(batchPayload, negotiated);

        double reductionRatio = 1.0 - ((double) lcaLcd2BatchBytes.length / (double) vanillaZlibTotalBytes);
        double reductionPercent = reductionRatio * 100.0;

        LOGGER.info(String.format(
                "[leafanvil-ADR-003 Benchmark] 16-Chunk Batch Wire Comparison: "
                        + "vanillaRaw=%d B (%.1f KiB), vanillaPerChunkZlib=%d B (%.2f KiB, %.1f B/chunk), "
                        + "lcaRawBatch=%d B (%.2f KiB), lcaBatchLcd2=%d B (%.2f KiB, %.1f B/chunk), "
                        + "wireReduction=%.2f%%",
                vanillaRawTotalBytes, vanillaRawTotalBytes / 1024.0,
                vanillaZlibTotalBytes, vanillaZlibTotalBytes / 1024.0, vanillaZlibTotalBytes / 16.0,
                lcaRawBatchBytes.length, lcaRawBatchBytes.length / 1024.0,
                lcaLcd2BatchBytes.length, lcaLcd2BatchBytes.length / 1024.0, lcaLcd2BatchBytes.length / 16.0,
                reductionPercent
        ));

        assertTrue(
                reductionPercent >= 65.0,
                "Expected >= 65% wire-size reduction vs per-chunk Zlib vanilla packet, got "
                        + String.format("%.2f%%", reductionPercent)
        );

        // Verify lossless round-trip decompression via pure-Java LCD2 (0x4C44)
        ClientboundLcaChunkBatchPayload decodedBatch =
                LcaWireStreamProtocol.decodeChunkBatchPayload(lcaLcd2BatchBytes, negotiated);
        assertEquals(regionX, decodedBatch.regionX());
        assertEquals(regionZ, decodedBatch.regionZ());
        assertEquals(LcaWireStreamProtocol.MAX_BATCH_CHUNKS, decodedBatch.entries().size());

        for (int i = 0; i < LcaWireStreamProtocol.MAX_BATCH_CHUNKS; i++) {
            EntryFullChunk expected = assertInstanceOf(EntryFullChunk.class, lcaBatchEntries.get(i));
            EntryFullChunk actual = assertInstanceOf(EntryFullChunk.class, decodedBatch.entries().get(i));
            assertEquals(expected.chunkX(), actual.chunkX());
            assertEquals(expected.chunkZ(), actual.chunkZ());
            assertEquals(expected.lastUpdateTick(), actual.lastUpdateTick());
            assertEquals(expected.secDedSyndrome64(), actual.secDedSyndrome64());
            assertEquals(24, actual.sections().size());

            int uniformCount = 0;
            int nonUniformCount = 0;
            for (int s = 0; s < 24; s++) {
                SectionWireSlice expSlice = expected.sections().get(s);
                SectionWireSlice actSlice = actual.sections().get(s);
                assertEquals(expSlice.directoryWord16(), actSlice.directoryWord16());
                assertArrayEquals(expSlice.baseFrameSlice(), actSlice.baseFrameSlice());
                assertArrayEquals(expSlice.dirtyOverlayEntries(), actSlice.dirtyOverlayEntries());
                if (actSlice.isUniform()) {
                    uniformCount++;
                } else {
                    nonUniformCount++;
                }
            }
            assertEquals(16, uniformCount);
            assertEquals(8, nonUniformCount);
        }
    }

    @Test
    @DisplayName("Section 3.3: Netty VarInt = 0 bypass frame & proxy-safe Deflater.NO_COMPRESSION stored-block Zlib frame")
    void nettyVarIntZeroBypassAndProxySafeStoredZlibFraming() throws IOException {
        SplittableRandom rng = new SplittableRandom(0x5A5354445F46524DL);
        byte[] sharedTemplate = new byte[64];
        rng.nextBytes(sharedTemplate);

        List<BatchChunkEntry> entries = new ArrayList<>(LcaWireStreamProtocol.MAX_BATCH_CHUNKS);
        for (int i = 0; i < LcaWireStreamProtocol.MAX_BATCH_CHUNKS; i++) {
            entries.add(buildBenchmarkLcaChunk(i, 0, 500L + i, sharedTemplate, rng));
        }
        NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        65_536,
                        REGISTRY_HASH_64
                ),
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                REGISTRY_HASH_64
        );
        ClientboundLcaChunkBatchPayload batch = new ClientboundLcaChunkBatchPayload(0, 0, entries);
        byte[] lcd2CompressedBatch = LcaWireStreamProtocol.encodeChunkBatchPayload(batch, session);
        assertTrue(
                lcd2CompressedBatch.length > 16 * 1024,
                "Expected > 16 KiB LCD2 batch payload, got " + lcd2CompressedBatch.length + " B"
        );

        // 1. Direct VarInt = 0 Netty bypass frame
        byte[] nettyFrame = LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(lcd2CompressedBatch);
        int packetLength = 1 + lcd2CompressedBatch.length;
        int packetLengthVarIntBytes = LcaWireStreamProtocol.varIntByteLength(packetLength);

        assertEquals(packetLengthVarIntBytes + 1 + lcd2CompressedBatch.length, nettyFrame.length);
        assertEquals(
                0,
                nettyFrame[packetLengthVarIntBytes],
                "Inner dataLength VarInt must be 0x00 to bypass Netty Zlib CompressionEncoder/Decoder"
        );

        int hypotheticalSubSliced256BFragments = (lcd2CompressedBatch.length + 254) / 255;
        assertTrue(
                hypotheticalSubSliced256BFragments >= 64,
                "Sub-slicing below 256 B threshold would fragment into 64+ packets, got " + hypotheticalSubSliced256BFragments
        );

        byte[] unwrappedPayload = LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(nettyFrame);
        assertArrayEquals(lcd2CompressedBatch, unwrappedPayload);

        // 2. Proxy-safe Deflater.NO_COMPRESSION (level = 0) stored-block Zlib frame (Velocity / BungeeCord safe)
        byte[] proxyStoredFrame = LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(lcd2CompressedBatch);
        byte[] inflatedFromProxyFrame = LcaWireStreamProtocol.decodeProxySafeZlibStoredFrame(proxyStoredFrame);
        assertArrayEquals(lcd2CompressedBatch, inflatedFromProxyFrame);

        int storedZlibOverheadBytes = proxyStoredFrame.length - nettyFrame.length;
        assertTrue(
                storedZlibOverheadBytes <= 16,
                "Proxy-safe stored-block Zlib wrapper should add <= 16 B overhead for a single <64 KiB block, got "
                        + storedZlibOverheadBytes
        );

        LOGGER.info(String.format(
                "[leafanvil-ADR-003 Netty Bypass] lcd2Batch=%d B, varInt0Frame=%d B, proxySafeStoredZlibFrame=%d B (+%d B), "
                        + "frames=1 vs %d fragments at 256 B threshold",
                lcd2CompressedBatch.length,
                nettyFrame.length,
                proxyStoredFrame.length,
                storedZlibOverheadBytes,
                hypotheticalSubSliced256BFragments
        ));

        byte[] invalidNonZeroDataLengthFrame = nettyFrame.clone();
        invalidNonZeroDataLengthFrame[packetLengthVarIntBytes] = 0x01;
        assertThrows(
                IllegalArgumentException.class,
                () -> LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(invalidNonZeroDataLengthFrame)
        );
    }

    @Test
    @DisplayName("Section 4: 3-way revisit delta sync (2 B Clean-Ack, Patch-Delta > 95% reduction) & SEC-DED corruption defense")
    void threeWayRevisitDeltaSyncAndSecDedCorruptionDefense() {
        String dimension = "minecraft:overworld";
        int regionX = 0;
        int regionZ = 0;
        int chunkX = 5;
        int chunkZ = 9;
        long initialTick = 12_000L;

        ClientChunkSlabCache clientCache = new ClientChunkSlabCache(64 * 1024);
        SplittableRandom rng = new SplittableRandom(0x52455649534954L);

        EntryFullChunk serverChunkV1 = buildSyntheticLcaChunk(chunkX, chunkZ, initialTick, new byte[128], 460, rng);
        int fullChunkWireBytes = serverChunkV1.toCanonicalSlabBytes().length;
        assertTrue(fullChunkWireBytes >= 3_500, "Expected full chunk canonical slab >= 3.5 KiB, got " + fullChunkWireBytes);

        BatchChunkEntry initialVisitEntry = LcaWireStreamProtocol.resolveServerRevisitEntry(
                serverChunkV1, null, 0L, -1
        );
        assertInstanceOf(EntryFullChunk.class, initialVisitEntry);
        ClientRevisitApplyResult initialApply = LcaWireStreamProtocol.applyClientRevisitEntry(
                clientCache, dimension, regionX, regionZ, initialVisitEntry, serverChunkV1
        );
        assertFalse(initialApply.secDedCorruptedAndEvicted());
        assertFalse(initialApply.requestedFullChunkFallback());
        assertEquals(1, clientCache.size());

        ServerboundLcaChunkDigestPayload digestPayload =
                ServerboundLcaChunkDigestPayload.fromBytes(
                        clientCache.buildRegionDigest(dimension, regionX, regionZ).toBytes()
                );
        ChunkDigestEntry clientDigestV1 = digestPayload.findChunk(serverChunkV1.localChunkIndex10());
        assertNotNull(clientDigestV1);
        assertEquals(initialTick, clientDigestV1.lastUpdateTick());
        assertEquals(serverChunkV1.secDedSyndrome64(), clientDigestV1.secDedSyndrome64());

        // 1. Case A: Unmodified revisit -> 2-byte ENTRY_CLEAN_ACK (> 99% wire reduction)
        BatchChunkEntry cleanAckEntry = LcaWireStreamProtocol.resolveServerRevisitEntry(
                serverChunkV1, clientDigestV1, serverChunkV1.secDedSyndrome64(), 0
        );
        EntryCleanAck cleanAck = assertInstanceOf(EntryCleanAck.class, cleanAckEntry);
        byte[] cleanAckBytes = cleanAck.toBytes();
        assertEquals(2, cleanAckBytes.length, "ENTRY_CLEAN_ACK must be exactly 2 bytes");
        assertEquals(cleanAck, EntryCleanAck.fromBytes(cleanAckBytes));

        double cleanAckReductionPercent = (1.0 - (2.0 / fullChunkWireBytes)) * 100.0;
        assertTrue(cleanAckReductionPercent > 99.0, "Expected > 99% wire reduction on clean revisit, got " + cleanAckReductionPercent);

        ClientRevisitApplyResult cleanApply = LcaWireStreamProtocol.applyClientRevisitEntry(
                clientCache, dimension, regionX, regionZ, cleanAck, serverChunkV1
        );
        assertFalse(cleanApply.secDedCorruptedAndEvicted());
        assertFalse(cleanApply.requestedFullChunkFallback());
        assertEquals(serverChunkV1.secDedSyndrome64(), cleanApply.resolvedChunk().secDedSyndrome64());

        // 2. Case B: 1-section modified revisit -> ENTRY_PATCH_DELTA (> 95% wire reduction)
        int mutatedSectionIndex = 4;
        byte[] updatedSectionFrame = new byte[120];
        rng.nextBytes(updatedSectionFrame);
        int[] updatedOverlay = new int[]{
                LcaWireStreamProtocol.packDirtyOverlayEntry(17, 1024),
                LcaWireStreamProtocol.packDirtyOverlayEntry(4095, 2049)
        };
        List<SectionWireSlice> v2Sections = new ArrayList<>(serverChunkV1.sections());
        v2Sections.set(
                mutatedSectionIndex,
                SectionWireSlice.nonUniform(mutatedSectionIndex, updatedSectionFrame, updatedOverlay)
        );
        long updatedTick = initialTick + 40L;
        EntryFullChunk serverChunkV2 = EntryFullChunk.createWithComputedSyndrome(
                chunkX, chunkZ, updatedTick, v2Sections, serverChunkV1.heightmapDeltaBytes()
        );
        int dirtyMask = 1 << mutatedSectionIndex;

        BatchChunkEntry patchDeltaEntry = LcaWireStreamProtocol.resolveServerRevisitEntry(
                serverChunkV2, clientDigestV1, serverChunkV1.secDedSyndrome64(), dirtyMask
        );
        EntryPatchDelta patchDelta = assertInstanceOf(EntryPatchDelta.class, patchDeltaEntry);
        byte[] patchDeltaWireBytes = patchDelta.toBytes();
        double patchReductionPercent = (1.0 - ((double) patchDeltaWireBytes.length / (double) fullChunkWireBytes)) * 100.0;

        LOGGER.info(String.format(
                "[leafanvil-ADR-003 Revisit Delta] fullChunk=%d B, cleanAck=%d B (%.2f%% reduction), "
                        + "1SectionPatchDelta=%d B (%.2f%% reduction)",
                fullChunkWireBytes,
                cleanAckBytes.length,
                cleanAckReductionPercent,
                patchDeltaWireBytes.length,
                patchReductionPercent
        ));

        assertTrue(
                patchReductionPercent > 95.0,
                "Expected > 95% wire reduction for 1-section ENTRY_PATCH_DELTA, got "
                        + String.format("%.2f%%", patchReductionPercent)
        );

        ClientRevisitApplyResult patchApply = LcaWireStreamProtocol.applyClientRevisitEntry(
                clientCache, dimension, regionX, regionZ, patchDelta, serverChunkV2
        );
        assertFalse(patchApply.secDedCorruptedAndEvicted());
        assertFalse(patchApply.requestedFullChunkFallback());
        assertEquals(updatedTick, patchApply.resolvedChunk().lastUpdateTick());
        assertEquals(serverChunkV2.secDedSyndrome64(), patchApply.resolvedChunk().secDedSyndrome64());
        assertArrayEquals(
                updatedSectionFrame,
                patchApply.resolvedChunk().sections().get(mutatedSectionIndex).baseFrameSlice()
        );
        assertArrayEquals(
                updatedOverlay,
                patchApply.resolvedChunk().sections().get(mutatedSectionIndex).dirtyOverlayEntries()
        );

        // 3. Case C: Single-bit corruption injected into client LRU cache -> SEC-DED detects mismatch, evicts, and falls back to ENTRY_FULL_CHUNK
        clientCache.corruptSingleBit(dimension, chunkX, chunkZ, 42, 3);
        LcaWireStreamProtocol.CachedChunkSlab corruptedCached = clientCache.get(dimension, chunkX, chunkZ);
        assertNotNull(corruptedCached);
        assertFalse(
                LcaWireStreamProtocol.verifySecDedSyndrome64(
                        corruptedCached.lastUpdateTick(),
                        corruptedCached.slabBytes(),
                        corruptedCached.secDedSyndrome64()
                ),
                "Extended Hamming (72, 64) SEC-DED check must detect single-bit cache corruption"
        );

        long evictionsBefore = clientCache.evictionCount();
        ClientRevisitApplyResult corruptedApply = LcaWireStreamProtocol.applyClientRevisitEntry(
                clientCache,
                dimension,
                regionX,
                regionZ,
                new EntryCleanAck(serverChunkV2.localChunkIndex10()),
                serverChunkV2
        );
        assertTrue(corruptedApply.secDedCorruptedAndEvicted());
        assertTrue(corruptedApply.requestedFullChunkFallback());
        assertEquals(evictionsBefore + 1, clientCache.evictionCount());
        assertNotNull(corruptedApply.resolvedChunk());
        assertEquals(serverChunkV2.secDedSyndrome64(), corruptedApply.resolvedChunk().secDedSyndrome64());

        LcaWireStreamProtocol.CachedChunkSlab healedCached = clientCache.get(dimension, chunkX, chunkZ);
        assertNotNull(healedCached);
        assertTrue(LcaWireStreamProtocol.verifySecDedSyndrome64(
                healedCached.lastUpdateTick(),
                healedCached.slabBytes(),
                healedCached.secDedSyndrome64()
        ));

        for (int extraIdx = 0; extraIdx < 24; extraIdx++) {
            EntryFullChunk extraChunk = buildSyntheticLcaChunk(10 + extraIdx, 20, 20_000L + extraIdx, new byte[64], 450, rng);
            clientCache.put(dimension, extraChunk);
            assertTrue(
                    clientCache.currentBytes() <= clientCache.maxBytes(),
                    "ClientChunkSlabCache currentBytes (" + clientCache.currentBytes()
                            + ") exceeded maxBytes (" + clientCache.maxBytes() + ")"
            );
        }
        assertTrue(clientCache.evictionCount() > evictionsBefore + 1);
    }

    @Test
    @DisplayName("Section 5: Strict Netty arrival-order client commit queue prevents off-thread chunk batch decode from overwriting subsequent BlockUpdatePackets")
    void strictNettyArrivalOrderClientCommitPreventsBlockUpdateOverwrite() {
        ClientOrderedBatchCommitQueue commitQueue = new ClientOrderedBatchCommitQueue();
        AtomicReference<LcaSectionBacking[]> clientLevelSections = new AtomicReference<>();

        // 1. Netty receives ClientboundLcaChunkBatchPayload at sequence 0 (where section 0 is uniform AIR = 0)
        long batchSeq0 = commitQueue.reserveNettyArrivalSequence();
        assertEquals(0L, batchSeq0);

        // 2. One tick later, BEFORE background LCD2 decode of batchSeq0 finishes, Netty receives:
        //    - ClientboundBlockUpdatePacket at sequence 1 setting (2, 3, 4) -> DIAMOND_BLOCK (777)
        //    - ClientboundSectionBlocksUpdatePacket at sequence 2 setting (5, 6, 7) -> EMERALD_BLOCK (888)
        long blockUpdateSeq1 = commitQueue.enqueueOrderedBlockMutation(() -> {
            LcaSectionBacking[] sections = clientLevelSections.get();
            assertNotNull(sections, "Batch seq 0 must be attached before BlockUpdate seq 1 executes");
            sections[0].setStateId(2, 3, 4, 777, 101L);
        });
        long multiBlockSeq2 = commitQueue.enqueueOrderedBlockMutation(() -> {
            LcaSectionBacking[] sections = clientLevelSections.get();
            assertNotNull(sections, "Batch seq 0 must be attached before SectionBlocksUpdate seq 2 executes");
            sections[0].applySectionBlockUpdates(
                    new int[]{LcaSectionBacking.pos12(5, 6, 7)},
                    new int[]{888},
                    102L,
                    null
            );
        });
        assertEquals(1L, blockUpdateSeq1);
        assertEquals(2L, multiBlockSeq2);

        // Main thread ticks while batchSeq0 is still decoding off-thread -> must NOT execute seq 1 or seq 2 yet!
        assertEquals(0, commitQueue.drainReadyInStrictNettyOrder());
        assertEquals(0L, commitQueue.nextCommitSequence());
        assertEquals(2, commitQueue.pendingOutOfOrderCount());

        // 3. Background worker finishes decoding & hydrating batchSeq0 (all AIR = 0) and publishes at seq 0
        EntryFullChunk batchChunk = new EntryFullChunk(
                0, 0, 100L, 1234L,
                List.of(SectionWireSlice.uniform(0, 0, 3)),
                new byte[0]
        );
        HydratedClientChunkCommit hydrated = LcaWireStreamProtocol.hydrateClientChunkOffThread(
                "minecraft:overworld",
                batchChunk,
                LcaWireStreamProtocol::hydrateDefaultSectionBacking
        );
        commitQueue.publishReadyAtSequence(batchSeq0, () ->
                clientLevelSections.set(hydrated.sectionBackings()));

        // 4. Client main thread drains ready queue -> executes seq 0 (attach), then seq 1 (777), then seq 2 (888) in exact order!
        int executed = commitQueue.drainReadyInStrictNettyOrder();
        assertEquals(3, executed);
        assertEquals(3L, commitQueue.nextCommitSequence());
        assertEquals(0, commitQueue.pendingOutOfOrderCount());

        LcaSectionBacking section0 = clientLevelSections.get()[0];
        assertEquals(777, section0.getStateId(2, 3, 4), "ClientboundBlockUpdatePacket (seq 1) must not be overwritten by batch (seq 0)");
        assertEquals(888, section0.getStateId(5, 6, 7), "ClientboundSectionBlocksUpdatePacket (seq 2) must not be overwritten by batch (seq 0)");
        assertEquals(0, section0.getStateId(0, 0, 0));
    }

    @Test
    @DisplayName("LcaRemoteStreamCodec full chunks with dirty overlays and heightmaps decode cleanly in LcaWireStreamProtocol")
    void lcaRemoteStreamCodecFullChunkWithOverlaysAndHeightmapsDecodesCleanlyInLcaWireStreamProtocol() throws IOException {
        int[] voxels = new int[4096];
        for (int i = 0; i < 4096; i++) {
            voxels[i] = (i & 1) == 0 ? 1 : 9;
        }
        LcaSectionBacking.EncodedCompactFrame compact = LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0);
        int packedOverlay = io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.packDirtyOverlayEntry(42, 77);
        List<io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice> remoteSlices = List.of(
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.uniform(0, 15, 0, 1),
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.nonUniform(
                        1, compact.frameBytes(), new int[]{packedOverlay}
                ),
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.uniform(2, 15, 0, 0)
        );
        byte[] hmBytes = new byte[]{1, 2, 3, 4, 5};
        io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk remoteChunk =
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
                        3, -2, 123L, remoteSlices, hmBytes
                );

        byte[] lcd2Wire = io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.encodeChunkBatch(
                3 >> 5, -2 >> 5, List.of(remoteChunk), false, true
        );
        NegotiatedSession session = new NegotiatedSession(
                true, LcaWireStreamProtocol.ALL_CAPABILITIES, 32768, 0x1234L
        );
        LcaOrderlessDictTable clientDict = new LcaOrderlessDictTable();
        ClientboundLcaChunkBatchPayload decoded =
                LcaWireStreamProtocol.decodeChunkBatchPayload(lcd2Wire, session, clientDict);
        assertEquals(1, decoded.entries().size());
        EntryFullChunk decodedFull = assertInstanceOf(EntryFullChunk.class, decoded.entries().get(0));
        assertEquals(3, decodedFull.chunkX());
        assertEquals(-2, decodedFull.chunkZ());
        assertEquals(3, decodedFull.sections().size());
        assertArrayEquals(new int[]{packedOverlay}, decodedFull.sections().get(1).dirtyOverlayEntries());
        assertArrayEquals(hmBytes, decodedFull.heightmapDeltaBytes());
    }

    @Test
    @DisplayName("Failed batch decode releases reserved arrivalSeq so subsequent chunks are never head-of-line deadlocked")
    void failedBatchDecodeDoesNotDeadlockSubsequentOrderedClientCommits() throws IOException {
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coordinator =
                new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(8 * 1024 * 1024));
        NegotiatedSession session = new NegotiatedSession(
                true, LcaWireStreamProtocol.ALL_CAPABILITIES, 32768, 0x1234L
        );
        List<HydratedClientChunkCommit> committed = new ArrayList<>();

        // 1. Corrupt batch throws IOException after reserving arrivalSeq = 0
        byte[] corruptBatch = new byte[]{0x4C, 0x43, 0x41, 0x42, 0x00, 0x00, (byte) 0xFF};
        assertThrows(IOException.class, () -> coordinator.receiveClientChunkBatchOrdered(
                "minecraft:overworld", corruptBatch, true, session, coordinator.clientOrderlessDictTable(), null, committed::add
        ));

        // 2. Subsequent valid chunk batch at arrivalSeq = 1 MUST still drain and commit immediately
        io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk validChunk =
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
                        4, 5, 50L,
                        List.of(io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.uniform(0, 15, 0, 1)),
                        new byte[0]
                );
        byte[] validWire = io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.encodeChunkBatch(
                0, 0, List.of(validChunk), true, true
        );
        coordinator.receiveClientChunkBatchOrdered(
                "minecraft:overworld", validWire, true, session, coordinator.clientOrderlessDictTable(), null, committed::add
        );
        coordinator.clientCommitQueue().drainReadyInStrictNettyOrder();
        assertEquals(1, committed.size(), "Valid batch after decode error must not be deadlocked in clientCommitQueue");
        assertEquals(4, committed.get(0).chunkX());
        assertEquals(5, committed.get(0).chunkZ());
    }

    // =========================================================================
    // Test Fixture Builders
    // =========================================================================

    private static EntryFullChunk buildBenchmarkLcaChunk(
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            byte[] sharedTemplate,
            SplittableRandom rng
    ) {
        List<SectionWireSlice> sections = new ArrayList<>(24);
        for (int s = 0; s < 16; s++) {
            int majorityStateId = (s < 4) ? 0 : (s < 12 ? 1 : 0);
            int skyLight2 = (s >= 12) ? 3 : 0;
            sections.add(SectionWireSlice.uniform(s, majorityStateId, skyLight2));
        }
        for (int s = 16; s < 24; s++) {
            byte[] baseFrame = new byte[240];
            System.arraycopy(sharedTemplate, 0, baseFrame, 0, sharedTemplate.length);
            for (int b = sharedTemplate.length; b < baseFrame.length; b++) {
                baseFrame[b] = (byte) rng.nextInt(256);
            }
            int[] dirtyOverlay = new int[]{
                    LcaWireStreamProtocol.packDirtyOverlayEntry((s * 37) & 0xFFF, 15 + s),
                    LcaWireStreamProtocol.packDirtyOverlayEntry((s * 91 + 3) & 0xFFF, 120 + s),
                    LcaWireStreamProtocol.packDirtyOverlayEntry((s * 173 + 7) & 0xFFF, 340 + s),
                    LcaWireStreamProtocol.packDirtyOverlayEntry((s * 251 + 11) & 0xFFF, 512 + s)
            };
            sections.add(SectionWireSlice.nonUniform(s, baseFrame, dirtyOverlay));
        }
        byte[] heightmaps = new byte[592];
        rng.nextBytes(heightmaps);
        return EntryFullChunk.createWithComputedSyndrome(chunkX, chunkZ, lastUpdateTick, sections, heightmaps);
    }

    private static EntryFullChunk buildSyntheticLcaChunk(
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            byte[] heightmapBytes,
            int nonUniformBytesPerSection,
            SplittableRandom rng
    ) {
        List<SectionWireSlice> sections = new ArrayList<>(24);
        for (int s = 0; s < 16; s++) {
            sections.add(SectionWireSlice.uniform(s, s % 3, s >= 12 ? 3 : 0));
        }
        for (int s = 16; s < 24; s++) {
            byte[] baseFrame = new byte[nonUniformBytesPerSection];
            rng.nextBytes(baseFrame);
            int[] overlay = new int[]{
                    LcaWireStreamProtocol.packDirtyOverlayEntry(s * 11, 42 + s),
                    LcaWireStreamProtocol.packDirtyOverlayEntry(s * 29 + 1, 99 + s)
            };
            sections.add(SectionWireSlice.nonUniform(s, baseFrame, overlay));
        }
        return EntryFullChunk.createWithComputedSyndrome(chunkX, chunkZ, lastUpdateTick, sections, heightmapBytes);
    }

    private static byte[] buildSimulatedVanillaChunkPacketBytes(SplittableRandom rng) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(48 * 1024);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            for (int i = 0; i < 74; i++) {
                long word = 0L;
                for (int c = 0; c < 7; c++) {
                    long height = 64 + rng.nextInt(48);
                    word |= (height & 0x1FFL) << (c * 9);
                }
                out.writeLong(word);
            }
            for (int s = 0; s < 16; s++) {
                out.writeShort(0);
                out.writeByte(0);
                out.writeShort(s < 8 ? 1 : 0);
                if (s >= 10) {
                    byte[] skyLightLayer = new byte[2048];
                    for (int idx = 0; idx < skyLightLayer.length; idx++) {
                        int l0 = (15 - ((idx + s) & 7)) & 0xF;
                        int l1 = (15 - ((idx + s + 3) & 7)) & 0xF;
                        skyLightLayer[idx] = (byte) ((l1 << 4) | l0);
                    }
                    out.write(skyLightLayer);
                }
            }
            for (int s = 0; s < 8; s++) {
                boolean fiveBit = (s >= 4);
                int bitsPerEntry = fiveBit ? 5 : 4;
                int valuesPerLong = 64 / bitsPerEntry;
                int longWords = fiveBit ? 342 : 256;
                int paletteBound = fiveBit ? 22 : 14;
                out.writeShort(3840);
                out.writeByte(bitsPerEntry);
                for (int w = 0; w < longWords; w++) {
                    long packedWord = 0L;
                    for (int v = 0; v < valuesPerLong; v++) {
                        int stateIdx = (rng.nextInt(100) < 84) ? 0 : (1 + rng.nextInt(paletteBound));
                        packedWord |= ((long) stateIdx) << (v * bitsPerEntry);
                    }
                    out.writeLong(packedWord);
                }
                byte[] skyLight = new byte[2048];
                byte[] blockLight = new byte[2048];
                for (int idx = 0; idx < 2048; idx++) {
                    int sky0 = (15 - ((idx + rng.nextInt(4)) & 0xF)) & 0xF;
                    int sky1 = (15 - ((idx + 1 + rng.nextInt(4)) & 0xF)) & 0xF;
                    skyLight[idx] = (byte) ((sky1 << 4) | sky0);
                    int blk0 = (rng.nextInt(100) < 75) ? 0 : (1 + rng.nextInt(14));
                    int blk1 = (rng.nextInt(100) < 75) ? 0 : (1 + rng.nextInt(14));
                    blockLight[idx] = (byte) ((blk1 << 4) | blk0);
                }
                out.write(skyLight);
                out.write(blockLight);
            }
        }
        return baos.toByteArray();
    }

    private static byte[] compressZlibPerChunk(byte[] rawChunkPacket) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(rawChunkPacket);
            deflater.finish();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(rawChunkPacket.length / 2);
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                if (count > 0) {
                    baos.write(buf, 0, count);
                }
            }
            return baos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Test
    @DisplayName("Section 6: Inter-server zero-translation chunk delta sync (LCAS) with SECDED-64 baseline fencing and refill detection")
    void interServerZeroTranslationChunkDeltaSyncAndRefillFencing() throws IOException {
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator serverA =
                new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(256), true);
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator serverB =
                new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(256), true);
        try {
            LcaSectionBacking[] sectionsA = new LcaSectionBacking[24];
            for (int i = 0; i < 24; i++) {
                sectionsA[i] = new LcaSectionBacking(new byte[0], i == 4 ? 1 : 0);
            }
            serverA.attachChunkColumn("minecraft:overworld", 10, -6, sectionsA, null, 100L, false);

            // 1. Export initial full chunk from Server A to pre-warm Server B
            LcaWireStreamProtocol.InterServerChunkUpdatePayload initialTransfer =
                    serverA.exportChunkForInterServerTransfer("node-alpha", "minecraft:overworld", 10, -6, 100L, 1L);
            assertNotNull(initialTransfer);
            byte[] encodedInitial = LcaWireStreamProtocol.encodeInterServerUpdatePayload(initialTransfer, true);
            LcaWireStreamProtocol.InterServerChunkUpdatePayload decodedInitial =
                    LcaWireStreamProtocol.decodeInterServerUpdatePayload(encodedInitial, true);
            assertEquals("node-alpha", decodedInitial.sourceServerId());
            assertEquals(1, decodedInitial.entries().size());

            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.APPLIED,
                    serverB.applyInterServerChunkDeltaEntry("minecraft:overworld", decodedInitial.entries().get(0), 15)
            );
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn colB =
                    serverB.getTrackedChunk("minecraft:overworld", 10, -6);
            assertNotNull(colB);
            assertEquals(1, colB.section(4).getStateId(2, 3, 4));

            // 2. Mutate 2 sections on Server A and export zero-translation EntryPatchDelta
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn colA =
                    serverA.getTrackedChunk("minecraft:overworld", 10, -6);
            colA.mutateVoxel(4, 2, 3, 4, 77, 105L);
            colA.mutateVoxel(4, 15, 15, 15, 88, 105L);
            colA.mutateVoxel(7, 0, 1, 2, 99, 106L);

            LcaWireStreamProtocol.InterServerChunkUpdatePayload deltaPayload =
                    serverA.exportDirtyChunkDeltasForInterServerSync(
                            "node-alpha",
                            "minecraft:overworld",
                            10 >> 5,
                            -6 >> 5,
                            106L,
                            2L,
                            true
                    );
            assertNotNull(deltaPayload);
            assertEquals(0, colA.dirtySectionBitmask32(), "advanceBaseline=true must clear dirtySectionBitmask32");

            byte[] wireDelta = LcaWireStreamProtocol.encodeInterServerUpdatePayload(deltaPayload, true);
            assertTrue(wireDelta.length < 256, "Inter-server 2-section delta LCD2 payload must be < 256 B (was " + wireDelta.length + " B)");
            LcaWireStreamProtocol.InterServerChunkUpdatePayload decodedDelta =
                    LcaWireStreamProtocol.decodeInterServerUpdatePayload(wireDelta, true);
            assertInstanceOf(EntryPatchDelta.class, decodedDelta.entries().get(0).chunkEntry());

            // 3. Apply delta on Server B -> verifies baselineSecDed64, folds into LcaSectionBacking, and queues live block commits
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.APPLIED,
                    serverB.applyInterServerChunkDeltaEntry("minecraft:overworld", decodedDelta.entries().get(0), 15)
            );
            assertEquals(77, colB.section(4).getStateId(2, 3, 4));
            assertEquals(88, colB.section(4).getStateId(15, 15, 15));
            assertEquals(99, colB.section(7).getStateId(0, 1, 2));
            assertEquals(0, colB.section(4).dirtyCount(), "Background delta apply must fold overlay into CompactBaseIndex");

            List<net.leaf.anvil.common.LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit> commits = new ArrayList<>();
            assertEquals(2, serverB.drainPendingInterServerBlockCommits(8, commits::add));

            // 4. Re-applying the same delta against advanced baselineSecDed64 must request NEEDS_FULL_CHUNK_REFILL
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL,
                    serverB.applyInterServerChunkDeltaEntry("minecraft:overworld", decodedDelta.entries().get(0), 15)
            );
        } finally {
            serverA.shutdownCompactionPool();
            serverB.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Section 7: LcaClientWireReceiver negotiates LCA_NET_V1 handshakes, handles S-004 malformed ACKs, ingests ordered batches, and guards co-installed registration")
    void clientWireReceiverNegotiatesIngestsBatchesAndGuardsRegistration() throws IOException {
        LcaClientWireReceiver.resetGlobalClientRegistrationForTests();
        assertTrue(LcaClientWireReceiver.tryAcquireGlobalClientRegistration());
        assertFalse(LcaClientWireReceiver.tryAcquireGlobalClientRegistration());
        LcaClientWireReceiver.resetGlobalClientRegistrationForTests();

        LcaClientWireReceiver clientRx = new LcaClientWireReceiver();
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator serverTx =
                new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(256), true);
        long parityHash = 0x5A5A_1234_9876_CAFEL;
        try {
            byte[] clientHsBytes = clientRx.buildClientHandshakeBytes(parityHash);
            assertEquals(LcaClientWireReceiver.HANDSHAKE_WIRE_BYTES, clientHsBytes.length);

            // Truncated ACK must log WARNING and fall back to vanilla without throwing (S-004)
            NegotiatedSession fallback = clientRx.onServerHandshakeAck(new byte[]{1, 2, 3}, parityHash);
            assertFalse(fallback.isLcaStreamActive());

            // Valid server ACK activates LCA_NET_V1 session
            NegotiatedSession active = clientRx.onServerHandshakeAck(clientHsBytes, parityHash);
            assertTrue(active.isLcaStreamActive());
            assertEquals(LcaWireStreamProtocol.ALL_CAPABILITIES, active.activeCapabilities());

            // Server builds an outbound chunk batch and sends it to LcaClientWireReceiver
            LcaSectionBacking[] sections = new LcaSectionBacking[24];
            for (int i = 0; i < 24; i++) {
                sections[i] = new LcaSectionBacking(new byte[0], i == 3 ? 1 : 0);
            }
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn serverCol =
                    serverTx.attachChunkColumn("minecraft:overworld", 4, -2, sections, null, 200L);
            serverCol.mutateVoxel(3, 1, 2, 3, 45, 205L);

            java.util.UUID playerId = java.util.UUID.randomUUID();
            serverTx.registerPlayerHandshake(playerId, HandshakePayload.fromBytes(clientHsBytes), parityHash);
            byte[] framedBatch = serverTx.packOutboundChunkBatchForPlayer(
                    playerId,
                    "minecraft:overworld",
                    4 >> 5,
                    -2 >> 5,
                    List.of(serverCol),
                    true
            );
            assertNotNull(framedBatch);

            java.util.concurrent.atomic.AtomicInteger mainThreadCommits =
                    new java.util.concurrent.atomic.AtomicInteger();
            int committed = clientRx.onChunkBatchPayloadReceived(
                    "minecraft:overworld",
                    framedBatch,
                    true,
                    parityHash,
                    commit -> mainThreadCommits.incrementAndGet()
            );
            assertEquals(1, committed);
            assertEquals(1, mainThreadCommits.get());

            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn clientCol =
                    clientRx.coordinator().getTrackedChunk("minecraft:overworld", 4, -2);
            assertNotNull(clientCol);
            assertEquals(45, clientCol.section(3).getStateId(1, 2, 3));
            assertTrue(clientRx.coordinator().isClientMode(), "LcaClientWireReceiver must run coordinator in clientMode");
            clientCol.mutateVoxel(3, 4, 5, 6, 77, 210L);
            assertEquals(77, clientCol.section(3).getStateId(4, 5, 6));
            assertEquals(0, clientRx.coordinator().dirtyChunkCount(),
                    "clientMode must bypass server-side dirtyChunkLru victim scanning on block updates");

            byte[] digestBytes = clientRx.buildRegionRevisitDigestBytes("minecraft:overworld", 4 >> 5, -2 >> 5);
            ServerboundLcaChunkDigestPayload digest = ServerboundLcaChunkDigestPayload.fromBytes(digestBytes);
            assertEquals(1, digest.entries().size());

            LcaClientWireReceiver.ClientTelemetrySnapshot snap = clientRx.snapshotTelemetry();
            assertEquals(1L, snap.batchesReceived());
            assertEquals(1L, snap.chunksCommitted());
            assertEquals(24L, snap.sectionsHydrated());
            assertTrue(snap.wireReductionPct() > 0.0);

            clientRx.onClientDisconnect();
            assertFalse(clientRx.negotiatedSession().isLcaStreamActive());
        } finally {
            clientRx.coordinator().shutdownCompactionPool();
            serverTx.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Direct Mode A, SimpleBitStorage flat-bitpack, O(1) passthrough, and incremental ODCT dictionary decode bit-identically in LcaClientWireReceiver")
    void directModeAFlatBitpackAndIncrementalOdctDecodeBitIdentically() throws IOException {
        // 1. Build a Mode A section directly via LcaModeABlockSection and export without decompression
        int[] modeAVoxels = new int[4096];
        for (int i = 0; i < 4096; i++) {
            modeAVoxels[i] = (i % 19 == 0) ? 14 : ((i % 7 == 0) ? 9 : 1);
        }
        io.github.dailystruggle.anvil.LcaModeABlockSection modeA =
                io.github.dailystruggle.anvil.LcaModeABlockSection.encodeFromRuntimeVoxels(modeAVoxels);
        assertNotNull(modeA);
        byte[] modeAWire = modeA.toWireSectionFrameBytes();

        // 2. Build a 4-bit SimpleBitStorage section (10 palette entries -> flatBits=4, 1:1 long copy)
        int[] pal10 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        long[] raw4 = new long[256];
        int[] expected4 = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int ord = (i * 7 + (i >>> 4)) % 10;
            expected4[i] = pal10[ord];
            raw4[i >>> 4] |= ((long) ord) << ((i & 15) << 2);
        }
        byte[] flat4Wire = io.github.dailystruggle.anvil.LcaModeABlockSection
                .encodeFlatBitpackFromSimpleBitStorage(pal10, 4, raw4);
        assertNotNull(flat4Wire);

        // 3. Build a 4-bit SimpleBitStorage section with only 4 palette entries (repacks to 2-bit = 1024 B)
        int[] pal4 = {0, 1, 9, 34};
        long[] raw2in4 = new long[256];
        int[] expected2 = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int ord = (i + (i >>> 6)) & 3;
            expected2[i] = pal4[ord];
            raw2in4[i >>> 4] |= ((long) ord) << ((i & 15) << 2);
        }
        byte[] flat2Wire = io.github.dailystruggle.anvil.LcaModeABlockSection
                .encodeFlatBitpackFromSimpleBitStorage(pal4, 4, raw2in4);
        assertNotNull(flat2Wire);

        List<io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice> slices = new ArrayList<>(4);
        slices.add(io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.uniform(0, 15, 0, 1));
        slices.add(io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.nonUniform(1, modeAWire, new int[0]));
        slices.add(io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.nonUniform(2, flat4Wire, new int[0]));
        slices.add(io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice.nonUniform(3, flat2Wire, new int[0]));

        io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable serverDict =
                new io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable(1);
        io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable.KnownSlotBitSet knownSlots =
                new io.github.dailystruggle.anvil.stream.LcaOrderlessDictWireTable.KnownSlotBitSet();

        io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk entry1 =
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
                        5, -3, 100L, slices, new byte[0]);
        byte[] packet1 = io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.encodeChunkBatchDirect(
                5 >> 5, -3 >> 5, List.of(entry1), true, true, serverDict, knownSlots);

        // Second chunk with identical slices must emit 0 learn entries and 2-byte ODCT references (much smaller!)
        io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk entry2 =
                io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk.createWithComputedSyndrome(
                        6, -3, 101L, slices, new byte[0]);
        byte[] packet2 = io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.encodeChunkBatchDirect(
                6 >> 5, -3 >> 5, List.of(entry2), true, true, serverDict, knownSlots);
        assertTrue(packet2.length < packet1.length / 4,
                "Second packet with already-known ODCT dictionary entries must be drastically smaller: p1="
                        + packet1.length + ", p2=" + packet2.length);

        // Decode both packets on the Fabric client receiver (which uses LcaWireStreamProtocol + LcaOrderlessDictTable)
        LcaClientWireReceiver clientRx = new LcaClientWireReceiver();
        long parityHash = 0x1122334455667788L;
        try {
            byte[] hs = clientRx.buildClientHandshakeBytes(parityHash);
            assertTrue(clientRx.onServerHandshakeAck(hs, parityHash).isLcaStreamActive());

            assertEquals(1, clientRx.onChunkBatchPayloadReceived("minecraft:overworld", packet1, false, parityHash, null));
            assertEquals(1, clientRx.onChunkBatchPayloadReceived("minecraft:overworld", packet2, false, parityHash, null));

            for (int cx : new int[]{5, 6}) {
                net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn col =
                        clientRx.coordinator().getTrackedChunk("minecraft:overworld", cx, -3);
                assertNotNull(col, "Chunk (" + cx + ",-3) must be hydrated");
                assertEquals(1, col.section(0).getStateId(0, 0, 0));
                int[] decodedModeA = new int[4096];
                LcaSectionBacking.decodeCompactBaseFrameInto(col.section(1).baseFrame(), 0, decodedModeA);
                assertArrayEquals(modeAVoxels, decodedModeA, "Mode A section must round-trip bit-identically");
                int[] decodedFlat4 = new int[4096];
                LcaSectionBacking.decodeCompactBaseFrameInto(col.section(2).baseFrame(), 0, decodedFlat4);
                assertArrayEquals(expected4, decodedFlat4, "4-bit SimpleBitStorage flat-bitpack must round-trip bit-identically");
                int[] decodedFlat2 = new int[4096];
                LcaSectionBacking.decodeCompactBaseFrameInto(col.section(3).baseFrame(), 0, decodedFlat2);
                assertArrayEquals(expected2, decodedFlat2, "2-bit repacked flat-bitpack must round-trip bit-identically");
            }
        } finally {
            clientRx.coordinator().shutdownCompactionPool();
        }
    }
}
