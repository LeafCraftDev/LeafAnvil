package net.leaf.anvil.fabric.client;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.net.LcaClientWireReceiver;
import net.leaf.anvil.common.net.LcaClientWireReceiver.ClientTelemetrySnapshot;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Free `MIT`-licensed Fabric client RX entrypoint for {@code LeafAnvil-Client}, negotiating
 * `LCA_NET_V1` `.lca` chunk streaming (`leafanvil:chunk_stream_handshake`), receiving compressed
 * chunk batches (`leafanvil:lca_chunk_batch`), and maintaining the client-side revisit delta cache
 * (`leafanvil-ADR-002`, `leafanvil-ADR-003`).
 *
 * <p>Contains zero dedicated-server storage interception (`NmsRegionStorageInterceptor`), world
 * conversion (`LeafAnvilWorldConverter`), or server TX batching (`MixinPlayerChunkSender`) logic
 * so it can be freely redistributed in Modrinth and CurseForge client modpacks.</p>
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, rtp-fabric-ADR-002, ADR-115
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy rtp-fabric-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class FabricLeafAnvilClientMod implements ClientModInitializer, ModInitializer {
    private static final Logger LOGGER = Logger.getLogger(FabricLeafAnvilClientMod.class.getName());

    private static volatile CustomPacketPayload.Type<net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload> handshakePayloadType;
    private static volatile CustomPacketPayload.Type<net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload> chunkBatchPayloadType;
    private static volatile long cachedRegistryParityHash;
    private static volatile boolean registryParityHashComputed;

    private final LcaClientWireReceiver wireReceiver;
    public FabricLeafAnvilClientMod() {
        this(new LcaClientWireReceiver());
    }

    public FabricLeafAnvilClientMod(LcaClientWireReceiver wireReceiver) {
        this.wireReceiver = wireReceiver;
    }

    @Override
    public void onInitializeClient() {
        initializeClientNetworkingIfFirst();
    }

    @Override
    public void onInitialize() {
        initializeClientNetworkingIfFirst();
    }

    /**
     * Registers Fabric client `LCA_NET_V1` payload codecs and join/disconnect hooks at most once
     * per JVM, preventing duplicate registration if full {@code LeafAnvil} is also installed.
     */
    public boolean initializeClientNetworkingIfFirst() {
        if (isDedicatedServerEnvironment()) {
            return false;
        }
        if (!LcaClientWireReceiver.tryAcquireGlobalClientRegistration()) {
            return false;
        }
        ensureCustomPayloadTypesRegistered();
        registerClientPayloadReceiversReflectively();
        registerClientJoinDisconnectHooksReflectively();
        wireReceiver.onInitialized("Fabric");
        return true;
    }

    private static boolean isDedicatedServerEnvironment() {
        try {
            Class<?> loaderCls = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderCls.getMethod("getInstance").invoke(null);
            Object envType = loaderCls.getMethod("getEnvironmentType").invoke(loader);
            return envType != null && "SERVER".equals(envType.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "[LeafAnvil-Client] FabricLoader environment probe unavailable", e);
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
     * Processes a server `LCA_NET_V1` handshake ACK on the Fabric client.
     */
    public NegotiatedSession onClientHandshakeAckReceived(byte[] payloadBytes) {
        return wireReceiver.onServerHandshakeAck(payloadBytes, computeBlockStateRegistryParityHash());
    }

    /**
     * Receives an inbound `leafanvil:lca_chunk_batch` frame on the Fabric client and commits
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
                    Level.WARNING,
                    "[LeafAnvil-Client] Fabric ClientLevel main-thread chunk commit failed: " + t.getMessage(),
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
            Runnable apply = () -> {
                net.minecraft.client.multiplayer.ClientLevel level = mc.level;
                if (level == null) {
                    return;
                }
                if (level.getChunkSource() instanceof LcaClientChunkInstaller installer) {
                    installer.leafanvil$installOrUpdateLcaChunk(
                            commit.chunkX(),
                            commit.chunkZ(),
                            commit.sectionBackings(),
                            commit.lastUpdateTick(),
                            wireReceiver.coordinator(),
                            commit.dimension(),
                            commit.heightmapBytes()
                    );
                    return;
                }
                if (!level.getChunkSource().hasChunk(commit.chunkX(), commit.chunkZ())) {
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
            };
            if (mc.isSameThread()) {
                apply.run();
            } else {
                mc.execute(apply);
            }
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
        if (registryParityHashComputed) {
            return cachedRegistryParityHash;
        }
        long hash = 0xCBF29CE484222325L;
        int size = Block.BLOCK_STATE_REGISTRY.size();
        hash = (hash ^ size) * 0x100000001B3L;
        for (int id = 0; id < size; id++) {
            BlockState state = Block.stateById(id);
            int actualId = Block.getId(state);
            hash = (hash ^ (((long) id << 32) | (actualId & 0xFFFFFFFFL))) * 0x100000001B3L;
        }
        if (size > 0) {
            cachedRegistryParityHash = hash;
            registryParityHashComputed = true;
        }
        return hash;
    }

    private static synchronized void ensureCustomPayloadTypesRegistered() {
        net.leaf.anvil.fabric.FabricLeafAnvilMod.ensureCustomPayloadTypesRegistered();
        handshakePayloadType = net.leaf.anvil.fabric.FabricLeafAnvilMod.handshakePayloadType();
        chunkBatchPayloadType = net.leaf.anvil.fabric.FabricLeafAnvilMod.chunkBatchPayloadType();
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
                            if (args != null
                                    && args.length >= 1
                                    && args[0] instanceof net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload raw) {
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
                            if (args != null
                                    && args.length >= 1
                                    && args[0] instanceof net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload raw) {
                                String dim = resolveClientDimensionId();
                                onClientChunkBatchReceived(dim, raw.data(), true);
                            }
                            return null;
                        }
                );
                regMethod.invoke(null, chunkBatchPayloadType, batchReceiver);
            }
        } catch (Throwable t) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil-Client] Optional ClientPlayNetworking receiver registration skipped: " + t.getMessage(),
                    t
            );
        }
    }

    private void registerClientJoinDisconnectHooksReflectively() {
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

            Field disconnectField = connEvents.getField("DISCONNECT");
            Object disconnectEvent = disconnectField.get(null);
            Method discInvokerMethod = disconnectEvent.getClass().getMethod("invoker");
            discInvokerMethod.setAccessible(true);
            Object discInvoker = discInvokerMethod.invoke(disconnectEvent);
            Class<?> discIface = discInvoker.getClass().getInterfaces()[0];
            Object discProxy = Proxy.newProxyInstance(
                    discIface.getClassLoader(),
                    new Class<?>[]{discIface},
                    (p, m, args) -> {
                        wireReceiver.onClientDisconnect();
                        LcaClientChunkInstaller.clearPendingLightEnable();
                        return null;
                    }
            );
            Method regDisc = disconnectEvent.getClass().getMethod("register", Object.class);
            regDisc.setAccessible(true);
            regDisc.invoke(disconnectEvent, discProxy);
        } catch (Throwable t) {
            LOGGER.log(
                    Level.FINE,
                    "[LeafAnvil-Client] Optional ClientPlayConnectionEvents registration skipped: " + t.getMessage(),
                    t
            );
        }
    }

    private static String resolveClientDimensionId() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null && mc.level.dimension() != null) {
                return mc.level.dimension().identifier().toString();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "[LeafAnvil-Client] Optional client dimension probe unavailable: " + t.getMessage(), t);
        }
        return "minecraft:overworld";
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
                backings[i] = net.leaf.anvil.fabric.FabricLeafAnvilMod.buildSectionBackingFromLiveSection(
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

    private void sendClientHandshakeOnJoin(Object packetSenderOrNull) {
        try {
            byte[] hsBytes = buildClientHandshakeBytes();
            boolean sentViaClientNet = false;
            if (handshakePayloadType != null) {
                net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload payload =
                        new net.leaf.anvil.fabric.FabricLeafAnvilMod.LcaRawCustomPayload(handshakePayloadType, hsBytes);
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
            LOGGER.log(
                    Level.FINE,
                    String.format(
                            Locale.ROOT,
                            "[LeafAnvil-Client] Client joined server; dispatched LCA_NET_V1 handshake (parityHash=0x%016X, %d B, clientNet=%s).",
                            computeBlockStateRegistryParityHash(),
                            hsBytes.length,
                            sentViaClientNet
                    )
            );
        } catch (Throwable t) {
            LcaClientWireReceiver.logWarning(
                    "[LeafAnvil-Client] Failed to dispatch client LCA_NET_V1 handshake on join: " + t.getMessage(),
                    t
            );
        }
    }
}
