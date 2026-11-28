package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaSectionBacking;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime duck-typing bridge implemented by `LevelChunkSection` (`MixinLevelChunkSection`) and
 * `PalettedContainer` (`MixinPalettedContainer`) to bind in-memory {@link LcaSectionBacking}
 * (`leafanvil-ADR-002`) and negotiated wire streaming (`leafanvil-ADR-003`) without exposing
 * platform NMS types in `:anvil-api` or `:leafanvil-common`.
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public interface LcaSectionBridge {

    ThreadLocal<Boolean> SUPPRESS_COPY_HYDRATION_FOR_DIRECT_SAVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    static void setSuppressCopyHydrationForDirectSave(boolean suppress) {
        if (suppress) {
            SUPPRESS_COPY_HYDRATION_FOR_DIRECT_SAVE.set(Boolean.TRUE);
        } else {
            SUPPRESS_COPY_HYDRATION_FOR_DIRECT_SAVE.remove();
        }
    }

    static boolean isSuppressCopyHydrationForDirectSave() {
        return Boolean.TRUE.equals(SUPPRESS_COPY_HYDRATION_FOR_DIRECT_SAVE.get());
    }

    /**
     * Returns the attached {@link LcaSectionBacking} for this section, or {@code null} if the
     * section has not been bound yet.
     */
    LcaSectionBacking leafanvil$getBacking();

    /**
     * Attaches or replaces the {@link LcaSectionBacking} for this section and binds the section's
     * inner `PalettedContainer` shell if present.
     */
    void leafanvil$setBacking(LcaSectionBacking backing);

    /**
     * Attaches or replaces the {@link LcaSectionBacking} for this section in either:
     * <ul>
     *   <li><b>Shadow-Tracked Native-Read Mode ({@code evictToZeroBitShell == false}):</b> keeps
     *       vanilla {@code SimpleBitStorage} intact for zero-overhead main-thread reads inside
     *       {@code simulation-distance} while mirroring writes into {@code dirtyOverlay}; or</li>
     *   <li><b>Cold Evicted Mode ({@code evictToZeroBitShell == true}):</b> swaps the inner
     *       {@code PalettedContainer} storage to a shared {@code 0-bit} {@code ZeroBitStorage}
     *       shell to reclaim {@code long[]} heap.</li>
     * </ul>
     */
    default void leafanvil$setBacking(LcaSectionBacking backing, boolean evictToZeroBitShell) {
        leafanvil$setBacking(backing);
    }

    /**
     * Returns {@code true} if this section's inner {@code PalettedContainer} has had its
     * {@code SimpleBitStorage} evicted to a {@code 0-bit} {@code ZeroBitStorage} shell.
     */
    default boolean leafanvil$isZeroBitShellEvicted() {
        return false;
    }

    /**
     * Promotes a shadow-tracked section into cold evicted {@code ZeroBitStorage} mode and returns
     * the number of {@code long[]} bytes freed.
     */
    default int leafanvil$evictToZeroBitShell() {
        return 0;
    }

    /**
     * Installs precomputed {@code O(paletteSize)} section block/fluid counts directly onto a
     * {@code LevelChunkSection} during zero-copy off-heap activation (`anvil-api-ADR-007`),
     * avoiding a redundant 4,096-voxel {@code recalcBlockCounts()} scan.
     */
    default void leafanvil$setColdBackedCounts(
            short nonEmptyBlockCount,
            short fluidCount,
            short tickingBlockCount,
            short tickingFluidCount
    ) {
    }

    /**
     * Marks whether the next {@code recalcBlockCounts()} call on this section may be skipped
     * because exact counts were pre-installed from {@code paletteVoxelCounts}.
     */
    default void leafanvil$setSkipRecalcBlockCounts(boolean skip) {
    }

    /**
     * Masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry and resolves the 19-bit
     * block state ID from the 3-layer {@link LcaSectionBacking}.
     */
    default int leafanvil$getStateId(int x, int y, int z) {
        LcaSectionBacking backing = leafanvil$getBacking();
        if (backing == null) {
            return -1;
        }
        return backing.getStateId(x & 15, y & 15, z & 15);
    }

    /**
     * Masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry, writes the new state ID
     * into `dirtyOverlay` and `dirtyOctantMask64`, and publishes `nonEmptyBlockCount` with release
     * semantics after the overlay write completes.
     */
    default int leafanvil$setAndGetPreviousStateId(int x, int y, int z, int stateId19, long currentTick) {
        LcaSectionBacking backing = leafanvil$getBacking();
        if (backing == null) {
            return -1;
        }
        return backing.setAndGetPreviousStateId(x & 15, y & 15, z & 15, stateId19, currentTick, null);
    }

    /**
     * Materializes an ephemeral 4,096-entry state ID snapshot for client meshing callers
     * (`Sodium` `WorldSlice` / `ChunkRenderRebuildTask` or vanilla `SectionCompiler`) without
     * permanently pinning an inflated `SimpleBitStorage` `long[]` onto the owning section.
     */
    default int[] leafanvil$materializeEphemeralSnapshot(int[] destinationOrNull) {
        LcaSectionBacking backing = leafanvil$getBacking();
        if (backing == null) {
            return new int[0];
        }
        return backing.materializeEphemeralStateIds(destinationOrNull, null);
    }

    /**
     * Bridge interface implemented by `PalettedContainer` (`MixinPalettedContainer`) so direct
     * container mutations (`ClientboundSectionBlocksUpdatePacket`) and `PalettedContainer#copy()`
     * snapshots route through the owning section's {@link LcaSectionBacking}.
     */
    interface PalettedContainerShellBridge {
        LcaSectionBacking leafanvil$getOwnerBacking();

        void leafanvil$setOwnerBacking(LcaSectionBacking backing);

        boolean leafanvil$isEphemeralSnapshot();

        void leafanvil$setEphemeralSnapshot(boolean ephemeral);

        default boolean leafanvil$isZeroBitShellEvicted() {
            return false;
        }

        default int leafanvil$evictVanillaBitStorageToZeroBitShell() {
            LcaSectionBacking backing = leafanvil$getOwnerBacking();
            int stateId = backing != null ? Math.max(0, backing.uniformStateId()) : 0;
            return LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(this, stateId);
        }

        /**
         * Attaches an {@link LcaSectionBacking} to a freshly constructed 0-bit
         * {@code PalettedContainer} shell without reflective {@code ZeroBitStorage} eviction
         * (`anvil-api-ADR-007`).
         */
        default void leafanvil$bindPreEvictedZeroBitShell(LcaSectionBacking backing) {
            leafanvil$setOwnerBacking(backing);
            if (backing != null) {
                leafanvil$evictVanillaBitStorageToZeroBitShell();
            }
        }
    }

    /**
     * Inspects the live `BitStorage#getRaw()` `long[]` byte footprint (`raw.length * 8`) inside a
     * Mojang `LevelChunkSection` or `PalettedContainer<BlockState>`, returning {@code 0} when the
     * container is backed by a `0-bit` `ZeroBitStorage` (`long[0]`) shell.
     */
    static int inspectPalettedContainerRawLongArrayBytes(Object sectionOrContainer) {
        return ZeroBitShellEvictor.inspectRawLongArrayBytes(sectionOrContainer);
    }

    /**
     * Replaces the `PalettedContainer.data` (`SimpleBitStorage` `long[256..512]` + `Palette`) on a
     * Mojang `LevelChunkSection` or `PalettedContainer<BlockState>` with a shared `0-bit`
     * `ZeroBitStorage` (`long[0]`) shell so the JVM garbage collector reclaims vanilla's backing
     * arrays (`leafanvil-ADR-002` Tier 3), returning the number of `long[]` bytes freed.
     */
    static int evictPalettedContainerBitStorageToZeroBitShell(Object sectionOrContainer) {
        int stateId = 0;
        if (sectionOrContainer instanceof LcaSectionBridge bridge && bridge.leafanvil$getBacking() != null) {
            stateId = Math.max(0, bridge.leafanvil$getBacking().uniformStateId());
        } else if (sectionOrContainer instanceof PalettedContainerShellBridge shell
                && shell.leafanvil$getOwnerBacking() != null) {
            stateId = Math.max(0, shell.leafanvil$getOwnerBacking().uniformStateId());
        }
        return ZeroBitShellEvictor.evictToZeroBitShell(sectionOrContainer, stateId);
    }

    /**
     * Replaces `PalettedContainer.data` with a shared `0-bit` `ZeroBitStorage` (`long[0]`) shell
     * whose `SingleValuePalette` is initialized to {@code Block.stateById(initialStateId)}.
    /**
     * Direct `BitStorage` `long[]` and `Palette#valueFor(int)` handle for fast $O(|P|)$ section
     * extraction without 4,096 per-voxel `getBlockState` lookups.
     */
    record RawPaletteStorage(int bits, long[] raw, Object palette, Method valueForMethod) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RawPaletteStorage other)) {
                return false;
            }
            return this.bits == other.bits
                    && java.util.Arrays.equals(this.raw, other.raw)
                    && java.util.Objects.equals(this.palette, other.palette)
                    && java.util.Objects.equals(this.valueForMethod, other.valueForMethod);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.bits;
            result = 31 * result + java.util.Arrays.hashCode(this.raw);
            result = 31 * result + java.util.Objects.hashCode(this.palette);
            result = 31 * result + java.util.Objects.hashCode(this.valueForMethod);
            return result;
        }

        @Override
        public String toString() {
            return "RawPaletteStorage[bits=" + this.bits
                    + ", raw=" + java.util.Arrays.toString(this.raw)
                    + ", palette=" + this.palette
                    + ", valueForMethod=" + this.valueForMethod
                    + "]";
        }
    }

    /**
     * Immutable $O(1)$ section snapshot (`long[].clone()` + `int[] paletteStateIds`) captured on
     * the owning server/region thread and handed off to the background compaction worker pool so
     * all $O(4,096)$ voxel unpacking and `CompactBaseIndex` construction happen off-thread.
     */
    record RawSectionSnapshot(
            int sectionIndex,
            boolean uniform,
            int uniformStateId,
            int bitsPerEntry,
            long[] rawWords,
            int[] paletteStateIds,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount,
            int skyConst4
    ) {
        public RawSectionSnapshot(
                int sectionIndex,
                boolean uniform,
                int uniformStateId,
                int bitsPerEntry,
                long[] rawWords,
                int[] paletteStateIds,
                int initialNonEmptyCount,
                int initialTickingBlockCount,
                int initialTickingFluidCount
        ) {
            this(
                    sectionIndex,
                    uniform,
                    uniformStateId,
                    bitsPerEntry,
                    rawWords,
                    paletteStateIds,
                    initialNonEmptyCount,
                    initialTickingBlockCount,
                    initialTickingFluidCount,
                    -1
            );
        }

        public boolean isUniform() {
            return uniform;
        }

        public long[] rawWordsClone() {
            return rawWords;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RawSectionSnapshot other)) {
                return false;
            }
            return this.sectionIndex == other.sectionIndex
                    && this.uniform == other.uniform
                    && this.uniformStateId == other.uniformStateId
                    && this.bitsPerEntry == other.bitsPerEntry
                    && java.util.Arrays.equals(this.rawWords, other.rawWords)
                    && java.util.Arrays.equals(this.paletteStateIds, other.paletteStateIds)
                    && this.initialNonEmptyCount == other.initialNonEmptyCount
                    && this.initialTickingBlockCount == other.initialTickingBlockCount
                    && this.initialTickingFluidCount == other.initialTickingFluidCount
                    && this.skyConst4 == other.skyConst4;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.sectionIndex;
            result = 31 * result + Boolean.hashCode(this.uniform);
            result = 31 * result + this.uniformStateId;
            result = 31 * result + this.bitsPerEntry;
            result = 31 * result + java.util.Arrays.hashCode(this.rawWords);
            result = 31 * result + java.util.Arrays.hashCode(this.paletteStateIds);
            result = 31 * result + this.initialNonEmptyCount;
            result = 31 * result + this.initialTickingBlockCount;
            result = 31 * result + this.initialTickingFluidCount;
            result = 31 * result + this.skyConst4;
            return result;
        }

        @Override
        public String toString() {
            return "RawSectionSnapshot[sectionIndex=" + this.sectionIndex
                    + ", uniform=" + this.uniform
                    + ", uniformStateId=" + this.uniformStateId
                    + ", bitsPerEntry=" + this.bitsPerEntry
                    + ", rawWords=" + java.util.Arrays.toString(this.rawWords)
                    + ", paletteStateIds=" + java.util.Arrays.toString(this.paletteStateIds)
                    + ", initialNonEmptyCount=" + this.initialNonEmptyCount
                    + ", initialTickingBlockCount=" + this.initialTickingBlockCount
                    + ", initialTickingFluidCount=" + this.initialTickingFluidCount
                    + ", skyConst4=" + this.skyConst4
                    + "]";
        }
    }

    static RawPaletteStorage extractRawPaletteAndStorage(Object sectionOrContainer) {
        return ZeroBitShellEvictor.extractRawPaletteAndStorage(sectionOrContainer);
    }

    /**
     * Captures an $O(1)$ immutable {@link RawSectionSnapshot} (`long[].clone()` + `int[] paletteStateIds`)
     * on the owning server thread without scanning 4,096 voxels.
     */
    static RawSectionSnapshot captureRawSectionSnapshot(
            int sectionIndex,
            Object sectionOrContainer,
            java.util.function.ToIntFunction<Object> stateToId,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount
    ) {
        return captureRawSectionSnapshot(
                sectionIndex,
                sectionOrContainer,
                stateToId,
                initialNonEmptyCount,
                initialTickingBlockCount,
                initialTickingFluidCount,
                -1
        );
    }

    static RawSectionSnapshot captureRawSectionSnapshot(
            int sectionIndex,
            Object sectionOrContainer,
            java.util.function.ToIntFunction<Object> stateToId,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount,
            int skyConst4
    ) {
        return ZeroBitShellEvictor.captureRawSectionSnapshot(
                sectionIndex,
                sectionOrContainer,
                stateToId,
                initialNonEmptyCount,
                initialTickingBlockCount,
                initialTickingFluidCount,
                skyConst4
        );
    }

    static RawSectionSnapshot captureRawSectionSnapshot(
            Object sectionOrContainer,
            java.util.function.ToIntFunction<Object> stateToId
    ) {
        return captureRawSectionSnapshot(0, sectionOrContainer, stateToId, -1, -1, -1, -1);
    }

    /**
     * Resolves a uniform 4-bit sky-light nibble (`0..15`) across a 2,048-byte packed `DataLayer`
     * array, returning `0` whenever the array is null, empty, or contains mixed sky-light levels
     * (so dark enclosed cave voxels are never collapsed to constant `15`).
     */
    static int dominantSkyNibbleFromLayer(byte[] skyNibbleBytes2048) {
        if (skyNibbleBytes2048 == null || skyNibbleBytes2048.length < 2048) {
            return 0;
        }
        int firstByte = skyNibbleBytes2048[0] & 0xFF;
        int firstLo = firstByte & 0x0F;
        if (((firstByte >>> 4) & 0x0F) != firstLo) {
            return 0;
        }
        for (int i = 1; i < 2048; i++) {
            if ((skyNibbleBytes2048[i] & 0xFF) != firstByte) {
                return 0;
            }
        }
        return firstLo;
    }

    /**
     * Resolves `skyConst4` (`0` or `15`) from dimension `hasSkyLight()` and primed `MOTION_BLOCKING`
     * column height bounds when no materialized `SkyLight` nibble array is present. Only sections
     * strictly at or above `maxMotionBlockingY` with all 256 columns open to the sky encode `15`.
     */
    static int resolveSkyConstFromHeightmapBounds(
            boolean hasSkyLight,
            int sectionBottomY,
            int minMotionBlockingY,
            int maxMotionBlockingY,
            int exposedColumns256
    ) {
        if (!hasSkyLight) {
            return 0;
        }
        if (sectionBottomY >= maxMotionBlockingY && exposedColumns256 >= 256) {
            return 15;
        }
        return 0;
    }

    /**
     * Fallback `skyConst4` estimator for standalone section encoding without a live chunk handle:
     * returns `0` when fully solid or sealed by an unbroken `y == 15` roof layer.
     */
    static int estimateSkyConstFromVoxelsFallback(
            int[] voxels4096,
            int nonEmptyCount,
            int sectionIndex,
            int totalSections,
            java.util.function.IntPredicate nonEmptyPred
    ) {
        if (nonEmptyCount >= 4096) {
            return 0;
        }
        if (voxels4096 != null && voxels4096.length >= 4096) {
            boolean solidRoof = true;
            for (int pos12 = 15 << 8; pos12 < 4096; pos12++) {
                int stateId = voxels4096[pos12];
                boolean nonEmpty = nonEmptyPred != null ? nonEmptyPred.test(stateId) : (stateId != 0);
                if (!nonEmpty) {
                    solidRoof = false;
                    break;
                }
            }
            if (solidRoof) {
                return 0;
            }
        }
        return (sectionIndex >= (totalSections / 2)) ? 15 : 0;
    }

    /**
     * Resolves `skyConst4` (`0..15`) for a {@link RawSectionSnapshot}, preferring captured chunk
     * sky-light/heightmap metadata (`skyConst4 >= 0`) and otherwise checking roof closure at `y == 15`.
     */
    static int resolveSnapshotSkyConst4(
            RawSectionSnapshot snap,
            int sectionIndex,
            int totalSections,
            java.util.function.IntPredicate nonEmptyPred
    ) {
        if (snap == null) {
            return 0;
        }
        if (snap.skyConst4() >= 0) {
            return snap.skyConst4() & 0xF;
        }
        if (snap.uniform()) {
            int uid = (snap.paletteStateIds() != null && snap.paletteStateIds().length > 0)
                    ? snap.paletteStateIds()[0]
                    : snap.uniformStateId();
            boolean nonEmpty = nonEmptyPred != null ? nonEmptyPred.test(uid) : (uid != 0);
            if (nonEmpty) {
                return 0;
            }
            return (sectionIndex >= (totalSections / 2)) ? 15 : 0;
        }
        if (snap.initialNonEmptyCount() >= 4096) {
            return 0;
        }
        long[] words = snap.rawWords();
        int bits = snap.bitsPerEntry();
        int[] pal = snap.paletteStateIds();
        if (words != null && words.length > 0 && bits > 0 && bits <= 64) {
            int valuesPerLong = 64 / bits;
            if (valuesPerLong > 0) {
                long mask = bits == 64 ? -1L : ((1L << bits) - 1L);
                boolean solidRoof = true;
                for (int pos12 = 15 << 8; pos12 < 4096; pos12++) {
                    int wordIdx = pos12 / valuesPerLong;
                    if (wordIdx >= words.length) {
                        solidRoof = false;
                        break;
                    }
                    int shift = (pos12 % valuesPerLong) * bits;
                    int rawEntry = (int) ((words[wordIdx] >>> shift) & mask);
                    int stateId = (pal != null && pal.length > 0)
                            ? ((rawEntry >= 0 && rawEntry < pal.length) ? pal[rawEntry] : 0)
                            : rawEntry;
                    boolean nonEmpty = nonEmptyPred != null ? nonEmptyPred.test(stateId) : (stateId != 0);
                    if (!nonEmpty) {
                        solidRoof = false;
                        break;
                    }
                }
                if (solidRoof) {
                    return 0;
                }
            }
        }
        return (sectionIndex >= (totalSections / 2)) ? 15 : 0;
    }

    /**
     * Verifies in $O(1)$ (SIMD vectorized `Arrays.equals` over 256–512 `long` words) that the live
     * section's `BitStorage` was not mutated while background indexing was in flight.
     */
    static boolean isRawSectionSnapshotUnmodified(Object sectionOrContainer, RawSectionSnapshot snapshot) {
        return ZeroBitShellEvictor.isRawSectionSnapshotUnmodified(sectionOrContainer, snapshot);
    }

    /**
     * Replaces `PalettedContainer.data` with a shared `0-bit` `ZeroBitStorage` (`long[0]`) shell
     * whose `SingleValuePalette` is initialized to {@code Block.stateById(initialStateId)}.
     */
    static int evictPalettedContainerBitStorageToZeroBitShell(Object sectionOrContainer, int initialStateId) {
        return ZeroBitShellEvictor.evictToZeroBitShell(sectionOrContainer, Math.max(0, initialStateId));
    }

    /**
     * Live NMS `LevelChunkSection` block/ticking counts + host `BlockState` predicate bundle
     * resolved once via reflection so cold `.lca` mounts preserve exact `!BlockState.isAir()`
     * (`CAVE_AIR` / `VOID_AIR`) and random-ticking invariants in $O(1)$.
     */
    record HostSectionEnvironment(
            java.util.function.IntPredicate nonEmptyPredicate,
            java.util.function.IntPredicate tickingBlockPredicate,
            java.util.function.IntPredicate tickingFluidPredicate
    ) {
    }

    static int[] inspectNmsSectionCountsOrNull(Object nmsSection) {
        return ZeroBitShellEvictor.inspectNmsSectionCountsOrNull(nmsSection);
    }

    static HostSectionEnvironment resolveHostSectionEnvironmentOrNull(Object sampleNmsSection) {
        return ZeroBitShellEvictor.resolveHostSectionEnvironmentOrNull(sampleNmsSection);
    }

    /**
     * Reflection-cached helper that inspects and evicts `PalettedContainer.data` to a `0-bit`
     * `ZeroBitStorage` shell across Fabric and NeoForge without compile-time NMS imports in
     * `:leafanvil-common`.
     */
    final class ZeroBitShellEvictor {
        private static final Logger LOGGER = Logger.getLogger(LcaSectionBridge.class.getName());
        private static volatile Field cachedSectionStatesField;
        private static volatile Method cachedSectionGetStatesMethod;
        private static volatile Field cachedContainerDataField;
        private static volatile Field cachedDataStorageField;
        private static volatile Field cachedDataPaletteField;
        private static volatile Method cachedStorageGetRawMethod;
        private static volatile Method cachedStorageGetBitsMethod;
        private static volatile Method cachedPaletteValueForMethod;
        private static volatile Method cachedPaletteGetSizeMethod;
        private static volatile Field cachedNonEmptyCountField;
        private static volatile Field cachedTickingBlockCountField;
        private static volatile Field cachedTickingFluidCountField;
        private static volatile HostSectionEnvironment cachedHostEnvironment;
        private static final ConcurrentHashMap<Integer, Object> ZERO_BIT_DATA_BY_STATE_ID =
                new ConcurrentHashMap<>();

        private ZeroBitShellEvictor() {
        }

        static int[] inspectNmsSectionCountsOrNull(Object nmsSection) {
            if (nmsSection == null) {
                return null;
            }
            try {
                Class<?> secClass = nmsSection.getClass();
                Field neField = cachedNonEmptyCountField;
                Field tbField = cachedTickingBlockCountField;
                Field tfField = cachedTickingFluidCountField;
                if (neField == null || !neField.getDeclaringClass().isAssignableFrom(secClass)) {
                    Class<?> walk = secClass;
                    while (walk != null && walk != Object.class) {
                        for (Field f : walk.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType() != short.class) {
                                continue;
                            }
                            String name = f.getName();
                            if ("nonEmptyBlockCount".equals(name)) {
                                f.setAccessible(true);
                                neField = f;
                            } else if ("tickingBlockCount".equals(name)) {
                                f.setAccessible(true);
                                tbField = f;
                            } else if ("tickingFluidCount".equals(name)) {
                                f.setAccessible(true);
                                tfField = f;
                            }
                        }
                        if (neField != null) {
                            break;
                        }
                        walk = walk.getSuperclass();
                    }
                    if (neField != null) {
                        cachedNonEmptyCountField = neField;
                        cachedTickingBlockCountField = tbField;
                        cachedTickingFluidCountField = tfField;
                    }
                }
                if (neField == null) {
                    return null;
                }
                int ne = neField.getShort(nmsSection) & 0xFFFF;
                int tb = tbField != null ? (tbField.getShort(nmsSection) & 0xFFFF) : 0;
                int tf = tfField != null ? (tfField.getShort(nmsSection) & 0xFFFF) : 0;
                return new int[]{ne, tb, tf};
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Could not inspect LevelChunkSection counts", t);
                return null;
            }
        }

        static HostSectionEnvironment resolveHostSectionEnvironmentOrNull(Object sampleNmsSection) {
            HostSectionEnvironment cached = cachedHostEnvironment;
            if (cached != null) {
                return cached;
            }
            ClassLoader[] candidates = new ClassLoader[]{
                    sampleNmsSection != null ? sampleNmsSection.getClass().getClassLoader() : null,
                    Thread.currentThread().getContextClassLoader(),
                    LcaSectionBridge.class.getClassLoader()
            };
            Class<?> blockClass = null;
            ClassLoader cl = null;
            for (ClassLoader cand : candidates) {
                if (cand == null) {
                    continue;
                }
                try {
                    blockClass = Class.forName("net.minecraft.world.level.block.Block", false, cand);
                    cl = cand;
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (blockClass == null) {
                return null;
            }
            try {
                Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState", false, cl);
                Class<?> fluidStateClass = Class.forName("net.minecraft.world.level.material.FluidState", false, cl);
                java.lang.invoke.MethodHandles.Lookup pub = java.lang.invoke.MethodHandles.publicLookup();
                java.lang.invoke.MethodHandle stateByIdMh = pub.findStatic(
                        blockClass, "stateById", java.lang.invoke.MethodType.methodType(stateClass, int.class));
                java.lang.invoke.MethodHandle isAirMh = pub.findVirtual(
                        stateClass, "isAir", java.lang.invoke.MethodType.methodType(boolean.class));
                java.lang.invoke.MethodHandle isRandTickBlockMh = pub.findVirtual(
                        stateClass, "isRandomlyTicking", java.lang.invoke.MethodType.methodType(boolean.class));
                java.lang.invoke.MethodHandle getFluidStateMh = pub.findVirtual(
                        stateClass, "getFluidState", java.lang.invoke.MethodType.methodType(fluidStateClass));
                java.lang.invoke.MethodHandle isRandTickFluidMh = pub.findVirtual(
                        fluidStateClass, "isRandomlyTicking", java.lang.invoke.MethodType.methodType(boolean.class));

                Object airProbe = stateByIdMh.invoke(0);
                if (airProbe == null || !(boolean) isAirMh.invoke(airProbe)) {
                    return null;
                }

                java.util.function.IntPredicate nonEmptyPred = id -> {
                    if (id <= 0) {
                        return false;
                    }
                    try {
                        Object st = stateByIdMh.invoke(id);
                        return st != null && !(boolean) isAirMh.invoke(st);
                    } catch (Throwable ex) {
                        return true;
                    }
                };
                java.util.function.IntPredicate tickBlockPred = id -> {
                    if (id <= 0) {
                        return false;
                    }
                    try {
                        Object st = stateByIdMh.invoke(id);
                        return st != null && (boolean) isRandTickBlockMh.invoke(st);
                    } catch (Throwable ex) {
                        return false;
                    }
                };
                java.util.function.IntPredicate tickFluidPred = id -> {
                    if (id <= 0) {
                        return false;
                    }
                    try {
                        Object st = stateByIdMh.invoke(id);
                        if (st == null) {
                            return false;
                        }
                        Object fl = getFluidStateMh.invoke(st);
                        return fl != null && (boolean) isRandTickFluidMh.invoke(fl);
                    } catch (Throwable ex) {
                        return false;
                    }
                };
                HostSectionEnvironment env = new HostSectionEnvironment(
                        nonEmptyPred, tickBlockPred, tickFluidPred);
                cachedHostEnvironment = env;
                return env;
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Host BlockState predicates unavailable in environment", t);
                return null;
            }
        }

        static RawPaletteStorage extractRawPaletteAndStorage(Object sectionOrContainer) {
            try {
                Object container = resolveStatesPalettedContainer(sectionOrContainer);
                if (container == null) {
                    return null;
                }
                Field dataField = resolveContainerDataField(container.getClass());
                if (dataField == null) {
                    return null;
                }
                Object dataObj = dataField.get(container);
                if (dataObj == null) {
                    return null;
                }
                Field storageField = resolveDataStorageField(dataObj.getClass());
                Field paletteField = resolveDataPaletteField(dataObj.getClass());
                if (storageField == null || paletteField == null) {
                    return null;
                }
                Object storageObj = storageField.get(dataObj);
                Object paletteObj = paletteField.get(dataObj);
                if (storageObj == null || paletteObj == null) {
                    return null;
                }
                Method getRaw = resolveStorageGetRawMethod(storageObj.getClass());
                Method getBits = resolveStorageGetBitsMethod(storageObj.getClass());
                Method valueFor = resolvePaletteValueForMethod(paletteObj.getClass());
                if (getRaw == null || getBits == null || valueFor == null) {
                    return null;
                }
                int bits = (int) getBits.invoke(storageObj);
                long[] raw = (long[]) getRaw.invoke(storageObj);
                return new RawPaletteStorage(bits, raw, paletteObj, valueFor);
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Could not extract RawPaletteStorage", t);
                return null;
            }
        }

        static RawSectionSnapshot captureRawSectionSnapshot(
                int sectionIndex,
                Object sectionOrContainer,
                java.util.function.ToIntFunction<Object> stateToId,
                int initialNonEmptyCount,
                int initialTickingBlockCount,
                int initialTickingFluidCount,
                int skyConst4
        ) {
            RawPaletteStorage rawStorage = extractRawPaletteAndStorage(sectionOrContainer);
            if (rawStorage == null) {
                return null;
            }
            try {
                int bits = rawStorage.bits();
                long[] liveRaw = rawStorage.raw();
                Method valueFor = rawStorage.valueForMethod();
                Object palette = rawStorage.palette();
                if (bits <= 0 || liveRaw == null || liveRaw.length == 0) {
                    Object singleState = valueFor.invoke(palette, 0);
                    int uniformStateId = singleState != null ? Math.max(0, stateToId.applyAsInt(singleState)) : 0;
                    return new RawSectionSnapshot(
                            sectionIndex,
                            true,
                            uniformStateId,
                            0,
                            new long[0],
                            new int[]{uniformStateId},
                            initialNonEmptyCount,
                            initialTickingBlockCount,
                            initialTickingFluidCount,
                            skyConst4
                    );
                }
                long[] rawClone = liveRaw.clone();
                int[] paletteStateIds = null;
                if (bits <= 8) {
                    int maxEntries = 1 << bits;
                    int paletteSize = maxEntries;
                    Method getSize = resolvePaletteGetSizeMethod(palette.getClass());
                    if (getSize != null) {
                        try {
                            int reported = (int) getSize.invoke(palette);
                            if (reported >= 0 && reported <= maxEntries) {
                                paletteSize = reported;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    paletteStateIds = new int[paletteSize];
                    for (int p = 0; p < paletteSize; p++) {
                        try {
                            Object stateObj = valueFor.invoke(palette, p);
                            paletteStateIds[p] = stateObj != null ? Math.max(0, stateToId.applyAsInt(stateObj)) : 0;
                        } catch (Throwable ignored) {
                            break;
                        }
                    }
                }
                return new RawSectionSnapshot(
                        sectionIndex,
                        false,
                        0,
                        bits,
                        rawClone,
                        paletteStateIds,
                        initialNonEmptyCount,
                        initialTickingBlockCount,
                        initialTickingFluidCount,
                        skyConst4
                );
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Could not capture RawSectionSnapshot", t);
                return null;
            }
        }

        static boolean isRawSectionSnapshotUnmodified(Object sectionOrContainer, RawSectionSnapshot snapshot) {
            if (snapshot == null) {
                return false;
            }
            RawPaletteStorage current = extractRawPaletteAndStorage(sectionOrContainer);
            if (current == null) {
                return false;
            }
            if (current.bits() != snapshot.bitsPerEntry()) {
                return false;
            }
            long[] curRaw = current.raw();
            long[] snapRaw = snapshot.rawWords();
            if (snapshot.uniform()) {
                return curRaw == null || curRaw.length == 0;
            }
            return curRaw != null && java.util.Arrays.equals(curRaw, snapRaw);
        }

        static int inspectRawLongArrayBytes(Object sectionOrContainer) {
            try {
                Object container = resolveStatesPalettedContainer(sectionOrContainer);
                if (container == null) {
                    return 0;
                }
                Field dataField = resolveContainerDataField(container.getClass());
                if (dataField == null) {
                    return 0;
                }
                Object dataObj = dataField.get(container);
                if (dataObj == null) {
                    return 0;
                }
                Field storageField = resolveDataStorageField(dataObj.getClass());
                if (storageField == null) {
                    return 0;
                }
                Object storageObj = storageField.get(dataObj);
                if (storageObj == null) {
                    return 0;
                }
                Method getRaw = resolveStorageGetRawMethod(storageObj.getClass());
                if (getRaw == null) {
                    return 0;
                }
                Object rawObj = getRaw.invoke(storageObj);
                if (rawObj instanceof long[] longs) {
                    return longs.length * Long.BYTES;
                }
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Could not inspect PalettedContainer BitStorage raw bytes", t);
            }
            return 0;
        }

        static int evictToZeroBitShell(Object sectionOrContainer, int initialStateId) {
            try {
                Object container = resolveStatesPalettedContainer(sectionOrContainer);
                if (container == null) {
                    return 0;
                }
                int beforeBytes = inspectRawLongArrayBytes(container);
                Field dataField = resolveContainerDataField(container.getClass());
                if (dataField == null) {
                    return 0;
                }
                Object zeroBitData = getOrCreateZeroBitBlockStateData(
                        container.getClass(),
                        dataField,
                        Math.max(0, initialStateId)
                );
                if (zeroBitData == null) {
                    return 0;
                }
                dataField.set(container, zeroBitData);
                return Math.max(0, beforeBytes);
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "[LeafAnvil] Could not evict PalettedContainer to 0-bit shell", t);
                return 0;
            }
        }

        private static Object resolveStatesPalettedContainer(Object sectionOrContainer) throws Exception {
            if (sectionOrContainer == null) {
                return null;
            }
            Class<?> cls = sectionOrContainer.getClass();
            if (cls.getSimpleName().contains("PalettedContainer")) {
                return sectionOrContainer;
            }
            Method getStates = cachedSectionGetStatesMethod;
            if (getStates != null && getStates.getDeclaringClass().isAssignableFrom(cls)) {
                return getStates.invoke(sectionOrContainer);
            }
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0
                        && m.getName().equals("getStates")
                        && m.getReturnType().getSimpleName().contains("PalettedContainer")) {
                    m.setAccessible(true);
                    cachedSectionGetStatesMethod = m;
                    return m.invoke(sectionOrContainer);
                }
            }
            Field statesField = cachedSectionStatesField;
            if (statesField != null && statesField.getDeclaringClass().isAssignableFrom(cls)) {
                return statesField.get(sectionOrContainer);
            }
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        && (f.getName().equals("states")
                        || f.getType().getSimpleName().contains("PalettedContainer"))) {
                    f.setAccessible(true);
                    cachedSectionStatesField = f;
                    return f.get(sectionOrContainer);
                }
            }
            return null;
        }

        private static Field resolveContainerDataField(Class<?> containerClass) {
            Field cached = cachedContainerDataField;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(containerClass)) {
                return cached;
            }
            for (Field f : containerClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getName().equals("data") || f.getType().getSimpleName().contains("Data")) {
                    f.setAccessible(true);
                    cachedContainerDataField = f;
                    return f;
                }
            }
            for (Field f : containerClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (resolveDataStorageField(f.getType()) != null) {
                    f.setAccessible(true);
                    cachedContainerDataField = f;
                    return f;
                }
            }
            return null;
        }

        private static Field resolveDataStorageField(Class<?> dataClass) {
            Field cached = cachedDataStorageField;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(dataClass)) {
                return cached;
            }
            for (Field f : dataClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getName().equals("storage") || f.getType().getSimpleName().contains("BitStorage")) {
                    f.setAccessible(true);
                    cachedDataStorageField = f;
                    return f;
                }
            }
            return null;
        }

        private static Field resolveDataPaletteField(Class<?> dataClass) {
            Field cached = cachedDataPaletteField;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(dataClass)) {
                return cached;
            }
            for (Field f : dataClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getName().equals("palette") || f.getType().getSimpleName().contains("Palette")) {
                    f.setAccessible(true);
                    cachedDataPaletteField = f;
                    return f;
                }
            }
            return null;
        }

        private static Method resolveStorageGetRawMethod(Class<?> storageClass) {
            Method cached = cachedStorageGetRawMethod;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(storageClass)) {
                return cached;
            }
            for (Class<?> iface : storageClass.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == long[].class) {
                        m.setAccessible(true);
                        cachedStorageGetRawMethod = m;
                        return m;
                    }
                }
            }
            for (Method m : storageClass.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == long[].class) {
                    m.setAccessible(true);
                    cachedStorageGetRawMethod = m;
                    return m;
                }
            }
            return null;
        }

        private static Method resolveStorageGetBitsMethod(Class<?> storageClass) {
            Method cached = cachedStorageGetBitsMethod;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(storageClass)) {
                return cached;
            }
            for (Class<?> iface : storageClass.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() == 0 && m.getName().equals("getBits") && m.getReturnType() == int.class) {
                        m.setAccessible(true);
                        cachedStorageGetBitsMethod = m;
                        return m;
                    }
                }
            }
            for (Method m : storageClass.getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().equals("getBits") && m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    cachedStorageGetBitsMethod = m;
                    return m;
                }
            }
            return null;
        }

        private static Method resolvePaletteValueForMethod(Class<?> paletteClass) {
            Method cached = cachedPaletteValueForMethod;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(paletteClass)) {
                return cached;
            }
            for (Class<?> iface : paletteClass.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == int.class
                            && (m.getName().equals("valueFor") || m.getReturnType() == Object.class)) {
                        m.setAccessible(true);
                        cachedPaletteValueForMethod = m;
                        return m;
                    }
                }
            }
            for (Method m : paletteClass.getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class
                        && (m.getName().equals("valueFor") || m.getReturnType() == Object.class)) {
                    m.setAccessible(true);
                    cachedPaletteValueForMethod = m;
                    return m;
                }
            }
            return null;
        }

        private static Method resolvePaletteGetSizeMethod(Class<?> paletteClass) {
            Method cached = cachedPaletteGetSizeMethod;
            if (cached != null && cached.getDeclaringClass().isAssignableFrom(paletteClass)) {
                return cached;
            }
            for (Class<?> iface : paletteClass.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    if (m.getParameterCount() == 0
                            && m.getReturnType() == int.class
                            && m.getName().equals("getSize")) {
                        m.setAccessible(true);
                        cachedPaletteGetSizeMethod = m;
                        return m;
                    }
                }
            }
            for (Method m : paletteClass.getMethods()) {
                if (m.getParameterCount() == 0
                        && m.getReturnType() == int.class
                        && m.getName().equals("getSize")) {
                    m.setAccessible(true);
                    cachedPaletteGetSizeMethod = m;
                    return m;
                }
            }
            return null;
        }

        private static Object getOrCreateZeroBitBlockStateData(
                Class<?> containerClass,
                Field dataField,
                int initialStateId
        ) {
            int key = Math.max(0, initialStateId);
            Object cached = ZERO_BIT_DATA_BY_STATE_ID.get(key);
            if (cached != null && dataField.getType().isInstance(cached)) {
                return cached;
            }
            synchronized (ZeroBitShellEvictor.class) {
                cached = ZERO_BIT_DATA_BY_STATE_ID.get(key);
                if (cached != null && dataField.getType().isInstance(cached)) {
                    return cached;
                }
                try {
                    ClassLoader cl = containerClass.getClassLoader();
                    Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block", false, cl);
                    Object registry = blockClass.getField("BLOCK_STATE_REGISTRY").get(null);
                    Method stateById = blockClass.getMethod("stateById", int.class);
                    Object initialState = stateById.invoke(null, key);

                    for (Constructor<?> ctor : containerClass.getDeclaredConstructors()) {
                        ctor.setAccessible(true);
                        Class<?>[] params = ctor.getParameterTypes();
                        if (params.length == 2 && params[0] == Object.class) {
                            for (Method m : params[1].getDeclaredMethods()) {
                                if (Modifier.isStatic(m.getModifiers())
                                        && m.getName().equals("createForBlockStates")
                                        && m.getParameterCount() == 1) {
                                    m.setAccessible(true);
                                    Object strategyObj = m.invoke(null, registry);
                                    Object emptyContainer = ctor.newInstance(initialState, strategyObj);
                                    Object dataObj = dataField.get(emptyContainer);
                                    if (dataObj != null) {
                                        if (ZERO_BIT_DATA_BY_STATE_ID.size() < 4096) {
                                            ZERO_BIT_DATA_BY_STATE_ID.put(key, dataObj);
                                        }
                                        return dataObj;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "[LeafAnvil] Could not create 0-bit PalettedContainer.Data shell", t);
                }
                return null;
            }
        }
    }
}
