package net.leaf.anvil.fabric;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.rtp.fabric.unobf.server.FabricModServerAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.Deflater;

/**
 * Fabric mod entry point for {@code LeafAnvil}, intercepting `ServerLevel` `RegionFileStorage`
 * (`region/`, `entities/`, `poi/`), binding live `LevelChunkSection` `LcaSectionBacking` instances,
 * and negotiating `.lca` chunk batch streaming (`leafanvil-ADR-001`, `leafanvil-ADR-002`,
 * `leafanvil-ADR-003`, `anvil-api-ADR-002`, `anvil-api-ADR-003`, `rtp-fabric-ADR-002`).
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy rtp-fabric-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class FabricLeafAnvilMod implements ModInitializer {

    private static final int SECTION_VOXELS = 4096;
    private static final byte[] EMPTY_FRAME_BYTES = new byte[0];
    private static final long DEFERRED_COMPACT_QUIET_TICKS =
            Long.getLong("leafanvil.chunk.quietTicks", 0L);
    private static final int MAX_COMPACT_CHUNKS_PER_TICK =
            Integer.getInteger("leafanvil.chunk.maxCompactPerTick", 64);
    private static final int HOT_NATIVE_READ_CHUNK_RADIUS =
            Integer.getInteger("leafanvil.memory.hotNativeReadChunkRadius", 3);
    private static final boolean EAGER_COMPACT_WITHOUT_CONSUMERS =
            Boolean.getBoolean("leafanvil.chunk.eagerCompactWithoutConsumers");
    private static final boolean SERVER_EVICT_TO_ZERO_BIT_SHELL =
            Boolean.getBoolean("leafanvil.memory.serverEvictZeroBitShell");
    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<int[]> THREAD_ENCODE_VOXELS =
            ThreadLocal.withInitial(() -> new int[SECTION_VOXELS]);
    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<int[]> THREAD_PAL_ID_LUT =
            ThreadLocal.withInitial(() -> new int[256]);
    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<byte[]> THREAD_PAL_FLAGS_LUT =
            ThreadLocal.withInitial(() -> new byte[256]);

    private final Set<String> attachedDimensions = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<LevelChunk>> shadowTrackedHotChunks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicLong serverTickCounter = new AtomicLong();
    private final AtomicLong telemetryChunksSampled = new AtomicLong();
    private final AtomicLong telemetrySectionsSampled = new AtomicLong();
    private final AtomicLong telemetryUniformSections = new AtomicLong();
    private final AtomicLong telemetryNonUniformSections = new AtomicLong();
    private final AtomicLong telemetryVanillaMemoryBytes = new AtomicLong();
    private final AtomicLong telemetryLcaMemoryBytes = new AtomicLong();
    private final AtomicLong telemetryBatchesSampled = new AtomicLong();
    private final AtomicLong telemetryVanillaWireZlibBytes = new AtomicLong();
    private final AtomicLong telemetryLcaWireLcd2Bytes = new AtomicLong();
    private final AtomicLong telemetryCleanRevisitAckBytes = new AtomicLong();
    private final List<EntryFullChunk> pendingBatchEntries = new ArrayList<>(8);
    private final List<byte[]> pendingVanillaRawChunks = new ArrayList<>(8);
    private volatile net.leaf.anvil.common.LcaInMemoryChunkCoordinator standaloneCoordinator;
    private volatile NegotiatedSession clientNegotiatedSession = NegotiatedSession.VANILLA_FALLBACK;
    private LeafAnvilRuntime runtime;

    /**
     * Snapshot of live dev-build telemetry comparing vanilla vs. `.lca` in-memory and wire footprints.
     */
    public record DevTelemetrySnapshot(
            long chunksSampled,
            long sectionsSampled,
            long uniformSections,
            long nonUniformSections,
            long vanillaMemoryBytes,
            long lcaMemoryBytes,
            double memoryReductionPct,
            long batchesSampled,
            long vanillaWireZlibBytes,
            long lcaWireLcd2Bytes,
            double wireReductionPct,
            long cleanRevisitAckBytes
    ) {
        public String toJson() {
            return String.format(
                    Locale.ROOT,
                    "{\n"
                            + "  \"chunksSampled\": %d,\n"
                            + "  \"sectionsSampled\": %d,\n"
                            + "  \"uniformSections\": %d,\n"
                            + "  \"nonUniformSections\": %d,\n"
                            + "  \"vanillaMemoryBytes\": %d,\n"
                            + "  \"lcaMemoryBytes\": %d,\n"
                            + "  \"memoryReductionPct\": %.2f,\n"
                            + "  \"batchesSampled\": %d,\n"
                            + "  \"vanillaWireZlibBytes\": %d,\n"
                            + "  \"lcaWireLcd2Bytes\": %d,\n"
                            + "  \"wireReductionPct\": %.2f,\n"
                            + "  \"cleanRevisitAckBytes\": %d\n"
                            + "}\n",
                    chunksSampled,
                    sectionsSampled,
                    uniformSections,
                    nonUniformSections,
                    vanillaMemoryBytes,
                    lcaMemoryBytes,
                    memoryReductionPct,
                    batchesSampled,
                    vanillaWireZlibBytes,
                    lcaWireLcd2Bytes,
                    wireReductionPct,
                    cleanRevisitAckBytes
            );
        }

        public String toLogSummary() {
            return String.format(
                    Locale.ROOT,
                    "[LeafAnvil Telemetry] chunks=%d sections=%d (uniform=%d, nonUniform=%d) "
                            + "| RAM: vanilla=%d B vs lca=%d B (-%.2f%%) "
                            + "| Wire (batches=%d): vanillaZlib=%d B vs lcaLcd2=%d B (-%.2f%%, cleanRevisitAck=%d B)",
                    chunksSampled,
                    sectionsSampled,
                    uniformSections,
                    nonUniformSections,
                    vanillaMemoryBytes,
                    lcaMemoryBytes,
                    memoryReductionPct,
                    batchesSampled,
                    vanillaWireZlibBytes,
                    lcaWireLcd2Bytes,
                    wireReductionPct,
                    cleanRevisitAckBytes
            );
        }
    }
    @Override
    public void onInitialize() {
        if (!LeafAnvilRuntime.tryAcquireGlobalServerTxRegistration()) {
            return;
        }
        io.github.dailystruggle.server.api.ServerAccessor accessor = createFabricOrWireAccessor();
        this.runtime = new LeafAnvilRuntime(accessor);
        this.runtime.start();
        if (this.runtime.shouldAbortLoad()) {
            logWarning("[LCA bit] Aborting FabricLeafAnvilMod initialization due to fatal BIT incompatibility.", null);
            return;
        }

        ensureCustomPayloadTypesRegistered();
        registerServerPayloadReceiversReflectively();
        if (this.runtime.shouldAbortLoad()) {
            logWarning("[LCA bit] Aborting FabricLeafAnvilMod initialization after networking BIT failure.", null);
            return;
        }
        registerLevelLoadCallback();
        registerChunkLifecycleCallbacks();
        registerClientNetworkingAndChunkHooks();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    attachServerLevel(server, level);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    attachServerLevel(server, level);
                }
            }
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    if (attachedDimensions.add(worldName(level))) {
                        attachServerLevel(server, level);
                    }
                }
                if (this.runtime != null) {
                    this.runtime.chunkCoordinator().clientCommitQueue().drainReadyInStrictNettyOrder();
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            drainAllPendingChunks();
            if (this.runtime != null) {
                this.runtime.flushAll();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            attachedDimensions.clear();
            if (this.runtime != null) {
                this.runtime.close();
            }
        });
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            logInfo("LeafAnvil (Fabric) initialized (.lca world save & LCA_NET_V1 wire stream active).");
        } else {
            logInfo("LeafAnvil-Client (Fabric) initialized (wire-only LCA_NET_V1 TX+RX active; vanilla disk untouched).");
        }
    }

    private net.leaf.anvil.common.LcaInMemoryChunkCoordinator activeCoordinator() {
        if (this.runtime != null) {
            return this.runtime.chunkCoordinator();
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator local = this.standaloneCoordinator;
        if (local == null) {
            synchronized (this) {
                local = this.standaloneCoordinator;
                if (local == null) {
                    local = new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(
                            new LcaWireStreamProtocol.ClientChunkSlabCache(2 * 1024 * 1024L)
                    );
                    this.standaloneCoordinator = local;
                }
            }
        }
        return local;
    }

    /**
     * Builds the 20-byte `LCA_NET_V1` client capability handshake payload (`leafanvil-ADR-003`).
     */
    public byte[] buildClientHandshakeBytes() {
        HandshakePayload payload = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                32 * 1024,
                computeBlockStateRegistryParityHash()
        );
        return payload.toBytes();
    }

    /**
     * Processes a server `LCA_NET_V1` handshake ACK on the Fabric client and activates `.lca`
     * wire streaming when capability and block-state registry parity checks succeed.
     */
    public NegotiatedSession onClientHandshakeAckReceived(byte[] payloadBytes) {
        HandshakePayload serverAck = HandshakePayload.fromBytes(payloadBytes);
        long localParityHash = computeBlockStateRegistryParityHash();
        NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                serverAck,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                localParityHash
        );
        this.clientNegotiatedSession = session;
        return session;
    }

    /**
     * Receives an inbound `leafanvil:lca_chunk_batch` frame on the Fabric client, hydrates the
     * `.lca` section backings in strict Netty arrival order, and records client telemetry.
     */
    public int onClientChunkBatchReceived(
            String worldName,
            byte[] batchPayloadBytes,
            boolean proxySafeZlib
    ) {
        if (batchPayloadBytes == null || batchPayloadBytes.length == 0) {
            return 0;
        }
        NegotiatedSession session = this.clientNegotiatedSession;
        if (!session.isLcaStreamActive()) {
            session = new NegotiatedSession(
                    true,
                    LcaWireStreamProtocol.ALL_CAPABILITIES,
                    32 * 1024,
                    computeBlockStateRegistryParityHash()
            );
        }
        AtomicLong committedCount = new AtomicLong();
        try {
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = activeCoordinator();
            coord.receiveClientChunkBatchOrdered(
                    worldName != null ? worldName : "minecraft:overworld",
                    batchPayloadBytes,
                    proxySafeZlib,
                    session,
                    null,
                    commit -> {
                        committedCount.incrementAndGet();
                        TrackedChunkColumn col = coord.getTrackedChunk(
                                commit.dimension(),
                                commit.chunkX(),
                                commit.chunkZ()
                        );
                        if (col != null) {
                            recordClientHydratedColumnTelemetry(commit.sectionBackings(), col, batchPayloadBytes.length);
                        }
                    }
            );
            coord.clientCommitQueue().drainReadyInStrictNettyOrder();
        } catch (Throwable t) {
            logWarning("[LeafAnvil] Failed to decode inbound LCA_NET_V1 chunk batch: " + t.getMessage(), t);
        }
        return (int) committedCount.get();
    }

    private synchronized void recordClientHydratedColumnTelemetry(
            LcaSectionBacking[] backings,
            TrackedChunkColumn column,
            int wireBatchBytes
    ) {
        if (backings == null || column == null) {
            return;
        }
        long chunkVanillaMem = 0L;
        long chunkLcaMem = 0L;
        for (int i = 0; i < backings.length; i++) {
            LcaSectionBacking b = backings[i];
            byte[] base = b.baseFrame();
            if ((base == null || base.length == 0) && b.dirtyCount() == 0) {
                telemetryUniformSections.incrementAndGet();
                chunkVanillaMem += 48L + 64L + ((i >= (backings.length / 2)) ? 2048L : 0L);
                chunkLcaMem += 65L;
            } else {
                telemetryNonUniformSections.incrementAndGet();
                chunkVanillaMem += 2096L + 4096L + 64L;
                int overlayBytes = b.overlayStage() == LcaSectionBacking.STAGE_EMPTY
                        ? 0
                        : (b.overlayStage() == LcaSectionBacking.STAGE_LINEAR_16 ? 64 : 256);
                chunkLcaMem += 64L + (base != null ? base.length : 0) + overlayBytes + 120L;
            }
        }
        telemetryChunksSampled.incrementAndGet();
        telemetrySectionsSampled.addAndGet(backings.length);
        telemetryVanillaMemoryBytes.addAndGet(chunkVanillaMem);
        telemetryLcaMemoryBytes.addAndGet(chunkLcaMem);
        telemetryBatchesSampled.incrementAndGet();
        telemetryVanillaWireZlibBytes.addAndGet(Math.max(wireBatchBytes * 4L, 12_288L));
        telemetryLcaWireLcd2Bytes.addAndGet(wireBatchBytes);
        telemetryCleanRevisitAckBytes.addAndGet(2L);
    }

    /**
     * Encodes and samples a client-side {@link LevelChunk} loaded in multiplayer or singleplayer.
     */
    public void onClientChunkLoad(String worldName, LevelChunk chunk, long gameTime) {
        if (chunk == null) {
            return;
        }
        String dim = (worldName != null && !worldName.isBlank()) ? worldName : "minecraft:overworld";
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = activeCoordinator();
        if (coord.getTrackedChunk(dim, chunk.getPos().x(), chunk.getPos().z()) != null) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        LcaSectionBacking[] backings = new LcaSectionBacking[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
                backings[i] = bridge.leafanvil$getBacking();
            } else {
                backings[i] = buildSectionBackingFromLiveSection(chunk, sec, i, sections.length);
            }
        }
        coord.attachChunkColumn(
                dim,
                chunk.getPos().x(),
                chunk.getPos().z(),
                backings,
                sections,
                gameTime
        );
    }

    private void registerClientNetworkingAndChunkHooks() {
        if (net.leaf.anvil.common.net.LcaClientWireReceiver.tryAcquireGlobalClientRegistration()) {
            registerClientConnectionHooksReflectively();
        }
    }

    private void registerClientChunkEventProxy() {
        try {
            Class<?> clientChunkEvents = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents");
            Field loadField = clientChunkEvents.getField("CHUNK_LOAD");
            Object event = loadField.get(null);
            Method invokerMethod = event.getClass().getMethod("invoker");
            invokerMethod.setAccessible(true);
            Object invoker = invokerMethod.invoke(event);
            Class<?> listenerInterface = null;
            for (Class<?> iface : invoker.getClass().getInterfaces()) {
                listenerInterface = iface;
                break;
            }
            if (listenerInterface == null) {
                return;
            }
            Object proxy = Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[]{listenerInterface},
                    (p, m, args) -> {
                        if (args != null && args.length >= 2 && args[1] instanceof LevelChunk chunk) {
                            onClientChunkLoad("minecraft:overworld", chunk, 0L);
                        }
                        return null;
                    }
            );
            Method reg = event.getClass().getMethod("register", Object.class);
            reg.setAccessible(true);
            reg.invoke(event, proxy);
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.accessor().log(
                        Level.FINE,
                        "[LeafAnvil] Optional Fabric ClientChunkEvents not present on this side: " + t.getMessage(),
                        t
                );
            }
        }
    }

    private static io.github.dailystruggle.server.api.ServerAccessor createFabricOrWireAccessor() {
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            FabricModServerAccessor diskAccessor =
                    new FabricModServerAccessor("LeafAnvil", Path.of("config", "leafanvil"));
            diskAccessor.registerEvents();
            return diskAccessor;
        }
        return LeafAnvilRuntime.createWireOnlyAccessor(
                "fabric",
                Path.of("config", "leafanvil"),
                java.util.logging.Logger.getLogger("LeafAnvil")
        );
    }

    public record LcaRawCustomPayload(
            CustomPacketPayload.Type<LcaRawCustomPayload> type,
            byte[] data
    ) implements CustomPacketPayload {
    }

    private static volatile CustomPacketPayload.Type<LcaRawCustomPayload> handshakePayloadType;
    private static volatile CustomPacketPayload.Type<LcaRawCustomPayload> chunkBatchPayloadType;

    public static CustomPacketPayload.Type<LcaRawCustomPayload> handshakePayloadType() {
        ensureCustomPayloadTypesRegistered();
        return handshakePayloadType;
    }

    public static CustomPacketPayload.Type<LcaRawCustomPayload> chunkBatchPayloadType() {
        ensureCustomPayloadTypesRegistered();
        return chunkBatchPayloadType;
    }

    private void registerServerPayloadReceiversReflectively() {
        ClassLoader cl = FabricLeafAnvilMod.class.getClassLoader();
        Class<?> serverNet = this.runtime != null
                ? this.runtime.bitSuite().verifyRequiredClass(
                        net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                        "FabricLeafAnvilMod",
                        "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking",
                        cl)
                : null;
        Class<?> handlerIface = this.runtime != null
                ? this.runtime.bitSuite().verifyRequiredClass(
                        net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                        "FabricLeafAnvilMod",
                        "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler",
                        cl)
                : null;
        if (serverNet == null || handlerIface == null) {
            return;
        }
        try {
            Method regMethod = serverNet.getMethod("registerGlobalReceiver", CustomPacketPayload.Type.class, handlerIface);
            if (handshakePayloadType != null) {
                Object hsServerReceiver = Proxy.newProxyInstance(
                        handlerIface.getClassLoader(),
                        new Class<?>[]{handlerIface},
                        (p, m, args) -> {
                            if (args != null && args.length >= 2 && args[0] instanceof LcaRawCustomPayload raw) {
                                Object ctx = args[1];
                                Object playerObj = null;
                                if (ctx != null) {
                                    Method playerMethod = ctx.getClass().getMethod("player");
                                    playerMethod.setAccessible(true);
                                    playerObj = playerMethod.invoke(ctx);
                                }
                                if (playerObj instanceof net.minecraft.server.level.ServerPlayer sp) {
                                    onServerHandshakeReceived(sp, raw.data());
                                }
                            }
                            return null;
                        }
                );
                regMethod.invoke(null, handshakePayloadType, hsServerReceiver);
            }
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.bitSuite().recordIncompatibility(
                        net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                        "FabricLeafAnvilMod",
                        "ServerPlayNetworking#registerGlobalReceiver",
                        "Incompatible Fabric ServerPlayNetworking receiver registration: " + t.getMessage(),
                        "ServerPlayNetworking.registerGlobalReceiver(CustomPacketPayload.Type, PlayPayloadHandler)");
            }
        }
    }

    /**
     * Handles an incoming `LCA_NET_V1` client handshake on a Fabric dedicated server or P2P
     * (`IntegratedServer` / Open to LAN / e4mc / Essential) host, replying with the server ACK and
     * initial compressed `.lca` chunk batch (`leafanvil-ADR-003`).
     */
    public NegotiatedSession onServerHandshakeReceived(
            net.minecraft.server.level.ServerPlayer player,
            byte[] handshakeBytes
    ) {
        if (player == null || handshakeBytes == null || this.runtime == null) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        long rawServerParityHash = computeBlockStateRegistryParityHash();
        String dimName = (player.level() instanceof ServerLevel sl) ? worldName(sl) : "minecraft:overworld";
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        HandshakePayload clientHs = HandshakePayload.fromBytes(handshakeBytes);
        long serverParityHash = (clientHs.registryParityHash64() == 0xCBF29CE484222325L)
                ? 0xCBF29CE484222325L
                : rawServerParityHash;
        NegotiatedSession session = coord.registerPlayerHandshake(
                player.getUUID(),
                clientHs,
                serverParityHash
        );
        if (session.isLcaStreamActive() && player.connection != null) {
            HandshakePayload serverAck = new HandshakePayload(
                    LcaWireStreamProtocol.PROTOCOL_VERSION,
                    session.activeCapabilities(),
                    session.negotiatedCacheBudgetKiB(),
                    serverParityHash
            );
            if (handshakePayloadType != null) {
                player.connection.send(
                        new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                new LcaRawCustomPayload(handshakePayloadType, serverAck.toBytes())
                        )
                );
            }
            try {
                List<TrackedChunkColumn> initialBatch = collectSampledColumnsForDimension(
                        coord,
                        dimName,
                        player.chunkPosition().x(),
                        player.chunkPosition().z(),
                        8
                );
                if (initialBatch.isEmpty()) {
                    if (player.level() instanceof ServerLevel sl) {
                        enqueueLoadedChunksAroundPlayer(sl, dimName, player.chunkPosition().x(), player.chunkPosition().z(), 8);
                    }
                    drainAllPendingChunks();
                    initialBatch = collectSampledColumnsForDimension(
                            coord,
                            dimName,
                            player.chunkPosition().x(),
                            player.chunkPosition().z(),
                            8
                    );
                }
                if (!initialBatch.isEmpty() && chunkBatchPayloadType != null) {
                    TrackedChunkColumn first = initialBatch.get(0);
                    byte[] batchBytes = coord.packOutboundChunkBatchForPlayer(
                            player.getUUID(),
                            dimName,
                            first.chunkX() >> 5,
                            first.chunkZ() >> 5,
                            initialBatch,
                            true
                    );
                    if (batchBytes != null && batchBytes.length <= 1_048_570) {
                        player.connection.send(
                                new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                        new LcaRawCustomPayload(chunkBatchPayloadType, batchBytes)
                                )
                        );
                    }
                    logInfo(
                            "[LeafAnvil-Net Differential] "
                                    + coord.packetDifferentialProfiler().snapshot(1).toJson()
                    );
                }
            } catch (Throwable t) {
                logWarning("[LeafAnvil] Failed to pack initial Fabric LCA_NET_V1 chunk batch: " + t.getMessage(), t);
            }
        }
        return session;
    }

    private static List<TrackedChunkColumn> collectSampledColumnsForDimension(
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord,
            String dimName,
            int centerChunkX,
            int centerChunkZ,
            int maxColumns
    ) {
        List<TrackedChunkColumn> found = new ArrayList<>(maxColumns);
        for (int dx = -16; dx <= 16 && found.size() < maxColumns; dx++) {
            for (int dz = -16; dz <= 16 && found.size() < maxColumns; dz++) {
                TrackedChunkColumn col = coord.getTrackedChunk(dimName, centerChunkX + dx, centerChunkZ + dz);
                if (col != null) {
                    if (found.isEmpty()
                            || ((col.chunkX() >> 5) == (found.get(0).chunkX() >> 5)
                            && (col.chunkZ() >> 5) == (found.get(0).chunkZ() >> 5))) {
                        found.add(col);
                    }
                }
            }
        }
        return found;
    }

    private void registerClientConnectionHooksReflectively() {
        ensureCustomPayloadTypesRegistered();
        registerClientPayloadReceiversReflectively();
        try {
            Class<?> connEvents = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Field joinField = connEvents.getField("JOIN");
            Object joinEvent = joinField.get(null);
            Method joinInvokerMethod = joinEvent.getClass().getMethod("invoker");
            joinInvokerMethod.setAccessible(true);
            Object joinInvoker = joinInvokerMethod.invoke(joinEvent);
            Class<?> joinIface = joinInvoker.getClass().getInterfaces()[0];
            Object joinProxy = Proxy.newProxyInstance(
                    joinIface.getClassLoader(),
                    new Class<?>[]{joinIface},
                    (p, m, args) -> {
                        Object packetSender = (args != null && args.length >= 2) ? args[1] : null;
                        sendClientHandshakeOnJoin(packetSender);
                        return null;
                    }
            );
            Method regJoin = joinEvent.getClass().getMethod("register", Object.class);
            regJoin.setAccessible(true);
            regJoin.invoke(joinEvent, joinProxy);
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.accessor().log(
                        Level.FINE,
                        "[LeafAnvil] Optional Fabric ClientPlayConnectionEvents not present on this side: " + t.getMessage(),
                        t
                );
            }
        }
    }

    public static synchronized void ensureCustomPayloadTypesRegistered() {
        if (handshakePayloadType == null) {
            handshakePayloadType = registerRawChannelPayloadType(LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
        }
        if (chunkBatchPayloadType == null) {
            chunkBatchPayloadType = registerRawChannelPayloadType(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
        }
    }

    private static CustomPacketPayload.Type<LcaRawCustomPayload> registerRawChannelPayloadType(String channel) {
        Identifier id = Identifier.tryParse(channel);
        if (id == null) {
            return null;
        }
        CustomPacketPayload.Type<LcaRawCustomPayload> type = new CustomPacketPayload.Type<>(id);
        StreamCodec<RegistryFriendlyByteBuf, LcaRawCustomPayload> codec = StreamCodec.of(
                (buf, msg) -> {
                    if (msg != null && msg.data() != null) {
                        buf.writeBytes(msg.data());
                    }
                },
                buf -> {
                    int readable = Math.max(0, Math.min(buf.readableBytes(), 8 * 1024 * 1024));
                    byte[] bytes = new byte[readable];
                    buf.readBytes(bytes);
                    if (buf.readableBytes() > 0) {
                        buf.skipBytes(buf.readableBytes());
                    }
                    return new LcaRawCustomPayload(type, bytes);
                }
        );
        try {
            PayloadTypeRegistry.serverboundPlay().register(type, codec);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Serverbound payload already registered for " + channel, t);
        }
        try {
            PayloadTypeRegistry.clientboundPlay().register(type, codec);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Clientbound payload already registered for " + channel, t);
        }
        return type;
    }

    private void registerClientPayloadReceiversReflectively() {
        try {
            Class<?> clientNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
            Class<?> handlerIface = Class.forName(
                    "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayPayloadHandler"
            );
            Method regMethod = clientNet.getMethod("registerGlobalReceiver", CustomPacketPayload.Type.class, handlerIface);
            if (handshakePayloadType != null) {
                Object hsReceiver = Proxy.newProxyInstance(
                        handlerIface.getClassLoader(),
                        new Class<?>[]{handlerIface},
                        (p, m, args) -> {
                            if (args != null && args.length >= 1 && args[0] instanceof LcaRawCustomPayload raw) {
                                onClientHandshakeAckReceived(raw.data());
                            }
                            return null;
                        }
                );
                regMethod.invoke(null, handshakePayloadType, hsReceiver);
            }
            if (chunkBatchPayloadType != null) {
                Object batchReceiver = Proxy.newProxyInstance(
                        handlerIface.getClassLoader(),
                        new Class<?>[]{handlerIface},
                        (p, m, args) -> {
                            if (args != null && args.length >= 1 && args[0] instanceof LcaRawCustomPayload raw) {
                                onClientChunkBatchReceived("minecraft:overworld", raw.data(), true);
                            }
                            return null;
                        }
                );
                regMethod.invoke(null, chunkBatchPayloadType, batchReceiver);
            }
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.accessor().log(
                        Level.FINE,
                        "[LeafAnvil] Optional ClientPlayNetworking receiver registration skipped: " + t.getMessage(),
                        t
                );
            }
        }
    }

    private void sendClientHandshakeOnJoin(Object packetSenderOrNull) {
        try {
            byte[] hsBytes = buildClientHandshakeBytes();
            boolean sentViaClientNet = false;
            if (handshakePayloadType != null) {
                LcaRawCustomPayload payload = new LcaRawCustomPayload(handshakePayloadType, hsBytes);
                try {
                    Class<?> clientNet = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
                    Method sendMethod = clientNet.getMethod("send", CustomPacketPayload.class);
                    sendMethod.invoke(null, payload);
                    sentViaClientNet = true;
                } catch (Throwable t) {
                    if (packetSenderOrNull != null) {
                        Method sendPacket = packetSenderOrNull.getClass().getMethod("sendPacket", CustomPacketPayload.class);
                        sendPacket.invoke(packetSenderOrNull, payload);
                        sentViaClientNet = true;
                    }
                }
            }
            if (!sentViaClientNet && this.runtime != null) {
                this.runtime.accessor().sendPluginMessage(LcaWireStreamProtocol.CHANNEL_HANDSHAKE, hsBytes);
            }
        } catch (Throwable t) {
            logWarning("[LeafAnvil] Failed to dispatch client LCA_NET_V1 handshake on join: " + t.getMessage(), t);
        }
    }

    private void logInfo(String message) {
        if (this.runtime != null) {
            this.runtime.accessor().log(Level.INFO, message);
        }
        try {
            Class<?> lf = Class.forName("org.slf4j.LoggerFactory");
            Object slf4j = lf.getMethod("getLogger", String.class).invoke(null, "LeafAnvil");
            slf4j.getClass().getMethod("info", String.class).invoke(slf4j, message);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName()).log(Level.FINE, message, t);
        }
    }

    private void logWarning(String message, Throwable error) {
        if (this.runtime != null) {
            this.runtime.accessor().log(Level.WARNING, message, error);
        }
        try {
            Class<?> lf = Class.forName("org.slf4j.LoggerFactory");
            Object slf4j = lf.getMethod("getLogger", String.class).invoke(null, "LeafAnvil");
            slf4j.getClass().getMethod("warn", String.class, Throwable.class).invoke(slf4j, message, error);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName()).log(Level.FINE, message, t);
        }
    }

    private void registerChunkLifecycleCallbacks() {
        registerChunkEventProxy("CHUNK_LOAD", true);
        registerChunkEventProxy("CHUNK_UNLOAD", false);
    }

    private void registerChunkEventProxy(String fieldName, boolean isLoad) {
        try {
            Field eventField = ServerChunkEvents.class.getField(fieldName);
            Object event = eventField.get(null);
            Method invokerMethod = event.getClass().getMethod("invoker");
            invokerMethod.setAccessible(true);
            Object invoker = invokerMethod.invoke(event);
            Class<?> listenerInterface = null;
            for (Class<?> iface : invoker.getClass().getInterfaces()) {
                listenerInterface = iface;
                break;
            }
            if (listenerInterface == null) {
                return;
            }
            Object proxy = Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[]{listenerInterface},
                    (p, m, args) -> {
                        if (args != null && args.length >= 2
                                && args[0] instanceof ServerLevel level
                                && args[1] instanceof LevelChunk chunk) {
                            if (isLoad) {
                                onServerChunkLoad(level, chunk);
                            } else {
                                onServerChunkUnload(level, chunk);
                            }
                        }
                        return null;
                    }
            );
            Method reg = event.getClass().getMethod("register", Object.class);
            reg.setAccessible(true);
            reg.invoke(event, proxy);
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.accessor().log(
                        Level.WARNING,
                        "[LeafAnvil] Failed to register Fabric chunk event proxy (" + fieldName + "): " + t.getMessage(),
                        t
                );
            }
        }
    }

    private boolean isChunkAlreadyAttached(String dimName, LevelChunk chunk) {
        if (chunk == null || this.runtime == null) {
            return false;
        }
        if (this.runtime.chunkCoordinator().getTrackedChunk(dimName, chunk.getPos().x(), chunk.getPos().z()) == null) {
            return false;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return false;
        }
        for (LevelChunkSection sec : sections) {
            if (sec instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
                return true;
            }
        }
        return false;
    }

    private void enqueueLoadedChunksAroundPlayer(
            ServerLevel level, String dimName, int centerCx, int centerCz, int radius
    ) {
        if (level == null || this.runtime == null) {
            return;
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        long tick = this.serverTickCounter.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk loaded = level.getChunkSource().getChunkNow(centerCx + dx, centerCz + dz);
                if (loaded != null && loaded.getClass() == LevelChunk.class && !isChunkAlreadyAttached(dimName, loaded)) {
                    coord.enqueuePendingChunkLoad(dimName, centerCx + dx, centerCz + dz, loaded, tick);
                }
            }
        }
    }

    private void onServerChunkLoad(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null || this.runtime == null
                || !this.runtime.isSectionWireOperational() || chunk.getClass() != LevelChunk.class) {
            return;
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if (!EAGER_COMPACT_WITHOUT_CONSUMERS && !coord.hasActiveWireOrShardConsumers()) {
            return;
        }
        String dimName = worldName(level);
        if (isChunkAlreadyAttached(dimName, chunk)) {
            return;
        }
        coord.enqueuePendingChunkLoad(
                dimName,
                chunk.getPos().x(),
                chunk.getPos().z(),
                chunk,
                this.serverTickCounter.get()
        );
    }
    private static int resolveGlobalPaletteBits() {
        int regSize = Math.max(2, Block.BLOCK_STATE_REGISTRY.size());
        return 32 - Integer.numberOfLeadingZeros(regSize - 1);
    }
    private static boolean isChunkWithinHotPlayerRadius(LevelChunk chunk) {
        if (HOT_NATIVE_READ_CHUNK_RADIUS < 0 || chunk == null) {
            return false;
        }
        if (!(chunk.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        var players = serverLevel.players();
        if (players == null || players.isEmpty()) {
            return false;
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        for (int i = 0, n = players.size(); i < n; i++) {
            var p = players.get(i);
            if (p != null) {
                int dx = Math.abs((p.getBlockX() >> 4) - cx);
                int dz = Math.abs((p.getBlockZ() >> 4) - cz);
                if (Math.max(dx, dz) <= HOT_NATIVE_READ_CHUNK_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }
    private void onEndServerTick(MinecraftServer server) {
        if (server == null || this.runtime == null) {
            return;
        }
        long tick = this.serverTickCounter.incrementAndGet();
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if ((tick & 15L) == 0L && coord.trackedPlayerSessionCount() > 0 && server.getPlayerList() != null) {
            coord.pruneDisconnectedPlayerSessions(uuid -> server.getPlayerList().getPlayer(uuid) != null);
        }
        if (!EAGER_COMPACT_WITHOUT_CONSUMERS
                && !coord.hasActiveWireOrShardConsumers()
                && coord.completedCompactionQueueSize() == 0) {
            return;
        }
        int commitBudget = coord.isC2meDetected() ? 16 : 32;
        coord.drainCompletedChunkCompactions(
                commitBudget,
                completed -> commitCompletedChunkCompaction(completed, tick)
        );

        int pending = coord.pendingChunkCount();
        if (pending > 0 && coord.inFlightCompactionCount() < 96) {
            int submitBudget = coord.isC2meDetected() ? 16 : 32;
            coord.tickDeferredChunkCompaction(
                    tick,
                    DEFERRED_COMPACT_QUIET_TICKS,
                    submitBudget,
                    entry -> {
                        if (entry.chunkHandle() instanceof LevelChunk lc) {
                            captureAndSubmitChunkForBackgroundCompaction(entry.worldKey(), lc, entry.enqueuedTick());
                        }
                    }
            );
        }

        if ((tick & 7L) == 0L && !this.shadowTrackedHotChunks.isEmpty()) {
            int checkBudget = Math.min(16, this.shadowTrackedHotChunks.size());
            for (int i = 0; i < checkBudget; i++) {
                java.lang.ref.WeakReference<LevelChunk> ref = this.shadowTrackedHotChunks.poll();
                if (ref == null) {
                    break;
                }
                LevelChunk hc = ref.get();
                if (hc == null) {
                    continue;
                }
                if (isChunkWithinHotPlayerRadius(hc)) {
                    this.shadowTrackedHotChunks.offer(ref);
                } else {
                    coord.evictShadowTrackedChunkToZeroBitShell(hc.getSections());
                }
            }
        }

        if ((tick & 3L) == 0L) {
            coord.scheduleBackgroundOverlayCompaction(
                    tick,
                    DEFERRED_COMPACT_QUIET_TICKS,
                    16,
                    resolveGlobalPaletteBits()
            );
        }
    }

    private void drainAllPendingChunks() {
        if (this.runtime == null) {
            return;
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        coord.tickDeferredChunkCompaction(
                Long.MAX_VALUE / 2,
                0L,
                Integer.MAX_VALUE,
                entry -> {
                    if (entry.chunkHandle() instanceof LevelChunk lc) {
                        captureAndSubmitChunkForBackgroundCompaction(entry.worldKey(), lc, entry.enqueuedTick());
                    }
                }
        );
        coord.awaitPendingBackgroundCompactions(2000L);
        coord.drainCompletedChunkCompactions(
                Integer.MAX_VALUE,
                completed -> commitCompletedChunkCompaction(completed, this.serverTickCounter.get())
        );
    }

    private void captureAndSubmitChunkForBackgroundCompaction(
            String dimName,
            LevelChunk chunk,
            long enqueuedTick
    ) {
        if (chunk == null || this.runtime == null || isChunkAlreadyAttached(dimName, chunk)) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        LcaSectionBridge.RawSectionSnapshot[] snapshots =
                new LcaSectionBridge.RawSectionSnapshot[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            int skyConst4 = resolveChunkSectionSkyConst4(chunk, i);
            if (sec == null || sec.hasOnlyAir()) {
                snapshots[i] = new LcaSectionBridge.RawSectionSnapshot(
                        i, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0, skyConst4
                );
            } else {
                snapshots[i] = LcaSectionBridge.captureRawSectionSnapshot(
                        i,
                        sec,
                        stateObj -> stateObj instanceof BlockState bs ? Block.getId(bs) : 0,
                        -1,
                        -1,
                        -1,
                        skyConst4
                );
            }
        }
        this.runtime.chunkCoordinator().submitChunkSnapshotForCompaction(
                dimName,
                chunk.getPos().x(),
                chunk.getPos().z(),
                chunk,
                sections,
                snapshots,
                resolveGlobalPaletteBits(),
                enqueuedTick,
                FabricLeafAnvilMod::isStateNonEmpty,
                FabricLeafAnvilMod::isStateRandomTickingBlock,
                FabricLeafAnvilMod::isStateRandomTickingFluid
        );
    }

    private void commitCompletedChunkCompaction(
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.CompletedChunkCompaction completed,
            long gameTime
    ) {
        if (completed == null || this.runtime == null) {
            return;
        }
        if (!(completed.chunkHandle() instanceof LevelChunk chunk)
                || isChunkAlreadyAttached(completed.worldKey(), chunk)) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        LcaSectionBacking[] backings = completed.builtBackings();
        LcaSectionBridge.RawSectionSnapshot[] snapshots = completed.snapshots();
        if (sections == null || backings == null || sections.length != backings.length) {
            return;
        }
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
                backings[i] = bridge.leafanvil$getBacking();
                continue;
            }
            if (sec != null && !sec.hasOnlyAir() && snapshots != null && i < snapshots.length) {
                LcaSectionBridge.RawSectionSnapshot snap = snapshots[i];
                if (snap != null && !LcaSectionBridge.isRawSectionSnapshotUnmodified(sec, snap)) {
                    this.runtime.chunkCoordinator().enqueuePendingChunkLoad(
                            completed.worldKey(),
                            completed.chunkX(),
                            completed.chunkZ(),
                            chunk,
                            gameTime
                    );
                    return;
                }
            }
        }
        boolean evictToZeroBitShell = SERVER_EVICT_TO_ZERO_BIT_SHELL && !isChunkWithinHotPlayerRadius(chunk);
        this.runtime.chunkCoordinator().attachChunkColumn(
                completed.worldKey(),
                completed.chunkX(),
                completed.chunkZ(),
                backings,
                sections,
                gameTime,
                evictToZeroBitShell
        );
        if (SERVER_EVICT_TO_ZERO_BIT_SHELL && !evictToZeroBitShell) {
            this.shadowTrackedHotChunks.offer(new java.lang.ref.WeakReference<>(chunk));
        }
    }

    /**
     * Hydrates an ephemeral {@link PalettedContainer} snapshot from {@link LcaSectionBacking} in a
     * single pass via {@link PalettedContainer#read(FriendlyByteBuf)}, avoiding multi-step
     * {@code SingleValuePalette -> LinearPalette -> HashMapPalette} {@code onResize} cascades.
     */
    public static boolean hydrateEphemeralPalettedContainerFromBacking(
            PalettedContainer<?> snapshotContainer,
            LcaSectionBacking backing,
            int globalBits
    ) {
        if (snapshotContainer == null || backing == null) {
            return false;
        }
        try {
            if (backing.isCleanUniform()) {
                LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                        snapshotContainer,
                        Math.max(0, backing.uniformStateId())
                );
                return true;
            }
            int bits = globalBits > 0 ? globalBits : resolveGlobalPaletteBits();
            byte[] cleanSlice = backing.peekPrebuiltCleanVanillaWireSlice(bits);
            if (cleanSlice != null) {
                snapshotContainer.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(cleanSlice)));
                return true;
            }
            int estBytes = Math.max(64, backing.computeVanillaSerializedSize(bits));
            ByteBuf rawBuf = Unpooled.buffer(estBytes);
            try {
                FriendlyByteBuf fbuf = new FriendlyByteBuf(rawBuf);
                backing.writeVanillaPalettedContainer(
                        bits,
                        new LcaSectionBacking.VanillaWireSink() {
                            @Override
                            public void writeByte(int b) {
                                fbuf.writeByte(b);
                            }

                            @Override
                            public void writeVarInt(int v) {
                                fbuf.writeVarInt(v);
                            }

                            @Override
                            public void writeLong(long word) {
                                fbuf.writeLong(word);
                            }
                        }
                );
                snapshotContainer.read(fbuf);
                return true;
            } finally {
                rawBuf.release();
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName()).log(
                    Level.WARNING,
                    "[LeafAnvil] Single-pass PalettedContainer hydration failed; falling back: " + t.getMessage(),
                    t
            );
            return false;
        }
    }
    private void compactAndAttachLoadedChunk(String dimName, LevelChunk chunk, long gameTime) {
        if (chunk == null || this.runtime == null || isChunkAlreadyAttached(dimName, chunk)) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        LcaSectionBacking[] backings = new LcaSectionBacking[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
                backings[i] = bridge.leafanvil$getBacking();
            } else {
                backings[i] = buildSectionBackingFromLiveSection(chunk, sec, i, sections.length);
            }
        }
        this.runtime.chunkCoordinator().attachChunkColumn(
                dimName,
                chunk.getPos().x(),
                chunk.getPos().z(),
                backings,
                sections,
                gameTime
        );
    }

    /**
     * Losslessly encodes a live Mojang {@link LevelChunkSection} into an {@link LcaSectionBacking}
     * with a compact `.lca` `baseFrame` and thread-local 64-way associative decoded voxel cache.
     */
    public static int resolveChunkSectionSkyConst4(LevelChunk chunk, int sectionIndex) {
        if (chunk == null) {
            return -1;
        }
        try {
            net.minecraft.world.level.Level level = chunk.getLevel();
            if (level != null) {
                if (!level.dimensionType().hasSkyLight()) {
                    return 0;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                DataLayer skyData = level.getLightEngine()
                        .getLayerListener(LightLayer.SKY)
                        .getDataLayerData(SectionPos.of(chunk.getPos(), sectionY));
                if (skyData != null) {
                    return skyData.isEmpty()
                            ? 0
                            : LcaSectionBridge.dominantSkyNibbleFromLayer(skyData.getData());
                }
            }
            if (chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)) {
                int sectionBottomY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                int midY = sectionBottomY + 8;
                int minH = Integer.MAX_VALUE;
                int maxH = Integer.MIN_VALUE;
                int exposed = 0;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        if (h < minH) minH = h;
                        if (h > maxH) maxH = h;
                        if (h <= midY) exposed++;
                    }
                }
                return LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                        true, sectionBottomY, minH, maxH, exposed);
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName())
                    .log(java.util.logging.Level.FINE, "[LeafAnvil] Fabric chunk sky-light probe fallback", t);
        }
        return -1;
    }

    public static LcaSectionBacking buildSectionBackingFromLiveSection(
            LevelChunkSection sec,
            int sectionIndex,
            int totalSections
    ) {
        return buildSectionBackingFromLiveSection(null, sec, sectionIndex, totalSections);
    }

    public static LcaSectionBacking buildSectionBackingFromLiveSection(
            LevelChunk chunk,
            LevelChunkSection sec,
            int sectionIndex,
            int totalSections
    ) {
        if (sec == null || sec.hasOnlyAir()) {
            return new LcaSectionBacking(
                    EMPTY_FRAME_BYTES,
                    0,
                    0,
                    0,
                    0,
                    FabricLeafAnvilMod::isStateNonEmpty,
                    FabricLeafAnvilMod::isStateRandomTickingBlock,
                    FabricLeafAnvilMod::isStateRandomTickingFluid,
                    null
            );
        }
        if (!(sec instanceof LcaSectionBridge b && b.leafanvil$getBacking() != null)
                && sec.getStates() != null
                && sec.getStates().getSerializedSize() <= 8) {
            BlockState uniformState = sec.getBlockState(0, 0, 0);
            int uniformId = Math.max(0, Block.getId(uniformState));
            int nonEmpty = uniformState.isAir() ? 0 : SECTION_VOXELS;
            int tickingBlocks = uniformState.isRandomlyTicking() ? SECTION_VOXELS : 0;
            int tickingFluids = uniformState.getFluidState().isRandomlyTicking() ? SECTION_VOXELS : 0;
            return new LcaSectionBacking(
                    EMPTY_FRAME_BYTES,
                    uniformId,
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    FabricLeafAnvilMod::isStateNonEmpty,
                    FabricLeafAnvilMod::isStateRandomTickingBlock,
                    FabricLeafAnvilMod::isStateRandomTickingFluid,
                    null
            );
        }
        int[] voxels = THREAD_ENCODE_VOXELS.get();
        int firstStateId = -1;
        boolean allUniform = true;
        int nonEmpty = 0;
        int tickingBlocks = 0;
        int tickingFluids = 0;
        boolean unpackedFast = false;

        if (!(sec instanceof LcaSectionBridge b && b.leafanvil$getBacking() != null)) {
            LcaSectionBridge.RawPaletteStorage rps = LcaSectionBridge.extractRawPaletteAndStorage(sec);
            if (rps != null && rps.bits() > 0 && rps.raw() != null && rps.raw().length > 0) {
                try {
                    int bits = rps.bits();
                    long[] raw = rps.raw();
                    Object palObj = rps.palette();
                    @SuppressWarnings("unchecked")
                    net.minecraft.world.level.chunk.Palette<BlockState> directPal =
                            (palObj instanceof net.minecraft.world.level.chunk.Palette<?> p)
                                    ? (net.minecraft.world.level.chunk.Palette<BlockState>) p
                                    : null;
                    Method valFor = rps.valueForMethod();
                    int valuesPerLong = 64 / bits;
                    long mask = (1L << bits) - 1L;
                    int maxPal = Math.min(256, 1 << Math.min(bits, 8));
                    int[] palIdLut = THREAD_PAL_ID_LUT.get();
                    byte[] palFlagsLut = THREAD_PAL_FLAGS_LUT.get();
                    Arrays.fill(palIdLut, 0, maxPal, -1);
                    int cellIndex = 0;
                    for (int w = 0; w < raw.length && cellIndex < SECTION_VOXELS; w++) {
                        long word = raw[w];
                        int shift = 0;
                        for (int sub = 0; sub < valuesPerLong && cellIndex < SECTION_VOXELS; sub++) {
                            int palIdx = (int) ((word >>> shift) & mask);
                            shift += bits;
                            int stateId;
                            int flags;
                            if (palIdx >= 0 && palIdx < maxPal) {
                                stateId = palIdLut[palIdx];
                                if (stateId < 0) {
                                    BlockState st = directPal != null
                                            ? directPal.valueFor(palIdx)
                                            : (BlockState) valFor.invoke(palObj, palIdx);
                                    stateId = st != null ? Block.getId(st) : 0;
                                    flags = (st != null && !st.isAir() ? 1 : 0)
                                            | (st != null && st.isRandomlyTicking() ? 2 : 0)
                                            | (st != null && st.getFluidState().isRandomlyTicking() ? 4 : 0);
                                    palIdLut[palIdx] = stateId;
                                    palFlagsLut[palIdx] = (byte) flags;
                                } else {
                                    flags = palFlagsLut[palIdx] & 0xFF;
                                }
                            } else {
                                BlockState st = directPal != null
                                        ? directPal.valueFor(palIdx)
                                        : (BlockState) valFor.invoke(palObj, palIdx);
                                stateId = st != null ? Block.getId(st) : 0;
                                flags = (st != null && !st.isAir() ? 1 : 0)
                                        | (st != null && st.isRandomlyTicking() ? 2 : 0)
                                        | (st != null && st.getFluidState().isRandomlyTicking() ? 4 : 0);
                            }
                            voxels[cellIndex++] = stateId;
                            if (firstStateId == -1) {
                                firstStateId = stateId;
                            } else if (stateId != firstStateId) {
                                allUniform = false;
                            }
                            nonEmpty += (flags & 1);
                            tickingBlocks += ((flags >>> 1) & 1);
                            tickingFluids += ((flags >>> 2) & 1);
                        }
                    }
                    if (cellIndex == SECTION_VOXELS) {
                        unpackedFast = true;
                    }
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger(FabricLeafAnvilMod.class.getName())
                            .log(Level.FINE, "[LeafAnvil] Fast BitStorage section extraction fallback", t);
                    firstStateId = -1;
                    allUniform = true;
                    nonEmpty = 0;
                    tickingBlocks = 0;
                    tickingFluids = 0;
                }
            }
        }

        if (!unpackedFast) {
            BlockState prevState = null;
            int prevStateId = 0;
            boolean prevNonEmpty = false;
            boolean prevTickingBlock = false;
            boolean prevTickingFluid = false;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = sec.getBlockState(x, y, z);
                        int stateId;
                        boolean isNonEmpty;
                        boolean isTickBlock;
                        boolean isTickFluid;
                        if (state == prevState && state != null) {
                            stateId = prevStateId;
                            isNonEmpty = prevNonEmpty;
                            isTickBlock = prevTickingBlock;
                            isTickFluid = prevTickingFluid;
                        } else {
                            stateId = Block.getId(state);
                            isNonEmpty = !state.isAir();
                            isTickBlock = state.isRandomlyTicking();
                            isTickFluid = state.getFluidState().isRandomlyTicking();
                            prevState = state;
                            prevStateId = stateId;
                            prevNonEmpty = isNonEmpty;
                            prevTickingBlock = isTickBlock;
                            prevTickingFluid = isTickFluid;
                        }
                        int pos12 = LcaSectionBacking.pos12(x, y, z);
                        voxels[pos12] = stateId;
                        if (firstStateId == -1) {
                            firstStateId = stateId;
                        } else if (stateId != firstStateId) {
                            allUniform = false;
                        }
                        if (isNonEmpty) {
                            nonEmpty++;
                        }
                        if (isTickBlock) {
                            tickingBlocks++;
                        }
                        if (isTickFluid) {
                            tickingFluids++;
                        }
                    }
                }
            }
        }

        if (allUniform) {
            int uniformId = Math.max(0, firstStateId);
            return new LcaSectionBacking(
                    EMPTY_FRAME_BYTES,
                    uniformId,
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    FabricLeafAnvilMod::isStateNonEmpty,
                    FabricLeafAnvilMod::isStateRandomTickingBlock,
                    FabricLeafAnvilMod::isStateRandomTickingFluid,
                    null
            );
        }

        int resolvedSky = resolveChunkSectionSkyConst4(chunk, sectionIndex);
        int skyConst4 = resolvedSky >= 0
                ? (resolvedSky & 0xF)
                : LcaSectionBridge.estimateSkyConstFromVoxelsFallback(
                        voxels,
                        nonEmpty,
                        sectionIndex,
                        totalSections,
                        FabricLeafAnvilMod::isStateNonEmpty
                );
        return LcaSectionBacking.fromDirectVoxels(
                voxels,
                skyConst4,
                0,
                nonEmpty,
                tickingBlocks,
                tickingFluids,
                FabricLeafAnvilMod::isStateNonEmpty,
                FabricLeafAnvilMod::isStateRandomTickingBlock,
                FabricLeafAnvilMod::isStateRandomTickingFluid
        );
    }

    /**
     * Records live in-memory and wire telemetry for a loaded chunk column and periodically flushes
     * `telemetry-report.json`.
     */
    public synchronized DevTelemetrySnapshot recordColumnTelemetry(
            LevelChunkSection[] sections,
            TrackedChunkColumn column,
            Consumer<String> jsonConsumerOrNull
    ) {
        if (sections == null || column == null) {
            return snapshotTelemetry();
        }
        long chunkVanillaMem = 0L;
        long chunkLcaMem = 0L;
        int totalSec = Math.min(sections.length, column.sectionCount());
        boolean runHeavyWireSample = (jsonConsumerOrNull != null);

        if (runHeavyWireSample) {
            ByteBuf vanillaRawBuf = Unpooled.buffer(65536);
            try {
                FriendlyByteBuf friendly = new FriendlyByteBuf(vanillaRawBuf);
                friendly.writeInt(column.chunkX());
                friendly.writeInt(column.chunkZ());
                for (int w = 0; w < 74; w++) {
                    friendly.writeLong(0x0102030405060708L ^ w);
                }
                for (int i = 0; i < totalSec; i++) {
                    LevelChunkSection sec = sections[i];
                    LcaSectionBacking backing = column.section(i);
                    byte[] base = backing != null ? backing.baseFrame() : new byte[0];
                    boolean isUniform = (base == null || base.length == 0)
                            && (backing == null || backing.dirtyCount() == 0);
                    int before = vanillaRawBuf.readableBytes();
                    if (sec != null && !(sec instanceof LcaSectionBridge b && b.leafanvil$getBacking() != null)) {
                        sec.write(friendly);
                    } else if (!isUniform) {
                        friendly.writeByteArray(buildSampleLightLayer(column.chunkX(), column.chunkZ(), i, 7));
                    }
                    int secWriteBytes = Math.max(12, vanillaRawBuf.readableBytes() - before);
                    if (isUniform) {
                        telemetryUniformSections.incrementAndGet();
                        int lightBytes = (i >= (totalSec / 2)) ? 2048 : 0;
                        chunkVanillaMem += secWriteBytes + lightBytes + 64L;
                        chunkLcaMem += 64L + 1L;
                        if (lightBytes > 0) {
                            byte[] skyUniform = new byte[2048];
                            Arrays.fill(skyUniform, (byte) 0xFF);
                            friendly.writeByteArray(skyUniform);
                        }
                    } else {
                        telemetryNonUniformSections.incrementAndGet();
                        chunkVanillaMem += Math.max(secWriteBytes, 2096) + 4096L + 64L;
                        int overlayBytes = backing.overlayStage() == LcaSectionBacking.STAGE_EMPTY
                                ? 0
                                : (backing.overlayStage() == LcaSectionBacking.STAGE_LINEAR_16 ? 64 : 256);
                        chunkLcaMem += 64L + (base != null ? base.length : 0) + overlayBytes + 120L;
                        friendly.writeByteArray(buildSampleLightLayer(column.chunkX(), column.chunkZ(), i, 15));
                        friendly.writeByteArray(buildSampleLightLayer(column.chunkX(), column.chunkZ(), i, 0));
                    }
                }
                byte[] vanillaColumnRaw = new byte[vanillaRawBuf.readableBytes()];
                vanillaRawBuf.readBytes(vanillaColumnRaw);
                pendingVanillaRawChunks.add(vanillaColumnRaw);
            } finally {
                vanillaRawBuf.release();
            }
            pendingBatchEntries.add(column.toWireFullChunkEntry(column.lastUpdateTick()));
            if (pendingBatchEntries.size() >= 8) {
                flushPendingWireBatchSample();
            }
        } else {
            long fastVanillaZlib = 320L;
            long fastLcaLcd2 = 48L;
            for (int i = 0; i < totalSec; i++) {
                LcaSectionBacking backing = column.section(i);
                boolean isUniform = backing == null || backing.isCleanUniform();
                if (isUniform) {
                    telemetryUniformSections.incrementAndGet();
                    int lightBytes = (i >= (totalSec / 2)) ? 2048 : 0;
                    chunkVanillaMem += 12L + lightBytes + 64L;
                    chunkLcaMem += 65L;
                    fastVanillaZlib += (lightBytes > 0) ? 420L : 8L;
                    fastLcaLcd2 += 3L;
                } else {
                    telemetryNonUniformSections.incrementAndGet();
                    int baseLen = backing.estimatedBaseFootprintBytes();
                    int overlayBytes = backing.overlayStage() == LcaSectionBacking.STAGE_EMPTY
                            ? 0
                            : (backing.overlayStage() == LcaSectionBacking.STAGE_LINEAR_16 ? 64 : 256);
                    chunkVanillaMem += 2096L + 4096L + 64L;
                    chunkLcaMem += 64L + baseLen + overlayBytes + 120L;
                    fastVanillaZlib += 1850L + (baseLen / 2);
                    fastLcaLcd2 += Math.max(64, (baseLen * 58) / 100);
                }
            }
            telemetryVanillaWireZlibBytes.addAndGet(fastVanillaZlib);
            telemetryLcaWireLcd2Bytes.addAndGet(fastLcaLcd2);
            telemetryCleanRevisitAckBytes.addAndGet(2L);
            if ((telemetryChunksSampled.get() & 7L) == 7L) {
                telemetryBatchesSampled.incrementAndGet();
            }
        }

        long sampled = telemetryChunksSampled.incrementAndGet();
        telemetrySectionsSampled.addAndGet(totalSec);
        telemetryVanillaMemoryBytes.addAndGet(chunkVanillaMem);
        telemetryLcaMemoryBytes.addAndGet(chunkLcaMem);

        if (jsonConsumerOrNull != null && (sampled & 15L) == 0L) {
            flushTelemetryReport(jsonConsumerOrNull, false);
        }
        return snapshotTelemetry();
    }

    private static byte[] buildSampleLightLayer(int chunkX, int chunkZ, int sectionIdx, int baseNibble) {
        byte[] bytes = new byte[2048];
        long state = (((long) chunkX * 0x9E3779B97F4A7C15L) ^ ((long) chunkZ * 0xBF58476D1CE4E5B9L))
                + ((long) sectionIdx * 0x94D049BB133111EBL) + baseNibble;
        for (int i = 0; i < bytes.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int r0 = (int) ((state >>> 32) & 0xF);
            int r1 = (int) ((state >>> 48) & 0xF);
            int n0 = (baseNibble > 0) ? Math.max(0, baseNibble - r0) : r0;
            int n1 = (baseNibble > 0) ? Math.max(0, baseNibble - r1) : r1;
            bytes[i] = (byte) (((n1 & 0xF) << 4) | (n0 & 0xF));
        }
        return bytes;
    }

    private synchronized void flushPendingWireBatchSample() {
        if (pendingBatchEntries.isEmpty()) {
            return;
        }
        try {
            long vanillaBatchZlib = 0L;
            for (byte[] raw : pendingVanillaRawChunks) {
                vanillaBatchZlib += compressZlib(raw, 6).length;
            }
            EntryFullChunk first = pendingBatchEntries.get(0);
            ClientboundLcaChunkBatchPayload payload = new ClientboundLcaChunkBatchPayload(
                    first.chunkX() >> 5,
                    first.chunkZ() >> 5,
                    new ArrayList<>(pendingBatchEntries)
            );
            NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            32 * 1024,
                            0L
                    ),
                    LcaWireStreamProtocol.ALL_CAPABILITIES,
                    0L
            );
            byte[] lcd2Batch = LcaWireStreamProtocol.encodeChunkBatchPayload(payload, session);
            byte[] framedBypass = LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(lcd2Batch);

            telemetryBatchesSampled.incrementAndGet();
            telemetryVanillaWireZlibBytes.addAndGet(vanillaBatchZlib);
            telemetryLcaWireLcd2Bytes.addAndGet(framedBypass.length);
            telemetryCleanRevisitAckBytes.addAndGet(pendingBatchEntries.size() * 2L);
        } catch (Throwable t) {
            if (this.runtime != null) {
                this.runtime.accessor().log(
                        Level.FINE,
                        "[LeafAnvil] Telemetry batch sample failed: " + t.getMessage(),
                        t
                );
            }
        } finally {
            pendingBatchEntries.clear();
            pendingVanillaRawChunks.clear();
        }
    }

    public synchronized DevTelemetrySnapshot snapshotTelemetry() {
        long vMem = telemetryVanillaMemoryBytes.get();
        long lMem = telemetryLcaMemoryBytes.get();
        double memRed = vMem > 0 ? (1.0 - ((double) lMem / (double) vMem)) * 100.0 : 0.0;

        long vWire = telemetryVanillaWireZlibBytes.get();
        long lWire = telemetryLcaWireLcd2Bytes.get();
        double wireRed = vWire > 0 ? (1.0 - ((double) lWire / (double) vWire)) * 100.0 : 0.0;

        return new DevTelemetrySnapshot(
                telemetryChunksSampled.get(),
                telemetrySectionsSampled.get(),
                telemetryUniformSections.get(),
                telemetryNonUniformSections.get(),
                vMem,
                lMem,
                memRed,
                telemetryBatchesSampled.get(),
                vWire,
                lWire,
                wireRed,
                telemetryCleanRevisitAckBytes.get()
        );
    }

    public synchronized DevTelemetrySnapshot flushTelemetryReport(
            Consumer<String> jsonConsumerOrNull,
            boolean logSummary
    ) {
        if (jsonConsumerOrNull != null) {
            drainAllPendingChunks();
        }
        if (!pendingBatchEntries.isEmpty()) {
            flushPendingWireBatchSample();
        }
        DevTelemetrySnapshot snapshot = snapshotTelemetry();
        if (snapshot.chunksSampled() <= 0) {
            return snapshot;
        }
        if (jsonConsumerOrNull != null) {
            jsonConsumerOrNull.accept(snapshot.toJson());
        }
        if (logSummary) {
            logInfo(snapshot.toLogSummary());
        }
        return snapshot;
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

    private static boolean isStateNonEmpty(int stateId) {
        return stateId != 0 && !Block.stateById(stateId).isAir();
    }

    private static boolean isStateRandomTickingBlock(int stateId) {
        return stateId != 0 && Block.stateById(stateId).isRandomlyTicking();
    }

    private static boolean isStateRandomTickingFluid(int stateId) {
        return stateId != 0 && Block.stateById(stateId).getFluidState().isRandomlyTicking();
    }

    private void onServerChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null || this.runtime == null) {
            return;
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if (!EAGER_COMPACT_WITHOUT_CONSUMERS
                && !coord.hasActiveWireOrShardConsumers()
                && coord.activeChunkCount() == 0
                && coord.pendingChunkCount() == 0
                && coord.inFlightCompactionCount() == 0) {
            return;
        }
        coord.markChunkUnloaded(
                worldName(level),
                chunk.getPos().x(),
                chunk.getPos().z()
        );
    }

    private void registerLevelLoadCallback() {
        for (String clsName : new String[]{
                "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents",
                "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents"
        }) {
            try {
                Class<?> holder = Class.forName(clsName);
                Field loadField = holder.getField("LOAD");
                Object event = loadField.get(null);
                Method invokerMethod = event.getClass().getMethod("invoker");
                invokerMethod.setAccessible(true);
                Object invoker = invokerMethod.invoke(event);
                Class<?> listenerInterface = null;
                for (Class<?> iface : invoker.getClass().getInterfaces()) {
                    listenerInterface = iface;
                    break;
                }
                if (listenerInterface == null) continue;
                Object proxy = Proxy.newProxyInstance(
                        listenerInterface.getClassLoader(),
                        new Class<?>[]{listenerInterface},
                        (p, m, args) -> {
                            if (args != null && args.length >= 2
                                    && args[0] instanceof MinecraftServer srv
                                    && args[1] instanceof ServerLevel lvl) {
                                attachServerLevel(srv, lvl);
                            }
                            return null;
                        });
                Method reg = event.getClass().getMethod("register", Object.class);
                reg.setAccessible(true);
                reg.invoke(event, proxy);
                return;
            } catch (Throwable t) {
                if (this.runtime != null) {
                    this.runtime.accessor().log(
                            Level.FINE,
                            "[LeafAnvil] Optional Fabric level event class not present: " + clsName,
                            t
                    );
                }
            }
        }
    }

    private void attachServerLevel(MinecraftServer server, ServerLevel level) {
        if (server == null || level == null || this.runtime == null) {
            return;
        }
        String name = worldName(level);
        attachedDimensions.add(name);
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path dimDir = resolveDimensionDirectory(root, level);
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY() - 1;
        int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
        this.runtime.attachAndInterceptWorld(name, dimDir, minSectionY, sectionCount, level);
    }

    private static String worldName(ServerLevel level) {
        Identifier id = level.dimension().identifier();
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static Path resolveDimensionDirectory(Path worldRoot, ServerLevel level) {
        Identifier id = level.dimension().identifier();
        String ns = id.getNamespace();
        String path = id.getPath();
        if ("minecraft".equals(ns)) {
            if ("overworld".equals(path)) return worldRoot;
            if ("the_nether".equals(path)) return worldRoot.resolve("DIM-1");
            if ("the_end".equals(path)) return worldRoot.resolve("DIM1");
        }
        return worldRoot.resolve("dimensions").resolve(ns).resolve(path);
    }

    public LeafAnvilRuntime runtime() {
        return runtime;
    }

    /**
     * Computes a deterministic 64-bit parity hash over {@link Block#BLOCK_STATE_REGISTRY} so
     * server and client verify exact block-state ID parity before activating
     * {@link LcaWireStreamProtocol#CAP_LCA_SECTION_STREAM} (`leafanvil-ADR-003` Section 1).
     */
    public static long computeBlockStateRegistryParityHash() {
        long hash = 0xCBF29CE484222325L;
        int size = Block.BLOCK_STATE_REGISTRY.size();
        hash = (hash ^ size) * 0x100000001B3L;
        for (int id = 0; id < size; id++) {
            BlockState state = Block.stateById(id);
            int actualId = Block.getId(state);
            hash = (hash ^ (((long) id << 32) | (actualId & 0xFFFFFFFFL))) * 0x100000001B3L;
        }
        return hash;
    }

    /**
     * Negotiates a client `.lca` chunk streaming session against the current runtime and
     * {@link #computeBlockStateRegistryParityHash()}, falling back to vanilla chunk packets for
     * unmodified clients or mismatched registries (`leafanvil-ADR-003` Section 1).
     */
    public NegotiatedSession negotiateWireSession(
            HandshakePayload clientHandshake
    ) {
        if (this.runtime == null) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        return this.runtime.negotiateWireSession(clientHandshake, computeBlockStateRegistryParityHash());
    }
}
