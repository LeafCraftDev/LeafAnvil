package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.LcaSectionBacking.CompactionSnapshot;
import io.github.dailystruggle.anvil.LcaSectionBacking.EpochCommitFence;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import net.leaf.anvil.agent.LcaAgentSectionCarrier;
import net.leaf.anvil.common.net.LcaMultiTargetConsolidator;
import net.leaf.anvil.common.net.LcaOrderlessDictTable;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.KnownSlotBitSet;
import net.leaf.anvil.common.net.LcaPacketDifferentialProfiler;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.BatchChunkEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ChunkDigestEntry;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientOrderedBatchCommitQueue;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientRevisitApplyResult;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryPatchDelta;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.HydratedClientChunkCommit;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ServerboundLcaChunkDigestPayload;

import io.github.dailystruggle.server.api.ManagedBlockingIoExecutor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Coordinates live in-memory {@link LcaSectionBacking} lifecycle (`leafanvil-ADR-002` Part 1B) and
 * negotiated `.lca` wire streaming (`leafanvil-ADR-003` Part 2B) across Fabric, NeoForge, and
 * Paper/Folia (`Moonrise` `SimpleRandomTickList` delta synchronization).
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, leafanvil-ADR-006, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002, REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaInMemoryChunkCoordinator {

    /**
     * Default wire byte budget credited per vanilla {@code PlayerChunkSender} chunk slot (`10 KiB`).
     */
    public static final int DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT = 10 * 1024;

    /**
     * Default per-tick wire byte ceiling (`32 KiB`) that prevents revisit-to-new-terrain whip-saw
     * when {@code PlayerChunkSender} has ramped {@code desiredChunksPerTick} during cached traversal.
     */
    public static final int DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES = 32 * 1024;

    /**
     * Minimum per-tick wire byte budget floor (`4 KiB`) when at least one vanilla chunk credit is granted.
     */
    public static final int MIN_TICK_WIRE_BUDGET_BYTES = 4 * 1024;

    /**
     * Default bounded capacity of dirty chunks holding uncompacted sparse block-change modifiers
     * in the write-back `DirtyChunkLruCache` (`leafanvil-ADR-006`).
     */
    public static final int DEFAULT_MAX_DIRTY_CHUNKS = 512;

    @Deprecated
    public static final int DEFAULT_MAX_HOT_3D_SLABS = DEFAULT_MAX_DIRTY_CHUNKS;

    public static final int DEFAULT_AIMD_DECAY_NUMERATOR = 1;
    public static final int DEFAULT_AIMD_DECAY_SHIFT = 1;

    private static final int BATCH_MAGIC = 0x4C434142; // "LCAB"
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(LcaInMemoryChunkCoordinator.class.getName());

    private static final ThreadLocal<Boolean> OUTBOUND_LCA_BATCH_BYPASS_ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<ChunkColumnKey, TrackedChunkColumn> activeChunks = new ConcurrentHashMap<>();
    private final Map<ChunkColumnKey, PendingChunkLoad> pendingChunkLoads = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkColumnKey> pendingChunkFifo = new ConcurrentLinkedQueue<>();
    private final Set<ChunkColumnKey> inFlightChunkKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<CompletedChunkCompaction> completedChunkCompactions =
            new ConcurrentLinkedQueue<>();
    private final Set<ChunkColumnKey> dirtyOverlayChunkKeys = ConcurrentHashMap.newKeySet();
    private final Map<RegionFenceKey, EpochCommitFence> regionFences = new ConcurrentHashMap<>();
    private final Map<UUID, NegotiatedSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<PlayerRegionKey, ServerboundLcaChunkDigestPayload> playerRegionDigests =
            new ConcurrentHashMap<>();
    private final Map<UUID, KnownSlotBitSet> playerKnownDictSlots = new ConcurrentHashMap<>();
    private final LcaPacketDifferentialProfiler packetDifferentialProfiler =
            new LcaPacketDifferentialProfiler();
    private final LcaOrderlessDictTable orderlessDictTable = new LcaOrderlessDictTable(1);
    private final LcaOrderlessDictTable clientOrderlessDictTable = new LcaOrderlessDictTable(1);
    private volatile int maxServerUploadKbps = 0;
    private final AtomicInteger activeLcaPlayerCount = new AtomicInteger();
    private volatile boolean interServerConsumerActive;
    private final ClientChunkSlabCache clientSlabCache;
    private final ClientOrderedBatchCommitQueue clientCommitQueue = new ClientOrderedBatchCommitQueue();
    private final boolean c2meDetected;
    private final int compactionWorkerThreads;
    public record WorldVoxelStorageBinding(
            LcaRegionFileStorage voxelStorage,
            Function<String, Integer> canonicalToRuntimeId,
            IntFunction<String> runtimeIdToCanonical
    ) {}

    public record DirectCoWSaveTicket(
            ChunkColumnKey key,
            TrackedChunkColumn column,
            CompactionSnapshot[] sectionSnapshots,
            int flushMask,
            byte[][] updatedSectionSkyLightOrNull,
            byte[][] updatedSectionBlockLightOrNull
    ) {
        public DirectCoWSaveTicket(
                ChunkColumnKey key,
                TrackedChunkColumn column,
                CompactionSnapshot[] sectionSnapshots,
                int flushMask
        ) {
            this(key, column, sectionSnapshots, flushMask, null, null);
        }

        public DirectCoWSaveTicket withSectionLightOverrides(
                byte[][] skyLightOverrides,
                byte[][] blockLightOverrides,
                int additionalLightMask
        ) {
            return new DirectCoWSaveTicket(
                    key,
                    column,
                    sectionSnapshots,
                    flushMask | additionalLightMask,
                    skyLightOverrides,
                    blockLightOverrides
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DirectCoWSaveTicket other)) {
                return false;
            }
            return java.util.Objects.equals(this.key, other.key)
                    && java.util.Objects.equals(this.column, other.column)
                    && java.util.Arrays.equals(this.sectionSnapshots, other.sectionSnapshots)
                    && this.flushMask == other.flushMask
                    && java.util.Arrays.deepEquals(this.updatedSectionSkyLightOrNull, other.updatedSectionSkyLightOrNull)
                    && java.util.Arrays.deepEquals(this.updatedSectionBlockLightOrNull, other.updatedSectionBlockLightOrNull);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.key);
            result = 31 * result + java.util.Objects.hashCode(this.column);
            result = 31 * result + java.util.Arrays.hashCode(this.sectionSnapshots);
            result = 31 * result + this.flushMask;
            result = 31 * result + java.util.Arrays.deepHashCode(this.updatedSectionSkyLightOrNull);
            result = 31 * result + java.util.Arrays.deepHashCode(this.updatedSectionBlockLightOrNull);
            return result;
        }

        @Override
        public String toString() {
            return "DirectCoWSaveTicket[key=" + this.key
                    + ", column=" + this.column
                    + ", sectionSnapshots=" + java.util.Arrays.toString(this.sectionSnapshots)
                    + ", flushMask=" + this.flushMask
                    + ", updatedSectionSkyLightOrNull=" + java.util.Arrays.deepToString(this.updatedSectionSkyLightOrNull)
                    + ", updatedSectionBlockLightOrNull=" + java.util.Arrays.deepToString(this.updatedSectionBlockLightOrNull)
                    + "]";
        }
    }

    private record AgentSectionRef(
            ChunkColumnKey key,
            TrackedChunkColumn column,
            int sectionIndex,
            WeakReference<Object> sectionRef
    ) {}

    private final BoundedCompactionDispatcher compactionExecutor;
    private final AtomicInteger activeBackgroundTasks = new AtomicInteger();
    private final Map<String, WorldVoxelStorageBinding> worldStorageBindings = new ConcurrentHashMap<>();
    private final Map<Integer, List<AgentSectionRef>> agentSectionIndex = new ConcurrentHashMap<>();
    private final Object dirtyCacheLock = new Object();
    private final LinkedHashMap<ChunkColumnKey, TrackedChunkColumn> dirtyChunkLru =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Set<ChunkColumnKey> flushingDirtyChunks = ConcurrentHashMap.newKeySet();
    private volatile int maxDirtyChunks = DEFAULT_MAX_DIRTY_CHUNKS;
    private volatile int defaultGlobalPaletteBits = 15;
    private volatile int aimdAdditiveIncrement = LcaSectionBacking.DEFAULT_AIMD_ADDITIVE_INCREMENT;
    private volatile int aimdDecayNumerator = DEFAULT_AIMD_DECAY_NUMERATOR;
    private volatile int aimdDecayShift = DEFAULT_AIMD_DECAY_SHIFT;
    private volatile boolean clientMode;

    /**
     * Enables client-only mode, bypassing server-side dirty-chunk LRU victim scanning and
     * background overlay compaction on Render-thread block updates (`S-005`).
     */
    public void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
    }

    public boolean isClientMode() {
        return this.clientMode;
    }

    public LcaInMemoryChunkCoordinator(ClientChunkSlabCache clientSlabCache) {
        this(clientSlabCache, detectC2mePresent(), null);
    }

    public LcaInMemoryChunkCoordinator(ClientChunkSlabCache clientSlabCache, boolean c2mePresent) {
        this(clientSlabCache, c2mePresent, null);
    }

    public LcaInMemoryChunkCoordinator(
            ClientChunkSlabCache clientSlabCache,
            boolean c2mePresent,
            Executor backingExecutor
    ) {
        this.clientSlabCache = Objects.requireNonNull(clientSlabCache, "clientSlabCache");
        this.c2meDetected = c2mePresent;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.compactionWorkerThreads = c2mePresent
                ? Math.max(1, Math.min(2, cores / 6))
                : Math.max(1, Math.min(4, cores / 4));
        int workerPriority = c2mePresent
                ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
                : Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1);
        this.compactionExecutor = new BoundedCompactionDispatcher(
                backingExecutor,
                this.compactionWorkerThreads,
                512,
                workerPriority);
    }

    /**
     * Bounded-concurrency, bounded-queue compaction facade over a caller-supplied blocking-I/O
     * {@link Executor} (falling back to a privately owned {@link ManagedBlockingIoExecutor} only
     * when constructed standalone outside a {@link LeafAnvilRuntime}). Preserves per-coordinator
     * parallelism caps so compaction bursts cannot starve RTP probes sharing the host pool, and
     * provides a deterministic shutdown drain barrier ({@code S-002}, {@code S-005}).
     */
    private static final class BoundedCompactionDispatcher implements Executor {
        private final Executor delegate;
        private final ManagedBlockingIoExecutor ownedFallback;
        private final int maxConcurrency;
        private final int maxQueued;
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private final Object lock = new Object();
        private int inFlight;
        private volatile boolean shutdown;

        BoundedCompactionDispatcher(
                Executor backingExecutor,
                int maxConcurrency,
                int maxQueued,
                int workerPriority
        ) {
            this.maxConcurrency = Math.max(1, maxConcurrency);
            this.maxQueued = Math.max(1, maxQueued);
            if (backingExecutor != null) {
                this.delegate = backingExecutor;
                this.ownedFallback = null;
            } else {
                this.ownedFallback = new ManagedBlockingIoExecutor(
                        "LeafAnvil-Compactor", this.maxConcurrency, workerPriority);
                this.delegate = this.ownedFallback;
            }
        }

        boolean isShutdown() {
            return shutdown;
        }

        @Override
        public void execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            boolean dispatchNow = false;
            synchronized (lock) {
                if (shutdown) {
                    throw new RejectedExecutionException("LeafAnvil compaction dispatcher is shut down");
                }
                if (inFlight < maxConcurrency) {
                    inFlight++;
                    dispatchNow = true;
                } else if (pending.size() < maxQueued) {
                    pending.addLast(task);
                } else {
                    throw new RejectedExecutionException("LeafAnvil compaction queue is full");
                }
            }
            if (dispatchNow) {
                submitToDelegate(task);
            }
        }

        private void submitToDelegate(Runnable firstTask) {
            try {
                delegate.execute(() -> runLoop(firstTask));
            } catch (RuntimeException ex) {
                synchronized (lock) {
                    inFlight--;
                    if (inFlight == 0) {
                        lock.notifyAll();
                    }
                }
                throw ex;
            }
        }

        private void runLoop(Runnable current) {
            Runnable task = current;
            while (task != null) {
                try {
                    task.run();
                } finally {
                    synchronized (lock) {
                        task = shutdown ? null : pending.pollFirst();
                        if (task == null) {
                            inFlight--;
                            if (inFlight == 0) {
                                lock.notifyAll();
                            }
                        }
                    }
                }
            }
        }

        void shutdownAndDrain(long timeout, TimeUnit unit) {
            long deadlineNanos = System.nanoTime() + Math.max(0L, unit.toNanos(timeout));
            synchronized (lock) {
                shutdown = true;
                pending.clear();
                while (inFlight > 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    try {
                        lock.wait(millis, nanos);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (ownedFallback != null) {
                ownedFallback.shutdownAndDrain(Duration.ofMillis(Math.max(100L, unit.toMillis(timeout))));
            }
        }
    }

    /**
     * Detects whether {@code C2ME} ({@code com.ishland.c2me.base.common.GlobalExecutors}) is loaded
     * on the current runtime so {@code LeafAnvil}'s background compaction pool bounds concurrency
     * and thread priority cooperatively without starving {@code C2ME} chunk I/O workers.
     */
    public static boolean detectC2mePresent() {
        String[] resourceProbes = {
            "com/ishland/c2me/base/common/GlobalExecutors.class",
            "com/ishland/c2me/C2MEMod.class",
            "com/ishland/c2me/notickvd/common/NoTickViewDistance.class"
        };
        ClassLoader[] loaders = {
            LcaInMemoryChunkCoordinator.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            for (String res : resourceProbes) {
                if (cl.getResource(res) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isC2meDetected() {
        return c2meDetected;
    }

    public int compactionWorkerThreadCount() {
        return compactionWorkerThreads;
    }

    public int inFlightCompactionCount() {
        return activeBackgroundTasks.get();
    }

    public int completedCompactionQueueSize() {
        return completedChunkCompactions.size();
    }

    /**
     * Completed off-thread chunk section indexing result ready for $O(1)$ attachment and
     * {@code ZeroBitStorage} eviction on the owning server/region thread.
     */
    public record CompletedChunkCompaction(
            String worldKey,
            int chunkX,
            int chunkZ,
            WeakReference<Object> chunkHandleRef,
            Object[] nmsSections,
            LcaSectionBridge.RawSectionSnapshot[] snapshots,
            LcaSectionBacking[] builtBackings,
            long enqueuedTick,
            RawChunkFrameSnapshot coldSnapshotOrNull
    ) {
        public CompletedChunkCompaction(
                String worldKey,
                int chunkX,
                int chunkZ,
                WeakReference<Object> chunkHandleRef,
                Object[] nmsSections,
                LcaSectionBridge.RawSectionSnapshot[] snapshots,
                LcaSectionBacking[] builtBackings,
                long enqueuedTick
        ) {
            this(worldKey, chunkX, chunkZ, chunkHandleRef, nmsSections, snapshots, builtBackings, enqueuedTick, null);
        }

        public CompletedChunkCompaction {
            worldKey = normalizeWorldKey(worldKey);
        }

        public Object chunkHandle() {
            return chunkHandleRef != null ? chunkHandleRef.get() : null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CompletedChunkCompaction other)) {
                return false;
            }
            return java.util.Objects.equals(this.worldKey, other.worldKey)
                    && this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && java.util.Objects.equals(this.chunkHandleRef, other.chunkHandleRef)
                    && java.util.Arrays.equals(this.nmsSections, other.nmsSections)
                    && java.util.Arrays.equals(this.snapshots, other.snapshots)
                    && java.util.Arrays.equals(this.builtBackings, other.builtBackings)
                    && this.enqueuedTick == other.enqueuedTick
                    && java.util.Objects.equals(this.coldSnapshotOrNull, other.coldSnapshotOrNull);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.worldKey);
            result = 31 * result + this.chunkX;
            result = 31 * result + this.chunkZ;
            result = 31 * result + java.util.Objects.hashCode(this.chunkHandleRef);
            result = 31 * result + java.util.Arrays.hashCode(this.nmsSections);
            result = 31 * result + java.util.Arrays.hashCode(this.snapshots);
            result = 31 * result + java.util.Arrays.hashCode(this.builtBackings);
            result = 31 * result + Long.hashCode(this.enqueuedTick);
            result = 31 * result + java.util.Objects.hashCode(this.coldSnapshotOrNull);
            return result;
        }

        @Override
        public String toString() {
            return "CompletedChunkCompaction[worldKey=" + this.worldKey
                    + ", chunkX=" + this.chunkX
                    + ", chunkZ=" + this.chunkZ
                    + ", chunkHandleRef=" + this.chunkHandleRef
                    + ", nmsSections=" + java.util.Arrays.toString(this.nmsSections)
                    + ", snapshots=" + java.util.Arrays.toString(this.snapshots)
                    + ", builtBackings=" + java.util.Arrays.toString(this.builtBackings)
                    + ", enqueuedTick=" + this.enqueuedTick
                    + ", coldSnapshotOrNull=" + this.coldSnapshotOrNull
                    + "]";
        }
    }

    public record ChunkColumnKey(String worldKey, int chunkX, int chunkZ) {
        public ChunkColumnKey {
            worldKey = normalizeWorldKey(worldKey);
        }
    }

    /**
     * 3D chunk-slab coordinate key `(worldKey, chunkX, sectionIndex, chunkZ)` addressing a single
     * `16x16x16` vertical section in the AIMD edit-count-scored LRU cache (`leafanvil-ADR-006`).
     */
    public record SectionSlab3dKey(String worldKey, int chunkX, int sectionIndex, int chunkZ) {
        public SectionSlab3dKey {
            worldKey = normalizeWorldKey(worldKey);
        }
    }

    /**
     * Lightweight deferred chunk entry queued in $O(1)$ on `CHUNK_LOAD` so section indexing and
     * `ZeroBitStorage` eviction execute under a strict per-tick budget after the initial burst
     * quiet window (`leafanvil-ADR-002`).
     */
    public record PendingChunkLoad(
            String worldKey,
            int chunkX,
            int chunkZ,
            WeakReference<Object> chunkHandleRef,
            long enqueuedTick
    ) {
        public PendingChunkLoad(
                String worldKey,
                int chunkX,
                int chunkZ,
                Object chunkHandle,
                long enqueuedTick
        ) {
            this(normalizeWorldKey(worldKey), chunkX, chunkZ, new WeakReference<>(chunkHandle), enqueuedTick);
        }

        public PendingChunkLoad {
            worldKey = normalizeWorldKey(worldKey);
        }

        public Object chunkHandle() {
            return chunkHandleRef != null ? chunkHandleRef.get() : null;
        }
    }

    public record RegionFenceKey(String worldKey, int regionX, int regionZ) {
        public RegionFenceKey {
            worldKey = normalizeWorldKey(worldKey);
        }
    }

    public record PlayerRegionKey(UUID playerId, String worldKey, int regionX, int regionZ) {
        public PlayerRegionKey {
            Objects.requireNonNull(playerId, "playerId");
            worldKey = normalizeWorldKey(worldKey);
        }
    }

    /**
     * Live server or client chunk column retaining an immutable cold `.lca` `ChunkFrame` baseline
     * (`0` slots in `DirtyChunkLruCache` when clean) and sparse per-section dirty cell maps
     * (`leafanvil-ADR-005`, `leafanvil-ADR-006`).
     */
    public static final class TrackedChunkColumn {
        private final String worldKey;
        private final int chunkX;
        private final int chunkZ;
        private final int initialTick32;
        private final LcaSectionBacking[] sections;
        private volatile RawChunkFrameSnapshot coldSnapshot;
        private volatile byte[] chunkFrame;
        private volatile int[] sectionOffsets;
        private volatile LcaRegionFileStorage backingStorage;
        private volatile Function<String, Integer> canonicalToRuntimeId;
        private volatile IntFunction<String> runtimeIdToCanonical;
        private volatile MoonriseTickingListSynchronizer[] moonriseSynchronizers;
        private volatile long lastUpdateTick;
        private volatile long baselineSecDed64;
        private volatile boolean baselineComputed;
        private volatile int dirtySectionBitmask32;
        private volatile boolean unloaded;

        public TrackedChunkColumn(
                String worldKey,
                int chunkX,
                int chunkZ,
                LcaSectionBacking[] sections,
                long initialTick
        ) {
            this.worldKey = normalizeWorldKey(worldKey);
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.initialTick32 = (int) initialTick;
            this.sections = Objects.requireNonNull(sections, "sections").clone();
            this.lastUpdateTick = initialTick;
            this.dirtySectionBitmask32 = 0;
        }

        void bindColdChunkFrame(
                RawChunkFrameSnapshot snapshot,
                LcaRegionFileStorage storageOrNull,
                Function<String, Integer> canonicalToRuntimeOrNull,
                IntFunction<String> runtimeToCanonicalOrNull
        ) {
            this.backingStorage = storageOrNull;
            this.canonicalToRuntimeId = canonicalToRuntimeOrNull;
            this.runtimeIdToCanonical = runtimeToCanonicalOrNull;
            if (snapshot != null) {
                this.coldSnapshot = snapshot;
                this.chunkFrame = snapshot.chunkFrame();
                this.sectionOffsets = snapshot.computeSectionRelStarts();
            }
        }

        public RawChunkFrameSnapshot coldSnapshot() {
            return coldSnapshot;
        }

        public byte[] coldChunkFrame() {
            return chunkFrame;
        }

        public int[] sectionOffsets() {
            int[] offs = sectionOffsets;
            return offs != null ? offs.clone() : null;
        }

        public LcaRegionFileStorage backingStorage() {
            return backingStorage;
        }

        public Function<String, Integer> canonicalToRuntimeId() {
            return canonicalToRuntimeId;
        }

        public IntFunction<String> runtimeIdToCanonical() {
            return runtimeIdToCanonical;
        }

        public boolean isUnloaded() {
            return unloaded;
        }

        void markColumnUnloaded() {
            this.unloaded = true;
        }

        public int totalAimdEditScore() {
            long sum = 0L;
            for (LcaSectionBacking sec : sections) {
                if (sec != null) {
                    sum += sec.editScore();
                }
            }
            return (int) Math.min(Integer.MAX_VALUE, sum);
        }

        public boolean hasAnyDirtyOverlay() {
            if (dirtySectionBitmask32 != 0) {
                for (LcaSectionBacking sec : sections) {
                    if (sec != null && (sec.dirtyCount() > 0 || hasNonEmptyFrozen(sec))) {
                        return true;
                    }
                }
            }
            for (LcaSectionBacking sec : sections) {
                if (sec != null && (sec.dirtyCount() > 0 || hasNonEmptyFrozen(sec))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasNonEmptyFrozen(LcaSectionBacking sec) {
            LcaSectionBacking.OverlayTable frozen = sec.frozenOverlay();
            return frozen != null && frozen.dirtyCount() > 0;
        }

        void markSectionMutated(int sectionIndex, long tick) {
            if (sectionIndex >= 0 && sectionIndex < 32) {
                synchronized (this) {
                    this.dirtySectionBitmask32 |= (1 << sectionIndex);
                    if (tick > this.lastUpdateTick) {
                        this.lastUpdateTick = tick;
                    }
                }
            }
        }

        synchronized void installFlushedColdChunkFrame(RawChunkFrameSnapshot updatedSnapshot, int flushedMask) {
            if (updatedSnapshot != null) {
                this.coldSnapshot = updatedSnapshot;
                this.chunkFrame = updatedSnapshot.chunkFrame();
                this.sectionOffsets = updatedSnapshot.computeSectionRelStarts();
                this.lastUpdateTick = Math.max(this.lastUpdateTick, updatedSnapshot.lastUpdateTick());
            }
            int remainingDirtyMask = 0;
            for (int i = 0; i < sections.length && i < 32; i++) {
                LcaSectionBacking sec = sections[i];
                if (sec != null && (sec.dirtyCount() > 0 || hasNonEmptyFrozen(sec))) {
                    remainingDirtyMask |= (1 << i);
                }
            }
            this.dirtySectionBitmask32 = remainingDirtyMask;
            EntryFullChunk refreshed = toWireFullChunkEntry(this.lastUpdateTick);
            this.baselineSecDed64 = refreshed.secDedSyndrome64();
            this.baselineComputed = true;
        }

        private long ensureBaselineSecDed64() {
            if (!this.baselineComputed) {
                synchronized (this) {
                    if (!this.baselineComputed) {
                        EntryFullChunk initialEntry = toWireFullChunkEntry(this.initialTick32);
                        this.baselineSecDed64 = initialEntry.secDedSyndrome64();
                        this.baselineComputed = true;
                    }
                }
            }
            return this.baselineSecDed64;
        }

        public String worldKey() {
            return worldKey;
        }

        public int chunkX() {
            return chunkX;
        }

        public int chunkZ() {
            return chunkZ;
        }

        public int sectionCount() {
            return sections.length;
        }

        public LcaSectionBacking section(int index) {
            return sections[index];
        }

        public LcaSectionBacking[] sections() {
            return sections.clone();
        }

        public synchronized MoonriseTickingListSynchronizer moonriseSynchronizer(int index) {
            if (index < 0 || index >= this.sections.length) {
                return null;
            }
            MoonriseTickingListSynchronizer[] arr = this.moonriseSynchronizers;
            if (arr != null && arr[index] != null) {
                return arr[index];
            }
            MoonriseTickingListSynchronizer lazy = new MoonriseTickingListSynchronizer(null, null, null, null);
            setMoonriseSynchronizer(index, lazy);
            return lazy;
        }

        public synchronized void setMoonriseSynchronizer(int index, MoonriseTickingListSynchronizer sync) {
            if (sync == null && this.moonriseSynchronizers == null) {
                return;
            }
            MoonriseTickingListSynchronizer[] arr = this.moonriseSynchronizers;
            if (arr == null) {
                arr = new MoonriseTickingListSynchronizer[this.sections.length];
                this.moonriseSynchronizers = arr;
            }
            arr[index] = sync;
            if (sync != null && sections[index] != null) {
                sections[index].setTickingCoordinateCallback(sync);
            }
        }

        public long lastUpdateTick() {
            return lastUpdateTick;
        }

        public long baselineSecDed64() {
            return ensureBaselineSecDed64();
        }

        public int dirtySectionBitmask32() {
            return dirtySectionBitmask32;
        }

        /**
         * Instant $O(1)$ sparse dirty cell write when {@code prevStateId19} is already known by NMS / carrier.
         */
        public synchronized void recordDirtyVoxel(
                int sectionIndex,
                int pos12,
                int prevStateId19,
                int newStateId19,
                long currentTick
        ) {
            if (!this.baselineComputed) {
                ensureBaselineSecDed64();
            }
            LcaSectionBacking sec = sections[sectionIndex];
            this.dirtySectionBitmask32 |= (1 << sectionIndex);
            this.lastUpdateTick = currentTick;
            sec.recordDirtyVoxel(pos12, prevStateId19, newStateId19, currentTick);
        }

        /**
         * Masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry, writes the state
         * into section {@code sectionIndex}, and updates the column's dirty section bitmask and tick.
         */
        public synchronized int mutateVoxel(
                int sectionIndex,
                int x,
                int y,
                int z,
                int stateId19,
                long currentTick
        ) {
            if (!this.baselineComputed) {
                ensureBaselineSecDed64();
            }
            LcaSectionBacking sec = sections[sectionIndex];
            this.dirtySectionBitmask32 |= (1 << sectionIndex);
            this.lastUpdateTick = currentTick;
            return sec.setAndGetPreviousStateId(x & 15, y & 15, z & 15, stateId19, currentTick, null);
        }

        /**
         * Exports this column as an {@link EntryFullChunk} without unpacking cold `ChunkFrame` /
         * `baseFrame` slices (`O(1)` `SectionFrame` slice + `VarInt[K]` `dictIdToRuntimeId` palette
         * header rewrite + sparse `4-byte` `dirtyOverlayEntries`).
         */
        public EntryFullChunk toWireFullChunkEntry(long exportTick) {
            long effectiveTick = Math.max(this.lastUpdateTick, exportTick);
            RawChunkFrameSnapshot snap = this.coldSnapshot;
            Function<String, Integer> lookup = this.canonicalToRuntimeId;
            List<SectionWireSlice> wireSlices = new ArrayList<>(sections.length);
            for (int i = 0; i < sections.length; i++) {
                LcaSectionBacking sec = sections[i];
                int[] overlay = sec != null ? sec.exportMergedDirtyOverlayEntries() : new int[0];
                byte[] base = sec != null ? sec.baseFrame() : null;
                if ((base == null || base.length == 0) && snap != null && i < snap.sectionCount()) {
                    int entry16 = snap.sectionDirectoryEntry16(i);
                    if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                        int uniformId = snap.resolveInlineUniformRuntimeStateId(i, lookup);
                        if (overlay.length == 0) {
                            int sky2 = (entry16 == 0)
                                    ? ((i >= (sections.length / 2)) ? 3 : 0)
                                    : ((entry16 >>> 13) & 3);
                            wireSlices.add(SectionWireSlice.uniform(i, uniformId, sky2));
                        } else {
                            wireSlices.add(SectionWireSlice.nonUniform(i, new byte[0], overlay));
                        }
                        continue;
                    }
                    base = snap.sliceSectionFrameWithRuntimePalette(i, lookup);
                }
                if ((base == null || base.length == 0) && overlay.length == 0) {
                    int uniformId = sec != null ? sec.uniformStateId() : 0;
                    int explicitSky2 = sec != null ? sec.uniformSkyLight2() : -1;
                    int sky2 = explicitSky2 >= 0
                            ? explicitSky2
                            : (uniformId == 0 && i >= (sections.length / 2) ? 0b01 : 0b00);
                    wireSlices.add(SectionWireSlice.uniform(i, uniformId, sky2));
                } else {
                    byte[] safeBase = (base == null) ? new byte[0] : base;
                    wireSlices.add(SectionWireSlice.nonUniform(i, safeBase, overlay));
                }
            }
            return EntryFullChunk.createWithComputedSyndrome(
                    chunkX,
                    chunkZ,
                    effectiveTick,
                    wireSlices,
                    new byte[0]
            );
        }

        /**
         * Exports only the mutated sections (`dirtySectionBitmask32`) of this column as a compact
         * {@link LcaWireStreamProtocol.InterServerChunkDeltaEntry} (`EntryPatchDelta` when
         * `1..24` sections are dirty, or `EntryFullChunk` when requested), optionally advancing
         * {@code baselineSecDed64} and clearing {@code dirtySectionBitmask32}.
         */
        public synchronized LcaWireStreamProtocol.InterServerChunkDeltaEntry exportInterServerDeltaEntry(
                long syncTick,
                boolean advanceBaseline
        ) {
            long prevBaseline = ensureBaselineSecDed64();
            EntryFullChunk full = toWireFullChunkEntry(syncTick);
            int mask = this.dirtySectionBitmask32;
            BatchChunkEntry entry;
            if (mask != 0) {
                List<SectionWireSlice> modifiedSlices = new ArrayList<>(Integer.bitCount(mask));
                int m = mask;
                while (m != 0) {
                    int secIdx = Integer.numberOfTrailingZeros(m);
                    m &= (m - 1);
                    if (secIdx >= 0 && secIdx < full.sections().size()) {
                        modifiedSlices.add(full.sections().get(secIdx));
                    }
                }
                if (modifiedSlices.size() == Integer.bitCount(mask)) {
                    entry = new EntryPatchDelta(
                            LcaWireStreamProtocol.toLocalChunkIndex10(chunkX, chunkZ),
                            full.lastUpdateTick(),
                            full.secDedSyndrome64(),
                            mask,
                            modifiedSlices
                    );
                } else {
                    entry = full;
                }
            } else {
                entry = full;
            }
            if (advanceBaseline) {
                this.baselineSecDed64 = full.secDedSyndrome64();
                this.baselineComputed = true;
                this.dirtySectionBitmask32 = 0;
            }
            return new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                    chunkX,
                    chunkZ,
                    prevBaseline,
                    entry
            );
        }

        public synchronized void updateBaselineAfterInterServerSync(long newLastUpdateTick, long newSecDed64) {
            this.lastUpdateTick = Math.max(this.lastUpdateTick, newLastUpdateTick);
            this.baselineSecDed64 = newSecDed64;
            this.baselineComputed = true;
            this.dirtySectionBitmask32 = 0;
        }
    }

    // =========================================================================
    // 1. Live Chunk & Section Attachment + Unload Epoch Fencing (Part 1B)
    // =========================================================================

    public EpochCommitFence getOrCreateRegionFence(String worldName, int regionX, int regionZ) {
        return regionFences.computeIfAbsent(
                new RegionFenceKey(worldName, regionX, regionZ),
                k -> new EpochCommitFence()
        );
    }

    /**
     * Binds a world's `.lca` voxel {@link LcaRegionFileStorage} and palette translation functions
     * so chunk loads mount cold `RawChunkFrameSnapshot`s directly without background NMS-to-`.lca`
     * re-encoding and dirty LRU flushes commit section-spliced CoW slabs (`leafanvil-ADR-006`).
     */
    public void bindWorldVoxelStorage(
            String worldName,
            LcaRegionFileStorage voxelStorage,
            Function<String, Integer> canonicalToRuntimeId,
            IntFunction<String> runtimeIdToCanonical
    ) {
        String key = normalizeWorldKey(worldName);
        if (voxelStorage == null) {
            worldStorageBindings.remove(key);
        } else {
            worldStorageBindings.put(
                    key,
                    new WorldVoxelStorageBinding(voxelStorage, canonicalToRuntimeId, runtimeIdToCanonical));
        }
    }

    public WorldVoxelStorageBinding getWorldVoxelStorageBinding(String worldName) {
        return worldStorageBindings.get(normalizeWorldKey(worldName));
    }

    /**
     * Mounts an immutable cold `.lca` {@link RawChunkFrameSnapshot} (from disk `readRawChunkFrame`
     * or `LeafGen` Stage K3 GPU synthesis) directly in memory with `0` entries in `DirtyChunkLruCache`
     * and zero NMS voxel re-encoding (`leafanvil-ADR-005`, `leafanvil-ADR-006`).
     */
    public TrackedChunkColumn attachColdChunkFrameColumn(
            String worldName,
            int chunkX,
            int chunkZ,
            RawChunkFrameSnapshot coldSnapshot,
            Object[] nmsLevelChunkSectionsOrNull,
            long currentTick,
            boolean evictToZeroBitShell
    ) {
        Objects.requireNonNull(coldSnapshot, "coldSnapshot");
        WorldVoxelStorageBinding binding = getWorldVoxelStorageBinding(worldName);
        Function<String, Integer> lookup = binding != null ? binding.canonicalToRuntimeId() : null;
        LcaSectionBacking[] coldBackings = buildColdSectionBackingsFromSnapshot(
                coldSnapshot, lookup, nmsLevelChunkSectionsOrNull, null, null, null);
        TrackedChunkColumn col = attachChunkColumn(
                worldName,
                chunkX,
                chunkZ,
                coldBackings,
                nmsLevelChunkSectionsOrNull,
                currentTick,
                evictToZeroBitShell
        );
        col.bindColdChunkFrame(
                coldSnapshot,
                binding != null ? binding.voxelStorage() : null,
                lookup,
                binding != null ? binding.runtimeIdToCanonical() : null
        );
        return col;
    }

    private static LcaSectionBacking[] buildColdSectionBackingsFromSnapshot(
            RawChunkFrameSnapshot snapshot,
            Function<String, Integer> canonicalToRuntimeId,
            Object[] nmsSectionsOrNull,
            IntPredicate explicitNonEmptyPred,
            IntPredicate explicitTickBlockPred,
            IntPredicate explicitTickFluidPred
    ) {
        int secCount = snapshot.sectionCount();
        Object sampleNms = (nmsSectionsOrNull != null && nmsSectionsOrNull.length > 0)
                ? nmsSectionsOrNull[0]
                : null;
        LcaSectionBridge.HostSectionEnvironment hostEnv =
                (explicitNonEmptyPred == null)
                        ? LcaSectionBridge.resolveHostSectionEnvironmentOrNull(sampleNms)
                        : null;
        IntPredicate nonEmptyPred = explicitNonEmptyPred != null
                ? explicitNonEmptyPred
                : (hostEnv != null ? hostEnv.nonEmptyPredicate() : (id -> id != 0));
        IntPredicate tickBlockPred = explicitTickBlockPred != null
                ? explicitTickBlockPred
                : (hostEnv != null ? hostEnv.tickingBlockPredicate() : (id -> false));
        IntPredicate tickFluidPred = explicitTickFluidPred != null
                ? explicitTickFluidPred
                : (hostEnv != null ? hostEnv.tickingFluidPredicate() : (id -> false));

        LcaSectionBacking[] built = new LcaSectionBacking[secCount];
        for (int s = 0; s < secCount; s++) {
            Object nmsSec = (nmsSectionsOrNull != null && s < nmsSectionsOrNull.length)
                    ? nmsSectionsOrNull[s]
                    : null;
            int[] liveCounts = LcaSectionBridge.inspectNmsSectionCountsOrNull(nmsSec);
            int initNe = liveCounts != null ? liveCounts[0] : -1;
            int initTb = liveCounts != null ? liveCounts[1] : -1;
            int initTf = liveCounts != null ? liveCounts[2] : -1;

            int entry16 = snapshot.sectionDirectoryEntry16(s);
            if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                int uniformRuntimeId = snapshot.resolveInlineUniformRuntimeStateId(s, canonicalToRuntimeId);
                built[s] = LcaSectionBacking.fromColdSectionFrame(
                        new byte[0],
                        uniformRuntimeId,
                        initNe,
                        initTb,
                        initTf,
                        nonEmptyPred,
                        tickBlockPred,
                        tickFluidPred
                );
            } else {
                byte[] runtimeBaseSlice = snapshot.sliceSectionFrameWithRuntimePalette(s, canonicalToRuntimeId);
                built[s] = LcaSectionBacking.fromColdSectionFrame(
                        runtimeBaseSlice,
                        0,
                        initNe,
                        initTb,
                        initTf,
                        nonEmptyPred,
                        tickBlockPred,
                        tickFluidPred
                );
            }
        }
        return built;
    }

    /**
     * Registers a loaded chunk column's {@link LcaSectionBacking} array and binds any
     * {@link LcaSectionBridge} or Paper/Folia `Moonrise` `SimpleRandomTickList` targets.
     */
    public TrackedChunkColumn attachChunkColumn(
            String worldName,
            int chunkX,
            int chunkZ,
            LcaSectionBacking[] backings,
            Object[] nmsLevelChunkSectionsOrNull,
            long currentTick
    ) {
        return attachChunkColumn(
                worldName,
                chunkX,
                chunkZ,
                backings,
                nmsLevelChunkSectionsOrNull,
                currentTick,
                true
        );
    }

    /**
     * Registers a loaded chunk column's {@link LcaSectionBacking} array and binds any
     * {@link LcaSectionBridge} in either Shadow-Tracked Native-Read Mode
     * ({@code evictToZeroBitShell == false}) or Cold Evicted Mode
     * ({@code evictToZeroBitShell == true}).
     */
    public TrackedChunkColumn attachChunkColumn(
            String worldName,
            int chunkX,
            int chunkZ,
            LcaSectionBacking[] backings,
            Object[] nmsLevelChunkSectionsOrNull,
            long currentTick,
            boolean evictToZeroBitShell
    ) {
        Objects.requireNonNull(backings, "backings");
        TrackedChunkColumn column = new TrackedChunkColumn(worldName, chunkX, chunkZ, backings, currentTick);
        String colWorldKey = column.worldKey();
        WorldVoxelStorageBinding storageBinding = worldStorageBindings.get(colWorldKey);
        if (storageBinding != null) {
            column.bindColdChunkFrame(
                    null,
                    storageBinding.voxelStorage(),
                    storageBinding.canonicalToRuntimeId(),
                    storageBinding.runtimeIdToCanonical()
            );
        }
        if (!this.clientMode) {
            for (int i = 0; i < backings.length; i++) {
                LcaSectionBacking backing = backings[i];
                if (backing != null) {
                    final int sectionIdx = i;
                    backing.setAimdAdditiveIncrement(this.aimdAdditiveIncrement);
                    backing.setSectionMutationListener((sec, tick) ->
                            recordSectionSlabMutation(colWorldKey, chunkX, sectionIdx, chunkZ, column, sec, tick));
                }
            }
        }
        if (nmsLevelChunkSectionsOrNull != null) {
            int limit = Math.min(backings.length, nmsLevelChunkSectionsOrNull.length);
            for (int i = 0; i < limit; i++) {
                Object nmsSection = nmsLevelChunkSectionsOrNull[i];
                LcaSectionBacking backing = backings[i];
                if (nmsSection != null && backing != null) {
                    if (nmsSection instanceof LcaSectionBridge bridge) {
                        bridge.leafanvil$setBacking(backing, evictToZeroBitShell);
                    }
                    if (nmsSection instanceof LcaAgentSectionCarrier carrier) {
                        byte[] existingCarrierFrame = carrier.leafanvil$getBaseFrame();
                        byte[] backingFrame = backing.baseFrame();
                        if ((existingCarrierFrame == null || existingCarrierFrame.length == 0)
                                && backingFrame != null && backingFrame.length > 0) {
                            carrier.leafanvil$mountBaseFrame(
                                    backingFrame,
                                    backing.uniformStateId(),
                                    4671,
                                    column.baselineSecDed64()
                            );
                        }
                        if (carrier.leafanvil$getDirtyCount() > 0) {
                            int[] pendingCarrierDirty = carrier.leafanvil$snapshotDirtyOverlay();
                            for (int entry32 : pendingCarrierDirty) {
                                if (entry32 != 0) {
                                    int pos12 = LcaSectionBacking.unpackPos12(entry32);
                                    int stateId = LcaSectionBacking.unpackStateId19(entry32);
                                    column.recordDirtyVoxel(i, pos12, -1, stateId, currentTick);
                                }
                            }
                        }
                        registerAgentSectionBinding(
                                new ChunkColumnKey(colWorldKey, chunkX, chunkZ), column, i, nmsSection);
                    }
                    MoonriseTickingListSynchronizer moonriseSync =
                            MoonriseTickingListSynchronizer.bindToSectionIfPresent(nmsSection);
                    if (moonriseSync != null) {
                        column.setMoonriseSynchronizer(i, moonriseSync);
                    }
                }
            }
        }
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        pendingChunkLoads.remove(key);
        activeChunks.put(key, column);
        return column;
    }

    /**
     * Promotes an already shadow-tracked chunk column into Cold Evicted {@code ZeroBitStorage} mode
     * when it moves outside active {@code simulation-distance}, returning total {@code long[]} bytes freed.
     */
    public int evictShadowTrackedChunkToZeroBitShell(Object[] nmsLevelChunkSections) {
        if (nmsLevelChunkSections == null) {
            return 0;
        }
        int totalFreed = 0;
        for (Object sec : nmsLevelChunkSections) {
            if (sec instanceof LcaSectionBridge bridge && !bridge.leafanvil$isZeroBitShellEvicted()) {
                totalFreed += bridge.leafanvil$evictToZeroBitShell();
            }
        }
        return totalFreed;
    }

    /**
     * Enqueues a newly loaded chunk in $O(1)$ for budgeted deferred section indexing and
     * `ZeroBitStorage` eviction outside the hot `CHUNK_LOAD` path.
     */
    public boolean enqueuePendingChunkLoad(
            String worldName,
            int chunkX,
            int chunkZ,
            Object chunkHandle,
            long currentTick
    ) {
        if (chunkHandle == null) {
            return false;
        }
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        PendingChunkLoad entry = new PendingChunkLoad(key.worldKey(), chunkX, chunkZ, chunkHandle, currentTick);
        if (pendingChunkLoads.put(key, entry) == null) {
            pendingChunkFifo.offer(key);
            return true;
        }
        return false;
    }

    public int pendingChunkCount() {
        return pendingChunkLoads.size();
    }

    public int activeChunkCount() {
        return activeChunks.size();
    }

    /**
     * Drains up to {@code maxChunksToCompact} deferred chunks that have settled for at least
     * {@code minQuietTicks} since `CHUNK_LOAD`.
     */
    public int tickDeferredChunkCompaction(
            long currentTick,
            long minQuietTicks,
            int maxChunksToCompact,
            Consumer<PendingChunkLoad> chunkCompactor
    ) {
        Objects.requireNonNull(chunkCompactor, "chunkCompactor");
        if (maxChunksToCompact <= 0 || pendingChunkLoads.isEmpty()) {
            return 0;
        }
        int drained = 0;
        int scanned = 0;
        int queueScanBound = Math.max(maxChunksToCompact * 4, pendingChunkLoads.size() + 4);
        while (drained < maxChunksToCompact && scanned < queueScanBound) {
            ChunkColumnKey key = pendingChunkFifo.poll();
            if (key == null) {
                break;
            }
            scanned++;
            PendingChunkLoad pending = pendingChunkLoads.get(key);
            if (pending == null) {
                continue;
            }
            if (minQuietTicks > 0L && (currentTick - pending.enqueuedTick()) < minQuietTicks) {
                pendingChunkFifo.offer(key);
                if (scanned >= pendingChunkLoads.size()) {
                    break;
                }
                continue;
            }
            if (pendingChunkLoads.remove(key, pending)) {
                if (pending.chunkHandle() != null) {
                    chunkCompactor.accept(pending);
                    drained++;
                }
            }
        }
        return drained;
    }

    /**
     * Submits a captured $O(1)$ {@link LcaSectionBridge.RawSectionSnapshot} array to the
     * {@code C2ME}-cooperative background compaction pool to build {@link LcaSectionBacking}
     * (`CompactBaseIndex` + pre-built clean wire/disk slices) completely off the server tick thread.
     */
    public boolean submitChunkSnapshotForCompaction(
            String worldName,
            int chunkX,
            int chunkZ,
            Object chunkHandle,
            Object[] nmsSections,
            LcaSectionBridge.RawSectionSnapshot[] snapshots,
            int globalPaletteBits,
            long enqueuedTick,
            IntPredicate nonEmptyPred,
            IntPredicate tickingBlockPred,
            IntPredicate tickingFluidPred
    ) {
        if (snapshots == null || snapshots.length == 0 || compactionExecutor.isShutdown()) {
            return false;
        }
        this.defaultGlobalPaletteBits = Math.max(4, globalPaletteBits);
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        TrackedChunkColumn alreadyCold = activeChunks.get(key);
        if (alreadyCold != null && alreadyCold.coldChunkFrame() != null) {
            // Cold .lca ChunkFrame already mounted in memory (e.g. -javaagent / Mixin / LeafGen Stage K3): zero re-encode!
            return false;
        }
        EpochCommitFence fence = getOrCreateRegionFence(key.worldKey(), chunkX >> 5, chunkZ >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(chunkX, chunkZ);
        if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
            return false;
        }
        if (!inFlightChunkKeys.add(key)) {
            return false;
        }
        WeakReference<Object> handleRef = new WeakReference<>(chunkHandle);
        WorldVoxelStorageBinding storageBinding = worldStorageBindings.get(key.worldKey());
        activeBackgroundTasks.incrementAndGet();
        try {
            compactionExecutor.execute(() -> {
                try {
                    if (fence.isUnloadedTombstoneCommitted(localIdx10) || !inFlightChunkKeys.contains(key)) {
                        return;
                    }
                    // Zero-copy Disk -> Memory fast path: reuse existing .lca ChunkFrame on disk if present!
                    if (storageBinding != null && storageBinding.voxelStorage() != null) {
                        try {
                            RawChunkFrameSnapshot diskSnap = storageBinding.voxelStorage().readRawChunkFrame(
                                    chunkX, chunkZ, storageBinding.canonicalToRuntimeId());
                            if (diskSnap != null && diskSnap.sectionCount() == snapshots.length) {
                                LcaSectionBacking[] coldBuilt = buildColdSectionBackingsFromSnapshot(
                                        diskSnap,
                                        storageBinding.canonicalToRuntimeId(),
                                        nmsSections,
                                        nonEmptyPred,
                                        tickingBlockPred,
                                        tickingFluidPred);
                                if (!fence.isUnloadedTombstoneCommitted(localIdx10) && inFlightChunkKeys.contains(key)) {
                                    completedChunkCompactions.offer(new CompletedChunkCompaction(
                                            key.worldKey(),
                                            chunkX,
                                            chunkZ,
                                            handleRef,
                                            nmsSections,
                                            snapshots,
                                            coldBuilt,
                                            enqueuedTick,
                                            diskSnap
                                    ));
                                }
                                return;
                            }
                        } catch (IOException ioEx) {
                            LOGGER.log(java.util.logging.Level.WARNING,
                                    "[LeafAnvil] Cold disk ChunkFrame read fallback for ("
                                            + chunkX + "," + chunkZ + "): " + ioEx.getMessage(), ioEx);
                        }
                    }
                    int secCount = snapshots.length;
                    LcaSectionBacking[] built = new LcaSectionBacking[secCount];
                    for (int s = 0; s < secCount; s++) {
                        if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
                            return;
                        }
                        LcaSectionBridge.RawSectionSnapshot snap = snapshots[s];
                        int skyConst4 = LcaSectionBridge.resolveSnapshotSkyConst4(
                                snap, s, secCount, nonEmptyPred);
                        if (snap == null) {
                            built[s] = new LcaSectionBacking(new byte[0], 0);
                        } else if (snap.uniform()) {
                            built[s] = LcaSectionBacking.fromRawBitStorageSnapshot(
                                    null,
                                    0,
                                    snap.paletteStateIds(),
                                    globalPaletteBits,
                                    skyConst4,
                                    0,
                                    snap.initialNonEmptyCount(),
                                    snap.initialTickingBlockCount(),
                                    snap.initialTickingFluidCount(),
                                    nonEmptyPred,
                                    tickingBlockPred,
                                    tickingFluidPred
                            );
                        } else {
                            built[s] = LcaSectionBacking.fromRawBitStorageSnapshot(
                                    snap.rawWords(),
                                    snap.bitsPerEntry(),
                                    snap.paletteStateIds(),
                                    globalPaletteBits,
                                    skyConst4,
                                    0,
                                    snap.initialNonEmptyCount(),
                                    snap.initialTickingBlockCount(),
                                    snap.initialTickingFluidCount(),
                                    nonEmptyPred,
                                    tickingBlockPred,
                                    tickingFluidPred,
                                    false
                            );
                            built[s].evictCompactBaseIndexToColdBaseFrame();
                        }
                    }
                    if (!fence.isUnloadedTombstoneCommitted(localIdx10) && inFlightChunkKeys.contains(key)) {
                        completedChunkCompactions.offer(new CompletedChunkCompaction(
                                key.worldKey(),
                                chunkX,
                                chunkZ,
                                handleRef,
                                nmsSections,
                                snapshots,
                                built,
                                enqueuedTick
                        ));
                    }
                } finally {
                    inFlightChunkKeys.remove(key);
                    activeBackgroundTasks.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            inFlightChunkKeys.remove(key);
            activeBackgroundTasks.decrementAndGet();
            if (chunkHandle != null) {
                enqueuePendingChunkLoad(key.worldKey(), chunkX, chunkZ, chunkHandle, enqueuedTick);
            }
            return false;
        }
    }

    public boolean submitPendingChunkForBackgroundCompaction(
            PendingChunkLoad pending,
            Object[] nmsSections,
            LcaSectionBridge.RawSectionSnapshot[] snapshots,
            int globalPaletteBits,
            IntPredicate nonEmptyPred,
            IntPredicate tickingBlockPred,
            IntPredicate tickingFluidPred
    ) {
        if (pending == null) {
            return false;
        }
        return submitChunkSnapshotForCompaction(
                pending.worldKey(),
                pending.chunkX(),
                pending.chunkZ(),
                pending.chunkHandle(),
                nmsSections,
                snapshots,
                globalPaletteBits,
                pending.enqueuedTick(),
                nonEmptyPred,
                tickingBlockPred,
                tickingFluidPred
        );
    }

    /**
     * Drains up to {@code maxToCommit} completed background chunk compactions on the owning
     * server/region thread, skipping any chunk fenced by {@link EpochCommitFence} unload tombstones.
     */
    public int drainCompletedChunkCompactions(
            int maxToCommit,
            Consumer<CompletedChunkCompaction> onMainThreadCommit
    ) {
        Objects.requireNonNull(onMainThreadCommit, "onMainThreadCommit");
        if (maxToCommit <= 0 || completedChunkCompactions.isEmpty()) {
            return 0;
        }
        int committed = 0;
        while (committed < maxToCommit) {
            CompletedChunkCompaction completed = completedChunkCompactions.poll();
            if (completed == null) {
                break;
            }
            EpochCommitFence fence = getOrCreateRegionFence(
                    completed.worldKey(),
                    completed.chunkX() >> 5,
                    completed.chunkZ() >> 5
            );
            int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(completed.chunkX(), completed.chunkZ());
            if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
                continue;
            }
            onMainThreadCommit.accept(completed);
            committed++;
        }
        return committed;
    }

    public int drainCompletedChunkAttachments(
            int maxToCommit,
            Consumer<CompletedChunkCompaction> onMainThreadCommit
    ) {
        return drainCompletedChunkCompactions(maxToCommit, onMainThreadCommit);
    }

    /**
     * Marks a chunk column as having dirty overlay mutations eligible for quiet-window background
     * compaction.
     */
    public void markChunkDirtyForOverlayCompaction(String worldName, int chunkX, int chunkZ) {
        dirtyOverlayChunkKeys.add(new ChunkColumnKey(worldName, chunkX, chunkZ));
    }

    /**
     * Freezes settled {@code activeOverlay} tables in $O(1)$ on the owning thread and offloads
     * {@link LcaSectionBacking#compactFrozenOverlayOffThread(CompactionSnapshot, int)} to the
     * background compaction pool guarded by {@link EpochCommitFence}.
     */
    public int scheduleBackgroundOverlayCompaction(
            long currentTick,
            long quietWindowTicks,
            int maxChunksPerTick,
            int globalPaletteBits
    ) {
        if (maxChunksPerTick <= 0 || compactionExecutor.isShutdown() || activeChunks.isEmpty()) {
            return 0;
        }
        int scheduledSections = 0;
        int inspectedChunks = 0;
        int maxScannedChunks = Math.max(maxChunksPerTick, maxChunksPerTick * 2);
        int scannedChunks = 0;
        for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> entry : activeChunks.entrySet()) {
            if (inspectedChunks >= maxChunksPerTick || scannedChunks >= maxScannedChunks) {
                break;
            }
            ChunkColumnKey key = entry.getKey();
            TrackedChunkColumn col = entry.getValue();
            if (col == null) {
                continue;
            }
            if (!dirtyOverlayChunkKeys.contains(key) && col.dirtySectionBitmask32() == 0) {
                scannedChunks++;
                continue;
            }
            scannedChunks++;
            EpochCommitFence fence = getOrCreateRegionFence(key.worldKey(), key.chunkX() >> 5, key.chunkZ() >> 5);
            int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(key.chunkX(), key.chunkZ());
            if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
                continue;
            }
            boolean hadReadyInChunk = false;
            int secCount = col.sectionCount();
            for (int i = 0; i < secCount; i++) {
                LcaSectionBacking sec = col.section(i);
                if (sec == null || sec.isUnloaded() || !sec.isReadyForQuietWindowCompaction(currentTick, quietWindowTicks)) {
                    continue;
                }
                CompactionSnapshot snap;
                try {
                    snap = sec.beginCompactionSnapshot();
                } catch (IllegalStateException unloadedEx) {
                    continue;
                }
                hadReadyInChunk = true;
                activeBackgroundTasks.incrementAndGet();
                try {
                    compactionExecutor.execute(() -> {
                        try {
                            if (fence.isUnloadedTombstoneCommitted(localIdx10) || sec.isUnloaded()) {
                                return;
                            }
                            if (sec.compactFrozenOverlayOffThread(snap, globalPaletteBits)) {
                                fence.tryCommitChunkEpoch(localIdx10, snap.epoch());
                                pruneCleanChunkFromDirtyLru(key, col);
                                if (dirtyChunkCount() > this.maxDirtyChunks) {
                                    flushExcessDirtyChunksAsync();
                                }
                            }
                        } finally {
                            activeBackgroundTasks.decrementAndGet();
                        }
                    });
                    scheduledSections++;
                } catch (RejectedExecutionException rejected) {
                    activeBackgroundTasks.decrementAndGet();
                }
            }
            if (hadReadyInChunk) {
                inspectedChunks++;
                dirtyOverlayChunkKeys.remove(key);
            }
        }
        if (scheduledSections > 0) {
            decayDirtyChunkScores();
        }
        return scheduledSections;
    }

    /**
     * Waits up to {@code timeoutMillis} for currently running background compaction tasks to finish.
     */
    public boolean awaitPendingBackgroundCompactions(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        while (activeBackgroundTasks.get() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            Thread.yield();
        }
        return activeBackgroundTasks.get() == 0;
    }

    /**
     * Cleanly shuts down the background compaction worker pool.
     */
    public void shutdownCompactionPool() {
        compactionExecutor.shutdownAndDrain(2L, TimeUnit.SECONDS);
        inFlightChunkKeys.clear();
        completedChunkCompactions.clear();
        dirtyOverlayChunkKeys.clear();
        flushingDirtyChunks.clear();
        synchronized (dirtyCacheLock) {
            dirtyChunkLru.clear();
        }
    }

    public TrackedChunkColumn getTrackedChunk(String worldName, int chunkX, int chunkZ) {
        return activeChunks.get(new ChunkColumnKey(worldName, chunkX, chunkZ));
    }

    /**
     * Atomically marks all sections in `(worldName, chunkX, chunkZ)` as `UNLOADED` (`bit 63`) and
     * commits the unload tombstone to the region's {@link EpochCommitFence}, fencing off any
     * in-flight background compaction (`REQ-RTP-S-005`).
     */
    public long markChunkUnloaded(String worldName, int chunkX, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        pendingChunkLoads.remove(key);
        inFlightChunkKeys.remove(key);
        dirtyOverlayChunkKeys.remove(key);
        TrackedChunkColumn removed = activeChunks.remove(key);
        EpochCommitFence fence = getOrCreateRegionFence(worldName, chunkX >> 5, chunkZ >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(chunkX, chunkZ);
        long maxUnloadedEpoch = LcaSectionBacking.UNLOADED_BIT;
        if (removed != null) {
            removed.markColumnUnloaded();
            synchronized (dirtyCacheLock) {
                dirtyChunkLru.remove(key);
            }
            flushingDirtyChunks.remove(key);
            purgeAgentSectionBindingsForChunk(key);
            for (int i = 0; i < removed.sectionCount(); i++) {
                LcaSectionBacking sec = removed.section(i);
                if (sec != null) {
                    long secUnloaded = sec.markUnloaded();
                    if ((secUnloaded & LcaSectionBacking.EPOCH_MAGNITUDE_MASK)
                            > (maxUnloadedEpoch & LcaSectionBacking.EPOCH_MAGNITUDE_MASK)) {
                        maxUnloadedEpoch = secUnloaded;
                    }
                }
            }
        }
        return fence.commitUnloadTombstone(localIdx10, maxUnloadedEpoch);
    }

    /**
     * Runs quiet-window compaction across all sections in the specified chunk that satisfy
     * {@link LcaSectionBacking#isReadyForQuietWindowCompaction(long, long)}.
     */
    public int compactReadySectionsInChunk(
            String worldName,
            int chunkX,
            int chunkZ,
            long currentTick,
            long quietWindowTicks,
            Function<CompactionSnapshot, byte[]> baseFrameMerger
    ) {
        Objects.requireNonNull(baseFrameMerger, "baseFrameMerger");
        TrackedChunkColumn column = getTrackedChunk(worldName, chunkX, chunkZ);
        if (column == null) {
            return 0;
        }
        EpochCommitFence fence = getOrCreateRegionFence(worldName, chunkX >> 5, chunkZ >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(chunkX, chunkZ);
        if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
            return 0;
        }
        int compactedCount = 0;
        for (int i = 0; i < column.sectionCount(); i++) {
            LcaSectionBacking sec = column.section(i);
            if (sec != null && !sec.isUnloaded() && sec.isReadyForQuietWindowCompaction(currentTick, quietWindowTicks)) {
                CompactionSnapshot snap = sec.beginCompactionSnapshot();
                byte[] mergedBaseFrame = baseFrameMerger.apply(snap);
                if (sec.completeCompaction(snap.epoch(), mergedBaseFrame, snap.uniformStateId())) {
                    fence.tryCommitChunkEpoch(localIdx10, snap.epoch());
                    compactedCount++;
                }
            }
        }
        return compactedCount;
    }

    public void clearWorld(String worldName) {
        String key = normalizeWorldKey(worldName);
        pendingChunkLoads.keySet().removeIf(k -> k.worldKey().equals(key));
        inFlightChunkKeys.removeIf(k -> k.worldKey().equals(key));
        dirtyOverlayChunkKeys.removeIf(k -> k.worldKey().equals(key));
        flushingDirtyChunks.removeIf(k -> k.worldKey().equals(key));
        completedChunkCompactions.removeIf(c -> c.worldKey().equals(key));
        for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> e : activeChunks.entrySet()) {
            if (e.getKey().worldKey().equals(key) && e.getValue() != null) {
                e.getValue().markColumnUnloaded();
            }
        }
        activeChunks.keySet().removeIf(k -> k.worldKey().equals(key));
        synchronized (dirtyCacheLock) {
            dirtyChunkLru.keySet().removeIf(k -> k.worldKey().equals(key));
        }
        purgeAgentSectionBindingsForWorld(key);
        worldStorageBindings.remove(key);
        regionFences.keySet().removeIf(k -> k.worldKey().equals(key));
        playerRegionDigests.keySet().removeIf(k -> k.worldKey().equals(key));
    }

    public int maxDirtyChunks() {
        return this.maxDirtyChunks;
    }

    public void setMaxDirtyChunks(int maxDirtyChunks) {
        if (maxDirtyChunks < 1) {
            throw new IllegalArgumentException("maxDirtyChunks must be >= 1: " + maxDirtyChunks);
        }
        this.maxDirtyChunks = maxDirtyChunks;
        flushExcessDirtyChunksAsync();
    }

    @Deprecated
    public int maxHot3dSlabs() {
        return maxDirtyChunks();
    }

    @Deprecated
    public void setMaxHot3dSlabs(int maxHot3dSlabs) {
        setMaxDirtyChunks(maxHot3dSlabs);
    }

    public void setAimdParameters(int additiveIncrement, int decayNumerator, int decayShift) {
        if (additiveIncrement < 1) {
            throw new IllegalArgumentException("additiveIncrement must be >= 1: " + additiveIncrement);
        }
        if (decayNumerator < 0 || decayShift < 0 || decayShift > 30 || decayNumerator > (1 << decayShift)) {
            throw new IllegalArgumentException(
                    "Invalid AIMD decay fraction (" + decayNumerator + " / 2^" + decayShift + ")");
        }
        this.aimdAdditiveIncrement = additiveIncrement;
        this.aimdDecayNumerator = decayNumerator;
        this.aimdDecayShift = decayShift;
        for (TrackedChunkColumn col : activeChunks.values()) {
            for (int i = 0; i < col.sectionCount(); i++) {
                LcaSectionBacking sec = col.section(i);
                if (sec != null) {
                    sec.setAimdAdditiveIncrement(additiveIncrement);
                }
            }
        }
    }

    public int dirtyChunkCount() {
        synchronized (dirtyCacheLock) {
            return dirtyChunkLru.size();
        }
    }

    @Deprecated
    public int hot3dSlabCount() {
        return dirtyChunkCount();
    }

    public boolean isChunkInDirtyLru(String worldName, int chunkX, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        synchronized (dirtyCacheLock) {
            return dirtyChunkLru.containsKey(key);
        }
    }

    /**
     * Touches `(worldName, chunkX, chunkZ)` in the access-ordered `DirtyChunkLruCache` strictly
     * under {@code dirtyCacheLock} (`0` unsynchronized `LinkedHashMap#get` race hazards).
     */
    public TrackedChunkColumn touchDirtyChunkLru(String worldName, int chunkX, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        synchronized (dirtyCacheLock) {
            return dirtyChunkLru.get(key);
        }
    }

    @Deprecated
    public boolean isSlabHotIn3dCache(String worldName, int chunkX, int sectionIndex, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        TrackedChunkColumn col;
        synchronized (dirtyCacheLock) {
            col = dirtyChunkLru.get(key);
        }
        if (col == null || sectionIndex < 0 || sectionIndex >= col.sectionCount()) {
            return false;
        }
        LcaSectionBacking sec = col.section(sectionIndex);
        return sec != null && (sec.dirtyCount() > 0 || (sec.frozenOverlay() != null && sec.frozenOverlay().dirtyCount() > 0));
    }

    private void recordSectionSlabMutation(
            String worldKey,
            int chunkX,
            int sectionIndex,
            int chunkZ,
            TrackedChunkColumn column,
            LcaSectionBacking sectionBacking,
            long tick
    ) {
        if (this.clientMode || column == null || column.isUnloaded() || sectionBacking == null || sectionBacking.isUnloaded()) {
            return;
        }
        column.markSectionMutated(sectionIndex, tick);
        ChunkColumnKey chunkKey = new ChunkColumnKey(worldKey, chunkX, chunkZ);
        dirtyOverlayChunkKeys.add(chunkKey);
        List<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> victimsToFlush = null;
        synchronized (dirtyCacheLock) {
            if (!column.isUnloaded() && !sectionBacking.isUnloaded()) {
                dirtyChunkLru.put(chunkKey, column);
            }
            if (dirtyChunkLru.size() > this.maxDirtyChunks) {
                victimsToFlush = selectDirtyVictimsForFlushLocked();
            }
        }
        if (victimsToFlush != null) {
            for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> victim : victimsToFlush) {
                scheduleDirtyChunkCoWFlushAsync(victim.getKey(), victim.getValue());
            }
        }
    }

    private List<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> selectDirtyVictimsForFlushLocked() {
        List<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> victims = new ArrayList<>();
        synchronized (dirtyCacheLock) {
            Iterator<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> cleanPurge = dirtyChunkLru.entrySet().iterator();
            while (cleanPurge.hasNext()) {
                Map.Entry<ChunkColumnKey, TrackedChunkColumn> e = cleanPurge.next();
                TrackedChunkColumn col = e.getValue();
                if (col == null || col.isUnloaded() || !col.hasAnyDirtyOverlay()) {
                    cleanPurge.remove();
                }
            }
            int inFlightInLru = 0;
            for (ChunkColumnKey k : dirtyChunkLru.keySet()) {
                if (flushingDirtyChunks.contains(k)) {
                    inFlightInLru++;
                }
            }
            int needed = dirtyChunkLru.size() - inFlightInLru - this.maxDirtyChunks;
            while (needed > 0) {
                Map.Entry<ChunkColumnKey, TrackedChunkColumn> best = null;
                int bestScore = Integer.MAX_VALUE;
                for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> e : dirtyChunkLru.entrySet()) {
                    ChunkColumnKey k = e.getKey();
                    TrackedChunkColumn col = e.getValue();
                    if (flushingDirtyChunks.contains(k) || victimsContainsKey(victims, k)) {
                        continue;
                    }
                    int score = col.totalAimdEditScore();
                    if (score < bestScore) {
                        bestScore = score;
                        best = Map.entry(k, col);
                    }
                }
                if (best == null) {
                    break;
                }
                victims.add(best);
                needed--;
            }
        }
        return victims;
    }

    private static boolean victimsContainsKey(
            List<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> victims,
            ChunkColumnKey key
    ) {
        for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> e : victims) {
            if (e.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schedules an asynchronous off-thread CoW flush (or in-memory sparse overlay fold when no
     * disk storage is bound) for a dirty chunk column (`REQ-RTP-S-004`, `REQ-RTP-S-005`).
     */
    public boolean flushDirtyChunkCoWAsync(String worldName, int chunkX, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        TrackedChunkColumn col = activeChunks.get(key);
        if (col == null || col.isUnloaded()) {
            return false;
        }
        return scheduleDirtyChunkCoWFlushAsync(key, col);
    }

    /**
     * Schedules immediate off-thread CoW flushes for the lowest-AIMD/LRU dirty chunks whenever
     * `dirtyChunkCount() > maxDirtyChunks` (`REQ-RTP-S-004`).
     */
    public int flushExcessDirtyChunksAsync() {
        List<Map.Entry<ChunkColumnKey, TrackedChunkColumn>> victims;
        synchronized (dirtyCacheLock) {
            victims = selectDirtyVictimsForFlushLocked();
        }
        int scheduled = 0;
        for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> v : victims) {
            if (scheduleDirtyChunkCoWFlushAsync(v.getKey(), v.getValue())) {
                scheduled++;
            }
        }
        return scheduled;
    }

    @Deprecated
    public int evictExcessHot3dSlabs() {
        return flushExcessDirtyChunksAsync();
    }

    private void registerAgentSectionBinding(
            ChunkColumnKey key,
            TrackedChunkColumn column,
            int sectionIndex,
            Object nmsSection
    ) {
        int idHash = System.identityHashCode(nmsSection);
        agentSectionIndex.compute(idHash, (h, existing) -> {
            List<AgentSectionRef> next = existing != null ? new ArrayList<>(existing) : new ArrayList<>(2);
            next.removeIf(ref -> {
                Object target = ref.sectionRef().get();
                return target == null || target == nmsSection;
            });
            next.add(new AgentSectionRef(key, column, sectionIndex, new WeakReference<>(nmsSection)));
            return next;
        });
    }

    private void purgeAgentSectionBindingsForChunk(ChunkColumnKey key) {
        agentSectionIndex.entrySet().removeIf(e -> {
            List<AgentSectionRef> list = e.getValue();
            list.removeIf(ref -> ref.sectionRef().get() == null || ref.key().equals(key));
            return list.isEmpty();
        });
    }

    private void purgeAgentSectionBindingsForWorld(String worldKey) {
        agentSectionIndex.entrySet().removeIf(e -> {
            List<AgentSectionRef> list = e.getValue();
            list.removeIf(ref -> ref.sectionRef().get() == null || ref.key().worldKey().equals(worldKey));
            return list.isEmpty();
        });
    }

    /**
     * Routes an inline `-javaagent` `LevelChunkSection#setBlockState` mutation from
     * {@link net.leaf.anvil.agent.LeafAnvilAgentBootstrap} directly into the owning
     * {@link TrackedChunkColumn}'s sparse dirty cell map in $O(1)$ (`leafanvil-ADR-005`).
     */
    public boolean handleAgentSectionMutation(
            Object nmsSection,
            int pos12,
            int prevStateId19,
            int newStateId19,
            long currentTick
    ) {
        if (nmsSection == null) {
            return false;
        }
        List<AgentSectionRef> candidates = agentSectionIndex.get(System.identityHashCode(nmsSection));
        if (candidates == null) {
            return false;
        }
        for (int i = 0; i < candidates.size(); i++) {
            AgentSectionRef ref = candidates.get(i);
            if (ref.sectionRef().get() == nmsSection && !ref.column().isUnloaded()) {
                ref.column().recordDirtyVoxel(ref.sectionIndex(), pos12, prevStateId19, newStateId19, currentTick);
                return true;
            }
        }
        return false;
    }

    /**
     * Captures a lightweight $O(1)$ {@link DirectCoWSaveTicket} on the owning server/region thread
     * (`SerializableChunkData.copyOf`) without copying `PalettedContainer` arrays, ready to commit
     * off-thread in `SerializableChunkData#write()` (`leafanvil-ADR-005`, `REQ-RTP-S-005`).
     */
    public DirectCoWSaveTicket captureDirectCoWSaveTicket(String worldName, int chunkX, int chunkZ) {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        TrackedChunkColumn col = activeChunks.get(key);
        if (col == null || col.isUnloaded() || col.coldSnapshot() == null || col.backingStorage() == null) {
            return null;
        }
        int secCount = col.sectionCount();
        CompactionSnapshot[] snaps = new CompactionSnapshot[secCount];
        int flushMask = 0;
        for (int i = 0; i < secCount && i < 32; i++) {
            LcaSectionBacking sec = col.section(i);
            if (sec == null || sec.isUnloaded()) {
                continue;
            }
            if (sec.dirtyCount() > 0 || (sec.frozenOverlay() != null && sec.frozenOverlay().dirtyCount() > 0)) {
                try {
                    snaps[i] = sec.beginCompactionSnapshot();
                    flushMask |= (1 << i);
                } catch (IllegalStateException unloadedEx) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                            "[LeafAnvil] Skipped unloaded section " + i + " during direct CoW save ticket capture: "
                                    + unloadedEx.getMessage(), unloadedEx);
                }
            }
        }
        return new DirectCoWSaveTicket(key, col, snaps, flushMask);
    }

    /**
     * Executes a {@link DirectCoWSaveTicket} on the chunk serialization I/O worker (`SerializableChunkData#write()`),
     * splicing clean `SectionFrame`s verbatim, re-encoding only `flushMask` dirty sections via CoW,
     * swapping `TrackedChunkColumn.chunkFrame`, and removing clean entries from `DirtyChunkLruCache`.
     */
    public boolean commitDirectCoWSaveTicketSync(DirectCoWSaveTicket ticket) throws IOException {
        if (ticket == null || ticket.column() == null || ticket.column().isUnloaded()) {
            return false;
        }
        TrackedChunkColumn col = ticket.column();
        RawChunkFrameSnapshot coldSnap = col.coldSnapshot();
        LcaRegionFileStorage storage = col.backingStorage();
        if (coldSnap == null || storage == null) {
            return false;
        }
        if (ticket.flushMask() == 0) {
            if (!storage.doesChunkExist(col.chunkX(), col.chunkZ())) {
                if (!storage.modeBLcd2()) {
                    storage.commitPreEncodedChunkSlab(col.chunkX(), col.chunkZ(), coldSnap.chunkFrame());
                } else {
                    storage.commitSplicedChunkFrameCoW(
                            col.chunkX(), col.chunkZ(), coldSnap.chunkFrame(), 0, null, null);
                }
            }
            pruneCleanChunkFromDirtyLru(ticket.key(), col);
            return true;
        }
        EpochCommitFence fence = getOrCreateRegionFence(
                ticket.key().worldKey(), col.chunkX() >> 5, col.chunkZ() >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(col.chunkX(), col.chunkZ());
        executeDirtyChunkFlushOffThread(
                ticket.key(),
                col,
                fence,
                localIdx10,
                ticket.sectionSnapshots(),
                ticket.flushMask(),
                ticket.updatedSectionSkyLightOrNull(),
                ticket.updatedSectionBlockLightOrNull(),
                this.defaultGlobalPaletteBits
        );
        clearFlushedAgentCarriers(ticket.key(), col, ticket.flushMask());
        pruneCleanChunkFromDirtyLru(ticket.key(), col);
        return true;
    }

    /**
     * Persists a cold-mounted or Stage K3 GPU-mounted `.lca` column to `LcaRegionFileStorage`:
     * untouched chunks (`dirtySectionMask == 0`) commit verbatim (`commitPreEncodedChunkSlab` in
     * Mode A or header-wrapped Mode B `LCD2` with zero CPU voxel re-encoding), while edited chunks
     * re-encode only dirty sections from `DirtyChunkLruCache` and splice the remaining GPU
     * `SectionFrame`s byte-for-byte (`leafanvil-ADR-005` Workstream 3).
     */
    public boolean commitOrSpliceColdColumnToStorage(
            String worldName,
            int chunkX,
            int chunkZ,
            Object[] nmsSectionsOrNull,
            long currentTick
    ) throws IOException {
        ChunkColumnKey key = new ChunkColumnKey(worldName, chunkX, chunkZ);
        TrackedChunkColumn col = activeChunks.get(key);
        if (col == null || col.isUnloaded()) {
            return false;
        }
        if (nmsSectionsOrNull != null) {
            int limit = Math.min(col.sectionCount(), nmsSectionsOrNull.length);
            for (int i = 0; i < limit; i++) {
                if (nmsSectionsOrNull[i] instanceof LcaAgentSectionCarrier carrier
                        && carrier.leafanvil$getDirtyCount() > 0) {
                    LcaSectionBacking sec = col.section(i);
                    for (int entry32 : carrier.leafanvil$snapshotDirtyOverlay()) {
                        if (entry32 != 0) {
                            int pos12 = LcaSectionBacking.unpackPos12(entry32);
                            int stateId = LcaSectionBacking.unpackStateId19(entry32);
                            if (sec.activeOverlay().getStateId(pos12) != stateId) {
                                col.recordDirtyVoxel(i, pos12, -1, stateId, currentTick);
                            }
                        }
                    }
                }
            }
        }
        DirectCoWSaveTicket ticket = captureDirectCoWSaveTicket(worldName, chunkX, chunkZ);
        if (ticket == null) {
            return false;
        }
        return commitDirectCoWSaveTicketSync(ticket);
    }

    private void clearFlushedAgentCarriers(ChunkColumnKey key, TrackedChunkColumn col, int flushedMask) {
        long colSecDed = col.baselineSecDed64();
        for (List<AgentSectionRef> list : agentSectionIndex.values()) {
            for (AgentSectionRef ref : list) {
                if (ref.key().equals(key) && (flushedMask & (1 << ref.sectionIndex())) != 0) {
                    Object target = ref.sectionRef().get();
                    if (target instanceof LcaAgentSectionCarrier carrier) {
                        LcaSectionBacking sec = col.section(ref.sectionIndex());
                        if (sec != null && sec.dirtyCount() == 0) {
                            carrier.leafanvil$clearDirtyAfterCompaction(sec.baseFrame(), colSecDed);
                        }
                    }
                }
            }
        }
    }

    private boolean scheduleDirtyChunkCoWFlushAsync(ChunkColumnKey key, TrackedChunkColumn col) {
        if (col == null || col.isUnloaded() || compactionExecutor.isShutdown()) {
            return false;
        }
        if (!flushingDirtyChunks.add(key)) {
            return false;
        }
        EpochCommitFence fence = getOrCreateRegionFence(key.worldKey(), key.chunkX() >> 5, key.chunkZ() >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(key.chunkX(), key.chunkZ());
        if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
            flushingDirtyChunks.remove(key);
            return false;
        }
        int secCount = col.sectionCount();
        CompactionSnapshot[] snaps = new CompactionSnapshot[secCount];
        int flushMask = 0;
        for (int i = 0; i < secCount && i < 32; i++) {
            LcaSectionBacking sec = col.section(i);
            if (sec == null || sec.isUnloaded()) {
                continue;
            }
            if (sec.dirtyCount() > 0 || (sec.frozenOverlay() != null && sec.frozenOverlay().dirtyCount() > 0)) {
                try {
                    snaps[i] = sec.beginCompactionSnapshot();
                    flushMask |= (1 << i);
                } catch (IllegalStateException unloadedEx) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                            "[LeafAnvil] Skipped unloaded section " + i + " during async CoW flush snapshot: "
                                    + unloadedEx.getMessage(), unloadedEx);
                }
            }
        }
        if (flushMask == 0) {
            flushingDirtyChunks.remove(key);
            pruneCleanChunkFromDirtyLru(key, col);
            return false;
        }

        final int effectiveFlushMask = flushMask;
        final int palBits = this.defaultGlobalPaletteBits;
        activeBackgroundTasks.incrementAndGet();
        try {
            compactionExecutor.execute(() -> {
                try {
                    if (fence.isUnloadedTombstoneCommitted(localIdx10) || col.isUnloaded()) {
                        return;
                    }
                    executeDirtyChunkFlushOffThread(key, col, fence, localIdx10, snaps, effectiveFlushMask, palBits);
                } finally {
                    flushingDirtyChunks.remove(key);
                    pruneCleanChunkFromDirtyLru(key, col);
                    if (dirtyChunkCount() > this.maxDirtyChunks) {
                        flushExcessDirtyChunksAsync();
                    }
                    activeBackgroundTasks.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            flushingDirtyChunks.remove(key);
            activeBackgroundTasks.decrementAndGet();
            return false;
        }
    }

    private void executeDirtyChunkFlushOffThread(
            ChunkColumnKey key,
            TrackedChunkColumn col,
            EpochCommitFence fence,
            int localIdx10,
            CompactionSnapshot[] snaps,
            int flushMask,
            int globalPaletteBits
    ) {
        executeDirtyChunkFlushOffThread(
                key, col, fence, localIdx10, snaps, flushMask, null, null, globalPaletteBits);
    }

    private void executeDirtyChunkFlushOffThread(
            ChunkColumnKey key,
            TrackedChunkColumn col,
            EpochCommitFence fence,
            int localIdx10,
            CompactionSnapshot[] snaps,
            int flushMask,
            byte[][] updatedSectionSkyLightOrNull,
            byte[][] updatedSectionBlockLightOrNull,
            int globalPaletteBits
    ) {
        RawChunkFrameSnapshot coldSnap = col.coldSnapshot();
        LcaRegionFileStorage storage = col.backingStorage();
        IntFunction<String> runtimeToCanon = col.runtimeIdToCanonical();
        Function<String, Integer> canonToRuntime = col.canonicalToRuntimeId();

        if (coldSnap != null && storage != null && runtimeToCanon != null) {
            try {
                int regionX = col.chunkX() >> 5;
                int regionZ = col.chunkZ() >> 5;
                int[][] translatedModifiers = new int[snaps.length][];
                for (int s = 0; s < snaps.length; s++) {
                    CompactionSnapshot snap = snaps[s];
                    if (snap == null) {
                        continue;
                    }
                    int[] packedRuntime = snap.frozenOverlay().toCompactPackedArray();
                    int[] packedDict = new int[packedRuntime.length];
                    for (int k = 0; k < packedRuntime.length; k++) {
                        int entry32 = packedRuntime[k];
                        int pos12 = LcaSectionBacking.unpackPos12(entry32);
                        int runtimeId = LcaSectionBacking.unpackStateId19(entry32);
                        int dictId = storage.resolveRuntimeIdToDictId(regionX, regionZ, runtimeId, runtimeToCanon);
                        packedDict[k] = LcaSectionBacking.packEntry32(pos12, dictId);
                    }
                    translatedModifiers[s] = packedDict;
                }

                byte[] newColdFrame = storage.commitAndGetSplicedChunkFrameCoW(
                        col.chunkX(),
                        col.chunkZ(),
                        coldSnap.chunkFrame(),
                        flushMask,
                        secIdx -> (secIdx >= 0 && secIdx < translatedModifiers.length)
                                ? translatedModifiers[secIdx]
                                : null,
                        secIdx -> (updatedSectionSkyLightOrNull != null
                                && secIdx >= 0
                                && secIdx < updatedSectionSkyLightOrNull.length)
                                ? updatedSectionSkyLightOrNull[secIdx]
                                : null,
                        secIdx -> (updatedSectionBlockLightOrNull != null
                                && secIdx >= 0
                                && secIdx < updatedSectionBlockLightOrNull.length)
                                ? updatedSectionBlockLightOrNull[secIdx]
                                : null,
                        null
                );
                RawChunkFrameSnapshot refreshedSnap = storage.readRawChunkFrame(
                        col.chunkX(), col.chunkZ(), canonToRuntime);
                if (refreshedSnap == null) {
                    int newResRel = ((newColdFrame[22] & 0xFF) << 8) | (newColdFrame[23] & 0xFF);
                    refreshedSnap = new RawChunkFrameSnapshot(
                            newColdFrame,
                            coldSnap.dataVersion(),
                            coldSnap.minSectionY(),
                            coldSnap.sectionCount(),
                            newResRel,
                            coldSnap.dictIdToRuntimeId()
                    );
                }
                for (int s = 0; s < snaps.length; s++) {
                    CompactionSnapshot snap = snaps[s];
                    if (snap == null) {
                        continue;
                    }
                    LcaSectionBacking sec = col.section(s);
                    if (sec == null || sec.isUnloaded()) {
                        continue;
                    }
                    int entry16 = refreshedSnap.sectionDirectoryEntry16(s);
                    byte[] newSecBase = (entry16 == 0 || (entry16 & 0x8000) != 0)
                            ? new byte[0]
                            : refreshedSnap.sliceSectionFrameWithRuntimePalette(s, canonToRuntime);
                    int newUniform = (entry16 == 0 || (entry16 & 0x8000) != 0)
                            ? refreshedSnap.resolveInlineUniformRuntimeStateId(s, canonToRuntime)
                            : 0;
                    if (sec.completeCompaction(snap.epoch(), newSecBase, newUniform)) {
                        fence.tryCommitChunkEpoch(localIdx10, snap.epoch());
                    }
                }
                col.installFlushedColdChunkFrame(refreshedSnap, flushMask);
                dirtyOverlayChunkKeys.remove(key);
                return;
            } catch (IOException ioEx) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "[LeafAnvil] Disk CoW section-splice flush failed for ("
                                + col.chunkX() + "," + col.chunkZ() + "), preserving edits in RAM baseFrame: "
                                + ioEx.getMessage(), ioEx);
            }
        }

        for (int s = 0; s < snaps.length; s++) {
            CompactionSnapshot snap = snaps[s];
            if (snap == null) {
                continue;
            }
            LcaSectionBacking sec = col.section(s);
            if (sec != null && !sec.isUnloaded()) {
                if (sec.compactFrozenOverlayOffThread(snap, globalPaletteBits)) {
                    fence.tryCommitChunkEpoch(localIdx10, snap.epoch());
                }
            }
        }
        col.installFlushedColdChunkFrame(null, flushMask);
        dirtyOverlayChunkKeys.remove(key);
    }

    private void pruneCleanChunkFromDirtyLru(ChunkColumnKey key, TrackedChunkColumn col) {
        synchronized (dirtyCacheLock) {
            TrackedChunkColumn current = dirtyChunkLru.get(key);
            if (current != null && (current.isUnloaded() || !current.hasAnyDirtyOverlay())) {
                dirtyChunkLru.remove(key);
            } else if (col != null && !col.isUnloaded() && col.hasAnyDirtyOverlay()) {
                dirtyChunkLru.put(key, col);
            }
        }
    }

    /**
     * Applies multiplicative AIMD score decay across all active dirty chunks under
     * {@code dirtyCacheLock} and flushes any excess victims if over budget (`leafanvil-ADR-006`).
     */
    public int decayDirtyChunkScores() {
        int num = this.aimdDecayNumerator;
        int shift = this.aimdDecayShift;
        List<TrackedChunkColumn> snapshotCols;
        synchronized (dirtyCacheLock) {
            dirtyChunkLru.entrySet().removeIf(e ->
                    e.getValue() == null || e.getValue().isUnloaded() || !e.getValue().hasAnyDirtyOverlay());
            snapshotCols = new ArrayList<>(dirtyChunkLru.values());
        }
        for (TrackedChunkColumn col : snapshotCols) {
            for (int i = 0; i < col.sectionCount(); i++) {
                LcaSectionBacking sec = col.section(i);
                if (sec != null && !sec.isUnloaded()) {
                    sec.applyMultiplicativeScoreDecay(num, shift);
                }
            }
        }
        flushExcessDirtyChunksAsync();
        return dirtyChunkCount();
    }

    @Deprecated
    public int decayHot3dSlabScores() {
        return decayDirtyChunkScores();
    }

    // =========================================================================
    // 2. Paper / Folia Moonrise SimpleRandomTickList Delta Synchronizer (Invariant 4)
    // =========================================================================

    /**
     * Synchronizes Paper/Folia `Moonrise` `SimpleRandomTickList` (`tickingBlocks` / `tickingFluids`)
     * on `LcaSectionBacking` `dirtyOverlay` transitions while maintaining an authoritative
     * coordinate bitset for deterministic verification and fallback.
     */
    public static final class MoonriseTickingListSynchronizer implements LcaSectionBacking.TickingCoordinateCallback {
        private static final boolean LAZY_BITSETS = Boolean.parseBoolean(
                System.getProperty("leafanvil.memory.lazyMoonriseBitSets", "true")
        );

        private record SectionMoonriseAccessor(
                Field blocksField,
                Field fluidsField,
                Method addMethod,
                Method removeMethod
        ) {
        }

        private static final Map<Class<?>, SectionMoonriseAccessor> ACCESSOR_CACHE =
                new ConcurrentHashMap<>();

        private final Object nmsTickingBlocksList;
        private final Object nmsTickingFluidsList;
        private final Method addMethod;
        private final Method removeMethod;
        private BitSet activeTickingBlockPositions;
        private BitSet activeTickingFluidPositions;

        private MoonriseTickingListSynchronizer(
                Object nmsTickingBlocksList,
                Object nmsTickingFluidsList,
                Method addMethod,
                Method removeMethod
        ) {
            this.nmsTickingBlocksList = nmsTickingBlocksList;
            this.nmsTickingFluidsList = nmsTickingFluidsList;
            this.addMethod = addMethod;
            this.removeMethod = removeMethod;
            if (!LAZY_BITSETS) {
                this.activeTickingBlockPositions = new BitSet(LcaSectionBacking.BULK_4096_CAPACITY);
                this.activeTickingFluidPositions = new BitSet(LcaSectionBacking.BULK_4096_CAPACITY);
            }
        }

        public static MoonriseTickingListSynchronizer bindToSection(Object nmsLevelChunkSection) {
            MoonriseTickingListSynchronizer present = bindToSectionIfPresent(nmsLevelChunkSection);
            return present != null ? present : new MoonriseTickingListSynchronizer(null, null, null, null);
        }

        public static MoonriseTickingListSynchronizer bindToSectionIfPresent(Object nmsLevelChunkSection) {
            if (nmsLevelChunkSection == null) {
                return null;
            }
            Class<?> secClass = nmsLevelChunkSection.getClass();
            SectionMoonriseAccessor acc = ACCESSOR_CACHE.computeIfAbsent(secClass, cls -> {
                Field bf = findField(cls, "tickingList", "tickingBlocks", "moonrise$tickingBlocks");
                Field ff = findField(cls, "fluidTickingList", "tickingFluids", "moonrise$tickingFluids");
                Class<?> listType = bf != null ? bf.getType() : (ff != null ? ff.getType() : null);
                Method add = listType != null ? findIntMethod(listType, "add") : null;
                Method remove = listType != null ? findIntMethod(listType, "remove") : null;
                return new SectionMoonriseAccessor(bf, ff, add, remove);
            });
            if (acc.blocksField() == null && acc.fluidsField() == null) {
                return null;
            }
            Object tickingBlocks = readFieldValue(acc.blocksField(), nmsLevelChunkSection);
            Object tickingFluids = readFieldValue(acc.fluidsField(), nmsLevelChunkSection);
            Method add = acc.addMethod();
            Method remove = acc.removeMethod();
            if (add == null || remove == null) {
                Object sample = tickingBlocks != null ? tickingBlocks : tickingFluids;
                if (sample != null) {
                    add = findIntMethod(sample.getClass(), "add");
                    remove = findIntMethod(sample.getClass(), "remove");
                }
            }
            return new MoonriseTickingListSynchronizer(tickingBlocks, tickingFluids, add, remove);
        }

        @Override
        public synchronized void onTickingBlockFlip(
                int pos12,
                int oldStateId,
                int newStateId,
                boolean nowTickingBlock
        ) {
            int maskedPos = pos12 & LcaSectionBacking.MAX_POS_12;
            if (nowTickingBlock) {
                if (activeTickingBlockPositions == null) {
                    activeTickingBlockPositions = new BitSet(LcaSectionBacking.BULK_4096_CAPACITY);
                }
                activeTickingBlockPositions.set(maskedPos, true);
            } else if (activeTickingBlockPositions != null) {
                activeTickingBlockPositions.set(maskedPos, false);
            }
            invokeMoonriseListDelta(nmsTickingBlocksList, maskedPos, nowTickingBlock);
        }

        @Override
        public synchronized void onTickingFluidFlip(
                int pos12,
                int oldStateId,
                int newStateId,
                boolean nowTickingFluid
        ) {
            int maskedPos = pos12 & LcaSectionBacking.MAX_POS_12;
            if (nowTickingFluid) {
                if (activeTickingFluidPositions == null) {
                    activeTickingFluidPositions = new BitSet(LcaSectionBacking.BULK_4096_CAPACITY);
                }
                activeTickingFluidPositions.set(maskedPos, true);
            } else if (activeTickingFluidPositions != null) {
                activeTickingFluidPositions.set(maskedPos, false);
            }
            invokeMoonriseListDelta(nmsTickingFluidsList, maskedPos, nowTickingFluid);
        }

        private void invokeMoonriseListDelta(Object listObj, int pos12, boolean add) {
            if (listObj == null) {
                return;
            }
            try {
                Method target = add ? addMethod : removeMethod;
                if (target != null) {
                    target.invoke(listObj, pos12);
                }
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger(LcaInMemoryChunkCoordinator.class.getName()).log(
                        java.util.logging.Level.FINE,
                        "[LeafAnvil] Moonrise SimpleRandomTickList delta invocation failed",
                        t
                );
            }
        }

        public synchronized boolean isTickingBlockAt(int x, int y, int z) {
            return activeTickingBlockPositions != null
                    && activeTickingBlockPositions.get(LcaSectionBacking.pos12(x, y, z));
        }

        public synchronized boolean isTickingFluidAt(int x, int y, int z) {
            return activeTickingFluidPositions != null
                    && activeTickingFluidPositions.get(LcaSectionBacking.pos12(x, y, z));
        }

        public synchronized int tickingBlockCoordinateCount() {
            return activeTickingBlockPositions != null ? activeTickingBlockPositions.cardinality() : 0;
        }

        public synchronized int tickingFluidCoordinateCount() {
            return activeTickingFluidPositions != null ? activeTickingFluidPositions.cardinality() : 0;
        }

        private static Field findField(Class<?> cls, String... candidateNames) {
            Class<?> c = cls;
            while (c != null && c != Object.class) {
                Field[] declared = c.getDeclaredFields();
                for (String name : candidateNames) {
                    for (Field f : declared) {
                        if (f.getName().equals(name)) {
                            f.setAccessible(true);
                            return f;
                        }
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        }

        private static Object readFieldValue(Field f, Object instance) {
            if (f == null || instance == null) {
                return null;
            }
            try {
                return f.get(instance);
            } catch (ReflectiveOperationException e) {
                java.util.logging.Logger.getLogger(LcaInMemoryChunkCoordinator.class.getName()).log(
                        java.util.logging.Level.FINE,
                        "[LeafAnvil] Could not read Moonrise ticking field " + f.getName(),
                        e
                );
                return null;
            }
        }

        private static Method findIntMethod(Class<?> cls, String name) {
            Class<?> c = cls;
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 1) {
                        Class<?> p = m.getParameterTypes()[0];
                        if (p == int.class || p == short.class) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        }
    }

    public NegotiatedSession registerPlayerHandshake(
            UUID playerId,
            LcaWireStreamProtocol.HandshakePayload clientHandshake,
            long serverRegistryParityHash64
    ) {
        Objects.requireNonNull(playerId, "playerId");
        NegotiatedSession session = LcaWireStreamProtocol.negotiate(
                clientHandshake,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                serverRegistryParityHash64
        );
        playerSessions.compute(playerId, (id, prev) -> {
            boolean wasActive = prev != null && prev.isLcaStreamActive();
            boolean nowActive = session.isLcaStreamActive();
            if (!wasActive && nowActive) {
                activeLcaPlayerCount.incrementAndGet();
            } else if (wasActive && !nowActive) {
                activeLcaPlayerCount.updateAndGet(c -> Math.max(0, c - 1));
            }
            return session;
        });
        return session;
    }

    public NegotiatedSession getPlayerSession(UUID playerId) {
        if (playerId == null) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        return playerSessions.getOrDefault(playerId, NegotiatedSession.VANILLA_FALLBACK);
    }

    public void removePlayerSession(UUID playerId) {
        if (playerId == null) {
            return;
        }
        final boolean[] wasActive = {false};
        playerSessions.computeIfPresent(playerId, (id, prev) -> {
            wasActive[0] = prev.isLcaStreamActive();
            return null;
        });
        if (wasActive[0]) {
            activeLcaPlayerCount.updateAndGet(c -> Math.max(0, c - 1));
        }
        playerKnownDictSlots.remove(playerId);
        playerRegionDigests.keySet().removeIf(k -> k.playerId().equals(playerId));
    }

    /**
     * Returns {@code true} when at least one negotiated {@code LCA_NET_V1} client stream or
     * inter-server ({@code LeafShard}) delta consumer is active.
     */
    public boolean hasActiveWireOrShardConsumers() {
        return activeLcaPlayerCount.get() > 0 || interServerConsumerActive;
    }

    public int activeNegotiatedPlayerCount() {
        return activeLcaPlayerCount.get();
    }

    public int trackedPlayerSessionCount() {
        return playerSessions.size();
    }

    public void setInterServerConsumerActive(boolean active) {
        this.interServerConsumerActive = active;
    }

    /**
     * Removes any tracked player session whose UUID no longer satisfies {@code isPlayerOnline}
     * and returns the number of pruned sessions.
     */
    public int pruneDisconnectedPlayerSessions(java.util.function.Predicate<UUID> isPlayerOnline) {
        Objects.requireNonNull(isPlayerOnline, "isPlayerOnline");
        if (playerSessions.isEmpty()) {
            return 0;
        }
        int pruned = 0;
        for (UUID playerId : new ArrayList<>(playerSessions.keySet())) {
            if (!isPlayerOnline.test(playerId)) {
                removePlayerSession(playerId);
                pruned++;
            }
        }
        return pruned;
    }

    public LcaPacketDifferentialProfiler packetDifferentialProfiler() {
        return packetDifferentialProfiler;
    }

    public LcaOrderlessDictTable orderlessDictTable() {
        return orderlessDictTable;
    }

    public LcaOrderlessDictTable clientOrderlessDictTable() {
        return clientOrderlessDictTable;
    }

    public KnownSlotBitSet getOrCreatePlayerKnownDictSlots(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return playerKnownDictSlots.computeIfAbsent(playerId, id -> new KnownSlotBitSet());
    }

    public int maxServerUploadKbps() {
        return maxServerUploadKbps;
    }

    public void setMaxServerUploadKbps(int maxServerUploadKbps) {
        this.maxServerUploadKbps = Math.max(0, maxServerUploadKbps);
    }

    public void recordPlayerRegionDigest(UUID playerId, ServerboundLcaChunkDigestPayload digest) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(digest, "digest");
        playerRegionDigests.put(
                new PlayerRegionKey(playerId, digest.dimension(), digest.regionX(), digest.regionZ()),
                digest
        );
    }

    public ServerboundLcaChunkDigestPayload getPlayerRegionDigest(
            UUID playerId, String worldName, int regionX, int regionZ
    ) {
        if (playerId == null || worldName == null) {
            return null;
        }
        return playerRegionDigests.get(new PlayerRegionKey(playerId, worldName, regionX, regionZ));
    }

    /**
     * Packs a tick-window batch of `1..16` outbound chunks for a negotiated player into an
     * `LCD2`-compressed (`0x4C44`) `.lca` batch frame (`VarInt = 0` bypass frame or proxy-safe stored-block
     * Zlib frame). Returns {@code null} if the player is on {@link NegotiatedSession#VANILLA_FALLBACK}.
     */
    public byte[] packOutboundChunkBatchForPlayer(
            UUID playerId,
            String worldName,
            int regionX,
            int regionZ,
            List<TrackedChunkColumn> outboundColumns,
            boolean useProxySafeStoredZlib
    ) throws IOException {
        NegotiatedSession session = getPlayerSession(playerId);
        if (!session.isLcaStreamActive() || outboundColumns == null || outboundColumns.isEmpty()) {
            return null;
        }
        String normWorld = normalizeWorldKey(worldName);
        ServerboundLcaChunkDigestPayload digest = playerRegionDigests.get(
                new PlayerRegionKey(playerId, normWorld, regionX, regionZ)
        );
        if (digest == null && !normWorld.equals(worldName)) {
            digest = playerRegionDigests.get(
                    new PlayerRegionKey(playerId, worldName, regionX, regionZ)
            );
        }
        int centerChunkX = outboundColumns.get(0).chunkX();
        int centerChunkZ = outboundColumns.get(0).chunkZ();
        List<TrackedChunkColumn> prioritized = LcaMultiTargetConsolidator.sortColumnsBySpiralRingPriority(
                centerChunkX,
                centerChunkZ,
                outboundColumns
        );
        KnownSlotBitSet knownSlots = session.isOrderlessDictAndMulticastEnabled()
                ? getOrCreatePlayerKnownDictSlots(playerId)
                : null;
        List<BatchChunkEntry> entries = new ArrayList<>(Math.min(prioritized.size(), LcaWireStreamProtocol.MAX_BATCH_CHUNKS));
        for (int i = 0; i < prioritized.size() && i < LcaWireStreamProtocol.MAX_BATCH_CHUNKS; i++) {
            TrackedChunkColumn col = prioritized.get(i);
            EntryFullChunk currentFull = col.toWireFullChunkEntry(col.lastUpdateTick());
            ChunkDigestEntry clientDigestEntry = (digest != null && session.isDeltaRevisitCacheEnabled())
                    ? digest.findChunk(currentFull.localChunkIndex10())
                    : null;
            BatchChunkEntry resolved = LcaWireStreamProtocol.resolveServerRevisitEntry(
                    currentFull,
                    clientDigestEntry,
                    col.baselineSecDed64(),
                    col.dirtySectionBitmask32()
            );
            entries.add(resolved);
        }
        ClientboundLcaChunkBatchPayload batchPayload = new ClientboundLcaChunkBatchPayload(regionX, regionZ, entries);
        byte[] lcd2Payload = LcaWireStreamProtocol.encodeChunkBatchPayload(
                batchPayload,
                session,
                orderlessDictTable,
                knownSlots
        );
        byte[] framed = (useProxySafeStoredZlib && session.isProxySafeStoredZlibEnabled())
                ? LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(lcd2Payload)
                : LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(lcd2Payload);
        int packedCount = entries.size();
        packetDifferentialProfiler.recordPacketDifferential(
                LcaPacketDifferentialProfiler.BUCKET_CHUNK_STREAM,
                packedCount + 2,
                packedCount * 28_672,
                packedCount * 9_728,
                packedCount,
                1,
                framed.length,
                1
        );
        packetDifferentialProfiler.advanceTickWindow();
        return framed;
    }

    /**
     * Region-scoped `.lca` batch frame produced by {@link #packByteBudgetedOutboundBatchesForPlayer}.
     */
    public record RegionPackedBatchFrame(
            int regionX,
            int regionZ,
            ClientboundLcaChunkBatchPayload payload,
            byte[] rawBatchBytes,
            byte[] framedWireBytes,
            List<TrackedChunkColumn> packedColumns,
            int cleanAckCount,
            int patchDeltaCount,
            int fullChunkCount
    ) {
        public RegionPackedBatchFrame {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(rawBatchBytes, "rawBatchBytes");
            Objects.requireNonNull(framedWireBytes, "framedWireBytes");
            Objects.requireNonNull(packedColumns, "packedColumns");
            packedColumns = List.copyOf(packedColumns);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RegionPackedBatchFrame other)) {
                return false;
            }
            return this.regionX == other.regionX
                    && this.regionZ == other.regionZ
                    && java.util.Objects.equals(this.payload, other.payload)
                    && java.util.Arrays.equals(this.rawBatchBytes, other.rawBatchBytes)
                    && java.util.Arrays.equals(this.framedWireBytes, other.framedWireBytes)
                    && java.util.Objects.equals(this.packedColumns, other.packedColumns)
                    && this.cleanAckCount == other.cleanAckCount
                    && this.patchDeltaCount == other.patchDeltaCount
                    && this.fullChunkCount == other.fullChunkCount;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.regionX;
            result = 31 * result + this.regionZ;
            result = 31 * result + java.util.Objects.hashCode(this.payload);
            result = 31 * result + java.util.Arrays.hashCode(this.rawBatchBytes);
            result = 31 * result + java.util.Arrays.hashCode(this.framedWireBytes);
            result = 31 * result + java.util.Objects.hashCode(this.packedColumns);
            result = 31 * result + this.cleanAckCount;
            result = 31 * result + this.patchDeltaCount;
            result = 31 * result + this.fullChunkCount;
            return result;
        }

        @Override
        public String toString() {
            return "RegionPackedBatchFrame[regionX=" + this.regionX
                    + ", regionZ=" + this.regionZ
                    + ", payload=" + this.payload
                    + ", rawBatchBytes=" + java.util.Arrays.toString(this.rawBatchBytes)
                    + ", framedWireBytes=" + java.util.Arrays.toString(this.framedWireBytes)
                    + ", packedColumns=" + this.packedColumns
                    + ", cleanAckCount=" + this.cleanAckCount
                    + ", patchDeltaCount=" + this.patchDeltaCount
                    + ", fullChunkCount=" + this.fullChunkCount
                    + "]";
        }
    }

    /**
     * Per-tick wire-byte-budgeted outbound chunk packing plan across one or more `32 x 32` regions.
     */
    public record ByteBudgetPackedTickPlan(
            int vanillaChunkCredits,
            int targetWireBudgetBytes,
            int totalRawBatchBytes,
            int totalFramedWireBytes,
            List<RegionPackedBatchFrame> regionFrames,
            List<TrackedChunkColumn> packedColumns,
            List<TrackedChunkColumn> deferredColumns
    ) {
        public ByteBudgetPackedTickPlan {
            Objects.requireNonNull(regionFrames, "regionFrames");
            Objects.requireNonNull(packedColumns, "packedColumns");
            Objects.requireNonNull(deferredColumns, "deferredColumns");
            regionFrames = List.copyOf(regionFrames);
            packedColumns = List.copyOf(packedColumns);
            deferredColumns = List.copyOf(deferredColumns);
        }

        public int totalPackedChunks() {
            return packedColumns.size();
        }

        public int totalDeferredChunks() {
            return deferredColumns.size();
        }

        public double effectiveChunksPerVanillaCredit() {
            if (vanillaChunkCredits <= 0) {
                return 0.0;
            }
            return (double) packedColumns.size() / (double) vanillaChunkCredits;
        }
    }

    private static final class RegionBatchAccumulator {
        final int regionX;
        final int regionZ;
        final List<BatchChunkEntry> entries = new ArrayList<>(LcaWireStreamProtocol.MAX_BATCH_CHUNKS);
        final List<TrackedChunkColumn> columns = new ArrayList<>(LcaWireStreamProtocol.MAX_BATCH_CHUNKS);
        int rawBatchBytes;
        int framedWireBytes;
        int cleanAckCount;
        int patchDeltaCount;
        int fullChunkCount;

        RegionBatchAccumulator(int regionX, int regionZ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        boolean isFull() {
            return entries.size() >= LcaWireStreamProtocol.MAX_BATCH_CHUNKS;
        }
    }

    /**
     * Converts vanilla {@code PlayerChunkSender} chunk credits into a bounded per-tick wire byte
     * budget (`10 KiB` per credit, clamped to `[4 KiB .. 32 KiB]`).
     */
    public static int computeTickWireByteBudget(int vanillaChunkCredits) {
        return computeTickWireByteBudget(
                vanillaChunkCredits,
                DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT,
                DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES
        );
    }

    /**
     * Converts vanilla {@code PlayerChunkSender} chunk credits into a bounded per-tick wire byte
     * budget using custom credit and whip-saw ceiling bounds.
     */
    public static int computeTickWireByteBudget(
            int vanillaChunkCredits,
            int bytesPerVanillaCredit,
            int maxTickWireBudgetBytes
    ) {
        if (bytesPerVanillaCredit <= 0) {
            throw new IllegalArgumentException("bytesPerVanillaCredit must be > 0: " + bytesPerVanillaCredit);
        }
        if (maxTickWireBudgetBytes <= 0) {
            throw new IllegalArgumentException("maxTickWireBudgetBytes must be > 0: " + maxTickWireBudgetBytes);
        }
        if (vanillaChunkCredits <= 0) {
            return 0;
        }
        long unClamped = (long) vanillaChunkCredits * (long) bytesPerVanillaCredit;
        int minFloor = Math.min(MIN_TICK_WIRE_BUDGET_BYTES, maxTickWireBudgetBytes);
        return (int) Math.max(minFloor, Math.min(maxTickWireBudgetBytes, unClamped));
    }

    /**
     * Computes the exact pre-serialization raw wire byte size of a {@link BatchChunkEntry} in $O(1)$
     * without allocating intermediate streams.
     */
    public static int computeEntryWireBytes(BatchChunkEntry entry, boolean omitHeightmaps) {
        Objects.requireNonNull(entry, "entry");
        if (entry instanceof LcaWireStreamProtocol.EntryCleanAck) {
            return 2;
        }
        if (entry instanceof EntryPatchDelta patch) {
            int bytes = 2
                    + varLongByteLength(patch.newLastUpdateTick())
                    + 8
                    + 4;
            for (SectionWireSlice slice : patch.modifiedSections()) {
                bytes += computeSectionWireSliceBytes(slice);
            }
            return bytes;
        }
        if (entry instanceof EntryFullChunk full) {
            int sectionCount = full.sections().size();
            int bytes = 2
                    + varIntByteLength(full.chunkX())
                    + varIntByteLength(full.chunkZ())
                    + varLongByteLength(full.lastUpdateTick())
                    + 8
                    + 1
                    + (sectionCount * 2)
                    + 4;
            for (int i = 0; i < sectionCount; i++) {
                SectionWireSlice slice = full.sections().get(i);
                if (!LcaWireStreamProtocol.isUniformDirectoryWord(slice.directoryWord16())) {
                    bytes += (slice.directoryWord16() & 0x7FFF);
                }
                int overlayLen = slice.dirtyOverlayEntries().length;
                if (overlayLen > 0) {
                    bytes += varIntByteLength(overlayLen) + (overlayLen * 4);
                }
            }
            if (!omitHeightmaps) {
                int hmLen = full.heightmapDeltaBytes().length;
                if (hmLen > 0) {
                    bytes += varIntByteLength(hmLen) + hmLen;
                }
            }
            return bytes;
        }
        throw new IllegalArgumentException("Unsupported BatchChunkEntry type: " + entry.getClass().getName());
    }

    /**
     * Computes the exact serialized byte length of a {@link ClientboundLcaChunkBatchPayload}
     * produced by {@link LcaWireStreamProtocol#serializeRawChunkBatch}.
     */
    public static int computeRawBatchWireBytes(
            int regionX,
            int regionZ,
            List<BatchChunkEntry> entries,
            boolean omitHeightmaps
    ) {
        Objects.requireNonNull(entries, "entries");
        int bytes = computeBatchHeaderWireBytes(regionX, regionZ);
        for (BatchChunkEntry entry : entries) {
            bytes += computeEntryWireBytes(entry, omitHeightmaps);
        }
        return bytes;
    }

    /**
     * Computes the exact Netty-framed wire byte length of a raw batch payload when wrapped with
     * {@link LcaWireStreamProtocol#encodeProxySafeZlibStoredFrame} or
     * {@link LcaWireStreamProtocol#encodeNettyVarIntZeroBypassFrame}.
     */
    public static int computeFramedBatchWireBytes(int rawBatchBytes, boolean useProxySafeStoredZlib) {
        if (rawBatchBytes < 0) {
            throw new IllegalArgumentException("rawBatchBytes must be >= 0: " + rawBatchBytes);
        }
        if (useProxySafeStoredZlib) {
            int blockCount = Math.max(1, (rawBatchBytes + 65534) / 65535);
            int zlibStreamLen = 2 + (blockCount * 5) + rawBatchBytes + 4;
            int dataLenVarIntBytes = varIntByteLength(rawBatchBytes);
            int packetLength = dataLenVarIntBytes + zlibStreamLen;
            return varIntByteLength(packetLength) + packetLength;
        }
        int packetLength = 1 + rawBatchBytes;
        return varIntByteLength(packetLength) + 1 + rawBatchBytes;
    }

    /**
     * Packs candidate outbound chunk columns for a negotiated player into pure `LCD2` (`0x4C44`) `.lca` region
     * batch frames governed by the saved-upload-space byte budget (`10 KiB` per vanilla chunk credit,
     * `32 KiB` per-tick whip-saw ceiling).
     */
    public ByteBudgetPackedTickPlan packByteBudgetedOutboundBatchesForPlayer(
            UUID playerId,
            String worldName,
            List<TrackedChunkColumn> candidateColumns,
            int vanillaChunkCredits,
            boolean useProxySafeStoredZlib
    ) {
        return packByteBudgetedOutboundBatchesForPlayer(
                playerId,
                worldName,
                candidateColumns,
                vanillaChunkCredits,
                DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT,
                DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES,
                useProxySafeStoredZlib
        );
    }

    /**
     * Packs candidate outbound chunk columns for a negotiated player into pure `LCD2` (`0x4C44`) `.lca` region
     * batch frames governed by the specified per-credit wire byte budget and per-tick whip-saw ceiling.
     */
    public ByteBudgetPackedTickPlan packByteBudgetedOutboundBatchesForPlayer(
            UUID playerId,
            String worldName,
            List<TrackedChunkColumn> candidateColumns,
            int vanillaChunkCredits,
            int bytesPerVanillaCredit,
            int maxTickWireBudgetBytes,
            boolean useProxySafeStoredZlib
    ) {
        int targetWireBudgetBytes = computeTickWireByteBudget(
                vanillaChunkCredits,
                bytesPerVanillaCredit,
                maxTickWireBudgetBytes
        );
        NegotiatedSession session = getPlayerSession(playerId);
        if (!session.isLcaStreamActive()
                || candidateColumns == null
                || candidateColumns.isEmpty()
                || vanillaChunkCredits <= 0) {
            List<TrackedChunkColumn> deferred = candidateColumns == null
                    ? List.of()
                    : List.copyOf(candidateColumns);
            return new ByteBudgetPackedTickPlan(
                    Math.max(0, vanillaChunkCredits),
                    targetWireBudgetBytes,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    deferred
            );
        }

        String normWorld = normalizeWorldKey(worldName);
        boolean omitHeightmaps = session.isClientHeightmapsEnabled();
        boolean effectiveStoredZlib = useProxySafeStoredZlib && session.isProxySafeStoredZlibEnabled();

        List<RegionBatchAccumulator> accumulators = new ArrayList<>(4);
        List<TrackedChunkColumn> packedColumns = new ArrayList<>(candidateColumns.size());
        List<TrackedChunkColumn> deferredColumns = new ArrayList<>();
        int currentTotalFramedBytes = 0;

        for (TrackedChunkColumn col : candidateColumns) {
            if (col == null) {
                continue;
            }
            int regionX = col.chunkX() >> 5;
            int regionZ = col.chunkZ() >> 5;
            ServerboundLcaChunkDigestPayload digest = playerRegionDigests.get(
                    new PlayerRegionKey(playerId, normWorld, regionX, regionZ)
            );
            if (digest == null && !normWorld.equals(worldName)) {
                digest = playerRegionDigests.get(
                        new PlayerRegionKey(playerId, worldName, regionX, regionZ)
                );
            }
            EntryFullChunk currentFull = col.toWireFullChunkEntry(col.lastUpdateTick());
            ChunkDigestEntry clientDigestEntry = (digest != null && session.isDeltaRevisitCacheEnabled())
                    ? digest.findChunk(currentFull.localChunkIndex10())
                    : null;
            BatchChunkEntry resolved = LcaWireStreamProtocol.resolveServerRevisitEntry(
                    currentFull,
                    clientDigestEntry,
                    col.baselineSecDed64(),
                    col.dirtySectionBitmask32()
            );
            int entryWireBytes = computeEntryWireBytes(resolved, omitHeightmaps);

            RegionBatchAccumulator openAcc = findOpenRegionAccumulator(accumulators, regionX, regionZ);
            int newRawBytes;
            int newFramedBytes;
            int projectedTotalFramedBytes;
            if (openAcc != null) {
                newRawBytes = openAcc.rawBatchBytes + entryWireBytes;
                newFramedBytes = computeFramedBatchWireBytes(newRawBytes, effectiveStoredZlib);
                projectedTotalFramedBytes = currentTotalFramedBytes - openAcc.framedWireBytes + newFramedBytes;
            } else {
                newRawBytes = computeBatchHeaderWireBytes(regionX, regionZ) + entryWireBytes;
                newFramedBytes = computeFramedBatchWireBytes(newRawBytes, effectiveStoredZlib);
                projectedTotalFramedBytes = currentTotalFramedBytes + newFramedBytes;
            }

            if (packedColumns.isEmpty() || projectedTotalFramedBytes <= targetWireBudgetBytes) {
                if (openAcc == null) {
                    openAcc = new RegionBatchAccumulator(regionX, regionZ);
                    accumulators.add(openAcc);
                }
                openAcc.entries.add(resolved);
                openAcc.columns.add(col);
                openAcc.rawBatchBytes = newRawBytes;
                openAcc.framedWireBytes = newFramedBytes;
                if (resolved instanceof LcaWireStreamProtocol.EntryCleanAck) {
                    openAcc.cleanAckCount++;
                } else if (resolved instanceof EntryPatchDelta) {
                    openAcc.patchDeltaCount++;
                } else {
                    openAcc.fullChunkCount++;
                }
                currentTotalFramedBytes = projectedTotalFramedBytes;
                packedColumns.add(col);
            } else {
                deferredColumns.add(col);
            }
        }

        List<RegionPackedBatchFrame> regionFrames = new ArrayList<>(accumulators.size());
        int totalRawBatchBytes = 0;
        int totalFramedWireBytes = 0;
        KnownSlotBitSet knownSlots = session.isOrderlessDictAndMulticastEnabled()
                ? getOrCreatePlayerKnownDictSlots(playerId)
                : null;
        for (RegionBatchAccumulator acc : accumulators) {
            ClientboundLcaChunkBatchPayload batchPayload = new ClientboundLcaChunkBatchPayload(
                    acc.regionX,
                    acc.regionZ,
                    acc.entries
            );
            byte[] rawBytes = LcaWireStreamProtocol.serializeRawChunkBatch(
                    batchPayload,
                    session,
                    orderlessDictTable,
                    knownSlots
            );
            byte[] framedBytes = effectiveStoredZlib
                    ? LcaWireStreamProtocol.encodeProxySafeZlibStoredFrame(rawBytes)
                    : LcaWireStreamProtocol.encodeNettyVarIntZeroBypassFrame(rawBytes);
            totalRawBatchBytes += rawBytes.length;
            totalFramedWireBytes += framedBytes.length;
            regionFrames.add(new RegionPackedBatchFrame(
                    acc.regionX,
                    acc.regionZ,
                    batchPayload,
                    rawBytes,
                    framedBytes,
                    acc.columns,
                    acc.cleanAckCount,
                    acc.patchDeltaCount,
                    acc.fullChunkCount
            ));
        }

        return new ByteBudgetPackedTickPlan(
                vanillaChunkCredits,
                targetWireBudgetBytes,
                totalRawBatchBytes,
                totalFramedWireBytes,
                regionFrames,
                packedColumns,
                deferredColumns
        );
    }

    /**
     * Generic outbound chunk batch packer that orders candidates by Chebyshev spiral priority ring
     * (`Ring 0` {@code r <= 2} immediate playable terrain first), promotes recurring non-uniform section
     * slices into {@link #orderlessDictTable()} when {@code CAP_ORDERLESS_DICT_AND_MULTICAST} is
     * negotiated, paces against any configured {@link #maxServerUploadKbps()} ceiling (or full
     * per-credit budget when `0`), and records the Layer-7 and Layer-3/Layer-4 wire differential
     * into {@link #packetDifferentialProfiler()}.
     */
    public ByteBudgetPackedTickPlan packOptimizedOutboundBatchesForPlayer(
            UUID playerId,
            String worldName,
            int playerChunkX,
            int playerChunkZ,
            List<TrackedChunkColumn> candidateColumns,
            int vanillaChunkCredits,
            int activeStreamingPlayers,
            boolean useProxySafeStoredZlib
    ) {
        return packOptimizedOutboundBatchesWithCeiling(
                playerId,
                worldName,
                playerChunkX,
                playerChunkZ,
                candidateColumns,
                vanillaChunkCredits,
                activeStreamingPlayers,
                maxServerUploadKbps,
                useProxySafeStoredZlib
        );
    }

    /**
     * Packs outbound chunks for a player on a `500 kbps` (`350 kbps` usable) uplink, ordering
     * candidates by spiral priority ring (`Ring 0` {@code r <= 2} immediate playable terrain first),
     * deducting current-tick non-chunk and TCP framing traffic from the global server upload
     * ceiling, and recording the chunk packet/byte differential into {@link #packetDifferentialProfiler()}.
     */
    public ByteBudgetPackedTickPlan packLowBandwidth500kOutboundBatchesForPlayer(
            UUID playerId,
            String worldName,
            int playerChunkX,
            int playerChunkZ,
            List<TrackedChunkColumn> candidateColumns,
            int vanillaChunkCredits,
            int activeStreamingPlayers,
            boolean useProxySafeStoredZlib
    ) {
        int effectiveKbps = maxServerUploadKbps > 0
                ? maxServerUploadKbps
                : LcaPacketDifferentialProfiler.PRACTICAL_500K_USABLE_KBPS;
        return packOptimizedOutboundBatchesWithCeiling(
                playerId,
                worldName,
                playerChunkX,
                playerChunkZ,
                candidateColumns,
                vanillaChunkCredits,
                activeStreamingPlayers,
                effectiveKbps,
                useProxySafeStoredZlib
        );
    }

    private ByteBudgetPackedTickPlan packOptimizedOutboundBatchesWithCeiling(
            UUID playerId,
            String worldName,
            int playerChunkX,
            int playerChunkZ,
            List<TrackedChunkColumn> candidateColumns,
            int vanillaChunkCredits,
            int activeStreamingPlayers,
            int usableKbpsCeiling,
            boolean useProxySafeStoredZlib
    ) {
        List<TrackedChunkColumn> prioritized = candidateColumns == null
                ? List.of()
                : LcaMultiTargetConsolidator.sortColumnsBySpiralRingPriority(
                        playerChunkX,
                        playerChunkZ,
                        candidateColumns
                );
        int bytesPerCredit = DEFAULT_BYTES_PER_VANILLA_CHUNK_CREDIT;
        int maxTickBudget = DEFAULT_MAX_TICK_WIRE_BUDGET_BYTES;
        if (usableKbpsCeiling > 0) {
            int remainingGlobalChunkBytes = packetDifferentialProfiler.remainingTickChunkBudgetBytes(usableKbpsCeiling);
            int players = Math.max(1, activeStreamingPlayers);
            int perPlayerTickCap = Math.max(
                    LcaMultiTargetConsolidator.LOW_BANDWIDTH_MIN_PLAYER_BUDGET_BYTES,
                    (remainingGlobalChunkBytes - (players * LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT))
                            / players
            );
            bytesPerCredit = perPlayerTickCap;
            maxTickBudget = perPlayerTickCap;
        }
        ByteBudgetPackedTickPlan plan = packByteBudgetedOutboundBatchesForPlayer(
                playerId,
                worldName,
                prioritized,
                vanillaChunkCredits,
                bytesPerCredit,
                maxTickBudget,
                useProxySafeStoredZlib
        );
        if (!plan.packedColumns().isEmpty()) {
            int packedCount = plan.packedColumns().size();
            int vanillaEstimatedWireBytes = packedCount * 9_728;
            int optimizedWireBytes = Math.max(2, plan.totalFramedWireBytes());
            packetDifferentialProfiler.recordPacketDifferential(
                    LcaPacketDifferentialProfiler.BUCKET_CHUNK_STREAM,
                    packedCount + 2,
                    packedCount * 28_672,
                    vanillaEstimatedWireBytes,
                    packedCount,
                    plan.regionFrames().size(),
                    optimizedWireBytes,
                    1
            );
            packetDifferentialProfiler.advanceTickWindow();
        }
        return plan;
    }

    private static RegionBatchAccumulator findOpenRegionAccumulator(
            List<RegionBatchAccumulator> accumulators,
            int regionX,
            int regionZ
    ) {
        for (int i = accumulators.size() - 1; i >= 0; i--) {
            RegionBatchAccumulator acc = accumulators.get(i);
            if (acc.regionX == regionX && acc.regionZ == regionZ && !acc.isFull()) {
                return acc;
            }
        }
        return null;
    }

    private static int computeBatchHeaderWireBytes(int regionX, int regionZ) {
        return 4 + varIntByteLength(regionX) + varIntByteLength(regionZ) + 1;
    }

    private static int computeSectionWireSliceBytes(SectionWireSlice slice) {
        int bytes = 2;
        if (!LcaWireStreamProtocol.isUniformDirectoryWord(slice.directoryWord16())) {
            bytes += (slice.directoryWord16() & 0x7FFF);
        }
        int overlayLen = slice.dirtyOverlayEntries().length;
        bytes += varIntByteLength(overlayLen) + (overlayLen * 4);
        return bytes;
    }

    private static int varIntByteLength(int value) {
        int v = value;
        int bytes = 1;
        while ((v & ~0x7F) != 0) {
            bytes++;
            v >>>= 7;
        }
        return bytes;
    }

    private static int varLongByteLength(long value) {
        long v = value;
        int bytes = 1;
        while ((v & ~0x7FL) != 0L) {
            bytes++;
            v >>>= 7;
        }
        return bytes;
    }

    private static boolean isRawBatchMagic(byte[] payload) {
        return payload != null
                && payload.length >= 4
                && (((payload[0] & 0xFF) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF)) == BATCH_MAGIC;
    }

    private static boolean isMultiTargetFrameMagic(byte[] payload) {
        return payload != null
                && payload.length >= 4
                && (((payload[0] & 0xFF) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF)) == LcaMultiTargetConsolidator.MULTI_TARGET_FRAME_MAGIC;
    }

    private static boolean isDirectInnerPayloadMagic(byte[] payload) {
        if (isRawBatchMagic(payload) || isMultiTargetFrameMagic(payload)) {
            return true;
        }
        return payload != null
                && payload.length >= 2
                && (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) == 0x4C44; // "LD" (LCD2 batch header)
    }

    /**
     * Receives a framed `.lca` chunk batch on the client, reserves the strict Netty arrival
     * sequence number on the calling Netty thread, expands any `ODCT`-encoded `SectionWireSlice`s
     * via {@link #clientOrderlessDictTable()}, verifies Extended Hamming `(72, 64)` SEC-DED
     * syndromes off-thread, and queues the hydrated {@link LcaSectionBacking} commit in strict
     * Netty arrival order (`Invariant 3`, `REQ-NET-ODCT-002`).
     */
    public long receiveClientChunkBatchOrdered(
            String dimension,
            byte[] framedBatchBytes,
            boolean proxySafeStoredZlibFrame,
            NegotiatedSession session,
            Function<SectionWireSlice, LcaSectionBacking> sectionHydrator,
            java.util.function.Consumer<HydratedClientChunkCommit> mainThreadChunkAttacher
    ) throws IOException {
        return receiveClientChunkBatchOrdered(
                dimension,
                framedBatchBytes,
                proxySafeStoredZlibFrame,
                session,
                clientOrderlessDictTable,
                sectionHydrator,
                mainThreadChunkAttacher
        );
    }

    public long receiveClientChunkBatchOrdered(
            String dimension,
            byte[] framedBatchBytes,
            boolean proxySafeStoredZlibFrame,
            NegotiatedSession session,
            LcaOrderlessDictTable clientDictTableOrNull,
            Function<SectionWireSlice, LcaSectionBacking> sectionHydrator,
            java.util.function.Consumer<HydratedClientChunkCommit> mainThreadChunkAttacher
    ) throws IOException {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(framedBatchBytes, "framedBatchBytes");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(mainThreadChunkAttacher, "mainThreadChunkAttacher");

        // 1. Reserve monotonic Netty arrival sequence number BEFORE off-thread decode
        long arrivalSeq = clientCommitQueue.reserveNettyArrivalSequence();
        boolean published = false;
        try {
            // 2. Unwrap Netty frame, expand MTCF/ODCT section references, decode .lca batch & verify SEC-DED off-thread
            LcaOrderlessDictTable effectiveClientDict = clientDictTableOrNull != null
                    ? clientDictTableOrNull
                    : clientOrderlessDictTable;
            byte[] innerPayload = isDirectInnerPayloadMagic(framedBatchBytes)
                    ? framedBatchBytes
                    : (proxySafeStoredZlibFrame
                            ? LcaWireStreamProtocol.decodeProxySafeZlibStoredFrame(framedBatchBytes)
                            : LcaWireStreamProtocol.decodeNettyVarIntZeroBypassFrame(framedBatchBytes));
            if (isMultiTargetFrameMagic(innerPayload)) {
                LcaMultiTargetConsolidator.DecodedMultiTargetFrame mtcf =
                        LcaMultiTargetConsolidator.decodeMultiTargetTickFrame(innerPayload, effectiveClientDict);
                innerPayload = mtcf.sharedChunkBatchBytes();
                if (innerPayload.length < 2) {
                    clientCommitQueue.publishReadyAtSequence(arrivalSeq, () -> {});
                    published = true;
                    return arrivalSeq;
                }
            }
            ClientboundLcaChunkBatchPayload decodedBatch = isRawBatchMagic(innerPayload)
                    ? LcaWireStreamProtocol.deserializeRawChunkBatch(innerPayload, effectiveClientDict)
                    : LcaWireStreamProtocol.decodeChunkBatchPayload(innerPayload, session, effectiveClientDict);
            Function<SectionWireSlice, LcaSectionBacking> hydrator = sectionHydrator != null
                    ? sectionHydrator
                    : LcaWireStreamProtocol::hydrateDefaultSectionBacking;

            List<HydratedClientChunkCommit> hydratedCommits = new ArrayList<>(decodedBatch.entries().size());
            for (BatchChunkEntry entry : decodedBatch.entries()) {
                ClientRevisitApplyResult revisitResult = LcaWireStreamProtocol.applyClientRevisitEntry(
                        clientSlabCache,
                        dimension,
                        decodedBatch.regionX(),
                        decodedBatch.regionZ(),
                        entry,
                        null
                );
                if (revisitResult.resolvedChunk() != null) {
                    hydratedCommits.add(LcaWireStreamProtocol.hydrateClientChunkOffThread(
                            dimension,
                            revisitResult.resolvedChunk(),
                            hydrator
                    ));
                }
            }

            // 3. Publish ready commit action at its exact Netty arrival sequence number
            clientCommitQueue.publishReadyAtSequence(arrivalSeq, () -> {
                for (HydratedClientChunkCommit commit : hydratedCommits) {
                    attachChunkColumn(
                            commit.dimension(),
                            commit.chunkX(),
                            commit.chunkZ(),
                            commit.sectionBackings(),
                            null,
                            commit.lastUpdateTick()
                    );
                    mainThreadChunkAttacher.accept(commit);
                }
            });
            published = true;
            return arrivalSeq;
        } finally {
            if (!published) {
                // Release reserved sequence slot so one decode error never head-of-line deadlocks subsequent chunks (S-004).
                clientCommitQueue.publishReadyAtSequence(arrivalSeq, () -> {});
            }
        }
    }

    public ClientOrderedBatchCommitQueue clientCommitQueue() {
        return clientCommitQueue;
    }

    public ClientChunkSlabCache clientSlabCache() {
        return clientSlabCache;
    }

    // =========================================================================
    // 4. Inter-Server Zero-Translation Chunk & Section Delta Sync (`LCAS`)
    // =========================================================================

    /**
     * Main-thread / Folia region-thread commit record emitted after an incoming inter-server
     * chunk delta has been verified and folded into {@link LcaSectionBacking} off-thread.
     */
    public record InterServerSectionBlockCommit(
            String worldKey,
            int chunkX,
            int chunkZ,
            int sectionIndex,
            int[] packedOverlayEntries,
            long updateTick
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof InterServerSectionBlockCommit other)) {
                return false;
            }
            return java.util.Objects.equals(this.worldKey, other.worldKey)
                    && this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && this.sectionIndex == other.sectionIndex
                    && java.util.Arrays.equals(this.packedOverlayEntries, other.packedOverlayEntries)
                    && this.updateTick == other.updateTick;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.worldKey);
            result = 31 * result + this.chunkX;
            result = 31 * result + this.chunkZ;
            result = 31 * result + this.sectionIndex;
            result = 31 * result + java.util.Arrays.hashCode(this.packedOverlayEntries);
            result = 31 * result + Long.hashCode(this.updateTick);
            return result;
        }

        @Override
        public String toString() {
            return "InterServerSectionBlockCommit[worldKey=" + this.worldKey
                    + ", chunkX=" + this.chunkX
                    + ", chunkZ=" + this.chunkZ
                    + ", sectionIndex=" + this.sectionIndex
                    + ", packedOverlayEntries=" + java.util.Arrays.toString(this.packedOverlayEntries)
                    + ", updateTick=" + this.updateTick
                    + "]";
        }
    }

    private final ConcurrentLinkedQueue<InterServerSectionBlockCommit> pendingInterServerCommits =
            new ConcurrentLinkedQueue<>();

    /**
     * Exports all dirty {@link TrackedChunkColumn}s (`dirtySectionBitmask32 != 0`) in the specified
     * `(worldName, regionX, regionZ)` as a zero-translation {@link LcaWireStreamProtocol.InterServerChunkUpdatePayload},
     * or returns {@code null} when no chunks in that region have pending section deltas.
     */
    public LcaWireStreamProtocol.InterServerChunkUpdatePayload exportDirtyChunkDeltasForInterServerSync(
            String sourceServerId,
            String worldName,
            int regionX,
            int regionZ,
            long syncTick,
            long epochSequence,
            boolean advanceBaseline
    ) {
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        String normWorld = normalizeWorldKey(worldName);
        List<LcaWireStreamProtocol.InterServerChunkDeltaEntry> deltas = new ArrayList<>();
        for (Map.Entry<ChunkColumnKey, TrackedChunkColumn> e : activeChunks.entrySet()) {
            ChunkColumnKey key = e.getKey();
            if (!key.worldKey().equals(normWorld)
                    || (key.chunkX() >> 5) != regionX
                    || (key.chunkZ() >> 5) != regionZ) {
                continue;
            }
            TrackedChunkColumn col = e.getValue();
            if (col != null && col.dirtySectionBitmask32() != 0) {
                deltas.add(col.exportInterServerDeltaEntry(syncTick, advanceBaseline));
            }
        }
        if (deltas.isEmpty()) {
            return null;
        }
        return new LcaWireStreamProtocol.InterServerChunkUpdatePayload(
                sourceServerId,
                normWorld,
                regionX,
                regionZ,
                epochSequence,
                deltas
        );
    }

    /**
     * Exports a single chunk column as a full `.lca` {@link LcaWireStreamProtocol.InterServerChunkUpdatePayload}
     * for cross-server `/rtp` destination pre-warming or peer full-chunk refill.
     */
    public LcaWireStreamProtocol.InterServerChunkUpdatePayload exportChunkForInterServerTransfer(
            String sourceServerId,
            String worldName,
            int chunkX,
            int chunkZ,
            long syncTick,
            long epochSequence
    ) {
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        String normWorld = normalizeWorldKey(worldName);
        TrackedChunkColumn col = getTrackedChunk(normWorld, chunkX, chunkZ);
        if (col == null) {
            return null;
        }
        EntryFullChunk full = col.toWireFullChunkEntry(syncTick);
        LcaWireStreamProtocol.InterServerChunkDeltaEntry entry =
                new LcaWireStreamProtocol.InterServerChunkDeltaEntry(
                        chunkX,
                        chunkZ,
                        0L,
                        full
                );
        return new LcaWireStreamProtocol.InterServerChunkUpdatePayload(
                sourceServerId,
                normWorld,
                chunkX >> 5,
                chunkZ >> 5,
                epochSequence,
                List.of(entry)
        );
    }

    /**
     * Applies a single {@link LcaWireStreamProtocol.InterServerChunkDeltaEntry} on a background
     * worker thread, enforcing {@link EpochCommitFence} (`UNLOADED_BIT`) and `SECDED-64` syndrome
     * verification before folding modified sections into {@link LcaSectionBacking} and queuing
     * lightweight coordinate commits for the owning server/region thread.
     */
    public LcaWireStreamProtocol.InterServerApplyStatus applyInterServerChunkDeltaEntry(
            String worldName,
            LcaWireStreamProtocol.InterServerChunkDeltaEntry deltaEntry,
            int globalPaletteBits
    ) {
        Objects.requireNonNull(deltaEntry, "deltaEntry");
        String normWorld = normalizeWorldKey(worldName);
        int chunkX = deltaEntry.chunkX();
        int chunkZ = deltaEntry.chunkZ();
        EpochCommitFence fence = getOrCreateRegionFence(normWorld, chunkX >> 5, chunkZ >> 5);
        int localIdx10 = LcaWireStreamProtocol.toLocalChunkIndex10(chunkX, chunkZ);
        if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
            return LcaWireStreamProtocol.InterServerApplyStatus.SKIPPED_UNLOADED_FENCE;
        }

        BatchChunkEntry chunkEntry = deltaEntry.chunkEntry();
        if (chunkEntry instanceof EntryFullChunk fullChunk) {
            EntryFullChunk verified = EntryFullChunk.createWithComputedSyndrome(
                    fullChunk.chunkX(),
                    fullChunk.chunkZ(),
                    fullChunk.lastUpdateTick(),
                    fullChunk.sections(),
                    fullChunk.heightmapDeltaBytes()
            );
            if (verified.secDedSyndrome64() != fullChunk.secDedSyndrome64()) {
                return LcaWireStreamProtocol.InterServerApplyStatus.SYNDROME_MISMATCH;
            }
            List<SectionWireSlice> slices = fullChunk.sections();
            LcaSectionBacking[] backings = new LcaSectionBacking[slices.size()];
            for (int i = 0; i < slices.size(); i++) {
                SectionWireSlice slice = slices.get(i);
                backings[i] = LcaWireStreamProtocol.hydrateDefaultSectionBacking(slice);
                if (slice.dirtyOverlayEntries().length > 0) {
                    backings[i].compactFrozenOverlayOffThread(globalPaletteBits);
                }
            }
            if (fence.isUnloadedTombstoneCommitted(localIdx10)) {
                return LcaWireStreamProtocol.InterServerApplyStatus.SKIPPED_UNLOADED_FENCE;
            }
            TrackedChunkColumn col = attachChunkColumn(
                    normWorld,
                    chunkX,
                    chunkZ,
                    backings,
                    null,
                    fullChunk.lastUpdateTick(),
                    false
            );
            col.updateBaselineAfterInterServerSync(fullChunk.lastUpdateTick(), fullChunk.secDedSyndrome64());
            return LcaWireStreamProtocol.InterServerApplyStatus.APPLIED;
        }

        if (chunkEntry instanceof EntryPatchDelta patchDelta) {
            TrackedChunkColumn col = getTrackedChunk(normWorld, chunkX, chunkZ);
            if (col == null) {
                return LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL;
            }
            if (col.baselineSecDed64() != deltaEntry.previousBaselineSecDed64()) {
                return LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL;
            }
            for (SectionWireSlice modSlice : patchDelta.modifiedSections()) {
                int secIdx = modSlice.sectionIndex();
                if (secIdx < 0 || secIdx >= col.sectionCount()) {
                    return LcaWireStreamProtocol.InterServerApplyStatus.NEEDS_FULL_CHUNK_REFILL;
                }
                LcaSectionBacking sec = col.section(secIdx);
                if (sec == null || sec.isUnloaded()) {
                    return LcaWireStreamProtocol.InterServerApplyStatus.SKIPPED_UNLOADED_FENCE;
                }
                int[] overlayEntries = modSlice.dirtyOverlayEntries();
                if (overlayEntries.length > 0) {
                    sec.applyPackedOverlayEntries(overlayEntries, patchDelta.newLastUpdateTick());
                    pendingInterServerCommits.offer(new InterServerSectionBlockCommit(
                            normWorld,
                            chunkX,
                            chunkZ,
                            secIdx,
                            overlayEntries.clone(),
                            patchDelta.newLastUpdateTick()
                    ));
                }
            }
            EntryFullChunk postPatchFull = col.toWireFullChunkEntry(patchDelta.newLastUpdateTick());
            if (postPatchFull.secDedSyndrome64() != patchDelta.newSecDedSyndrome64()) {
                return LcaWireStreamProtocol.InterServerApplyStatus.SYNDROME_MISMATCH;
            }
            for (SectionWireSlice modSlice : patchDelta.modifiedSections()) {
                LcaSectionBacking sec = col.section(modSlice.sectionIndex());
                if (sec != null && sec.dirtyCount() > 0) {
                    sec.compactFrozenOverlayOffThread(globalPaletteBits);
                }
            }
            col.updateBaselineAfterInterServerSync(
                    patchDelta.newLastUpdateTick(),
                    patchDelta.newSecDedSyndrome64()
            );
            return LcaWireStreamProtocol.InterServerApplyStatus.APPLIED;
        }

        return LcaWireStreamProtocol.InterServerApplyStatus.APPLIED;
    }

    /**
     * Drains up to {@code maxCommits} pending inter-server section block commits on the owning
     * server tick thread (or Folia region thread).
     */
    public int drainPendingInterServerBlockCommits(
            int maxCommits,
            Consumer<InterServerSectionBlockCommit> onMainThreadCommit
    ) {
        Objects.requireNonNull(onMainThreadCommit, "onMainThreadCommit");
        int drained = 0;
        while (drained < maxCommits) {
            InterServerSectionBlockCommit commit = pendingInterServerCommits.poll();
            if (commit == null) {
                break;
            }
            onMainThreadCommit.accept(commit);
            drained++;
        }
        return drained;
    }

    /**
     * Marks the current thread as writing an outbound `ClientboundLcaChunkBatchPayload` so
     * `MixinCompressionEncoder` bypasses `Zlib` `Deflater` (`VarInt = 0` or stored-block Zlib).
     */
    public static void setOutboundLcaBatchBypassActive(boolean active) {
        if (active) {
            OUTBOUND_LCA_BATCH_BYPASS_ACTIVE.set(Boolean.TRUE);
        } else {
            OUTBOUND_LCA_BATCH_BYPASS_ACTIVE.remove();
        }
    }

    public static boolean isOutboundLcaBatchBypassActive() {
        return Boolean.TRUE.equals(OUTBOUND_LCA_BATCH_BYPASS_ACTIVE.get());
    }

    private static String normalizeWorldKey(String worldName) {
        if (worldName == null) {
            return "minecraft:overworld";
        }
        return worldName.trim().toLowerCase(Locale.ROOT);
    }
}
