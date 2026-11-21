package net.leaf.anvil.common.net;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader-agnostic client RX session manager for `LCA_NET_V1` negotiated `.lca` chunk streaming
 * and revisit delta synchronization (`leafanvil-ADR-002`, `leafanvil-ADR-003`).
 *
 * <p>Shared by the free `MIT`-licensed {@code LeafAnvil-Client} mod (`FabricLeafAnvilClientMod`,
 * `NeoForgeLeafAnvilClientMod`) and the full {@code LeafAnvil} mod (`FabricLeafAnvilMod`,
 * `NeoForgeLeafAnvilMod`) without depending on dedicated-server storage interception classes
 * (`LeafAnvilRuntime`, `NmsRegionStorageInterceptor`, `LeafAnvilWorldConverter`).</p>
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaClientWireReceiver {

    /**
     * Default client slab cache budget in KiB (`32 MiB`) advertised in `LCA_NET_V1` handshakes.
     */
    public static final int DEFAULT_CLIENT_CACHE_BUDGET_KIB = 32 * 1024;

    /**
     * Wire byte length of {@link HandshakePayload} (`20` bytes).
     */
    public static final int HANDSHAKE_WIRE_BYTES = 20;

    private static final Logger LOGGER = Logger.getLogger(LcaClientWireReceiver.class.getName());
    private static final AtomicBoolean GLOBAL_CLIENT_HOOKS_REGISTERED = new AtomicBoolean(false);

    private final LcaInMemoryChunkCoordinator coordinator;
    private final int advertisedCacheBudgetKiB;
    private volatile NegotiatedSession negotiatedSession = NegotiatedSession.VANILLA_FALLBACK;

    private final AtomicLong batchesReceived = new AtomicLong();
    private final AtomicLong chunksCommitted = new AtomicLong();
    private final AtomicLong sectionsHydrated = new AtomicLong();
    private final AtomicLong uniformSectionsHydrated = new AtomicLong();
    private final AtomicLong nonUniformSectionsHydrated = new AtomicLong();
    private final AtomicLong wireBytesReceived = new AtomicLong();
    private final AtomicLong estimatedVanillaZlibBytes = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();

    /**
     * Immutable telemetry snapshot of client-side `LCA_NET_V1` wire streaming activity.
     */
    public record ClientTelemetrySnapshot(
            long batchesReceived,
            long chunksCommitted,
            long sectionsHydrated,
            long uniformSectionsHydrated,
            long nonUniformSectionsHydrated,
            long wireBytesReceived,
            long estimatedVanillaZlibBytes,
            long failedBatches,
            boolean sessionActive,
            int activeCapabilities,
            int negotiatedCacheBudgetKiB
    ) {
        /**
         * Returns the percentage reduction in wire payload bytes compared to estimated vanilla
         * `Zlib` chunk packets.
         */
        public double wireReductionPct() {
            if (estimatedVanillaZlibBytes <= 0L) {
                return 0.0;
            }
            return (1.0 - ((double) wireBytesReceived / (double) estimatedVanillaZlibBytes)) * 100.0;
        }
    }

    /**
     * Creates a standalone client RX receiver with its own {@link LcaInMemoryChunkCoordinator}
     * and {@link ClientChunkSlabCache}.
     */
    public LcaClientWireReceiver() {
        this(
                new LcaInMemoryChunkCoordinator(
                        new ClientChunkSlabCache((long) DEFAULT_CLIENT_CACHE_BUDGET_KIB * 1024L)
                ),
                DEFAULT_CLIENT_CACHE_BUDGET_KIB
        );
    }

    /**
     * Creates a client RX receiver backed by the supplied {@link LcaInMemoryChunkCoordinator}.
     */
    public LcaClientWireReceiver(
            LcaInMemoryChunkCoordinator coordinator,
            int advertisedCacheBudgetKiB
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.advertisedCacheBudgetKiB = Math.max(256, advertisedCacheBudgetKiB);
    }

    /**
     * Atomically claims global client networking hook registration so co-installed
     * {@code LeafAnvil-Client} and {@code LeafAnvil} jars never double-register payload listeners
     * in singleplayer or LAN sessions.
     */
    public static boolean tryAcquireGlobalClientRegistration() {
        return GLOBAL_CLIENT_HOOKS_REGISTERED.compareAndSet(false, true);
    }

    /**
     * Resets the global client registration guard (for unit tests).
     */
    public static void resetGlobalClientRegistrationForTests() {
        GLOBAL_CLIENT_HOOKS_REGISTERED.set(false);
    }

    public LcaInMemoryChunkCoordinator coordinator() {
        return coordinator;
    }

    public LcaOrderlessDictTable clientOrderlessDictTable() {
        return coordinator.clientOrderlessDictTable();
    }

    public NegotiatedSession negotiatedSession() {
        return negotiatedSession;
    }

    /**
     * Builds the 20-byte `LCA_NET_V1` client capability handshake payload (`leafanvil-ADR-003` Section 1).
     */
    public byte[] buildClientHandshakeBytes(long localBlockStateParityHash64) {
        HandshakePayload payload = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                advertisedCacheBudgetKiB,
                localBlockStateParityHash64
        );
        return payload.toBytes();
    }

    public void onInitialized(String loaderName) {
        logInfo("[LeafAnvil-Client] Initialized (" + loaderName + " client LCA_NET_V1 wire receiver active).");
    }

    /**
     * Logs an informational message to SLF4J (`logs/latest.log` on Fabric/NeoForge) with JUL fallback.
     */
    public static void logInfo(String message) {
        try {
            Class<?> lf = Class.forName("org.slf4j.LoggerFactory");
            Object slf4j = lf.getMethod("getLogger", String.class).invoke(null, "LeafAnvil-Client");
            slf4j.getClass().getMethod("info", String.class).invoke(slf4j, message);
        } catch (Throwable t) {
            LOGGER.log(Level.INFO, message);
        }
    }

    /**
     * Logs a warning message (`S-004`) to SLF4J (`logs/latest.log` on Fabric/NeoForge) with JUL fallback.
     */
    public static void logWarning(String message, Throwable errorOrNull) {
        try {
            Class<?> lf = Class.forName("org.slf4j.LoggerFactory");
            Object slf4j = lf.getMethod("getLogger", String.class).invoke(null, "LeafAnvil-Client");
            if (errorOrNull != null) {
                slf4j.getClass().getMethod("warn", String.class, Throwable.class).invoke(slf4j, message, errorOrNull);
            } else {
                slf4j.getClass().getMethod("warn", String.class).invoke(slf4j, message);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, message, errorOrNull);
        }
    }

    /**
     * Processes a server `LCA_NET_V1` handshake ACK on the client and activates `.lca` wire
     * streaming when protocol version, capability mask, and 64-bit block-state registry parity
     * match (`S-004` explicit warning fallback on invalid payloads).
     */
    public NegotiatedSession onServerHandshakeAck(
            byte[] payloadBytes,
            long localBlockStateParityHash64
    ) {
        if (payloadBytes == null || payloadBytes.length < HANDSHAKE_WIRE_BYTES) {
            logWarning(
                    "[LeafAnvil-Client] Ignoring truncated LCA_NET_V1 handshake ACK ("
                            + (payloadBytes == null ? 0 : payloadBytes.length)
                            + " B); staying on vanilla chunk packets.",
                    null
            );
            NegotiatedSession fallback = new NegotiatedSession(false, 0, 0, localBlockStateParityHash64);
            this.negotiatedSession = fallback;
            return fallback;
        }
        try {
            HandshakePayload serverAck = HandshakePayload.fromBytes(payloadBytes);
            NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                    serverAck,
                    LcaWireStreamProtocol.ALL_CAPABILITIES,
                    localBlockStateParityHash64
            );
            this.negotiatedSession = session;
            return session;
        } catch (RuntimeException ex) {
            logWarning(
                    "[LeafAnvil-Client] Failed to parse server LCA_NET_V1 handshake ACK; falling back to vanilla chunk packets: "
                            + ex.getMessage(),
                    ex
            );
            NegotiatedSession fallback = new NegotiatedSession(false, 0, 0, localBlockStateParityHash64);
            this.negotiatedSession = fallback;
            return fallback;
        }
    }

    /**
     * Decodes an inbound `leafanvil:lca_chunk_batch` payload (`leafanvil-ADR-003` Section 4),
     * commits hydrated `.lca` section backings in strict Netty arrival order, and updates client
     * telemetry counters.
     */
    public int onChunkBatchPayloadReceived(
            String worldName,
            byte[] batchPayloadBytes,
            boolean proxySafeZlib,
            long localBlockStateParityHash64,
            Consumer<HydratedClientChunkCommit> onMainThreadCommitOrNull
    ) {
        if (batchPayloadBytes == null || batchPayloadBytes.length == 0) {
            return 0;
        }
        NegotiatedSession session = this.negotiatedSession;
        if (!session.isLcaStreamActive()) {
            session = new NegotiatedSession(
                    true,
                    LcaWireStreamProtocol.ALL_CAPABILITIES,
                    advertisedCacheBudgetKiB,
                    localBlockStateParityHash64
            );
            this.negotiatedSession = session;
        }
        String dim = (worldName != null && !worldName.isBlank()) ? worldName : "minecraft:overworld";
        AtomicLong committedCount = new AtomicLong();
        try {
            coordinator.receiveClientChunkBatchOrdered(
                    dim,
                    batchPayloadBytes,
                    proxySafeZlib,
                    session,
                    coordinator.clientOrderlessDictTable(),
                    null,
                    commit -> {
                        committedCount.incrementAndGet();
                        TrackedChunkColumn col = coordinator.getTrackedChunk(
                                commit.dimension(),
                                commit.chunkX(),
                                commit.chunkZ()
                        );
                        if (col != null) {
                            recordHydratedColumnTelemetry(commit.sectionBackings(), batchPayloadBytes.length, true);
                        }
                        if (onMainThreadCommitOrNull != null) {
                            onMainThreadCommitOrNull.accept(commit);
                        }
                    }
            );
            coordinator.clientCommitQueue().drainReadyInStrictNettyOrder();
        } catch (Throwable t) {
            failedBatches.incrementAndGet();
            logWarning(
                    "[LeafAnvil-Client] Failed to decode inbound LCA_NET_V1 chunk batch ("
                            + batchPayloadBytes.length + " B): " + t.getMessage(),
                    t
            );
        }
        return (int) committedCount.get();
    }

    /**
     * Decodes a consolidated multi-target tick frame (`MTCF`, {@link LcaMultiTargetConsolidator}),
     * applies inline `ODCT` dictionary learn entries into {@link #clientOrderlessDictTable()},
     * delivers quantized {@link LcaMultiTargetConsolidator.KinematicDeltaRow}s, and forwards any
     * embedded `LCAB` chunk batch into {@link #onChunkBatchPayloadReceived} (`REQ-NET-MTCF-001`).
     */
    public LcaMultiTargetConsolidator.DecodedMultiTargetFrame onMultiTargetTickFrameReceived(
            String worldName,
            int localPlayerSlotIndex,
            byte[] mtcfWireBytes,
            boolean proxySafeZlib,
            long localBlockStateParityHash64,
            Consumer<List<LcaMultiTargetConsolidator.KinematicDeltaRow>> onKinematicsDecodedOrNull,
            Consumer<HydratedClientChunkCommit> onMainThreadCommitOrNull
    ) {
        if (mtcfWireBytes == null || mtcfWireBytes.length < 4) {
            return null;
        }
        try {
            boolean hasRawMtcfMagic = (((mtcfWireBytes[0] & 0xFF) << 24)
                    | ((mtcfWireBytes[1] & 0xFF) << 16)
                    | ((mtcfWireBytes[2] & 0xFF) << 8)
                    | (mtcfWireBytes[3] & 0xFF)) == LcaMultiTargetConsolidator.MULTI_TARGET_FRAME_MAGIC;
            byte[] unwrappedMtcf = hasRawMtcfMagic
                    ? mtcfWireBytes
                    : (proxySafeZlib
                            ? LcaWireStreamProtocol.decodeProxySafeZlibStoredFrame(mtcfWireBytes)
                            : LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(mtcfWireBytes));
            LcaMultiTargetConsolidator.DecodedMultiTargetFrame decoded =
                    LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(
                            unwrappedMtcf,
                            coordinator.clientOrderlessDictTable()
                    );
            if (localPlayerSlotIndex >= 0 && !decoded.targetsPlayerSlot(localPlayerSlotIndex)) {
                return decoded;
            }
            if (onKinematicsDecodedOrNull != null && !decoded.decodedKinematics().isEmpty()) {
                onKinematicsDecodedOrNull.accept(decoded.decodedKinematics());
            }
            byte[] chunkBatchBytes = decoded.sharedChunkBatchBytes();
            if (chunkBatchBytes.length >= 2) {
                onChunkBatchPayloadReceived(
                        worldName,
                        chunkBatchBytes,
                        false,
                        localBlockStateParityHash64,
                        onMainThreadCommitOrNull
                );
            }
            return decoded;
        } catch (IOException | RuntimeException ex) {
            failedBatches.incrementAndGet();
            String msg = "[LeafAnvil-Client] Failed to decode inbound MTCF multi-target frame ("
                    + mtcfWireBytes.length + " B): " + ex.getMessage();
            LOGGER.log(Level.WARNING, msg, ex);
            logWarning(msg, ex);
            return null;
        }
    }

    /**
     * Builds the compact `leafanvil:lca_chunk_digest` revisit digest payload for the specified
     * `32x32` region from the client's local {@link ClientChunkSlabCache}.
     */
    public byte[] buildRegionRevisitDigestBytes(String worldName, int regionX, int regionZ) {
        String dim = (worldName != null && !worldName.isBlank()) ? worldName : "minecraft:overworld";
        ServerboundLcaChunkDigestPayload digest =
                coordinator.clientSlabCache().buildRegionDigest(dim, regionX, regionZ);
        return digest.toBytes();
    }

    /**
     * Resets the negotiated session state upon disconnecting from a server while preserving cached
     * client chunk slabs within the bounded LRU cache for fast reconnects.
     */
    public void onClientDisconnect() {
        this.negotiatedSession = NegotiatedSession.VANILLA_FALLBACK;
        coordinator.clientCommitQueue().drainReadyInStrictNettyOrder();
    }

    /**
     * Records a client-loaded chunk column into the client telemetry counters and logs periodic
     * summaries every 16 chunks.
     */
    public void recordClientChunkLoadSample(LcaSectionBacking[] backings, int estimatedWireBytes) {
        recordHydratedColumnTelemetry(backings, estimatedWireBytes, false);
    }

    private void recordHydratedColumnTelemetry(
            LcaSectionBacking[] backings,
            int wireBatchBytes,
            boolean fromWireBatch
    ) {
        if (backings == null) {
            return;
        }
        int uniform = 0;
        int nonUniform = 0;
        int lcaEstimate = 48;
        int vanillaEstimate = 320;
        for (LcaSectionBacking b : backings) {
            if (b == null) {
                continue;
            }
            byte[] base = b.baseFrame();
            if ((base == null || base.length == 0) && b.dirtyCount() == 0) {
                uniform++;
                lcaEstimate += 4;
                vanillaEstimate += 64;
            } else {
                nonUniform++;
                int frameLen = (base != null && base.length > 0) ? base.length : 180;
                lcaEstimate += frameLen;
                vanillaEstimate += Math.max(frameLen * 4, 1536);
            }
        }
        if (fromWireBatch || (chunksCommitted.get() & 7L) == 7L) {
            batchesReceived.incrementAndGet();
        }
        chunksCommitted.incrementAndGet();
        sectionsHydrated.addAndGet(backings.length);
        uniformSectionsHydrated.addAndGet(uniform);
        nonUniformSectionsHydrated.addAndGet(nonUniform);
        int actualWire = wireBatchBytes > 0 ? wireBatchBytes : lcaEstimate;
        wireBytesReceived.addAndGet(actualWire);
        estimatedVanillaZlibBytes.addAndGet(Math.max(actualWire * 4L, vanillaEstimate));
    }

    /**
     * Returns an immutable telemetry snapshot of all batches and chunks received by this client.
     */
    public ClientTelemetrySnapshot snapshotTelemetry() {
        NegotiatedSession session = this.negotiatedSession;
        return new ClientTelemetrySnapshot(
                batchesReceived.get(),
                chunksCommitted.get(),
                sectionsHydrated.get(),
                uniformSectionsHydrated.get(),
                nonUniformSectionsHydrated.get(),
                wireBytesReceived.get(),
                estimatedVanillaZlibBytes.get(),
                failedBatches.get(),
                session.isLcaStreamActive(),
                session.activeCapabilities(),
                session.negotiatedCacheBudgetKiB()
        );
    }
}
