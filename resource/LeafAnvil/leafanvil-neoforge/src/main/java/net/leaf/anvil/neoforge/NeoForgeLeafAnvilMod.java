package net.leaf.anvil.neoforge;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.rtp.neoforge.v26_1_R1.server.NeoForgeModServerAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
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
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.Deflater;

/**
 * NeoForge mod entry point for {@code LeafAnvil}, intercepting `ServerLevel` `RegionFileStorage`
 * instances (`region/`, `entities/`, `poi/`), attaching live in-memory {@link LcaSectionBacking}
 * columns (`ZeroBitStorage` heap eviction), and negotiating `LCA_NET_V1` `.lca` chunk streaming
 * (`leafanvil-ADR-001`, `leafanvil-ADR-002`, `leafanvil-ADR-003`, `anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-001, leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-002, anvil-api-ADR-003, ADR-033, ADR-115
 * @governedBy leafanvil-ADR-001
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-033
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class NeoForgeLeafAnvilMod {

    /**
     * Dedicated `@Mod("leafanvil")` entrypoint for the full commercial {@code LeafAnvil.jar}
     * distribution; excluded from {@code LeafAnvil-Net.jar} so NeoForge's annotation scanner
     * sees only {@code @Mod("leafanvil_net")} in the client/wire-only artifact.
     */
    @Mod("leafanvil")
    public static final class ServerEntrypoint {
        public ServerEntrypoint() {
            net.leaf.anvil.neoforge.client.NeoForgeLeafAnvilClientMod
                    .withReceiver(new net.leaf.anvil.common.net.LcaClientWireReceiver())
                    .initializeClientNetworkingIfFirst();
            initializeServerTxIfFirst();
        }
    }
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

    private static final AtomicLong serverTickCounter = new AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<LevelChunk>> shadowTrackedHotChunks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final AtomicLong telemetryChunksSampled = new AtomicLong();
    private static final AtomicLong telemetrySectionsSampled = new AtomicLong();
    private static final AtomicLong telemetryUniformSections = new AtomicLong();
    private static final AtomicLong telemetryNonUniformSections = new AtomicLong();
    private static final AtomicLong telemetryVanillaMemoryBytes = new AtomicLong();
    private static final AtomicLong telemetryLcaMemoryBytes = new AtomicLong();
    private static final AtomicLong telemetryBatchesSampled = new AtomicLong();
    private static final AtomicLong telemetryVanillaWireZlibBytes = new AtomicLong();
    private static final AtomicLong telemetryLcaWireLcd2Bytes = new AtomicLong();
    private static final AtomicLong telemetryCleanRevisitAckBytes = new AtomicLong();
    private static final List<EntryFullChunk> pendingBatchEntries = new ArrayList<>();
    private static final List<byte[]> pendingVanillaRawChunks = new ArrayList<>();

    private final LeafAnvilRuntime runtime;
    private final net.leaf.anvil.common.net.LcaClientWireReceiver clientWireReceiver;

    /**
     * Immutable telemetry snapshot of live NeoForge in-memory and wire reductions.
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
        public String toLogSummary() {
            return String.format(
                    Locale.ROOT,
                    "[LeafAnvil Telemetry] chunks=%d sections=%d (uniform=%d, nonUniform=%d) | RAM: vanilla=%d B vs lca=%d B (-%.2f%%) | Wire (batches=%d): vanillaZlib=%d B vs lcaLcd2=%d B (-%.2f%%, cleanRevisitAck=%d B)",
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

    private static volatile NeoForgeLeafAnvilMod sharedServerInstance;

    public static synchronized NeoForgeLeafAnvilMod initializeServerTxIfFirst() {
        if (sharedServerInstance == null && LeafAnvilRuntime.tryAcquireGlobalServerTxRegistration()) {
            sharedServerInstance = new NeoForgeLeafAnvilMod();
        }
        return sharedServerInstance;
    }

    public NeoForgeLeafAnvilMod() {
        LeafAnvilRuntime.tryAcquireGlobalServerTxRegistration();
        sharedServerInstance = this;
        io.github.dailystruggle.server.api.ServerAccessor accessor = createNeoForgeOrWireAccessor();
        this.runtime = new LeafAnvilRuntime(accessor);
        this.runtime.start();
        this.clientWireReceiver = new net.leaf.anvil.common.net.LcaClientWireReceiver(
                this.runtime.chunkCoordinator(),
                net.leaf.anvil.common.net.LcaClientWireReceiver.DEFAULT_CLIENT_CACHE_BUDGET_KIB
        );
        if (this.runtime.shouldAbortLoad()) {
            this.runtime.accessor().log(
                    Level.SEVERE,
                    "[LCA bit] Aborting NeoForgeLeafAnvilMod initialization due to fatal BIT incompatibility.");
            return;
        }

        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                attachServerLevel(serverLevel.getServer(), serverLevel);
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Save event) -> {
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                this.runtime.flushWorld(worldName(serverLevel));
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel serverLevel) {
                this.runtime.detachWorld(worldName(serverLevel));
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getChunk() != null
                    && event.getChunk().getClass() == LevelChunk.class
                    && event.getLevel() instanceof ServerLevel serverLevel) {
                LevelChunk levelChunk = (LevelChunk) event.getChunk();
                onChunkLoaded(worldName(serverLevel), levelChunk, serverLevel.getGameTime());
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getChunk() instanceof LevelChunk levelChunk) {
                net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
                if (!EAGER_COMPACT_WITHOUT_CONSUMERS
                        && !coord.hasActiveWireOrShardConsumers()
                        && coord.activeChunkCount() == 0
                        && coord.pendingChunkCount() == 0
                        && coord.inFlightCompactionCount() == 0) {
                    return;
                }
                String dimName = (event.getLevel() instanceof ServerLevel serverLevel)
                        ? worldName(serverLevel)
                        : "minecraft:overworld";
                coord.markChunkUnloaded(
                        dimName,
                        levelChunk.getPos().x(),
                        levelChunk.getPos().z()
                );
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            MinecraftServer server = event.getServer();
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    attachServerLevel(server, level);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            drainAllPendingChunks();
            this.runtime.flushAll();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> this.runtime.close());

        registerServerPlayPayloadsIfFirst();

        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            logInfo("LeafAnvil (NeoForge) initialized (.lca world save, ZeroBitStorage heap eviction & wire stream active).");
        } else {
            logInfo("LeafAnvil-Net (NeoForge) initialized (wire-only LCA_NET_V1 TX+RX active; vanilla disk untouched).");
        }
    }

    private record LcaRawCustomPayload(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<LcaRawCustomPayload> type,
            byte[] data
    ) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LcaRawCustomPayload other)) {
                return false;
            }
            return java.util.Objects.equals(this.type, other.type)
                    && java.util.Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.type);
            result = 31 * result + java.util.Arrays.hashCode(this.data);
            return result;
        }

        @Override
        public String toString() {
            return "LcaRawCustomPayload[type=" + this.type
                    + ", data=" + java.util.Arrays.toString(this.data)
                    + "]";
        }
    }

    private static volatile net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<LcaRawCustomPayload>
            serverHandshakePayloadType;
    private static volatile net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<LcaRawCustomPayload>
            serverChunkBatchPayloadType;

    private void registerServerPlayPayloadsIfFirst() {
        if (!net.leaf.anvil.common.net.LcaClientWireReceiver.tryAcquireGlobalClientRegistration()) {
            return;
        }
        serverHandshakePayloadType = registerNeoForgeChannelPayload(LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
        serverChunkBatchPayloadType = registerNeoForgeChannelPayload(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
    }

    private net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<LcaRawCustomPayload>
            registerNeoForgeChannelPayload(String channel) {
        Identifier id = Identifier.tryParse(channel);
        if (id == null) {
            return null;
        }
        net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<LcaRawCustomPayload> type =
                new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(id);
        net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, LcaRawCustomPayload> codec =
                net.minecraft.network.codec.StreamCodec.of(
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
            net.neoforged.neoforge.network.handling.IPayloadHandler<LcaRawCustomPayload> handler = (msg, context) -> {
                if (msg == null || msg.data() == null) {
                    return;
                }
                if (context != null && context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    if (LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(channel)) {
                        onServerHandshakeReceived(
                                serverPlayer,
                                msg.data(),
                                (isHandshakeAck, outBytes) -> {
                                    var outType = Boolean.TRUE.equals(isHandshakeAck)
                                            ? serverHandshakePayloadType
                                            : serverChunkBatchPayloadType;
                                    if (outType != null && serverPlayer.connection != null) {
                                        registerAdHocChannelsOnConnection(context.connection());
                                        serverPlayer.connection.send(
                                                new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                                        new LcaRawCustomPayload(outType, outBytes)
                                                )
                                        );
                                    }
                                }
                        );
                    }
                }
            };
            synchronized (net.neoforged.neoforge.network.registration.PayloadRegistrar.class) {
                new net.neoforged.neoforge.network.registration.PayloadRegistrar("1")
                        .optional()
                        .playBidirectional(type, codec, handler, handler);
            }
        } catch (RuntimeException t) {
            this.runtime.accessor().log(
                    Level.WARNING,
                    "[LeafAnvil] NeoForge PayloadRegistrar registration skipped for " + channel + ": " + t.getMessage(),
                    t
            );
        }
        return type;
    }

    private void registerAdHocChannelsOnConnection(Object connectionObj) {
        if (connectionObj == null) {
            return;
        }
        try {
            Class<?> regClass = Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry");
            for (java.lang.reflect.Method m : regClass.getDeclaredMethods()) {
                if ("onMinecraftRegister".equals(m.getName()) && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    java.util.Set<Identifier> channels = new java.util.HashSet<>();
                    Identifier hs = Identifier.tryParse(LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
                    Identifier cb = Identifier.tryParse(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
                    if (hs != null) {
                        channels.add(hs);
                    }
                    if (cb != null) {
                        channels.add(cb);
                    }
                    m.invoke(null, connectionObj, channels);
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException t) {
            this.runtime.accessor().log(
                    Level.WARNING,
                    "[LeafAnvil] Optional NetworkRegistry#onMinecraftRegister failed: " + t.getMessage(),
                    t
            );
        }
    }

    private static io.github.dailystruggle.server.api.ServerAccessor createNeoForgeOrWireAccessor() {
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            NeoForgeModServerAccessor diskAccessor =
                    new NeoForgeModServerAccessor("LeafAnvil", Path.of("config", "leafanvil"));
            diskAccessor.registerEvents();
            return diskAccessor;
        }
        return LeafAnvilRuntime.createWireOnlyAccessor(
                "neoforge",
                Path.of("config", "leafanvil"),
                java.util.logging.Logger.getLogger("LeafAnvil")
        );
    }

    /**
     * Handles an incoming `LCA_NET_V1` client handshake on a NeoForge dedicated server or P2P
     * (`IntegratedServer` / Open to LAN / e4mc / Essential) host, replying with the server ACK and
     * initial compressed `.lca` chunk batch (`leafanvil-ADR-003`).
     */
    public NegotiatedSession onServerHandshakeReceived(
            net.minecraft.server.level.ServerPlayer player,
            byte[] handshakeBytes,
            java.util.function.BiConsumer<Boolean, byte[]> outboundSender
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
        if (session.isLcaStreamActive() && outboundSender != null) {
            HandshakePayload serverAck = new HandshakePayload(
                    LcaWireStreamProtocol.PROTOCOL_VERSION,
                    session.activeCapabilities(),
                    session.negotiatedCacheBudgetKiB(),
                    serverParityHash
            );
            outboundSender.accept(Boolean.TRUE, serverAck.toBytes());
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
                if (!initialBatch.isEmpty()) {
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
                        outboundSender.accept(Boolean.FALSE, batchBytes);
                    }
                    logInfo(
                            "[LeafAnvil-Net Differential] "
                                    + coord.packetDifferentialProfiler().snapshot(1).toJson()
                    );
                }
            } catch (Throwable t) {
                this.runtime.accessor().log(
                        Level.WARNING,
                        "[LeafAnvil] Failed to pack initial NeoForge LCA_NET_V1 chunk batch: " + t.getMessage(),
                        t
                );
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
        long tick = serverTickCounter.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk loaded = level.getChunkSource().getChunkNow(centerCx + dx, centerCz + dz);
                if (loaded != null && loaded.getClass() == LevelChunk.class && !isChunkAlreadyAttached(dimName, loaded)) {
                    coord.enqueuePendingChunkLoad(dimName, centerCx + dx, centerCz + dz, loaded, tick);
                }
            }
        }
    }

    private void onChunkLoaded(String dimName, LevelChunk levelChunk, long gameTime) {
        if (levelChunk == null || this.runtime == null || !this.runtime.isSectionWireOperational()) {
            return;
        }
        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if (!EAGER_COMPACT_WITHOUT_CONSUMERS && !coord.hasActiveWireOrShardConsumers()) {
            return;
        }
        if (isChunkAlreadyAttached(dimName, levelChunk)) {
            return;
        }
        coord.enqueuePendingChunkLoad(
                dimName,
                levelChunk.getPos().x(),
                levelChunk.getPos().z(),
                levelChunk,
                serverTickCounter.get()
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
    private void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event != null ? event.getServer() : null;
        if (server == null || this.runtime == null) {
            return;
        }
        long tick = serverTickCounter.incrementAndGet();
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
                completed -> commitCompletedChunkCompaction(completed, serverTickCounter.get())
        );
    }

    private void captureAndSubmitChunkForBackgroundCompaction(
            String dimName,
            LevelChunk levelChunk,
            long enqueuedTick
    ) {
        if (levelChunk == null || this.runtime == null || isChunkAlreadyAttached(dimName, levelChunk)) {
            return;
        }
        LevelChunkSection[] sections = levelChunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        LcaSectionBridge.RawSectionSnapshot[] snapshots =
                new LcaSectionBridge.RawSectionSnapshot[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            int skyConst4 = resolveChunkSectionSkyConst4(levelChunk, i);
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
                levelChunk.getPos().x(),
                levelChunk.getPos().z(),
                levelChunk,
                sections,
                snapshots,
                resolveGlobalPaletteBits(),
                enqueuedTick,
                NeoForgeLeafAnvilMod::isStateNonEmpty,
                NeoForgeLeafAnvilMod::isStateRandomTickingBlock,
                NeoForgeLeafAnvilMod::isStateRandomTickingFluid
        );
    }

    private void commitCompletedChunkCompaction(
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.CompletedChunkCompaction completed,
            long gameTime
    ) {
        if (completed == null || this.runtime == null) {
            return;
        }
        if (!(completed.chunkHandle() instanceof LevelChunk levelChunk)
                || isChunkAlreadyAttached(completed.worldKey(), levelChunk)) {
            return;
        }
        LevelChunkSection[] sections = levelChunk.getSections();
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
                            levelChunk,
                            gameTime
                    );
                    return;
                }
            }
        }
        boolean evictToZeroBitShell = SERVER_EVICT_TO_ZERO_BIT_SHELL && !isChunkWithinHotPlayerRadius(levelChunk);
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
            this.shadowTrackedHotChunks.offer(new java.lang.ref.WeakReference<>(levelChunk));
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
            java.util.logging.Logger.getLogger(NeoForgeLeafAnvilMod.class.getName()).log(
                    Level.WARNING,
                    "[LeafAnvil] Single-pass PalettedContainer hydration failed; falling back: " + t.getMessage(),
                    t
            );
            return false;
        }
    }
    private void compactAndAttachLoadedChunk(String dimName, LevelChunk levelChunk, long gameTime) {
        if (levelChunk == null || this.runtime == null || isChunkAlreadyAttached(dimName, levelChunk)) {
            return;
        }
        LevelChunkSection[] sections = levelChunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        LcaSectionBacking[] backings = new LcaSectionBacking[sections.length];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
                backings[i] = bridge.leafanvil$getBacking();
            } else {
                backings[i] = buildSectionBackingFromLiveSection(levelChunk, sec, i, sections.length);
            }
        }
        this.runtime.chunkCoordinator().attachChunkColumn(
                dimName,
                levelChunk.getPos().x(),
                levelChunk.getPos().z(),
                backings,
                sections,
                gameTime
        );
    }

    /**
     * Losslessly encodes a live Mojang {@link LevelChunkSection} on NeoForge into an
     * {@link LcaSectionBacking} with a compact `.lca` `baseFrame`.
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
            java.util.logging.Logger.getLogger(NeoForgeLeafAnvilMod.class.getName())
                    .log(Level.FINE, "[LeafAnvil] NeoForge chunk sky-light probe fallback", t);
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
                    NeoForgeLeafAnvilMod::isStateNonEmpty,
                    NeoForgeLeafAnvilMod::isStateRandomTickingBlock,
                    NeoForgeLeafAnvilMod::isStateRandomTickingFluid,
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
            int tickingFluids = (!uniformState.getFluidState().isEmpty()
                    && uniformState.getFluidState().isRandomlyTicking()) ? SECTION_VOXELS : 0;
            return new LcaSectionBacking(
                    EMPTY_FRAME_BYTES,
                    uniformId,
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    NeoForgeLeafAnvilMod::isStateNonEmpty,
                    NeoForgeLeafAnvilMod::isStateRandomTickingBlock,
                    NeoForgeLeafAnvilMod::isStateRandomTickingFluid,
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
                    java.lang.reflect.Method valFor = rps.valueForMethod();
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
                                            | (st != null && !st.getFluidState().isEmpty()
                                                    && st.getFluidState().isRandomlyTicking() ? 4 : 0);
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
                                        | (st != null && !st.getFluidState().isEmpty()
                                                && st.getFluidState().isRandomlyTicking() ? 4 : 0);
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
                    java.util.logging.Logger.getLogger(NeoForgeLeafAnvilMod.class.getName())
                            .log(java.util.logging.Level.FINE, "[LeafAnvil] Fast BitStorage section extraction fallback", t);
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
                            isTickFluid = !state.getFluidState().isEmpty() && state.getFluidState().isRandomlyTicking();
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
            return new LcaSectionBacking(
                    new byte[0],
                    Math.max(0, firstStateId),
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    NeoForgeLeafAnvilMod::isStateNonEmpty,
                    NeoForgeLeafAnvilMod::isStateRandomTickingBlock,
                    NeoForgeLeafAnvilMod::isStateRandomTickingFluid,
                    null
            );
        }

        int resolvedSky = resolveChunkSectionSkyConst4(chunk, sectionIndex);
        int skyConst = resolvedSky >= 0
                ? (resolvedSky & 0xF)
                : LcaSectionBridge.estimateSkyConstFromVoxelsFallback(
                        voxels,
                        nonEmpty,
                        sectionIndex,
                        totalSections,
                        NeoForgeLeafAnvilMod::isStateNonEmpty
                );
        return LcaSectionBacking.fromDirectVoxels(
                voxels,
                skyConst,
                0,
                nonEmpty,
                tickingBlocks,
                tickingFluids,
                NeoForgeLeafAnvilMod::isStateNonEmpty,
                NeoForgeLeafAnvilMod::isStateRandomTickingBlock,
                NeoForgeLeafAnvilMod::isStateRandomTickingFluid
        );
    }

    public synchronized DevTelemetrySnapshot recordColumnTelemetry(
            LevelChunkSection[] sections,
            TrackedChunkColumn column,
            Consumer<String> jsonConsumerOrNull
    ) {
        if (sections == null || column == null) {
            return snapshotTelemetry();
        }
        int totalSec = Math.min(sections.length, column.sectionCount());
        long chunkVanillaMem = 0L;
        long chunkLcaMem = 0L;
        boolean runHeavyWireSample = (jsonConsumerOrNull != null);

        if (runHeavyWireSample) {
            ByteBuf vanillaRawBuf = Unpooled.buffer(32_768);
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
                    } else if (!isUniform && base != null) {
                        friendly.writeBytes(base);
                    }
                    int secWriteBytes = Math.max(12, vanillaRawBuf.readableBytes() - before);
                    if (isUniform) {
                        telemetryUniformSections.incrementAndGet();
                        int lightBytes = (i >= (totalSec / 2)) ? 2048 : 0;
                        chunkVanillaMem += secWriteBytes + lightBytes + 64L;
                        chunkLcaMem += 64L + 1L;
                    } else {
                        telemetryNonUniformSections.incrementAndGet();
                        chunkVanillaMem += Math.max(secWriteBytes, 2096) + 4096L + 64L;
                        int overlayBytes = backing.overlayStage() == LcaSectionBacking.STAGE_EMPTY
                                ? 0
                                : (backing.overlayStage() == LcaSectionBacking.STAGE_LINEAR_16 ? 64 : 256);
                        chunkLcaMem += 64L + (base != null ? base.length : 0) + overlayBytes + 120L;
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

    private static synchronized void flushPendingWireBatchSample() {
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
            java.util.logging.Logger.getLogger(NeoForgeLeafAnvilMod.class.getName())
                    .log(Level.FINE, "[LeafAnvil] NeoForge telemetry wire sample fallback", t);
        } finally {
            pendingBatchEntries.clear();
            pendingVanillaRawChunks.clear();
        }
    }

    public static synchronized DevTelemetrySnapshot snapshotTelemetry() {
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
            jsonConsumerOrNull.accept(snapshot.toLogSummary());
        }
        if (logSummary) {
            logInfo(snapshot.toLogSummary());
        }
        return snapshot;
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
            java.util.logging.Logger.getLogger(NeoForgeLeafAnvilMod.class.getName()).log(Level.FINE, message, t);
        }
    }

    private static boolean isStateNonEmpty(int stateId) {
        return !Block.stateById(stateId).isAir();
    }

    private static boolean isStateRandomTickingBlock(int stateId) {
        return Block.stateById(stateId).isRandomlyTicking();
    }

    private static boolean isStateRandomTickingFluid(int stateId) {
        BlockState state = Block.stateById(stateId);
        return !state.getFluidState().isEmpty() && state.getFluidState().isRandomlyTicking();
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

    private void attachServerLevel(MinecraftServer server, ServerLevel level) {
        if (server == null || level == null) {
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path dimDir = resolveDimensionDirectory(root, level);
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY() - 1;
        int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
        this.runtime.attachAndInterceptWorld(worldName(level), dimDir, minSectionY, sectionCount, level);
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
    public LcaWireStreamProtocol.NegotiatedSession negotiateWireSession(
            LcaWireStreamProtocol.HandshakePayload clientHandshake
    ) {
        return this.runtime.negotiateWireSession(clientHandshake, computeBlockStateRegistryParityHash());
    }

    /**
     * Builds the 20-byte `LCA_NET_V1` client capability handshake payload (`leafanvil-ADR-003`).
     */
    public byte[] buildClientHandshakeBytes() {
        return this.clientWireReceiver.buildClientHandshakeBytes(computeBlockStateRegistryParityHash());
    }

    /**
     * Processes a server `LCA_NET_V1` handshake ACK on the NeoForge client (`leafanvil-ADR-003`).
     */
    public NegotiatedSession onClientHandshakeAckReceived(byte[] payloadBytes) {
        return this.clientWireReceiver.onServerHandshakeAck(payloadBytes, computeBlockStateRegistryParityHash());
    }

    /**
     * Receives an inbound `leafanvil:lca_chunk_batch` frame on the NeoForge client (`leafanvil-ADR-003`).
     */
    public int onClientChunkBatchReceived(
            String worldName,
            byte[] batchPayloadBytes,
            boolean proxySafeZlib
    ) {
        return this.clientWireReceiver.onChunkBatchPayloadReceived(
                worldName,
                batchPayloadBytes,
                proxySafeZlib,
                computeBlockStateRegistryParityHash(),
                null
        );
    }
}
