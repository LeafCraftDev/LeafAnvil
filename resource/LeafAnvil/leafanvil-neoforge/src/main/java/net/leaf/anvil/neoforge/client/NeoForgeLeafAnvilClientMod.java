package net.leaf.anvil.neoforge.client;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.net.LcaClientWireReceiver;
import net.leaf.anvil.common.net.LcaClientWireReceiver.ClientTelemetrySnapshot;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Free `MIT`-licensed NeoForge client RX entrypoint for {@code LeafAnvil-Client}, negotiating
 * `LCA_NET_V1` `.lca` chunk streaming (`leafanvil:chunk_stream_handshake`), receiving compressed
 * chunk batches (`leafanvil:lca_chunk_batch`), and maintaining the client-side revisit delta cache
 * (`leafanvil-ADR-002`, `leafanvil-ADR-003`).
 *
 * <p>Contains zero dedicated-server storage interception (`NmsRegionStorageInterceptor`), world
 * conversion (`LeafAnvilWorldConverter`), or server TX batching (`MixinPlayerChunkSender`) logic
 * so it can be freely redistributed in Modrinth and CurseForge client modpacks.</p>
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, ADR-033, ADR-115
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-033
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class NeoForgeLeafAnvilClientMod {

    /**
     * Dedicated `@Mod("leafanvil_net")` entrypoint for {@code LeafAnvil-Net.jar}; excluded from
     * {@code LeafAnvil.jar} so NeoForge's annotation scanner sees only {@code @Mod("leafanvil")}
     * while keeping {@link NeoForgeLeafAnvilClientMod} bundled in the single product JAR (`ADR-126`).
     */
    @Mod("leafanvil_net")
    public static final class NetEntrypoint {
        public NetEntrypoint() {
            new NeoForgeLeafAnvilClientMod();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(NeoForgeLeafAnvilClientMod.class.getName());

    private record LcaRawCustomPayload(
            CustomPacketPayload.Type<LcaRawCustomPayload> type,
            byte[] data
    ) implements CustomPacketPayload {
    }

    private static volatile CustomPacketPayload.Type<LcaRawCustomPayload> handshakePayloadType;
    private static volatile CustomPacketPayload.Type<LcaRawCustomPayload> chunkBatchPayloadType;

    private final LcaClientWireReceiver wireReceiver;

    public NeoForgeLeafAnvilClientMod() {
        this(new LcaClientWireReceiver());
        initializeClientNetworkingIfFirst();
        net.leaf.anvil.neoforge.NeoForgeLeafAnvilMod.initializeServerTxIfFirst();
    }

    NeoForgeLeafAnvilClientMod(LcaClientWireReceiver wireReceiver) {
        this.wireReceiver = wireReceiver;
    }

    public static NeoForgeLeafAnvilClientMod withReceiver(LcaClientWireReceiver wireReceiver) {
        return new NeoForgeLeafAnvilClientMod(wireReceiver);
    }

    /**
     * Registers NeoForge client `LCA_NET_V1` payload codecs and join/disconnect listeners at most
     * once per JVM, preventing duplicate registration if full {@code LeafAnvil} is also installed.
     */
    public boolean initializeClientNetworkingIfFirst() {
        if (isDedicatedServerEnvironment()) {
            return false;
        }
        if (!LcaClientWireReceiver.tryAcquireGlobalClientRegistration()) {
            return false;
        }
        registerNeoForgePlayPayloads();
        registerClientConnectionListenersReflectively();
        wireReceiver.onInitialized("NeoForge");
        return true;
    }

    private static boolean isDedicatedServerEnvironment() {
        try {
            Class<?> fmlEnv = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = null;
            for (java.lang.reflect.Field field : fmlEnv.getFields()) {
                if ("dist".equals(field.getName())) {
                    dist = field.get(null);
                    break;
                }
            }
            if (dist == null) {
                for (Method m : fmlEnv.getMethods()) {
                    if ("getDist".equals(m.getName()) && m.getParameterCount() == 0) {
                        dist = m.invoke(null);
                        break;
                    }
                }
            }
            return dist != null && "DEDICATED_SERVER".equals(dist.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[LeafAnvil-Client] FMLEnvironment dist probe unavailable", e);
            return false;
        }
    }

    public LcaClientWireReceiver wireReceiver() {
        return wireReceiver;
    }

    /**
     * Builds the 20-byte `LCA_NET_V1` client capability handshake payload (`leafanvil-ADR-003`).
     */
    public byte[] buildClientHandshakeBytes() {
        return wireReceiver.buildClientHandshakeBytes(computeBlockStateRegistryParityHash());
    }

    /**
     * Processes a server `LCA_NET_V1` handshake ACK on the NeoForge client.
     */
    public NegotiatedSession onClientHandshakeAckReceived(byte[] payloadBytes) {
        return wireReceiver.onServerHandshakeAck(payloadBytes, computeBlockStateRegistryParityHash());
    }

    /**
     * Receives an inbound `leafanvil:lca_chunk_batch` frame on the NeoForge client and commits
     * hydrated `.lca` section backings in strict Netty arrival order.
     */
    public int onClientChunkBatchReceived(
            String worldName,
            byte[] batchPayloadBytes,
            boolean proxySafeZlib
    ) {
        return wireReceiver.onChunkBatchPayloadReceived(
                worldName,
                batchPayloadBytes,
                proxySafeZlib,
                computeBlockStateRegistryParityHash(),
                this::commitHydratedChunkOnClientMainThread
        );
    }

    private void commitHydratedChunkOnClientMainThread(
            LcaWireStreamProtocol.HydratedClientChunkCommit commit
    ) {
        if (commit == null) {
            return;
        }
        try {
            ClientLevelCommitBridge.commit(wireReceiver, commit);
        } catch (Throwable t) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil-Client] Optional NeoForge ClientLevel main-thread chunk commit skipped: " + t.getMessage(),
                    t
            );
        }
    }

    private static final class ClientLevelCommitBridge {
        static void commit(
                LcaClientWireReceiver wireReceiver,
                LcaWireStreamProtocol.HydratedClientChunkCommit commit
        ) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                net.minecraft.client.multiplayer.ClientLevel level = mc.level;
                if (level == null || !level.getChunkSource().hasChunk(commit.chunkX(), commit.chunkZ())) {
                    return;
                }
                LevelChunk chunk = level.getChunk(commit.chunkX(), commit.chunkZ());
                if (chunk == null) {
                    return;
                }
                LevelChunkSection[] sections = chunk.getSections();
                if (sections == null || sections.length == 0) {
                    return;
                }
                wireReceiver.coordinator().attachChunkColumn(
                        commit.dimension(),
                        commit.chunkX(),
                        commit.chunkZ(),
                        commit.sectionBackings(),
                        sections,
                        commit.lastUpdateTick()
                );
            });
        }
    }

    public ClientTelemetrySnapshot snapshotTelemetry() {
        return wireReceiver.snapshotTelemetry();
    }

    /**
     * Computes the deterministic 64-bit FNV-1a parity hash over {@link Block#BLOCK_STATE_REGISTRY}
     * (`leafanvil-ADR-003` Section 1).
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

    private void registerNeoForgePlayPayloads() {
        handshakePayloadType = registerChannelPayload(
                LcaWireStreamProtocol.CHANNEL_HANDSHAKE,
                bytes -> onClientHandshakeAckReceived(bytes)
        );
        chunkBatchPayloadType = registerChannelPayload(
                LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH,
                bytes -> onClientChunkBatchReceived("minecraft:overworld", bytes, true)
        );
    }

    private static CustomPacketPayload.Type<LcaRawCustomPayload> registerChannelPayload(
            String channel,
            Consumer<byte[]> onClientReceive
    ) {
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
            IPayloadHandler<LcaRawCustomPayload> handler = (msg, context) -> {
                if (msg == null || msg.data() == null) {
                    return;
                }
                if (context != null && context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    if (LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(channel)) {
                        net.leaf.anvil.neoforge.NeoForgeLeafAnvilMod serverMod =
                                net.leaf.anvil.neoforge.NeoForgeLeafAnvilMod.initializeServerTxIfFirst();
                            serverMod.onServerHandshakeReceived(
                                    serverPlayer,
                                    msg.data(),
                                    (isHandshakeAck, outBytes) -> {
                                        CustomPacketPayload.Type<LcaRawCustomPayload> outType =
                                                Boolean.TRUE.equals(isHandshakeAck) ? handshakePayloadType : chunkBatchPayloadType;
                                        if (outType != null && serverPlayer.connection != null) {
                                            registerAdHocChannelsForConnection(context.connection());
                                            serverPlayer.connection.send(
                                                    new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                                            new LcaRawCustomPayload(outType, outBytes)
                                                    )
                                            );
                                        }
                                    }
                            );
                    }
                    return;
                }
                onClientReceive.accept(msg.data());
            };
            // Synchronize on shared NeoForge PayloadRegistrar.class so parallel @Mod
            // constructors (modloading-worker-*) do not race on NetworkRegistry's
            // unsynchronized PAYLOAD_REGISTRATIONS HashMap.
            synchronized (PayloadRegistrar.class) {
                new PayloadRegistrar("1")
                        .optional()
                        .playBidirectional(
                                type,
                                codec,
                                handler,
                                handler
                        );
            }
        } catch (Throwable t) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil-Client] NeoForge PayloadRegistrar registration skipped for " + channel + ": " + t.getMessage(),
                    t
            );
        }
        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerClientConnectionListenersReflectively() {
        try {
            Class<? extends Event> loginCls = (Class<? extends Event>) Class.forName(
                    "net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent$LoggingIn"
            );
            Consumer loginListener = event -> sendClientHandshakeOnJoin(event);
            NeoForge.EVENT_BUS.addListener(loginCls, loginListener);

            Class<? extends Event> logoutCls = (Class<? extends Event>) Class.forName(
                    "net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent$LoggingOut"
            );
            Consumer logoutListener = event -> wireReceiver.onClientDisconnect();
            NeoForge.EVENT_BUS.addListener(logoutCls, logoutListener);
        } catch (Throwable t) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil-Client] Optional NeoForge ClientPlayerNetworkEvent registration skipped: " + t.getMessage(),
                    t
            );
        }
    }

    public void onClientChunkLoaded(String worldName, LevelChunk chunk) {
        if (chunk == null) {
            return;
        }
        String dim = (worldName != null && !worldName.isBlank()) ? worldName : "minecraft:overworld";
        if (wireReceiver.coordinator().getTrackedChunk(dim, chunk.getPos().x(), chunk.getPos().z()) != null) {
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
                backings[i] = net.leaf.anvil.neoforge.NeoForgeLeafAnvilMod.buildSectionBackingFromLiveSection(
                        chunk,
                        sec,
                        i,
                        sections.length
                );
            }
        }
        wireReceiver.coordinator().attachChunkColumn(
                dim,
                chunk.getPos().x(),
                chunk.getPos().z(),
                backings,
                sections,
                0L
        );
    }

    @SuppressWarnings("unchecked")
    private static void registerAdHocChannelsForConnection(Object connection) {
        if (connection == null) {
            return;
        }
        try {
            Class<?> attrCls = Class.forName("net.neoforged.neoforge.network.registration.ChannelAttributes");
            for (Method m : attrCls.getMethods()) {
                if ("getOrCreateAdHocChannels".equals(m.getName()) && m.getParameterCount() == 1) {
                    Object setObj = m.invoke(null, connection);
                    if (setObj instanceof Collection<?> col) {
                        Collection<Object> rawCol = (Collection<Object>) col;
                        if (handshakePayloadType != null) {
                            rawCol.add(handshakePayloadType.id());
                        }
                        if (chunkBatchPayloadType != null) {
                            rawCol.add(chunkBatchPayloadType.id());
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "[LeafAnvil-Client] Optional ChannelAttributes ad-hoc setup skipped", t);
        }
    }

    private void sendClientHandshakeOnJoin(Object loginEventOrNull) {
        try {
            byte[] hsBytes = buildClientHandshakeBytes();
            boolean dispatched = false;
            if (handshakePayloadType != null && loginEventOrNull != null) {
                try {
                    Method getConn = loginEventOrNull.getClass().getMethod("getConnection");
                    Object connection = getConn.invoke(loginEventOrNull);
                    if (connection != null) {
                        registerAdHocChannelsForConnection(connection);
                        ServerboundCustomPayloadPacket pkt = new ServerboundCustomPayloadPacket(
                                new LcaRawCustomPayload(handshakePayloadType, hsBytes)
                        );
                        for (Method m : connection.getClass().getMethods()) {
                            if ("send".equals(m.getName()) && m.getParameterCount() == 1) {
                                m.invoke(connection, pkt);
                                dispatched = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "[LeafAnvil-Client] Reflective NeoForge connection send skipped", t);
                }
            }
            LOGGER.log(
                    Level.FINE,
                    String.format(
                            Locale.ROOT,
                            "[LeafAnvil-Client] Client joined server; dispatched LCA_NET_V1 handshake (parityHash=0x%016X, %d B, clientNet=%s).",
                            computeBlockStateRegistryParityHash(),
                            hsBytes.length,
                            dispatched
                    )
            );
        } catch (Throwable t) {
            LcaClientWireReceiver.logWarning(
                    "[LeafAnvil-Client] Failed to dispatch NeoForge client LCA_NET_V1 handshake: " + t.getMessage(),
                    t
            );
        }
    }
}
