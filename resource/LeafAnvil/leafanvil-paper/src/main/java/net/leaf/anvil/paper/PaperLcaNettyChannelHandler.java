package net.leaf.anvil.paper;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.ConsolidatedMultiTargetFrame;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator.TargetRecipient;
import net.leaf.anvil.common.net.LcaOrderlessDictTable;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.KnownSlotBitSet;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ChunkDigestEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-connection Netty {@link ChannelDuplexHandler} on Paper/Folia routing outbound voxel packets
 * conditionally between unmodified vanilla clients and negotiated `LCA_NET_V1` clients
 * (`leafanvil-ADR-005` Workstream 4, `leafanvil-ADR-003`, `leafanvil-ADR-004`, `leafanvil-ADR-006`).
 *
 * <ul>
 *   <li><b>Vanilla clients:</b> Passes `ClientboundLevelChunkWithLightPacket`,
 *       `ClientboundBlockUpdatePacket`, and `ClientboundSectionBlocksUpdatePacket` straight through
 *       untouched (and provides thread-local $O(D)$ sparse dirty-map stamping over `int[4096]`
 *       scratch when shell-evicted).</li>
 *   <li><b>Negotiated `LCA_NET_V1` clients:</b> Suppresses those three vanilla voxel packets
 *       (releasing any `ReferenceCounted` buffers per `REQ-RTP-S-002`), preserves
 *       `ClientboundChunkBatchStartPacket` / `ClientboundChunkBatchFinishedPacket` /
 *       `ServerboundChunkBatchReceivedPacket` RTT congestion pacing, and emits `EntryFullChunk`
 *       (`SectionFrame` slices + `4-byte` sparse dirty modifiers) and coalesced `EntryPatchDelta`
 *       frames using `LcaOrderlessDictTable` (`ODCT`), `LcaMultiTargetConsolidator` (`MTCF`), and
 *       `LCD2` (`0x4C44`) without Bukkit's `32,760 B` plugin-message ceiling.</li>
 * </ul>
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
public final class PaperLcaNettyChannelHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "leafanvil_lca_wire_handler";
    public static final int LCA_NETTY_WIRE_MAGIC = 0x4C434E31; // "LCN1"
    public static final int MAX_VANILLA_CUSTOM_PAYLOAD_BYTES = 1_048_576; // 1 MiB Play ClientboundCustomPayloadPacket ceiling

    private static final Logger LOGGER = Logger.getLogger(PaperLcaNettyChannelHandler.class.getName());
    private static final ThreadLocal<int[]> VANILLA_SHELL_SCRATCH_4096 =
            ThreadLocal.withInitial(() -> new int[4096]);
    private static final ClassLoader BOOTSTRAP_LOADER_SENTINEL = new ClassLoader(null) {};
    private static final Map<ClassLoader, Optional<CustomPayloadPacketFactory>> CUSTOM_PAYLOAD_FACTORY_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Protocol-level packet adapter allowing both reflective NMS packets (`1.20.4`–`26.1+`) and
     * headless Netty `EmbeddedChannel` fixtures to supply coordinates and block deltas cleanly.
     */
    public sealed interface OutboundVoxelDescriptor
            permits FullChunkDescriptor, SingleBlockUpdateDescriptor, SectionMultiBlockUpdateDescriptor, ChunkBatchPacingDescriptor {}

    public record FullChunkDescriptor(int chunkX, int chunkZ) implements OutboundVoxelDescriptor {}

    public record SingleBlockUpdateDescriptor(int blockX, int blockY, int blockZ, int runtimeStateId)
            implements OutboundVoxelDescriptor {}

    public record SectionMultiBlockUpdateDescriptor(
            int sectionX,
            int sectionY,
            int sectionZ,
            short[] packedLocalPositions12,
            int[] runtimeStateIds
    ) implements OutboundVoxelDescriptor {}

    public record ChunkBatchPacingDescriptor(boolean batchFinished, int batchSize)
            implements OutboundVoxelDescriptor {}

    /**
     * Decoded outbound `LCA_NET_V1` wire frame emitted onto a negotiated Netty channel (`ByteBuf`
     * `[int magic = LCN1, byte frameKind, utf8 channel, int payloadLen, byte[] payload]`).
     */
    public record EmittedLcaNettyFrame(
            byte frameKind,
            String channel,
            byte[] framedPayloadBytes
    ) {
        public static final byte KIND_CHUNK_BATCH = 1;
        public static final byte KIND_MTCF_PATCH_DELTA = 2;
        public static final byte KIND_HANDSHAKE_ACK = 3;
    }

    private final LcaInMemoryChunkCoordinator coordinator;
    private final UUID playerId;
    private volatile String worldName;
    private final int minSectionY;
    private final int playerBitSlot;
    private final boolean coalesceTickFlushes;
    private final boolean proxySafeStoredZlib;
    private final KnownSlotBitSet playerOdctKnownSlots;
    private volatile CustomPayloadPacketFactory customPayloadFactory;
    private volatile boolean customPayloadFactoryProbed;

    private final Object pendingLock = new Object();
    // Coalesces rapid in-tick mutations to the same coordinate per (chunkKey, sectionIdx) -> (pos12 -> stateId19)
    private final Map<Long, Map<Integer, Map<Integer, Integer>>> pendingChunkSectionDeltas = new LinkedHashMap<>();
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private final AtomicLong suppressedVanillaChunkPackets = new AtomicLong();
    private final AtomicLong suppressedVanillaDeltaPackets = new AtomicLong();
    private final AtomicLong emittedLcaFrames = new AtomicLong();
    private final AtomicLong emittedLcaWireBytes = new AtomicLong();

    public PaperLcaNettyChannelHandler(
            LcaInMemoryChunkCoordinator coordinator,
            UUID playerId,
            String worldName,
            int minSectionY,
            int playerBitSlot,
            boolean coalesceTickFlushes,
            boolean proxySafeStoredZlib
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.worldName = worldName != null ? worldName : "minecraft:overworld";
        this.minSectionY = minSectionY;
        this.playerBitSlot = Math.max(0, playerBitSlot);
        this.coalesceTickFlushes = coalesceTickFlushes;
        this.proxySafeStoredZlib = proxySafeStoredZlib;
        this.playerOdctKnownSlots = coordinator.getOrCreatePlayerKnownDictSlots(playerId);
    }

    public UUID playerId() {
        return playerId;
    }

    public String worldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            this.worldName = worldName;
        }
    }

    public KnownSlotBitSet playerOdctKnownSlots() {
        return playerOdctKnownSlots;
    }

    public long suppressedVanillaChunkPackets() {
        return suppressedVanillaChunkPackets.get();
    }

    public long suppressedVanillaDeltaPackets() {
        return suppressedVanillaDeltaPackets.get();
    }

    public long emittedLcaFrames() {
        return emittedLcaFrames.get();
    }

    public long emittedLcaWireBytes() {
        return emittedLcaWireBytes.get();
    }

    public boolean isCleanedUp() {
        return cleanedUp.get();
    }

    /**
     * Stamps a shell-evicted {@link LcaSectionBacking}'s cold `baseFrame` + flat sparse dirty map
     * in $O(D)$ onto a thread-local `int[4096]` scratch array for unnegotiated vanilla serialization.
     */
    public static int[] stampShellEvictedSectionForVanilla(LcaSectionBacking backing) {
        Objects.requireNonNull(backing, "backing");
        int[] scratch = VANILLA_SHELL_SCRATCH_4096.get();
        backing.materializeEphemeralStateIds(scratch, null);
        for (int entry32 : backing.exportMergedDirtyOverlayEntries()) {
            if (entry32 != 0) {
                int pos12 = LcaSectionBacking.unpackPos12(entry32) & 4095;
                int stateId = LcaSectionBacking.unpackStateId19(entry32);
                scratch[pos12] = stateId;
            }
        }
        return scratch;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg == null) {
            super.write(ctx, null, promise);
            return;
        }

        NegotiatedSession session = coordinator.getPlayerSession(playerId);
        if (!session.isLcaStreamActive()) {
            // Unnegotiated vanilla client: pass all packets straight through untouched!
            super.write(ctx, msg, promise);
            return;
        }

        OutboundVoxelDescriptor desc = inspectOutboundPacket(msg);
        if (desc == null) {
            super.write(ctx, msg, promise);
            return;
        }

        if (desc instanceof ChunkBatchPacingDescriptor pacing) {
            // Preserve Minecraft RTT congestion-window pacing packets on negotiated channels!
            super.write(ctx, msg, promise);
            if (pacing.batchFinished()) {
                flushCoalescedPatchDeltas(ctx);
            }
            return;
        }

        if (desc instanceof FullChunkDescriptor fullChunk) {
            TrackedChunkColumn col = coordinator.getTrackedChunk(worldName, fullChunk.chunkX(), fullChunk.chunkZ());
            if (col == null || col.isUnloaded()) {
                // Fail-safe (REQ-RTP-S-004): never drop terrain if column is untracked; pass vanilla packet through
                super.write(ctx, msg, promise);
                return;
            }
            ReferenceCountUtil.release(msg);
            suppressedVanillaChunkPackets.incrementAndGet();
            try {
                Object wireMsg = encodeSingleOrRevisitChunkWireMessage(ctx, col, session);
                if (wireMsg != null) {
                    ctx.write(wireMsg, promise);
                } else {
                    promise.trySuccess();
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[LeafAnvil Netty] Failed encoding LCA_NET_V1 full chunk ("
                        + fullChunk.chunkX() + "," + fullChunk.chunkZ() + "): " + t.getMessage(), t);
                promise.tryFailure(t);
            }
            return;
        }

        if (desc instanceof SingleBlockUpdateDescriptor singleEdit) {
            int cx = singleEdit.blockX() >> 4;
            int cz = singleEdit.blockZ() >> 4;
            TrackedChunkColumn col = coordinator.getTrackedChunk(worldName, cx, cz);
            if (col == null || col.isUnloaded()) {
                super.write(ctx, msg, promise);
                return;
            }
            int secIdx = (singleEdit.blockY() >> 4) - minSectionY;
            if (secIdx < 0 || secIdx >= col.sectionCount()) {
                super.write(ctx, msg, promise);
                return;
            }
            ReferenceCountUtil.release(msg);
            suppressedVanillaDeltaPackets.incrementAndGet();
            int pos12 = LcaSectionBacking.pos12(
                    singleEdit.blockX() & 15, singleEdit.blockY() & 15, singleEdit.blockZ() & 15);
            recordPendingDelta(cx, cz, secIdx, pos12, singleEdit.runtimeStateId());
            if (!coalesceTickFlushes) {
                flushCoalescedPatchDeltas(ctx, promise);
            } else {
                promise.trySuccess();
            }
            return;
        }

        if (desc instanceof SectionMultiBlockUpdateDescriptor multiEdit) {
            int cx = multiEdit.sectionX();
            int cz = multiEdit.sectionZ();
            TrackedChunkColumn col = coordinator.getTrackedChunk(worldName, cx, cz);
            int secIdx = multiEdit.sectionY() - minSectionY;
            if (col == null || col.isUnloaded() || secIdx < 0 || secIdx >= col.sectionCount()) {
                super.write(ctx, msg, promise);
                return;
            }
            ReferenceCountUtil.release(msg);
            suppressedVanillaDeltaPackets.incrementAndGet();
            short[] positions = multiEdit.packedLocalPositions12();
            int[] states = multiEdit.runtimeStateIds();
            int count = Math.min(positions != null ? positions.length : 0, states != null ? states.length : 0);
            for (int i = 0; i < count; i++) {
                int rawShort = positions[i] & 0x0FFF;
                // Mojang SectionPos.sectionRelativePos packs (x << 8 | z << 4 | y)
                int x = (rawShort >>> 8) & 15;
                int z = (rawShort >>> 4) & 15;
                int y = rawShort & 15;
                recordPendingDelta(cx, cz, secIdx, LcaSectionBacking.pos12(x, y, z), states[i]);
            }
            if (!coalesceTickFlushes) {
                flushCoalescedPatchDeltas(ctx, promise);
            } else {
                promise.trySuccess();
            }
            return;
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        NegotiatedSession session = coordinator.getPlayerSession(playerId);
        if (session.isLcaStreamActive()) {
            flushCoalescedPatchDeltas(ctx);
        }
        super.flush(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ServerboundLcaChunkDigestPayload digest) {
            coordinator.recordPlayerRegionDigest(playerId, digest);
            return;
        }
        // ServerboundChunkBatchReceivedPacket and all other serverbound packets pass straight through
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanupSessionAndBuffers();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cleanupSessionAndBuffers();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(
                Level.WARNING,
                "[LeafAnvil Netty] Pipeline fault on channel for player " + playerId + ": " + cause.getMessage(),
                cause
        );
        cleanupSessionAndBuffers();
        ctx.close();
    }

    private void cleanupSessionAndBuffers() {
        if (cleanedUp.compareAndSet(false, true)) {
            synchronized (pendingLock) {
                pendingChunkSectionDeltas.clear();
            }
            playerOdctKnownSlots.clear();
            coordinator.removePlayerSession(playerId);
        }
    }

    private void recordPendingDelta(int chunkX, int chunkZ, int sectionIdx, int pos12, int runtimeStateId) {
        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFF_FFFFL);
        synchronized (pendingLock) {
            Map<Integer, Map<Integer, Integer>> bySec =
                    pendingChunkSectionDeltas.computeIfAbsent(chunkKey, k -> new LinkedHashMap<>(4));
            Map<Integer, Integer> cells =
                    bySec.computeIfAbsent(sectionIdx, k -> new LinkedHashMap<>(8));
            cells.put(pos12 & 4095, runtimeStateId & 0x7FFFF);
        }
    }

    public void flushCoalescedPatchDeltas(ChannelHandlerContext ctx) {
        flushCoalescedPatchDeltas(ctx, null);
    }

    private void flushCoalescedPatchDeltas(ChannelHandlerContext ctx, ChannelPromise optionalPromise) {
        Map<Long, Map<Integer, Map<Integer, Integer>>> drained;
        synchronized (pendingLock) {
            if (pendingChunkSectionDeltas.isEmpty()) {
                if (optionalPromise != null) {
                    optionalPromise.trySuccess();
                }
                return;
            }
            drained = new LinkedHashMap<>(pendingChunkSectionDeltas);
            pendingChunkSectionDeltas.clear();
        }

        NegotiatedSession session = coordinator.getPlayerSession(playerId);
        if (!session.isLcaStreamActive()) {
            if (optionalPromise != null) {
                optionalPromise.trySuccess();
            }
            return;
        }

        try {
            // Group EntryPatchDelta entries by (regionX, regionZ) and consolidate through ODCT + MTCF + LCD2
            Map<Long, List<BatchChunkEntry>> byRegion = new LinkedHashMap<>();
            for (Map.Entry<Long, Map<Integer, Map<Integer, Integer>>> chunkEntry : drained.entrySet()) {
                long cKey = chunkEntry.getKey();
                int cx = (int) (cKey >> 32);
                int cz = (int) cKey;
                TrackedChunkColumn col = coordinator.getTrackedChunk(worldName, cx, cz);
                long tick = col != null ? col.lastUpdateTick() : 1L;
                long secDed = col != null ? col.toWireFullChunkEntry(tick).secDedSyndrome64() : 0L;

                List<SectionWireSlice> deltaSlices = new ArrayList<>(chunkEntry.getValue().size());
                int dirtyMask32 = 0;
                for (int secIdx = 0; secIdx < 32; secIdx++) {
                    Map<Integer, Integer> cells = chunkEntry.getValue().get(secIdx);
                    if (cells == null || cells.isEmpty()) {
                        continue;
                    }
                    dirtyMask32 |= (1 << secIdx);
                    int[] packedModifiers = new int[cells.size()];
                    int idx = 0;
                    for (Map.Entry<Integer, Integer> cell : cells.entrySet()) {
                        packedModifiers[idx++] = LcaSectionBacking.packEntry32(cell.getKey(), cell.getValue());
                    }
                    deltaSlices.add(SectionWireSlice.nonUniform(secIdx, new byte[0], packedModifiers));
                }
                if (dirtyMask32 == 0) {
                    continue;
                }
                int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(cx, cz);
                EntryPatchDelta patchDelta = new EntryPatchDelta(localIdx10, tick, secDed, dirtyMask32, deltaSlices);
                long regKey = (((long) (cx >> 5)) << 32) | ((cz >> 5) & 0xFFFF_FFFFL);
                byRegion.computeIfAbsent(regKey, k -> new ArrayList<>(4)).add(patchDelta);
            }

            List<Map.Entry<Long, List<BatchChunkEntry>>> regionBatches = new ArrayList<>(byRegion.entrySet());
            for (int r = 0; r < regionBatches.size(); r++) {
                Map.Entry<Long, List<BatchChunkEntry>> reg = regionBatches.get(r);
                int rx = (int) (reg.getKey() >> 32);
                int rz = (int) reg.getKey().longValue();
                ClientboundLcaChunkBatchPayload batchPayload =
                        new ClientboundLcaChunkBatchPayload(rx, rz, reg.getValue());
                byte[] lcd2Batch = LcaWireStreamProtocol.encodeChunkBatchPayload(
                        batchPayload,
                        session,
                        coordinator.orderlessDictTable(),
                        playerOdctKnownSlots
                );
                TargetRecipient recipient = new TargetRecipient(playerBitSlot, playerId, playerOdctKnownSlots);
                ConsolidatedMultiTargetFrame mtcf = LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                        List.of(recipient),
                        coordinator.orderlessDictTable(),
                        LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                        List.of(),
                        List.of(),
                        lcd2Batch,
                        Math.max(64, lcd2Batch.length * 4)
                );
                byte[] wireBytes = (proxySafeStoredZlib && session.isProxySafeStoredZlibEnabled())
                        ? LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(mtcf.sharedWireFrameBytes())
                        : LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(mtcf.sharedWireFrameBytes());
                Object outMsg = allocateOutboundWireMessage(
                        ctx, EmittedLcaNettyFrame.KIND_MTCF_PATCH_DELTA, LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, wireBytes);
                boolean isLast = (r == regionBatches.size() - 1);
                if (outMsg == null) {
                    if (isLast && optionalPromise != null) {
                        optionalPromise.trySuccess();
                    }
                    continue;
                }
                if (isLast && optionalPromise != null) {
                    ctx.write(outMsg, optionalPromise);
                } else {
                    ctx.write(outMsg);
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[LeafAnvil Netty] Failed flushing coalesced MTCF patch deltas: " + t.getMessage(), t);
            if (optionalPromise != null) {
                optionalPromise.tryFailure(t);
            }
        }
    }

    private Object encodeSingleOrRevisitChunkWireMessage(
            ChannelHandlerContext ctx,
            TrackedChunkColumn col,
            NegotiatedSession session
    ) throws IOException {
        int rx = col.chunkX() >> 5;
        int rz = col.chunkZ() >> 5;
        EntryFullChunk full = col.toWireFullChunkEntry(col.lastUpdateTick());
        ServerboundLcaChunkDigestPayload digest =
                coordinator.getPlayerRegionDigest(playerId, worldName, rx, rz);
        ChunkDigestEntry digestEntry = (digest != null && session.isDeltaRevisitCacheEnabled())
                ? digest.findChunk(full.localChunkIndex10())
                : null;
        BatchChunkEntry resolved = LcaWireStreamProtocol.resolveServerRevisitEntry(
                full,
                digestEntry,
                col.baselineSecDed64(),
                col.dirtySectionBitmask32()
        );
        ClientboundLcaChunkBatchPayload batch = new ClientboundLcaChunkBatchPayload(rx, rz, List.of(resolved));
        byte[] lcd2Payload;
        synchronized (playerOdctKnownSlots) {
            lcd2Payload = LcaWireStreamProtocol.encodeChunkBatchPayload(
                    batch,
                    session,
                    coordinator.orderlessDictTable(),
                    playerOdctKnownSlots
            );
        }
        byte[] framed = (proxySafeStoredZlib && session.isProxySafeStoredZlibEnabled())
                ? LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(lcd2Payload)
                : LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(lcd2Payload);
        return allocateOutboundWireMessage(
                ctx, EmittedLcaNettyFrame.KIND_CHUNK_BATCH, LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, framed);
    }

    /**
     * Writes an arbitrary-sized (`> 32,760 B` supported, up to `1 MiB` per `ClientboundCustomPayloadPacket`)
     * `LCA_NET_V1` frame onto the player's Netty channel, routing through Paper's `PacketEncoder`
     * when present so vanilla/Fabric/NeoForge `PacketDecoder` never misinterprets raw magic bytes.
     */
    public void sendDirectLcaPayload(Channel channel, byte frameKind, String lcaChannel, byte[] framedBytes) {
        if (channel == null || !channel.isActive() || framedBytes == null) {
            return;
        }
        Runnable sendTask = () -> {
            if (!channel.isActive()) {
                return;
            }
            Object wireMsg = buildOutboundWireMessageForPipeline(
                    channel.pipeline(), channel.alloc(), frameKind, lcaChannel, framedBytes);
            if (wireMsg != null) {
                channel.writeAndFlush(wireMsg);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            sendTask.run();
        } else {
            channel.eventLoop().execute(sendTask);
        }
    }

    private Object allocateOutboundWireMessage(
            ChannelHandlerContext ctx, byte frameKind, String lcaChannel, byte[] framedBytes
    ) {
        return buildOutboundWireMessageForPipeline(
                ctx.pipeline(), ctx.alloc(), frameKind, lcaChannel, framedBytes);
    }

    private Object buildOutboundWireMessageForPipeline(
            ChannelPipeline pipeline,
            io.netty.buffer.ByteBufAllocator alloc,
            byte frameKind,
            String lcaChannel,
            byte[] framedBytes
    ) {
        CustomPayloadPacketFactory factory = resolveCustomPayloadFactoryForPipeline(pipeline);
        if (factory != null) {
            if (framedBytes.length > MAX_VANILLA_CUSTOM_PAYLOAD_BYTES) {
                LOGGER.log(
                        Level.WARNING,
                        "[LeafAnvil Netty] Dropping oversized LCA_NET_V1 custom payload on "
                                + lcaChannel + " (" + framedBytes.length + " B > "
                                + MAX_VANILLA_CUSTOM_PAYLOAD_BYTES + " B)");
                return null;
            }
            Object nmsPacket = factory.createPacket(lcaChannel, framedBytes);
            if (nmsPacket != null) {
                emittedLcaFrames.incrementAndGet();
                emittedLcaWireBytes.addAndGet(framedBytes.length);
                return nmsPacket;
            }
        }
        emittedLcaFrames.incrementAndGet();
        emittedLcaWireBytes.addAndGet(framedBytes.length);
        ByteBuf buf = alloc.buffer(framedBytes.length + 48);
        return encodeEnvelopeToByteBuf(buf, frameKind, lcaChannel, framedBytes);
    }

    private CustomPayloadPacketFactory resolveCustomPayloadFactoryForPipeline(ChannelPipeline pipeline) {
        CustomPayloadPacketFactory cached = this.customPayloadFactory;
        if (cached != null || this.customPayloadFactoryProbed) {
            return cached;
        }
        CustomPayloadPacketFactory resolved = probePipelineCustomPayloadFactory(pipeline);
        this.customPayloadFactory = resolved;
        this.customPayloadFactoryProbed = true;
        return resolved;
    }

    public static ByteBuf encodeEnvelopeToByteBuf(
            ByteBuf buf, byte frameKind, String lcaChannel, byte[] framedBytes
    ) {
        byte[] chanUtf8 = lcaChannel.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(LCA_NETTY_WIRE_MAGIC);
        buf.writeByte(frameKind);
        buf.writeShort(chanUtf8.length);
        buf.writeBytes(chanUtf8);
        buf.writeInt(framedBytes.length);
        buf.writeBytes(framedBytes);
        return buf;
    }

    public static EmittedLcaNettyFrame decodeEnvelopeFromByteBuf(ByteBuf buf) {
        int magic = buf.readInt();
        if (magic != LCA_NETTY_WIRE_MAGIC) {
            throw new IllegalArgumentException("Unexpected LCA Netty magic: 0x" + Integer.toHexString(magic));
        }
        byte kind = buf.readByte();
        int chanLen = buf.readUnsignedShort();
        byte[] chanBytes = new byte[chanLen];
        buf.readBytes(chanBytes);
        int payloadLen = buf.readInt();
        byte[] payload = new byte[payloadLen];
        buf.readBytes(payload);
        return new EmittedLcaNettyFrame(kind, new String(chanBytes, StandardCharsets.UTF_8), payload);
    }

    // =========================================================================
    // Pipeline Injection & NMS Packet Reflection (`1.20.4`–`26.1+`)
    // =========================================================================

    public static PaperLcaNettyChannelHandler injectIntoChannel(
            Channel channel,
            LcaInMemoryChunkCoordinator coordinator,
            UUID playerId,
            String worldName,
            int minSectionY,
            int playerBitSlot,
            boolean coalesceTickFlushes,
            boolean proxySafeStoredZlib
    ) {
        Objects.requireNonNull(channel, "channel");
        ChannelPipeline pipeline = channel.pipeline();
        ChannelDuplexHandler existing = (ChannelDuplexHandler) pipeline.get(HANDLER_NAME);
        if (existing instanceof PaperLcaNettyChannelHandler lcaHandler) {
            lcaHandler.setWorldName(worldName);
            return lcaHandler;
        }
        PaperLcaNettyChannelHandler handler = new PaperLcaNettyChannelHandler(
                coordinator, playerId, worldName, minSectionY, playerBitSlot, coalesceTickFlushes, proxySafeStoredZlib);
        handler.resolveCustomPayloadFactoryForPipeline(pipeline);
        // Outbound writes flow TAIL -> packet_handler -> unbundler -> encoder/packet_encoder -> compress -> prepender -> HEAD.
        // Position before packet_handler (or after unbundler/encoder) so write() intercepts Packet<?> objects before serialization
        // and ctx.write(ClientboundCustomPayloadPacket) flows downstream into PacketEncoder.
        if (pipeline.get("packet_handler") != null) {
            pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
        } else if (pipeline.get("unbundler") != null) {
            pipeline.addAfter("unbundler", HANDLER_NAME, handler);
        } else if (pipeline.get("packet_encoder") != null) {
            pipeline.addAfter("packet_encoder", HANDLER_NAME, handler);
        } else if (pipeline.get("encoder") != null) {
            pipeline.addAfter("encoder", HANDLER_NAME, handler);
        } else {
            pipeline.addLast(HANDLER_NAME, handler);
        }
        return handler;
    }

    @FunctionalInterface
    private interface IdentifierParser {
        Object parse(String channel) throws Exception;
    }

    private record CustomPayloadPacketFactory(
            IdentifierParser idParser,
            Constructor<?> discardedPayloadCtor,
            Constructor<?> customPayloadPacketCtor
    ) {
        Object createPacket(String channel, byte[] payloadBytes) {
            try {
                Object id = idParser.parse(channel);
                if (id == null) {
                    return null;
                }
                Object payload = discardedPayloadCtor.newInstance(id, payloadBytes);
                return customPayloadPacketCtor.newInstance(payload);
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "[LeafAnvil Netty] Failed constructing ClientboundCustomPayloadPacket for "
                                + channel + ": " + e.getMessage(),
                        e);
                return null;
            }
        }
    }

    private static CustomPayloadPacketFactory probePipelineCustomPayloadFactory(ChannelPipeline pipeline) {
        if (pipeline != null) {
            for (String handlerKey : new String[] {"packet_encoder", "encoder", "outbound_config", "packet_handler"}) {
                Object h = pipeline.get(handlerKey);
                if (h != null) {
                    CustomPayloadPacketFactory fromHandler =
                            lookupCustomPayloadFactory(h.getClass().getClassLoader());
                    if (fromHandler != null) {
                        return fromHandler;
                    }
                }
            }
            if (pipeline.channel() != null) {
                CustomPayloadPacketFactory fromChannel =
                        lookupCustomPayloadFactory(pipeline.channel().getClass().getClassLoader());
                if (fromChannel != null) {
                    return fromChannel;
                }
            }
        }
        return lookupCustomPayloadFactory(PaperLcaNettyChannelHandler.class.getClassLoader());
    }

    private static CustomPayloadPacketFactory lookupCustomPayloadFactory(ClassLoader loader) {
        ClassLoader key = loader != null ? loader : BOOTSTRAP_LOADER_SENTINEL;
        return CUSTOM_PAYLOAD_FACTORY_CACHE
                .computeIfAbsent(key, cl -> Optional.ofNullable(buildCustomPayloadFactory(loader)))
                .orElse(null);
    }

    private static CustomPayloadPacketFactory buildCustomPayloadFactory(ClassLoader loader) {
        try {
            ClassLoader effective = loader != null ? loader : ClassLoader.getSystemClassLoader();
            Class<?> pktCls = Class.forName(
                    "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket", false, effective);
            Class<?> payloadIface = Class.forName(
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload", false, effective);
            Class<?> discardedCls = Class.forName(
                    "net.minecraft.network.protocol.common.custom.DiscardedPayload", false, effective);
            Constructor<?> pktCtor = pktCls.getConstructor(payloadIface);

            Class<?> idCls = resolveNmsIdentifierClass(effective);
            if (idCls == null) {
                return null;
            }
            Constructor<?> discardedCtor = discardedCls.getConstructor(idCls, byte[].class);
            Method parseMethod = findStaticStringFactoryMethod(idCls, "tryParse", "parse");
            IdentifierParser parser;
            if (parseMethod != null) {
                parser = ch -> parseMethod.invoke(null, ch);
            } else {
                Constructor<?> strCtor = idCls.getConstructor(String.class);
                parser = strCtor::newInstance;
            }
            return new CustomPayloadPacketFactory(parser, discardedCtor, pktCtor);
        } catch (ReflectiveOperationException | SecurityException e) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil Netty] NMS ClientboundCustomPayloadPacket unavailable on classloader; using raw envelope fallback",
                    e);
            return null;
        }
    }

    private static Class<?> resolveNmsIdentifierClass(ClassLoader effective) {
        try {
            return Class.forName("net.minecraft.resources.Identifier", false, effective);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.FINEST, "[LeafAnvil Netty] Identifier not present, trying legacy ResourceLocation", e);
        }
        try {
            return Class.forName("net.minecraft.resources.ResourceLocation", false, effective);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.FINEST, "[LeafAnvil Netty] ResourceLocation not present on classloader", e);
            return null;
        }
    }

    private static Method findStaticStringFactoryMethod(Class<?> cls, String... names) {
        Method[] methods = cls.getMethods();
        for (String name : names) {
            for (Method m : methods) {
                if (name.equals(m.getName())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    public static Channel extractBukkitPlayerNettyChannel(Object bukkitPlayer) {
        if (bukkitPlayer == null) {
            return null;
        }
        try {
            Method getHandle = bukkitPlayer.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(bukkitPlayer);
            if (serverPlayer == null) {
                return null;
            }
            Object listener = findFieldBySimpleType(serverPlayer, "ServerGamePacketListenerImpl", "ServerCommonPacketListenerImpl");
            if (listener == null) {
                return null;
            }
            Object connection = findFieldBySimpleType(listener, "Connection");
            if (connection == null) {
                return null;
            }
            for (Field f : getAllFields(connection.getClass())) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Channel) f.get(connection);
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "[LeafAnvil Netty] Reflective Netty channel lookup unavailable: " + t.getMessage(), t);
        }
        return null;
    }

    private static final Map<Class<?>, PacketAccessor> PACKET_ACCESSOR_CACHE = new ConcurrentHashMap<>();

    private interface PacketAccessor {
        OutboundVoxelDescriptor extract(Object pkt) throws Exception;
    }

    static OutboundVoxelDescriptor inspectOutboundPacket(Object msg) {
        if (msg instanceof OutboundVoxelDescriptor direct) {
            return direct;
        }
        Class<?> cls = msg.getClass();
        String simple = cls.getSimpleName();
        if (!simple.startsWith("Clientbound")) {
            return null;
        }
        PacketAccessor acc = PACKET_ACCESSOR_CACHE.computeIfAbsent(cls, PaperLcaNettyChannelHandler::buildPacketAccessor);
        try {
            return acc.extract(msg);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[LeafAnvil Netty] Packet inspection fallback for " + cls.getName(), e);
            return null;
        }
    }

    private static PacketAccessor buildPacketAccessor(Class<?> cls) {
        String simple = cls.getSimpleName();
        try {
            if ("ClientboundChunkBatchStartPacket".equals(simple)) {
                return pkt -> new ChunkBatchPacingDescriptor(false, 0);
            }
            if ("ClientboundChunkBatchFinishedPacket".equals(simple)) {
                Method sizeMethod = findZeroArgMethod(cls, "batchSize");
                return pkt -> new ChunkBatchPacingDescriptor(
                        true,
                        sizeMethod != null ? ((Number) sizeMethod.invoke(pkt)).intValue() : 0
                );
            }
            if ("ClientboundLevelChunkWithLightPacket".equals(simple)) {
                Method getX = findZeroArgMethod(cls, "getX", "x");
                Method getZ = findZeroArgMethod(cls, "getZ", "z");
                if (getX != null && getZ != null) {
                    return pkt -> new FullChunkDescriptor(
                            ((Number) getX.invoke(pkt)).intValue(),
                            ((Number) getZ.invoke(pkt)).intValue()
                    );
                }
            }
            if ("ClientboundBlockUpdatePacket".equals(simple)) {
                Method getPos = findZeroArgMethod(cls, "getPos");
                Method getBlockState = findZeroArgMethod(cls, "getBlockState");
                if (getPos != null && getBlockState != null) {
                    Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", false, cls.getClassLoader());
                    Method getId = findOneArgMethod(blockCls, "getId");
                    return pkt -> {
                        Object pos = getPos.invoke(pkt);
                        Object state = getBlockState.invoke(pkt);
                        int x = ((Number) pos.getClass().getMethod("getX").invoke(pos)).intValue();
                        int y = ((Number) pos.getClass().getMethod("getY").invoke(pos)).intValue();
                        int z = ((Number) pos.getClass().getMethod("getZ").invoke(pos)).intValue();
                        int id = getId != null ? ((Number) getId.invoke(null, state)).intValue() : 0;
                        return new SingleBlockUpdateDescriptor(x, y, z, id);
                    };
                }
            }
            if ("ClientboundSectionBlocksUpdatePacket".equals(simple)) {
                Field secPosField = findFieldBySimpleTypeClass(cls, "SectionPos");
                Field positionsField = findFieldOfType(cls, short[].class);
                Field statesField = findArrayFieldSimple(cls, "BlockState");
                if (secPosField != null && positionsField != null && statesField != null) {
                    Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", false, cls.getClassLoader());
                    Method getId = findOneArgMethod(blockCls, "getId");
                    return pkt -> {
                        Object secPos = secPosField.get(pkt);
                        int sx = ((Number) secPos.getClass().getMethod("x").invoke(secPos)).intValue();
                        int sy = ((Number) secPos.getClass().getMethod("y").invoke(secPos)).intValue();
                        int sz = ((Number) secPos.getClass().getMethod("z").invoke(secPos)).intValue();
                        short[] posShorts = (short[]) positionsField.get(pkt);
                        Object[] stateObjs = (Object[]) statesField.get(pkt);
                        int[] ids = new int[stateObjs.length];
                        for (int i = 0; i < stateObjs.length; i++) {
                            ids[i] = getId != null ? ((Number) getId.invoke(null, stateObjs[i])).intValue() : 0;
                        }
                        return new SectionMultiBlockUpdateDescriptor(sx, sy, sz, posShorts, ids);
                    };
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                    "[LeafAnvil Netty] Packet accessor reflection fallback for " + cls.getName() + ": " + t.getMessage(), t);
        }
        return pkt -> null;
    }

    private static Method findZeroArgMethod(Class<?> cls, String... names) {
        Method[] methods = cls.getMethods();
        for (String n : names) {
            for (Method m : methods) {
                if (n.equals(m.getName()) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findOneArgMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                out.add(f);
            }
        }
        return out;
    }

    private static Object findFieldBySimpleType(Object target, String... simpleNames) throws IllegalAccessException {
        for (Field f : getAllFields(target.getClass())) {
            String sn = f.getType().getSimpleName();
            for (String cand : simpleNames) {
                if (cand.equals(sn)) {
                    f.setAccessible(true);
                    return f.get(target);
                }
            }
        }
        return null;
    }

    private static Field findFieldBySimpleTypeClass(Class<?> cls, String simpleName) {
        for (Field f : getAllFields(cls)) {
            if (simpleName.equals(f.getType().getSimpleName())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Field findFieldOfType(Class<?> cls, Class<?> fieldType) {
        for (Field f : getAllFields(cls)) {
            if (f.getType() == fieldType) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static Field findArrayFieldSimple(Class<?> cls, String componentSimpleName) {
        for (Field f : getAllFields(cls)) {
            if (f.getType().isArray() && componentSimpleName.equals(f.getType().getComponentType().getSimpleName())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }
}
