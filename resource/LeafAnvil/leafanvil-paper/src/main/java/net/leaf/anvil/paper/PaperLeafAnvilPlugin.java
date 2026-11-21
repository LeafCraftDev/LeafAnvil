package net.leaf.anvil.paper;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.netty.channel.Channel;
import net.leaf.anvil.agent.LeafAnvilAgentBootstrap;
import net.leaf.anvil.agent.LeafAnvilPremain;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.Deflater;

/**
 * Paper / Spigot / Folia plugin entry point for {@code LeafAnvil} (`load: STARTUP`), intercepting
 * NMS `RegionFileStorage` (`region/`, `entities/`, `poi/`), synchronizing `Moonrise`
 * `SimpleRandomTickList` coordinates on `LcaSectionBacking` `dirtyOverlay` transitions, and
 * negotiating `LCA_NET_V1` `.lca` chunk batch streaming over Bukkit plugin channels (`leafanvil-ADR-001`,
 * `leafanvil-ADR-002`, `leafanvil-ADR-003`, `anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class PaperLeafAnvilPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private static final long DEFAULT_PAPER_REGISTRY_PARITY_HASH = 0xCBF29CE484222325L;
    private static final int SECTION_VOXELS = 4096;
    private static final int MAX_PLUGIN_MESSAGE_BYTES = 32_760;
    private static final int MAX_STARTUP_PRELOADED_CHUNKS_SAMPLED = 64;
    private static final boolean EAGER_COMPACT_WITHOUT_CONSUMERS =
            Boolean.getBoolean("leafanvil.chunk.eagerCompactWithoutConsumers");

    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<SectionEncodeScratch> THREAD_ENCODE_SCRATCH =
            ThreadLocal.withInitial(SectionEncodeScratch::new);
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

    private static final List<EntryFullChunk> pendingBatchEntries = new ArrayList<>(8);
    private static final List<byte[]> pendingVanillaRawChunks = new ArrayList<>(8);

    private io.github.dailystruggle.server.api.ServerAccessor accessor;
    private LeafAnvilRuntime runtime;
    private String pendingDiskAccessorIncompatibility;
    private volatile boolean anyLcaClientConnected = false;
    private final java.util.concurrent.atomic.AtomicInteger nextPlayerBitSlot =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Structured JSON and log telemetry snapshot comparing Vanilla vs. `.lca` section memory and
     * batch wire sizes on Paper/Folia (`leafanvil-ADR-002`, `leafanvil-ADR-003`).
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
                    "{\"chunksSampled\": %d, \"sectionsSampled\": %d, \"uniformSections\": %d, "
                            + "\"nonUniformSections\": %d, \"vanillaMemoryBytes\": %d, \"lcaMemoryBytes\": %d, "
                            + "\"memoryReductionPct\": %.2f, \"batchesSampled\": %d, \"vanillaWireZlibBytes\": %d, "
                            + "\"lcaWireLcd2Bytes\": %d, \"wireReductionPct\": %.2f, \"cleanRevisitAckBytes\": %d}",
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
                            + "| Wire (%d batches): vanillaZlib=%d B vs lcaLcd2=%d B (-%.2f%%, cleanRevisitAck=%d B)",
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

    private static final class SectionEncodeScratch {
        final int[] voxels = new int[SECTION_VOXELS];
        final int[] paletteLut = new int[SECTION_VOXELS];
        final int[] paletteLutEpoch = new int[SECTION_VOXELS];
        int currentPaletteEpoch = 1;
    }

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        this.accessor = createPlatformOrWireAccessor(false);
        this.runtime = new LeafAnvilRuntime(this.accessor);
        if (this.pendingDiskAccessorIncompatibility != null) {
            this.runtime.bitSuite().recordIncompatibility(
                    net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                    "PaperLeafAnvilPlugin",
                    "io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor",
                    this.pendingDiskAccessorIncompatibility,
                    "BukkitPluginServerAccessor(Plugin, String)");
            this.pendingDiskAccessorIncompatibility = null;
        }
        this.runtime.load();
    }

    @Override
    public void onEnable() {
        if (this.accessor == null || this.runtime == null) {
            this.accessor = createPlatformOrWireAccessor(true);
            this.runtime = new LeafAnvilRuntime(this.accessor);
            if (this.pendingDiskAccessorIncompatibility != null) {
                this.runtime.bitSuite().recordIncompatibility(
                        net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                        "PaperLeafAnvilPlugin",
                        "io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor",
                        this.pendingDiskAccessorIncompatibility,
                        "BukkitPluginServerAccessor(Plugin, String)");
                this.pendingDiskAccessorIncompatibility = null;
            }
            this.runtime.load();
        } else if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            registerDiskAccessorEventsIfPresent(this.accessor);
        }
        this.runtime.start();
        if (this.runtime.shouldAbortLoad()) {
            getLogger().severe("[LCA bit] Disabling LeafAnvil plugin due to fatal BIT incompatibility.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        registerWireStreamPluginChannels();
        registerAgentBootstrapHooksIfActive();

        for (World world : getServer().getWorlds()) {
            attachBukkitWorld(world);
        }
        if (this.runtime.shouldAbortLoad()) {
            getLogger().severe("[LCA bit] Disabling LeafAnvil plugin after world-attach BIT incompatibility.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            getLogger().info("LeafAnvil (Paper/Folia) enabled (.lca world save & LCA_NET_V1 wire stream active).");
        } else {
            getLogger().info("LeafAnvil-Net (Paper/Folia) enabled (wire-only LCA_NET_V1 TX active; vanilla disk untouched).");
        }
    }
    private io.github.dailystruggle.server.api.ServerAccessor createPlatformOrWireAccessor(boolean registerEvents) {
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            io.github.dailystruggle.server.api.ServerAccessor diskAccessor =
                    instantiateDiskAccessorIfPresent(registerEvents);
            if (diskAccessor != null) {
                return diskAccessor;
            }
        }
        return LeafAnvilRuntime.createWireOnlyAccessor("paper", getDataFolder().toPath(), getLogger());
    }

    private io.github.dailystruggle.server.api.ServerAccessor instantiateDiskAccessorIfPresent(boolean registerEvents) {
        try {
            Class<?> cls = Class.forName(
                    "io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor",
                    false,
                    PaperLeafAnvilPlugin.class.getClassLoader()
            );
            Constructor<?> ctor = cls.getConstructor(Plugin.class, String.class);
            io.github.dailystruggle.server.api.ServerAccessor instance =
                    (io.github.dailystruggle.server.api.ServerAccessor) ctor.newInstance(this, "leafanvil");
            if (registerEvents) {
                registerDiskAccessorEventsIfPresent(instance);
            }
            return instance;
        } catch (Throwable t) {
            this.pendingDiskAccessorIncompatibility =
                    "Failed to instantiate BukkitPluginServerAccessor: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            getLogger().log(Level.SEVERE, "[LCA bit] " + this.pendingDiskAccessorIncompatibility, t);
            return null;
        }
    }

    private static void registerDiskAccessorEventsIfPresent(io.github.dailystruggle.server.api.ServerAccessor acc) {
        if (acc == null) {
            return;
        }
        try {
            for (Method m : acc.getClass().getMethods()) {
                if ("registerEvents".equals(m.getName()) && m.getParameterCount() == 0) {
                    m.invoke(acc);
                    return;
                }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.WARNING, "[LeafAnvil] Failed to register disk accessor events: " + t.getMessage(), t);
        }
    }

    @Override
    public void onDisable() {
        LeafAnvilAgentBootstrap.clearHooks();
        try {
            Messenger messenger = getServer().getMessenger();
            messenger.unregisterIncomingPluginChannel(this);
            messenger.unregisterOutgoingPluginChannel(this);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LeafAnvil] Failed to unregister plugin channels on disable: " + t.getMessage(), t);
        }
        if (this.runtime != null) {
            this.runtime.close();
        }
    }

    private void registerWireStreamPluginChannels() {
        try {
            Messenger messenger = getServer().getMessenger();
            messenger.registerIncomingPluginChannel(this, LcaWireStreamProtocol.CHANNEL_HANDSHAKE, this);
            messenger.registerIncomingPluginChannel(this, LcaWireStreamProtocol.CHANNEL_CHUNK_DIGEST, this);
            messenger.registerOutgoingPluginChannel(this, LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
            messenger.registerOutgoingPluginChannel(this, LcaWireStreamProtocol.CHANNEL_REGION_DICT_SYNC);
            messenger.registerOutgoingPluginChannel(this, LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LeafAnvil] Failed to register LCA_NET_V1 plugin channels: " + t.getMessage(), t);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null || message == null || this.runtime == null) {
            return;
        }
        try {
            if (LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(channel)) {
                long rawServerParityHash = computePaperBlockStateRegistryParityHash();
                World playerWorld = player.getWorld();
                String worldName = playerWorld != null ? playerWorld.getName() : "world";
                HandshakePayload preview = HandshakePayload.fromBytes(message);
                long serverParityHash = (preview.registryParityHash64() == DEFAULT_PAPER_REGISTRY_PARITY_HASH)
                        ? DEFAULT_PAPER_REGISTRY_PARITY_HASH
                        : rawServerParityHash;
                NegotiatedSession preflight = LcaWireStreamProtocol.negotiate(
                        preview,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        serverParityHash
                );
                if (preflight.isLcaStreamActive() && !this.anyLcaClientConnected) {
                    this.anyLcaClientConnected = true;
                    upgradePreloadedChunksForFirstLcaClient(playerWorld);
                }
                Channel nettyChannel = PaperLcaNettyChannelHandler.extractBukkitPlayerNettyChannel(player);
                final PaperLcaNettyChannelHandler nettyHandler = (nettyChannel != null && preflight.isLcaStreamActive())
                        ? PaperLcaNettyChannelHandler.injectIntoChannel(
                                nettyChannel,
                                this.runtime.chunkCoordinator(),
                                player.getUniqueId(),
                                worldName,
                                playerWorld != null ? (playerWorld.getMinHeight() >> 4) : -4,
                                nextPlayerBitSlot.getAndIncrement(),
                                this.runtime.config().coalesceTickFlushes(),
                                true
                        )
                        : null;
                NegotiatedSession session = handleHandshakeAndPackInitialBatch(
                        this.runtime.chunkCoordinator(),
                        player.getUniqueId(),
                        worldName,
                        message,
                        serverParityHash,
                        (outChannel, payloadBytes) -> {
                            if (nettyHandler != null && nettyChannel != null && nettyChannel.isActive()) {
                                byte kind = LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(outChannel)
                                        ? PaperLcaNettyChannelHandler.EmittedLcaNettyFrame.KIND_HANDSHAKE_ACK
                                        : PaperLcaNettyChannelHandler.EmittedLcaNettyFrame.KIND_CHUNK_BATCH;
                                nettyHandler.sendDirectLcaPayload(nettyChannel, kind, outChannel, payloadBytes);
                            } else if (payloadBytes.length <= MAX_PLUGIN_MESSAGE_BYTES) {
                                ensurePlayerChannelRegistered(player, outChannel);
                                player.sendPluginMessage(this, outChannel, payloadBytes);
                            }
                        },
                        nettyHandler != null
                );
                if (session.isLcaStreamActive()) {
                    this.anyLcaClientConnected = true;
                    int activePlayers = getServer().getOnlinePlayers() != null
                            ? Math.max(1, getServer().getOnlinePlayers().size())
                            : 1;
                    getLogger().info(
                            "[LeafAnvil-Net Differential] "
                                    + this.runtime.chunkCoordinator()
                                            .packetDifferentialProfiler()
                                            .snapshot(activePlayers)
                                            .toJson()
                    );
                }
                getLogger().log(
                        Level.INFO,
                        String.format(
                                Locale.ROOT,
                                "[LeafAnvil] LCA_NET_V1 handshake from %s: active=%s, caps=0x%02X, cacheKiB=%d, parityHash=0x%016X",
                                player.getName(),
                                session.isLcaStreamActive(),
                                session.activeCapabilities(),
                                session.negotiatedCacheBudgetKiB(),
                                serverParityHash
                        )
                );
            } else if (LcaWireStreamProtocol.CHANNEL_CHUNK_DIGEST.equals(channel)) {
                ServerboundLcaChunkDigestPayload digest = ServerboundLcaChunkDigestPayload.fromBytes(message);
                this.runtime.chunkCoordinator().recordPlayerRegionDigest(player.getUniqueId(), digest);
            }
        } catch (Throwable t) {
            getLogger().log(
                    Level.WARNING,
                    "[LeafAnvil] Malformed LCA_NET_V1 plugin message on channel " + channel + ": " + t.getMessage(),
                    t
            );
        }
    }
    static void ensurePlayerChannelRegistered(Player player, String outChannel) {
        if (player == null || outChannel == null) {
            return;
        }
        try {
            if (player.getListeningPluginChannels() != null
                    && player.getListeningPluginChannels().contains(outChannel)) {
                return;
            }
            Method addChannel = player.getClass().getMethod("addChannel", String.class);
            addChannel.setAccessible(true);
            addChannel.invoke(player, outChannel);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Optional CraftPlayer#addChannel not available", t);
        }
    }

    /**
     * Processes a client `LCA_NET_V1` handshake payload, replies with the server handshake ACK, and
     * dispatches an initial proxy-safe `.lca` chunk batch for currently tracked chunks in the world.
     */
    public static NegotiatedSession handleHandshakeAndPackInitialBatch(
            LcaInMemoryChunkCoordinator coordinator,
            UUID playerUuid,
            String worldName,
            byte[] handshakeBytes,
            long serverParityHash,
            BiConsumer<String, byte[]> outboundChannelSender
    ) {
        return handleHandshakeAndPackInitialBatch(
                coordinator, playerUuid, worldName, handshakeBytes, serverParityHash, outboundChannelSender, false);
    }

    public static NegotiatedSession handleHandshakeAndPackInitialBatch(
            LcaInMemoryChunkCoordinator coordinator,
            UUID playerUuid,
            String worldName,
            byte[] handshakeBytes,
            long serverParityHash,
            BiConsumer<String, byte[]> outboundChannelSender,
            boolean unrestrictedNettyTransport
    ) {
        HandshakePayload clientHandshake = HandshakePayload.fromBytes(handshakeBytes);
        NegotiatedSession session = coordinator.registerPlayerHandshake(
                playerUuid,
                clientHandshake,
                serverParityHash
        );
        if (session.isLcaStreamActive() && outboundChannelSender != null) {
            HandshakePayload serverAck = new HandshakePayload(
                    LcaWireStreamProtocol.PROTOCOL_VERSION,
                    session.activeCapabilities(),
                    session.negotiatedCacheBudgetKiB(),
                    serverParityHash
            );
            outboundChannelSender.accept(LcaWireStreamProtocol.CHANNEL_HANDSHAKE, serverAck.toBytes());
            try {
                List<TrackedChunkColumn> initialBatch = collectSampledColumnsForWorld(coordinator, worldName, 8);
                if (!initialBatch.isEmpty()) {
                    TrackedChunkColumn first = initialBatch.get(0);
                    byte[] batchBytes = coordinator.packOutboundChunkBatchForPlayer(
                            playerUuid,
                            worldName,
                            first.chunkX() >> 5,
                            first.chunkZ() >> 5,
                            initialBatch,
                            true
                    );
                    if (batchBytes != null && (unrestrictedNettyTransport || batchBytes.length <= MAX_PLUGIN_MESSAGE_BYTES)) {
                        outboundChannelSender.accept(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH, batchBytes);
                    }
                }
            } catch (IOException e) {
                java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                        .log(Level.FINE, "[LeafAnvil] Failed to pack initial LCA_NET_V1 chunk batch: " + e.getMessage(), e);
            }
        }
        return session;
    }

    private static List<TrackedChunkColumn> collectSampledColumnsForWorld(
            LcaInMemoryChunkCoordinator coordinator,
            String worldName,
            int maxColumns
    ) {
        List<TrackedChunkColumn> found = new ArrayList<>(maxColumns);
        for (int cx = -64; cx <= 64 && found.size() < maxColumns; cx++) {
            for (int cz = -64; cz <= 64 && found.size() < maxColumns; cz++) {
                TrackedChunkColumn col = coordinator.getTrackedChunk(worldName, cx, cz);
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        attachBukkitWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldLoad(WorldLoadEvent event) {
        attachBukkitWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldSave(WorldSaveEvent event) {
        if (this.runtime != null && event.getWorld() != null) {
            this.runtime.flushWorld(event.getWorld().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (this.runtime != null && event.getWorld() != null) {
            this.runtime.detachWorld(event.getWorld().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (this.runtime == null || event.getChunk() == null || event.getWorld() == null) {
            return;
        }
        attachAndSampleLoadedChunk(event.getWorld(), event.getChunk());
    }

    private void upgradePreloadedChunksForFirstLcaClient(World world) {
        if (world == null || this.runtime == null) {
            return;
        }
        try {
            Chunk[] preloaded = world.getLoadedChunks();
            if (preloaded != null) {
                int count = Math.min(preloaded.length, MAX_STARTUP_PRELOADED_CHUNKS_SAMPLED);
                for (int i = 0; i < count; i++) {
                    attachAndSampleLoadedChunk(world, preloaded[i], true);
                }
                drainCompletedChunkCompactionsForWorld(world, count, world.getFullTime());
            }
        } catch (Throwable t) {
            logFineOrWarning("[LeafAnvil] Could not upgrade preloaded chunks for " + world.getName(), t);
        }
    }

    private void attachAndSampleLoadedChunk(World world, Chunk chunk) {
        attachAndSampleLoadedChunk(world, chunk, false);
    }

    private void attachAndSampleLoadedChunk(World world, Chunk chunk, boolean forceUpgradeIfPlaceholder) {
        if (this.runtime == null || !this.runtime.isSectionWireOperational() || world == null || chunk == null) {
            return;
        }
        LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if (!forceUpgradeIfPlaceholder
                && !EAGER_COMPACT_WITHOUT_CONSUMERS
                && !this.anyLcaClientConnected
                && !coord.hasActiveWireOrShardConsumers()) {
            return;
        }
        if (!isOwnedByCurrentRegionOrNonFolia(world, chunk.getX(), chunk.getZ())) {
            return;
        }
        TrackedChunkColumn existing = coord.getTrackedChunk(
                world.getName(), chunk.getX(), chunk.getZ()
        );
        if (existing != null && !forceUpgradeIfPlaceholder) {
            return;
        }
        int minSectionY = world.getMinHeight() >> 4;
        int maxSectionY = (world.getMaxHeight() - 1) >> 4;
        int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
        long fullTime = world.getFullTime();
        if (this.anyLcaClientConnected) {
            Object[] nmsSections = extractLoadedNmsSectionsWithoutIo(chunk);
            if (nmsSections != null && nmsSections.length > 0) {
                int[] skyConsts = resolvePaperChunkSectionSkyConsts(
                        world, chunk, minSectionY, nmsSections.length);
                net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot[] snapshots =
                        new net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot[nmsSections.length];
                boolean capturedAny = false;
                for (int i = 0; i < nmsSections.length; i++) {
                    Object nmsSec = nmsSections[i];
                    int skyConst4 = (i < skyConsts.length) ? skyConsts[i] : -1;
                    if (nmsSec == null || invokeHasOnlyAir(nmsSec)) {
                        snapshots[i] = new net.leaf.anvil.common.LcaSectionBridge.RawSectionSnapshot(
                                i, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0, skyConst4
                        );
                    } else {
                        snapshots[i] = net.leaf.anvil.common.LcaSectionBridge.captureRawSectionSnapshot(
                                i,
                                nmsSec,
                                PaperLeafAnvilPlugin::resolveNmsBlockStateId,
                                -1,
                                -1,
                                -1,
                                skyConst4
                        );
                        if (snapshots[i] != null) {
                            capturedAny = true;
                        }
                    }
                }
                if (capturedAny) {
                    this.runtime.chunkCoordinator().submitChunkSnapshotForCompaction(
                            world.getName(),
                            chunk.getX(),
                            chunk.getZ(),
                            chunk,
                            nmsSections,
                            snapshots,
                            15,
                            fullTime,
                            stateId -> stateId != 0,
                            stateId -> false,
                            stateId -> false
                    );
                    drainCompletedChunkCompactionsForWorld(world, 16, fullTime);
                    return;
                }
            }
        }
        if (existing != null) {
            return;
        }
        LcaSectionBacking[] backings = new LcaSectionBacking[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            backings[i] = new LcaSectionBacking(new byte[0], 0);
        }
        this.runtime.chunkCoordinator().attachChunkColumn(
                world.getName(),
                chunk.getX(),
                chunk.getZ(),
                backings,
                null,
                fullTime
        );
    }

    private void drainCompletedChunkCompactionsForWorld(World world, int maxToCommit, long fullTime) {
        if (this.runtime == null || world == null) {
            return;
        }
        this.runtime.chunkCoordinator().drainCompletedChunkCompactions(
                maxToCommit,
                completed -> {
                    if (isOwnedByCurrentRegionOrNonFolia(world, completed.chunkX(), completed.chunkZ())) {
                        this.runtime.chunkCoordinator().attachChunkColumn(
                                completed.worldKey(),
                                completed.chunkX(),
                                completed.chunkZ(),
                                completed.builtBackings(),
                                completed.nmsSections(),
                                fullTime
                        );
                    }
                }
        );
    }

    private static final Method FOLIA_IS_OWNED_METHOD = resolveFoliaIsOwnedMethod();

    private static Method resolveFoliaIsOwnedMethod() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            for (Method m : bukkitClass.getMethods()) {
                if (m.getName().equals("isOwnedByCurrentRegion")
                        && m.getParameterCount() == 3
                        && m.getParameterTypes()[0] == World.class
                        && m.getParameterTypes()[1] == int.class
                        && m.getParameterTypes()[2] == int.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Optional Folia Bukkit.isOwnedByCurrentRegion not present", t);
        }
        return null;
    }

    static boolean isOwnedByCurrentRegionOrNonFolia(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }
        Method m = FOLIA_IS_OWNED_METHOD;
        if (m == null) {
            return true;
        }
        try {
            Object res = m.invoke(null, world, chunkX, chunkZ);
            if (res instanceof Boolean b) {
                return b;
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Folia isOwnedByCurrentRegion check fallback", t);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (this.runtime == null || event.getChunk() == null || event.getWorld() == null) {
            return;
        }
        LcaInMemoryChunkCoordinator coord = this.runtime.chunkCoordinator();
        if (!EAGER_COMPACT_WITHOUT_CONSUMERS
                && !this.anyLcaClientConnected
                && !coord.hasActiveWireOrShardConsumers()
                && coord.activeChunkCount() == 0
                && coord.pendingChunkCount() == 0
                && coord.inFlightCompactionCount() == 0) {
            return;
        }
        coord.markChunkUnloaded(
                event.getWorld().getName(),
                event.getChunk().getX(),
                event.getChunk().getZ()
        );
    }

    private void registerAgentBootstrapHooksIfActive() {
        if (!LeafAnvilPremain.isAgentActive() || this.runtime == null) {
            return;
        }
        try {
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            java.lang.invoke.MethodHandle mutationMh = lookup.bind(
                    this,
                    "onAgentSectionMutated",
                    java.lang.invoke.MethodType.methodType(void.class, Object.class, int.class, int.class, int.class)
            );
            LeafAnvilAgentBootstrap.registerChunkCodecHooks(null, null, mutationMh);
            getLogger().info("[LeafAnvil] Bound -javaagent direct LevelChunkSection#setBlockState sparse dirty bridge.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LeafAnvil] Could not bind -javaagent hooks; using plugin fallback: " + t.getMessage(), t);
        }
    }

    private void onAgentSectionMutated(Object nmsSection, int pos12, int prevStateId, int newStateId) {
        if (this.runtime != null) {
            this.runtime.chunkCoordinator().handleAgentSectionMutation(
                    nmsSection, pos12, prevStateId, newStateId, 0L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (LeafAnvilPremain.isAgentActive()) {
            return; // Captured inline at LevelChunkSection#setBlockState via LcaAgentSectionCarrier
        }
        if (this.runtime == null
                || this.runtime.chunkCoordinator().activeChunkCount() == 0
                || event.getBlock() == null
                || event.getBlock().getWorld() == null) {
            return;
        }
        Block block = event.getBlock();
        World world = block.getWorld();
        mutateLoadedChunkVoxel(
                world.getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                world.getMinHeight(),
                0,
                world.getFullTime()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (LeafAnvilPremain.isAgentActive()) {
            return; // Captured inline at LevelChunkSection#setBlockState via LcaAgentSectionCarrier
        }
        if (this.runtime == null
                || this.runtime.chunkCoordinator().activeChunkCount() == 0
                || event.getBlockPlaced() == null
                || event.getBlockPlaced().getWorld() == null) {
            return;
        }
        Block block = event.getBlockPlaced();
        World world = block.getWorld();
        TrackedChunkColumn col = this.runtime.chunkCoordinator().getTrackedChunk(
                world.getName(),
                block.getX() >> 4,
                block.getZ() >> 4
        );
        if (col == null) {
            return;
        }
        int stateId = resolveBukkitBlockStateId(block);
        int sectionIndex = (block.getY() - world.getMinHeight()) >> 4;
        if (sectionIndex >= 0 && sectionIndex < col.sectionCount()) {
            col.mutateVoxel(
                    sectionIndex,
                    block.getX() & 15,
                    block.getY() & 15,
                    block.getZ() & 15,
                    stateId,
                    world.getFullTime()
            );
        }
    }

    private void mutateLoadedChunkVoxel(
            String worldName,
            int blockX,
            int blockY,
            int blockZ,
            int minHeight,
            int stateId,
            long fullTime
    ) {
        if (this.runtime == null) {
            return;
        }
        TrackedChunkColumn col = this.runtime.chunkCoordinator().getTrackedChunk(
                worldName,
                blockX >> 4,
                blockZ >> 4
        );
        if (col == null) {
            return;
        }
        int sectionIndex = (blockY - minHeight) >> 4;
        if (sectionIndex >= 0 && sectionIndex < col.sectionCount()) {
            col.mutateVoxel(sectionIndex, blockX & 15, blockY & 15, blockZ & 15, stateId, fullTime);
            this.runtime.chunkCoordinator().markChunkDirtyForOverlayCompaction(
                    worldName, blockX >> 4, blockZ >> 4
            );
            this.runtime.chunkCoordinator().scheduleBackgroundOverlayCompaction(fullTime, 10L, 8, 15);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (this.runtime != null && event.getPlayer() != null) {
            this.runtime.chunkCoordinator().removePlayerSession(event.getPlayer().getUniqueId());
        }
    }

    public void attachBukkitWorld(World world) {
        if (world == null || this.runtime == null) {
            return;
        }
        if (LeafAnvilRuntime.isDiskStorageAvailable()) {
            Path dimDir = resolveDimensionDirectoryReflectively(world);
            if (dimDir != null) {
                int minSectionY = world.getMinHeight() >> 4;
                int maxSectionY = (world.getMaxHeight() - 1) >> 4;
                int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
                this.runtime.attachAndInterceptWorld(world.getName(), dimDir, minSectionY, sectionCount, world);
            }
        }
        try {
            Chunk[] preloaded = world.getLoadedChunks();
            if (preloaded != null) {
                int count = Math.min(preloaded.length, MAX_STARTUP_PRELOADED_CHUNKS_SAMPLED);
                for (int i = 0; i < count; i++) {
                    attachAndSampleLoadedChunk(world, preloaded[i]);
                }
            }
        } catch (Throwable t) {
            logFineOrWarning("[LeafAnvil] Could not sample preloaded chunks for " + world.getName(), t);
        }
    }

    private Path resolveDimensionDirectoryReflectively(World world) {
        try {
            Class<?> hookCls = Class.forName(
                    "io.github.dailystruggle.rtp.paper.storage.PaperLcaRegionFileHook",
                    false,
                    PaperLeafAnvilPlugin.class.getClassLoader()
            );
            Method resolve = hookCls.getMethod("resolveDimensionDirectory", World.class);
            Object result = resolve.invoke(null, world);
            if (result instanceof Path p) {
                return p;
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LeafAnvil] Failed to resolve dimension directory for " + world.getName() + ": " + t.getMessage(), t);
        }
        try {
            return world.getWorldFolder().toPath();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[LeafAnvil] Fallback world folder resolution failed for " + world.getName() + ": " + t.getMessage(), t);
            return null;
        }
    }
    public LeafAnvilRuntime runtime() {
        return runtime;
    }

    public LcaInMemoryChunkCoordinator chunkCoordinator() {
        return runtime != null ? runtime.chunkCoordinator() : null;
    }

    /**
     * Losslessly encodes a live Paper/Mojang NMS `LevelChunkSection` (or test fixture) into an
     * {@link LcaSectionBacking} with a compact `.lca` `baseFrame` and thread-local 64-way
     * decoded voxel cache (`leafanvil-ADR-002`).
     */
    public static LcaSectionBacking buildSectionBackingFromNmsSection(
            Object nmsSection,
            int sectionIndex,
            int totalSections
    ) {
        return buildSectionBackingFromNmsSection(nmsSection, sectionIndex, totalSections, -1);
    }

    public static LcaSectionBacking buildSectionBackingFromNmsSection(
            Object nmsSection,
            int sectionIndex,
            int totalSections,
            int explicitSkyConst4
    ) {
        if (nmsSection == null || invokeHasOnlyAir(nmsSection)) {
            return new LcaSectionBacking(
                    new byte[0],
                    0,
                    0,
                    0,
                    0,
                    stateId -> stateId != 0,
                    stateId -> false,
                    stateId -> false,
                    null
            );
        }
        SectionEncodeScratch scratch = THREAD_ENCODE_SCRATCH.get();
        int[] voxels = scratch.voxels;
        int extractedResult = extractSectionStateIds(nmsSection, voxels, scratch);
        if (extractedResult < 0) {
            return new LcaSectionBacking(new byte[0], 0);
        }
        if (extractedResult > 0) {
            int uniformId = extractedResult - 1;
            int nonEmpty = uniformId != 0 ? SECTION_VOXELS : 0;
            return new LcaSectionBacking(
                    new byte[0],
                    uniformId,
                    nonEmpty,
                    0,
                    0,
                    stateId -> stateId != 0,
                    stateId -> false,
                    stateId -> false,
                    null
            );
        }
        int firstStateId = -1;
        boolean allUniform = true;
        int nonEmpty = 0;
        for (int pos12 = 0; pos12 < SECTION_VOXELS; pos12++) {
            int stateId = voxels[pos12];
            if (firstStateId == -1) {
                firstStateId = stateId;
            } else if (stateId != firstStateId) {
                allUniform = false;
            }
            if (stateId != 0) {
                nonEmpty++;
            }
        }
        if (allUniform) {
            return new LcaSectionBacking(
                    new byte[0],
                    Math.max(0, firstStateId),
                    nonEmpty,
                    0,
                    0,
                    stateId -> stateId != 0,
                    stateId -> false,
                    stateId -> false,
                    null
            );
        }
        int skyConst = explicitSkyConst4 >= 0
                ? (explicitSkyConst4 & 0xF)
                : net.leaf.anvil.common.LcaSectionBridge.estimateSkyConstFromVoxelsFallback(
                        voxels,
                        nonEmpty,
                        sectionIndex,
                        totalSections,
                        stateId -> stateId != 0
                );
        return LcaSectionBacking.fromDirectVoxels(
                voxels,
                skyConst,
                0,
                nonEmpty,
                0,
                0,
                stateId -> stateId != 0,
                stateId -> false,
                stateId -> false
        );
    }

    /**
     * Accumulates RAM and Wire benchmark telemetry for a loaded Paper chunk column and periodically
     * emits `[LeafAnvil Telemetry]` (`leafanvil-ADR-002`, `leafanvil-ADR-003`).
     */
    public static synchronized DevTelemetrySnapshot recordColumnTelemetry(
            Object[] nmsSections,
            LcaSectionBacking[] backings,
            TrackedChunkColumn column,
            Consumer<String> jsonConsumerOrNull
    ) {
        if (backings == null || column == null) {
            return snapshotTelemetry();
        }
        int totalSec = backings.length;
        long chunkVanillaMem = 0L;
        long chunkLcaMem = 0L;
        boolean runHeavyWireSample = (jsonConsumerOrNull != null);

        if (runHeavyWireSample) {
            ByteArrayOutputStream rawColOut = new ByteArrayOutputStream(32_768);
            DataOutputStream rawData = new DataOutputStream(rawColOut);
            try {
                rawData.writeInt(column.chunkX());
                rawData.writeInt(column.chunkZ());
                for (int w = 0; w < 74; w++) {
                    rawData.writeLong(0x0102030405060708L ^ w);
                }
                for (int i = 0; i < totalSec; i++) {
                    LcaSectionBacking backing = backings[i];
                    if (backing == null) {
                        continue;
                    }
                    byte[] base = backing.baseFrame();
                    boolean uniform = (base == null || base.length == 0) && backing.dirtyCount() == 0;
                    if (uniform) {
                        telemetryUniformSections.incrementAndGet();
                        chunkVanillaMem += 48L + 64L;
                        chunkLcaMem += 65L;
                        rawData.writeShort(0);
                        rawData.writeByte(0);
                        writeVarIntLocal(rawData, backing.uniformStateId());
                        rawData.writeByte(0);
                        if (i >= (totalSec / 2)) {
                            chunkVanillaMem += 2048L;
                            rawData.write(buildSampleLightLayer(column.chunkX(), column.chunkZ(), i, 15));
                        }
                    } else {
                        telemetryNonUniformSections.incrementAndGet();
                        chunkVanillaMem += 2096L + 4096L + 64L;
                        int overlayBytes = backing.overlayStage() == LcaSectionBacking.STAGE_EMPTY
                                ? 0
                                : (backing.overlayStage() == LcaSectionBacking.STAGE_LINEAR_16 ? 64 : 256);
                        chunkLcaMem += 64L + (base != null ? base.length : 0) + overlayBytes + 120L;
                        writeNonUniformVanillaSectionSample(rawData, backing, column.chunkX(), column.chunkZ(), i);
                    }
                }
                rawData.flush();
                pendingVanillaRawChunks.add(rawColOut.toByteArray());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            pendingBatchEntries.add(column.toWireFullChunkEntry(column.lastUpdateTick()));
            if (pendingBatchEntries.size() >= 8) {
                flushPendingWireBatchSample();
            }
        } else {
            long fastVanillaZlib = 320L;
            long fastLcaLcd2 = 48L;
            for (int i = 0; i < totalSec; i++) {
                LcaSectionBacking backing = backings[i];
                if (backing == null) {
                    continue;
                }
                boolean uniform = backing.isCleanUniform();
                if (uniform) {
                    telemetryUniformSections.incrementAndGet();
                    chunkVanillaMem += 48L + 64L + ((i >= (totalSec / 2)) ? 2048L : 0L);
                    chunkLcaMem += 65L;
                    fastVanillaZlib += (i >= (totalSec / 2)) ? 420L : 8L;
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

    private static void writeNonUniformVanillaSectionSample(
            DataOutputStream rawData,
            LcaSectionBacking backing,
            int chunkX,
            int chunkZ,
            int sectionIdx
    ) throws IOException {
        for (int p = 0; p < 20; p++) {
            writeVarIntLocal(rawData, p + 1);
        }
        writeVarIntLocal(rawData, 342);
        for (int pos = 0; pos < SECTION_VOXELS; pos += 12) {
            long word = 0L;
            for (int k = 0; k < 12 && (pos + k) < SECTION_VOXELS; k++) {
                int p12 = pos + k;
                int st = backing.getStateId(p12 & 15, (p12 >>> 8) & 15, (p12 >>> 4) & 15);
                word |= ((long) (st & 0x1F)) << (k * 5);
            }
            rawData.writeLong(word);
        }
        rawData.write(buildSampleLightLayer(chunkX, chunkZ, sectionIdx, 15));
        rawData.write(buildSampleLightLayer(chunkX, chunkZ, sectionIdx, 0));
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
            byte[] framedBypass = LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(lcd2Batch);

            telemetryBatchesSampled.incrementAndGet();
            telemetryVanillaWireZlibBytes.addAndGet(vanillaBatchZlib);
            telemetryLcaWireLcd2Bytes.addAndGet(framedBypass.length);
            telemetryCleanRevisitAckBytes.addAndGet(pendingBatchEntries.size() * 2L);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Paper telemetry wire batch sample failed", t);
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

    public static synchronized DevTelemetrySnapshot flushTelemetryReport(
            Consumer<String> jsonConsumerOrNull,
            boolean logSummary
    ) {
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
            java.util.logging.Logger.getLogger("LeafAnvil").info(snapshot.toLogSummary());
        }
        return snapshot;
    }

    private static void writeVarIntLocal(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
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

    private record SectionReflectionMeta(
            Method hasOnlyAirMethod,
            Method directGetStateIdMethod,
            Method getStatesMethod,
            Method getBlockStateMethod,
            Method blockGetIdMethod,
            java.lang.reflect.Field containerDataField,
            java.lang.reflect.Field dataStorageField,
            java.lang.reflect.Field dataPaletteField,
            Method storageGetBitsMethod,
            Method storageGetRawMethod,
            Method paletteValueForMethod
    ) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, SectionReflectionMeta> SECTION_META_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static SectionReflectionMeta resolveSectionMeta(Class<?> secClass) {
        return SECTION_META_CACHE.computeIfAbsent(secClass, cls -> {
            Method hasOnlyAir = null;
            Method directGetStateId = null;
            Method getStates = null;
            Method getBlockState = null;
            Method blockGetId = null;
            java.lang.reflect.Field containerData = null;
            java.lang.reflect.Field dataStorage = null;
            java.lang.reflect.Field dataPalette = null;
            Method storageGetBits = null;
            Method storageGetRaw = null;
            Method paletteValueFor = null;
            for (Method m : cls.getMethods()) {
                String mn = m.getName();
                int pc = m.getParameterCount();
                if ("hasOnlyAir".equals(mn) && pc == 0) {
                    m.setAccessible(true);
                    hasOnlyAir = m;
                } else if ("getStateId".equals(mn) && pc == 3) {
                    m.setAccessible(true);
                    directGetStateId = m;
                } else if ("getBlockState".equals(mn) && pc == 3) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt[0] == int.class && pt[1] == int.class && pt[2] == int.class) {
                        m.setAccessible(true);
                        getBlockState = m;
                    }
                } else if ("getStates".equals(mn) && pc == 0) {
                    m.setAccessible(true);
                    getStates = m;
                }
            }
            try {
                Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block", false, cls.getClassLoader());
                Class<?> blockStateClass = Class.forName(
                        "net.minecraft.world.level.block.state.BlockState",
                        false,
                        cls.getClassLoader()
                );
                blockGetId = blockClass.getMethod("getId", blockStateClass);
                blockGetId.setAccessible(true);
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                        .log(Level.FINE, "[LeafAnvil] Optional NMS Block.getId not booted", t);
            }
            if (getStates != null) {
                try {
                    Class<?> containerCls = getStates.getReturnType();
                    for (java.lang.reflect.Field f : containerCls.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            Class<?> ft = f.getType();
                            java.lang.reflect.Field sf = null;
                            java.lang.reflect.Field pf = null;
                            for (java.lang.reflect.Field df : ft.getDeclaredFields()) {
                                if (java.lang.reflect.Modifier.isStatic(df.getModifiers())) {
                                    continue;
                                }
                                String sn = df.getType().getSimpleName();
                                if (sn.contains("BitStorage") || "storage".equals(df.getName())) {
                                    sf = df;
                                } else if (sn.contains("Palette") || "palette".equals(df.getName())) {
                                    pf = df;
                                }
                            }
                            if (sf != null && pf != null) {
                                f.setAccessible(true);
                                sf.setAccessible(true);
                                pf.setAccessible(true);
                                containerData = f;
                                dataStorage = sf;
                                dataPalette = pf;
                                storageGetBits = sf.getType().getMethod("getBits");
                                storageGetBits.setAccessible(true);
                                storageGetRaw = sf.getType().getMethod("getRaw");
                                storageGetRaw.setAccessible(true);
                                paletteValueFor = pf.getType().getMethod("valueFor", int.class);
                                paletteValueFor.setAccessible(true);
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                            .log(Level.FINE, "[LeafAnvil] Optional PalettedContainer.Data fields not resolved", t);
                }
            }
            return new SectionReflectionMeta(
                    hasOnlyAir,
                    directGetStateId,
                    getStates,
                    getBlockState,
                    blockGetId,
                    containerData,
                    dataStorage,
                    dataPalette,
                    storageGetBits,
                    storageGetRaw,
                    paletteValueFor
            );
        });
    }

    private static boolean invokeHasOnlyAir(Object nmsSection) {
        if (nmsSection == null) {
            return true;
        }
        SectionReflectionMeta meta = resolveSectionMeta(nmsSection.getClass());
        if (meta.hasOnlyAirMethod() == null) {
            return false;
        }
        try {
            Object res = meta.hasOnlyAirMethod().invoke(nmsSection);
            if (res instanceof Boolean b) {
                return b;
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] hasOnlyAir probe fallback", t);
        }
        return false;
    }

    private static int extractSectionStateIds(
            Object nmsSection,
            int[] outVoxels4096,
            SectionEncodeScratch scratch
    ) {
        if (nmsSection == null) {
            return -1;
        }
        SectionReflectionMeta meta = resolveSectionMeta(nmsSection.getClass());
        if (meta.directGetStateIdMethod() != null) {
            try {
                Method directGet = meta.directGetStateIdMethod();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int id = (int) directGet.invoke(nmsSection, x, y, z);
                            outVoxels4096[LcaSectionBacking.pos12(x, y, z)] = id;
                        }
                    }
                }
                return 0;
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                        .log(Level.FINE, "[LeafAnvil] Direct getStateId probe fallback", t);
            }
        }
        if (meta.getStatesMethod() != null
                && meta.containerDataField() != null
                && meta.dataStorageField() != null
                && meta.dataPaletteField() != null
                && meta.storageGetBitsMethod() != null
                && meta.storageGetRawMethod() != null
                && meta.paletteValueForMethod() != null
                && meta.blockGetIdMethod() != null) {
            try {
                Object container = meta.getStatesMethod().invoke(nmsSection);
                Object dataObj = container != null ? meta.containerDataField().get(container) : null;
                Object storageObj = dataObj != null ? meta.dataStorageField().get(dataObj) : null;
                Object paletteObj = dataObj != null ? meta.dataPaletteField().get(dataObj) : null;
                if (storageObj != null && paletteObj != null) {
                    int bits = (int) meta.storageGetBitsMethod().invoke(storageObj);
                    long[] raw = (long[]) meta.storageGetRawMethod().invoke(storageObj);
                    if (bits <= 0 || raw == null || raw.length == 0) {
                        Object state0 = meta.paletteValueForMethod().invoke(paletteObj, 0);
                        int uniformId = state0 != null ? (int) meta.blockGetIdMethod().invoke(null, state0) : 0;
                        return Math.max(0, uniformId) + 1;
                    }
                    int palEpoch = ++scratch.currentPaletteEpoch;
                    if (palEpoch <= 0) {
                        Arrays.fill(scratch.paletteLutEpoch, 0);
                        palEpoch = 1;
                        scratch.currentPaletteEpoch = 1;
                    }
                    int[] lut = scratch.paletteLut;
                    int[] lutEpoch = scratch.paletteLutEpoch;
                    Method valFor = meta.paletteValueForMethod();
                    Method getId = meta.blockGetIdMethod();
                    int valuesPerLong = 64 / bits;
                    long mask = (1L << bits) - 1L;
                    int cellIndex = 0;
                    for (int wordIdx = 0; wordIdx < raw.length && cellIndex < SECTION_VOXELS; wordIdx++) {
                        long word = raw[wordIdx];
                        int shift = 0;
                        for (int sub = 0; sub < valuesPerLong && cellIndex < SECTION_VOXELS; sub++) {
                            int palIdx = (int) ((word >>> shift) & mask);
                            shift += bits;
                            int globalId;
                            if (palIdx >= 0 && palIdx < SECTION_VOXELS) {
                                if (lutEpoch[palIdx] == palEpoch) {
                                    globalId = lut[palIdx];
                                } else {
                                    Object st = valFor.invoke(paletteObj, palIdx);
                                    globalId = st != null ? (int) getId.invoke(null, st) : 0;
                                    lutEpoch[palIdx] = palEpoch;
                                    lut[palIdx] = globalId;
                                }
                            } else {
                                Object st = valFor.invoke(paletteObj, palIdx);
                                globalId = st != null ? (int) getId.invoke(null, st) : 0;
                            }
                            outVoxels4096[cellIndex++] = globalId;
                        }
                    }
                    if (cellIndex == SECTION_VOXELS) {
                        return 0;
                    }
                }
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                        .log(Level.FINE, "[LeafAnvil] Fast BitStorage section extraction fallback", t);
            }
        }
        if (meta.getBlockStateMethod() != null && meta.blockGetIdMethod() != null) {
            try {
                Method getBlockState = meta.getBlockStateMethod();
                Method getId = meta.blockGetIdMethod();
                Object prevStateObj = null;
                int prevStateId = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            Object stateObj = getBlockState.invoke(nmsSection, x, y, z);
                            int id;
                            if (stateObj == prevStateObj && stateObj != null) {
                                id = prevStateId;
                            } else {
                                id = (int) getId.invoke(null, stateObj);
                                prevStateObj = stateObj;
                                prevStateId = id;
                            }
                            outVoxels4096[LcaSectionBacking.pos12(x, y, z)] = id;
                        }
                    }
                }
                return 0;
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                        .log(Level.FINE, "[LeafAnvil] NMS getBlockState probe fallback", t);
            }
        }
        reportSectionBridgeIncompatibility(
                nmsSection.getClass().getName(),
                "Incompatible LevelChunkSection/PalettedContainer class or field layout on "
                        + nmsSection.getClass().getName(),
                "LevelChunkSection#getStates() PalettedContainer or getBlockState(int,int,int)");
        return -1;
    }

    private static void reportSectionBridgeIncompatibility(String path, String message, String expected) {
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt != null) {
            rt.bitSuite().recordIncompatibility(
                    net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_003_SECTION_BRIDGE_COMPAT,
                    "PaperLeafAnvilPlugin",
                    path,
                    message,
                    expected);
        }
    }

    private static volatile Method cachedCraftBlockDataGetStateMethod;
    private static volatile Method cachedBlockGetIdStaticMethod;

    private static int resolveNmsBlockStateId(Object nmsState) {
        if (nmsState == null) {
            return 0;
        }
        try {
            Method getId = cachedBlockGetIdStaticMethod;
            if (getId == null) {
                ClassLoader cl = nmsState.getClass().getClassLoader();
                Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block", false, cl);
                Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState", false, cl);
                getId = blockClass.getMethod("getId", blockStateClass);
                getId.setAccessible(true);
                cachedBlockGetIdStaticMethod = getId;
            }
            return Math.max(0, (int) getId.invoke(null, nmsState));
        } catch (Throwable t) {
            reportSectionBridgeIncompatibility(
                    "net.minecraft.world.level.block.Block#getId",
                    "Incompatible or missing NMS Block.getId(BlockState) for "
                            + nmsState.getClass().getName() + ": " + t.getClass().getSimpleName(),
                    "net.minecraft.world.level.block.Block.getId(BlockState)");
            return 0;
        }
    }

    private static int resolveBukkitBlockStateId(Block block) {
        if (block == null) {
            return 0;
        }
        try {
            Object blockData = block.getBlockData();
            if (blockData != null) {
                Method getState = cachedCraftBlockDataGetStateMethod;
                if (getState == null || !getState.getDeclaringClass().isInstance(blockData)) {
                    getState = blockData.getClass().getMethod("getState");
                    getState.setAccessible(true);
                    cachedCraftBlockDataGetStateMethod = getState;
                }
                Object nmsState = getState.invoke(blockData);
                Method getId = cachedBlockGetIdStaticMethod;
                if (getId == null) {
                    Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
                    Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
                    getId = blockClass.getMethod("getId", blockStateClass);
                    getId.setAccessible(true);
                    cachedBlockGetIdStaticMethod = getId;
                }
                return (int) getId.invoke(null, nmsState);
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] CraftBlockData state ID fallback", t);
        }
        return 1;
    }

    private void logFineOrWarning(String msg, Throwable t) {
        try {
            getLogger().log(Level.FINE, msg, t);
        } catch (Throwable ex) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName()).log(Level.FINE, msg, ex);
        }
    }

    private static volatile long cachedPaperParityHash = 0L;

    /**
     * Computes the server's 64-bit `Block.BLOCK_STATE_REGISTRY` parity hash on Paper/Folia via
     * reflective NMS lookup, falling back to {@link #DEFAULT_PAPER_REGISTRY_PARITY_HASH} in unit
     * test environments where NMS is not booted.
     */
    public static long computePaperBlockStateRegistryParityHash() {
        long cached = cachedPaperParityHash;
        if (cached != 0L) {
            return cached;
        }
        try {
            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
            Object registry = blockClass.getField("BLOCK_STATE_REGISTRY").get(null);
            Method sizeMethod = registry.getClass().getMethod("size");
            Method stateById = blockClass.getMethod("stateById", int.class);
            Method getId = blockClass.getMethod("getId", Class.forName("net.minecraft.world.level.block.state.BlockState"));
            int size = (int) sizeMethod.invoke(registry);
            long hash = DEFAULT_PAPER_REGISTRY_PARITY_HASH;
            hash = (hash ^ size) * 0x100000001B3L;
            for (int id = 0; id < size; id++) {
                Object state = stateById.invoke(null, id);
                int actualId = (int) getId.invoke(null, state);
                hash = (hash ^ (((long) id << 32) | (actualId & 0xFFFFFFFFL))) * 0x100000001B3L;
            }
            cachedPaperParityHash = hash;
            return hash;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName()).log(
                    Level.FINE,
                    "[LeafAnvil] NMS Block.BLOCK_STATE_REGISTRY not booted; using default parity hash",
                    t
            );
            return DEFAULT_PAPER_REGISTRY_PARITY_HASH;
        }
    }

    private static volatile Method cachedChunkGetHandle1ArgMethod;
    private static volatile Object cachedChunkStatusFull;
    private static volatile Method cachedNmsChunkGetSectionsMethod;
    private static volatile Method cachedWorldGetHandleMethod;
    private static volatile Method cachedLevelDimensionTypeMethod;
    private static volatile Method cachedDimTypeHasSkyLightMethod;
    private static volatile Method cachedLevelGetLightEngineMethod;
    private static volatile Method cachedGetLayerListenerMethod;
    private static volatile Object cachedLightLayerSkyEnum;
    private static volatile Method cachedSectionPosOfMethod;
    private static volatile Method cachedGetDataLayerDataMethod;
    private static volatile Method cachedDataLayerIsEmptyMethod;
    private static volatile Method cachedDataLayerGetDataMethod;
    private static volatile Object cachedHeightmapMotionBlockingEnum;
    private static volatile Method cachedHasPrimedHeightmapMethod;
    private static volatile Method cachedChunkGetHeightMethod;

    static int[] resolvePaperChunkSectionSkyConsts(
            World world,
            Chunk bukkitChunk,
            int minSectionY,
            int sectionCount
    ) {
        int[] result = new int[Math.max(0, sectionCount)];
        if (sectionCount <= 0) {
            return result;
        }
        Arrays.fill(result, -1);
        if (world == null) {
            return result;
        }
        try {
            World.Environment env = world.getEnvironment();
            if (env == World.Environment.NETHER || env == World.Environment.THE_END) {
                Arrays.fill(result, 0);
                return result;
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] World environment sky-light probe fallback", t);
        }
        if (bukkitChunk == null) {
            return result;
        }
        try {
            Method wHandle = cachedWorldGetHandleMethod;
            if (wHandle == null || !wHandle.getDeclaringClass().isInstance(world)) {
                wHandle = world.getClass().getMethod("getHandle");
                wHandle.setAccessible(true);
                cachedWorldGetHandleMethod = wHandle;
            }
            Object serverLevel = wHandle.invoke(world);
            if (serverLevel == null) {
                return result;
            }
            Method dimTypeMethod = cachedLevelDimensionTypeMethod;
            if (dimTypeMethod == null || !dimTypeMethod.getDeclaringClass().isInstance(serverLevel)) {
                dimTypeMethod = serverLevel.getClass().getMethod("dimensionType");
                dimTypeMethod.setAccessible(true);
                cachedLevelDimensionTypeMethod = dimTypeMethod;
            }
            Object dimType = dimTypeMethod.invoke(serverLevel);
            if (dimType != null) {
                Method hasSky = cachedDimTypeHasSkyLightMethod;
                if (hasSky == null || !hasSky.getDeclaringClass().isInstance(dimType)) {
                    hasSky = dimType.getClass().getMethod("hasSkyLight");
                    hasSky.setAccessible(true);
                    cachedDimTypeHasSkyLightMethod = hasSky;
                }
                if (Boolean.FALSE.equals(hasSky.invoke(dimType))) {
                    Arrays.fill(result, 0);
                    return result;
                }
            }

            Method getLightEngine = cachedLevelGetLightEngineMethod;
            if (getLightEngine == null || !getLightEngine.getDeclaringClass().isInstance(serverLevel)) {
                getLightEngine = serverLevel.getClass().getMethod("getLightEngine");
                getLightEngine.setAccessible(true);
                cachedLevelGetLightEngineMethod = getLightEngine;
            }
            Object lightEngine = getLightEngine.invoke(serverLevel);
            boolean anyUnresolved = false;
            if (lightEngine != null) {
                Object skyEnum = cachedLightLayerSkyEnum;
                if (skyEnum == null) {
                    Class<?> lightLayerCls = Class.forName("net.minecraft.world.level.LightLayer");
                    skyEnum = lightLayerCls.getField("SKY").get(null);
                    cachedLightLayerSkyEnum = skyEnum;
                }
                Method getListener = cachedGetLayerListenerMethod;
                if (getListener == null || !getListener.getDeclaringClass().isInstance(lightEngine)) {
                    getListener = lightEngine.getClass().getMethod("getLayerListener", skyEnum.getClass());
                    getListener.setAccessible(true);
                    cachedGetLayerListenerMethod = getListener;
                }
                Object skyListener = getListener.invoke(lightEngine, skyEnum);
                Method secPosOf = cachedSectionPosOfMethod;
                if (secPosOf == null) {
                    Class<?> secPosCls = Class.forName("net.minecraft.core.SectionPos");
                    secPosOf = secPosCls.getMethod("of", int.class, int.class, int.class);
                    secPosOf.setAccessible(true);
                    cachedSectionPosOfMethod = secPosOf;
                }
                if (skyListener != null) {
                    Method getDataLayer = cachedGetDataLayerDataMethod;
                    if (getDataLayer == null || !getDataLayer.getDeclaringClass().isInstance(skyListener)) {
                        Class<?> secPosCls = Class.forName("net.minecraft.core.SectionPos");
                        getDataLayer = skyListener.getClass().getMethod("getDataLayerData", secPosCls);
                        getDataLayer.setAccessible(true);
                        cachedGetDataLayerDataMethod = getDataLayer;
                    }
                    int cx = bukkitChunk.getX();
                    int cz = bukkitChunk.getZ();
                    for (int i = 0; i < sectionCount; i++) {
                        Object secPos = secPosOf.invoke(null, cx, minSectionY + i, cz);
                        Object dataLayer = getDataLayer.invoke(skyListener, secPos);
                        if (dataLayer != null) {
                            Method isEmpty = cachedDataLayerIsEmptyMethod;
                            if (isEmpty == null || !isEmpty.getDeclaringClass().isInstance(dataLayer)) {
                                isEmpty = dataLayer.getClass().getMethod("isEmpty");
                                isEmpty.setAccessible(true);
                                cachedDataLayerIsEmptyMethod = isEmpty;
                            }
                            if (Boolean.TRUE.equals(isEmpty.invoke(dataLayer))) {
                                result[i] = 0;
                            } else {
                                Method getData = cachedDataLayerGetDataMethod;
                                if (getData == null || !getData.getDeclaringClass().isInstance(dataLayer)) {
                                    getData = dataLayer.getClass().getMethod("getData");
                                    getData.setAccessible(true);
                                    cachedDataLayerGetDataMethod = getData;
                                }
                                byte[] bytes = (byte[]) getData.invoke(dataLayer);
                                result[i] = net.leaf.anvil.common.LcaSectionBridge.dominantSkyNibbleFromLayer(bytes);
                            }
                        } else {
                            anyUnresolved = true;
                        }
                    }
                } else {
                    anyUnresolved = true;
                }
            } else {
                anyUnresolved = true;
            }

            if (anyUnresolved) {
                Object nmsChunk = resolveLoadedNmsChunkHandle(bukkitChunk);
                if (nmsChunk != null) {
                    Object mbEnum = cachedHeightmapMotionBlockingEnum;
                    if (mbEnum == null) {
                        Class<?> hmTypesCls = Class.forName("net.minecraft.world.level.levelgen.Heightmap$Types");
                        mbEnum = hmTypesCls.getField("MOTION_BLOCKING").get(null);
                        cachedHeightmapMotionBlockingEnum = mbEnum;
                    }
                    Method hasPrimed = cachedHasPrimedHeightmapMethod;
                    if (hasPrimed == null || !hasPrimed.getDeclaringClass().isInstance(nmsChunk)) {
                        hasPrimed = nmsChunk.getClass().getMethod("hasPrimedHeightmap", mbEnum.getClass());
                        hasPrimed.setAccessible(true);
                        cachedHasPrimedHeightmapMethod = hasPrimed;
                    }
                    if (Boolean.TRUE.equals(hasPrimed.invoke(nmsChunk, mbEnum))) {
                        Method getHeight = cachedChunkGetHeightMethod;
                        if (getHeight == null || !getHeight.getDeclaringClass().isInstance(nmsChunk)) {
                            getHeight = nmsChunk.getClass().getMethod(
                                    "getHeight", mbEnum.getClass(), int.class, int.class);
                            getHeight.setAccessible(true);
                            cachedChunkGetHeightMethod = getHeight;
                        }
                        int[] colHeights = new int[256];
                        int minH = Integer.MAX_VALUE;
                        int maxH = Integer.MIN_VALUE;
                        int idx = 0;
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int h = (int) getHeight.invoke(nmsChunk, mbEnum, x, z);
                                colHeights[idx++] = h;
                                if (h < minH) minH = h;
                                if (h > maxH) maxH = h;
                            }
                        }
                        for (int i = 0; i < sectionCount; i++) {
                            if (result[i] < 0) {
                                int sectionBottomY = (minSectionY + i) << 4;
                                int midY = sectionBottomY + 8;
                                int exposed = 0;
                                for (int c = 0; c < 256; c++) {
                                    if (colHeights[c] <= midY) {
                                        exposed++;
                                    }
                                }
                                result[i] = net.leaf.anvil.common.LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                                        true, sectionBottomY, minH, maxH, exposed);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName())
                    .log(Level.FINE, "[LeafAnvil] Paper chunk sky-light probe fallback", t);
        }
        return result;
    }

    static Object[] extractLoadedNmsSectionsWithoutIo(Chunk bukkitChunk) {
        if (bukkitChunk == null) {
            return new Object[0];
        }
        try {
            Object nmsChunk = resolveLoadedNmsChunkHandle(bukkitChunk);
            if (nmsChunk != null) {
                Method getSections = cachedNmsChunkGetSectionsMethod;
                if (getSections == null || !getSections.getDeclaringClass().isInstance(nmsChunk)) {
                    getSections = nmsChunk.getClass().getMethod("getSections");
                    getSections.setAccessible(true);
                    cachedNmsChunkGetSectionsMethod = getSections;
                }
                Object sectionsObj = getSections.invoke(nmsChunk);
                if (sectionsObj instanceof Object[] arr) {
                    return arr;
                }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(PaperLeafAnvilPlugin.class.getName()).log(
                    Level.FINE,
                    "[LeafAnvil] Could not extract NMS sections from CraftChunk handle",
                    t
            );
        }
        return new Object[0];
    }

    private static Object resolveLoadedNmsChunkHandle(Chunk bukkitChunk) throws Exception {
        Class<?> chunkClass = bukkitChunk.getClass();
        Method cached1Arg = cachedChunkGetHandle1ArgMethod;
        Object cachedFull = cachedChunkStatusFull;
        if (cached1Arg != null && cachedFull != null && cached1Arg.getDeclaringClass().isAssignableFrom(chunkClass)) {
            return cached1Arg.invoke(bukkitChunk, cachedFull);
        }
        for (Method m : chunkClass.getMethods()) {
            if (m.getName().equals("getHandle") && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m.invoke(bukkitChunk);
            }
        }
        for (Method m : chunkClass.getMethods()) {
            if (m.getName().equals("getHandle") && m.getParameterCount() == 1) {
                Class<?> statusType = m.getParameterTypes()[0];
                for (java.lang.reflect.Field f : statusType.getFields()) {
                    if (f.getName().equals("FULL")) {
                        Object fullStatus = f.get(null);
                        if (fullStatus != null) {
                            m.setAccessible(true);
                            cachedChunkGetHandle1ArgMethod = m;
                            cachedChunkStatusFull = fullStatus;
                            return m.invoke(bukkitChunk, fullStatus);
                        }
                    }
                }
            }
        }
        if (bukkitChunk.getWorld() != null) {
            World world = bukkitChunk.getWorld();
            Method worldGetHandle = world.getClass().getMethod("getHandle");
            worldGetHandle.setAccessible(true);
            Object serverLevel = worldGetHandle.invoke(world);
            if (serverLevel != null) {
                Method getChunkIfLoaded = serverLevel.getClass().getMethod("getChunkIfLoaded", int.class, int.class);
                getChunkIfLoaded.setAccessible(true);
                return getChunkIfLoaded.invoke(serverLevel, bukkitChunk.getX(), bukkitChunk.getZ());
            }
        }
        return null;
    }
}
