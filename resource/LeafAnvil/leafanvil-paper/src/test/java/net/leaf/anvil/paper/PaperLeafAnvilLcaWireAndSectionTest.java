package net.leaf.anvil.paper;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification tests for Paper/Folia `Moonrise` `SimpleRandomTickList` synchronization and
 * `LCA_NET_V1` `.lca` chunk batch streaming against Paper/Folia servers (`leafanvil-ADR-002`
 * Section 5.B.1 & `leafanvil-ADR-003`).
 *
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class PaperLeafAnvilLcaWireAndSectionTest {

    /**
     * Simulates Paper/Folia's `ca.spottedleaf.moonrise.common.list.SimpleRandomTickList` to verify
     * reflective `add(int)` and `remove(int)` synchronization on `dirtyOverlay` transitions.
     */
    static final class FakeMoonriseSimpleRandomTickList {
        final BitSet coordinates = new BitSet(4096);

        public void add(int pos12) {
            coordinates.set(pos12);
        }

        public void remove(int pos12) {
            coordinates.clear(pos12);
        }

        public boolean contains(int pos12) {
            return coordinates.get(pos12);
        }

        public int size() {
            return coordinates.cardinality();
        }
    }

    static final class FakeMoonriseChunkSection {
        final FakeMoonriseSimpleRandomTickList tickingList = new FakeMoonriseSimpleRandomTickList();
        final FakeMoonriseSimpleRandomTickList fluidTickingList = new FakeMoonriseSimpleRandomTickList();
    }

    @Test
    @DisplayName("Paper/Folia Moonrise SimpleRandomTickList synchronization on dirtyOverlay transitions & LCA_NET_V1 streaming")
    void paperMoonriseSimpleRandomTickListSyncAndNegotiatedWireStreaming() throws Exception {
        ClientChunkSlabCache cache = new ClientChunkSlabCache(16 * 1024 * 1024L);
        LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(cache);

        // Create a section where stateId 55 = random-ticking crop (e.g. wheat) and stateId 66 = ticking lava fluid
        LcaSectionBacking section0 = new LcaSectionBacking(
                new byte[0],
                0,
                0,
                0,
                0,
                state -> state != 0,
                state -> state == 55,
                state -> state == 66,
                null
        );
        FakeMoonriseChunkSection fakeNmsSection = new FakeMoonriseChunkSection();

        TrackedChunkColumn column = coordinator.attachChunkColumn(
                "world",
                4,
                -3,
                new LcaSectionBacking[]{section0},
                new Object[]{fakeNmsSection},
                100L
        );
        MoonriseTickingListSynchronizer sync = column.moonriseSynchronizer(0);
        assertNotNull(sync);

        // 1. Place ticking crop (55) at unmasked coordinates (163, 70, -29) -> masks & 15 and adds to Moonrise tickingList
        int posCrop = LcaSectionBacking.pos12(163, 70, -29);
        column.mutateVoxel(0, 163, 70, -29, 55, 101L);
        assertTrue(sync.isTickingBlockAt(163, 70, -29));
        assertTrue(fakeNmsSection.tickingList.contains(posCrop), "Moonrise tickingList.add(pos12) must be invoked");
        assertEquals(1, fakeNmsSection.tickingList.size());
        assertEquals(1, section0.tickingBlockCount());
        assertEquals(1, section0.nonEmptyBlockCount());

        // 2. Replace crop (55) with ticking fluid (66) at same coordinate -> removes from tickingList, adds to fluidTickingList
        column.mutateVoxel(0, 163, 70, -29, 66, 102L);
        assertFalse(sync.isTickingBlockAt(163, 70, -29));
        assertFalse(fakeNmsSection.tickingList.contains(posCrop), "Moonrise tickingList.remove(pos12) must be invoked");
        assertTrue(sync.isTickingFluidAt(163, 70, -29));
        assertTrue(fakeNmsSection.fluidTickingList.contains(posCrop), "Moonrise fluidTickingList.add(pos12) must be invoked");
        assertEquals(0, section0.tickingBlockCount());
        assertEquals(1, section0.tickingFluidCount());

        // 3. Negotiate Fabric/NeoForge client against Paper server and stream Proxy-Safe Stored-Zlib batch
        long serverHash = PaperLeafAnvilPlugin.computePaperBlockStateRegistryParityHash();
        UUID fabricClientOnPaper = UUID.randomUUID();
        UUID vanillaClientOnPaper = UUID.randomUUID();

        NegotiatedSession negotiated = coordinator.registerPlayerHandshake(
                fabricClientOnPaper,
                new HandshakePayload(
                        LcaWireStreamProtocol.PROTOCOL_VERSION,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        32_768,
                        serverHash
                ),
                serverHash
        );
        assertTrue(negotiated.isLcaStreamActive());
        assertFalse(coordinator.getPlayerSession(vanillaClientOnPaper).isLcaStreamActive());
        assertNull(coordinator.packOutboundChunkBatchForPlayer(
                vanillaClientOnPaper, "world", 0, -1, List.of(column), true
        ));

        byte[] batchFrame = coordinator.packOutboundChunkBatchForPlayer(
                fabricClientOnPaper, "world", 0, -1, List.of(column), true
        );
        assertNotNull(batchFrame);

        AtomicReference<HydratedClientChunkCommit> clientCommitted = new AtomicReference<>();
        LcaInMemoryChunkCoordinator clientCoordinator = new LcaInMemoryChunkCoordinator(cache);
        clientCoordinator.receiveClientChunkBatchOrdered(
                "world",
                batchFrame,
                true,
                negotiated,
                null,
                clientCommitted::set
        );
        assertEquals(1, clientCoordinator.clientCommitQueue().drainReadyInStrictNettyOrder());
        assertNotNull(clientCommitted.get());
        assertEquals(66, clientCommitted.get().sectionBackings()[0].getStateId(163, 70, -29));

        // 4. Unload chunk on Paper/Folia -> marks section UNLOADED (bit 63) and fences off region slot
        long tombstone = coordinator.markChunkUnloaded("world", 4, -3);
        assertTrue((tombstone & LcaSectionBacking.UNLOADED_BIT) != 0L);
        assertTrue(section0.isUnloaded());
        assertTrue(coordinator.getOrCreateRegionFence("world", 4 >> 5, (-3) >> 5)
                .isUnloadedTombstoneCommitted(LcaWireStreamProtocol.toLocalChunkIndex10(4, -3)));
    }

    static final class FakeLiveNmsSection {
        private final boolean onlyAir;
        private final int[] stateIds = new int[4096];
        final FakeMoonriseSimpleRandomTickList tickingList = new FakeMoonriseSimpleRandomTickList();
        final FakeMoonriseSimpleRandomTickList fluidTickingList = new FakeMoonriseSimpleRandomTickList();

        FakeLiveNmsSection(boolean onlyAir) {
            this.onlyAir = onlyAir;
        }

        public boolean hasOnlyAir() {
            return onlyAir;
        }

        public int getStateId(int x, int y, int z) {
            return stateIds[LcaSectionBacking.pos12(x, y, z)];
        }

        void setVoxel(int x, int y, int z, int stateId) {
            stateIds[LcaSectionBacking.pos12(x, y, z)] = stateId;
        }
    }

    @Test
    @DisplayName("Paper live NMS section encoding, DevTelemetrySnapshot report, and handshake initial chunk_batch dispatch")
    void paperLiveNmsSectionEncodingTelemetryAndHandshakeBatchDispatch() {
        LcaInMemoryChunkCoordinator coordinator =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(16 * 1024 * 1024L));
        AtomicReference<String> telemetryJson = new AtomicReference<>();

        for (int c = 0; c < 16; c++) {
            Object[] nmsSections = new Object[24];
            LcaSectionBacking[] backings = new LcaSectionBacking[24];
            for (int s = 0; s < 24; s++) {
                if (s >= 8) {
                    FakeLiveNmsSection airSec = new FakeLiveNmsSection(true);
                    nmsSections[s] = airSec;
                    backings[s] = PaperLeafAnvilPlugin.buildSectionBackingFromNmsSection(airSec, s, 24);
                } else {
                    FakeLiveNmsSection sec = new FakeLiveNmsSection(false);
                    for (int pos = 0; pos < 4096; pos++) {
                        int stateId = (pos < 3600) ? 1 : (2 + ((pos + s + c) % 18));
                        sec.stateIds[pos] = stateId;
                    }
                    nmsSections[s] = sec;
                    backings[s] = PaperLeafAnvilPlugin.buildSectionBackingFromNmsSection(sec, s, 24);
                    for (int pos = 0; pos < 4096; pos++) {
                        int x = pos & 15;
                        int z = (pos >>> 4) & 15;
                        int y = (pos >>> 8) & 15;
                        assertEquals(sec.getStateId(x, y, z), backings[s].getStateId(x, y, z));
                    }
                }
            }
            TrackedChunkColumn col = coordinator.attachChunkColumn(
                    "world", 10 + c, 20 + c, backings, nmsSections, 200L + c
            );
            PaperLeafAnvilPlugin.recordColumnTelemetry(nmsSections, backings, col, telemetryJson::set);
        }

        PaperLeafAnvilPlugin.DevTelemetrySnapshot snapshot =
                PaperLeafAnvilPlugin.flushTelemetryReport(telemetryJson::set, false);
        assertEquals(16L, snapshot.chunksSampled());
        assertEquals(16L * 24L, snapshot.sectionsSampled());
        assertEquals(2L, snapshot.batchesSampled());
        assertTrue(snapshot.memoryReductionPct() >= 80.0,
                "Expected >= 80% RAM reduction on Paper: " + snapshot.memoryReductionPct());
        assertTrue(snapshot.wireReductionPct() >= 75.0,
                "Expected >= 75% wire reduction on Paper: " + snapshot.wireReductionPct());
        assertNotNull(telemetryJson.get());
        assertTrue(telemetryJson.get().contains("\"chunksSampled\": 16"));

        long parityHash = PaperLeafAnvilPlugin.computePaperBlockStateRegistryParityHash();
        UUID playerUuid = UUID.randomUUID();
        HandshakePayload clientHs = new HandshakePayload(
                LcaWireStreamProtocol.PROTOCOL_VERSION,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                32_768,
                parityHash
        );
        AtomicReference<byte[]> sentHandshakeAck = new AtomicReference<>();
        AtomicReference<byte[]> sentChunkBatch = new AtomicReference<>();

        NegotiatedSession session = PaperLeafAnvilPlugin.handleHandshakeAndPackInitialBatch(
                coordinator,
                playerUuid,
                "world",
                clientHs.toBytes(),
                parityHash,
                (ch, bytes) -> {
                    if (LcaWireStreamProtocol.CHANNEL_HANDSHAKE.equals(ch)) {
                        sentHandshakeAck.set(bytes);
                    } else if (LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH.equals(ch)) {
                        sentChunkBatch.set(bytes);
                    }
                }
        );
        assertTrue(session.isLcaStreamActive());
        assertNotNull(sentHandshakeAck.get(), "Server must reply with leafanvil:handshake ACK");
        assertNotNull(sentChunkBatch.get(), "Server must dispatch initial leafanvil:chunk_batch for loaded chunks");
    }

    public static final class FakeChunkStatus26_3 {
        public static final FakeChunkStatus26_3 FULL = new FakeChunkStatus26_3();
    }

    public static final class FakeNmsChunk26_3 {
        private final Object[] sections;

        FakeNmsChunk26_3(Object[] sections) {
            this.sections = sections;
        }

        public Object[] getSections() {
            return sections;
        }
    }

    interface ChunkWithStatus26_3 extends org.bukkit.Chunk {
        FakeNmsChunk26_3 getHandle(FakeChunkStatus26_3 status);
    }

    interface PlayerWithAddChannel extends org.bukkit.entity.Player {
        void addChannel(String channel);
    }

    @Test
    @DisplayName("Paper/Folia 26.3 CraftChunk.getHandle(ChunkStatus.FULL), Folia region guard, and CraftPlayer.addChannel")
    void paper26_3CraftChunkStatusFullResolutionFoliaGuardAndChannelRegistration() {
        Object[] expectedSections = new Object[]{new FakeLiveNmsSection(true), new FakeLiveNmsSection(false)};
        FakeNmsChunk26_3 nmsChunk = new FakeNmsChunk26_3(expectedSections);

        org.bukkit.Chunk chunk26_3 = (org.bukkit.Chunk) java.lang.reflect.Proxy.newProxyInstance(
                ChunkWithStatus26_3.class.getClassLoader(),
                new Class<?>[]{ChunkWithStatus26_3.class},
                (proxy, method, args) -> {
                    if ("getHandle".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && args[0] == FakeChunkStatus26_3.FULL) {
                        return nmsChunk;
                    }
                    return null;
                }
        );

        Object[] extracted = PaperLeafAnvilPlugin.extractLoadedNmsSectionsWithoutIo(chunk26_3);
        assertEquals(2, extracted.length, "Must resolve 1-arg getHandle(ChunkStatus.FULL) on Paper/Folia 26.3");

        java.util.Set<String> registeredChannels = new java.util.HashSet<>();
        org.bukkit.entity.Player fakePlayer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                PlayerWithAddChannel.class.getClassLoader(),
                new Class<?>[]{PlayerWithAddChannel.class},
                (proxy, method, args) -> {
                    if ("getListeningPluginChannels".equals(method.getName())) {
                        return registeredChannels;
                    }
                    if ("addChannel".equals(method.getName()) && args != null && args.length == 1) {
                        registeredChannels.add((String) args[0]);
                        return null;
                    }
                    return null;
                }
        );

        PaperLeafAnvilPlugin.ensurePlayerChannelRegistered(fakePlayer, LcaWireStreamProtocol.CHANNEL_HANDSHAKE);
        PaperLeafAnvilPlugin.ensurePlayerChannelRegistered(fakePlayer, LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH);
        assertTrue(registeredChannels.contains(LcaWireStreamProtocol.CHANNEL_HANDSHAKE));
        assertTrue(registeredChannels.contains(LcaWireStreamProtocol.CHANNEL_CHUNK_BATCH));

        org.bukkit.World fakeWorld = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> "world".equals(method.getName()) ? "world" : null
        );
        assertTrue(PaperLeafAnvilPlugin.isOwnedByCurrentRegionOrNonFolia(fakeWorld, 0, 0));

        // Verify incompatible section class records LCA_003 BIT failure and disables section/wire streaming
        io.github.dailystruggle.server.api.ServerAccessor acc =
                net.leaf.anvil.common.LeafAnvilRuntime.createWireOnlyAccessor(
                        "paper",
                        java.nio.file.Path.of("build", "tmp", "paper-bit-test"),
                        java.util.logging.Logger.getLogger("PaperBitTest"));
        try (net.leaf.anvil.common.LeafAnvilRuntime rt = new net.leaf.anvil.common.LeafAnvilRuntime(acc)) {
            rt.start();
            assertTrue(rt.isSectionWireOperational());
            PaperLeafAnvilPlugin.buildSectionBackingFromNmsSection(new Object(), 0, 24);
            assertFalse(rt.isSectionWireOperational(),
                    "Incompatible section class must disable section compaction and wire streaming via BIT");
            assertEquals(io.github.dailystruggle.bit.BitHealth.FAILED, rt.bitSuite().getHealthStatus());
            NegotiatedSession fallback = rt.negotiateWireSession(
                    new HandshakePayload(
                            LcaWireStreamProtocol.PROTOCOL_VERSION,
                            LcaWireStreamProtocol.ALL_CAPABILITIES,
                            4096,
                            1234L),
                    1234L);
            assertFalse(fallback.isLcaStreamActive(),
                    "Wire negotiation must fall back to vanilla when section bridge BIT fails");
        }
    }

    @Test
    @DisplayName("Paper ChunkLoadEvent skips idle LcaSectionBacking[24] allocation when no LCA_NET_V1 or LeafShard consumers exist")
    void paperChunkLoadSkipsIdleSidecarAllocationWithoutActiveConsumers() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        PaperLeafAnvilPlugin plugin = (PaperLeafAnvilPlugin) unsafe.allocateInstance(PaperLeafAnvilPlugin.class);

        io.github.dailystruggle.server.api.ServerAccessor acc =
                net.leaf.anvil.common.LeafAnvilRuntime.createWireOnlyAccessor(
                        "paper",
                        java.nio.file.Path.of("build", "tmp", "paper-selective-test"),
                        java.util.logging.Logger.getLogger("PaperSelectiveTest"));
        try (net.leaf.anvil.common.LeafAnvilRuntime rt = new net.leaf.anvil.common.LeafAnvilRuntime(acc)) {
            rt.start();
            java.lang.reflect.Field rtField = PaperLeafAnvilPlugin.class.getDeclaredField("runtime");
            rtField.setAccessible(true);
            rtField.set(plugin, rt);

            org.bukkit.World fakeWorld = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.World.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> "world";
                        case "getMinHeight" -> -64;
                        case "getMaxHeight" -> 320;
                        case "getFullTime" -> 100L;
                        default -> null;
                    }
            );
            org.bukkit.Chunk fakeChunk = (org.bukkit.Chunk) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.Chunk.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.Chunk.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getWorld" -> fakeWorld;
                        case "getX" -> 7;
                        case "getZ" -> -9;
                        default -> null;
                    }
            );

            plugin.onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(fakeChunk, false));
            assertEquals(
                    0,
                    rt.chunkCoordinator().activeChunkCount(),
                    "Idle Paper server without LCA_NET_V1 or LeafShard consumers must not allocate TrackedChunkColumn sidecars");
            assertNull(rt.chunkCoordinator().getTrackedChunk("world", 7, -9));
        }
    }

    @Test
    @DisplayName("Paper buildSectionBackingFromNmsSection encodes skyConst4 = 0 for enclosed mountain cave at Y >= 128")
    void paperEnclosedMountainCaveAtHighAltitudeEncodesZeroSkyConst() {
        FakeLiveNmsSection enclosedCave = new FakeLiveNmsSection(false);
        java.util.Arrays.fill(enclosedCave.stateIds, 1);
        for (int pos = 2 << 8; pos < (6 << 8); pos++) {
            enclosedCave.stateIds[pos] = 0;
        }
        LcaSectionBacking backing = PaperLeafAnvilPlugin.buildSectionBackingFromNmsSection(enclosedCave, 15, 24);
        byte[] baseFrame = backing.baseFrame();
        assertTrue(baseFrame.length >= 2);
        int cursor = 0;
        while (cursor < baseFrame.length && (baseFrame[cursor++] & 0x80) != 0) {
            // skip VarInt majorityStateId
        }
        int skyConst4 = (baseFrame[cursor] >>> 4) & 0xF;
        assertEquals(0, skyConst4,
                "Enclosed mountain cave at sectionIndex=15 (Y >= 128) must encode skyConst4 = 0 instead of 15");

        byte[] caveLayer = new byte[2048];
        caveLayer[0] = (byte) 0xF0;
        assertEquals(0, net.leaf.anvil.common.LcaSectionBridge.dominantSkyNibbleFromLayer(caveLayer));
        byte[] mixedHillsideCaveLayer = new byte[2048];
        java.util.Arrays.fill(mixedHillsideCaveLayer, 0, 1536, (byte) 0xFF); // 75% open hillside sky=15, 25% dark cave=0
        assertEquals(0, net.leaf.anvil.common.LcaSectionBridge.dominantSkyNibbleFromLayer(mixedHillsideCaveLayer),
                "Mixed hillside/cave section with dark cave voxels must never collapse to constant skyConst4 = 15");
        byte[] fullOpenSkyLayer = new byte[2048];
        java.util.Arrays.fill(fullOpenSkyLayer, (byte) 0xFF);
        assertEquals(15, net.leaf.anvil.common.LcaSectionBridge.dominantSkyNibbleFromLayer(fullOpenSkyLayer));
        assertEquals(0, net.leaf.anvil.common.LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                true, 128, 190, 212, 0));
        assertEquals(0, net.leaf.anvil.common.LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                true, 64, 58, 82, 200),
                "Mixed valley/hillside section partially below maxMotionBlockingY must encode skyConst4 = 0, not 15");
        assertEquals(0, net.leaf.anvil.common.LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                false, 160, 0, 0, 256));
        assertEquals(15, net.leaf.anvil.common.LcaSectionBridge.resolveSkyConstFromHeightmapBounds(
                true, 80, 62, 68, 256));

        org.bukkit.World netherWorld = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> "getEnvironment".equals(method.getName())
                        ? org.bukkit.World.Environment.NETHER
                        : null
        );
        int[] netherSky = PaperLeafAnvilPlugin.resolvePaperChunkSectionSkyConsts(netherWorld, null, 0, 16);
        for (int s = 0; s < netherSky.length; s++) {
            assertEquals(0, netherSky[s], "Nether sections must always resolve skyConst4 = 0");
        }

        net.leaf.anvil.common.LcaInMemoryChunkCoordinator coord =
                new net.leaf.anvil.common.LcaInMemoryChunkCoordinator(
                        new LcaWireStreamProtocol.ClientChunkSlabCache(64));
        try {
            LcaSectionBacking[] backings = new LcaSectionBacking[24];
            for (int s = 0; s < 24; s++) {
                // s = 0..17: solid stone (stateId = 1); s = 18..23: open sky air (stateId = 0)
                backings[s] = new LcaSectionBacking(new byte[0], s < 18 ? 1 : 0);
            }
            net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn col =
                    coord.attachChunkColumn("world", 3, -4, backings, null, 1L);
            LcaWireStreamProtocol.EntryFullChunk wireFull = col.toWireFullChunkEntry(1L);
            LcaWireStreamProtocol.SectionWireSlice highStoneSlice = wireFull.sections().get(15);
            int highStoneSky2 = (highStoneSlice.directoryWord16() >>> 13) & 3;
            assertEquals(0, highStoneSky2,
                    "Solid uniform stone section at sectionIndex=15 (Y >= 128) must export skyLight2 = 0, not 3");
            LcaWireStreamProtocol.SectionWireSlice openSkyAirSlice = wireFull.sections().get(20);
            int openSkyAir2 = (openSkyAirSlice.directoryWord16() >>> 13) & 3;
            assertEquals(1, openSkyAir2,
                    "Open-sky uniform air section above terrain must export skyLight2 = 0b01 (sky=15, block=0)");
        } finally {
            coord.shutdownCompactionPool();
        }
    }
}
