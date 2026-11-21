package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Zero-fork runtime `RegionFileStorage` and `RegionFile` bytecode/cache interceptor
 * (`leafanvil-ADR-001`, `anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * <p>Discovers NMS `RegionFileStorage` instances (`region/`, `entities/`, `poi/`) on a world handle
 * (`CraftWorld` or `ServerLevel`) and replaces each `regionCache` (`Long2ObjectLinkedOpenHashMap`)
 * with an intercepting subclass whose `getAndMoveToFirst(long)` returns a dynamically generated
 * `RegionFile` subclass backed by {@link LcaRegionFileStorage} (`r.<x>.<z>.lca`).</p>
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class NmsRegionStorageInterceptor {

    private static final Unsafe UNSAFE = resolveUnsafe();
    private static final Map<String, Class<?>> GENERATED_CLASSES = new ConcurrentHashMap<>();

    /**
     * Custom `RegionFileVersion` stream ID (`0x4C` = `'L'`) used to hook `RegionFileVersion#wrap(OutputStream)`
     * so `C2ME`'s `GlobalExecutors.prioritizedScheduler` worker pool encodes `.lca` `ChunkFrame` slabs
     * directly inside `lambda$writeChunk$1`, leaving only the $O(1)$ slab commit on `C2MEStorageThread`.
     */
    public static final int LCA_PREENCODED_VERSION_ID = 0x4C;

    private NmsRegionStorageInterceptor() {
    }

    /**
     * `OutputStream` wrapper returned by our custom `RegionFileVersion$StreamWrapper` on `C2ME`'s
     * `GlobalExecutors.prioritizedScheduler` worker pool. Buffers uncompressed Mojang NBT bytes from
     * `NbtIo.write(CompoundTag, DataOutput)` and, on {@link #close()}, parses and encodes the `.lca`
     * `ChunkFrame` slab on the `C2ME` worker thread before writing the pre-encoded slab into `rawOut`.
     */
    static final class LcaPreEncodingOutputStream extends OutputStream {
        private final LcaRegionHandle handle;
        private final OutputStream rawOut;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private byte[] buf = new byte[65536];
        private int count;

        LcaPreEncodingOutputStream(LcaRegionHandle handle, OutputStream rawOut) {
            this.handle = handle;
            this.rawOut = rawOut;
        }

        private void ensureCap(int needed) {
            if (needed > buf.length) {
                int next = Math.max(needed, buf.length << 1);
                buf = java.util.Arrays.copyOf(buf, next);
            }
        }

        @Override
        public void write(int b) {
            ensureCap(count + 1);
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] src, int off, int len) {
            java.util.Objects.checkFromIndexSize(off, len, src.length);
            if (len > 0) {
                ensureCap(count + len);
                System.arraycopy(src, off, buf, count, len);
                count += len;
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (count > 0 && !LcaRegionFileStorage.containsDirectCoWCommittedMarker(buf, 0, count)) {
                byte[] preEncodedSlab = handle.storage().encodeChunkNbtForRegionSlab(
                        handle.regionX(), handle.regionZ(), buf, 0, count);
                if (preEncodedSlab != null && preEncodedSlab.length > 0) {
                    rawOut.write(preEncodedSlab);
                }
            }
            rawOut.flush();
        }
    }

    /**
     * Per-region `(regionX, regionZ)` handle attached to a generated `RegionFile` bridge instance.
     */
    public static final class LcaRegionHandle {
        private final LcaRegionFileStorage storage;
        private final int regionX;
        private final int regionZ;
        private final Path regionPath;

        public LcaRegionHandle(LcaRegionFileStorage storage, int regionX, int regionZ) {
            this.storage = storage;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.regionPath = storage.storageDirectory().resolve("r." + regionX + "." + regionZ + ".lca");
        }

        public LcaRegionFileStorage storage() {
            return storage;
        }

        public int regionX() {
            return regionX;
        }

        public int regionZ() {
            return regionZ;
        }

        public Path regionPath() {
            return regionPath;
        }
    }

    /**
     * Controller attached to a generated `Long2ObjectLinkedOpenHashMap` `regionCache` bridge instance.
     */
    public static final class CacheController {
        private final LcaRegionFileStorage storage;
        private final Class<?> regionFileBridgeClass;
        private final Field handleField;
        private final Method putMethod;
        private final Object storageInfo;
        private final Object uncompressedVersion;
        private final boolean wrapInOptional;
        private final Map<Long, Object> bridgesByKey = new ConcurrentHashMap<>();

        CacheController(
                LcaRegionFileStorage storage,
                Class<?> regionFileBridgeClass,
                Class<?> cacheClass,
                Object regionFileStorage,
                boolean wrapInOptional
        ) throws ReflectiveOperationException {
            this.storage = storage;
            this.regionFileBridgeClass = regionFileBridgeClass;
            this.handleField = regionFileBridgeClass.getField("lcaHandle");
            this.putMethod = findPutLongMethod(cacheClass);
            this.storageInfo = extractStorageInfo(regionFileStorage);
            this.uncompressedVersion = resolveUncompressedRegionFileVersion(regionFileBridgeClass.getSuperclass());
            this.wrapInOptional = wrapInOptional;
        }

        private static Method findPutLongMethod(Class<?> cacheClass) {
            for (String methodName : List.of("putAndMoveToFirst", "put")) {
                try {
                    Method m = cacheClass.getMethod(methodName, long.class, Object.class);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }

        public LcaRegionFileStorage storage() {
            return storage;
        }

        Object getOrCreateBridge(Object cacheInstance, long packedRegionKey) {
            boolean[] created = {false};
            Object bridge = bridgesByKey.computeIfAbsent(packedRegionKey, key -> {
                try {
                    int rx = (int) (long) key;
                    int rz = (int) (key >>> 32);
                    Object b = UNSAFE.allocateInstance(regionFileBridgeClass);
                    LcaRegionHandle handle = new LcaRegionHandle(storage, rx, rz);
                    handleField.set(b, handle);
                    initializeMoonriseRegionFileFields(b, regionFileBridgeClass.getSuperclass(), handle);
                    created[0] = true;
                    return wrapInOptional ? Optional.of(b) : b;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Failed to instantiate LCA RegionFile bridge", e);
                }
            });
            if (created[0] && putMethod != null && cacheInstance != null) {
                try {
                    synchronized (cacheInstance) {
                        putMethod.invoke(cacheInstance, packedRegionKey, bridge);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Failed to register LCA RegionFile bridge in cache", e);
                }
            }
            return bridge;
        }

        @SuppressWarnings("deprecation")
        private void initializeMoonriseRegionFileFields(
                Object bridge, Class<?> regionFileClass, LcaRegionHandle handle
        ) {
            if (regionFileClass == null || UNSAFE == null) {
                return;
            }
            Path regionPath = handle.regionPath();
            Object lcaVersion = createLcaRegionFileVersion(regionFileClass, handle, uncompressedVersion);
            Class<?> cls = regionFileClass;
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        long off = UNSAFE.objectFieldOffset(f);
                        String fName = f.getName();
                        Class<?> ft = f.getType();
                        if ("version".equals(fName) || ft.getSimpleName().contains("RegionFileVersion")) {
                            if (lcaVersion != null) {
                                UNSAFE.putObject(bridge, off, lcaVersion);
                            }
                        } else if ("info".equals(fName) || ft.getSimpleName().contains("RegionStorageInfo")) {
                            if (storageInfo != null) {
                                UNSAFE.putObject(bridge, off, storageInfo);
                            }
                        } else if ("recalculateCount".equals(fName) && ft == AtomicInteger.class) {
                            UNSAFE.putObject(bridge, off, new AtomicInteger(0));
                        } else if ("oversized".equals(fName) && ft == byte[].class) {
                            UNSAFE.putObject(bridge, off, new byte[1024]);
                        } else if ("path".equals(fName) && ft == Path.class) {
                            UNSAFE.putObject(bridge, off, regionPath);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
            }
        }
    }

    private static Object createLcaRegionFileVersion(
            Class<?> regionFileClass, LcaRegionHandle handle, Object fallbackVersion
    ) {
        if (regionFileClass == null || handle == null) {
            return fallbackVersion;
        }
        Class<?> cls = regionFileClass;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        && ("version".equals(f.getName()) || f.getType().getSimpleName().contains("RegionFileVersion"))) {
                    Class<?> versionClass = f.getType();
                    try {
                        for (Constructor<?> ctor : versionClass.getDeclaredConstructors()) {
                            Class<?>[] pTypes = ctor.getParameterTypes();
                            if (pTypes.length == 4
                                    && pTypes[0] == int.class
                                    && pTypes[1] == String.class
                                    && pTypes[2].isInterface()
                                    && pTypes[2] == pTypes[3]) {
                                Class<?> wrapperIface = pTypes[2];
                                Object inWrapper = Proxy.newProxyInstance(
                                        wrapperIface.getClassLoader(),
                                        new Class<?>[] {wrapperIface},
                                        (proxy, method, args) -> (args != null && args.length > 0) ? args[0] : null);
                                Object outWrapper = Proxy.newProxyInstance(
                                        wrapperIface.getClassLoader(),
                                        new Class<?>[] {wrapperIface},
                                        (proxy, method, args) -> {
                                            if (args != null && args.length == 1 && args[0] instanceof OutputStream os) {
                                                return new java.io.BufferedOutputStream(
                                                        new LcaPreEncodingOutputStream(handle, os),
                                                        8192);
                                            }
                                            return (args != null && args.length > 0) ? args[0] : null;
                                        });
                                ctor.setAccessible(true);
                                return ctor.newInstance(LCA_PREENCODED_VERSION_ID, null, inWrapper, outWrapper);
                            }
                        }
                    } catch (ReflectiveOperationException | SecurityException ex) {
                        Logger.getLogger(NmsRegionStorageInterceptor.class.getName()).log(
                                Level.FINE, "Falling back to uncompressed RegionFileVersion on " + versionClass.getName(), ex);
                        return fallbackVersion;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return fallbackVersion;
    }

    private static Object extractStorageInfo(Object regionFileStorage) {
        if (regionFileStorage == null) {
            return null;
        }
        Class<?> cls = regionFileStorage.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        && ("info".equals(f.getName()) || f.getType().getSimpleName().contains("RegionStorageInfo"))) {
                    try {
                        f.setAccessible(true);
                        return f.get(regionFileStorage);
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Object resolveUncompressedRegionFileVersion(Class<?> regionFileClass) {
        if (regionFileClass == null) {
            return null;
        }
        for (Field f : regionFileClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && ("version".equals(f.getName()) || f.getType().getSimpleName().contains("RegionFileVersion"))) {
                Class<?> versionClass = f.getType();
                try {
                    Field vNone = versionClass.getDeclaredField("VERSION_NONE");
                    vNone.setAccessible(true);
                    return vNone.get(null);
                } catch (Throwable ignored) {
                }
                try {
                    Method fromId = versionClass.getDeclaredMethod("fromId", int.class);
                    fromId.setAccessible(true);
                    Object v3 = fromId.invoke(null, 3);
                    if (v3 != null) {
                        return v3;
                    }
                } catch (Throwable ignored) {
                }
                for (Field sf : versionClass.getDeclaredFields()) {
                    if (Modifier.isStatic(sf.getModifiers()) && sf.getType() == versionClass) {
                        try {
                            sf.setAccessible(true);
                            Object candidate = sf.get(null);
                            if (candidate != null) {
                                Method getId = versionClass.getDeclaredMethod("getId");
                                getId.setAccessible(true);
                                if (Integer.valueOf(3).equals(getId.invoke(candidate))) {
                                    return candidate;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Static callback hooks invoked by generated RegionFile & RegionCache bytecode
    // -------------------------------------------------------------------------

    public static Object onCacheGet(Object cacheInstance, Object controllerObj, long key) {
        if (!(controllerObj instanceof CacheController controller)) {
            return null;
        }
        return controller.getOrCreateBridge(cacheInstance, key);
    }

    public static Path onGetPath(Object handleObj) {
        if (handleObj instanceof LcaRegionHandle h) {
            return h.regionPath();
        }
        return null;
    }

    public static DataInputStream onGetChunkDataInputStream(Object handleObj, Object chunkPosObj) throws IOException {
        if (!(handleObj instanceof LcaRegionHandle h)) {
            return null;
        }
        int[] coords = LcaRegionFileStorage.extractChunkPosCoords(chunkPosObj);
        int worldCx = (h.regionX() << 5) | (coords[0] & 31);
        int worldCz = (h.regionZ() << 5) | (coords[1] & 31);
        return h.storage().getChunkDataInputStream(worldCx, worldCz);
    }

    public static DataOutputStream onGetChunkDataOutputStream(Object handleObj, Object chunkPosObj) throws IOException {
        if (!(handleObj instanceof LcaRegionHandle h)) {
            throw new IOException("Missing LcaRegionHandle on RegionFile bridge");
        }
        int[] coords = LcaRegionFileStorage.extractChunkPosCoords(chunkPosObj);
        int worldCx = (h.regionX() << 5) | (coords[0] & 31);
        int worldCz = (h.regionZ() << 5) | (coords[1] & 31);
        prewarmTrackedChunkBaseFramesOnIoWorker(worldCx, worldCz);
        return h.storage().getChunkDataOutputStream(worldCx, worldCz);
    }

    private static void prewarmTrackedChunkBaseFramesOnIoWorker(int worldCx, int worldCz) {
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt == null) {
            return;
        }
        LcaInMemoryChunkCoordinator coord = rt.chunkCoordinator();
        for (String worldKey : rt.worldSessions().keySet()) {
            LcaInMemoryChunkCoordinator.TrackedChunkColumn col = coord.getTrackedChunk(worldKey, worldCx, worldCz);
            if (col != null) {
                for (int i = 0; i < col.sectionCount(); i++) {
                    io.github.dailystruggle.anvil.LcaSectionBacking sec = col.section(i);
                    if (sec != null && !sec.isUnloaded() && sec.dirtyCount() > 0) {
                        sec.compactFrozenOverlayOffThread(15);
                    }
                }
            }
        }
    }

    public static boolean onDoesChunkExist(Object handleObj, Object chunkPosObj) {
        if (!(handleObj instanceof LcaRegionHandle h)) {
            return false;
        }
        try {
            int[] coords = LcaRegionFileStorage.extractChunkPosCoords(chunkPosObj);
            int worldCx = (h.regionX() << 5) | (coords[0] & 31);
            int worldCz = (h.regionZ() << 5) | (coords[1] & 31);
            return h.storage().doesChunkExist(worldCx, worldCz);
        } catch (IOException e) {
            LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
            String msg = "[LeafAnvil] I/O error probing chunk existence in " + h.regionPath() + ": " + e.getMessage();
            if (rt != null) {
                rt.accessor().log(java.util.logging.Level.WARNING, msg, e);
            } else {
                java.util.logging.Logger.getLogger(NmsRegionStorageInterceptor.class.getName())
                        .log(java.util.logging.Level.WARNING, msg, e);
            }
            throw new java.io.UncheckedIOException(msg, e);
        }
    }

    public static void onClear(Object handleObj, Object chunkPosObj) throws IOException {
        if (!(handleObj instanceof LcaRegionHandle h)) {
            return;
        }
        int[] coords = LcaRegionFileStorage.extractChunkPosCoords(chunkPosObj);
        int worldCx = (h.regionX() << 5) | (coords[0] & 31);
        int worldCz = (h.regionZ() << 5) | (coords[1] & 31);
        h.storage().removeChunk(worldCx, worldCz);
    }

    public static void onFlush(Object handleObj) throws IOException {
        if (handleObj instanceof LcaRegionHandle h) {
            h.storage().flushRegion(h.regionX(), h.regionZ());
        }
    }

    public static void onClose(Object handleObj) throws IOException {
        if (handleObj instanceof LcaRegionHandle h) {
            h.storage().closeRegion(h.regionX(), h.regionZ());
        }
    }

    public static int onGetRecalculateCount(Object handleObj) {
        return 0;
    }

    public static void onWriteByteBuffer(Object handleObj, Object chunkPosObj, ByteBuffer buf) throws IOException {
        if (!(handleObj instanceof LcaRegionHandle h) || buf == null) {
            return;
        }
        int[] coords = LcaRegionFileStorage.extractChunkPosCoords(chunkPosObj);
        int worldCx = (h.regionX() << 5) | (coords[0] & 31);
        int worldCz = (h.regionZ() << 5) | (coords[1] & 31);
        ByteBuffer dup = buf.duplicate();
        if (dup.remaining() < 5) {
            throw new IOException("Truncated Anvil chunk write buffer (" + dup.remaining() + " < 5 bytes) for "
                    + h.regionPath());
        }
        int startPos = dup.position();
        int declaredLen = dup.getInt(startPos);
        int compressionId = dup.get(startPos + 4) & 0xFF;
        if (compressionId == LCA_PREENCODED_VERSION_ID) {
            int payloadOffset = startPos + 5;
            int payloadLen = Math.min(dup.remaining() - 5, Math.max(0, declaredLen - 1));
            if (payloadLen <= 0) {
                payloadLen = dup.remaining() - 5;
            }
            if (payloadLen <= 0) {
                throw new IOException("Empty pre-encoded .lca chunk slab payload for " + h.regionPath());
            }
            byte[] preEncodedSlab = new byte[payloadLen];
            dup.position(payloadOffset);
            dup.get(preEncodedSlab, 0, payloadLen);
            h.storage().commitPreEncodedChunkSlab(worldCx, worldCz, preEncodedSlab);
            return;
        }
        if (compressionId == 3) {
            int payloadOffset = startPos + 5;
            int payloadLen = Math.min(dup.remaining() - 5, Math.max(0, declaredLen - 1));
            if (payloadLen <= 0) {
                payloadLen = dup.remaining() - 5;
            }
            if (payloadLen > 0) {
                ByteBuffer slice = dup.duplicate();
                slice.position(payloadOffset).limit(payloadOffset + payloadLen);
                h.storage().writeChunkNbtBuffer(worldCx, worldCz, slice.slice());
            }
            return;
        }
        byte[] rawNbt = decodeRegionChunkBufferPayload(buf);
        if (rawNbt != null && rawNbt.length > 0) {
            h.storage().writeChunkNbtBytes(worldCx, worldCz, rawNbt);
        }
    }

    private static byte[] decodeRegionChunkBufferPayload(ByteBuffer buf) throws IOException {
        ByteBuffer dup = buf.duplicate();
        if (dup.remaining() < 5) {
            throw new IOException("Truncated Anvil chunk buffer (" + dup.remaining() + " < 5 bytes)");
        }
        int startPos = dup.position();
        int declaredLen = dup.getInt(startPos);
        int compressionId = dup.get(startPos + 4) & 0xFF;
        int payloadOffset = startPos + 5;
        int payloadLen = Math.min(dup.remaining() - 5, Math.max(0, declaredLen - 1));
        if (payloadLen <= 0) {
            payloadLen = dup.remaining() - 5;
        }
        byte[] payload = new byte[payloadLen];
        dup.position(payloadOffset);
        dup.get(payload, 0, payloadLen);
        return LcaRegionFileStorage.decodeAnvilChunkPayload(compressionId, payload, 0, payloadLen);
    }

    // -------------------------------------------------------------------------
    // World & RegionFileStorage Reflective Discovery and Injection
    // -------------------------------------------------------------------------

    /**
     * Resolves the active on-disk dimension directory (`.../overworld`, `.../DIM-1`, etc.) from a live
     * `CraftWorld` or `ServerLevel` handle by inspecting the parent of its `RegionFileStorage.folder`
     * field, falling back to `fallbackDimensionDir` when unavailable.
     */
    public static Path resolveDimensionDirectory(Object worldOrServerLevel, Path fallbackDimensionDir) {
        if (worldOrServerLevel != null) {
            List<Object> storages = discoverRegionFileStorages(worldOrServerLevel);
            for (Object rfs : storages) {
                Path folder = extractFolderField(rfs);
                if (folder != null && folder.getParent() != null) {
                    return folder.getParent();
                }
            }
        }
        return fallbackDimensionDir;
    }

    /**
     * Discovers all `RegionFileStorage` instances on a `CraftWorld`, `ServerLevel`, or storage holder
     * and hooks each one into the matching folder (`region/`, `entities/`, `poi/`) of `session`.
     *
     * @return number of `RegionFileStorage` instances intercepted
     */
    private record InstalledCacheSwap(
            Object regionFileStorage,
            long fieldOffset,
            Object previousCache,
            Object bridgeCache
    ) {
    }

    private static final Map<LcaRegionFileStorage.LcaWorldStorageSession, List<InstalledCacheSwap>>
            INSTALLED_SESSION_SWAPS = new ConcurrentHashMap<>();

    /**
     * Discovers all `RegionFileStorage` instances reachable from `worldOrServerLevel` and installs
     * `.lca` cache bridges mapped to the matching storage (`region/`, `entities/`, `poi/`) in `session`.
     * Rolls back all newly installed bridges if any discovered storage fails installation.
     *
     * @return number of `RegionFileStorage` instances intercepted (`0` on partial or total failure)
     */
    public static int interceptWorldStorages(
            Object worldOrServerLevel, LcaRegionFileStorage.LcaWorldStorageSession session
    ) {
        if (worldOrServerLevel == null || session == null) {
            return 0;
        }
        List<Object> storages = discoverRegionFileStorages(worldOrServerLevel);
        if (storages.isEmpty()) {
            return 0;
        }
        List<InstalledCacheSwap> newlyInstalled = new ArrayList<>(storages.size());
        int hooked = 0;
        try {
            for (Object rfs : storages) {
                Path folder = extractFolderField(rfs);
                LcaRegionFileStorage targetLcaStorage = session.storageForFolder(folder);
                if (!installOnRegionFileStorageInternal(rfs, targetLcaStorage, newlyInstalled)) {
                    rollbackInstalledSwaps(newlyInstalled);
                    return 0;
                }
                hooked++;
            }
        } catch (RuntimeException | Error t) {
            rollbackInstalledSwaps(newlyInstalled);
            throw t;
        }
        if (!newlyInstalled.isEmpty()) {
            INSTALLED_SESSION_SWAPS
                    .computeIfAbsent(session, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .addAll(newlyInstalled);
        }
        return hooked;
    }

    /**
     * Restores the original `regionCache` field on every `RegionFileStorage` intercepted for `session`.
     */
    @SuppressWarnings("deprecation")
    public static void uninstallWorldStorages(LcaRegionFileStorage.LcaWorldStorageSession session) {
        if (session == null) {
            return;
        }
        List<InstalledCacheSwap> swaps = INSTALLED_SESSION_SWAPS.remove(session);
        if (swaps != null) {
            rollbackInstalledSwaps(swaps);
        }
    }

    @SuppressWarnings("deprecation")
    private static void rollbackInstalledSwaps(List<InstalledCacheSwap> swaps) {
        if (UNSAFE == null || swaps == null) {
            return;
        }
        for (int i = swaps.size() - 1; i >= 0; i--) {
            InstalledCacheSwap swap = swaps.get(i);
            Object current = UNSAFE.getObjectVolatile(swap.regionFileStorage(), swap.fieldOffset());
            if (current == swap.bridgeCache()) {
                UNSAFE.putObjectVolatile(swap.regionFileStorage(), swap.fieldOffset(), swap.previousCache());
            }
        }
    }

    /**
     * Installs an intercepting `regionCache` on a single `RegionFileStorage` instance so all chunk
     * reads, writes, scans, flushes, and closes route through `lcaStorage`.
     */
    public static boolean installOnRegionFileStorage(
            Object regionFileStorage, LcaRegionFileStorage lcaStorage
    ) {
        return installOnRegionFileStorageInternal(regionFileStorage, lcaStorage, null);
    }

    @SuppressWarnings("deprecation")
    private static boolean installOnRegionFileStorageInternal(
            Object regionFileStorage,
            LcaRegionFileStorage lcaStorage,
            List<InstalledCacheSwap> recordedSwaps
    ) {
        if (regionFileStorage == null || lcaStorage == null || UNSAFE == null) {
            return false;
        }
        try {
            Field cacheField = findRegionCacheField(regionFileStorage.getClass());
            if (cacheField == null) {
                reportStorageIncompatibility(
                        regionFileStorage.getClass().getName() + "#regionCache",
                        "No regionCache field found on " + regionFileStorage.getClass().getName(),
                        "non-static Long2ObjectLinkedOpenHashMap regionCache field");
                return false;
            }
            cacheField.setAccessible(true);
            Object currentCache = cacheField.get(regionFileStorage);
            if (currentCache != null && currentCache.getClass().getName().contains("$$LeafAnvilCacheBridge")) {
                return true;
            }

            Class<?> regionFileClass = resolveRegionFileClass(regionFileStorage.getClass());
            if (regionFileClass == null || Modifier.isFinal(regionFileClass.getModifiers())) {
                reportStorageIncompatibility(
                        regionFileStorage.getClass().getName() + "#RegionFile",
                        "Could not resolve non-final RegionFile class on " + regionFileStorage.getClass().getName(),
                        "non-final RegionFile or IRegionFile type");
                return false;
            }
            Class<?> cacheClass = currentCache != null ? currentCache.getClass() : cacheField.getType();
            if (Modifier.isFinal(cacheClass.getModifiers()) || cacheClass.isInterface()) {
                reportStorageIncompatibility(
                        cacheClass.getName(),
                        "regionCache type " + cacheClass.getName() + " is final or an interface",
                        "subclassable Long2ObjectLinkedOpenHashMap class");
                return false;
            }

            BridgePair pair = getOrGenerateBridgeClasses(regionFileClass, cacheClass);
            Object newCache = pair.cacheBridgeClass().getDeclaredConstructor().newInstance();
            boolean wrapInOptional = usesOptionalCacheValues(cacheField);
            CacheController controller = new CacheController(
                    lcaStorage, pair.regionFileBridgeClass(), cacheClass, regionFileStorage, wrapInOptional);
            pair.cacheBridgeClass().getField("lcaController").set(newCache, controller);

            long fieldOffset = UNSAFE.objectFieldOffset(cacheField);
            UNSAFE.putObjectVolatile(regionFileStorage, fieldOffset, newCache);
            if (recordedSwaps != null) {
                recordedSwaps.add(new InstalledCacheSwap(regionFileStorage, fieldOffset, currentCache, newCache));
            }
            clearNonExistingRegionFilesSet(regionFileStorage);

            // Flush & close any vanilla/Linear RegionFile instances opened before interception
            if (currentCache != null) {
                closeExistingCachedRegionFiles(currentCache);
            }
            return true;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to install LCA interceptor on "
                    + regionFileStorage.getClass().getName() + ": " + t.getMessage(), t);
        }
    }

    private static void reportStorageIncompatibility(String path, String message, String expected) {
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt != null) {
            rt.bitSuite().recordIncompatibility(
                    net.leaf.anvil.common.bit.LeafAnvilBitSuite.LCA_002_DISK_STORAGE_COMPAT,
                    "NmsRegionStorageInterceptor",
                    path,
                    message,
                    expected);
        }
    }

    private static boolean usesOptionalCacheValues(Field cacheField) {
        if (cacheField == null) {
            return false;
        }
        Type genericType = cacheField.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            for (Type arg : pt.getActualTypeArguments()) {
                if (arg == Optional.class) {
                    return true;
                }
                if (arg instanceof ParameterizedType argPt && argPt.getRawType() == Optional.class) {
                    return true;
                }
                if (arg.getTypeName().startsWith("java.util.Optional<")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void logInterceptorDiagnostic(java.util.logging.Level level, String msg, Throwable t) {
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt != null) {
            rt.accessor().log(level, msg, t);
        } else {
            java.util.logging.Logger.getLogger(NmsRegionStorageInterceptor.class.getName()).log(level, msg, t);
        }
    }

    private static void clearNonExistingRegionFilesSet(Object regionFileStorage) {
        Class<?> cls = regionFileStorage.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        && ("nonExistingRegionFiles".equals(f.getName())
                        || f.getType().getSimpleName().contains("LongLinkedOpenHashSet"))) {
                    try {
                        f.setAccessible(true);
                        Object setObj = f.get(regionFileStorage);
                        if (setObj instanceof Collection<?> col) {
                            synchronized (setObj) {
                                col.clear();
                            }
                        }
                    } catch (Throwable ex) {
                        logInterceptorDiagnostic(
                                java.util.logging.Level.FINE,
                                "[LeafAnvil] Could not clear nonExistingRegionFiles on "
                                        + regionFileStorage.getClass().getName() + ": " + ex.getMessage(),
                                ex);
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static void closeExistingCachedRegionFiles(Object currentCache) {
        try {
            List<?> snapshot;
            synchronized (currentCache) {
                Method valuesMethod = currentCache.getClass().getMethod("values");
                Object valuesObj = valuesMethod.invoke(currentCache);
                snapshot = valuesObj instanceof Collection<?> col
                        ? new ArrayList<>(col)
                        : Collections.emptyList();
                Method clearMethod = currentCache.getClass().getMethod("clear");
                clearMethod.invoke(currentCache);
            }
            for (Object entry : snapshot) {
                Object rf = entry instanceof Optional<?> opt ? opt.orElse(null) : entry;
                Path rfPath = extractRegionFilePath(rf);
                if (rf instanceof AutoCloseable ac) {
                    try {
                        ac.close();
                    } catch (Exception closeEx) {
                        logInterceptorDiagnostic(
                                java.util.logging.Level.WARNING,
                                "[LeafAnvil] Failed to close pre-existing cached RegionFile "
                                        + rfPath + ": " + closeEx.getMessage(),
                                closeEx);
                    }
                }
                if (rfPath != null) {
                    try {
                        if (java.nio.file.Files.exists(rfPath) && java.nio.file.Files.size(rfPath) == 0L) {
                            java.nio.file.Files.deleteIfExists(rfPath);
                        }
                    } catch (Throwable delEx) {
                        logInterceptorDiagnostic(
                                java.util.logging.Level.FINE,
                                "[LeafAnvil] Could not delete 0-byte placeholder region file "
                                        + rfPath + ": " + delEx.getMessage(),
                                delEx);
                    }
                }
            }
        } catch (Throwable cacheEx) {
            logInterceptorDiagnostic(
                    java.util.logging.Level.WARNING,
                    "[LeafAnvil] Failed to drain pre-existing regionCache on "
                            + currentCache.getClass().getName() + ": " + cacheEx.getMessage(),
                    cacheEx);
        }
    }

    private static Path extractRegionFilePath(Object rf) {
        if (rf == null) {
            return null;
        }
        try {
            Method getPath = rf.getClass().getMethod("getPath");
            Object p = getPath.invoke(rf);
            if (p instanceof Path path) {
                return path;
            }
        } catch (Throwable ex) {
            logInterceptorDiagnostic(
                    java.util.logging.Level.FINE,
                    "[LeafAnvil] RegionFile#getPath probe unavailable on " + rf.getClass().getName(),
                    ex);
        }
        return extractFolderField(rf);
    }

    /**
     * Reflectively locates all `RegionFileStorage` instances reachable from `root` (`CraftWorld`,
     * `ServerLevel`, `ChunkMap`, `PersistentEntitySectionManager`, `PoiManager`, `IOWorker`, etc.).
     */
    public static List<Object> discoverRegionFileStorages(Object root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<Object> found = new ArrayList<>(4);
        try {
            Object start = root;
            try {
                Method getHandle = root.getClass().getMethod("getHandle");
                Object handle = getHandle.invoke(root);
                if (handle != null) {
                    start = handle;
                }
            } catch (NoSuchMethodException ignored) {
            }
            if (start.getClass().getSimpleName().contains("RegionFileStorage")) {
                found.add(start);
                return found;
            }
            collectStoragesRecursive(start, found, 0);
        } catch (ReflectiveOperationException ignored) {
        }
        return found;
    }

    @SuppressWarnings("deprecation")
    private static void collectStoragesRecursive(Object target, List<Object> out, int depth)
            throws IllegalAccessException {
        if (target == null || depth > 7) {
            return;
        }
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class && cls != Thread.class && !cls.getName().startsWith("java.")) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> ft = f.getType();
                if (ft.isPrimitive()) {
                    continue;
                }
                String typeName = ft.getSimpleName();
                Object val = null;
                try {
                    f.setAccessible(true);
                    val = f.get(target);
                } catch (Throwable t) {
                    if (UNSAFE != null) {
                        try {
                            val = UNSAFE.getObject(target, UNSAFE.objectFieldOffset(f));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (val == null || val == target) {
                    continue;
                }
                String runtimeName = val.getClass().getSimpleName();
                if (typeName.contains("RegionFileStorage") || runtimeName.contains("RegionFileStorage")) {
                    if (!out.contains(val)) {
                        out.add(val);
                    }
                } else if (depth < 7 && (isStorageHolderType(typeName) || isStorageHolderType(runtimeName))) {
                    collectStoragesRecursive(val, out, depth + 1);
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static boolean isStorageHolderType(String simpleName) {
        return simpleName.contains("ChunkMap")
                || simpleName.contains("ChunkSource")
                || simpleName.contains("ChunkCache")
                || simpleName.contains("EntitySectionManager")
                || simpleName.contains("EntityPersistentStorage")
                || simpleName.contains("EntityStorage")
                || simpleName.contains("PoiManager")
                || simpleName.contains("SectionStorage")
                || simpleName.contains("SimpleRegionStorage")
                || simpleName.contains("IOWorker")
                || simpleName.contains("C2MEStorage")
                || simpleName.contains("StorageThread")
                || simpleName.contains("StorageIoWorker")
                || simpleName.contains("RegionFileIO")
                || simpleName.contains("DataController")
                || simpleName.contains("ChunkTaskScheduler");
    }

    @SuppressWarnings("deprecation")
    public static Path extractFolderField(Object regionFileStorage) {
        if (regionFileStorage == null) {
            return null;
        }
        Class<?> cls = regionFileStorage.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Path.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(regionFileStorage);
                        if (v instanceof Path p) {
                            return p;
                        }
                    } catch (Throwable t) {
                        if (UNSAFE != null) {
                            try {
                                Object v = UNSAFE.getObject(regionFileStorage, UNSAFE.objectFieldOffset(f));
                                if (v instanceof Path p) {
                                    return p;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Field findRegionCacheField(Class<?> storageClass) {
        Class<?> cls = storageClass;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String fName = f.getName();
                String tName = f.getType().getSimpleName();
                if ("regionCache".equals(fName) || tName.contains("Long2ObjectLinkedOpenHashMap")) {
                    return f;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Class<?> resolveRegionFileClass(Class<?> storageClass) {
        Class<?> interfaceCandidate = null;
        Class<?> cls = storageClass;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                Class<?> rt = m.getReturnType();
                if (rt != null && rt != Object.class && rt != void.class
                        && (rt.getSimpleName().contains("RegionFile") || "getRegionFile".equals(m.getName()))) {
                    if (!rt.isInterface()) {
                        return rt;
                    }
                    if (interfaceCandidate == null) {
                        interfaceCandidate = rt;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        try {
            Class<?> nmsRf = Class.forName(
                    "net.minecraft.world.level.chunk.storage.RegionFile",
                    false,
                    storageClass.getClassLoader());
            if (!nmsRf.isInterface() && (interfaceCandidate == null || interfaceCandidate.isAssignableFrom(nmsRf))) {
                return nmsRf;
            }
        } catch (ClassNotFoundException cnfe) {
            logInterceptorDiagnostic(
                    java.util.logging.Level.FINE,
                    "[LeafAnvil] Mojang RegionFile class not present on "
                            + storageClass.getName() + " classloader; using interface candidate",
                    cnfe);
        }
        return interfaceCandidate;
    }

    // -------------------------------------------------------------------------
    // Dynamic JVM Classfile Generator for RegionFile & RegionCache Bridges
    // -------------------------------------------------------------------------

    private record BridgePair(Class<?> regionFileBridgeClass, Class<?> cacheBridgeClass) {
    }

    private static BridgePair getOrGenerateBridgeClasses(Class<?> regionFileClass, Class<?> cacheClass) {
        String key = regionFileClass.getName() + "#" + cacheClass.getName() + "#" + System.identityHashCode(regionFileClass.getClassLoader());
        Class<?> rfBridge = GENERATED_CLASSES.get(key + ":rf");
        Class<?> cacheBridge = GENERATED_CLASSES.get(key + ":cache");
        if (rfBridge != null && cacheBridge != null) {
            return new BridgePair(rfBridge, cacheBridge);
        }
        synchronized (GENERATED_CLASSES) {
            rfBridge = GENERATED_CLASSES.get(key + ":rf");
            cacheBridge = GENERATED_CLASSES.get(key + ":cache");
            if (rfBridge != null && cacheBridge != null) {
                return new BridgePair(rfBridge, cacheBridge);
            }

            BridgeClassLoader loader = new BridgeClassLoader(
                    regionFileClass.getClassLoader(),
                    NmsRegionStorageInterceptor.class.getClassLoader());

            String rfBridgeName = "net.leaf.anvil.generated." + regionFileClass.getSimpleName() + "$$LeafAnvilRfBridge";
            byte[] rfBytes = emitRegionFileBridgeClassBytes(rfBridgeName.replace('.', '/'), regionFileClass);
            rfBridge = loader.define(rfBridgeName, rfBytes);

            String cacheBridgeName = "net.leaf.anvil.generated." + cacheClass.getSimpleName() + "$$LeafAnvilCacheBridge";
            byte[] cacheBytes = emitRegionCacheBridgeClassBytes(cacheBridgeName.replace('.', '/'), cacheClass);
            cacheBridge = loader.define(cacheBridgeName, cacheBytes);

            GENERATED_CLASSES.put(key + ":rf", rfBridge);
            GENERATED_CLASSES.put(key + ":cache", cacheBridge);
            return new BridgePair(rfBridge, cacheBridge);
        }
    }

    private static final class BridgeClassLoader extends ClassLoader {
        private final ClassLoader pluginLoader;

        BridgeClassLoader(ClassLoader parent, ClassLoader pluginLoader) {
            super(parent);
            this.pluginLoader = pluginLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            return pluginLoader.loadClass(name);
        }

        Class<?> define(String binaryName, byte[] classBytes) {
            return defineClass(binaryName, classBytes, 0, classBytes.length);
        }
    }

    private static byte[] emitRegionFileBridgeClassBytes(String internalName, Class<?> superClass) {
        boolean isInterface = superClass.isInterface();
        String superInternal = isInterface ? "java/lang/Object" : superClass.getName().replace('.', '/');
        String interceptorInternal = NmsRegionStorageInterceptor.class.getName().replace('.', '/');

        ClassFileBuilder cb = new ClassFileBuilder(internalName, superInternal);
        if (isInterface) {
            cb.addInterface(superClass.getName().replace('.', '/'));
        }
        cb.addPublicField("lcaHandle", "Ljava/lang/Object;");

        List<Method> candidateMethods = new ArrayList<>();
        if (isInterface) {
            Collections.addAll(candidateMethods, superClass.getMethods());
        } else {
            Class<?> cur = superClass;
            while (cur != null && cur != Object.class && cur != Thread.class) {
                Collections.addAll(candidateMethods, cur.getDeclaredMethods());
                cur = cur.getSuperclass();
            }
            Collections.addAll(candidateMethods, superClass.getMethods());
        }

        java.util.Set<String> emittedSignatures = new java.util.HashSet<>();
        // Inspect methods on superClass/interfaces to override stream, existence, clear, flush, close, and getPath
        for (Method m : candidateMethods) {
            int mods = m.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isFinal(mods) || Modifier.isPrivate(mods)) {
                continue;
            }
            String mName = m.getName();
            Class<?>[] params = m.getParameterTypes();
            Class<?> ret = m.getReturnType();
            String sigKey = mName + "#" + java.util.Arrays.toString(params) + "#" + ret.getName();
            if (!emittedSignatures.add(sigKey)) {
                continue;
            }

            if (params.length == 1 && ret == DataInputStream.class && "getChunkDataInputStream".equals(mName)) {
                String desc = "(" + descriptor(params[0]) + ")Ljava/io/DataInputStream;";
                cb.addDelegateChunkPosMethod(mName, desc, interceptorInternal, "onGetChunkDataInputStream",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/io/DataInputStream;", 0xB0 /* ARETURN */);
            } else if (params.length == 1 && ret == DataOutputStream.class && "getChunkDataOutputStream".equals(mName)) {
                String desc = "(" + descriptor(params[0]) + ")Ljava/io/DataOutputStream;";
                cb.addDelegateChunkPosMethod(mName, desc, interceptorInternal, "onGetChunkDataOutputStream",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/io/DataOutputStream;", 0xB0 /* ARETURN */);
            } else if (params.length == 1 && ret == boolean.class
                    && ("doesChunkExist".equals(mName) || "hasChunk".equals(mName))) {
                String desc = "(" + descriptor(params[0]) + ")Z";
                cb.addDelegateChunkPosMethod(mName, desc, interceptorInternal, "onDoesChunkExist",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", 0xAC /* IRETURN */);
            } else if (params.length == 1 && ret == void.class && "clear".equals(mName)) {
                String desc = "(" + descriptor(params[0]) + ")V";
                cb.addDelegateChunkPosMethod(mName, desc, interceptorInternal, "onClear",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", 0xB1 /* RETURN */);
            } else if (params.length == 0 && ret == void.class && "flush".equals(mName)) {
                cb.addDelegateNoArgMethod("flush", "()V", interceptorInternal, "onFlush",
                        "(Ljava/lang/Object;)V", 0xB1 /* RETURN */);
            } else if (params.length == 0 && ret == void.class && "close".equals(mName)) {
                cb.addDelegateNoArgMethod("close", "()V", interceptorInternal, "onClose",
                        "(Ljava/lang/Object;)V", 0xB1 /* RETURN */);
            } else if (params.length == 0 && ret == Path.class && "getPath".equals(mName)) {
                cb.addDelegateNoArgMethod("getPath", "()Ljava/nio/file/Path;", interceptorInternal, "onGetPath",
                        "(Ljava/lang/Object;)Ljava/nio/file/Path;", 0xB0 /* ARETURN */);
            } else if (params.length == 0 && ret == int.class && "getRecalculateCount".equals(mName)) {
                cb.addDelegateNoArgMethod("getRecalculateCount", "()I", interceptorInternal, "onGetRecalculateCount",
                        "(Ljava/lang/Object;)I", 0xAC /* IRETURN */);
            } else if (params.length == 0 && ret == boolean.class && "recalculateHeader".equals(mName)) {
                cb.addConstMethod("recalculateHeader", "()Z", 1, new byte[] {0x03 /* iconst_0 */, (byte) 0xAC /* ireturn */});
            } else if (params.length == 2 && params[0] == int.class && params[1] == int.class
                    && ret == boolean.class && "isOversized".equals(mName)) {
                cb.addConstMethod("isOversized", "(II)Z", 3, new byte[] {0x03 /* iconst_0 */, (byte) 0xAC /* ireturn */});
            } else if (params.length == 3 && params[0] == int.class && params[1] == int.class && params[2] == boolean.class
                    && ret == void.class && "setOversized".equals(mName)) {
                cb.addConstMethod("setOversized", "(IIZ)V", 4, new byte[] {(byte) 0xB1 /* return */});
            } else if (params.length == 2 && params[0] == int.class && params[1] == int.class
                    && !ret.isPrimitive() && "getOversizedData".equals(mName)) {
                cb.addConstMethod("getOversizedData", "(II)" + descriptor(ret), 3,
                        new byte[] {0x01 /* aconst_null */, (byte) 0xB0 /* areturn */});
            } else if (params.length == 2 && params[1] == ByteBuffer.class && ret == void.class
                    && ("write".equals(mName) || "invokeWriteChunk".equals(mName))) {
                String desc = "(" + descriptor(params[0]) + "Ljava/nio/ByteBuffer;)V";
                cb.addDelegateChunkPosByteBufferMethod(mName, desc, interceptorInternal, "onWriteByteBuffer",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/ByteBuffer;)V");
            }
        }
        return cb.toByteArray();
    }

    private static byte[] emitRegionCacheBridgeClassBytes(String internalName, Class<?> superClass) {
        String superInternal = superClass.getName().replace('.', '/');
        String interceptorInternal = NmsRegionStorageInterceptor.class.getName().replace('.', '/');

        ClassFileBuilder cb = new ClassFileBuilder(internalName, superInternal);
        cb.addPublicField("lcaController", "Ljava/lang/Object;");
        cb.addDefaultConstructorCallingSuper();
        cb.addCacheGetMethod("getAndMoveToFirst", "(J)Ljava/lang/Object;", interceptorInternal);
        cb.addCacheGetMethod("get", "(J)Ljava/lang/Object;", interceptorInternal);
        return cb.toByteArray();
    }

    private static String descriptor(Class<?> cls) {
        return cls.descriptorString();
    }

    private static final class ClassFileBuilder {
        private final String thisInternal;
        private final String superInternal;
        private final List<byte[]> cpEntries = new ArrayList<>();
        private final Map<String, Integer> utf8Index = new HashMap<>();
        private final Map<String, Integer> classIndex = new HashMap<>();
        private final List<Integer> interfaces = new ArrayList<>();
        private final List<FieldRec> fields = new ArrayList<>();
        private final List<MethodRec> methods = new ArrayList<>();

        private record FieldRec(int access, int nameIdx, int descIdx) {
        }

        private record MethodRec(int access, int nameIdx, int descIdx, byte[] codeAttr) {
        }

        ClassFileBuilder(String thisInternal, String superInternal) {
            this.thisInternal = thisInternal;
            this.superInternal = superInternal;
            cpClass(thisInternal);
            cpClass(superInternal);
            cpUtf8("Code");
        }

        void addInterface(String interfaceInternal) {
            interfaces.add(cpClass(interfaceInternal));
        }

        void addConstMethod(String methodName, String methodDesc, int maxLocals, byte[] bytecode) {
            methods.add(new MethodRec(0x0001, cpUtf8(methodName), cpUtf8(methodDesc), wrapCodeAttr(1, maxLocals, bytecode)));
        }

        int cpUtf8(String s) {
            return utf8Index.computeIfAbsent(s, k -> {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(bos);
                    dos.writeByte(1); // CONSTANT_Utf8
                    dos.writeUTF(k);
                    cpEntries.add(bos.toByteArray());
                    return cpEntries.size();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        int cpClass(String internalName) {
            return classIndex.computeIfAbsent(internalName, k -> {
                int nameIdx = cpUtf8(k);
                cpEntries.add(new byte[] {7, (byte) (nameIdx >>> 8), (byte) nameIdx});
                return cpEntries.size();
            });
        }

        int cpNameAndType(String name, String desc) {
            int nIdx = cpUtf8(name);
            int dIdx = cpUtf8(desc);
            cpEntries.add(new byte[] {
                    12,
                    (byte) (nIdx >>> 8), (byte) nIdx,
                    (byte) (dIdx >>> 8), (byte) dIdx
            });
            return cpEntries.size();
        }

        int cpFieldref(String ownerInternal, String name, String desc) {
            int cIdx = cpClass(ownerInternal);
            int ntIdx = cpNameAndType(name, desc);
            cpEntries.add(new byte[] {
                    9,
                    (byte) (cIdx >>> 8), (byte) cIdx,
                    (byte) (ntIdx >>> 8), (byte) ntIdx
            });
            return cpEntries.size();
        }

        int cpMethodref(String ownerInternal, String name, String desc) {
            int cIdx = cpClass(ownerInternal);
            int ntIdx = cpNameAndType(name, desc);
            cpEntries.add(new byte[] {
                    10,
                    (byte) (cIdx >>> 8), (byte) cIdx,
                    (byte) (ntIdx >>> 8), (byte) ntIdx
            });
            return cpEntries.size();
        }

        void addPublicField(String name, String desc) {
            fields.add(new FieldRec(0x0001 /* ACC_PUBLIC */, cpUtf8(name), cpUtf8(desc)));
        }

        void addDefaultConstructorCallingSuper() {
            int superInitRef = cpMethodref(superInternal, "<init>", "()V");
            byte[] bytecode = new byte[] {
                    0x2A, // aload_0
                    (byte) 0xB7, (byte) (superInitRef >>> 8), (byte) superInitRef, // invokespecial super.<init>
                    (byte) 0xB1 // return
            };
            methods.add(new MethodRec(0x0001, cpUtf8("<init>"), cpUtf8("()V"), wrapCodeAttr(1, 1, bytecode)));
        }

        void addDelegateNoArgMethod(
                String methodName,
                String methodDesc,
                String targetOwner,
                String targetName,
                String targetDesc,
                int returnOpcode
        ) {
            int fRef = cpFieldref(thisInternal, "lcaHandle", "Ljava/lang/Object;");
            int mRef = cpMethodref(targetOwner, targetName, targetDesc);
            byte[] bytecode = new byte[] {
                    0x2A, // aload_0
                    (byte) 0xB4, (byte) (fRef >>> 8), (byte) fRef, // getfield this.lcaHandle
                    (byte) 0xB8, (byte) (mRef >>> 8), (byte) mRef, // invokestatic targetOwner.targetName
                    (byte) returnOpcode
            };
            methods.add(new MethodRec(0x0001, cpUtf8(methodName), cpUtf8(methodDesc), wrapCodeAttr(2, 1, bytecode)));
        }

        void addDelegateChunkPosMethod(
                String methodName,
                String methodDesc,
                String targetOwner,
                String targetName,
                String targetDesc,
                int returnOpcode
        ) {
            int fRef = cpFieldref(thisInternal, "lcaHandle", "Ljava/lang/Object;");
            int mRef = cpMethodref(targetOwner, targetName, targetDesc);
            byte[] bytecode = new byte[] {
                    0x2A, // aload_0
                    (byte) 0xB4, (byte) (fRef >>> 8), (byte) fRef, // getfield this.lcaHandle
                    0x2B, // aload_1 (ChunkPos)
                    (byte) 0xB8, (byte) (mRef >>> 8), (byte) mRef, // invokestatic targetOwner.targetName
                    (byte) returnOpcode
            };
            methods.add(new MethodRec(0x0001, cpUtf8(methodName), cpUtf8(methodDesc), wrapCodeAttr(3, 2, bytecode)));
        }

        void addDelegateChunkPosByteBufferMethod(
                String methodName,
                String methodDesc,
                String targetOwner,
                String targetName,
                String targetDesc
        ) {
            int fRef = cpFieldref(thisInternal, "lcaHandle", "Ljava/lang/Object;");
            int mRef = cpMethodref(targetOwner, targetName, targetDesc);
            byte[] bytecode = new byte[] {
                    0x2A, // aload_0
                    (byte) 0xB4, (byte) (fRef >>> 8), (byte) fRef, // getfield this.lcaHandle
                    0x2B, // aload_1 (ChunkPos)
                    0x2C, // aload_2 (ByteBuffer)
                    (byte) 0xB8, (byte) (mRef >>> 8), (byte) mRef, // invokestatic targetOwner.targetName
                    (byte) 0xB1 // return
            };
            methods.add(new MethodRec(0x0001, cpUtf8(methodName), cpUtf8(methodDesc), wrapCodeAttr(3, 3, bytecode)));
        }

        void addCacheGetMethod(String methodName, String methodDesc, String targetOwner) {
            int fRef = cpFieldref(thisInternal, "lcaController", "Ljava/lang/Object;");
            int mRef = cpMethodref(targetOwner, "onCacheGet", "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;");
            byte[] bytecode = new byte[] {
                    0x2A, // aload_0 (this cache instance)
                    0x2A, // aload_0
                    (byte) 0xB4, (byte) (fRef >>> 8), (byte) fRef, // getfield this.lcaController
                    0x1F, // lload_1 (long key)
                    (byte) 0xB8, (byte) (mRef >>> 8), (byte) mRef, // invokestatic onCacheGet
                    (byte) 0xB0 // areturn
            };
            methods.add(new MethodRec(0x0001, cpUtf8(methodName), cpUtf8(methodDesc), wrapCodeAttr(4, 3, bytecode)));
        }

        private byte[] wrapCodeAttr(int maxStack, int maxLocals, byte[] bytecode) {
            try {
                int codeUtf8 = cpUtf8("Code");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(bos);
                dos.writeShort(codeUtf8);
                dos.writeInt(12 + bytecode.length);
                dos.writeShort(maxStack);
                dos.writeShort(maxLocals);
                dos.writeInt(bytecode.length);
                dos.write(bytecode);
                dos.writeShort(0); // exception table length
                dos.writeShort(0); // attributes count
                return bos.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        byte[] toByteArray() {
            try {
                int thisClassIdx = cpClass(thisInternal);
                int superClassIdx = cpClass(superInternal);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(bos);
                dos.writeInt(0xCAFEBABE);
                dos.writeShort(0);
                dos.writeShort(61); // Java 17 classfile version
                dos.writeShort(cpEntries.size() + 1);
                for (byte[] entry : cpEntries) {
                    dos.write(entry);
                }
                dos.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
                dos.writeShort(thisClassIdx);
                dos.writeShort(superClassIdx);
                dos.writeShort(interfaces.size()); // interfaces count
                for (int ifaceIdx : interfaces) {
                    dos.writeShort(ifaceIdx);
                }
                dos.writeShort(fields.size());
                for (FieldRec f : fields) {
                    dos.writeShort(f.access());
                    dos.writeShort(f.nameIdx());
                    dos.writeShort(f.descIdx());
                    dos.writeShort(0);
                }
                dos.writeShort(methods.size());
                for (MethodRec m : methods) {
                    dos.writeShort(m.access());
                    dos.writeShort(m.nameIdx());
                    dos.writeShort(m.descIdx());
                    dos.writeShort(1); // 1 attribute (Code)
                    dos.write(m.codeAttr());
                }
                dos.writeShort(0); // class attributes count
                return bos.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Unsafe resolveUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
