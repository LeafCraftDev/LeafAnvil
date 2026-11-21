package net.leaf.anvil.agent;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-JDK `BootstrapClassLoader` / `AppClassLoader` dispatch table and sparse
 * `(pos12 -> runtimeStateId19)` carrier state connecting bytecode-transformed NMS chunk classes
 * to `LeafAnvil` and `LeafGen` (`leafanvil-ADR-005`, `leafanvil-ADR-006`).
 *
 * <p>Imports exclusively `java.base` types so `-javaagent` bootstrap classloader registration
 * succeeds before Paperclip unpacks `org.bukkit.*` or `net.minecraft.*` under
 * `PlatformClassLoader`.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LeafAnvilAgentBootstrap {

    public static final String AGENT_ACTIVE_PROPERTY = "leafanvil.agent.active";
    private static final System.Logger LOGGER = System.getLogger(LeafAnvilAgentBootstrap.class.getName());
    private static final AtomicBoolean AGENT_ACTIVE = new AtomicBoolean(false);
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final int MAX_POS_12 = 4095;
    private static final int MAX_STATE_ID_19 = 0x7FFFF;
    private static final int FIBONACCI_HASH_MULTIPLIER = 0x9E3779B9;

    private static volatile MethodHandle directChunkSerializeHook;
    private static volatile MethodHandle directChunkHydrateHook;
    private static volatile MethodHandle sectionMutationListener;
    private static volatile MethodHandle blockStateToIdResolver;

    private static final Set<String> LOGGED_FALLBACK_CLASSES = ConcurrentHashMap.newKeySet();
    private static final AtomicLong FALLBACK_WARNING_COUNT = new AtomicLong();

    private LeafAnvilAgentBootstrap() {}

    public static void markAgentActive() {
        AGENT_ACTIVE.set(true);
        System.setProperty(AGENT_ACTIVE_PROPERTY, "true");
    }

    public static boolean isAgentActive() {
        return AGENT_ACTIVE.get() || Boolean.getBoolean(AGENT_ACTIVE_PROPERTY);
    }

    public static void resetAgentActiveForTest() {
        AGENT_ACTIVE.set(false);
        System.clearProperty(AGENT_ACTIVE_PROPERTY);
    }

    /**
     * Registers runtime `MethodHandle` hooks from `LeafAnvil` once the server plugin enables:
     * <ul>
     *   <li>{@code directChunkSerializeHook}: {@code (Object contextOrSelf, Object chunkOrNull) -> Object tagOrBytesOrNull}</li>
     *   <li>{@code directChunkHydrateHook}: {@code (Object levelHeightOrLevel, Object factoryOrPos, Object compoundTagOrFrame) -> Object serializableOrChunkOrNull}</li>
     *   <li>{@code sectionMutationListener}: {@code (Object section, int pos12, int prevStateId, int newStateId) -> void}</li>
     * </ul>
     */
    public static void registerChunkCodecHooks(
            MethodHandle serializeHook,
            MethodHandle hydrateHook,
            MethodHandle mutationListener
    ) {
        directChunkSerializeHook = serializeHook;
        directChunkHydrateHook = hydrateHook;
        sectionMutationListener = mutationListener;
    }

    /**
     * Registers an optional fast {@code (Object blockState) -> int runtimeStateId} resolver used by
     * transformed `LevelChunkSection#setBlockState` wrappers.
     */
    public static void registerBlockStateIdResolver(MethodHandle stateToIdHook) {
        blockStateToIdResolver = stateToIdHook;
    }

    public static void clearHooks() {
        directChunkSerializeHook = null;
        directChunkHydrateHook = null;
        sectionMutationListener = null;
        blockStateToIdResolver = null;
    }

    public static MethodHandle directChunkSerializeHook() {
        return directChunkSerializeHook;
    }

    public static MethodHandle directChunkHydrateHook() {
        return directChunkHydrateHook;
    }

    public static MethodHandle sectionMutationListener() {
        return sectionMutationListener;
    }

    /**
     * Logs a single structured `WARNING` per mismatched class (`REQ-RTP-S-004`) when an exotic NMS
     * fork alters target method descriptors, falling back cleanly to Tier 1 `NmsRegionStorageInterceptor`.
     */
    public static void recordDescriptorFallbackWarning(String internalClassName, String reason) {
        String key = internalClassName == null ? "<unknown>" : internalClassName;
        if (LOGGED_FALLBACK_CLASSES.add(key)) {
            FALLBACK_WARNING_COUNT.incrementAndGet();
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "[LeafAnvil Agent] Skipping direct bytecode bridge for {0} ({1}); falling back to NmsRegionStorageInterceptor + LcaStreamingNbtEncoder.",
                    key,
                    reason
            );
        }
    }

    public static long fallbackWarningCount() {
        return FALLBACK_WARNING_COUNT.get();
    }

    public static void resetFallbackWarningsForTest() {
        LOGGED_FALLBACK_CLASSES.clear();
        FALLBACK_WARNING_COUNT.set(0L);
    }

    public static int resolveBlockStateId(Object blockStateObj) {
        if (blockStateObj == null) {
            return 0;
        }
        if (blockStateObj instanceof Integer n) {
            return n.intValue() & MAX_STATE_ID_19;
        }
        MethodHandle mh = blockStateToIdResolver;
        if (mh != null) {
            try {
                return ((int) mh.invoke(blockStateObj)) & MAX_STATE_ID_19;
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] BlockState ID resolver fault: " + t.getMessage(), t);
            }
        }
        return blockStateObj.hashCode() & MAX_STATE_ID_19;
    }

    // =========================================================================
    // CarrierState &Bytecode Helper Entrypoints (Invoked by Transformed Classes)
    // =========================================================================

    public static byte[] getBaseFrame(Object stateObj) {
        if (stateObj instanceof CarrierState cs) {
            return cs.baseFrame;
        }
        return EMPTY_BYTES;
    }

    public static int getUniformStateId(Object stateObj) {
        return (stateObj instanceof CarrierState cs) ? cs.uniformStateId : 0;
    }

    public static int getDataVersion(Object stateObj) {
        return (stateObj instanceof CarrierState cs) ? cs.dataVersion : 0;
    }

    public static long getSecDed64(Object stateObj) {
        return (stateObj instanceof CarrierState cs) ? cs.secDed64 : 0L;
    }

    static int getUniformStateIdForSection(Object section) {
        if (section != null) {
            try {
                java.lang.reflect.Field f = section.getClass().getDeclaredField("leafanvil$carrierState");
                f.setAccessible(true);
                return getUniformStateId(f.get(section));
            } catch (ReflectiveOperationException e) {
                LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Failed reading uniformStateId from carrier section: " + e.getMessage(), e);
            }
        }
        return 0;
    }

    static int getDataVersionForSection(Object section) {
        if (section != null) {
            try {
                java.lang.reflect.Field f = section.getClass().getDeclaredField("leafanvil$carrierState");
                f.setAccessible(true);
                return getDataVersion(f.get(section));
            } catch (ReflectiveOperationException e) {
                LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Failed reading dataVersion from carrier section: " + e.getMessage(), e);
            }
        }
        return 0;
    }

    static long getSecDed64ForSection(Object section) {
        if (section != null) {
            try {
                java.lang.reflect.Field f = section.getClass().getDeclaredField("leafanvil$carrierState");
                f.setAccessible(true);
                return getSecDed64(f.get(section));
            } catch (ReflectiveOperationException e) {
                LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Failed reading secDed64 from carrier section: " + e.getMessage(), e);
            }
        }
        return 0L;
    }

    public static Object mountBaseFrame(
            Object section,
            Object stateObj,
            byte[] baseFrame,
            int uniformStateId,
            int dataVersion,
            long secDed64
    ) {
        CarrierState cs = (stateObj instanceof CarrierState existing) ? existing : new CarrierState();
        cs.mount(baseFrame, uniformStateId, dataVersion, secDed64);
        return cs;
    }

    public static Object recordCarrierMutation(
            Object section,
            Object stateObj,
            int pos12,
            int prevStateId19,
            int newStateId19,
            long currentTick
    ) {
        int maskedPos = pos12 & MAX_POS_12;
        int clampedPrev = prevStateId19 & MAX_STATE_ID_19;
        int clampedNew = newStateId19 & MAX_STATE_ID_19;
        CarrierState cs = (stateObj instanceof CarrierState existing) ? existing : new CarrierState();
        cs.putDirty(maskedPos, clampedNew, currentTick);
        notifyMutationListener(section, maskedPos, clampedPrev, clampedNew);
        return cs;
    }

    public static Object onSectionSetBlockState(
            Object section,
            Object stateObj,
            int x,
            int y,
            int z,
            Object prevStateObj,
            Object newStateObj
    ) {
        if (prevStateObj == newStateObj) {
            return stateObj;
        }
        int pos12 = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        int prevId = resolveBlockStateId(prevStateObj);
        int newId = resolveBlockStateId(newStateObj);
        if (prevId == newId && prevStateObj != null && prevStateObj.equals(newStateObj)) {
            return stateObj;
        }
        CarrierState cs = (stateObj instanceof CarrierState existing) ? existing : new CarrierState();
        cs.putDirty(pos12, newId, 0L);
        notifyMutationListener(section, pos12, prevId, newId);
        return cs;
    }

    public static int getDirtyCount(Object stateObj) {
        return (stateObj instanceof CarrierState cs) ? cs.dirtyCount() : 0;
    }

    public static int[] snapshotDirtyOverlay(Object stateObj) {
        return (stateObj instanceof CarrierState cs) ? cs.snapshotPackedDirtyArray() : EMPTY_INTS;
    }

    public static Object clearDirtyAfterCompaction(Object stateObj, byte[] newBaseFrame, long newSecDed64) {
        CarrierState cs = (stateObj instanceof CarrierState existing) ? existing : new CarrierState();
        cs.clearAfterCompaction(newBaseFrame, newSecDed64);
        return cs;
    }

    public static Object tryDirectSerialize1(Object self) {
        MethodHandle mh = directChunkSerializeHook;
        if (mh == null) {
            return null;
        }
        try {
            return mh.invoke(self, null);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Direct chunk serialize hook fault; falling back to NMS write: " + t.getMessage(), t);
            return null;
        }
    }

    public static Object tryDirectSerialize2(Object level, Object chunk) {
        MethodHandle mh = directChunkSerializeHook;
        if (mh == null) {
            return null;
        }
        try {
            return mh.invoke(level, chunk);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Direct chunk serialize hook fault; falling back to NMS write: " + t.getMessage(), t);
            return null;
        }
    }

    public static Object tryDirectHydrate3(Object arg0, Object arg1, Object arg2) {
        MethodHandle mh = directChunkHydrateHook;
        if (mh == null) {
            return null;
        }
        try {
            return mh.invoke(arg0, arg1, arg2);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Direct chunk hydrate hook fault; falling back to NMS parse: " + t.getMessage(), t);
            return null;
        }
    }

    private static void notifyMutationListener(Object section, int pos12, int prevStateId, int newStateId) {
        MethodHandle listener = sectionMutationListener;
        if (listener != null) {
            try {
                listener.invoke(section, pos12, prevStateId, newStateId);
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.WARNING, "[LeafAnvil Agent] Section mutation listener fault: " + t.getMessage(), t);
            }
        }
    }

    /**
     * Compact per-section carrier state storing an immutable `.lca` `baseFrame` reference and a
     * flat resizable open-addressed `(pos12 -> stateId19)` primitive hash table (`0 -> 16 -> 64 -> 256 -> 1024 -> 8192`).
     */
    static final class CarrierState {
        volatile byte[] baseFrame = EMPTY_BYTES;
        volatile int uniformStateId;
        volatile int dataVersion;
        volatile long secDed64;
        volatile long lastMutationTick;
        private int[] table = EMPTY_INTS;
        private int size;

        synchronized void mount(byte[] frame, int uniformId, int dv, long secDed) {
            this.baseFrame = frame != null ? frame : EMPTY_BYTES;
            this.uniformStateId = uniformId;
            this.dataVersion = dv;
            this.secDed64 = secDed;
            this.table = EMPTY_INTS;
            this.size = 0;
        }

        synchronized int dirtyCount() {
            return this.size;
        }

        synchronized void clearAfterCompaction(byte[] newFrame, long newSecDed) {
            if (newFrame != null) {
                this.baseFrame = newFrame;
            }
            this.secDed64 = newSecDed;
            this.table = EMPTY_INTS;
            this.size = 0;
        }

        synchronized void putDirty(int pos12, int stateId19, long tick) {
            int maskedPos = pos12 & MAX_POS_12;
            int packed = ((maskedPos + 1) << 19) | (stateId19 & MAX_STATE_ID_19);
            this.lastMutationTick = tick;
            int[] tab = this.table;
            int cap = tab.length;
            if (cap == 0) {
                int[] next = new int[16];
                next[0] = packed;
                this.table = next;
                this.size = 1;
                return;
            }
            if (cap == 16) {
                for (int i = 0; i < this.size; i++) {
                    if ((((tab[i] >>> 19) - 1) & MAX_POS_12) == maskedPos) {
                        tab[i] = packed;
                        return;
                    }
                }
                if (this.size < 16) {
                    tab[this.size++] = packed;
                    return;
                }
                rehash(64, packed);
                return;
            }
            int mask = cap - 1;
            int slot = initialSlot(maskedPos, cap);
            int maxLoad = cap >= 8192 ? 4096 : ((cap * 3) >>> 2);
            for (int probes = 0; probes < cap; probes++) {
                int existing = tab[slot];
                if (existing == 0) {
                    if (this.size < maxLoad) {
                        tab[slot] = packed;
                        this.size++;
                        return;
                    }
                    break;
                }
                if ((((existing >>> 19) - 1) & MAX_POS_12) == maskedPos) {
                    tab[slot] = packed;
                    return;
                }
                slot = (slot + 1) & mask;
            }
            int nextCap = cap == 64 ? 256 : (cap == 256 ? 1024 : 8192);
            rehash(nextCap, packed);
        }

        private synchronized void rehash(int newCap, int extraPacked) {
            int[] oldTab = this.table;
            int[] newTab = new int[newCap];
            int mask = newCap - 1;
            int count = 0;
            for (int entry : oldTab) {
                if (entry != 0) {
                    int pos = ((entry >>> 19) - 1) & MAX_POS_12;
                    int s = initialSlot(pos, newCap);
                    while (newTab[s] != 0) {
                        s = (s + 1) & mask;
                    }
                    newTab[s] = entry;
                    count++;
                }
            }
            int addPos = ((extraPacked >>> 19) - 1) & MAX_POS_12;
            int s = initialSlot(addPos, newCap);
            while (newTab[s] != 0) {
                if ((((newTab[s] >>> 19) - 1) & MAX_POS_12) == addPos) {
                    newTab[s] = extraPacked;
                    this.table = newTab;
                    this.size = count;
                    return;
                }
                s = (s + 1) & mask;
            }
            newTab[s] = extraPacked;
            this.table = newTab;
            this.size = count + 1;
        }

        synchronized int[] snapshotPackedDirtyArray() {
            if (this.size == 0 || this.table.length == 0) {
                return EMPTY_INTS;
            }
            if (this.table.length == 16) {
                return Arrays.copyOf(this.table, this.size);
            }
            int[] out = new int[this.size];
            int idx = 0;
            for (int entry : this.table) {
                if (entry != 0 && idx < out.length) {
                    out[idx++] = entry;
                }
            }
            return idx == out.length ? out : Arrays.copyOf(out, idx);
        }

        private static int initialSlot(int pos12, int cap) {
            int shift = Integer.numberOfLeadingZeros(cap) + 1;
            return ((pos12 * FIBONACCI_HASH_MULTIPLIER) >>> shift) & (cap - 1);
        }
    }
}
