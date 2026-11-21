package net.leaf.anvil.common.net;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free Layer-7 packet count, payload byte, TCP flush segment, and Layer-3/Layer-4 framing
 * differential profiler for {@code LeafAnvil} and {@code LeafAnvil-Net} low-bandwidth (`500 kbps`
 * nominal / `350 kbps` practical usable upload) server pacing (`leafanvil-ADR-003`,
 * `leafanvil-ADR-004`).
 * <p>
 * Structured with fixed-index primitive arrays and flat records with zero reflection so automated
 * Java-to-Rust and cross-language translation maps 1:1 to fixed-size native structs.
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-004, leafanvil-ADR-002, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-004
 * @governedBy leafanvil-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaPacketDifferentialProfiler {

    public static final int BUCKET_CHUNK_STREAM = 0;
    public static final int BUCKET_ENTITY_KINEMATICS = 1;
    public static final int BUCKET_ENTITY_METADATA_NBT = 2;
    public static final int BUCKET_WORLD_BLOCK_DELTA = 3;
    public static final int BUCKET_TRANSIENT_FX_UI = 4;
    public static final int BUCKET_TCP_FRAMING_TAX = 5;
    public static final int BUCKET_COUNT = 6;

    /**
     * Standard Layer-3/Layer-4 TCP/IPv4 + timestamp framing overhead per flushed segment (`52 B`).
     */
    public static final int DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT = 52;

    /**
     * Conservative TCP payload MTU before segmentation (`1,400 B`).
     */
    public static final int DEFAULT_MTU_PAYLOAD_BYTES = 1400;

    /**
     * Standard Minecraft server tick frequency (`20 TPS`).
     */
    public static final int TICKS_PER_SECOND = 20;

    /**
     * Nominal residential / constrained uplink target (`500 kbps`).
     */
    public static final int NOMINAL_500K_UPLOAD_KBPS = 500;

    /**
     * Practical usable goodput on a nominal `500 kbps` link (`70%` = `350 kbps`, within the
     * `2/3` to `3/4` real-world ISP shaping and bufferbloat-free ceiling).
     */
    public static final int PRACTICAL_500K_USABLE_KBPS = 350;

    /**
     * Total physical wire byte budget per server tick (`20 TPS`) at `350 kbps` usable upload
     * (`2,187 bytes/tick` across all connected players combined).
     */
    public static final int PRACTICAL_500K_BYTES_PER_TICK =
            (PRACTICAL_500K_USABLE_KBPS * 1000) / (8 * TICKS_PER_SECOND);

    private final int headerBytesPerSegment;
    private final int mtuPayloadBytes;

    private final AtomicLongArray vanillaPacketCounts = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray vanillaRawPayloadBytes = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray vanillaWirePayloadBytes = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray vanillaFlushSegments = new AtomicLongArray(BUCKET_COUNT);

    private final AtomicLongArray optimizedPacketCounts = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray optimizedWirePayloadBytes = new AtomicLongArray(BUCKET_COUNT);
    private final AtomicLongArray optimizedFlushSegments = new AtomicLongArray(BUCKET_COUNT);

    private final AtomicLong currentTickNonChunkWireBytes = new AtomicLong();
    private final AtomicLong currentTickNonChunkSegments = new AtomicLong();
    private final AtomicLong sampledTicks = new AtomicLong();

    public LcaPacketDifferentialProfiler() {
        this(DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT, DEFAULT_MTU_PAYLOAD_BYTES);
    }

    public LcaPacketDifferentialProfiler(int headerBytesPerSegment, int mtuPayloadBytes) {
        if (headerBytesPerSegment < 0) {
            throw new IllegalArgumentException("headerBytesPerSegment must be >= 0: " + headerBytesPerSegment);
        }
        if (mtuPayloadBytes <= 0) {
            throw new IllegalArgumentException("mtuPayloadBytes must be > 0: " + mtuPayloadBytes);
        }
        this.headerBytesPerSegment = headerBytesPerSegment;
        this.mtuPayloadBytes = mtuPayloadBytes;
    }

    public int headerBytesPerSegment() {
        return headerBytesPerSegment;
    }

    public int mtuPayloadBytes() {
        return mtuPayloadBytes;
    }

    /**
     * Computes the number of physical TCP segments emitted by a single flush of {@code wireBytes}.
     */
    public static int computeSegmentsForFlush(int wireBytes, int mtuBytes) {
        if (wireBytes <= 0) {
            return 0;
        }
        int effectiveMtu = Math.max(256, mtuBytes);
        return (wireBytes + effectiveMtu - 1) / effectiveMtu;
    }

    /**
     * Computes physical Layer-3/Layer-4 wire bytes (`L7 payload + segmentCount * headerBytes`).
     */
    public static long computePhysicalWireBytes(
            long l7WirePayloadBytes,
            long flushSegmentCount,
            int headerBytesPerSegment
    ) {
        return Math.max(0L, l7WirePayloadBytes)
                + Math.max(0L, flushSegmentCount) * Math.max(0, headerBytesPerSegment);
    }

    /**
     * Converts a usable upload bandwidth cap in `kbps` (e.g. `350 kbps`) to bytes per `20 TPS` tick.
     */
    public static int computeTickByteBudgetForKbps(int usableUploadKbps) {
        if (usableUploadKbps <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(256, (usableUploadKbps * 1000) / (8 * TICKS_PER_SECOND));
    }

    /**
     * Computes remaining physical wire bytes available for chunk streaming in the current tick after
     * deducting non-chunk Layer-7 traffic and Layer-3/Layer-4 TCP framing headers.
     */
    public static int computeRemainingTickChunkBudgetBytes(
            int usableUploadKbps,
            long nonChunkWireBytesThisTick,
            long nonChunkFlushSegmentsThisTick,
            int headerBytesPerSegment
    ) {
        if (usableUploadKbps <= 0) {
            return Integer.MAX_VALUE;
        }
        int totalTickBudget = computeTickByteBudgetForKbps(usableUploadKbps);
        long nonChunkPhysical = computePhysicalWireBytes(
                nonChunkWireBytesThisTick,
                nonChunkFlushSegmentsThisTick,
                headerBytesPerSegment
        );
        long remaining = (long) totalTickBudget - nonChunkPhysical;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, remaining));
    }

    /**
     * Classifies a logical Minecraft packet identifier or class simple name into one of the five
     * Layer-7 traffic buckets (`0..4`) with zero reflection.
     */
    public static int classifyPacketName(String packetIdentifier) {
        if (packetIdentifier == null || packetIdentifier.isEmpty()) {
            return BUCKET_TRANSIENT_FX_UI;
        }
        String norm = packetIdentifier.toLowerCase(Locale.ROOT);
        if (norm.contains("chunk")
                || norm.contains("lca_chunk")
                || norm.contains("lca_region_dict")
                || norm.contains("lightupdate")) {
            return BUCKET_CHUNK_STREAM;
        }
        if (norm.contains("moveentity")
                || norm.contains("rotatehead")
                || norm.contains("teleportentity")
                || norm.contains("entitymotion")
                || norm.contains("entityvelocity")
                || norm.contains("kinematic")) {
            return BUCKET_ENTITY_KINEMATICS;
        }
        if (norm.contains("entitydata")
                || norm.contains("equipment")
                || norm.contains("attributes")
                || norm.contains("addentity")
                || norm.contains("spawnentity")
                || norm.contains("metadata")) {
            return BUCKET_ENTITY_METADATA_NBT;
        }
        if (norm.contains("blockupdate")
                || norm.contains("sectionblocksupdate")
                || norm.contains("blockentity")
                || norm.contains("blockchanged")
                || norm.contains("multiblockchange")) {
            return BUCKET_WORLD_BLOCK_DELTA;
        }
        return BUCKET_TRANSIENT_FX_UI;
    }

    /**
     * Records a comparative packet count and wire-byte observation for a traffic bucket.
     */
    public void recordPacketDifferential(
            int bucketId,
            int vanillaPackets,
            int vanillaRawBytes,
            int vanillaWireBytes,
            int vanillaFlushes,
            int optimizedPackets,
            int optimizedWireBytes,
            int optimizedFlushes
    ) {
        int b = validateBucket(bucketId);
        int vSegments = vanillaFlushes > 0
                ? vanillaFlushes
                : computeSegmentsForFlush(vanillaWireBytes, mtuPayloadBytes);
        int oSegments = optimizedFlushes > 0
                ? optimizedFlushes
                : computeSegmentsForFlush(optimizedWireBytes, mtuPayloadBytes);

        if (vanillaPackets > 0) {
            vanillaPacketCounts.addAndGet(b, vanillaPackets);
        }
        if (vanillaRawBytes > 0) {
            vanillaRawPayloadBytes.addAndGet(b, vanillaRawBytes);
        }
        if (vanillaWireBytes > 0) {
            vanillaWirePayloadBytes.addAndGet(b, vanillaWireBytes);
        }
        if (vSegments > 0) {
            vanillaFlushSegments.addAndGet(b, vSegments);
            vanillaFlushSegments.addAndGet(BUCKET_TCP_FRAMING_TAX, vSegments);
            vanillaWirePayloadBytes.addAndGet(
                    BUCKET_TCP_FRAMING_TAX,
                    (long) vSegments * headerBytesPerSegment
            );
        }

        if (optimizedPackets > 0) {
            optimizedPacketCounts.addAndGet(b, optimizedPackets);
        }
        if (optimizedWireBytes > 0) {
            optimizedWirePayloadBytes.addAndGet(b, optimizedWireBytes);
        }
        if (oSegments > 0) {
            optimizedFlushSegments.addAndGet(b, oSegments);
            optimizedFlushSegments.addAndGet(BUCKET_TCP_FRAMING_TAX, oSegments);
            optimizedWirePayloadBytes.addAndGet(
                    BUCKET_TCP_FRAMING_TAX,
                    (long) oSegments * headerBytesPerSegment
            );
        }

        if (b != BUCKET_CHUNK_STREAM && b != BUCKET_TCP_FRAMING_TAX) {
            if (optimizedWireBytes > 0) {
                currentTickNonChunkWireBytes.addAndGet(optimizedWireBytes);
            }
            if (oSegments > 0) {
                currentTickNonChunkSegments.addAndGet(oSegments);
            }
        }
    }

    /**
     * Records outbound non-chunk traffic emitted during the current server tick so the global
     * `500 kbps` (`350 kbps` usable) pacer can deduct it from the current tick's chunk budget.
     */
    public void recordTickNonChunkTraffic(int bucketId, int optimizedWireBytes, int flushSegments) {
        int b = validateBucket(bucketId);
        int segments = flushSegments > 0
                ? flushSegments
                : computeSegmentsForFlush(optimizedWireBytes, mtuPayloadBytes);
        if (optimizedWireBytes > 0) {
            optimizedPacketCounts.incrementAndGet(b);
            optimizedWirePayloadBytes.addAndGet(b, optimizedWireBytes);
            currentTickNonChunkWireBytes.addAndGet(optimizedWireBytes);
        }
        if (segments > 0) {
            optimizedFlushSegments.addAndGet(b, segments);
            optimizedFlushSegments.addAndGet(BUCKET_TCP_FRAMING_TAX, segments);
            optimizedWirePayloadBytes.addAndGet(
                    BUCKET_TCP_FRAMING_TAX,
                    (long) segments * headerBytesPerSegment
            );
            currentTickNonChunkSegments.addAndGet(segments);
        }
    }

    /**
     * Returns the remaining chunk wire byte budget for the current tick under {@code usableUploadKbps}.
     */
    public int remainingTickChunkBudgetBytes(int usableUploadKbps) {
        return computeRemainingTickChunkBudgetBytes(
                usableUploadKbps,
                currentTickNonChunkWireBytes.get(),
                currentTickNonChunkSegments.get(),
                headerBytesPerSegment
        );
    }

    /**
     * Advances the tick window, resetting the per-tick non-chunk accumulators and incrementing the
     * sampled tick count.
     */
    public long advanceTickWindow() {
        currentTickNonChunkWireBytes.set(0L);
        currentTickNonChunkSegments.set(0L);
        return sampledTicks.incrementAndGet();
    }

    public long currentTickNonChunkWireBytes() {
        return currentTickNonChunkWireBytes.get();
    }

    public long currentTickNonChunkSegments() {
        return currentTickNonChunkSegments.get();
    }

    public long sampledTicks() {
        return sampledTicks.get();
    }

    /**
     * Resets all cumulative and per-tick counters.
     */
    public void resetAll() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            vanillaPacketCounts.set(i, 0L);
            vanillaRawPayloadBytes.set(i, 0L);
            vanillaWirePayloadBytes.set(i, 0L);
            vanillaFlushSegments.set(i, 0L);
            optimizedPacketCounts.set(i, 0L);
            optimizedWirePayloadBytes.set(i, 0L);
            optimizedFlushSegments.set(i, 0L);
        }
        currentTickNonChunkWireBytes.set(0L);
        currentTickNonChunkSegments.set(0L);
        sampledTicks.set(0L);
    }

    /**
     * Captures a point-in-time differential snapshot across all 6 traffic buckets.
     */
    public DifferentialSnapshot snapshot(int activePlayers) {
        int players = Math.max(1, activePlayers);
        long ticks = Math.max(1L, sampledTicks.get());
        BucketDifferential[] buckets = new BucketDifferential[BUCKET_COUNT];

        long totalVanillaPackets = 0L;
        long totalVanillaRawL7 = 0L;
        long totalVanillaWireL7 = 0L;
        long totalVanillaSegments = 0L;

        long totalOptimizedPackets = 0L;
        long totalOptimizedWireL7 = 0L;
        long totalOptimizedSegments = 0L;

        for (int b = 0; b < BUCKET_COUNT; b++) {
            long vPkt = vanillaPacketCounts.get(b);
            long vRaw = vanillaRawPayloadBytes.get(b);
            long vWire = vanillaWirePayloadBytes.get(b);
            long vSeg = vanillaFlushSegments.get(b);
            long oPkt = optimizedPacketCounts.get(b);
            long oWire = optimizedWirePayloadBytes.get(b);
            long oSeg = optimizedFlushSegments.get(b);

            buckets[b] = new BucketDifferential(
                    b,
                    bucketName(b),
                    vPkt,
                    vRaw,
                    vWire,
                    vSeg,
                    oPkt,
                    oWire,
                    oSeg,
                    vWire - oWire,
                    vPkt - oPkt
            );

            if (b != BUCKET_TCP_FRAMING_TAX) {
                totalVanillaPackets += vPkt;
                totalVanillaRawL7 += vRaw;
                totalVanillaWireL7 += vWire;
                totalVanillaSegments += vSeg;

                totalOptimizedPackets += oPkt;
                totalOptimizedWireL7 += oWire;
                totalOptimizedSegments += oSeg;
            }
        }

        long vanillaPhysicalBytes = computePhysicalWireBytes(
                totalVanillaWireL7,
                totalVanillaSegments,
                headerBytesPerSegment
        );
        long optimizedPhysicalBytes = computePhysicalWireBytes(
                totalOptimizedWireL7,
                totalOptimizedSegments,
                headerBytesPerSegment
        );
        long savedPhysicalBytes = Math.max(0L, vanillaPhysicalBytes - optimizedPhysicalBytes);
        double reductionRatio = vanillaPhysicalBytes > 0L
                ? (double) savedPhysicalBytes / (double) vanillaPhysicalBytes
                : 0.0;

        double seconds = (double) ticks / (double) TICKS_PER_SECOND;
        double vanillaKbps = seconds > 0.0 ? (vanillaPhysicalBytes * 8.0) / (seconds * 1000.0) : 0.0;
        double optimizedKbps = seconds > 0.0 ? (optimizedPhysicalBytes * 8.0) / (seconds * 1000.0) : 0.0;
        double kbpsPerPlayer = optimizedKbps / (double) players;
        int estimatedMaxPlayersAt350Kbps = kbpsPerPlayer > 0.0
                ? (int) Math.floor(PRACTICAL_500K_USABLE_KBPS / kbpsPerPlayer)
                : players;

        return new DifferentialSnapshot(
                players,
                ticks,
                totalVanillaPackets,
                totalOptimizedPackets,
                totalVanillaRawL7,
                totalVanillaWireL7,
                totalOptimizedWireL7,
                totalVanillaSegments,
                totalOptimizedSegments,
                vanillaPhysicalBytes,
                optimizedPhysicalBytes,
                savedPhysicalBytes,
                reductionRatio,
                vanillaKbps,
                optimizedKbps,
                estimatedMaxPlayersAt350Kbps,
                buckets
        );
    }

    public static String bucketName(int bucketId) {
        return switch (bucketId) {
            case BUCKET_CHUNK_STREAM -> "CHUNK_STREAM";
            case BUCKET_ENTITY_KINEMATICS -> "ENTITY_KINEMATICS";
            case BUCKET_ENTITY_METADATA_NBT -> "ENTITY_METADATA_NBT";
            case BUCKET_WORLD_BLOCK_DELTA -> "WORLD_BLOCK_DELTA";
            case BUCKET_TRANSIENT_FX_UI -> "TRANSIENT_FX_UI";
            case BUCKET_TCP_FRAMING_TAX -> "TCP_FRAMING_TAX";
            default -> "UNKNOWN_" + bucketId;
        };
    }

    private static int validateBucket(int bucketId) {
        if (bucketId < 0 || bucketId >= BUCKET_COUNT) {
            throw new IllegalArgumentException("Invalid bucketId (expected 0.." + (BUCKET_COUNT - 1) + "): " + bucketId);
        }
        return bucketId;
    }

    /**
     * Per-bucket packet and wire-byte differential record.
     */
    public record BucketDifferential(
            int bucketId,
            String bucketName,
            long vanillaPacketCount,
            long vanillaRawBytes,
            long vanillaWireBytes,
            long vanillaSegments,
            long optimizedPacketCount,
            long optimizedWireBytes,
            long optimizedSegments,
            long savedWireBytes,
            long savedPackets
    ) {
        public BucketDifferential {
            Objects.requireNonNull(bucketName, "bucketName");
        }
    }

    /**
     * Aggregate Layer-7 + Layer-3/Layer-4 wire differential snapshot across all 6 traffic buckets.
     */
    public record DifferentialSnapshot(
            int activePlayers,
            long sampledTicks,
            long totalVanillaPackets,
            long totalOptimizedPackets,
            long totalVanillaRawL7Bytes,
            long totalVanillaWireL7Bytes,
            long totalOptimizedWireL7Bytes,
            long totalVanillaSegments,
            long totalOptimizedSegments,
            long totalVanillaPhysicalWireBytes,
            long totalOptimizedPhysicalWireBytes,
            long savedPhysicalWireBytes,
            double physicalWireReductionRatio,
            double vanillaTotalKbps,
            double optimizedTotalKbps,
            int estimatedMaxPlayersAt350Kbps,
            BucketDifferential[] buckets
    ) {
        public DifferentialSnapshot {
            Objects.requireNonNull(buckets, "buckets");
            buckets = buckets.clone();
        }

        public BucketDifferential bucket(int bucketId) {
            return buckets[validateBucket(bucketId)];
        }

        public double vanillaKbpsPerPlayer() {
            return activePlayers > 0 ? vanillaTotalKbps / (double) activePlayers : vanillaTotalKbps;
        }

        public double optimizedKbpsPerPlayer() {
            return activePlayers > 0 ? optimizedTotalKbps / (double) activePlayers : optimizedTotalKbps;
        }

        /**
         * Estimates the maximum concurrent players supported by any usable upload ceiling in `kbps`
         * based on the measured per-player physical wire throughput.
         */
        public int estimateMaxPlayersForUsableKbps(double usableUploadKbps) {
            if (usableUploadKbps <= 0.0) {
                return 0;
            }
            double perPlayer = optimizedKbpsPerPlayer();
            if (perPlayer <= 0.0) {
                return Math.max(1, activePlayers);
            }
            return (int) Math.floor(usableUploadKbps / perPlayer);
        }

        /**
         * Estimates the maximum concurrent players supported by a nominal upload link in `kbps`
         * (e.g., `500`, `1000`, `5000`) at a practical usable link fraction (e.g., `2.0 / 3.0` to `0.75`).
         */
        public int estimateMaxPlayersForNominalKbps(double nominalUploadKbps, double usableLinkFraction) {
            if (nominalUploadKbps <= 0.0 || usableLinkFraction <= 0.0) {
                return 0;
            }
            double clampedFraction = Math.min(1.0, usableLinkFraction);
            return estimateMaxPlayersForUsableKbps(nominalUploadKbps * clampedFraction);
        }

        /**
         * Estimates the maximum concurrent players on aVanilla server for a nominal upload link in `kbps`
         * at the specified usable link fraction.
         */
        public int estimateVanillaMaxPlayersForNominalKbps(double nominalUploadKbps, double usableLinkFraction) {
            if (nominalUploadKbps <= 0.0 || usableLinkFraction <= 0.0) {
                return 0;
            }
            double usableKbps = nominalUploadKbps * Math.min(1.0, usableLinkFraction);
            double perPlayer = vanillaKbpsPerPlayer();
            if (perPlayer <= 0.0) {
                return Math.max(1, activePlayers);
            }
            return (int) Math.floor(usableKbps / perPlayer);
        }

        public boolean fitsUsableUplinkKbps(double usableUploadKbps) {
            return optimizedTotalKbps <= usableUploadKbps;
        }

        public boolean fitsPractical500kUplink() {
            return fitsUsableUplinkKbps(PRACTICAL_500K_USABLE_KBPS);
        }

        public String toJson() {
            return String.format(
                    Locale.ROOT,
                    "{\"activePlayers\":%d,\"sampledTicks\":%d,"
                            + "\"vanillaPackets\":%d,\"optimizedPackets\":%d,"
                            + "\"vanillaWireL7Bytes\":%d,\"optimizedWireL7Bytes\":%d,"
                            + "\"vanillaSegments\":%d,\"optimizedSegments\":%d,"
                            + "\"vanillaPhysicalBytes\":%d,\"optimizedPhysicalBytes\":%d,"
                            + "\"savedPhysicalBytes\":%d,\"physicalReductionPct\":%.2f,"
                            + "\"vanillaTotalKbps\":%.2f,\"optimizedTotalKbps\":%.2f,"
                            + "\"vanillaKbpsPerPlayer\":%.2f,\"optimizedKbpsPerPlayer\":%.2f,"
                            + "\"maxPlayers500kAt67Pct\":%d,\"maxPlayers500kAt70Pct\":%d,\"maxPlayers500kAt75Pct\":%d,"
                            + "\"vanillaMaxPlayers500kAt70Pct\":%d}",
                    activePlayers,
                    sampledTicks,
                    totalVanillaPackets,
                    totalOptimizedPackets,
                    totalVanillaWireL7Bytes,
                    totalOptimizedWireL7Bytes,
                    totalVanillaSegments,
                    totalOptimizedSegments,
                    totalVanillaPhysicalWireBytes,
                    totalOptimizedPhysicalWireBytes,
                    savedPhysicalWireBytes,
                    physicalWireReductionRatio * 100.0,
                    vanillaTotalKbps,
                    optimizedTotalKbps,
                    vanillaKbpsPerPlayer(),
                    optimizedKbpsPerPlayer(),
                    estimateMaxPlayersForNominalKbps(500.0, 2.0 / 3.0),
                    estimateMaxPlayersForNominalKbps(500.0, 0.70),
                    estimateMaxPlayersForNominalKbps(500.0, 0.75),
                    estimateVanillaMaxPlayersForNominalKbps(500.0, 0.70)
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DifferentialSnapshot other)) {
                return false;
            }
            return this.activePlayers == other.activePlayers
                    && this.sampledTicks == other.sampledTicks
                    && this.totalVanillaPackets == other.totalVanillaPackets
                    && this.totalOptimizedPackets == other.totalOptimizedPackets
                    && this.totalVanillaRawL7Bytes == other.totalVanillaRawL7Bytes
                    && this.totalVanillaWireL7Bytes == other.totalVanillaWireL7Bytes
                    && this.totalOptimizedWireL7Bytes == other.totalOptimizedWireL7Bytes
                    && this.totalVanillaSegments == other.totalVanillaSegments
                    && this.totalOptimizedSegments == other.totalOptimizedSegments
                    && this.totalVanillaPhysicalWireBytes == other.totalVanillaPhysicalWireBytes
                    && this.totalOptimizedPhysicalWireBytes == other.totalOptimizedPhysicalWireBytes
                    && this.savedPhysicalWireBytes == other.savedPhysicalWireBytes
                    && Double.compare(this.physicalWireReductionRatio, other.physicalWireReductionRatio) == 0
                    && Double.compare(this.vanillaTotalKbps, other.vanillaTotalKbps) == 0
                    && Double.compare(this.optimizedTotalKbps, other.optimizedTotalKbps) == 0
                    && this.estimatedMaxPlayersAt350Kbps == other.estimatedMaxPlayersAt350Kbps
                    && java.util.Arrays.equals(this.buckets, other.buckets);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.activePlayers;
            result = 31 * result + Long.hashCode(this.sampledTicks);
            result = 31 * result + Long.hashCode(this.totalVanillaPackets);
            result = 31 * result + Long.hashCode(this.totalOptimizedPackets);
            result = 31 * result + Long.hashCode(this.totalVanillaRawL7Bytes);
            result = 31 * result + Long.hashCode(this.totalVanillaWireL7Bytes);
            result = 31 * result + Long.hashCode(this.totalOptimizedWireL7Bytes);
            result = 31 * result + Long.hashCode(this.totalVanillaSegments);
            result = 31 * result + Long.hashCode(this.totalOptimizedSegments);
            result = 31 * result + Long.hashCode(this.totalVanillaPhysicalWireBytes);
            result = 31 * result + Long.hashCode(this.totalOptimizedPhysicalWireBytes);
            result = 31 * result + Long.hashCode(this.savedPhysicalWireBytes);
            result = 31 * result + Double.hashCode(this.physicalWireReductionRatio);
            result = 31 * result + Double.hashCode(this.vanillaTotalKbps);
            result = 31 * result + Double.hashCode(this.optimizedTotalKbps);
            result = 31 * result + this.estimatedMaxPlayersAt350Kbps;
            result = 31 * result + java.util.Arrays.hashCode(this.buckets);
            return result;
        }

        @Override
        public String toString() {
            return "DifferentialSnapshot[activePlayers=" + this.activePlayers
                    + ", sampledTicks=" + this.sampledTicks
                    + ", totalVanillaPackets=" + this.totalVanillaPackets
                    + ", totalOptimizedPackets=" + this.totalOptimizedPackets
                    + ", totalVanillaRawL7Bytes=" + this.totalVanillaRawL7Bytes
                    + ", totalVanillaWireL7Bytes=" + this.totalVanillaWireL7Bytes
                    + ", totalOptimizedWireL7Bytes=" + this.totalOptimizedWireL7Bytes
                    + ", totalVanillaSegments=" + this.totalVanillaSegments
                    + ", totalOptimizedSegments=" + this.totalOptimizedSegments
                    + ", totalVanillaPhysicalWireBytes=" + this.totalVanillaPhysicalWireBytes
                    + ", totalOptimizedPhysicalWireBytes=" + this.totalOptimizedPhysicalWireBytes
                    + ", savedPhysicalWireBytes=" + this.savedPhysicalWireBytes
                    + ", physicalWireReductionRatio=" + this.physicalWireReductionRatio
                    + ", vanillaTotalKbps=" + this.vanillaTotalKbps
                    + ", optimizedTotalKbps=" + this.optimizedTotalKbps
                    + ", estimatedMaxPlayersAt350Kbps=" + this.estimatedMaxPlayersAt350Kbps
                    + ", buckets=" + java.util.Arrays.toString(this.buckets)
                    + "]";
        }
    }
}
