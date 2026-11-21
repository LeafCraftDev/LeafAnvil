package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.Nbt;
import io.github.dailystruggle.server.api.ServerAccessor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import net.leaf.anvil.agent.LeafAnvilAgentBootstrap;
import net.leaf.anvil.agent.LeafAnvilSectionClassTransformer;
import net.leaf.anvil.common.bit.LeafAnvilBitSuite;
import net.leaf.anvil.common.net.LcaClientWireReceiver;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator;
import net.leaf.anvil.common.net.LcaOrderlessDictTable;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral unit tests covering {@code leafanvil-common} section-bridge helpers, Moonrise ticking
 * list synchronization, NMS region storage interception, runtime lifecycle, world-converter folder
 * discovery, BIT probes, orderless dictionary tables, client wire receiver, and bytecode
 * transformer guard paths.
 */
class LeafAnvilCommonCoverageTest {

    // -------------------------------------------------------------------------
    // 1. LcaSectionBridge sky-light estimation & ZeroBitShellEvictor helpers
    // -------------------------------------------------------------------------

    public static final class SyntheticSkyBitStorage {
        private final int bits;
        private final long[] raw;

        public SyntheticSkyBitStorage(int bits, long[] raw) {
            this.bits = bits;
            this.raw = raw;
        }

        public int getBits() {
            return bits;
        }

        public long[] getRaw() {
            return raw;
        }
    }

    public static final class SyntheticSkyPalette {
        private final Object[] values;

        public SyntheticSkyPalette(Object... values) {
            this.values = values;
        }

        public Object valueFor(int idx) {
            return values[idx];
        }

        public int getSize() {
            return values.length;
        }
    }

    public static final class SyntheticSkyContainerData {
        public final SyntheticSkyBitStorage storage;
        public final SyntheticSkyPalette palette;

        public SyntheticSkyContainerData(SyntheticSkyBitStorage storage, SyntheticSkyPalette palette) {
            this.storage = storage;
            this.palette = palette;
        }
    }

    public static final class SyntheticSkyPalettedContainer {
        public SyntheticSkyContainerData data;

        public SyntheticSkyPalettedContainer(SyntheticSkyContainerData data) {
            this.data = data;
        }
    }

    public static final class FieldBackedSection {
        public final SyntheticSkyPalettedContainer states;

        public FieldBackedSection(SyntheticSkyPalettedContainer states) {
            this.states = states;
        }
    }

    @Test
    @DisplayName("LcaSectionBridge resolves sky-light nibbles, heightmap bounds, voxel roofs, and unmodified snapshot checks")
    void sectionBridgeSkyLightAndZeroBitShellHelpers() {
        assertEquals(0, LcaSectionBridge.dominantSkyNibbleFromLayer(null));
        assertEquals(0, LcaSectionBridge.dominantSkyNibbleFromLayer(new byte[10]));

        byte[] mixedNibble = new byte[2048];
        Arrays.fill(mixedNibble, (byte) 0xF0);
        assertEquals(0, LcaSectionBridge.dominantSkyNibbleFromLayer(mixedNibble));

        byte[] mixedBytes = new byte[2048];
        Arrays.fill(mixedBytes, (byte) 0xFF);
        mixedBytes[500] = (byte) 0x77;
        assertEquals(0, LcaSectionBridge.dominantSkyNibbleFromLayer(mixedBytes));

        byte[] uniformSky = new byte[2048];
        Arrays.fill(uniformSky, (byte) 0xEE);
        assertEquals(14, LcaSectionBridge.dominantSkyNibbleFromLayer(uniformSky));

        assertEquals(0, LcaSectionBridge.resolveSkyConstFromHeightmapBounds(false, 128, 0, 64, 256));
        assertEquals(0, LcaSectionBridge.resolveSkyConstFromHeightmapBounds(true, 32, 0, 64, 256));
        assertEquals(0, LcaSectionBridge.resolveSkyConstFromHeightmapBounds(true, 128, 0, 64, 200));
        assertEquals(15, LcaSectionBridge.resolveSkyConstFromHeightmapBounds(true, 128, 0, 64, 256));

        assertEquals(0, LcaSectionBridge.estimateSkyConstFromVoxelsFallback(null, 4096, 16, 24, null));
        assertEquals(15, LcaSectionBridge.estimateSkyConstFromVoxelsFallback(null, 0, 16, 24, null));
        assertEquals(0, LcaSectionBridge.estimateSkyConstFromVoxelsFallback(null, 0, 4, 24, null));

        int[] solidRoofVoxels = new int[4096];
        Arrays.fill(solidRoofVoxels, 15 << 8, 4096, 1);
        assertEquals(0, LcaSectionBridge.estimateSkyConstFromVoxelsFallback(solidRoofVoxels, 256, 18, 24, id -> id != 0));
        solidRoofVoxels[4095] = 0;
        assertEquals(15, LcaSectionBridge.estimateSkyConstFromVoxelsFallback(solidRoofVoxels, 255, 18, 24, id -> id != 0));

        assertEquals(0, LcaSectionBridge.resolveSnapshotSkyConst4(null, 12, 24, null));
        LcaSectionBridge.RawSectionSnapshot explicitSky =
                new LcaSectionBridge.RawSectionSnapshot(0, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0, 11);
        assertEquals(11, LcaSectionBridge.resolveSnapshotSkyConst4(explicitSky, 0, 24, null));

        LcaSectionBridge.RawSectionSnapshot uniformSolid =
                new LcaSectionBridge.RawSectionSnapshot(0, true, 5, 0, new long[0], new int[]{5}, 4096, 0, 0, -1);
        assertEquals(0, LcaSectionBridge.resolveSnapshotSkyConst4(uniformSolid, 18, 24, id -> id != 0));

        LcaSectionBridge.RawSectionSnapshot uniformAir =
                new LcaSectionBridge.RawSectionSnapshot(0, true, 0, 0, new long[0], new int[]{0}, 0, 0, 0, -1);
        assertEquals(15, LcaSectionBridge.resolveSnapshotSkyConst4(uniformAir, 18, 24, id -> id != 0));
        assertEquals(0, LcaSectionBridge.resolveSnapshotSkyConst4(uniformAir, 4, 24, id -> id != 0));

        long[] packedOpenRoof = new long[256];
        LcaSectionBridge.RawSectionSnapshot packedOpenSnap =
                new LcaSectionBridge.RawSectionSnapshot(18, false, 0, 4, packedOpenRoof, new int[]{0, 1}, 10, 0, 0, -1);
        assertEquals(15, LcaSectionBridge.resolveSnapshotSkyConst4(packedOpenSnap, 18, 24, id -> id != 0));

        long[] packedSolidRoof = new long[256];
        Arrays.fill(packedSolidRoof, 240, 256, 0x1111_1111_1111_1111L);
        LcaSectionBridge.RawSectionSnapshot packedSolidSnap =
                new LcaSectionBridge.RawSectionSnapshot(18, false, 0, 4, packedSolidRoof, new int[]{0, 1}, 256, 0, 0, -1);
        assertEquals(0, LcaSectionBridge.resolveSnapshotSkyConst4(packedSolidSnap, 18, 24, id -> id != 0));

        LcaSectionBridge.RawSectionSnapshot fullSolidSnap =
                new LcaSectionBridge.RawSectionSnapshot(18, false, 0, 4, packedOpenRoof, new int[]{0, 1}, 4096, 0, 0, -1);
        assertEquals(0, LcaSectionBridge.resolveSnapshotSkyConst4(fullSolidSnap, 18, 24, id -> id != 0));

        // Field-backed section + isRawSectionSnapshotUnmodified + evictToZeroBitShell
        long[] livePacked = new long[256];
        livePacked[0] = 0x1010L;
        SyntheticSkyPalettedContainer liveContainer = new SyntheticSkyPalettedContainer(
                new SyntheticSkyContainerData(
                        new SyntheticSkyBitStorage(4, livePacked),
                        new SyntheticSkyPalette(0, 1)));
        FieldBackedSection fieldSection = new FieldBackedSection(liveContainer);

        LcaSectionBridge.RawSectionSnapshot captured =
                LcaSectionBridge.captureRawSectionSnapshot(fieldSection, o -> (Integer) o);
        assertNotNull(captured);
        assertTrue(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(fieldSection, captured));
        assertFalse(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(fieldSection, null));
        assertFalse(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified("not-a-section", captured));
        livePacked[0] = 0x2020L;
        assertFalse(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(fieldSection, captured));

        SyntheticSkyPalettedContainer uniformContainer = new SyntheticSkyPalettedContainer(
                new SyntheticSkyContainerData(
                        new SyntheticSkyBitStorage(0, new long[0]),
                        new SyntheticSkyPalette(0)));
        LcaSectionBridge.RawSectionSnapshot capturedUniform =
                LcaSectionBridge.captureRawSectionSnapshot(uniformContainer, o -> (Integer) o);
        assertTrue(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(uniformContainer, capturedUniform));
        assertFalse(LcaSectionBridge.ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(uniformContainer, captured));

        // Eviction gracefully returns 0 when net.minecraft.world.level.block.Block is absent
        assertEquals(0, LcaSectionBridge.ZeroBitShellEvictor.evictToZeroBitShell(null, 0));
        assertEquals(0, LcaSectionBridge.ZeroBitShellEvictor.evictToZeroBitShell(fieldSection, 0));
    }

    // -------------------------------------------------------------------------
    // 2. MoonriseTickingListSynchronizer & TrackedChunkColumn
    // -------------------------------------------------------------------------

    public static final class SyntheticRandomTickList {
        final Set<Integer> active = new HashSet<>();

        public void add(int pos12) {
            active.add(pos12);
        }

        public void remove(int pos12) {
            active.remove(pos12);
        }
    }

    public static final class SyntheticMoonriseSection {
        public final SyntheticRandomTickList tickingList = new SyntheticRandomTickList();
        public final SyntheticRandomTickList fluidTickingList = new SyntheticRandomTickList();
    }

    @Test
    @DisplayName("MoonriseTickingListSynchronizer binds to synthetic Moonrise section lists and mirrors block/fluid flips")
    void moonriseTickingListSynchronizerBindingAndFlips() {
        assertNull(LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer.bindToSectionIfPresent(null));
        assertNull(LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer.bindToSectionIfPresent("plain"));

        LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer unbound =
                LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer.bindToSection("plain");
        assertNotNull(unbound);
        unbound.onTickingBlockFlip(LcaSectionBacking.pos12(1, 2, 3), 0, 5, true);
        assertTrue(unbound.isTickingBlockAt(1, 2, 3));
        unbound.onTickingBlockFlip(LcaSectionBacking.pos12(1, 2, 3), 5, 0, false);
        assertFalse(unbound.isTickingBlockAt(1, 2, 3));

        SyntheticMoonriseSection nmsSection = new SyntheticMoonriseSection();
        LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer sync =
                LcaInMemoryChunkCoordinator.MoonriseTickingListSynchronizer.bindToSectionIfPresent(nmsSection);
        assertNotNull(sync);

        int pos = LcaSectionBacking.pos12(4, 5, 6);
        sync.onTickingBlockFlip(pos, 0, 10, true);
        assertTrue(sync.isTickingBlockAt(4, 5, 6));
        assertTrue(nmsSection.tickingList.active.contains(pos));
        sync.onTickingBlockFlip(pos, 10, 0, false);
        assertFalse(sync.isTickingBlockAt(4, 5, 6));
        assertFalse(nmsSection.tickingList.active.contains(pos));

        int fpos = LcaSectionBacking.pos12(7, 8, 9);
        sync.onTickingFluidFlip(fpos, 0, 20, true);
        assertTrue(sync.isTickingFluidAt(7, 8, 9));
        assertTrue(nmsSection.fluidTickingList.active.contains(fpos));
        sync.onTickingFluidFlip(fpos, 20, 0, false);
        assertFalse(sync.isTickingFluidAt(7, 8, 9));
        assertFalse(nmsSection.fluidTickingList.active.contains(fpos));

        // Attach to TrackedChunkColumn via setMoonriseSynchronizer
        LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                new LcaWireStreamProtocol.ClientChunkSlabCache(16), true);
        try {
            LcaSectionBacking[] sections = new LcaSectionBacking[]{
                    new LcaSectionBacking(new byte[0], 0),
                    new LcaSectionBacking(new byte[0], 1)
            };
            LcaInMemoryChunkCoordinator.TrackedChunkColumn col =
                    coord.attachChunkColumn("minecraft:overworld", 2, 3, sections, null, 1L, false);
            col.setMoonriseSynchronizer(0, sync);
            col.setMoonriseSynchronizer(1, sync);
            assertEquals(2, col.sectionCount());
        } finally {
            coord.shutdownCompactionPool();
        }
    }

    // -------------------------------------------------------------------------
    // 3. NmsRegionStorageInterceptor synthetic NMS discovery & write paths
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface FakeStreamWrapper<O> {
        O wrap(O stream) throws IOException;
    }

    public static final class FakeRegionFileVersion {
        public static final FakeRegionFileVersion VERSION_NONE = new FakeRegionFileVersion(3, null, s -> s, s -> s);
        private final int id;
        private final FakeStreamWrapper<OutputStream> outWrapper;

        public FakeRegionFileVersion(
                int id,
                String externalName,
                FakeStreamWrapper<InputStream> inWrapper,
                FakeStreamWrapper<OutputStream> outWrapper
        ) {
            this.id = id;
            this.outWrapper = outWrapper;
        }

        public int getId() {
            return id;
        }

        public static FakeRegionFileVersion fromId(int id) {
            return id == 3 ? VERSION_NONE : null;
        }

        public OutputStream wrap(OutputStream out) throws IOException {
            return outWrapper.wrap(out);
        }
    }

    public static final class FakeRegionStorageInfo {
        public final String level;

        public FakeRegionStorageInfo(String level) {
            this.level = level;
        }
    }

    public static class FakeRegionFile implements AutoCloseable {
        public FakeRegionFileVersion version = FakeRegionFileVersion.VERSION_NONE;
        private final Path path;
        final AtomicBoolean closed = new AtomicBoolean(false);

        public FakeRegionFile(Path path) {
            this.path = path;
        }

        public Path getPath() {
            return path;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    public static class FakeRegionCache extends HashMap<Long, Object> {
        private static final long serialVersionUID = 1L;

        public Object get(long key) {
            return super.get(Long.valueOf(key));
        }

        public Object getAndMoveToFirst(long key) {
            return super.get(Long.valueOf(key));
        }

        public Object put(long key, Object val) {
            return super.put(Long.valueOf(key), val);
        }
    }

    public static final class FakeRegionFileStorage {
        public final Path folder;
        public final FakeRegionStorageInfo info = new FakeRegionStorageInfo("overworld");
        public final Set<Long> nonExistingRegionFiles = new HashSet<>();
        public FakeRegionCache regionCache = new FakeRegionCache();

        public FakeRegionFileStorage(Path folder) {
            this.folder = folder;
        }

        public FakeRegionFile getRegionFile(FakeChunkPos pos) {
            return null;
        }
    }

    public static final class FinalRegionFile {
        public final Path path;

        public FinalRegionFile(Path path) {
            this.path = path;
        }
    }

    public static final class IncompatibleFinalStorage {
        public final Path folder;
        public final Map<Long, FinalRegionFile> regionCache = new HashMap<>();

        public IncompatibleFinalStorage(Path folder) {
            this.folder = folder;
        }
    }

    public static final class NoCacheStorage {
        public final Path folder;

        public NoCacheStorage(Path folder) {
            this.folder = folder;
        }
    }

    public static final class FakeChunkMap {
        public final FakeRegionFileStorage storage;

        public FakeChunkMap(FakeRegionFileStorage storage) {
            this.storage = storage;
        }
    }

    public static final class FakeServerLevel {
        public final FakeChunkMap chunkMap;

        public FakeServerLevel(FakeChunkMap chunkMap) {
            this.chunkMap = chunkMap;
        }
    }

    public static final class FakeCraftWorld {
        private final FakeServerLevel handle;

        public FakeCraftWorld(FakeServerLevel handle) {
            this.handle = handle;
        }

        public FakeServerLevel getHandle() {
            return handle;
        }
    }

    public static final class FakeChunkPos {
        public final int x;
        public final int z;

        public FakeChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static byte[] minimalChunkNbtBytes(int cx, int cz) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", cx);
        root.put("zPos", cz);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 10L);
        root.put("InhabitedTime", 0L);
        return Nbt.writeNamedRoot("", root);
    }

    @Test
    @DisplayName("NmsRegionStorageInterceptor discovers synthetic NMS storages, handles pre-encoded/uncompressed/zlib writes, and clears chunks")
    void nmsRegionStorageInterceptorDiscoveryAndWritePaths(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("world");
        Path regionDir = Files.createDirectories(worldDir.resolve("region"));
        Path placeholderFile = regionDir.resolve("r.0.0.mca");
        Files.write(placeholderFile, new byte[0]);

        FakeRegionFileStorage storageObj = new FakeRegionFileStorage(regionDir);
        storageObj.nonExistingRegionFiles.add(1234L);
        FakeRegionFile cachedRf = new FakeRegionFile(placeholderFile);
        storageObj.regionCache.put(0L, cachedRf);

        FakeCraftWorld craftWorld = new FakeCraftWorld(new FakeServerLevel(new FakeChunkMap(storageObj)));

        assertEquals(List.of(), NmsRegionStorageInterceptor.discoverRegionFileStorages(null));
        List<Object> discovered = NmsRegionStorageInterceptor.discoverRegionFileStorages(craftWorld);
        assertEquals(1, discovered.size());
        assertEquals(storageObj, discovered.get(0));
        assertEquals(1, NmsRegionStorageInterceptor.discoverRegionFileStorages(storageObj).size());
        assertEquals(regionDir, NmsRegionStorageInterceptor.extractFolderField(storageObj));
        assertNull(NmsRegionStorageInterceptor.extractFolderField(null));
        assertEquals(worldDir, NmsRegionStorageInterceptor.resolveDimensionDirectory(craftWorld, tempDir));
        assertEquals(tempDir, NmsRegionStorageInterceptor.resolveDimensionDirectory(null, tempDir));

        try (LcaRegionFileStorage lcaStorage = new LcaRegionFileStorage(regionDir)) {
            NmsRegionStorageInterceptor.LcaRegionHandle handle =
                    new NmsRegionStorageInterceptor.LcaRegionHandle(lcaStorage, 0, 0);
            assertEquals(0, NmsRegionStorageInterceptor.onGetRecalculateCount(handle));

            byte[] nbt = minimalChunkNbtBytes(1, 2);
            FakeChunkPos pos = new FakeChunkPos(1, 2);

            // 1. LcaPreEncodingOutputStream single-byte & bulk writes + idempotent close
            ByteArrayOutputStream slabOut = new ByteArrayOutputStream();
            try (NmsRegionStorageInterceptor.LcaPreEncodingOutputStream preOut =
                         new NmsRegionStorageInterceptor.LcaPreEncodingOutputStream(handle, slabOut)) {
                preOut.write(nbt[0]);
                preOut.write(nbt, 1, nbt.length - 1);
                preOut.close();
                preOut.close(); // idempotent second close
            }
            byte[] preEncodedSlab = slabOut.toByteArray();
            assertTrue(preEncodedSlab.length > 0);

            // 2. onWriteByteBuffer with LCA_PREENCODED_VERSION_ID (0x4C)
            ByteBuffer preBuf = ByteBuffer.allocate(5 + preEncodedSlab.length);
            preBuf.putInt(preEncodedSlab.length + 1);
            preBuf.put((byte) NmsRegionStorageInterceptor.LCA_PREENCODED_VERSION_ID);
            preBuf.put(preEncodedSlab);
            preBuf.flip();
            NmsRegionStorageInterceptor.onWriteByteBuffer(handle, pos, preBuf);
            assertTrue(lcaStorage.doesChunkExist(1, 2));

            // 3. onWriteByteBuffer with compressionId == 3 (uncompressed)
            byte[] nbt3 = minimalChunkNbtBytes(2, 3);
            ByteBuffer rawBuf = ByteBuffer.allocate(5 + nbt3.length);
            rawBuf.putInt(nbt3.length + 1);
            rawBuf.put((byte) 3);
            rawBuf.put(nbt3);
            rawBuf.flip();
            NmsRegionStorageInterceptor.onWriteByteBuffer(handle, new FakeChunkPos(2, 3), rawBuf);
            assertTrue(lcaStorage.doesChunkExist(2, 3));

            // 4. onWriteByteBuffer with compressionId == 2 (zlib)
            ByteArrayOutputStream zlibOut = new ByteArrayOutputStream();
            try (DeflaterOutputStream def = new DeflaterOutputStream(zlibOut)) {
                def.write(minimalChunkNbtBytes(3, 4));
            }
            byte[] zlibBytes = zlibOut.toByteArray();
            ByteBuffer zlibBuf = ByteBuffer.allocate(5 + zlibBytes.length);
            zlibBuf.putInt(zlibBytes.length + 1);
            zlibBuf.put((byte) 2);
            zlibBuf.put(zlibBytes);
            zlibBuf.flip();
            NmsRegionStorageInterceptor.onWriteByteBuffer(handle, new FakeChunkPos(3, 4), zlibBuf);
            assertTrue(lcaStorage.doesChunkExist(3, 4));

            // 5. Truncated buffer throws IOException
            ByteBuffer truncated = ByteBuffer.allocate(3);
            assertThrows(IOException.class,
                    () -> NmsRegionStorageInterceptor.onWriteByteBuffer(handle, pos, truncated));

            // 6. onClear, onFlush, onClose, and null-guard paths
            NmsRegionStorageInterceptor.onClear(null, pos);
            NmsRegionStorageInterceptor.onClear(handle, pos);
            assertFalse(lcaStorage.doesChunkExist(1, 2));
            NmsRegionStorageInterceptor.onFlush(handle);
            NmsRegionStorageInterceptor.onClose(handle);
            NmsRegionStorageInterceptor.onWriteByteBuffer(null, pos, rawBuf);
            NmsRegionStorageInterceptor.onWriteByteBuffer(handle, pos, null);
        }
    }

    // -------------------------------------------------------------------------
    // 4. LeafAnvilRuntime, LeafAnvilBitSuite, LeafAnvilConfig, LeafAnvilWorldConverter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LeafAnvilRuntime, LeafAnvilBitSuite, and LeafAnvilWorldConverter handle folder resolution, BIT probes, and carrier incompatibilities")
    void runtimeBitSuiteAndConverterFolderDiscovery(@TempDir Path tempDir) throws Exception {
        // LeafAnvilWorldConverter.resolveStorageFolders across direct, unified 26.1+, and legacy DIM-1/DIM1 layouts
        assertTrue(LeafAnvilWorldConverter.resolveStorageFolders(null).isEmpty());
        assertTrue(LeafAnvilWorldConverter.resolveStorageFolders(tempDir.resolve("missing")).isEmpty());

        Path worldRoot = Files.createDirectories(tempDir.resolve("multi_dim_world"));
        Files.createDirectories(worldRoot.resolve("region"));
        Files.createDirectories(worldRoot.resolve("entities"));
        Files.createDirectories(worldRoot.resolve("poi"));
        Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(worldRoot.resolve("dimensions/custom/sky/poi"));
        Files.createDirectories(worldRoot.resolve("DIM-1/region"));
        Files.createDirectories(worldRoot.resolve("DIM1/entities"));
        List<Path> resolved = LeafAnvilWorldConverter.resolveStorageFolders(worldRoot);
        assertEquals(7, resolved.size());

        // LeafAnvilRuntime lifecycle, flushWorld, flushAll, detachWorld, BIT probes & carrier incompatibilities
        Path dataDir = Files.createDirectories(tempDir.resolve("rt_data"));
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());
        Mockito.when(accessor.isPrimaryThread()).thenReturn(false);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            assertNotNull(runtime.config());
            assertNotNull(runtime.wireStreamCache());
            assertNotNull(runtime.chunkCoordinator());

            runtime.attachAndInterceptWorld("overworld", worldRoot, -64, 320, null);
            runtime.flushWorld("overworld");
            runtime.flushWorld("nonexistent");
            runtime.flushAll();

            LeafAnvilBitSuite bitSuite = runtime.bitSuite();
            assertNotNull(bitSuite.executeAllAndFormat());
            assertNull(bitSuite.verifyRequiredClass(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT, "TestProbe", "  ", null));
            assertNull(bitSuite.verifyRequiredClass(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT, "TestProbe", "java.lang.String", null));
            assertNull(bitSuite.verifyRequiredClass(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT, "TestProbe", "net.leaf.anvil.DoesNotExist", null));
            assertNotNull(bitSuite.verifyRequiredClass(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                    "TestProbe",
                    LeafAnvilRuntime.class.getName(),
                    LeafAnvilRuntime.class.getClassLoader()));

            bitSuite.recordWorldInterception("overworld", 0);
            assertEquals(0, bitSuite.interceptedCountForWorld("overworld"));
            assertEquals(0, bitSuite.interceptedCountForWorld(null));

            runtime.handleCarrierIncompatibility("BukkitPluginServerAccessor", "synthetic test");
            runtime.handleCarrierIncompatibility("ServerNetworkingPayload", "synthetic test");
            runtime.handleCarrierIncompatibility("OtherCarrier", "synthetic test");
            runtime.disableAllFunctions("synthetic fatal");
            assertFalse(runtime.isDiskStorageOperational());
            assertFalse(runtime.isSectionWireOperational());

            runtime.detachWorld("overworld");
            runtime.detachWorld(null);
        }
    }

    // -------------------------------------------------------------------------
    // 5. LcaOrderlessDictTable, LcaClientWireReceiver, LcaWireStreamProtocol, Transformer
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LcaOrderlessDictTable immediate interning, learned entries, KnownSlotBitSet intersection, and error guards")
    void orderlessDictTableAndKnownSlotBitSetEdgeCases() throws Exception {
        LcaOrderlessDictTable table = new LcaOrderlessDictTable();
        assertNull(table.putOrGetImmediate(LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE, new byte[]{1, 2}));
        assertNull(table.getSlot(-1));
        assertNull(table.getSlot(LcaOrderlessDictTable.TABLE_SLOT_COUNT + 5));

        byte[] pattern = new byte[64];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (byte) (i * 7 + 3);
        }
        LcaOrderlessDictTable.DictSlotEntry slot =
                table.putOrGetImmediate(LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE, pattern);
        assertNotNull(slot);
        assertEquals(slot, table.putOrGetImmediate(LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE, pattern));

        LcaOrderlessDictTable.DictSlotEntry utf8Slot =
                table.putOrGetUtf8Symbol("minecraft:stone_bricks_custom_symbol_for_testing");
        assertNotNull(utf8Slot);

        // Apply valid learned entry into a fresh client table, then re-apply idempotently
        LcaOrderlessDictTable clientTable = new LcaOrderlessDictTable();
        LcaOrderlessDictTable.DictLearnEntry learn = new LcaOrderlessDictTable.DictLearnEntry(
                slot.slotId12(), slot.tag8(), slot.kind(), slot.patternBytes());
        LcaOrderlessDictTable.DictSlotEntry applied = clientTable.applyLearnedEntry(learn);
        assertEquals(slot.slotId12(), applied.slotId12());
        assertEquals(applied, clientTable.applyLearnedEntry(learn));

        // Invalid slotId12 and mismatched tag8 fail closed
        LcaOrderlessDictTable.DictLearnEntry badSlot = new LcaOrderlessDictTable.DictLearnEntry(
                -1, slot.tag8(), slot.kind(), slot.patternBytes());
        assertThrows(IOException.class, () -> clientTable.applyLearnedEntry(badSlot));

        LcaOrderlessDictTable.DictLearnEntry badTag = new LcaOrderlessDictTable.DictLearnEntry(
                slot.slotId12(), (byte) (slot.tag8() ^ 0xFF), slot.kind(), slot.patternBytes());
        assertThrows(IOException.class, () -> clientTable.applyLearnedEntry(badTag));

        // Slot miss and tag4 mismatch on resolveVerifiedReference
        int validRef = LcaOrderlessDictTable.packRefWord16(slot.slotId12(), slot.tag8());
        assertEquals(applied, clientTable.resolveVerifiedReference(validRef));
        int badTagRef = (slot.slotId12() << 4) | ((LcaOrderlessDictTable.computeTag4(slot.tag8()) ^ 0xF) & 0xF);
        assertThrows(IOException.class, () -> clientTable.resolveVerifiedReference(badTagRef));
        int missRef = LcaOrderlessDictTable.packRefWord16((slot.slotId12() + 1) & 0xFFF, (byte) 0);
        assertThrows(IOException.class, () -> clientTable.resolveVerifiedReference(missRef));

        // KnownSlotBitSet intersectWith
        LcaOrderlessDictTable.KnownSlotBitSet a = new LcaOrderlessDictTable.KnownSlotBitSet();
        LcaOrderlessDictTable.KnownSlotBitSet b = new LcaOrderlessDictTable.KnownSlotBitSet();
        a.markKnown(10);
        a.markKnown(20);
        b.markKnown(20);
        b.markKnown(30);
        a.intersectWith(b);
        assertFalse(a.isKnown(10));
        assertTrue(a.isKnown(20));
        assertFalse(a.isKnown(30));
    }

    @Test
    @DisplayName("LcaClientWireReceiver handles handshake ACKs, malformed batches, MTCF frames, telemetry, and disconnect")
    void clientWireReceiverHandshakeBatchMtcfAndTelemetry() throws Exception {
        LcaClientWireReceiver receiver = new LcaClientWireReceiver();
        receiver.onInitialized("test-loader");
        LcaClientWireReceiver.logWarning("synthetic warning without throwable", null);
        LcaClientWireReceiver.logWarning("synthetic warning with throwable", new IllegalStateException("test"));

        long parityHash = 0xCAFEBABE12345678L;
        byte[] clientHs = receiver.buildClientHandshakeBytes(parityHash);
        LcaWireStreamProtocol.NegotiatedSession negotiated = receiver.onServerHandshakeAck(clientHs, parityHash);
        assertTrue(negotiated.isLcaStreamActive());

        // Truncated and null handshake ACKs fall back cleanly
        assertFalse(receiver.onServerHandshakeAck(null, parityHash).isLcaStreamActive());
        assertFalse(receiver.onServerHandshakeAck(new byte[]{1, 2}, parityHash).isLcaStreamActive());

        // Empty / malformed chunk batch & MTCF frames fail closed without throwing
        assertEquals(0, receiver.onChunkBatchPayloadReceived("minecraft:overworld", null, false, parityHash, null));
        assertEquals(0, receiver.onChunkBatchPayloadReceived("minecraft:overworld", new byte[0], false, parityHash, null));
        assertEquals(0, receiver.onChunkBatchPayloadReceived("minecraft:overworld", new byte[]{1, 2, 3, 4}, false, parityHash, null));

        assertNull(receiver.onMultiTargetTickFrameReceived("minecraft:overworld", 0, null, false, parityHash, null, null));
        assertNull(receiver.onMultiTargetTickFrameReceived("minecraft:overworld", 0, new byte[]{1, 2}, false, parityHash, null, null));
        assertNull(receiver.onMultiTargetTickFrameReceived("minecraft:overworld", 0, new byte[]{1, 2, 3, 4, 5}, false, parityHash, null, null));

        // Valid consolidated multi-target frame routed through receiver
        LcaOrderlessDictTable serverDict = new LcaOrderlessDictTable();
        LcaMultiTargetConsolidator.ConsolidatedMultiTargetFrame mtcf =
                LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                        List.of(
                                new LcaMultiTargetConsolidator.TargetRecipient(
                                        0, UUID.randomUUID(), new LcaOrderlessDictTable.KnownSlotBitSet()),
                                new LcaMultiTargetConsolidator.TargetRecipient(
                                        2, UUID.randomUUID(), new LcaOrderlessDictTable.KnownSlotBitSet())),
                        serverDict,
                        LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                        List.of(),
                        List.of(new LcaMultiTargetConsolidator.KinematicDeltaRow(
                                101, (byte) 1, (byte) 2, (byte) 3, true, (byte) 4, (byte) 5)),
                        new byte[0],
                        1
                );
        AtomicInteger kinCount = new AtomicInteger();
        LcaMultiTargetConsolidator.DecodedMultiTargetFrame decoded =
                receiver.onMultiTargetTickFrameReceived(
                        "minecraft:overworld",
                        0,
                        mtcf.sharedWireFrameBytes(),
                        false,
                        parityHash,
                        rows -> kinCount.addAndGet(rows.size()),
                        null
                );
        assertNotNull(decoded);
        assertEquals(1, kinCount.get());

        // Non-targeted player slot returns decoded frame without invoking callback
        AtomicInteger ignoredKin = new AtomicInteger();
        LcaMultiTargetConsolidator.DecodedMultiTargetFrame skipped =
                receiver.onMultiTargetTickFrameReceived(
                        "minecraft:overworld",
                        5,
                        mtcf.sharedWireFrameBytes(),
                        false,
                        parityHash,
                        rows -> ignoredKin.addAndGet(rows.size()),
                        null
                );
        assertNotNull(skipped);
        assertEquals(0, ignoredKin.get());

        // Telemetry + revisit digest + disconnect
        LcaSectionBacking[] backings = new LcaSectionBacking[]{
                new LcaSectionBacking(new byte[0], 0),
                new LcaSectionBacking(new byte[]{1, 2, 3, 4}, 0)
        };
        for (int i = 0; i < 16; i++) {
            receiver.recordClientChunkLoadSample(backings, 128);
        }
        assertNotNull(receiver.buildRegionRevisitDigestBytes("minecraft:overworld", 0, 0));
        receiver.onClientDisconnect();
    }

    @Test
    @DisplayName("LeafAnvilSectionClassTransformer, LeafAnvilPremain, and LeafAnvilAgentBootstrap guard paths")
    void agentTransformerPremainAndBootstrapGuards() {
        LeafAnvilSectionClassTransformer transformer = new LeafAnvilSectionClassTransformer("test/nms/");
        assertNull(transformer.transform(null, null, null, null, new byte[4]));
        assertNull(transformer.transform(null, "test/nms/LevelChunkSection", null, null, null));
        assertNull(transformer.transform(null, "test/nms/LevelChunkSection", String.class, null, new byte[4]));
        assertNull(transformer.transform(null, "com/example/UnrelatedClass", null, null, new byte[4]));
        // Corrupted class bytes on matched target fail closed with warning and return null
        assertNull(transformer.transform(null, "test/nms/LevelChunkSection", null, null, new byte[]{0, 1, 2, 3}));
        assertNull(transformer.transform(null, "test/nms/SerializableChunkData", null, null, new byte[]{0, 1, 2, 3}));
        assertNull(transformer.transform(null, "net/minecraft/world/level/chunk/LevelChunkSection", null, null, new byte[]{0, 1, 2, 3}));
        assertNull(transformer.transform(null, "net/minecraft/world/level/chunk/storage/SerializableChunkData", null, null, new byte[]{0, 1, 2, 3}));

        // LeafAnvilAgentBootstrap safe guards
        assertEquals(0, LeafAnvilAgentBootstrap.resolveBlockStateId(null));
    }

    @Test
    @DisplayName("NmsRegionStorageInterceptor installs bytecode cache bridge, drains placeholders, and rejects incompatible layouts")
    void nmsRegionStorageInterceptorInstallAndBridgeLifecycle(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("bridge_world");
        Path regionDir = Files.createDirectories(worldDir.resolve("region"));
        Path placeholderFile = regionDir.resolve("r.0.0.mca");
        Files.write(placeholderFile, new byte[0]);

        FakeRegionFileStorage storageObj = new FakeRegionFileStorage(regionDir);
        storageObj.nonExistingRegionFiles.add(777L);
        FakeRegionFile cachedRf = new FakeRegionFile(placeholderFile);
        storageObj.regionCache.put(0L, cachedRf);

        FakeCraftWorld craftWorld = new FakeCraftWorld(new FakeServerLevel(new FakeChunkMap(storageObj)));

        try (LcaRegionFileStorage.LcaWorldStorageSession session =
                     new LcaRegionFileStorage.LcaWorldStorageSession(worldDir, -4, 24, 4671, false, false)) {
            int intercepted = NmsRegionStorageInterceptor.interceptWorldStorages(craftWorld, session);
            assertEquals(1, intercepted);
            assertTrue(storageObj.nonExistingRegionFiles.isEmpty());
            assertTrue(cachedRf.closed.get());
            assertFalse(Files.exists(placeholderFile));

            // Calling getAndMoveToFirst(0L) on the swapped regionCache bridge instantiates a FakeRegionFile bridge with LcaRegionFileVersion
            FakeRegionFile bridged = (FakeRegionFile) storageObj.regionCache.getAndMoveToFirst(0L);
            assertNotNull(bridged);
            assertNotNull(bridged.version);

            // Re-installing is idempotent
            assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(storageObj, session.voxelStorage()));

            // Incompatible storage layouts return false without throwing
            assertFalse(NmsRegionStorageInterceptor.installOnRegionFileStorage(null, session.voxelStorage()));
            assertFalse(NmsRegionStorageInterceptor.installOnRegionFileStorage(storageObj, null));
            assertFalse(NmsRegionStorageInterceptor.installOnRegionFileStorage(
                    new NoCacheStorage(regionDir), session.voxelStorage()));
            assertFalse(NmsRegionStorageInterceptor.installOnRegionFileStorage(
                    new IncompatibleFinalStorage(regionDir), session.voxelStorage()));

            NmsRegionStorageInterceptor.uninstallWorldStorages(session);
        }
    }

    @Test
    @DisplayName("LcaInMemoryChunkCoordinator commits cold column splice with dirty agent carrier and validates auxiliary records")
    void coordinatorColdSpliceAndAuxiliaryRecords(@TempDir Path tempDir) throws Exception {
        Path regionDir = Files.createDirectories(tempDir.resolve("coord_region"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            storage.writeChunkNbtBytes(0, 0, minimalChunkNbtBytes(0, 0));
            io.github.dailystruggle.anvil.RawChunkFrameSnapshot coldSnap = storage.readRawChunkFrame(0, 0, null);
            assertNotNull(coldSnap);

            LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                    new LcaWireStreamProtocol.ClientChunkSlabCache(32), true);
            try {
                assertFalse(coord.commitOrSpliceColdColumnToStorage("minecraft:overworld", 99, 99, null, 1L));

                LcaInMemoryChunkCoordinator.TrackedChunkColumn col = coord.attachColdChunkFrameColumn(
                        "minecraft:overworld", 0, 0, coldSnap, null, 10L, false);
                col.bindColdChunkFrame(coldSnap, storage, null, id -> "minecraft:stone");

                // Synthetic dirty LcaAgentSectionCarrier spliced into cold column commit
                int packedEntry = LcaSectionBacking.packEntry32(LcaSectionBacking.pos12(2, 3, 4), 42);
                net.leaf.anvil.agent.LcaAgentSectionCarrier dirtyCarrier =
                        new net.leaf.anvil.agent.LcaAgentSectionCarrier() {
                            @Override
                            public void leafanvil$mountBaseFrame(byte[] baseFrame, int uniformStateId, int dataVersion, long secDed64) {
                            }

                            @Override
                            public void leafanvil$recordBlockMutation(int pos12, int prevStateId19, int newStateId19, long currentTick) {
                            }

                            @Override
                            public int leafanvil$getDirtyCount() {
                                return 1;
                            }

                            @Override
                            public int[] leafanvil$snapshotDirtyOverlay() {
                                return new int[]{packedEntry};
                            }

                            @Override
                            public void leafanvil$clearDirtyAfterCompaction(byte[] baseFrame, long secDed) {
                            }

                            @Override
                            public byte[] leafanvil$getBaseFrame() {
                                return new byte[0];
                            }

                            @Override
                            public int leafanvil$getUniformStateId() {
                                return 0;
                            }

                            @Override
                            public int leafanvil$getDataVersion() {
                                return 4671;
                            }

                            @Override
                            public long leafanvil$getBaselineSecDed64() {
                                return 0L;
                            }
                        };

                assertTrue(coord.commitOrSpliceColdColumnToStorage(
                        "minecraft:overworld", 0, 0, new Object[]{dirtyCarrier}, 25L));

                // RawPaletteStorage value semantics
                LcaSectionBridge.RawPaletteStorage rps1 =
                        new LcaSectionBridge.RawPaletteStorage(4, new long[]{1L, 2L}, "pal", null);
                LcaSectionBridge.RawPaletteStorage rps2 =
                        new LcaSectionBridge.RawPaletteStorage(4, new long[]{1L, 2L}, "pal", null);
                Object rpsSelf = rps1;
                assertEquals(rps1, rpsSelf);
                assertEquals(rps1, rps2);
                assertEquals(rps1.hashCode(), rps2.hashCode());
                assertTrue(rps1.toString().contains("bits=4"));
                assertFalse(rps1.equals(null));
                assertFalse(rps1.equals("other"));
                assertFalse(rps1.equals(new LcaSectionBridge.RawPaletteStorage(5, new long[]{1L, 2L}, "pal", null)));

                // Coordinator auxiliary records value semantics
                Object[] emptyObjs = new Object[0];
                LcaSectionBridge.RawSectionSnapshot[] emptySnaps = new LcaSectionBridge.RawSectionSnapshot[0];
                LcaSectionBacking[] emptyBackings = new LcaSectionBacking[0];
                LcaInMemoryChunkCoordinator.CompletedChunkCompaction cc1 =
                        new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                                "minecraft:overworld", 0, 0, null, emptyObjs, emptySnaps, emptyBackings, 10L, null);
                LcaInMemoryChunkCoordinator.CompletedChunkCompaction cc2 =
                        new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                                "minecraft:overworld", 0, 0, null, emptyObjs, emptySnaps, emptyBackings, 10L, null);
                Object ccSelf = cc1;
                assertEquals(cc1, ccSelf);
                assertEquals(cc1, cc2);
                assertEquals(cc1.hashCode(), cc2.hashCode());
                assertTrue(cc1.toString().contains("chunkX=0"));
                assertFalse(cc1.equals(new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                        "minecraft:overworld", 1, 0, null, emptyObjs, emptySnaps, emptyBackings, 10L, null)));

                LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload emptyBatch =
                        new LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(
                                0, 0, List.of(new LcaWireStreamProtocol.EntryCleanAck(0)));
                LcaInMemoryChunkCoordinator.RegionPackedBatchFrame rpf1 =
                        new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                                0, 0, emptyBatch, new byte[]{1, 2}, new byte[]{3, 4}, List.of(), 1, 0, 0);
                LcaInMemoryChunkCoordinator.RegionPackedBatchFrame rpf2 =
                        new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                                0, 0, emptyBatch, new byte[]{1, 2}, new byte[]{3, 4}, List.of(), 1, 0, 0);
                Object rpfSelf = rpf1;
                assertEquals(rpf1, rpfSelf);
                assertEquals(rpf1, rpf2);
                assertEquals(rpf1.hashCode(), rpf2.hashCode());
                assertTrue(rpf1.toString().contains("regionX=0"));
                assertFalse(rpf1.equals(new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                        1, 0, emptyBatch, new byte[]{1, 2}, new byte[]{3, 4}, List.of(), 1, 0, 0)));

                LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit isc1 =
                        new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit(
                                "minecraft:overworld", 0, 0, 1, new int[]{5, 6}, 10L);
                LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit isc2 =
                        new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit(
                                "minecraft:overworld", 0, 0, 1, new int[]{5, 6}, 10L);
                Object iscSelf = isc1;
                assertEquals(isc1, iscSelf);
                assertEquals(isc1, isc2);
                assertEquals(isc1.hashCode(), isc2.hashCode());
                assertTrue(isc1.toString().contains("sectionIndex=1"));
                assertFalse(isc1.equals(new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit(
                        "minecraft:overworld", 1, 0, 1, new int[]{5, 6}, 10L)));
            } finally {
                coord.shutdownCompactionPool();
            }
        }
    }

    @Test
    @DisplayName("LcaOrderlessDictTable associative slot spills, LcaWireStreamProtocol orderless/zlib paths, and LcaMultiTargetConsolidator guards")
    void orderlessDictSpillWireProtocolAndConsolidatorEdgeCases() throws Exception {
        // Fill past 4,096 slots to exercise primary + secondary associative placement and collision/eviction paths
        LcaOrderlessDictTable fullTable = new LcaOrderlessDictTable();
        int inserted = 0;
        for (int i = 0; i < 4500; i++) {
            byte[] pat = new byte[32];
            pat[0] = (byte) (i & 0xFF);
            pat[1] = (byte) ((i >>> 8) & 0xFF);
            pat[2] = (byte) ((i * 31) & 0xFF);
            pat[3] = (byte) ((i * 17) & 0xFF);
            if (fullTable.putOrGetImmediate(LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE, pat) != null) {
                inserted++;
            }
        }
        assertTrue(inserted > 2000);

        // LcaWireStreamProtocol orderless-dict batch encode/decode + missing-dict fail-closed error
        LcaOrderlessDictTable serverDict = new LcaOrderlessDictTable();
        LcaOrderlessDictTable clientDict = new LcaOrderlessDictTable();
        LcaOrderlessDictTable.KnownSlotBitSet knownSlots = new LcaOrderlessDictTable.KnownSlotBitSet();

        byte[] sectionBody = new byte[48];
        Arrays.fill(sectionBody, (byte) 0x5A);
        LcaWireStreamProtocol.SectionWireSlice slice = new LcaWireStreamProtocol.SectionWireSlice(
                0, (short) sectionBody.length, sectionBody, new int[]{LcaSectionBacking.packEntry32(10, 5)});
        LcaWireStreamProtocol.EntryFullChunk fullEntry = new LcaWireStreamProtocol.EntryFullChunk(
                1, 2, 100L, 0x11223344L, List.of(slice), new byte[]{7, 8, 9});
        LcaWireStreamProtocol.EntryPatchDelta patchEntry = new LcaWireStreamProtocol.EntryPatchDelta(
                3, 101L, 0x55667788L, 1, List.of(slice));
        LcaWireStreamProtocol.EntryCleanAck ackEntry = new LcaWireStreamProtocol.EntryCleanAck(4);

        LcaWireStreamProtocol.NegotiatedSession session = new LcaWireStreamProtocol.NegotiatedSession(
                true, LcaWireStreamProtocol.ALL_CAPABILITIES, 64, 0x1234L);
        LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload batch =
                new LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(
                        0, 0, List.of(fullEntry, patchEntry, ackEntry));

        byte[] wireBatch = LcaWireStreamProtocol.encodeChunkBatchPayload(batch, session, serverDict, knownSlots);
        LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload decoded =
                LcaWireStreamProtocol.decodeChunkBatchPayload(wireBatch, session, clientDict);
        assertEquals(3, decoded.entries().size());

        // Decoding orderless-dict batch without a client dict table fails closed
        assertThrows(IOException.class,
                () -> LcaWireStreamProtocol.decodeChunkBatchPayload(wireBatch, session, null));

        // Proxy-safe zlib stored frame round-trip and malformed input error
        byte[] storedFrame = LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(wireBatch);
        byte[] unwrapped = LcaWireStreamProtocol.decodeProxySafeZlibStoredFrame(storedFrame);
        assertTrue(Arrays.equals(wireBatch, unwrapped));
        assertThrows(IllegalArgumentException.class,
                () -> LcaWireStreamProtocol.decodeProxySafeZlibStoredFrame(new byte[]{1, 2}));

        // LcaMultiTargetConsolidator invalid inputs fail closed
        assertThrows(IllegalArgumentException.class,
                () -> LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                        List.of(), serverDict, LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                        List.of(), List.of(), new byte[0], 1));
        assertThrows(IOException.class,
                () -> LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(new byte[]{0, 0, 0, 0}, clientDict));
    }

    @Test
    @DisplayName("LeafAnvilWorldConverter heal/convert round-trip and LeafAnvilCommand parameter validation helpers")
    void converterHealRoundTripAndCommandValidation(@TempDir Path tempDir) throws Exception {
        Path worldDir = Files.createDirectories(tempDir.resolve("heal_world"));
        Path regionDir = Files.createDirectories(worldDir.resolve("region"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            storage.writeChunkNbtBytes(0, 0, minimalChunkNbtBytes(0, 0));
            storage.writeChunkNbtBytes(1, 0, minimalChunkNbtBytes(1, 0));
            storage.flush();
        }
        // Create a corrupted/truncated .lca alongside the valid one to exercise heal failure reporting
        Files.write(regionDir.resolve("r.9.9.lca"), new byte[]{1, 2, 3, 4});

        LeafAnvilWorldConverter.OperationReport healReport =
                LeafAnvilWorldConverter.healAndVerifyWorld(worldDir);
        assertNotNull(healReport);
        assertTrue(healReport.filesScanned() >= 1);

        LeafAnvilWorldConverter.OperationReport toMcaReport =
                LeafAnvilWorldConverter.convertWorldToMca(worldDir, false);
        assertNotNull(toMcaReport);
        assertTrue(Files.exists(regionDir.resolve("r.0.0.mca")));

        // LeafAnvilCommand parameter validation via convert subcommand's workers parameter
        Path dataDir = Files.createDirectories(tempDir.resolve("cmd_val_data"));
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());
        Mockito.when(accessor.isPrimaryThread()).thenReturn(false);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            runtime.attachAndInterceptWorld("heal_world", worldDir, -64, 320, null);

            net.leaf.anvil.common.command.LeafAnvilCommand cmd =
                    new net.leaf.anvil.common.command.LeafAnvilCommand("leafanvil", accessor, runtime);
            io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand convertSub = null;
            for (io.github.dailystruggle.commandsapi.common.CommandsAPICommand sub : cmd.getCommandLookup().values()) {
                if ("convert".equalsIgnoreCase(sub.name())) {
                    convertSub = (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand) sub;
                    break;
                }
            }
            assertNotNull(convertSub);
            io.github.dailystruggle.commandsapi.common.CommandParameter workersParam = null;
            for (Map.Entry<String, io.github.dailystruggle.commandsapi.common.CommandParameter> e
                    : convertSub.getParameterLookup().entrySet()) {
                if ("workers".equalsIgnoreCase(e.getKey())) {
                    workersParam = e.getValue();
                    break;
                }
            }
            assertNotNull(workersParam);
            assertFalse(workersParam.values().isEmpty());
            UUID caller = UUID.randomUUID();
            assertFalse(workersParam.isRelevant.apply(caller, null));
            assertFalse(workersParam.isRelevant.apply(caller, "  "));
            assertTrue(workersParam.isRelevant.apply(caller, "auto"));
            assertTrue(workersParam.isRelevant.apply(caller, "4"));
            assertFalse(workersParam.isRelevant.apply(caller, "0"));
            assertFalse(workersParam.isRelevant.apply(caller, "65"));
            assertFalse(workersParam.isRelevant.apply(caller, "notanumber"));

            // Execute all subcommands while a world session IS attached
            for (io.github.dailystruggle.commandsapi.common.CommandsAPICommand sub : cmd.getCommandLookup().values()) {
                Map<String, List<String>> params = switch (sub.name().toLowerCase(java.util.Locale.ROOT)) {
                    case "convert" -> Map.of("world", List.of("heal_world"), "format", List.of("lca"), "workers", List.of("2"));
                    case "heal" -> Map.of("world", List.of("heal_world"), "workers", List.of("2"));
                    default -> Map.of("world", List.of("heal_world"));
                };
                sub.onCommand(caller, params, null);
            }
        }
    }

    @Test
    @DisplayName("LcaInMemoryChunkCoordinator async CoW dirty flush, inter-server wire payloads, consolidator budgets, and config YAML parsing")
    void asyncDirtyFlushWireInterServerBudgetsAndConfig(@TempDir Path tempDir) throws Exception {
        // 1. Coordinator async dirty CoW flush
        Path regionDir = Files.createDirectories(tempDir.resolve("async_flush_region"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            storage.writeChunkNbtBytes(0, 0, minimalChunkNbtBytes(0, 0));
            io.github.dailystruggle.anvil.RawChunkFrameSnapshot coldSnap = storage.readRawChunkFrame(0, 0, null);
            assertNotNull(coldSnap);

            LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                    new LcaWireStreamProtocol.ClientChunkSlabCache(16), true);
            try {
                assertFalse(coord.flushDirtyChunkCoWAsync("minecraft:overworld", 9, 9));
                LcaInMemoryChunkCoordinator.TrackedChunkColumn col = coord.attachColdChunkFrameColumn(
                        "minecraft:overworld", 0, 0, coldSnap, null, 10L, false);
                col.bindColdChunkFrame(coldSnap, storage, null, id -> "minecraft:stone");
                // Clean column returns false
                assertFalse(coord.flushDirtyChunkCoWAsync("minecraft:overworld", 0, 0));

                // Mutate voxel and trigger async dirty flush
                col.mutateVoxel(4, 1, 2, 3, 77, 20L);
                assertTrue(coord.flushDirtyChunkCoWAsync("minecraft:overworld", 0, 0));
                long deadline = System.currentTimeMillis() + 3000L;
                while (coord.dirtyChunkCount() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10L);
                }
                assertEquals(0, coord.flushExcessDirtyChunksAsync());
            } finally {
                coord.shutdownCompactionPool();
            }
        }

        // 2. Netty VarInt zero-bypass framing + InterServerChunkUpdatePayload (compressed & uncompressed)
        byte[] samplePayload = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        byte[] bypassFrame = LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(samplePayload);
        assertTrue(Arrays.equals(samplePayload, LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(bypassFrame)));
        assertThrows(IllegalArgumentException.class,
                () -> LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(new byte[]{5, 1, 2}));

        LcaWireStreamProtocol.InterServerChunkDeltaEntry deltaEntry =
                new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                        0, 0, 0L, new LcaWireStreamProtocol.EntryCleanAck(0));
        LcaWireStreamProtocol.InterServerChunkUpdatePayload interPayload =
                new LcaWireStreamProtocol.InterServerChunkUpdatePayload(
                        "node-a",
                        "minecraft:overworld",
                        0,
                        0,
                        10L,
                        List.of(deltaEntry)
                );
        byte[] rawInter = LcaWireStreamProtocol.encodeInterServerUpdatePayload(interPayload, false);
        assertEquals("node-a", LcaWireStreamProtocol.decodeInterServerUpdatePayload(rawInter, false).sourceServerId());
        byte[] compInter = LcaWireStreamProtocol.encodeInterServerUpdatePayload(interPayload, true);
        assertEquals("node-a", LcaWireStreamProtocol.decodeInterServerUpdatePayload(compInter, true).sourceServerId());

        // 3. LcaMultiTargetConsolidator low-bandwidth tick budget allocation & quantization edge cases
        assertThrows(IllegalArgumentException.class,
                () -> LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(350, 0, 0, new int[]{1}, new int[]{1, 2}));
        assertEquals(0, LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(350, 0, 0, new int[0], new int[0]).length);
        int[] idleBudgets = LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(
                350, 0, 0, new int[]{0, 0}, new int[]{0, 0});
        assertEquals(0, idleBudgets[0]);
        int[] zeroPool = LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(
                1, 100_000, 100, new int[]{2, 0}, new int[]{4, 2});
        assertEquals(0, zeroPool[0]);
        int[] mixedPool = LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(
                350, 100, 1, new int[]{2, 0, 0}, new int[]{5, 3, 0});
        assertTrue(mixedPool[0] > mixedPool[1]);
        assertEquals(0, mixedPool[2]);
        int[] equalPool = LcaMultiTargetConsolidator.allocateLowBandwidthTickBudgets(
                350, 100, 1, new int[]{0, 0}, new int[]{5, 5});
        assertEquals(equalPool[0], equalPool[1]);

        byte qDelta = LcaMultiTargetConsolidator.quantizeBlockDelta8(1.5);
        assertEquals(1.5, LcaMultiTargetConsolidator.dequantizeBlockDelta8(qDelta), 0.05);
        assertEquals(127, LcaMultiTargetConsolidator.quantizeBlockDelta8(99.0));
        assertEquals(-127, LcaMultiTargetConsolidator.quantizeBlockDelta8(-99.0));
        assertEquals((byte) 0, LcaMultiTargetConsolidator.quantizeAngleDegrees8(0.0f));
        assertTrue((LcaMultiTargetConsolidator.quantizeAngleDegrees8(-90.0f) & 0xFF) > 128);

        // 4. LeafAnvilConfig YAML loading with custom & boundary values
        Path cfgDir = Files.createDirectories(tempDir.resolve("custom_cfg"));
        Files.writeString(cfgDir.resolve("config.yml"), String.join("\n",
                "enabled: true",
                "compression:",
                "  mode: balanced",
                "  lcd2-level: 4",
                "  auto-migrate-legacy: true",
                "  keep-backups-on-convert: true",
                "memory:",
                "  enable-in-memory-sections: true",
                "  max-dirty-chunks: 256",
                "network:",
                "  enable-wire-stream: true",
                "  client-cache-budget-kib: 4096",
                "  uplink-budget-kbps: 500",
                "converter:",
                "  max-workers: 2",
                "  target-tps: 19.0",
                ""
        ));
        LeafAnvilConfig cfg = LeafAnvilConfig.loadOrCreate(cfgDir);
        assertNotNull(cfg);
        assertTrue(cfg.enabled());

        // 5. LeafAnvilWorldConverter convertWorldToLca with keepBackups=true and legacy priority (.mca + .linear)
        Path legacyWorld = Files.createDirectories(tempDir.resolve("legacy_world"));
        Path legacyRegion = Files.createDirectories(legacyWorld.resolve("region"));
        try (LcaRegionFileStorage st = new LcaRegionFileStorage(legacyRegion)) {
            st.writeChunkNbtBytes(0, 0, minimalChunkNbtBytes(0, 0));
            st.flush();
        }
        LeafAnvilWorldConverter.convertWorldToMca(legacyWorld, false);
        Files.deleteIfExists(legacyRegion.resolve("r.0.0.lca"));
        Files.write(legacyRegion.resolve("r.0.0.linear"), new byte[]{0, 1, 2});
        LeafAnvilWorldConverter.OperationReport toLca =
                LeafAnvilWorldConverter.convertWorldToLca(legacyWorld, true, true);
        assertNotNull(toLca);
        assertTrue(Files.exists(legacyRegion.resolve("r.0.0.lca")));
    }

    public static final class FromIdOnlyRegionFileVersion {
        private final int id;

        public FromIdOnlyRegionFileVersion(int id) {
            this.id = id;
        }

        public static FromIdOnlyRegionFileVersion fromId(int id) {
            return id == 3 ? new FromIdOnlyRegionFileVersion(3) : null;
        }
    }

    public static class FromIdRegionFile {
        public FromIdOnlyRegionFileVersion version = new FromIdOnlyRegionFileVersion(3);
    }

    public static final class FromIdRegionFileStorage {
        public final Path folder;
        public FakeRegionCache regionCache = new FakeRegionCache();

        public FromIdRegionFileStorage(Path folder) {
            this.folder = folder;
        }

        public FromIdRegionFile getRegionFile(FakeChunkPos pos) {
            return null;
        }
    }

    public static final class StaticFieldOnlyRegionFileVersion {
        public static final StaticFieldOnlyRegionFileVersion NONE_V3 = new StaticFieldOnlyRegionFileVersion(3);
        private final int id;

        public StaticFieldOnlyRegionFileVersion(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    public static class StaticFieldRegionFile {
        public StaticFieldOnlyRegionFileVersion version = StaticFieldOnlyRegionFileVersion.NONE_V3;
    }

    public static final class StaticFieldRegionFileStorage {
        public final Path folder;
        public FakeRegionCache regionCache = new FakeRegionCache();

        public StaticFieldRegionFileStorage(Path folder) {
            this.folder = folder;
        }

        public StaticFieldRegionFile getRegionFile(FakeChunkPos pos) {
            return null;
        }
    }

    @Test
    @DisplayName("Inter-server delta statuses, LeafAnvilConfig clamping, RegionFileVersion fallbacks, and wire protocol error guards")
    void interServerDeltaOutcomesConfigClampingVersionFallbacksAndWireErrors(@TempDir Path tempDir) throws Exception {
        // 1. Inter-server delta apply statuses
        LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                new LcaWireStreamProtocol.ClientChunkSlabCache(16), true);
        try {
            LcaWireStreamProtocol.SectionWireSlice uniformSlice = new LcaWireStreamProtocol.SectionWireSlice(
                    0, LcaWireStreamProtocol.encodeUniformDirectoryWord(1, 0), new byte[0], new int[0]);
            LcaWireStreamProtocol.EntryFullChunk validFull =
                    LcaWireStreamProtocol.EntryFullChunk.createWithComputedSyndrome(
                            0, 0, 10L, List.of(uniformSlice), new byte[0]);
            LcaWireStreamProtocol.EntryFullChunk badSyndromeFull = new LcaWireStreamProtocol.EntryFullChunk(
                    0, 0, 10L, validFull.secDedSyndrome64() ^ 1L, List.of(uniformSlice), new byte[0]);

            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.SYNDROME_MISMATCH,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(0, 0, 0L, badSyndromeFull),
                            4));
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.APPLIED,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(0, 0, 0L, validFull),
                            4));

            // Patch on untracked chunk -> NEEDS_FULL_CHUNK_REFILL
            LcaWireStreamProtocol.SectionWireSlice dirtySlice = new LcaWireStreamProtocol.SectionWireSlice(
                    0, LcaWireStreamProtocol.encodeUniformDirectoryWord(1, 0), new byte[0],
                    new int[]{LcaSectionBacking.packEntry32(5, 9)});
            LcaWireStreamProtocol.EntryPatchDelta patch = new LcaWireStreamProtocol.EntryPatchDelta(
                    0, 20L, 0x9999L, 1, List.of(dirtySlice));
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(5, 5, 0L, patch),
                            4));

            // Patch with mismatched baseline -> NEEDS_FULL_CHUNK_REFILL
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                                    0, 0, validFull.secDedSyndrome64() ^ 0xFFL, patch),
                            4));

            // Patch with out-of-range section index -> NEEDS_FULL_CHUNK_REFILL
            LcaWireStreamProtocol.SectionWireSlice oobSlice = new LcaWireStreamProtocol.SectionWireSlice(
                    15, LcaWireStreamProtocol.encodeUniformDirectoryWord(1, 0), new byte[0], new int[0]);
            LcaWireStreamProtocol.EntryPatchDelta oobPatch = new LcaWireStreamProtocol.EntryPatchDelta(
                    0, 20L, 0x9999L, 1, List.of(oobSlice));
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                                    0, 0, validFull.secDedSyndrome64(), oobPatch),
                            4));

            // Patch with wrong post-patch syndrome -> SYNDROME_MISMATCH (and queues commit)
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.SYNDROME_MISMATCH,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                                    0, 0, validFull.secDedSyndrome64(), patch),
                            4));
            AtomicInteger drained = new AtomicInteger();
            assertEquals(1, coord.drainPendingInterServerBlockCommits(10, c -> drained.incrementAndGet()));
            assertEquals(1, drained.get());

            // Clean ack -> APPLIED
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.APPLIED,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                                    0, 0, 0L, new LcaWireStreamProtocol.EntryCleanAck(0)),
                            4));
        } finally {
            coord.shutdownCompactionPool();
        }

        // 2. LeafAnvilConfig clamping & worker thread resolution
        LeafAnvilConfig clamped = new LeafAnvilConfig(
                true, true, true, 99, 0.35, 4671, -5, false,
                0, Double.NaN, Double.POSITIVE_INFINITY, -10L, null, null, null);
        assertEquals(22, clamped.lcd2Level());
        assertEquals(0, clamped.maxServerUploadKbps());
        assertNotNull(clamped.msgBusy());
        assertNotNull(clamped.msgInvalidCommand());
        assertNotNull(clamped.msgNoPermission());
        LeafAnvilConfig disabledLcd = new LeafAnvilConfig(
                false, false, false, -1, 0.35, 4671, 500, false,
                null, null, null);
        assertEquals(0, disabledLcd.lcd2Level());
        assertTrue(disabledLcd.coalesceTickFlushes());
        assertEquals(2, LeafAnvilConfig.resolveConvertWorkerThreads(2, 8, false));
        assertEquals(2, LeafAnvilConfig.resolveConvertWorkerThreads(0, 12, true));
        assertEquals(3, LeafAnvilConfig.resolveConvertWorkerThreads(0, 12, false));
        assertTrue(LeafAnvilConfig.resolveConvertWorkerThreads(0, false) >= 1);

        // 3. NmsRegionStorageInterceptor RegionFileVersion fallback resolution (fromId + static field)
        Path vDir = Files.createDirectories(tempDir.resolve("ver_fallbacks"));
        try (LcaRegionFileStorage lca = new LcaRegionFileStorage(vDir)) {
            FromIdRegionFileStorage s1 = new FromIdRegionFileStorage(vDir);
            assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(s1, lca));
            assertNotNull(s1.regionCache.getAndMoveToFirst(0L));

            StaticFieldRegionFileStorage s2 = new StaticFieldRegionFileStorage(vDir);
            assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(s2, lca));
            assertNotNull(s2.regionCache.getAndMoveToFirst(0L));
        }

        // 4. Wire protocol error guards + LcaRegionDictSyncPayload + ServerboundLcaChunkDigestPayload
        assertThrows(IllegalArgumentException.class, () -> LcaWireStreamProtocol.HandshakePayload.fromBytes(new byte[4]));
        assertThrows(IOException.class,
                () -> LcaWireStreamProtocol.decodeInterServerUpdatePayload(new byte[]{0, 0, 0, 0}, false));
        LcaWireStreamProtocol.NegotiatedSession activeSession = new LcaWireStreamProtocol.NegotiatedSession(
                true, LcaWireStreamProtocol.ALL_CAPABILITIES, 64, 1L);
        assertThrows(IOException.class,
                () -> LcaWireStreamProtocol.decodeChunkBatchPayload(new byte[]{0, 0, 0, 0}, activeSession, null));

        // 5. Kinematic table with rotation=false and rotation=true
        byte[] kinBytes = LcaMultiTargetConsolidator.packKinematicDeltaTable(List.of(
                new LcaMultiTargetConsolidator.KinematicDeltaRow(1, (byte) 1, (byte) 2, (byte) 3, false, (byte) 0, (byte) 0),
                new LcaMultiTargetConsolidator.KinematicDeltaRow(2, (byte) -1, (byte) 0, (byte) 4, true, (byte) 10, (byte) 20)
        ));
        assertTrue(kinBytes.length > 0);
    }

    @Test
    @DisplayName("Pulsed TaskScheduler command flows, FakeCraftWorld runtime attach, EntryPatchDelta guards, and MultiTargetTcpFanOutRelay")
    void pulsedSchedulerCommandsWirePayloadsAndRelayCoverage(@TempDir Path tempDir) throws Exception {
        // 1. Mock TaskScheduler that executes runAsync inline and captures async timers for deterministic ticking
        Path worldDir = Files.createDirectories(tempDir.resolve("pulsed_world"));
        Path regionDir = Files.createDirectories(worldDir.resolve("region"));
        try (LcaRegionFileStorage st = new LcaRegionFileStorage(regionDir)) {
            st.writeChunkNbtBytes(0, 0, minimalChunkNbtBytes(0, 0));
            st.flush();
        }
        // Corrupted .lca triggers PacedRegionSession#recordFailure during heal
        Files.write(regionDir.resolve("r.4.4.lca"), new byte[]{1, 2, 3, 4});

        FakeRegionFileStorage fakeStorage = new FakeRegionFileStorage(regionDir);
        FakeCraftWorld fakeWorld = new FakeCraftWorld(new FakeServerLevel(new FakeChunkMap(fakeStorage)));

        List<Runnable> asyncPulses = new java.util.concurrent.CopyOnWriteArrayList<>();
        io.github.dailystruggle.server.api.TaskScheduler mockSched =
                Mockito.mock(io.github.dailystruggle.server.api.TaskScheduler.class);
        Mockito.when(mockSched.runSyncTimer(Mockito.any(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(Mockito.mock(io.github.dailystruggle.server.api.TaskHandle.class));
        Mockito.when(mockSched.runAsync(Mockito.any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return Mockito.mock(io.github.dailystruggle.server.api.TaskHandle.class);
        });
        Mockito.when(mockSched.runAsyncTimer(Mockito.any(), Mockito.anyLong(), Mockito.anyLong())).thenAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            asyncPulses.add(r);
            return Mockito.mock(io.github.dailystruggle.server.api.TaskHandle.class);
        });

        Path dataDir = Files.createDirectories(tempDir.resolve("pulsed_data"));
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.of(mockSched));
        Mockito.when(accessor.isPrimaryThread()).thenReturn(false);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            // Non-null world handle exercises invokeResolveDimensionDirectory & invokeInterceptWorldStorages
            runtime.attachAndInterceptWorld("pulsed_world", worldDir, -64, 320, fakeWorld);

            net.leaf.anvil.common.command.LeafAnvilCommand cmd =
                    new net.leaf.anvil.common.command.LeafAnvilCommand("leafanvil", accessor, runtime);
            UUID caller = UUID.randomUUID();

            // Run pulsed heal subcommand and drive pulses to completion
            io.github.dailystruggle.commandsapi.common.CommandsAPICommand healSub = null;
            io.github.dailystruggle.commandsapi.common.CommandsAPICommand convertSub = null;
            for (io.github.dailystruggle.commandsapi.common.CommandsAPICommand sub : cmd.getCommandLookup().values()) {
                if ("heal".equalsIgnoreCase(sub.name())) {
                    healSub = sub;
                } else if ("convert".equalsIgnoreCase(sub.name())) {
                    convertSub = sub;
                }
            }
            assertNotNull(healSub);
            assertNotNull(convertSub);

            healSub.onCommand(caller, Map.of("world", List.of("pulsed_world"), "workers", List.of("2")), null);
            for (int tick = 0; tick < 25; tick++) {
                for (Runnable p : asyncPulses) {
                    p.run();
                }
                Thread.sleep(5L);
            }
            asyncPulses.clear();

            // Run pulsed convert to mca and drive pulses to completion
            convertSub.onCommand(caller, Map.of(
                    "world", List.of("pulsed_world"),
                    "format", List.of("mca"),
                    "workers", List.of("2")), null);
            for (int tick = 0; tick < 25; tick++) {
                for (Runnable p : asyncPulses) {
                    p.run();
                }
                Thread.sleep(5L);
            }
            // Also test invalid format branch on convertSub
            convertSub.onCommand(caller, Map.of(
                    "world", List.of("pulsed_world"),
                    "format", List.of("bogus")), null);

            // Runtime batch busy guard
            assertTrue(runtime.runBatchTaskAsync(caller, () -> {
            }));
        }

        // 2. EntryPatchDelta constructor guards + LcaRegionDictSyncPayload + ServerboundLcaChunkDigestPayload
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.EntryPatchDelta(-1, 1L, 0L, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.EntryPatchDelta(1024, 1L, 0L, 0, List.of()));
        LcaWireStreamProtocol.SectionWireSlice s0 = new LcaWireStreamProtocol.SectionWireSlice(
                0, LcaWireStreamProtocol.encodeUniformDirectoryWord(1, 0), new byte[0], new int[0]);
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.EntryPatchDelta(0, 1L, 0L, 0b11, List.of(s0)));

        LcaWireStreamProtocol.LcaRegionDictSyncPayload dictSync =
                new LcaWireStreamProtocol.LcaRegionDictSyncPayload(1, -2, new int[]{10, 20}, new int[]{30, 40});
        byte[] dictSyncBytes = dictSync.toBytes();
        assertEquals(dictSync, LcaWireStreamProtocol.LcaRegionDictSyncPayload.fromBytes(dictSyncBytes));
        assertThrows(java.io.UncheckedIOException.class,
                () -> LcaWireStreamProtocol.LcaRegionDictSyncPayload.fromBytes(new byte[]{1, 2}));

        LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload digest =
                new LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload(
                        "minecraft:overworld",
                        0,
                        0,
                        List.of(new LcaWireStreamProtocol.ChunkDigestEntry(1, 100L, 0x1111L)));
        byte[] digestBytes = digest.toBytes();
        assertEquals(1, LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload.fromBytes(digestBytes).entries().size());
        assertThrows(java.io.UncheckedIOException.class,
                () -> LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload.fromBytes(new byte[]{1, 2}));

        // 3. MultiTargetTcpFanOutRelay ephemeral port broadcast & invalid frame guard
        try (LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay relay =
                     new LcaMultiTargetConsolidator.MultiTargetTcpFanOutRelay(0)) {
            LcaOrderlessDictTable dict = new LcaOrderlessDictTable();
            LcaMultiTargetConsolidator.ConsolidatedMultiTargetFrame frame =
                    LcaMultiTargetConsolidator.consolidateMultiTargetTickFrame(
                            List.of(
                                    new LcaMultiTargetConsolidator.TargetRecipient(
                                            0, UUID.randomUUID(), new LcaOrderlessDictTable.KnownSlotBitSet()),
                                    new LcaMultiTargetConsolidator.TargetRecipient(
                                            1, UUID.randomUUID(), new LcaOrderlessDictTable.KnownSlotBitSet())),
                            dict,
                            LcaOrderlessDictTable.KIND_SECTION_SLICE_OR_PALETTE,
                            List.of(),
                            List.of(),
                            new byte[0],
                            1);
            relay.broadcastUpstreamMtcfFrame(frame.sharedWireFrameBytes());
            assertThrows(IOException.class, () -> relay.broadcastUpstreamMtcfFrame(new byte[]{0, 0, 0, 0}));
        }

        // 4. Coordinator unload / clearWorld / tombstone fence
        LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                new LcaWireStreamProtocol.ClientChunkSlabCache(16), true);
        try {
            LcaSectionBacking[] secs = new LcaSectionBacking[]{new LcaSectionBacking(new byte[0], 0)};
            coord.attachChunkColumn("minecraft:overworld", 3, 4, secs, null, 1L, false);
            assertNotNull(coord.getTrackedChunk("minecraft:overworld", 3, 4));
            coord.markChunkUnloaded("minecraft:overworld", 3, 4);
            assertNull(coord.getTrackedChunk("minecraft:overworld", 3, 4));
            // Unloaded tombstone fence rejects subsequent inter-server delta
            assertEquals(
                    LcaWireStreamProtocol.InterServerApplyStatus.SKIPPED_UNLOADED_FENCE,
                    coord.applyInterServerChunkDeltaEntry(
                            "minecraft:overworld",
                            new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                                    3, 4, 0L, new LcaWireStreamProtocol.EntryCleanAck(0)),
                            4));
            coord.clearWorld("minecraft:overworld");
        } finally {
            coord.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("LeafAnvilConfig YAML parser edge cases, LcaPacketDifferentialProfiler guards, BaseSub command hooks, and wire validation")
    void configYamlEdgeCasesProfilerGuardsSubcommandHooksAndWireValidation(@TempDir Path tempDir) throws Exception {
        // 1. LeafAnvilConfig YAML edge cases: line without colon, escaped quotes, single quotes, invalid numbers
        Path cfgDir = Files.createDirectories(tempDir.resolve("yaml_edges"));
        Files.writeString(cfgDir.resolve("config.yml"), String.join("\n",
                "malformed_line_without_colon",
                "enabled: 'true' # inline comment",
                "lcd2-level: not_an_int",
                "compact-fragmentation-threshold: not_a_double",
                "convert-max-overtime-ms: not_a_long",
                ""
        ));
        Files.writeString(cfgDir.resolve("messages.yml"), String.join("\n",
                "busy: \"Busy \\\"quoted\\\" message\"",
                "invalid-command: 'Bad %arg%'",
                ""
        ));
        LeafAnvilConfig parsed = LeafAnvilConfig.loadOrCreate(cfgDir);
        assertTrue(parsed.enabled());
        assertEquals(1, parsed.lcd2Level());
        assertEquals(0.35, parsed.compactFragmentationThreshold(), 0.001);
        assertEquals(LeafAnvilConfig.DEFAULT_CONVERT_MAX_OVERTIME_MS, parsed.convertMaxOvertimeMs());

        // 2. LcaPacketDifferentialProfiler constructor & static utility guards
        assertThrows(IllegalArgumentException.class,
                () -> new net.leaf.anvil.common.net.LcaPacketDifferentialProfiler(-1, 1400));
        assertThrows(IllegalArgumentException.class,
                () -> new net.leaf.anvil.common.net.LcaPacketDifferentialProfiler(40, 0));
        net.leaf.anvil.common.net.LcaPacketDifferentialProfiler prof =
                new net.leaf.anvil.common.net.LcaPacketDifferentialProfiler();
        assertTrue(prof.headerBytesPerSegment() > 0);
        assertTrue(prof.mtuPayloadBytes() > 0);
        assertEquals(0, net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.computeSegmentsForFlush(0, 1400));
        assertEquals(Integer.MAX_VALUE,
                net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.computeTickByteBudgetForKbps(0));
        assertEquals(Integer.MAX_VALUE,
                net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.computeRemainingTickChunkBudgetBytes(0, 10, 1, 40));
        assertEquals(net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.BUCKET_TRANSIENT_FX_UI,
                net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.classifyPacketName(null));
        assertEquals(net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.BUCKET_TRANSIENT_FX_UI,
                net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.classifyPacketName(""));

        // 3. LeafAnvilCommand BaseSub forwarding hooks
        Path cmdData = Files.createDirectories(tempDir.resolve("sub_hooks_data"));
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(cmdData);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());
        Mockito.when(accessor.isPrimaryThread()).thenReturn(false);
        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            net.leaf.anvil.common.command.LeafAnvilCommand cmd =
                    new net.leaf.anvil.common.command.LeafAnvilCommand("leafanvil", accessor, runtime);
            io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand statusSub =
                    (io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand)
                            cmd.getCommandLookup().values().iterator().next();
            assertNotNull(statusSub.getCommandLookup());
            UUID caller = UUID.randomUUID();
            statusSub.msgNoPermission(caller, "leafanvil.admin");
            statusSub.msgInvalidCommand(caller, "bad");
            statusSub.msgBadParameter(caller, "workers", "bad");
        }

        // 4. LcaWireStreamProtocol record & helper validation guards
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.EntryCleanAck(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.EntryCleanAck(1024));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.SectionWireSlice(-1, (short) 0, new byte[0], new int[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.SectionWireSlice(32, (short) 0, new byte[0], new int[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.ChunkDigestEntry(-1, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.ChunkDigestEntry(1024, 0L, 0L));
        assertEquals(-1, new LcaWireStreamProtocol.HandshakePayload(0, 0, -1, 0L).clientCacheBudgetKiB());
        assertThrows(IllegalArgumentException.class,
                () -> new LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(0, 0, List.of()));

        // 5. LcaClientWireReceiver handshake bad-magic catch & proxy-safe zlib batch error path
        LcaClientWireReceiver rx = new LcaClientWireReceiver();
        rx.recordClientChunkLoadSample(null, 0);
        rx.recordClientChunkLoadSample(new LcaSectionBacking[]{null}, 0);
        byte[] badMagicHs = new byte[24];
        assertFalse(rx.onServerHandshakeAck(badMagicHs, 0x1234L).isLcaStreamActive());
        assertEquals(0, rx.onChunkBatchPayloadReceived("minecraft:overworld", new byte[]{1, 2, 3}, true, 0x1234L, null));
        assertNull(rx.onMultiTargetTickFrameReceived("minecraft:overworld", 0, new byte[]{1, 2, 3, 4}, true, 0x1234L, null, null));

        // 6. LcaPacketDifferentialProfiler remaining accessors & empty snapshot edge cases
        assertEquals(0L, prof.currentTickNonChunkWireBytes());
        assertEquals(0L, prof.currentTickNonChunkSegments());
        assertEquals(0L, prof.sampledTicks());
        assertEquals("UNKNOWN_99", net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.bucketName(99));
        net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.DifferentialSnapshot emptySnap = prof.snapshot(0);
        assertNotNull(emptySnap.bucket(0));
        assertThrows(IllegalArgumentException.class, () -> emptySnap.bucket(-1));
        assertEquals(0.0, emptySnap.vanillaTotalKbps(), 0.001);
        assertEquals(0.0, emptySnap.optimizedTotalKbps(), 0.001);
        assertNotNull(emptySnap.toJson());

        // 7. LeafAnvilAgentBootstrap Number fast-path & null-state accessors
        assertEquals(42, LeafAnvilAgentBootstrap.resolveBlockStateId(Integer.valueOf(42)));
        assertEquals(0, LeafAnvilAgentBootstrap.getBaseFrame(null).length);
        assertEquals(0, LeafAnvilAgentBootstrap.getUniformStateId(null));

        // 8. LcaWireStreamProtocol non-orderless batch encode/decode (full + patch + ack with overlays & heightmaps)
        LcaWireStreamProtocol.NegotiatedSession plainSession = new LcaWireStreamProtocol.NegotiatedSession(
                true, LcaWireStreamProtocol.ALL_CAPABILITIES, 64, 0x1234L);
        byte[] nonEmptyBody = new byte[16];
        Arrays.fill(nonEmptyBody, (byte) 0x33);
        LcaWireStreamProtocol.SectionWireSlice plainSlice = new LcaWireStreamProtocol.SectionWireSlice(
                0, (short) nonEmptyBody.length, nonEmptyBody, new int[]{LcaSectionBacking.packEntry32(3, 7)});
        LcaWireStreamProtocol.EntryFullChunk plainFull = new LcaWireStreamProtocol.EntryFullChunk(
                0, 0, 50L, 0xABCDL, List.of(plainSlice), new byte[]{1, 2, 3, 4});
        LcaWireStreamProtocol.EntryPatchDelta plainPatch = new LcaWireStreamProtocol.EntryPatchDelta(
                1, 51L, 0xDCBAL, 1, List.of(plainSlice));
        LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload plainBatch =
                new LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(
                        0, 0, List.of(plainFull, plainPatch, new LcaWireStreamProtocol.EntryCleanAck(2)));
        byte[] plainWire = LcaWireStreamProtocol.encodeChunkBatchPayload(plainBatch, plainSession);
        assertEquals(3, LcaWireStreamProtocol.decodeChunkBatchPayload(plainWire, plainSession).entries().size());
    }
}
