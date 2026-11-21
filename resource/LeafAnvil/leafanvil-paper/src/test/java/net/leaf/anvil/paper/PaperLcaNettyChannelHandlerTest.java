package net.leaf.anvil.paper;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.DecodedMultiTargetFrame;
import net.leaf.anvil.common.net.LcaOrderlessDictTable;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.paper.PaperLcaNettyChannelHandler.ChunkBatchPacingDescriptor;
import net.leaf.anvil.paper.PaperLcaNettyChannelHandler.EmittedLcaNettyFrame;
import net.leaf.anvil.paper.PaperLcaNettyChannelHandler.FullChunkDescriptor;
import net.leaf.anvil.paper.PaperLcaNettyChannelHandler.SectionMultiBlockUpdateDescriptor;
import net.leaf.anvil.paper.PaperLcaNettyChannelHandler.SingleBlockUpdateDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual-client Netty {@link EmbeddedChannel} verification suite for {@link PaperLcaNettyChannelHandler}
 * (`leafanvil-ADR-005` Workstream 4, `leafanvil-ADR-003`, `leafanvil-ADR-004`, `leafanvil-ADR-006`).
 *
 * <p>Verifies simultaneous conditional routing where an unnegotiated vanilla client and a
 * negotiated `LCA_NET_V1` client view the same dirty chunk concurrently: vanilla packets pass
 * straight through on the first channel (with $O(D)$ sparse scratch stamping verified), while the
 * second channel suppresses vanilla voxel packets, releases `ReferenceCounted` buffers (`REQ-RTP-S-002`),
 * preserves RTT chunk-batch pacing packets, supports `> 32 KiB` frames, coalesces 100 rapid block
 * edits into a single `MTCF` + `ODCT` + `LCD2` (`0x4C44`) `EntryPatchDelta`, and purges session/buffer
 * state deterministically on disconnect.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-004
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class PaperLcaNettyChannelHandlerTest {

    private static final long PARITY_HASH = 0x517CC1B727220A95L;

    /**
     * Simulated NMS `ClientboundLevelChunkWithLightPacket` implementing {@link ReferenceCounted}
     * to prove deterministic `ByteBuf` / reference-count release on suppression (`REQ-RTP-S-002`).
     */
    public static final class ClientboundLevelChunkWithLightPacket extends AbstractReferenceCounted {
        private final int x;
        private final int z;
        private boolean deallocated;

        public ClientboundLevelChunkWithLightPacket(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public int getX() {
            return x;
        }

        public int getZ() {
            return z;
        }

        public boolean isDeallocated() {
            return deallocated;
        }

        @Override
        protected void deallocate() {
            this.deallocated = true;
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }
    }

    @Test
    @DisplayName("Simultaneous vanilla and LCA_NET_V1 EmbeddedChannels route native packets vs ODCT + MTCF + LCD2 (> 32 KiB supported) and release buffers on disconnect")
    void dualEmbeddedChannelConditionalVanillaAndLcaWireStreaming() throws Exception {
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(256));
        try {
            // Build a 24-section column with 2 sparse dirty block changes in section 8 (y = 4)
            LcaSectionBacking[] sections = new LcaSectionBacking[24];
            SplittableRandom rng = new SplittableRandom(0x4C43415F4E4554L);
            for (int s = 0; s < 24; s++) {
                int[] vox = new int[4096];
                Arrays.fill(vox, 1 + (s % 4));
                for (int i = 0; i < 64; i++) {
                    vox[rng.nextInt(4096)] = 10 + (i & 7);
                }
                sections[s] = LcaSectionBacking.fromDirectVoxels(
                        vox, 15, 0, 4096, 0, 0, id -> id != 0, id -> false, id -> false);
                assertTrue(sections[s].evictCompactBaseIndexToColdBaseFrame());
            }
            TrackedChunkColumn col = coordinator.attachChunkColumn(
                    "minecraft:overworld", 3, -5, sections, null, 200L, false);
            col.recordDirtyVoxel(8, LcaSectionBacking.pos12(4, 5, 6), 1, 777, 201L);
            col.recordDirtyVoxel(8, LcaSectionBacking.pos12(9, 10, 11), 1, 888, 202L);

            // Verify O(D) sparse dirty-map stamping over thread-local int[4096] scratch for shell-evicted vanilla path
            int[] vanillaScratch = PaperLcaNettyChannelHandler.stampShellEvictedSectionForVanilla(col.section(8));
            assertEquals(777, vanillaScratch[LcaSectionBacking.pos12(4, 5, 6)]);
            assertEquals(888, vanillaScratch[LcaSectionBacking.pos12(9, 10, 11)]);
            assertFalse(col.section(8).hasHydratedCompactBaseIndex(), "Vanilla scratch stamp must keep CompactBaseIndex == null");

            UUID vanillaPlayerId = UUID.randomUUID();
            UUID lcaPlayerId = UUID.randomUUID();

            // Negotiate ONLY lcaPlayerId (vanillaPlayerId remains unnegotiated)
            NegotiatedSession negotiated = coordinator.registerPlayerHandshake(
                    lcaPlayerId,
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            65_536,
                            PARITY_HASH
                    ),
                    PARITY_HASH
            );
            assertTrue(negotiated.isLcaStreamActive());
            assertFalse(coordinator.getPlayerSession(vanillaPlayerId).isLcaStreamActive());

            EmbeddedChannel vanillaChannel = new EmbeddedChannel();
            EmbeddedChannel lcaChannel = new EmbeddedChannel();
            PaperLcaNettyChannelHandler vanillaHandler = PaperLcaNettyChannelHandler.injectIntoChannel(
                    vanillaChannel, coordinator, vanillaPlayerId, "minecraft:overworld", -4, 0, true, false);
            PaperLcaNettyChannelHandler lcaHandler = PaperLcaNettyChannelHandler.injectIntoChannel(
                    lcaChannel, coordinator, lcaPlayerId, "minecraft:overworld", -4, 1, true, false);

            // 1. RTT Congestion-Window Batch Start -> must pass straight through to BOTH channels!
            ChunkBatchPacingDescriptor batchStart = new ChunkBatchPacingDescriptor(false, 0);
            vanillaChannel.writeOutbound(batchStart);
            lcaChannel.writeOutbound(batchStart);
            assertSame(batchStart, vanillaChannel.readOutbound());
            assertSame(batchStart, lcaChannel.readOutbound());

            // 2. Outbound Full Chunk Packet -> Vanilla gets original packet; LCA_NET_V1 suppresses & releases it and emits EntryFullChunk!
            ClientboundLevelChunkWithLightPacket vanillaChunkPkt = new ClientboundLevelChunkWithLightPacket(3, -5);
            ClientboundLevelChunkWithLightPacket lcaSuppressedChunkPkt = new ClientboundLevelChunkWithLightPacket(3, -5);

            vanillaChannel.writeOutbound(vanillaChunkPkt);
            lcaChannel.writeOutbound(lcaSuppressedChunkPkt);

            assertSame(vanillaChunkPkt, vanillaChannel.readOutbound(), "Unnegotiated vanilla client must receive untouched chunk packet");
            assertFalse(vanillaChunkPkt.isDeallocated(), "Passed-through vanilla packet must retain refCnt == 1 for downstream encoder");
            vanillaChunkPkt.release();

            assertTrue(lcaSuppressedChunkPkt.isDeallocated(), "Suppressed vanilla packet on LCA channel must release ReferenceCounted immediately (REQ-RTP-S-002)");
            assertEquals(1L, lcaHandler.suppressedVanillaChunkPackets());

            ByteBuf lcaChunkBuf = assertInstanceOf(ByteBuf.class, lcaChannel.readOutbound());
            LcaOrderlessDictTable clientOdct = new LcaOrderlessDictTable(1);
            try {
                EmittedLcaNettyFrame decodedFrame = PaperLcaNettyChannelHandler.decodeEnvelopeFromByteBuf(lcaChunkBuf);
                assertEquals(EmittedLcaNettyFrame.KIND_CHUNK_BATCH, decodedFrame.frameKind());
                byte[] innerLcd2 = LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(decodedFrame.framedPayloadBytes());
                ClientboundLcaChunkBatchPayload decodedBatch =
                        LcaWireStreamProtocol.decodeChunkBatchPayload(innerLcd2, negotiated, clientOdct);
                EntryFullChunk receivedFull = assertInstanceOf(EntryFullChunk.class, decodedBatch.entries().get(0));
                assertEquals(3, receivedFull.chunkX());
                assertEquals(-5, receivedFull.chunkZ());
                assertEquals(2, receivedFull.sections().get(8).dirtyOverlayEntries().length,
                        "Negotiated client must receive SectionFrame slice + 2 sparse 4-byte dirty modifiers");
            } finally {
                lcaChunkBuf.release();
            }

            // 3. Rapid 100 overwrites of the same block + multi-block section update -> Vanilla gets 101 packets; LCA coalesces into 1 MTCF + ODCT + LCD2 frame on BatchFinished!
            for (int r = 0; r < 100; r++) {
                SingleBlockUpdateDescriptor edit = new SingleBlockUpdateDescriptor(48 + 2, 64 + 3, -80 + 4, 500 + r);
                vanillaChannel.writeOutbound(edit);
                lcaChannel.writeOneOutbound(edit);
                assertNotNull(vanillaChannel.readOutbound());
            }
            // Mojang SectionPos.sectionRelativePos packs (x << 8 | z << 4 | y): (7, 8, 9) -> (7 << 8) | (9 << 4) | 8
            short packedRelPos = (short) ((7 << 8) | (9 << 4) | 8);
            SectionMultiBlockUpdateDescriptor multiEdit = new SectionMultiBlockUpdateDescriptor(
                    3, 4, -5, new short[] {packedRelPos}, new int[] {901});
            vanillaChannel.writeOutbound(multiEdit);
            lcaChannel.writeOneOutbound(multiEdit);
            assertSame(multiEdit, vanillaChannel.readOutbound());
            assertNull(lcaChannel.readOutbound(), "Coalescing mode must hold block deltas until batch finish / flush");

            // BatchFinished passes through AND flushes the coalesced MTCF delta frame!
            ChunkBatchPacingDescriptor batchFinish = new ChunkBatchPacingDescriptor(true, 1);
            vanillaChannel.writeOutbound(batchFinish);
            lcaChannel.writeOutbound(batchFinish);
            assertSame(batchFinish, vanillaChannel.readOutbound());
            assertSame(batchFinish, lcaChannel.readOutbound());

            ByteBuf mtcfBuf = assertInstanceOf(ByteBuf.class, lcaChannel.readOutbound());
            try {
                EmittedLcaNettyFrame mtcfEnv = PaperLcaNettyChannelHandler.decodeEnvelopeFromByteBuf(mtcfBuf);
                assertEquals(EmittedLcaNettyFrame.KIND_MTCF_PATCH_DELTA, mtcfEnv.frameKind());
                byte[] mtcfRaw = LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(mtcfEnv.framedPayloadBytes());
                DecodedMultiTargetFrame decodedMtcf =
                        LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(mtcfRaw, clientOdct);
                assertTrue(decodedMtcf.targetsPlayerSlot(1));
                ClientboundLcaChunkBatchPayload deltaBatch = LcaWireStreamProtocol.decodeChunkBatchPayload(
                        decodedMtcf.sharedChunkBatchBytes(), negotiated, clientOdct);
                EntryPatchDelta patch = assertInstanceOf(EntryPatchDelta.class, deltaBatch.entries().get(0));
                int[] modifiers = patch.modifiedSections().get(0).dirtyOverlayEntries();
                assertEquals(2, modifiers.length, "100 overwrites of (2,3,4) + 1 edit at (7,8,9) must coalesce to 2 4-byte modifiers");
            } finally {
                mtcfBuf.release();
            }

            // 4. Unrestricted > 32 KiB Netty batch support (exceeding Bukkit's 32,760 B sendPluginMessage limit)
            byte[] large48KiBBatch = new byte[48 * 1024];
            Arrays.fill(large48KiBBatch, (byte) 0x4C);
            lcaHandler.sendDirectLcaPayload(
                    lcaChannel,
                    EmittedLcaNettyFrame.KIND_CHUNK_BATCH,
                    LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH,
                    large48KiBBatch
            );
            ByteBuf largeOut = assertInstanceOf(ByteBuf.class, lcaChannel.readOutbound());
            try {
                EmittedLcaNettyFrame decodedLarge = PaperLcaNettyChannelHandler.decodeEnvelopeFromByteBuf(largeOut);
                assertEquals(48 * 1024, decodedLarge.framedPayloadBytes().length);
                assertArrayEquals(large48KiBBatch, decodedLarge.framedPayloadBytes());
            } finally {
                largeOut.release();
            }

            // 5. Deterministic S-002 disconnect cleanup: closing channels removes NegotiatedSession & clears ODCT bitsets
            assertFalse(vanillaHandler.isCleanedUp());
            vanillaChannel.close();
            lcaChannel.close();
            assertTrue(vanillaHandler.isCleanedUp());
            assertTrue(lcaHandler.isCleanedUp());
            assertFalse(coordinator.getPlayerSession(lcaPlayerId).isLcaStreamActive(),
                    "Closing Netty channel must deterministically purge NegotiatedSession (REQ-RTP-S-002)");
            assertEquals(0, lcaHandler.playerOdctKnownSlots().knownSlotCount());
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Gate 1 (26.3 Wire Regression): NMS packet_encoder pipeline wraps LCA_NET_V1 frames in ClientboundCustomPayloadPacket instead of raw 0x4C (recipe_book_remove) ByteBuf and round-trips ODCT + MTCF into LcaClientWireReceiver")
    void nmsPacketEncoderWrapsCustomPayloadsWithout0x4cRecipeBookRemoveDesyncAndDecodesMtcfOnClient() throws Exception {
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(256));
        try (URLClassLoaderWithInMemoryNms nmsLoader = new URLClassLoaderWithInMemoryNms(
                PaperLcaNettyChannelHandlerTest.class.getClassLoader())) {
            LcaSectionBacking[] sections = new LcaSectionBacking[24];
            SplittableRandom rng = new SplittableRandom(0x26_3_4C434E31L);
            for (int s = 0; s < 24; s++) {
                int[] vox = new int[4096];
                Arrays.fill(vox, s < 12 ? 1 : 0);
                if (s == 8 || s == 9) {
                    // Identical non-uniform motif across sections 8 & 9 exercises ODCT slot learning + reuse
                    for (int i = 0; i < 48; i++) {
                        vox[i * 17] = 14 + (i & 3);
                    }
                } else if (s < 12) {
                    vox[rng.nextInt(4096)] = 22;
                }
                sections[s] = LcaSectionBacking.fromDirectVoxels(
                        vox, 15, 0, 4096, 0, 0, id -> id != 0, id -> false, id -> false);
            }
            TrackedChunkColumn spawnCol = coordinator.attachChunkColumn(
                    "world", 0, 0, sections, null, 100L, false);
            TrackedChunkColumn neighborCol = coordinator.attachChunkColumn(
                    "world", 1, 0, sections.clone(), null, 101L, false);
            assertNotNull(spawnCol);
            assertNotNull(neighborCol);

            UUID playerId = UUID.randomUUID();
            net.leaf.anvil.common.net.LcaClientWireReceiver clientReceiver =
                    new net.leaf.anvil.common.net.LcaClientWireReceiver();
            byte[] clientHsBytes = clientReceiver.buildClientHandshakeBytes(PARITY_HASH);

            // Build Netty pipeline mirroring Paper 26.3: [packet_encoder (MessageToByteEncoder<Packet>)]
            io.netty.channel.ChannelOutboundHandler packetEncoder = nmsLoader.newPacketEncoderInstance();
            EmbeddedChannel serverChannel = new EmbeddedChannel();
            serverChannel.pipeline().addLast("packet_encoder", packetEncoder);

            PaperLcaNettyChannelHandler handler = PaperLcaNettyChannelHandler.injectIntoChannel(
                    serverChannel, coordinator, playerId, "world", -4, 0, true, true);

            // 1. Handshake + initial batch via PaperLeafAnvilPlugin#handleHandshakeAndPackInitialBatch -> sendDirectLcaPayload
            NegotiatedSession serverSession = PaperLeafAnvilPlugin.handleHandshakeAndPackInitialBatch(
                    coordinator,
                    playerId,
                    "world",
                    clientHsBytes,
                    PARITY_HASH,
                    (outChannel, payloadBytes) -> {
                        byte kind = LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(outChannel)
                                ? EmittedLcaNettyFrame.KIND_HANDSHAKE_ACK
                                : EmittedLcaNettyFrame.KIND_CHUNK_BATCH;
                        handler.sendDirectLcaPayload(serverChannel, kind, outChannel, payloadBytes);
                    },
                    true
            );
            assertTrue(serverSession.isLcaStreamActive());
            serverChannel.runPendingTasks();

            // Client PacketDecoder reads wire frames: leading byte 0x4C (76) is 'clientbound/minecraft:recipe_book_remove' in 26.3!
            DecodedPlayCustomPayload hsWirePkt = decodeMinecraft26_3PlayPacket(serverChannel.readOutbound());
            assertEquals(LcaWireStreamProtocol.CHANNEL_HANDSHAKE, hsWirePkt.channel());
            assertTrue(clientReceiver.onServerHandshakeAck(hsWirePkt.data(), PARITY_HASH).isLcaStreamActive());

            DecodedPlayCustomPayload initialBatchPkt = decodeMinecraft26_3PlayPacket(serverChannel.readOutbound());
            assertEquals(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, initialBatchPkt.channel());
            int initialCommitted = clientReceiver.onChunkBatchPayloadReceived(
                    "minecraft:overworld", initialBatchPkt.data(), true, PARITY_HASH, null);
            assertTrue(initialCommitted >= 1, "Initial handshake batch must commit on client receiver");

            // Shared KnownSlotBitSet invariant: slots learned during handleHandshakeAndPackInitialBatch must be visible to handler!
            assertSame(
                    coordinator.getOrCreatePlayerKnownDictSlots(playerId),
                    handler.playerOdctKnownSlots(),
                    "PaperLcaNettyChannelHandler must share coordinator KnownSlotBitSet with initial handshake batch"
            );
            assertTrue(handler.playerOdctKnownSlots().knownSlotCount() > 0);

            // 2. Outbound FullChunkDescriptor through Netty write -> must encode as ClientboundCustomPayloadPacket (not 0x4C!)
            ClientboundLevelChunkWithLightPacket chunkPkt = new ClientboundLevelChunkWithLightPacket(1, 0);
            serverChannel.writeOutbound(chunkPkt);
            assertTrue(chunkPkt.isDeallocated());
            DecodedPlayCustomPayload streamedChunkPkt = decodeMinecraft26_3PlayPacket(serverChannel.readOutbound());
            assertEquals(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, streamedChunkPkt.channel());
            assertEquals(1, clientReceiver.onChunkBatchPayloadReceived(
                    "minecraft:overworld", streamedChunkPkt.data(), true, PARITY_HASH, null));

            // 3. Block update + ChunkBatchFinished -> flushes MTCF frame on CHANNEL_CHUNK_BATCH and client receiver must unwrap MTCF cleanly!
            serverChannel.writeOneOutbound(new SingleBlockUpdateDescriptor(16 + 3, 64 + 2, 5, 333));
            serverChannel.writeOutbound(new ChunkBatchPacingDescriptor(true, 1));
            assertInstanceOf(ChunkBatchPacingDescriptor.class, serverChannel.readOutbound());

            DecodedPlayCustomPayload mtcfDeltaPkt = decodeMinecraft26_3PlayPacket(serverChannel.readOutbound());
            assertEquals(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, mtcfDeltaPkt.channel());
            int deltaCommitted = clientReceiver.onChunkBatchPayloadReceived(
                    "minecraft:overworld", mtcfDeltaPkt.data(), true, PARITY_HASH, null);
            assertEquals(1, deltaCommitted, "Client chunk batch receiver must unwrap MTCF patch delta without LCD2 magic failure");
            assertEquals(0L, clientReceiver.snapshotTelemetry().failedBatches());
            assertEquals(
                    333,
                    clientReceiver.coordinator().getTrackedChunk("minecraft:overworld", 1, 0).section(8).getStateId(3, 2, 5),
                    "Coalesced MTCF block mutation must apply to client tracked column"
            );

            serverChannel.close();
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    private record DecodedPlayCustomPayload(String channel, byte[] data) {}

    private static final int PLAY_PACKET_ID_CUSTOM_PAYLOAD_26_3 = 0x19;
    private static final int PLAY_PACKET_ID_RECIPE_BOOK_REMOVE_26_3 = 0x4C; // 'L' in "LCN1" (0x4C434E31)

    private static DecodedPlayCustomPayload decodeMinecraft26_3PlayPacket(Object outboundMsg) {
        ByteBuf wireBuf = assertInstanceOf(
                ByteBuf.class, outboundMsg, "Downstream of packet_encoder must be a wire ByteBuf");
        try {
            int packetId = readVarInt(wireBuf);
            if (packetId == PLAY_PACKET_ID_RECIPE_BOOK_REMOVE_26_3) {
                throw new IllegalStateException(
                        "Failed to decode packet 'clientbound/minecraft:recipe_book_remove'");
            }
            assertEquals(
                    PLAY_PACKET_ID_CUSTOM_PAYLOAD_26_3,
                    packetId,
                    "Expected Play clientbound/minecraft:custom_payload packet ID, got 0x" + Integer.toHexString(packetId)
            );
            int chanLen = readVarInt(wireBuf);
            byte[] chanUtf8 = new byte[chanLen];
            wireBuf.readBytes(chanUtf8);
            int dataLen = wireBuf.readableBytes();
            byte[] payload = new byte[dataLen];
            wireBuf.readBytes(payload);
            return new DecodedPlayCustomPayload(
                    new String(chanUtf8, java.nio.charset.StandardCharsets.UTF_8), payload);
        } finally {
            wireBuf.release();
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            buf.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.writeByte(v);
    }

    private static int readVarInt(ByteBuf buf) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buf.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    /**
     * Synthesizes Paper 26.3's exact NMS custom-payload hierarchy (`Identifier`,
     * `CustomPacketPayload`, `DiscardedPayload`, `ClientboundCustomPayloadPacket`, and a
     * `MessageToByteEncoder`-equivalent `PacketEncoder` that skips raw `ByteBuf`s) in memory using
     * standard JDK `javax.tools.JavaCompiler` so `:leafanvil-paper` tests verify real Netty
     * `PacketEncoder` behavior deterministically without depending on a Paperclip server jar.
     */
    private static final class URLClassLoaderWithInMemoryNms extends java.net.URLClassLoader {
        private final java.nio.file.Path tempClassesDir;

        URLClassLoaderWithInMemoryNms(ClassLoader parent) throws Exception {
            super(new java.net.URL[0], parent);
            this.tempClassesDir = java.nio.file.Files.createTempDirectory("leafanvil-nms-26-3-test");
            compileNmsStubs(this.tempClassesDir);
            addURL(this.tempClassesDir.toUri().toURL());
        }

        io.netty.channel.ChannelOutboundHandler newPacketEncoderInstance() throws Exception {
            Class<?> encCls = loadClass("net.minecraft.network.PacketEncoder");
            return (io.netty.channel.ChannelOutboundHandler) encCls.getConstructor().newInstance();
        }

        @Override
        public void close() throws java.io.IOException {
            super.close();
            try (var walk = java.nio.file.Files.walk(tempClassesDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
        }

        private static void compileNmsStubs(java.nio.file.Path outDir) throws Exception {
            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "JDK JavaCompiler required for NMS protocol stub compilation");
            java.nio.file.Path srcDir = outDir.resolve("src");
            writeSource(srcDir, "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public final class Identifier {
                        private final String value;
                        private Identifier(String value) { this.value = value; }
                        public static Identifier parse(String value) { return new Identifier(value); }
                        public static Identifier tryParse(String value) { return new Identifier(value); }
                        @Override public String toString() { return value; }
                    }
                    """);
            writeSource(srcDir, "net/minecraft/network/protocol/Packet.java", """
                    package net.minecraft.network.protocol;
                    public interface Packet<T> {}
                    """);
            writeSource(srcDir, "net/minecraft/network/protocol/common/custom/CustomPacketPayload.java", """
                    package net.minecraft.network.protocol.common.custom;
                    public interface CustomPacketPayload {}
                    """);
            writeSource(srcDir, "net/minecraft/network/protocol/common/custom/DiscardedPayload.java", """
                    package net.minecraft.network.protocol.common.custom;
                    import net.minecraft.resources.Identifier;
                    public record DiscardedPayload(Identifier id, byte[] data) implements CustomPacketPayload {}
                    """);
            writeSource(srcDir, "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket.java", """
                    package net.minecraft.network.protocol.common;
                    import net.minecraft.network.protocol.Packet;
                    import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
                    public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<Object> {}
                    """);
            writeSource(srcDir, "net/minecraft/network/PacketEncoder.java", """
                    package net.minecraft.network;
                    import io.netty.buffer.ByteBuf;
                    import io.netty.channel.ChannelHandlerContext;
                    import io.netty.channel.ChannelOutboundHandlerAdapter;
                    import io.netty.channel.ChannelPromise;
                    import java.nio.charset.StandardCharsets;
                    import net.minecraft.network.protocol.Packet;
                    import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
                    import net.minecraft.network.protocol.common.custom.DiscardedPayload;
                    public final class PacketEncoder extends ChannelOutboundHandlerAdapter {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                            // Mirrors Mojang MessageToByteEncoder<Packet<?>>: non-Packet ByteBufs bypass PacketEncoder!
                            if (!(msg instanceof Packet<?>)) {
                                super.write(ctx, msg, promise);
                                return;
                            }
                            if (msg instanceof ClientboundCustomPayloadPacket custom
                                    && custom.payload() instanceof DiscardedPayload disc) {
                                byte[] chanBytes = disc.id().toString().getBytes(StandardCharsets.UTF_8);
                                byte[] data = disc.data();
                                ByteBuf out = ctx.alloc().buffer(8 + chanBytes.length + data.length);
                                writeVarInt(out, 0x19);
                                writeVarInt(out, chanBytes.length);
                                out.writeBytes(chanBytes);
                                out.writeBytes(data);
                                ctx.write(out, promise);
                                return;
                            }
                            super.write(ctx, msg, promise);
                        }
                        private static void writeVarInt(ByteBuf buf, int value) {
                            int v = value;
                            while ((v & ~0x7F) != 0) {
                                buf.writeByte((v & 0x7F) | 0x80);
                                v >>>= 7;
                            }
                            buf.writeByte(v);
                        }
                    }
                    """);
            java.util.List<String> files = new java.util.ArrayList<>();
            try (var walk = java.nio.file.Files.walk(srcDir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
            }
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-classpath");
            args.add(System.getProperty("java.class.path"));
            args.add("-d");
            args.add(outDir.toString());
            args.addAll(files);
            int rc = compiler.run(null, null, null, args.toArray(String[]::new));
            assertEquals(0, rc, "In-memory NMS 26.3 stub compilation must succeed");
        }

        private static void writeSource(java.nio.file.Path srcRoot, String rel, String code) throws java.io.IOException {
            java.nio.file.Path f = srcRoot.resolve(rel);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, code, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
