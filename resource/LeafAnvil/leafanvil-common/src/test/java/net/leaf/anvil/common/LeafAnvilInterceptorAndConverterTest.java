package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.AnvilReader;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LcaRegionWriter;
import io.github.dailystruggle.anvil.Nbt;
import io.github.dailystruggle.server.api.ServerAccessor;
import net.leaf.anvil.common.LeafAnvilWorldConverter.OperationReport;
import net.leaf.anvil.common.command.LeafAnvilCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end unit tests for {@link NmsRegionStorageInterceptor}, {@link LeafAnvilRuntime},
 * {@link LeafAnvilWorldConverter}, and {@link LeafAnvilCommand} (`leafanvil-ADR-001`, `anvil-api-ADR-002`,
 * `anvil-api-ADR-003`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `REQ-RTP-S-007`).
 */
@DisplayName("anvil-api-ADR-003: LeafAnvil Runtime Bytecode Interceptor, World Converter & Commands")
public class LeafAnvilInterceptorAndConverterTest {

    public static final class SyntheticChunkPos {
        public final int x;
        public final int z;

        public SyntheticChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public int getRegionX() {
            return x >> 5;
        }

        public int getRegionZ() {
            return z >> 5;
        }

        public static long pack(int rx, int rz) {
            return ((long) rx & 0xFFFF_FFFFL) | (((long) rz & 0xFFFF_FFFFL) << 32);
        }
    }

    public static class SyntheticRegionFile implements AutoCloseable {
        private final Path path;

        public SyntheticRegionFile(Path path) {
            this.path = path;
        }

        public Path getPath() {
            return path;
        }

        public DataInputStream getChunkDataInputStream(SyntheticChunkPos pos) throws IOException {
            throw new UnsupportedOperationException("Vanilla .mca RegionFile should be intercepted by LeafAnvil");
        }

        public DataOutputStream getChunkDataOutputStream(SyntheticChunkPos pos) throws IOException {
            throw new UnsupportedOperationException("Vanilla .mca RegionFile should be intercepted by LeafAnvil");
        }

        public boolean doesChunkExist(SyntheticChunkPos pos) {
            return false;
        }

        public boolean hasChunk(SyntheticChunkPos pos) {
            return false;
        }

        public void clear(SyntheticChunkPos pos) throws IOException {
        }

        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
    }

    public static class SyntheticLong2ObjectLinkedOpenHashMap {
        private final LinkedHashMap<Long, Object> map = new LinkedHashMap<>();

        public Object getAndMoveToFirst(long key) {
            return map.get(key);
        }

        public Object get(long key) {
            return map.get(key);
        }

        public Object putAndMoveToFirst(long key, Object val) {
            return map.put(key, val);
        }

        public Object put(long key, Object val) {
            return map.put(key, val);
        }

        public Collection<Object> values() {
            return map.values();
        }

        public void clear() {
            map.clear();
        }
    }

    public static final class SyntheticRegionFileStorage implements AutoCloseable {
        private final SyntheticLong2ObjectLinkedOpenHashMap regionCache = new SyntheticLong2ObjectLinkedOpenHashMap();
        private final Path folder;

        public SyntheticRegionFileStorage(Path folder) {
            this.folder = folder;
        }

        private SyntheticRegionFile getRegionFile(SyntheticChunkPos pos) {
            long key = SyntheticChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
            SyntheticRegionFile rf = (SyntheticRegionFile) this.regionCache.getAndMoveToFirst(key);
            if (rf != null) {
                return rf;
            }
            Path mca = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            rf = new SyntheticRegionFile(mca);
            this.regionCache.putAndMoveToFirst(key, rf);
            return rf;
        }

        public LinkedHashMap<String, Object> read(SyntheticChunkPos pos) throws IOException {
            SyntheticRegionFile rf = getRegionFile(pos);
            try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                if (in == null) {
                    return null;
                }
                return Nbt.readRootCompound(in.readAllBytes());
            }
        }

        public void write(SyntheticChunkPos pos, Map<String, Object> tag) throws IOException {
            SyntheticRegionFile rf = getRegionFile(pos);
            if (tag == null) {
                rf.clear(pos);
                return;
            }
            try (DataOutputStream out = rf.getChunkDataOutputStream(pos)) {
                out.write(Nbt.writeNamedRoot("", tag));
            }
        }

        public boolean doesChunkExist(SyntheticChunkPos pos) {
            return getRegionFile(pos).doesChunkExist(pos);
        }

        public void flush() throws IOException {
            for (Object val : this.regionCache.values()) {
                ((SyntheticRegionFile) val).flush();
            }
        }

        @Override
        public void close() throws IOException {
            for (Object val : this.regionCache.values()) {
                ((SyntheticRegionFile) val).close();
            }
        }
    }

    static final class SyntheticIOWorker {
        private final SyntheticRegionFileStorage storage;

        SyntheticIOWorker(Path folder) {
            this.storage = new SyntheticRegionFileStorage(folder);
        }
    }

    static final class SyntheticSimpleRegionStorage {
        private final SyntheticIOWorker worker;

        SyntheticSimpleRegionStorage(Path folder) {
            this.worker = new SyntheticIOWorker(folder);
        }
    }

    static final class SyntheticPoiManager {
        private final SyntheticSimpleRegionStorage simpleRegionStorage;

        SyntheticPoiManager(Path dimDir) {
            this.simpleRegionStorage = new SyntheticSimpleRegionStorage(dimDir.resolve("poi"));
        }
    }

    static final class SyntheticChunkMap {
        private final SyntheticIOWorker worker;
        private final SyntheticPoiManager poiManager;

        SyntheticChunkMap(Path dimDir) {
            this.worker = new SyntheticIOWorker(dimDir.resolve("region"));
            this.poiManager = new SyntheticPoiManager(dimDir);
        }
    }

    static final class SyntheticServerChunkCache {
        private final SyntheticChunkMap chunkMap;

        SyntheticServerChunkCache(Path dimDir) {
            this.chunkMap = new SyntheticChunkMap(dimDir);
        }
    }

    static final class SyntheticEntityStorage {
        private final SyntheticSimpleRegionStorage simpleRegionStorage;

        SyntheticEntityStorage(Path dimDir) {
            this.simpleRegionStorage = new SyntheticSimpleRegionStorage(dimDir.resolve("entities"));
        }
    }

    static final class SyntheticPersistentEntitySectionManager {
        private final SyntheticEntityStorage permanentStorage;

        SyntheticPersistentEntitySectionManager(Path dimDir) {
            this.permanentStorage = new SyntheticEntityStorage(dimDir);
        }
    }

    static final class SyntheticServerLevel {
        private final SyntheticServerChunkCache chunkSource;
        private final SyntheticPersistentEntitySectionManager entityManager;

        SyntheticServerLevel(Path dimDir) {
            this.chunkSource = new SyntheticServerChunkCache(dimDir);
            this.entityManager = new SyntheticPersistentEntitySectionManager(dimDir);
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: NmsRegionStorageInterceptor intercepts region/, entities/, and poi/ RegionFileStorage instances at runtime")
    void runtimeBytecodeInterceptionAndWorldConverter(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("plugin-data");
        Path worldDir = tempDir.resolve("world");
        Files.createDirectories(worldDir.resolve("region"));
        Files.createDirectories(worldDir.resolve("entities"));
        Files.createDirectories(worldDir.resolve("poi"));

        // Seed a legacy .mca file in region/r.-1.0.mca before attaching the world to test lazy migration
        Map<String, Object> legacyChunk = buildVoxelChunk(-32, 0, "minecraft:Granite");
        LcaRegionWriter.LcaLiveArchive tempLca = LcaRegionWriter.createEmptyLiveArchive(-1, 0, -4, 24, 4671, false);
        tempLca.saveChunkCoW(0, 0, legacyChunk);
        byte[] mcaBytes = LcaRegionWriter.transcodeToMca(tempLca.toByteArray());
        Path legacyMcaPath = worldDir.resolve("region").resolve("r.-1.0.mca");
        Files.write(legacyMcaPath, mcaBytes);

        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());
        List<String> sentMessages = new ArrayList<>();
        Mockito.doAnswer(inv -> {
            sentMessages.add(inv.getArgument(1));
            return null;
        }).when(accessor).sendMessage(Mockito.any(), Mockito.anyString(), Mockito.anyBoolean());

        SyntheticServerLevel serverLevel = new SyntheticServerLevel(worldDir);
        SyntheticRegionFileStorage voxelRfs = serverLevel.chunkSource.chunkMap.worker.storage;
        SyntheticRegionFileStorage entityRfs = serverLevel.entityManager.permanentStorage.simpleRegionStorage.worker.storage;
        SyntheticRegionFileStorage poiRfs = serverLevel.chunkSource.chunkMap.poiManager.simpleRegionStorage.worker.storage;

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            assertTrue(runtime.config().modeBLcd2(),
                    "LeafAnvilConfig must default to Mode B LCD2 (mode-b-lcd2: true)");
            assertEquals(1, runtime.config().lcd2Level(),
                    "LeafAnvilConfig must default to lcd2-level: 1");
            String defaultCfgText = Files.readString(dataDir.resolve("config.yml"));
            assertTrue(defaultCfgText.contains("lcd2-level: 1"),
                    "Generated default config.yml must contain lcd2-level: 1");
            Files.writeString(dataDir.resolve("config.yml"),
                    "enabled: true\nauto-migrate-legacy: true\nbackup-migrated-legacy-files: true\nlcd2-level: 6\n");
            LeafAnvilConfig cfg6 = LeafAnvilConfig.loadOrCreate(dataDir);
            assertEquals(6, cfg6.lcd2Level());
            assertTrue(cfg6.modeBLcd2());
            Files.writeString(dataDir.resolve("config.yml"),
                    "enabled: true\nauto-migrate-legacy: true\nbackup-migrated-legacy-files: true\nmode-b-lcd2: false\n");
            LeafAnvilConfig cfgModeBLcd2False = LeafAnvilConfig.loadOrCreate(dataDir);
            assertEquals(0, cfgModeBLcd2False.lcd2Level());
            assertFalse(cfgModeBLcd2False.modeBLcd2());
            Files.writeString(dataDir.resolve("config.yml"),
                    "enabled: true\nauto-migrate-legacy: true\nbackup-migrated-legacy-files: true\nlcd2-level: 0\n");
            LeafAnvilConfig cfg0 = LeafAnvilConfig.loadOrCreate(dataDir);
            assertEquals(0, cfg0.lcd2Level());
            assertFalse(cfg0.modeBLcd2());
            Files.writeString(dataDir.resolve("config.yml"),
                    "enabled: true\nauto-migrate-legacy: true\nbackup-migrated-legacy-files: true\nlcd2-level: 1\n");
            runtime.load();
            Path extractedIcd = dataDir.resolve("ICD.md");
            assertTrue(Files.isRegularFile(extractedIcd),
                    "Bundled .lca ICD.md must be extracted to the LeafAnvil data directory on startup");
            String icdText = Files.readString(extractedIcd);
            assertTrue(icdText.contains("LCA-ICD-1.0")
                            && icdText.contains("STATIC_CORE_STATES")
                            && icdText.contains("HAS_CHUNK_PATCH_TAIL"),
                    "Extracted ICD.md must contain the canonical .lca specification, ChunkPatchTail layout, and static lookup tables");
            assertNotNull(LeafAnvilConfig.class.getClassLoader().getResource("META-INF/leafanvil/ICD.md"),
                    "ICD.md must also be packaged at META-INF/leafanvil/ICD.md on the classpath");
            Path repoRouterIcd = Path.of("..", "docs", "ICD.md");
            if (Files.isRegularFile(repoRouterIcd)) {
                List<String> routerLines = Files.readAllLines(repoRouterIcd);
                assertTrue(routerLines.size() <= 60
                                && String.join("\n", routerLines).contains("api/anvil-api/docs/ICD.md"),
                        "resource/LeafAnvil/docs/ICD.md must remain a thin router to api/anvil-api/docs/ICD.md (single source of truth)");
            }
            LcaRegionFileStorage.LcaWorldStorageSession session =
                    runtime.attachAndInterceptWorld("world", worldDir, -4, 24, serverLevel);
            assertNotNull(session);
            assertEquals(3, runtime.bitSuite().interceptedCountForWorld("world"),
                    "All 3 RegionFileStorage instances (region/, entities/, poi/) must be recorded in BIT");
            assertEquals(io.github.dailystruggle.bit.BitHealth.HEALTHY, runtime.bitSuite().getHealthStatus(),
                    "BIT health must be HEALTHY when all RegionFileStorage instances are intercepted");

            // 1. Write a new chunk (3, 7) through the intercepted voxel RegionFileStorage
            SyntheticChunkPos pos = new SyntheticChunkPos(3, 7);
            assertFalse(voxelRfs.doesChunkExist(pos));
            voxelRfs.write(pos, buildVoxelChunk(3, 7, "minecraft:stone"));
            assertTrue(voxelRfs.doesChunkExist(pos));
            assertTrue(Files.exists(worldDir.resolve("region").resolve("r.0.0.lca")),
                    "Intercepted write must persist to r.0.0.lca");
            assertFalse(Files.exists(worldDir.resolve("region").resolve("r.0.0.mca")),
                    "Intercepted write must never create vanilla r.0.0.mca");

            LinkedHashMap<String, Object> loadedVoxel = voxelRfs.read(pos);
            assertNotNull(loadedVoxel);
            assertEquals(3, ((Number) loadedVoxel.get("xPos")).intValue());
            assertEquals(7, ((Number) loadedVoxel.get("zPos")).intValue());

            // 2. Write & read entity compound through the intercepted entities/ RegionFileStorage
            LinkedHashMap<String, Object> entityRoot = new LinkedHashMap<>();
            entityRoot.put("DataVersion", 4671);
            entityRoot.put("Position", new int[] {3, 7});
            LinkedHashMap<String, Object> villager = new LinkedHashMap<>();
            villager.put("id", "minecraft:villager");
            entityRoot.put("Entities", List.of(villager));
            entityRfs.write(pos, entityRoot);
            assertTrue(Files.exists(worldDir.resolve("entities").resolve("r.0.0.lca")));

            LinkedHashMap<String, Object> loadedEntities = entityRfs.read(pos);
            assertNotNull(loadedEntities);
            Nbt.NbtList entitiesList = (Nbt.NbtList) loadedEntities.get("Entities");
            @SuppressWarnings("unchecked")
            Map<String, Object> firstEntity = (Map<String, Object>) entitiesList.items.get(0);
            assertEquals("minecraft:villager", firstEntity.get("id"));

            // 3. Write & read POI compound through the intercepted poi/ RegionFileStorage
            LinkedHashMap<String, Object> poiRoot = new LinkedHashMap<>();
            poiRoot.put("DataVersion", 4671);
            LinkedHashMap<String, Object> poiSections = new LinkedHashMap<>();
            poiSections.put("4", Map.of("Valid", (byte) 1));
            poiRoot.put("Sections", poiSections);
            poiRfs.write(pos, poiRoot);
            assertTrue(Files.exists(worldDir.resolve("poi").resolve("r.0.0.lca")));
            assertNotNull(poiRfs.read(pos));

            // 4. Read from legacy region (-1, 0) via intercepted voxelRfs -> triggers lazy .mca -> .lca migration
            SyntheticChunkPos legacyPos = new SyntheticChunkPos(-32, 0);
            assertTrue(voxelRfs.doesChunkExist(legacyPos));
            LinkedHashMap<String, Object> migratedRead = voxelRfs.read(legacyPos);
            assertNotNull(migratedRead);
            assertTrue(Files.exists(worldDir.resolve("region").resolve("r.-1.0.lca")));
            assertTrue(Files.exists(worldDir.resolve("region").resolve("r.-1.0.mca.bak")));

            // 5. Execute /leafanvil status, bit, compact, heal, and reload commands
            LeafAnvilCommand cmd = new LeafAnvilCommand("leafanvil", accessor, runtime);
            UUID caller = UUID.randomUUID();
            assertTrue(cmd.onCommand(caller, Map.of(), null));
            assertTrue(cmd.getCommandLookup().get("BIT").onCommand(caller, Map.of(), null));
            assertTrue(cmd.getCommandLookup().get("COMPACT").onCommand(caller, Map.of("world", List.of("world")), null));
            assertTrue(cmd.getCommandLookup().get("HEAL").onCommand(caller, Map.of("world", List.of("world")), null));
            assertTrue(cmd.getCommandLookup().get("RELOAD").onCommand(caller, Map.of(), null));
            assertFalse(sentMessages.isEmpty());
            assertTrue(sentMessages.stream().noneMatch(m -> m.contains("interceptedStorages=")),
                    "/leafanvil status must not emit non-BIT interceptedStorages telemetry");
            assertTrue(sentMessages.stream().anyMatch(m -> m.contains("[LCA bit] Health=HEALTHY")),
                    "/leafanvil bit must report [LCA bit] Health=HEALTHY");

            // 5b. Live session-coordinated conversion (.lca -> .mca and .mca -> .lca) while session is active
            OperationReport liveToMca = LeafAnvilWorldConverter.convertWorldToMca(worldDir, false, session);
            assertTrue(liveToMca.filesConvertedOrModified() >= 4);
            assertEquals(0, session.totalOpenRegionCount(), "Live conversion to .mca must close cached .lca handles");
            OperationReport liveToLca = LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, false, session);
            assertTrue(liveToLca.filesConvertedOrModified() >= 4);
            assertNotNull(voxelRfs.read(pos), "Intercepted storage must seamlessly reopen converted .lca region");

            voxelRfs.flush();
            voxelRfs.close();
        }

        // 6. Test reversible batch conversion (.lca -> .mca and back .mca -> .lca)
        OperationReport toMca = LeafAnvilWorldConverter.convertWorldToMca(worldDir, false);
        assertTrue(toMca.filesConvertedOrModified() >= 4);
        assertTrue(Files.exists(worldDir.resolve("region").resolve("r.0.0.mca")));
        assertTrue(AnvilReader.INSTANCE.isChunkGenerated(
                Files.readAllBytes(worldDir.resolve("region").resolve("r.0.0.mca")), 3, 7));

        OperationReport backToLca = LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, true);
        assertTrue(backToLca.filesConvertedOrModified() >= 4);
        assertTrue(Files.exists(worldDir.resolve("region").resolve("r.0.0.lca")));
        assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(
                Files.readAllBytes(worldDir.resolve("region").resolve("r.0.0.lca")), 3, 7));
    }

    public interface SyntheticIRegionFile extends AutoCloseable {
        Path getPath();
        DataInputStream getChunkDataInputStream(SyntheticChunkPos pos) throws IOException;
        DataOutputStream getChunkDataOutputStream(SyntheticChunkPos pos) throws IOException;
        boolean doesChunkExist(SyntheticChunkPos pos);
        boolean hasChunk(SyntheticChunkPos pos);
        void write(SyntheticChunkPos pos, java.nio.ByteBuffer buf) throws IOException;
        void invokeWriteChunk(SyntheticChunkPos pos, java.nio.ByteBuffer buf) throws IOException;
        void clear(SyntheticChunkPos pos) throws IOException;
        void flush() throws IOException;
        boolean isOversized(int x, int z);
        void setOversized(int x, int z, boolean oversized) throws IOException;
        Object getOversizedData(int x, int z) throws IOException;
        boolean recalculateHeader() throws IOException;
        int getRecalculateCount();
        @Override
        void close() throws IOException;
    }

    public static class SyntheticLeavesLinearRegionFileStorage {
        private final Path folder;
        public final SyntheticLong2ObjectLinkedOpenHashMap regionCache =
                new SyntheticLong2ObjectLinkedOpenHashMap();

        public SyntheticLeavesLinearRegionFileStorage(Path folder) {
            this.folder = folder;
        }

        public SyntheticIRegionFile getRegionFile(SyntheticChunkPos pos) {
            long key = SyntheticChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
            return (SyntheticIRegionFile) regionCache.getAndMoveToFirst(key);
        }
    }

    public static class SyntheticC2MEStorageThread extends Thread {
        private final SyntheticLeavesLinearRegionFileStorage storage;

        public SyntheticC2MEStorageThread(Path folder) {
            super("Synthetic-C2ME-Storage");
            this.storage = new SyntheticLeavesLinearRegionFileStorage(folder);
        }
    }

    public static class SyntheticC2MEStorageVanillaInterface {
        private final SyntheticC2MEStorageThread backend;

        public SyntheticC2MEStorageVanillaInterface(Path folder) {
            this.backend = new SyntheticC2MEStorageThread(folder);
        }
    }

    public static class SyntheticC2MEPoiManager {
        private final SyntheticC2MEStorageVanillaInterface c2meStorage;

        public SyntheticC2MEPoiManager(Path folder) {
            this.c2meStorage = new SyntheticC2MEStorageVanillaInterface(folder.resolve("poi"));
        }
    }

    @Test
    @DisplayName("Intercepts C2ME 7-hop StorageThread + invokeWriteChunk and Leaves/Leaf IRegionFile interface")
    void interceptsC2MEAndLeavesIRegionFile(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("c2me_leaves_world");
        Files.createDirectories(worldDir.resolve("poi"));

        SyntheticC2MEPoiManager rootHolder = new SyntheticC2MEPoiManager(worldDir);
        List<Object> discovered = NmsRegionStorageInterceptor.discoverRegionFileStorages(rootHolder);
        assertEquals(1, discovered.size(), "Must discover RegionFileStorage inside C2MEStorageThread (extends Thread)");

        LcaRegionFileStorage lcaPoi = new LcaRegionFileStorage(worldDir.resolve("poi"));
        assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(discovered.get(0), lcaPoi));

        SyntheticLeavesLinearRegionFileStorage rfs = rootHolder.c2meStorage.backend.storage;
        SyntheticChunkPos pos = new SyntheticChunkPos(2, 4);
        SyntheticIRegionFile rf = rfs.getRegionFile(pos);
        assertNotNull(rf);
        assertFalse(rf.isOversized(2, 4));
        rf.setOversized(2, 4, false);
        assertNull(rf.getOversizedData(2, 4));
        assertFalse(rf.recalculateHeader());
        assertEquals(0, rf.getRecalculateCount());

        // C2ME calls invokeWriteChunk(pos, ByteBuffer) with a 5-byte Anvil stream header [len:4][version:1] + NBT
        LinkedHashMap<String, Object> poiCompound = buildVoxelChunk(2, 4, "minecraft:stone");
        byte[] rawNbt = Nbt.writeNamedRoot("", poiCompound);
        java.nio.ByteBuffer c2meBuf = java.nio.ByteBuffer.allocate(5 + rawNbt.length);
        c2meBuf.putInt(rawNbt.length + 1);
        c2meBuf.put((byte) 3); // VERSION_NONE (uncompressed)
        c2meBuf.put(rawNbt);
        c2meBuf.flip();

        rf.invokeWriteChunk(pos, c2meBuf);
        assertTrue(rf.doesChunkExist(pos));
        assertTrue(rf.hasChunk(pos));
        assertTrue(Files.exists(worldDir.resolve("poi").resolve("r.0.0.lca")));

        // Verify repeated cache lookups do not trigger recursive put()
        for (int i = 0; i < 16; i++) {
            assertEquals(rf, rfs.getRegionFile(pos));
        }
        lcaPoi.close();
    }

    public static class SyntheticTypedLong2ObjectMap<V> extends SyntheticLong2ObjectLinkedOpenHashMap {
    }

    public static final class SyntheticPaper263RegionFileStorage implements AutoCloseable {
        private final SyntheticTypedLong2ObjectMap<Optional<SyntheticRegionFile>> regionCache =
                new SyntheticTypedLong2ObjectMap<>();
        private final Path folder;

        public SyntheticPaper263RegionFileStorage(Path folder) {
            this.folder = folder;
        }

        @SuppressWarnings("unchecked")
        public SyntheticRegionFile getRegionFile(SyntheticChunkPos pos) {
            long key = SyntheticChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
            Optional<SyntheticRegionFile> cached =
                    (Optional<SyntheticRegionFile>) this.regionCache.getAndMoveToFirst(key);
            if (cached != null) {
                return cached.orElse(null);
            }
            Path mca = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            SyntheticRegionFile rf = new SyntheticRegionFile(mca);
            this.regionCache.putAndMoveToFirst(key, Optional.of(rf));
            return rf;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void close() throws IOException {
            for (Object val : this.regionCache.values()) {
                Optional<SyntheticRegionFile> opt = (Optional<SyntheticRegionFile>) val;
                if (opt.isPresent()) {
                    opt.get().close();
                }
            }
        }
    }

    @Test
    @DisplayName("Intercepts Paper 26.3 RegionFileStorage where regionCache values are wrapped in Optional<RegionFile>")
    void interceptsOptionalWrappedRegionCachePaper263(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("paper263_world").resolve("region");
        Files.createDirectories(regionDir);

        SyntheticPaper263RegionFileStorage storage = new SyntheticPaper263RegionFileStorage(regionDir);
        // Pre-populate an Optional<RegionFile> entry prior to interception to exercise closeExistingCachedRegionFiles
        SyntheticChunkPos seedPos = new SyntheticChunkPos(32, 32);
        assertNotNull(storage.getRegionFile(seedPos));

        try (LcaRegionFileStorage lcaStorage = new LcaRegionFileStorage(regionDir)) {
            assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(storage, lcaStorage));

            SyntheticChunkPos pos = new SyntheticChunkPos(35, 194);
            SyntheticRegionFile rf = storage.getRegionFile(pos);
            assertNotNull(rf);

            LinkedHashMap<String, Object> chunkCompound = buildVoxelChunk(35, 194, "minecraft:stone");
            try (DataOutputStream out = rf.getChunkDataOutputStream(pos)) {
                out.write(Nbt.writeNamedRoot("", chunkCompound));
            }
            assertTrue(rf.doesChunkExist(pos));
            try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                assertNotNull(in);
                LinkedHashMap<String, Object> readBack = Nbt.readRootCompound(in.readAllBytes());
                assertEquals(35, ((Number) readBack.get("xPos")).intValue());
                assertEquals(194, ((Number) readBack.get("zPos")).intValue());
            }
            record MethodOnlyChunkPos(int x, int z) {}
            int[] extractedMethodPos = LcaRegionFileStorage.extractChunkPosCoords(new MethodOnlyChunkPos(-17, 42));
            assertEquals(-17, extractedMethodPos[0]);
            assertEquals(42, extractedMethodPos[1]);
            int[] extractedFieldPos = LcaRegionFileStorage.extractChunkPosCoords(pos);
            assertEquals(35, extractedFieldPos[0]);
            assertEquals(194, extractedFieldPos[1]);
            storage.close();
        }
    }

    @Test
    @DisplayName("BIT fails on wrong class names or 0 intercepted RegionFileStorage instances and disables incompatible functions")
    void bitFailsOnIncompatibleClassNamesOrZeroInterceptedStorages(@TempDir Path tempDir) throws Exception {
        ServerAccessor accessor = LeafAnvilRuntime.createWireOnlyAccessor(
                "paper", tempDir, java.util.logging.Logger.getLogger("LeafAnvilBitTest"));
        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            assertTrue(runtime.bitSuite().executePbit().passed());
            assertEquals(io.github.dailystruggle.bit.BitHealth.HEALTHY, runtime.bitSuite().getHealthStatus());
            assertTrue(runtime.isDiskStorageOperational());
            assertTrue(runtime.isSectionWireOperational());
            assertFalse(runtime.shouldAbortLoad());

            // 1. Attaching an incompatible world handle with 0 RegionFileStorage fields triggers LCA_002 BIT failure & disables disk storage
            Path badWorldDir = tempDir.resolve("bad_world");
            Files.createDirectories(badWorldDir);
            LcaRegionFileStorage.LcaWorldStorageSession badSession =
                    runtime.attachAndInterceptWorld("bad_world", badWorldDir, -4, 24, new Object());
            assertNull(badSession, "World attachment with 0 intercepted RegionFileStorage must fail closed");
            assertFalse(runtime.isDiskStorageOperational(), "Disk storage must be disabled on LCA_002 BIT failure");
            assertTrue(runtime.isSectionWireOperational(), "Wire/section streaming remains active while only disk is incompatible");
            assertFalse(runtime.shouldAbortLoad(), "Plugin remains loaded for wire streaming while section bridge is compatible");
            assertEquals(io.github.dailystruggle.bit.BitHealth.FAILED, runtime.bitSuite().getHealthStatus());

            // 2. Wrong class name for section/network carrier triggers LCA_003 / LCA_004 BIT failure, disables wire streaming, and marks loadAborted
            Class<?> missingCls = runtime.bitSuite().verifyRequiredClass(
                    net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_003_SECTION_BRIDGE_COMPAT,
                    "TestSectionProbe",
                    "net.minecraft.world.level.chunk.NonExistent27xLevelChunkSection",
                    LeafAnvilInterceptorAndConverterTest.class.getClassLoader());
            assertNull(missingCls);
            assertFalse(runtime.isSectionWireOperational(), "Section/wire streaming must be disabled on LCA_003 BIT failure");
            assertTrue(runtime.shouldAbortLoad(), "When both disk and section/wire are incompatible, shouldAbortLoad() must be true");
            assertFalse(runtime.bitSuite().executePbit().passed());
        }
    }

    private static LinkedHashMap<String, Object> buildVoxelChunk(int cx, int cz, String blockId) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", cx);
        root.put("yPos", -4);
        root.put("zPos", cz);
        root.put("Status", "minecraft:full");

        List<Map<String, Object>> sections = new ArrayList<>();
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 0);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("Name", blockId);
        bs.put("palette", List.of(entry));
        sec.put("block_states", bs);
        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", List.of("minecraft:plains"));
        sec.put("biomes", biomes);
        sections.add(sec);
        root.put("sections", sections);
        return root;
    }

    public static final class NonNoArgRegionCache extends SyntheticLong2ObjectLinkedOpenHashMap {
        public NonNoArgRegionCache(String requiredArg) {
            java.util.Objects.requireNonNull(requiredArg);
        }
    }

    public static final class SyntheticFailingRegionFileStorage {
        private final NonNoArgRegionCache regionCache = new NonNoArgRegionCache("boom");
        private final Path folder;

        public SyntheticFailingRegionFileStorage(Path folder) {
            this.folder = folder;
        }
    }

    public static final class SyntheticPartialWorldHolder {
        private final SyntheticRegionFileStorage goodVoxelStorage;
        private final SyntheticFailingRegionFileStorage failingEntityStorage;

        public SyntheticPartialWorldHolder(Path worldDir) {
            this.goodVoxelStorage = new SyntheticRegionFileStorage(worldDir.resolve("region"));
            this.failingEntityStorage = new SyntheticFailingRegionFileStorage(worldDir.resolve("entities"));
        }
    }

    private static byte[] wrapRawLz4BlockStream(byte[] rawPayload) {
        int check = xxHash32ForTest(rawPayload, 0, rawPayload.length, 0x9747b28c) & 0x0FFFFFFF;
        int levelNibble = 0;
        while ((1 << (10 + levelNibble)) < Math.max(1, rawPayload.length) && levelNibble < 15) {
            levelNibble++;
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(21 + rawPayload.length + 21)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] magic = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
        bb.put(magic);
        bb.put((byte) (0x10 | levelNibble));
        bb.putInt(rawPayload.length);
        bb.putInt(rawPayload.length);
        bb.putInt(check);
        bb.put(rawPayload);
        bb.put(magic);
        bb.put((byte) 0x10);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        return bb.array();
    }

    private static int xxHash32ForTest(byte[] b, int off, int len, int seed) {
        final int p1 = 0x9E3779B1;
        final int p2 = 0x85EBCA77;
        final int p3 = 0xC2B2AE3D;
        final int p4 = 0x27D4EB2F;
        final int p5 = 0x165667B1;
        final int end = off + len;
        int h;
        if (len >= 16) {
            final int limit = end - 16;
            int v1 = seed + p1 + p2;
            int v2 = seed + p2;
            int v3 = seed;
            int v4 = seed - p1;
            do {
                v1 = Integer.rotateLeft(v1 + intLETest(b, off) * p2, 13) * p1;
                v2 = Integer.rotateLeft(v2 + intLETest(b, off + 4) * p2, 13) * p1;
                v3 = Integer.rotateLeft(v3 + intLETest(b, off + 8) * p2, 13) * p1;
                v4 = Integer.rotateLeft(v4 + intLETest(b, off + 12) * p2, 13) * p1;
                off += 16;
            } while (off <= limit);
            h = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h = seed + p5;
        }
        h += len;
        while (off <= end - 4) {
            h = Integer.rotateLeft(h + intLETest(b, off) * p3, 17) * p4;
            off += 4;
        }
        while (off < end) {
            h = Integer.rotateLeft(h + (b[off] & 0xFF) * p5, 11) * p1;
            off++;
        }
        h ^= h >>> 15;
        h *= p2;
        h ^= h >>> 13;
        h *= p3;
        h ^= h >>> 16;
        return h;
    }

    private static int intLETest(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
    }

    @Test
    @DisplayName("Re-running convertWorldToLca never self-deletes or clobbers existing .lca archives")
    void repeatedConvertWorldToLcaPreservesExistingLcaRegions(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("repeat_world");
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);

        LcaRegionWriter.LcaLiveArchive seedLca =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
        seedLca.saveChunkCoW(3, 7, buildVoxelChunk(3, 7, "minecraft:diamond_block"));
        Files.write(regionDir.resolve("r.0.0.mca"), LcaRegionWriter.transcodeToMca(seedLca.toByteArray()));

        OperationReport firstRun = LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, false);
        assertEquals(1, firstRun.filesConvertedOrModified());
        Path lcaPath = regionDir.resolve("r.0.0.lca");
        assertTrue(Files.isRegularFile(lcaPath));
        assertFalse(Files.exists(regionDir.resolve("r.0.0.mca")));

        OperationReport secondRunNoBackup = LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, false);
        assertEquals(0, secondRunNoBackup.filesConvertedOrModified(),
                "Second convertWorldToLca run must not re-convert existing .lca files");
        assertTrue(Files.isRegularFile(lcaPath),
                "Second convertWorldToLca(keepBackups=false) must not delete live r.0.0.lca");

        OperationReport thirdRunWithBackup = LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, true);
        assertEquals(0, thirdRunWithBackup.filesConvertedOrModified());
        assertTrue(Files.isRegularFile(lcaPath),
                "convertWorldToLca(keepBackups=true) must not move live r.0.0.lca to .lca.bak");
        assertFalse(Files.exists(regionDir.resolve("r.0.0.lca.bak")));

        // Stale leftover .mca next to valid live .lca must not clobber live .lca data
        LcaRegionWriter.LcaLiveArchive staleSeed =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
        staleSeed.saveChunkCoW(1, 1, buildVoxelChunk(1, 1, "minecraft:dirt"));
        Files.write(regionDir.resolve("r.0.0.mca"), LcaRegionWriter.transcodeToMca(staleSeed.toByteArray()));

        LeafAnvilWorldConverter.convertWorldToLca(worldDir, false, true);
        AnvilReader.ChunkEntry preserved = LcaRegionReader.INSTANCE.readChunk(Files.readAllBytes(lcaPath), 3, 7);
        assertNotNull(preserved, "Live r.0.0.lca chunk (3, 7) must not be overwritten by stale r.0.0.mca");
    }

    @Test
    @DisplayName("Interceptor propagates doesChunkExist I/O faults, decodes LZ4 stream mode 4, and rejects bad codecs")
    void interceptorPropagatesExistIoErrorDecodesLz4AndRejectsUnknownCodecs(@TempDir Path tempDir) throws Exception {
        Path poiDir = tempDir.resolve("codec_world").resolve("poi");
        Files.createDirectories(poiDir);

        SyntheticC2MEPoiManager rootHolder = new SyntheticC2MEPoiManager(tempDir.resolve("codec_world"));
        List<Object> discovered = NmsRegionStorageInterceptor.discoverRegionFileStorages(rootHolder);
        LcaRegionFileStorage lcaPoi = new LcaRegionFileStorage(poiDir);
        assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(discovered.get(0), lcaPoi));
        SyntheticIRegionFile rf = rootHolder.c2meStorage.backend.storage.getRegionFile(new SyntheticChunkPos(2, 4));

        // 1. Valid LZ4Block (mode 4) stream write round-trips cleanly
        byte[] rawNbt = Nbt.writeNamedRoot("", buildVoxelChunk(2, 4, "minecraft:emerald_block"));
        byte[] lz4Payload = wrapRawLz4BlockStream(rawNbt);
        java.nio.ByteBuffer lz4Buf = java.nio.ByteBuffer.allocate(5 + lz4Payload.length);
        lz4Buf.putInt(lz4Payload.length + 1);
        lz4Buf.put((byte) 4);
        lz4Buf.put(lz4Payload);
        lz4Buf.flip();

        SyntheticChunkPos pos = new SyntheticChunkPos(2, 4);
        rf.invokeWriteChunk(pos, lz4Buf);
        LinkedHashMap<String, Object> decoded = lcaPoi.readChunkCompound(2, 4);
        assertNotNull(decoded, "Mode-4 LZ4 stream must decode and persist valid chunk NBT");
        assertEquals(2, ((Number) decoded.get("xPos")).intValue());

        // 2. Unknown compression ID and truncated (< 5B) stream header throw IOException (S-004)
        java.nio.ByteBuffer unknownCodecBuf = java.nio.ByteBuffer.allocate(5 + rawNbt.length);
        unknownCodecBuf.putInt(rawNbt.length + 1);
        unknownCodecBuf.put((byte) 99);
        unknownCodecBuf.put(rawNbt);
        unknownCodecBuf.flip();
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> rf.invokeWriteChunk(pos, unknownCodecBuf));

        java.nio.ByteBuffer truncatedBuf = java.nio.ByteBuffer.wrap(new byte[] {0, 0, 1});
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> rf.invokeWriteChunk(pos, truncatedBuf));

        // 3. Corrupt region file on disk propagates UncheckedIOException in doesChunkExist instead of returning false
        lcaPoi.close();
        try (LcaRegionFileStorage freshStorage = new LcaRegionFileStorage(poiDir)) {
            byte[] garbage = new byte[40960];
            java.util.Arrays.fill(garbage, (byte) 0x5A);
            Files.write(poiDir.resolve("r.1.0.lca"), garbage);
            NmsRegionStorageInterceptor.LcaRegionHandle badHandle =
                    new NmsRegionStorageInterceptor.LcaRegionHandle(freshStorage, 1, 0);
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.UncheckedIOException.class,
                    () -> NmsRegionStorageInterceptor.onDoesChunkExist(badHandle, new SyntheticChunkPos(32, 0)),
                    "doesChunkExist must surface I/O/corruption errors instead of reporting chunk missing");
        }
    }

    @Test
    @DisplayName("Partial world interception rolls back swapped caches and detachWorld restores original caches")
    void partialWorldInterceptionRollsBackSwappedCachesAndDetachRestoresOriginalCache(@TempDir Path tempDir)
            throws Exception {
        Path dataDir = tempDir.resolve("plugin_data");
        Path partialWorldDir = tempDir.resolve("partial_world");
        Files.createDirectories(partialWorldDir.resolve("region"));
        Files.createDirectories(partialWorldDir.resolve("entities"));

        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());

        java.lang.reflect.Field cacheField = SyntheticRegionFileStorage.class.getDeclaredField("regionCache");
        cacheField.setAccessible(true);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();

            SyntheticPartialWorldHolder partialWorld = new SyntheticPartialWorldHolder(partialWorldDir);
            Object originalGoodCache = cacheField.get(partialWorld.goodVoxelStorage);
            LcaRegionFileStorage.LcaWorldStorageSession failedSession =
                    runtime.attachAndInterceptWorld("partial_world", partialWorldDir, -4, 24, partialWorld);
            assertNull(failedSession, "Partial interception failure must abort world session attachment");
            assertEquals(originalGoodCache, cacheField.get(partialWorld.goodVoxelStorage),
                    "Already-swapped RegionFileStorage cache must roll back when sibling interception fails");
        }

        // Now verify detachWorld restores original caches on a healthy world
        Path healthyWorldDir = tempDir.resolve("healthy_world");
        Files.createDirectories(healthyWorldDir.resolve("region"));
        Files.createDirectories(healthyWorldDir.resolve("entities"));
        Files.createDirectories(healthyWorldDir.resolve("poi"));
        try (LeafAnvilRuntime runtime2 = new LeafAnvilRuntime(accessor)) {
            runtime2.start();
            SyntheticServerLevel serverLevel = new SyntheticServerLevel(healthyWorldDir);
            SyntheticRegionFileStorage voxelRfs = serverLevel.chunkSource.chunkMap.worker.storage;
            Object originalVoxelCache = cacheField.get(voxelRfs);

            LcaRegionFileStorage.LcaWorldStorageSession attached =
                    runtime2.attachAndInterceptWorld("healthy_world", healthyWorldDir, -4, 24, serverLevel);
            assertNotNull(attached);
            assertTrue(cacheField.get(voxelRfs).getClass().getName().contains("$$LeafAnvilCacheBridge"));

            runtime2.detachWorld("healthy_world");
            assertEquals(originalVoxelCache, cacheField.get(voxelRfs),
                    "detachWorld must restore the original RegionFileStorage cache");
        }
    }

    @Test
    @DisplayName("LeafAnvilConfig strips inline YAML comments and primary-thread batch fallback dispatches asynchronously")
    void configStripsInlineCommentsAndPrimaryThreadBatchTaskRunsOffThread(@TempDir Path tempDir) throws Exception {
        Path cfgDir = tempDir.resolve("cfg_data");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.yml"),
                "enabled: true # keep storage enabled\n"
                        + "auto-migrate-legacy: false # manual convert only\n"
                        + "lcd2-level: 4 # balanced lcd2\n");
        Files.writeString(cfgDir.resolve("messages.yml"),
                "busy: \"Storage busy #1\" # quoted hash preserved, trailing comment stripped\n");

        LeafAnvilConfig cfg = LeafAnvilConfig.loadOrCreate(cfgDir);
        assertTrue(cfg.enabled(), "Inline '# ...' comment after 'true' must not turn enabled into false");
        assertFalse(cfg.autoMigrateLegacy());
        assertEquals(4, cfg.lcd2Level());
        assertEquals("Storage busy #1", cfg.msgBusy());

        ServerAccessor primaryThreadAccessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(primaryThreadAccessor.getRootFolder()).thenReturn(cfgDir);
        Mockito.when(primaryThreadAccessor.scheduler()).thenReturn(Optional.empty());
        Mockito.when(primaryThreadAccessor.isPrimaryThread()).thenReturn(true);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(primaryThreadAccessor)) {
            runtime.load();
            Thread callerThread = Thread.currentThread();
            java.util.concurrent.CompletableFuture<Thread> ranOn = new java.util.concurrent.CompletableFuture<>();
            assertTrue(runtime.runBatchTaskAsync(UUID.randomUUID(), () -> ranOn.complete(Thread.currentThread())));
            Thread worker = ranOn.get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotSame(
                    callerThread, worker, "runBatchTaskAsync must never execute on the primary server thread (S-005)");
        }
    }

    @Test
    @DisplayName("LcaInMemoryChunkCoordinator gates server compaction on active LCA_NET_V1 or inter-server consumers")
    void coordinatorGatesCompactionOnActiveWireOrShardConsumers() {
        LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                new net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache(16), false);
        try {
            assertFalse(coord.hasActiveWireOrShardConsumers(),
                    "Compaction consumer gate must start false when 0 LCA_NET_V1 or LeafShard consumers exist");

            // Mismatched parity hash negotiates VANILLA_FALLBACK -> consumer gate must remain false
            UUID vanillaPlayer = UUID.randomUUID();
            net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload badHs =
                    new net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload(
                            net.leaf.anvil.common.net.LcaWireStreamProtocol.PROTOCOL_VERSION,
                            net.leaf.anvil.common.net.LcaWireStreamProtocol.ALL_CAPABILITIES,
                            4096,
                            0x1111222233334444L);
            assertFalse(coord.registerPlayerHandshake(vanillaPlayer, badHs, 0x9999AAAABBBBCCCCL).isLcaStreamActive());
            assertFalse(coord.hasActiveWireOrShardConsumers());

            // Valid LCA_NET_V1 handshake activates consumer gate; pruning offline player turns it back off
            UUID lcaPlayer = UUID.randomUUID();
            long parity = 0x9999AAAABBBBCCCCL;
            net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload goodHs =
                    new net.leaf.anvil.common.net.LcaWireStreamProtocol.HandshakePayload(
                            net.leaf.anvil.common.net.LcaWireStreamProtocol.PROTOCOL_VERSION,
                            net.leaf.anvil.common.net.LcaWireStreamProtocol.ALL_CAPABILITIES,
                            4096,
                            parity);
            assertTrue(coord.registerPlayerHandshake(lcaPlayer, goodHs, parity).isLcaStreamActive());
            assertTrue(coord.hasActiveWireOrShardConsumers());

            assertEquals(1, coord.pruneDisconnectedPlayerSessions(id -> id.equals(vanillaPlayer)));
            assertFalse(coord.hasActiveWireOrShardConsumers(),
                    "Pruning the last active LCA_NET_V1 player must deactivate background compaction");

            // Explicit LeafShard / inter-server consumer toggle also activates and deactivates gate
            coord.setInterServerConsumerActive(true);
            assertTrue(coord.hasActiveWireOrShardConsumers());
            coord.setInterServerConsumerActive(false);
            assertFalse(coord.hasActiveWireOrShardConsumers());
        } finally {
            coord.shutdownCompactionPool();
        }
    }

    public static final class SyntheticBitStorage {
        private final int bits;
        private final long[] raw;

        public SyntheticBitStorage(int bits, long[] raw) {
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

    public static final class SyntheticPalette {
        private final Object[] values;

        public SyntheticPalette(Object... values) {
            this.values = values;
        }

        public Object valueFor(int idx) {
            return values[idx];
        }

        public int getSize() {
            return values.length;
        }
    }

    public static final class SyntheticContainerData {
        public final SyntheticBitStorage storage;
        public final SyntheticPalette palette;

        public SyntheticContainerData(SyntheticBitStorage storage, SyntheticPalette palette) {
            this.storage = storage;
            this.palette = palette;
        }
    }

    public static final class SyntheticPalettedContainer {
        public SyntheticContainerData data;

        public SyntheticPalettedContainer(SyntheticContainerData data) {
            this.data = data;
        }
    }

    public static final class SyntheticNmsSection {
        public short nonEmptyBlockCount;
        public short tickingBlockCount;
        public short tickingFluidCount;
        private final SyntheticPalettedContainer states;

        public SyntheticNmsSection(
                short nonEmptyBlockCount,
                short tickingBlockCount,
                short tickingFluidCount,
                SyntheticPalettedContainer states
        ) {
            this.nonEmptyBlockCount = nonEmptyBlockCount;
            this.tickingBlockCount = tickingBlockCount;
            this.tickingFluidCount = tickingFluidCount;
            this.states = states;
        }

        public SyntheticPalettedContainer getStates() {
            return states;
        }
    }

    @Test
    @DisplayName("LcaSectionBridge ZeroBitShellEvictor inspects synthetic NMS containers, snapshots 0-bit and paletted sections, and verifies RawSectionSnapshot value semantics")
    void sectionBridgeZeroBitShellEvictorAndSnapshotValueSemantics() {
        assertFalse(LcaSectionBridge.isSuppressCopyHydrationForDirectSave());
        LcaSectionBridge.setSuppressCopyHydrationForDirectSave(true);
        assertTrue(LcaSectionBridge.isSuppressCopyHydrationForDirectSave());
        LcaSectionBridge.setSuppressCopyHydrationForDirectSave(false);
        assertFalse(LcaSectionBridge.isSuppressCopyHydrationForDirectSave());

        assertNull(LcaSectionBridge.ZeroBitShellEvictor.inspectNmsSectionCountsOrNull(null));
        assertNull(LcaSectionBridge.ZeroBitShellEvictor.inspectNmsSectionCountsOrNull("not-a-section"));

        // 0-bit uniform section
        SyntheticPalettedContainer uniformContainer = new SyntheticPalettedContainer(
                new SyntheticContainerData(new SyntheticBitStorage(0, new long[0]), new SyntheticPalette(Integer.valueOf(7))));
        SyntheticNmsSection uniformSection = new SyntheticNmsSection((short) 4096, (short) 2, (short) 1, uniformContainer);

        int[] counts = LcaSectionBridge.ZeroBitShellEvictor.inspectNmsSectionCountsOrNull(uniformSection);
        assertNotNull(counts);
        assertEquals(4096, counts[0]);
        assertEquals(2, counts[1]);
        assertEquals(1, counts[2]);

        LcaSectionBridge.RawSectionSnapshot uniformSnap = LcaSectionBridge.ZeroBitShellEvictor.captureRawSectionSnapshot(
                3, uniformSection, o -> ((Integer) o), 4096, 2, 1, 15);
        assertNotNull(uniformSnap);
        assertTrue(uniformSnap.uniform());
        assertEquals(7, uniformSnap.uniformStateId());

        // 4-bit paletted section
        long[] packed = new long[256];
        packed[0] = 0x1010_0101L;
        SyntheticPalettedContainer palContainer = new SyntheticPalettedContainer(
                new SyntheticContainerData(
                        new SyntheticBitStorage(4, packed),
                        new SyntheticPalette(Integer.valueOf(0), Integer.valueOf(12), Integer.valueOf(34))));
        SyntheticNmsSection palSection = new SyntheticNmsSection((short) 128, (short) 4, (short) 0, palContainer);

        assertEquals(256 * 8, LcaSectionBridge.ZeroBitShellEvictor.inspectRawLongArrayBytes(palSection));
        assertEquals(256 * 8, LcaSectionBridge.ZeroBitShellEvictor.inspectRawLongArrayBytes(palContainer));
        assertEquals(0, LcaSectionBridge.ZeroBitShellEvictor.inspectRawLongArrayBytes(null));

        LcaSectionBridge.RawSectionSnapshot palSnap = LcaSectionBridge.ZeroBitShellEvictor.captureRawSectionSnapshot(
                5, palSection, o -> ((Integer) o), 128, 4, 0, 14);
        assertNotNull(palSnap);
        assertFalse(palSnap.uniform());
        assertEquals(4, palSnap.bitsPerEntry());
        assertEquals(3, palSnap.paletteStateIds().length);
        assertEquals(34, palSnap.paletteStateIds()[2]);

        // RawSectionSnapshot equals / hashCode / toString coverage
        LcaSectionBridge.RawSectionSnapshot palSnapCopy = new LcaSectionBridge.RawSectionSnapshot(
                5, false, 0, 4, packed.clone(), new int[]{0, 12, 34}, 128, 4, 0, 14);
        Object selfRef = palSnap;
        assertEquals(palSnap, selfRef);
        assertEquals(palSnap, palSnapCopy);
        assertEquals(palSnap.hashCode(), palSnapCopy.hashCode());
        assertTrue(palSnap.toString().contains("sectionIndex=5"));
        assertFalse(palSnap.equals(null));
        assertFalse(palSnap.equals("other"));
        assertFalse(palSnap.equals(uniformSnap));

        // Default bridge methods
        final io.github.dailystruggle.anvil.LcaSectionBacking[] holder =
                new io.github.dailystruggle.anvil.LcaSectionBacking[1];
        LcaSectionBridge bridge = new LcaSectionBridge() {
            @Override
            public io.github.dailystruggle.anvil.LcaSectionBacking leafanvil$getBacking() {
                return holder[0];
            }

            @Override
            public void leafanvil$setBacking(io.github.dailystruggle.anvil.LcaSectionBacking backing) {
                holder[0] = backing;
            }
        };
        assertEquals(-1, bridge.leafanvil$getStateId(0, 0, 0));
        assertEquals(-1, bridge.leafanvil$setAndGetPreviousStateId(0, 0, 0, 5, 1L));
        assertFalse(bridge.leafanvil$isZeroBitShellEvicted());
        assertEquals(0, bridge.leafanvil$evictToZeroBitShell());
        bridge.leafanvil$setBacking(new io.github.dailystruggle.anvil.LcaSectionBacking(new byte[0], 3), true);
        assertEquals(3, bridge.leafanvil$getStateId(16, 32, 48));
        assertEquals(3, bridge.leafanvil$setAndGetPreviousStateId(16, 32, 48, 9, 2L));
        assertEquals(9, bridge.leafanvil$getStateId(0, 0, 0));
    }

    @Test
    @DisplayName("LeafAnvilCommand exposes metadata and executes status, bit, reload, pause, resume, cancel, and compact subcommands")
    void leafAnvilCommandMetadataAndSubcommandDispatch(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("cmd_data");
        Files.createDirectories(dataDir);
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.empty());
        Mockito.when(accessor.isPrimaryThread()).thenReturn(false);

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.start();
            LeafAnvilCommand cmd = new LeafAnvilCommand("leafanvil", accessor, runtime);
            assertEquals("leafanvil", cmd.name());
            assertEquals("leafanvil.admin", cmd.permission());
            assertFalse(cmd.description().isBlank());
            assertNull(cmd.parent());
            assertEquals(0L, cmd.avgTime());
            assertNotNull(cmd.getParameterLookup());
            assertTrue(cmd.getCommandLookup().size() >= 9);

            UUID sender = UUID.randomUUID();
            assertTrue(cmd.onCommand(sender, Map.of(), null));
            for (io.github.dailystruggle.commandsapi.common.CommandsAPICommand sub : cmd.getCommandLookup().values()) {
                assertNotNull(sub.name());
                assertNotNull(sub.permission());
                assertNotNull(sub.description());
                assertEquals(cmd, sub.parent());
                assertEquals(0L, sub.avgTime());
                Map<String, List<String>> params = "convert".equalsIgnoreCase(sub.name())
                        ? Map.of("world", List.of("all"), "format", List.of("lca"), "workers", List.of("auto"))
                        : ("heal".equalsIgnoreCase(sub.name())
                                ? Map.of("world", List.of("all"), "workers", List.of("2"))
                                : Map.of());
                sub.onCommand(sender, params, null);
            }
            cmd.msgNoPermission(sender, "leafanvil.admin");
            cmd.msgInvalidCommand(sender, "bogus");
            cmd.msgBadParameter(sender, "workers", "999");
        }
    }

    @Test
    @DisplayName("LcaInMemoryChunkCoordinator compaction, CoW save tickets, AIMD decay, and transformer/profiler helpers")
    void coordinatorCompactionCowSaveTicketsAndAuxiliaryHelpers(@TempDir Path tempDir) throws Exception {
        Path regionDir = Files.createDirectories(tempDir.resolve("region"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir)) {
            LcaInMemoryChunkCoordinator coord = new LcaInMemoryChunkCoordinator(
                    new net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache(64), true);
            try {
                // Build a minimal real .lca chunk frame via LcaRegionFileStorage so coldSnapshot + CoW splice work end-to-end
                LinkedHashMap<String, Object> chunkRoot = new LinkedHashMap<>();
                chunkRoot.put("DataVersion", 4671);
                chunkRoot.put("xPos", 0);
                chunkRoot.put("zPos", 0);
                chunkRoot.put("yPos", -4);
                chunkRoot.put("Status", "minecraft:full");
                chunkRoot.put("LastUpdate", 10L);
                chunkRoot.put("InhabitedTime", 0L);
                byte[] nbtBytes = Nbt.writeNamedRoot("", chunkRoot);
                storage.writeChunkNbtBytes(0, 0, nbtBytes);

                io.github.dailystruggle.anvil.RawChunkFrameSnapshot coldSnap = storage.readRawChunkFrame(0, 0, null);
                assertNotNull(coldSnap);

                LcaInMemoryChunkCoordinator.TrackedChunkColumn col = coord.attachColdChunkFrameColumn(
                        "minecraft:overworld", 0, 0, coldSnap, null, 10L, false);
                col.bindColdChunkFrame(coldSnap, storage, null, id -> "minecraft:stone");

                // Mutate section 4 and exercise AIMD decay + quiet-window compaction + background compaction
                col.mutateVoxel(4, 1, 2, 3, 55, 10L);
                coord.decayDirtyChunkScores();

                int compacted = coord.compactReadySectionsInChunk(
                        "minecraft:overworld", 0, 0, 500L, 10L, snap -> new byte[]{0x00, 0x01, 0x01});
                assertTrue(compacted >= 1);

                // Mutate again and schedule background overlay compaction + CoW save ticket
                col.mutateVoxel(5, 2, 3, 4, 77, 510L);
                int scheduled = coord.scheduleBackgroundOverlayCompaction(900L, 10L, 4, 15);
                assertTrue(scheduled >= 0);

                LcaInMemoryChunkCoordinator.DirectCoWSaveTicket ticket =
                        coord.captureDirectCoWSaveTicket("minecraft:overworld", 0, 0);
                assertNotNull(ticket);
                Object ticketSelf = ticket;
                assertEquals(ticket, ticketSelf);
                assertEquals(ticket.hashCode(), ticketSelf.hashCode());
                assertTrue(ticket.toString().contains("DirectCoWSaveTicket"));
                assertFalse(ticket.equals(null));
                assertTrue(coord.commitDirectCoWSaveTicketSync(ticket));
                assertFalse(coord.commitDirectCoWSaveTicketSync(null));

                // Exercise commitOrSpliceColdColumnToStorage on clean & dirty column
                col.mutateVoxel(6, 0, 0, 0, 88, 950L);
                assertTrue(coord.commitOrSpliceColdColumnToStorage("minecraft:overworld", 0, 0, null, 960L));
                assertFalse(coord.commitOrSpliceColdColumnToStorage("minecraft:overworld", 99, 99, null, 960L));

                // Deferred chunk load queue compaction
                assertTrue(coord.enqueuePendingChunkLoad("minecraft:overworld", 2, 3, new Object(), 100L));
                List<LcaInMemoryChunkCoordinator.PendingChunkLoad> drainedLoads = new ArrayList<>();
                assertEquals(0, coord.tickDeferredChunkCompaction(105L, 20L, 4, drainedLoads::add));
                assertEquals(1, coord.tickDeferredChunkCompaction(200L, 20L, 4, drainedLoads::add));
                assertEquals(1, drainedLoads.size());

                // Coordinator record value semantics
                Object[] nmsSecs = new Object[0];
                LcaSectionBridge.RawSectionSnapshot[] snaps = new LcaSectionBridge.RawSectionSnapshot[0];
                io.github.dailystruggle.anvil.LcaSectionBacking[] backings =
                        new io.github.dailystruggle.anvil.LcaSectionBacking[0];
                LcaInMemoryChunkCoordinator.CompletedChunkCompaction cc1 =
                        new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                                "world", 1, 2, null, nmsSecs, snaps, backings, 10L, null);
                LcaInMemoryChunkCoordinator.CompletedChunkCompaction cc2 =
                        new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                                "world", 1, 2, null, nmsSecs, snaps, backings, 10L, null);
                Object ccSelf = cc1;
                assertEquals(cc1, ccSelf);
                assertEquals(cc1, cc2);
                assertEquals(cc1.hashCode(), cc2.hashCode());
                assertTrue(cc1.toString().contains("chunkX=1"));
                assertFalse(cc1.equals(new LcaInMemoryChunkCoordinator.CompletedChunkCompaction(
                        "world", 9, 2, null, nmsSecs, snaps, backings, 10L, null)));

                net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload emptyBatch =
                        new net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload(
                                1, 2, List.of(new net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryCleanAck(0)));
                LcaInMemoryChunkCoordinator.RegionPackedBatchFrame rbf1 =
                        new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                                1, 2, emptyBatch, new byte[]{4, 5}, new byte[]{6, 7}, List.of(), 0, 0, 1);
                LcaInMemoryChunkCoordinator.RegionPackedBatchFrame rbf2 =
                        new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                                1, 2, emptyBatch, new byte[]{4, 5}, new byte[]{6, 7}, List.of(), 0, 0, 1);
                Object rbfSelf = rbf1;
                assertEquals(rbf1, rbfSelf);
                assertEquals(rbf1, rbf2);
                assertEquals(rbf1.hashCode(), rbf2.hashCode());
                assertTrue(rbf1.toString().contains("regionX=1"));
                assertFalse(rbf1.equals(new LcaInMemoryChunkCoordinator.RegionPackedBatchFrame(
                        9, 2, emptyBatch, new byte[]{4, 5}, new byte[]{6, 7}, List.of(), 0, 0, 1)));

                LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit isc1 =
                        new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit("world", 1, 2, 3, new int[]{10, 20}, 99L);
                LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit isc2 =
                        new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit("world", 1, 2, 3, new int[]{10, 20}, 99L);
                Object iscSelf = isc1;
                assertEquals(isc1, iscSelf);
                assertEquals(isc1, isc2);
                assertEquals(isc1.hashCode(), isc2.hashCode());
                assertTrue(isc1.toString().contains("sectionIndex=3"));
                assertFalse(isc1.equals(new LcaInMemoryChunkCoordinator.InterServerSectionBlockCommit(
                        "world", 1, 2, 4, new int[]{10, 20}, 99L)));

                // Profiler resetAll + transformer non-matching class passthrough
                net.leaf.anvil.common.net.LcaPacketDifferentialProfiler prof =
                        new net.leaf.anvil.common.net.LcaPacketDifferentialProfiler();
                prof.recordPacketDifferential(
                        net.leaf.anvil.common.net.LcaPacketDifferentialProfiler.BUCKET_CHUNK_STREAM,
                        1, 500, 500, 1, 1, 100, 1);
                prof.resetAll();
                assertEquals(0L, prof.snapshot(1).totalVanillaPackets());

                net.leaf.anvil.agent.LeafAnvilSectionClassTransformer xform =
                        new net.leaf.anvil.agent.LeafAnvilSectionClassTransformer();
                assertNull(xform.transform(null, "java/lang/String", null, null, new byte[]{1, 2, 3}));
                assertNull(xform.transform(null, null, null, null, new byte[]{1, 2, 3}));
            } finally {
                coord.shutdownCompactionPool();
            }
        }
    }
}
