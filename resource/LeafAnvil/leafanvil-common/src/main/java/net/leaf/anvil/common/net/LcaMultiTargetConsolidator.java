package net.leaf.anvil.common.net;

import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.KnownSlotBitSet;
import net.leaf.anvil.common.net.LcaOrderlessDictTable.OrderlessEncodedBatch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-target (`1-to-M`) spatial broadcast group consolidator, quantized entity kinematic delta
 * bundler (`4–6 B` per moving entity), end-of-tick frame coalescer, standalone NIO/TCP `1-to-M`
 * edge relay, and `500 kbps` (`350 kbps` practical usable) spiral-ring bandwidth allocator for
 * {@code LeafAnvil} and {@code LeafAnvil-Net} (`leafanvil-ADR-003`, `leafanvil-ADR-004`).
 * <p>
 * Designed with flat records and primitive arrays for 1:1 Rust and cross-language auto-translation.
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
public final class LcaMultiTargetConsolidator {

    public static final int MULTI_TARGET_FRAME_MAGIC = 0x4D544346; // "MTCF"
    public static final int MAX_TARGET_PLAYER_SLOTS = 4096;
    public static final int MAX_TARGET_BITMAP_WORDS = MAX_TARGET_PLAYER_SLOTS >>> 6; // 64 words = 4,096 slots

    public static final int RING_0_IMMEDIATE = 0; // Chebyshev distance <= 2 (25 immediate playable chunks)
    public static final int RING_1_MIDFIELD = 1;  // Chebyshev distance 3..6
    public static final int RING_2_HORIZON = 2;   // Chebyshev distance >= 7

    public static final int RING_0_MAX_RADIUS = 2;
    public static final int RING_1_MAX_RADIUS = 6;

    /**
     * Minimum per-player tick chunk budget on constrained `350 kbps` links (`512 B/tick`), allowing
     * partial or orderless-dictionary-compressed chunk progression without exceeding global uplink.
     */
    public static final int LOW_BANDWIDTH_MIN_PLAYER_BUDGET_BYTES = 512;

    private LcaMultiTargetConsolidator() {
    }

    /**
     * Classifies a chunk coordinate into one of three spiral priority rings relative to the player's
     * current chunk center:
     * <ul>
     *   <li>{@link #RING_0_IMMEDIATE} ({@code r <= 2}): `25` chunks immediately under and around the player</li>
     *   <li>{@link #RING_1_MIDFIELD} (`r = 3..6`): active walking and combat visibility ring</li>
     *   <li>{@link #RING_2_HORIZON} (`r >= 7`): horizon chunks served from `LCAD` revisit cache or idle trickle</li>
     * </ul>
     */
    public static int classifySpiralRing(int playerChunkX, int playerChunkZ, int chunkX, int chunkZ) {
        int dist = chebyshevChunkDistance(playerChunkX, playerChunkZ, chunkX, chunkZ);
        if (dist <= RING_0_MAX_RADIUS) {
            return RING_0_IMMEDIATE;
        }
        if (dist <= RING_1_MAX_RADIUS) {
            return RING_1_MIDFIELD;
        }
        return RING_2_HORIZON;
    }

    public static int chebyshevChunkDistance(int playerChunkX, int playerChunkZ, int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
    }

    /**
     * Returns a new list of candidate {@link TrackedChunkColumn}s ordered by spiral priority ring
     * (`Ring 0` $\to$ `Ring 1` $\to$ `Ring 2`), then by Chebyshev distance and Manhattan distance
     * from `(playerChunkX, playerChunkZ)`.
     */
    public static List<TrackedChunkColumn> sortColumnsBySpiralRingPriority(
            int playerChunkX,
            int playerChunkZ,
            List<TrackedChunkColumn> candidateColumns
    ) {
        Objects.requireNonNull(candidateColumns, "candidateColumns");
        if (candidateColumns.size() <= 1) {
            return List.copyOf(candidateColumns);
        }
        List<TrackedChunkColumn> sorted = new ArrayList<>(candidateColumns.size());
        for (int i = 0; i < candidateColumns.size(); i++) {
            TrackedChunkColumn col = candidateColumns.get(i);
            if (col != null) {
                sorted.add(col);
            }
        }
        sorted.sort(Comparator
                .comparingInt((TrackedChunkColumn c) ->
                        classifySpiralRing(playerChunkX, playerChunkZ, c.chunkX(), c.chunkZ()))
                .thenComparingInt(c ->
                        chebyshevChunkDistance(playerChunkX, playerChunkZ, c.chunkX(), c.chunkZ()))
                .thenComparingInt(c ->
                        Math.abs(c.chunkX() - playerChunkX) + Math.abs(c.chunkZ() - playerChunkZ))
                .thenComparingInt(TrackedChunkColumn::chunkX)
                .thenComparingInt(TrackedChunkColumn::chunkZ));
        return List.copyOf(sorted);
    }

    /**
     * Allocates per-player chunk streaming wire byte budgets for a single server tick under a
     * global usable upload cap (`usableUploadKbps`, e.g., `350 kbps` for a `500 kbps` nominal link).
     * <p>
     * Deducts live non-chunk Layer-7 traffic and Layer-3/Layer-4 TCP framing headers first, then
     * prioritizes players with pending {@link #RING_0_IMMEDIATE} ({@code r <= 2}) chunks so joining or
     * teleporting players receive immediate terrain before horizon chunks are trickle-filled.
     */
    public static int[] allocateLowBandwidthTickBudgets(
            int usableUploadKbps,
            long nonChunkWireBytesThisTick,
            long nonChunkFlushSegmentsThisTick,
            int[] playerRing0PendingCounts,
            int[] playerTotalPendingCounts
    ) {
        Objects.requireNonNull(playerRing0PendingCounts, "playerRing0PendingCounts");
        Objects.requireNonNull(playerTotalPendingCounts, "playerTotalPendingCounts");
        int playerCount = playerTotalPendingCounts.length;
        if (playerRing0PendingCounts.length != playerCount) {
            throw new IllegalArgumentException("playerRing0PendingCounts and playerTotalPendingCounts length mismatch");
        }
        int[] budgets = new int[playerCount];
        if (playerCount == 0) {
            return budgets;
        }

        int activeStreamingPlayers = 0;
        int ring0UrgentPlayers = 0;
        for (int i = 0; i < playerCount; i++) {
            if (playerTotalPendingCounts[i] > 0) {
                activeStreamingPlayers++;
                if (playerRing0PendingCounts[i] > 0) {
                    ring0UrgentPlayers++;
                }
            }
        }
        if (activeStreamingPlayers == 0) {
            return budgets;
        }

        int remainingPool = LcaPacketDifferentialProfiler.computeRemainingTickChunkBudgetBytes(
                usableUploadKbps,
                nonChunkWireBytesThisTick,
                nonChunkFlushSegmentsThisTick,
                LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT
        );

        // Deduct 1 coalesced TCP/IP segment header per active streaming player
        int framingReserve = activeStreamingPlayers
                * LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT;
        int distributableL7Bytes = Math.max(0, remainingPool - framingReserve);
        if (distributableL7Bytes == 0) {
            return budgets;
        }

        if (ring0UrgentPlayers > 0 && ring0UrgentPlayers < activeStreamingPlayers) {
            // Allocate 75% of the tick pool to players waiting on Ring 0 immediate terrain (r <= 2)
            // and 25% to background Ring 1/2 walkers.
            int ring0Pool = (distributableL7Bytes * 3) / 4;
            int backgroundPool = distributableL7Bytes - ring0Pool;
            int perRing0 = ring0Pool / ring0UrgentPlayers;
            int nonUrgentPlayers = activeStreamingPlayers - ring0UrgentPlayers;
            int perBackground = backgroundPool / nonUrgentPlayers;

            for (int i = 0; i < playerCount; i++) {
                if (playerTotalPendingCounts[i] <= 0) {
                    budgets[i] = 0;
                } else if (playerRing0PendingCounts[i] > 0) {
                    budgets[i] = perRing0;
                } else {
                    budgets[i] = perBackground;
                }
            }
        } else {
            int equalShare = distributableL7Bytes / activeStreamingPlayers;
            for (int i = 0; i < playerCount; i++) {
                if (playerTotalPendingCounts[i] > 0) {
                    budgets[i] = equalShare;
                }
            }
        }
        return budgets;
    }

    /**
     * Quantizes a floating-point position delta in blocks (`-3.96875 .. +3.96875`) into a signed
     * `1/32` block fixed-point byte (`-127 .. +127`).
     */
    public static byte quantizeBlockDelta8(double deltaBlocks) {
        long scaled = Math.round(deltaBlocks * 32.0);
        return (byte) Math.max(-127L, Math.min(127L, scaled));
    }

    public static double dequantizeBlockDelta8(byte quantizedDelta) {
        return (double) quantizedDelta / 32.0;
    }

    /**
     * Quantizes an angle in degrees (`0..360`) into a single unsigned byte (`256` steps).
     */
    public static byte quantizeAngleDegrees8(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped < 0.0f) {
            wrapped += 360.0f;
        }
        return (byte) ((int) Math.floor((wrapped * 256.0f) / 360.0f) & 0xFF);
    }

    /**
     * Packs a per-tick list of moving entity kinematic rows into a compact binary table (`4 bytes`
     * for position-only delta, `6 bytes` for position + rotation delta; stationary or constant
     * dead-reckoned entities are omitted with `0 bytes`).
     */
    public static byte[] packKinematicDeltaTable(List<KinematicDeltaRow> rows) throws IOException {
        Objects.requireNonNull(rows, "rows");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2 + rows.size() * 6);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            LcaOrderlessDictTable.writeVarInt(out, rows.size());
            for (int i = 0; i < rows.size(); i++) {
                KinematicDeltaRow row = Objects.requireNonNull(rows.get(i), "rows[i]");
                int headerByte = (row.localEntitySlot7() & 0x7F) | (row.hasRotation() ? 0x80 : 0x00);
                out.writeByte(headerByte);
                out.writeByte(row.dxFixed8());
                out.writeByte(row.dyFixed8());
                out.writeByte(row.dzFixed8());
                if (row.hasRotation()) {
                    out.writeByte(row.yawAngle8());
                    out.writeByte(row.pitchAngle8());
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * Unpacks a quantized entity kinematic delta table produced by {@link #packKinematicDeltaTable}.
     */
    public static List<KinematicDeltaRow> unpackKinematicDeltaTable(byte[] tableBytes) throws IOException {
        Objects.requireNonNull(tableBytes, "tableBytes");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(tableBytes))) {
            int count = LcaOrderlessDictTable.readVarInt(in);
            if (count < 0 || count > 4096) {
                throw new IOException("Invalid kinematic delta row count: " + count);
            }
            List<KinematicDeltaRow> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int headerByte = in.readUnsignedByte();
                int slot7 = headerByte & 0x7F;
                boolean hasRot = (headerByte & 0x80) != 0;
                byte dx = in.readByte();
                byte dy = in.readByte();
                byte dz = in.readByte();
                byte yaw = hasRot ? in.readByte() : 0;
                byte pitch = hasRot ? in.readByte() : 0;
                rows.add(new KinematicDeltaRow(slot7, dx, dy, dz, hasRot, yaw, pitch));
            }
            return rows;
        }
    }

    /**
     * Consolidates a shared spatial group tick payload across $M$ target players (`1-to-M` shared
     * encoding + `N-to-1` end-of-tick packet coalescing).
     * <p>
     * Computes the `commonKnownSlots` intersection (`bitwise AND`) across all recipients'
     * {@link KnownSlotBitSet}s, encodes recurring section/metadata/symbol slices once against
     * {@code dictTable}, updates all $M$ recipients' bitsets with any newly learned slots, and
     * wraps the shared chunk batch and quantized kinematic table into a single
     * {@link ConsolidatedMultiTargetFrame}.
     */
    public static ConsolidatedMultiTargetFrame consolidateMultiTargetTickFrame(
            List<TargetRecipient> recipients,
            LcaOrderlessDictTable dictTable,
            byte sliceKind,
            List<byte[]> sharedRecurringSlices,
            List<KinematicDeltaRow> sharedKinematicRows,
            byte[] sharedChunkBatchBytes,
            int estimatedVanillaPacketsPerPlayer
    ) throws IOException {
        Objects.requireNonNull(recipients, "recipients");
        Objects.requireNonNull(dictTable, "dictTable");
        Objects.requireNonNull(sharedRecurringSlices, "sharedRecurringSlices");
        Objects.requireNonNull(sharedKinematicRows, "sharedKinematicRows");
        byte[] chunkBytes = sharedChunkBatchBytes != null ? sharedChunkBatchBytes : new byte[0];

        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }

        int maxWordIdx = 0;
        List<KnownSlotBitSet> recipientBitSets = new ArrayList<>(recipients.size());
        for (int i = 0; i < recipients.size(); i++) {
            TargetRecipient r = Objects.requireNonNull(recipients.get(i), "recipients[i]");
            int slot = r.playerSlotIndex();
            if (slot < 0 || slot >= MAX_TARGET_PLAYER_SLOTS) {
                throw new IllegalArgumentException(
                        "playerSlotIndex must be in 0.." + (MAX_TARGET_PLAYER_SLOTS - 1) + ": " + slot
                );
            }
            int wordIdx = slot >>> 6;
            if (wordIdx > maxWordIdx) {
                maxWordIdx = wordIdx;
            }
            recipientBitSets.add(r.knownSlots());
        }

        long[] targetBitmapWords = new long[maxWordIdx + 1];
        for (int i = 0; i < recipients.size(); i++) {
            int slot = recipients.get(i).playerSlotIndex();
            targetBitmapWords[slot >>> 6] |= (1L << (slot & 63));
        }

        // 1. Compute commonKnownSlots intersection across all M recipients in the spatial group
        KnownSlotBitSet commonKnownSlots = KnownSlotBitSet.intersectAll(recipientBitSets);

        // 2. Encode recurring slices ONCE against commonKnownSlots
        OrderlessEncodedBatch encodedSlices = dictTable.encodeSlicesForTarget(
                sliceKind,
                sharedRecurringSlices,
                commonKnownSlots
        );

        // 3. Synchronize all M recipients' KnownSlotBitSets with the newly emitted inline learn entries
        for (int i = 0; i < recipients.size(); i++) {
            recipients.get(i).knownSlots().unionFrom(commonKnownSlots);
        }

        // 4. Pack quantized entity kinematic deltas into a single compact table
        byte[] kinematicBytes = packKinematicDeltaTable(sharedKinematicRows);

        // 5. Assemble the coalesced multi-target wire frame ("MTCF") with multi-word target bitmap
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                32 + (targetBitmapWords.length * 8) + encodedSlices.wireBytes().length + kinematicBytes.length + chunkBytes.length
        );
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(MULTI_TARGET_FRAME_MAGIC);
            LcaOrderlessDictTable.writeVarInt(out, targetBitmapWords.length);
            for (int w = 0; w < targetBitmapWords.length; w++) {
                out.writeLong(targetBitmapWords[w]);
            }
            LcaOrderlessDictTable.writeVarInt(out, encodedSlices.wireBytes().length);
            out.write(encodedSlices.wireBytes());
            LcaOrderlessDictTable.writeVarInt(out, kinematicBytes.length);
            out.write(kinematicBytes);
            LcaOrderlessDictTable.writeVarInt(out, chunkBytes.length);
            out.write(chunkBytes);
        }
        byte[] sharedFrameBytes = baos.toByteArray();

        int m = recipients.size();
        int vanillaPerPlayerL7 = encodedSlices.originalRawBytes()
                + (sharedKinematicRows.size() * 34)
                + chunkBytes.length;
        int vanillaPacketsPerPlayer = Math.max(
                1,
                estimatedVanillaPacketsPerPlayer > 0
                        ? estimatedVanillaPacketsPerPlayer
                        : (sharedRecurringSlices.size() + (sharedKinematicRows.size() * 2) + (chunkBytes.length > 0 ? 2 : 0))
        );
        long unConsolidatedVanillaPhysicalBytes = (long) m * (
                vanillaPerPlayerL7
                        + ((long) vanillaPacketsPerPlayer * LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT)
        );

        int segmentsPerSharedFrame = LcaPacketDifferentialProfiler.computeSegmentsForFlush(
                sharedFrameBytes.length,
                LcaPacketDifferentialProfiler.DEFAULT_MTU_PAYLOAD_BYTES
        );
        long singleTargetPhysicalBytes = LcaPacketDifferentialProfiler.computePhysicalWireBytes(
                sharedFrameBytes.length,
                segmentsPerSharedFrame,
                LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT
        );
        long directHostCoalescedPhysicalBytes = (long) m * singleTargetPhysicalBytes;
        long edgeRelayMulticastPhysicalBytes = singleTargetPhysicalBytes;

        return new ConsolidatedMultiTargetFrame(
                targetBitmapWords,
                m,
                sharedFrameBytes,
                encodedSlices.inlineLearnEntryCount(),
                encodedSlices.slotReferenceHitCount(),
                sharedKinematicRows.size(),
                unConsolidatedVanillaPhysicalBytes,
                directHostCoalescedPhysicalBytes,
                edgeRelayMulticastPhysicalBytes
        );
    }

    /**
     * Extracts only the multi-word target player bitmap (`long[]`) from the header of an `MTCF`
     * frame in $O(\text{words})$ without decoding inner dictionary or chunk payloads.
     */
    public static long[] peekTargetBitmapWords(byte[] wireFrameBytes) throws IOException {
        Objects.requireNonNull(wireFrameBytes, "wireFrameBytes");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wireFrameBytes))) {
            int magic = in.readInt();
            if (magic != MULTI_TARGET_FRAME_MAGIC) {
                throw new IOException("Invalid MultiTargetFrame magic: 0x" + Integer.toHexString(magic));
            }
            int wordCount = LcaOrderlessDictTable.readVarInt(in);
            if (wordCount < 1 || wordCount > MAX_TARGET_BITMAP_WORDS) {
                throw new IOException("Invalid MultiTargetFrame bitmap wordCount: " + wordCount);
            }
            long[] words = new long[wordCount];
            for (int w = 0; w < wordCount; w++) {
                words[w] = in.readLong();
            }
            return words;
        }
    }

    public static boolean isSlotTargetedByBitmap(long[] targetBitmapWords, int playerSlotIndex) {
        if (targetBitmapWords == null || playerSlotIndex < 0) {
            return false;
        }
        int wordIdx = playerSlotIndex >>> 6;
        if (wordIdx >= targetBitmapWords.length) {
            return false;
        }
        return (targetBitmapWords[wordIdx] & (1L << (playerSlotIndex & 63))) != 0L;
    }

    /**
     * Decodes a {@link ConsolidatedMultiTargetFrame} on a client or edge relay, updating
     * {@code clientDictTable} with any inline learned dictionary slots.
     */
    public static DecodedMultiTargetFrame decodeMultiTargetTickFrame(
            byte[] wireFrameBytes,
            LcaOrderlessDictTable clientDictTable
    ) throws IOException {
        Objects.requireNonNull(wireFrameBytes, "wireFrameBytes");
        Objects.requireNonNull(clientDictTable, "clientDictTable");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wireFrameBytes))) {
            int magic = in.readInt();
            if (magic != MULTI_TARGET_FRAME_MAGIC) {
                throw new IOException("Invalid MultiTargetFrame magic: 0x" + Integer.toHexString(magic));
            }
            int wordCount = LcaOrderlessDictTable.readVarInt(in);
            if (wordCount < 1 || wordCount > MAX_TARGET_BITMAP_WORDS) {
                throw new IOException("Invalid MultiTargetFrame bitmap wordCount: " + wordCount);
            }
            long[] targetBitmapWords = new long[wordCount];
            for (int w = 0; w < wordCount; w++) {
                targetBitmapWords[w] = in.readLong();
            }
            int dictBatchLen = LcaOrderlessDictTable.readVarInt(in);
            if (dictBatchLen < 0 || dictBatchLen > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                throw new IOException("Invalid dictBatchLen: " + dictBatchLen);
            }
            byte[] dictBatchBytes = in.readNBytes(dictBatchLen);
            if (dictBatchBytes.length != dictBatchLen) {
                throw new IOException("Truncated dictBatchBytes in MultiTargetFrame");
            }
            List<byte[]> decodedSlices = clientDictTable.decodeSlicesFromWire(dictBatchBytes);

            int kinematicLen = LcaOrderlessDictTable.readVarInt(in);
            if (kinematicLen < 0 || kinematicLen > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                throw new IOException("Invalid kinematicLen: " + kinematicLen);
            }
            byte[] kinematicBytes = in.readNBytes(kinematicLen);
            if (kinematicBytes.length != kinematicLen) {
                throw new IOException("Truncated kinematicBytes in MultiTargetFrame");
            }
            List<KinematicDeltaRow> decodedKinematics = unpackKinematicDeltaTable(kinematicBytes);

            int chunkLen = LcaOrderlessDictTable.readVarInt(in);
            if (chunkLen < 0 || chunkLen > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                throw new IOException("Invalid chunkLen: " + chunkLen);
            }
            byte[] chunkBytes = in.readNBytes(chunkLen);
            if (chunkBytes.length != chunkLen) {
                throw new IOException("Truncated chunkBytes in MultiTargetFrame");
            }
            return new DecodedMultiTargetFrame(
                    targetBitmapWords,
                    decodedSlices,
                    decodedKinematics,
                    chunkBytes
            );
        }
    }

    /**
     * Target recipient in a spatial broadcast group (`0..4095` slot index + session {@link KnownSlotBitSet}).
     */
    public record TargetRecipient(
            int playerSlotIndex,
            UUID playerId,
            KnownSlotBitSet knownSlots
    ) {
        public TargetRecipient {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(knownSlots, "knownSlots");
        }
    }

    /**
     * Quantized per-tick entity movement delta row (`4 bytes` position-only or `6 bytes` position + rotation).
     */
    public record KinematicDeltaRow(
            int localEntitySlot7,
            byte dxFixed8,
            byte dyFixed8,
            byte dzFixed8,
            boolean hasRotation,
            byte yawAngle8,
            byte pitchAngle8
    ) {
        public static KinematicDeltaRow positionOnly(
                int localEntitySlot7,
                double dxBlocks,
                double dyBlocks,
                double dzBlocks
        ) {
            return new KinematicDeltaRow(
                    localEntitySlot7 & 0x7F,
                    quantizeBlockDelta8(dxBlocks),
                    quantizeBlockDelta8(dyBlocks),
                    quantizeBlockDelta8(dzBlocks),
                    false,
                    (byte) 0,
                    (byte) 0
            );
        }

        public static KinematicDeltaRow positionAndRotation(
                int localEntitySlot7,
                double dxBlocks,
                double dyBlocks,
                double dzBlocks,
                float yawDegrees,
                float pitchDegrees
        ) {
            return new KinematicDeltaRow(
                    localEntitySlot7 & 0x7F,
                    quantizeBlockDelta8(dxBlocks),
                    quantizeBlockDelta8(dyBlocks),
                    quantizeBlockDelta8(dzBlocks),
                    true,
                    quantizeAngleDegrees8(yawDegrees),
                    quantizeAngleDegrees8(pitchDegrees)
            );
        }

        public int wireByteLength() {
            return hasRotation ? 6 : 4;
        }
    }

    /**
     * Encoded `1-to-M` multi-target coalesced tick frame and wire differential metrics (`REQ-NET-MTCF-001`).
     */
    public record ConsolidatedMultiTargetFrame(
            long[] targetBitmapWords,
            int recipientCount,
            byte[] sharedWireFrameBytes,
            int inlineDictLearnCount,
            int dictSlotReferenceHits,
            int consolidatedKinematicCount,
            long unConsolidatedVanillaPhysicalBytes,
            long directHostCoalescedPhysicalBytes,
            long edgeRelayMulticastPhysicalBytes
    ) {
        public ConsolidatedMultiTargetFrame {
            Objects.requireNonNull(targetBitmapWords, "targetBitmapWords");
            Objects.requireNonNull(sharedWireFrameBytes, "sharedWireFrameBytes");
            targetBitmapWords = targetBitmapWords.length == 0 ? new long[]{0L} : targetBitmapWords.clone();
            sharedWireFrameBytes = sharedWireFrameBytes.clone();
        }

        public ConsolidatedMultiTargetFrame(
                long targetPlayerBitmap64,
                int recipientCount,
                byte[] sharedWireFrameBytes,
                int inlineDictLearnCount,
                int dictSlotReferenceHits,
                int consolidatedKinematicCount,
                long unConsolidatedVanillaPhysicalBytes,
                long directHostCoalescedPhysicalBytes,
                long edgeRelayMulticastPhysicalBytes
        ) {
            this(
                    new long[]{targetPlayerBitmap64},
                    recipientCount,
                    sharedWireFrameBytes,
                    inlineDictLearnCount,
                    dictSlotReferenceHits,
                    consolidatedKinematicCount,
                    unConsolidatedVanillaPhysicalBytes,
                    directHostCoalescedPhysicalBytes,
                    edgeRelayMulticastPhysicalBytes
            );
        }

        @Override
        public long[] targetBitmapWords() {
            return targetBitmapWords.clone();
        }

        public long targetPlayerBitmap64() {
            return targetBitmapWords.length > 0 ? targetBitmapWords[0] : 0L;
        }

        @Override
        public byte[] sharedWireFrameBytes() {
            return sharedWireFrameBytes.clone();
        }

        public boolean targetsPlayerSlot(int playerSlotIndex) {
            return isSlotTargetedByBitmap(targetBitmapWords, playerSlotIndex);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ConsolidatedMultiTargetFrame other)) {
                return false;
            }
            return java.util.Arrays.equals(this.targetBitmapWords, other.targetBitmapWords)
                    && this.recipientCount == other.recipientCount
                    && java.util.Arrays.equals(this.sharedWireFrameBytes, other.sharedWireFrameBytes)
                    && this.inlineDictLearnCount == other.inlineDictLearnCount
                    && this.dictSlotReferenceHits == other.dictSlotReferenceHits
                    && this.consolidatedKinematicCount == other.consolidatedKinematicCount
                    && this.unConsolidatedVanillaPhysicalBytes == other.unConsolidatedVanillaPhysicalBytes
                    && this.directHostCoalescedPhysicalBytes == other.directHostCoalescedPhysicalBytes
                    && this.edgeRelayMulticastPhysicalBytes == other.edgeRelayMulticastPhysicalBytes;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.targetBitmapWords);
            result = 31 * result + this.recipientCount;
            result = 31 * result + java.util.Arrays.hashCode(this.sharedWireFrameBytes);
            result = 31 * result + this.inlineDictLearnCount;
            result = 31 * result + this.dictSlotReferenceHits;
            result = 31 * result + this.consolidatedKinematicCount;
            result = 31 * result + Long.hashCode(this.unConsolidatedVanillaPhysicalBytes);
            result = 31 * result + Long.hashCode(this.directHostCoalescedPhysicalBytes);
            result = 31 * result + Long.hashCode(this.edgeRelayMulticastPhysicalBytes);
            return result;
        }

        @Override
        public String toString() {
            return "ConsolidatedMultiTargetFrame[targetBitmapWords=" + java.util.Arrays.toString(this.targetBitmapWords)
                    + ", recipientCount=" + this.recipientCount
                    + ", sharedWireFrameBytes=" + java.util.Arrays.toString(this.sharedWireFrameBytes)
                    + ", inlineDictLearnCount=" + this.inlineDictLearnCount
                    + ", dictSlotReferenceHits=" + this.dictSlotReferenceHits
                    + ", consolidatedKinematicCount=" + this.consolidatedKinematicCount
                    + ", unConsolidatedVanillaPhysicalBytes=" + this.unConsolidatedVanillaPhysicalBytes
                    + ", directHostCoalescedPhysicalBytes=" + this.directHostCoalescedPhysicalBytes
                    + ", edgeRelayMulticastPhysicalBytes=" + this.edgeRelayMulticastPhysicalBytes
                    + "]";
        }
    }

    /**
     * Decoded payload from {@link #decodeMultiTargetTickFrame} (`REQ-NET-MTCF-001`).
     */
    public record DecodedMultiTargetFrame(
            long[] targetBitmapWords,
            List<byte[]> decodedRecurringSlices,
            List<KinematicDeltaRow> decodedKinematics,
            byte[] sharedChunkBatchBytes
    ) {
        public DecodedMultiTargetFrame {
            Objects.requireNonNull(targetBitmapWords, "targetBitmapWords");
            Objects.requireNonNull(decodedRecurringSlices, "decodedRecurringSlices");
            Objects.requireNonNull(decodedKinematics, "decodedKinematics");
            Objects.requireNonNull(sharedChunkBatchBytes, "sharedChunkBatchBytes");
            targetBitmapWords = targetBitmapWords.length == 0 ? new long[]{0L} : targetBitmapWords.clone();
            decodedRecurringSlices = List.copyOf(decodedRecurringSlices);
            decodedKinematics = List.copyOf(decodedKinematics);
            sharedChunkBatchBytes = sharedChunkBatchBytes.clone();
        }

        public DecodedMultiTargetFrame(
                long targetPlayerBitmap64,
                List<byte[]> decodedRecurringSlices,
                List<KinematicDeltaRow> decodedKinematics,
                byte[] sharedChunkBatchBytes
        ) {
            this(new long[]{targetPlayerBitmap64}, decodedRecurringSlices, decodedKinematics, sharedChunkBatchBytes);
        }

        @Override
        public long[] targetBitmapWords() {
            return targetBitmapWords.clone();
        }

        public long targetPlayerBitmap64() {
            return targetBitmapWords.length > 0 ? targetBitmapWords[0] : 0L;
        }

        public boolean targetsPlayerSlot(int playerSlotIndex) {
            return isSlotTargetedByBitmap(targetBitmapWords, playerSlotIndex);
        }

        @Override
        public byte[] sharedChunkBatchBytes() {
            return sharedChunkBatchBytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DecodedMultiTargetFrame other)) {
                return false;
            }
            return java.util.Arrays.equals(this.targetBitmapWords, other.targetBitmapWords)
                    && java.util.Objects.equals(this.decodedRecurringSlices, other.decodedRecurringSlices)
                    && java.util.Objects.equals(this.decodedKinematics, other.decodedKinematics)
                    && java.util.Arrays.equals(this.sharedChunkBatchBytes, other.sharedChunkBatchBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.targetBitmapWords);
            result = 31 * result + java.util.Objects.hashCode(this.decodedRecurringSlices);
            result = 31 * result + java.util.Objects.hashCode(this.decodedKinematics);
            result = 31 * result + java.util.Arrays.hashCode(this.sharedChunkBatchBytes);
            return result;
        }

        @Override
        public String toString() {
            return "DecodedMultiTargetFrame[targetBitmapWords=" + java.util.Arrays.toString(this.targetBitmapWords)
                    + ", decodedRecurringSlices=" + this.decodedRecurringSlices
                    + ", decodedKinematics=" + this.decodedKinematics
                    + ", sharedChunkBatchBytes=" + java.util.Arrays.toString(this.sharedChunkBatchBytes)
                    + "]";
        }
    }

    /**
     * Projects per-tick and per-second bandwidth, packet count, and server CPU encode scaling for a
     * high-density mega-event or {@code LeafShard} region containing {@code coLocatedPlayersM}
     * players and {@code movingEntitiesPerTick} moving entities, comparing:
     * <ol>
     *   <li><b>Vanilla $O(M^2)$ Unicast:</b> Per-player independent encoding and uncoalesced flushes,</li>
     *   <li><b>{@code LeafAnvil-Net} Direct Host $O(M)$ Zero-Copy Broadcast:</b> Encode once per tick
     *       with quantized `6 B` kinematic rows + orderless dictionary, fan out via zero-copy buffer
     *       slice to $M$ players,</li>
     *   <li><b>{@code LeafShard} / Edge-Relay $O(R)$ Upstream Multicast:</b> Authoritative shard
     *       transmits one `MultiTargetFrame` per regional edge proxy ($R \ll M$), offloading the
     *       last-mile $1\text{-to-}M$ fan-out to the edge proxies.</li>
     * </ol>
     */
    public static MegaEventBroadcastProjection projectMegaEventBroadcastScaling(
            int coLocatedPlayersM,
            int regionalEdgeRelaysR,
            int movingEntitiesPerTick,
            int vanillaChunkAndMetaBytesPerPlayerTick,
            int optimizedSharedChunkAndMetaBytesPerTick
    ) {
        int m = Math.max(1, coLocatedPlayersM);
        int r = Math.max(1, Math.min(m, regionalEdgeRelaysR));
        int entities = Math.max(0, movingEntitiesPerTick);
        int vChunkMeta = Math.max(0, vanillaChunkAndMetaBytesPerPlayerTick);
        int oChunkMeta = Math.max(0, optimizedSharedChunkAndMetaBytesPerTick);

        // 1. Vanilla O(M^2): each of M players receives 2 packets (~22 B) per moving entity + chunk/meta
        long vanillaCpuEncodeOpsPerTick = (long) m * (long) Math.max(1, entities);
        long vanillaPacketsPerPlayerTick = (long) entities * 2L + (vChunkMeta > 0 ? 2L : 1L);
        long vanillaTotalPacketsPerTick = (long) m * vanillaPacketsPerPlayerTick;
        long vanillaL7BytesPerPlayerTick = (long) entities * 22L + vChunkMeta;
        long vanillaSegmentsPerPlayerTick = Math.max(
                3L,
                LcaPacketDifferentialProfiler.computeSegmentsForFlush(
                        (int) Math.min(Integer.MAX_VALUE, vanillaL7BytesPerPlayerTick),
                        LcaPacketDifferentialProfiler.DEFAULT_MTU_PAYLOAD_BYTES
                ) + Math.min(8L, entities / 4L)
        );
        long vanillaPhysicalBytesPerTick = (long) m * LcaPacketDifferentialProfiler.computePhysicalWireBytes(
                vanillaL7BytesPerPlayerTick,
                vanillaSegmentsPerPlayerTick,
                LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT
        );

        // 2. LeafAnvil-Net O(M) Direct Host Broadcast: encode 1 shared frame per tick (6 B/entity + ODCT)
        long consolidatedCpuEncodeOpsPerTick = Math.max(1L, entities);
        int sharedFrameL7Bytes = 23 + (entities * 6) + oChunkMeta;
        int segmentsPerSharedFrame = Math.max(
                1,
                LcaPacketDifferentialProfiler.computeSegmentsForFlush(
                        sharedFrameL7Bytes,
                        LcaPacketDifferentialProfiler.DEFAULT_MTU_PAYLOAD_BYTES
                )
        );
        long singleTargetPhysicalBytes = LcaPacketDifferentialProfiler.computePhysicalWireBytes(
                sharedFrameL7Bytes,
                segmentsPerSharedFrame,
                LcaPacketDifferentialProfiler.DEFAULT_L3_L4_HEADER_BYTES_PER_SEGMENT
        );
        long directHostTotalPacketsPerTick = m;
        long directHostPhysicalBytesPerTick = (long) m * singleTargetPhysicalBytes;

        // 3. LeafShard Authoritative Shard -> R Regional Edge Relays O(R)
        long leafShardUpstreamPacketsPerTick = r;
        long leafShardUpstreamPhysicalBytesPerTick = (long) r * singleTargetPhysicalBytes;

        double bitsPerTickToMbps = (8.0 * LcaPacketDifferentialProfiler.TICKS_PER_SECOND) / 1_000_000.0;
        double vanillaMbps = vanillaPhysicalBytesPerTick * bitsPerTickToMbps;
        double directHostMbps = directHostPhysicalBytesPerTick * bitsPerTickToMbps;
        double leafShardUpstreamMbps = leafShardUpstreamPhysicalBytesPerTick * bitsPerTickToMbps;

        return new MegaEventBroadcastProjection(
                m,
                r,
                entities,
                sharedFrameL7Bytes,
                vanillaCpuEncodeOpsPerTick,
                consolidatedCpuEncodeOpsPerTick,
                vanillaTotalPacketsPerTick * LcaPacketDifferentialProfiler.TICKS_PER_SECOND,
                directHostTotalPacketsPerTick * LcaPacketDifferentialProfiler.TICKS_PER_SECOND,
                leafShardUpstreamPacketsPerTick * LcaPacketDifferentialProfiler.TICKS_PER_SECOND,
                vanillaMbps,
                directHostMbps,
                leafShardUpstreamMbps
        );
    }

    /**
     * Scaling projection for $M$ co-located players in a mega-event or {@code LeafShard} region.
     */
    public record MegaEventBroadcastProjection(
            int coLocatedPlayersM,
            int regionalEdgeRelaysR,
            int movingEntitiesPerTick,
            int sharedTickFrameL7Bytes,
            long vanillaCpuEncodeOpsPerTick,
            long consolidatedCpuEncodeOpsPerTick,
            long vanillaPacketsPerSecond,
            long directHostPacketsPerSecond,
            long leafShardUpstreamPacketsPerSecond,
            double vanillaEgressMbps,
            double directHostConsolidatedMbps,
            double leafShardAuthoritativeUpstreamMbps
    ) {
        public double directHostBandwidthReductionPct() {
            if (vanillaEgressMbps <= 0.0) {
                return 0.0;
            }
            return Math.max(0.0, (1.0 - (directHostConsolidatedMbps / vanillaEgressMbps)) * 100.0);
        }

        public double leafShardUpstreamBandwidthReductionPct() {
            if (vanillaEgressMbps <= 0.0) {
                return 0.0;
            }
            return Math.max(0.0, (1.0 - (leafShardAuthoritativeUpstreamMbps / vanillaEgressMbps)) * 100.0);
        }
    }

    /**
     * Standalone TCP `1-to-M` edge fan-out relay for {@link ConsolidatedMultiTargetFrame} (`MTCF`)
     * payloads (`REQ-NET-MTCF-001`, `leafanvil-ADR-003`).
     * <p>
     * Accepts `1` (or more) upstream `MTCF` TCP streams (`ROLE_UPSTREAM_ORIGIN = 0x02`) or direct
     * {@link #broadcastUpstreamMtcfFrame(byte[])} invocations and fans out the shared frame to all
     * connected downstream client TCP sockets (`ROLE_DOWNSTREAM_CLIENT = 0x01`) whose
     * {@code playerSlotIndex} bit is set in {@link ConsolidatedMultiTargetFrame#targetBitmapWords()}.
     */
    public static final class MultiTargetTcpFanOutRelay implements AutoCloseable {

        public static final byte ROLE_DOWNSTREAM_CLIENT = 0x01;
        public static final byte ROLE_UPSTREAM_ORIGIN = 0x02;

        private final ServerSocket serverSocket;
        private final CopyOnWriteArrayList<DownstreamClientEntry> downstreamClients =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Socket> activeUpstreamSockets =
                new CopyOnWriteArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong upstreamFramesReceived = new AtomicLong();
        private final AtomicLong upstreamBytesReceived = new AtomicLong();
        private final AtomicLong downstreamFramesDelivered = new AtomicLong();
        private final AtomicLong downstreamBytesDelivered = new AtomicLong();
        private volatile CompletableFuture<Void> acceptFuture;

        private record DownstreamClientEntry(
                int playerSlotIndex,
                Socket socket,
                OutputStream outputStream
        ) {
        }

        public MultiTargetTcpFanOutRelay(int listenPort) throws IOException {
            this("0.0.0.0", listenPort);
        }

        public MultiTargetTcpFanOutRelay(String bindHost, int listenPort) throws IOException {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bindHost, listenPort), 1024);
            this.serverSocket = ss;
        }

        public int boundPort() {
            return serverSocket.getLocalPort();
        }

        public void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            this.acceptFuture = CompletableFuture.runAsync(this::acceptLoop);
        }
        public int connectedClientCount() {
            return downstreamClients.size();
        }

        public long upstreamFramesReceived() {
            return upstreamFramesReceived.get();
        }

        public long upstreamBytesReceived() {
            return upstreamBytesReceived.get();
        }

        public long downstreamFramesDelivered() {
            return downstreamFramesDelivered.get();
        }

        public long downstreamBytesDelivered() {
            return downstreamBytesDelivered.get();
        }

        /**
         * Broadcasts a single upstream `MTCF` frame to all connected downstream client TCP sockets
         * targeted by the frame's multi-word player bitmap (`REQ-NET-MTCF-001`).
         */
        public void broadcastUpstreamMtcfFrame(byte[] mtcfWireBytes) throws IOException {
            Objects.requireNonNull(mtcfWireBytes, "mtcfWireBytes");
            long[] bitmapWords = peekTargetBitmapWords(mtcfWireBytes);
            upstreamFramesReceived.incrementAndGet();
            upstreamBytesReceived.addAndGet(mtcfWireBytes.length);

            byte[] lengthPrefixedFrame = new byte[4 + mtcfWireBytes.length];
            ByteBuffer.wrap(lengthPrefixedFrame).putInt(mtcfWireBytes.length);
            System.arraycopy(mtcfWireBytes, 0, lengthPrefixedFrame, 4, mtcfWireBytes.length);

            for (DownstreamClientEntry client : downstreamClients) {
                if (!isSlotTargetedByBitmap(bitmapWords, client.playerSlotIndex())) {
                    continue;
                }
                Socket sock = client.socket();
                if (sock.isClosed()) {
                    downstreamClients.remove(client);
                    continue;
                }
                try {
                    OutputStream out = client.outputStream();
                    synchronized (out) {
                        out.write(lengthPrefixedFrame);
                        out.flush();
                    }
                    downstreamFramesDelivered.incrementAndGet();
                    downstreamBytesDelivered.addAndGet(mtcfWireBytes.length);
                } catch (IOException ex) {
                    downstreamClients.remove(client);
                    closeQuietly(sock);
                }
            }
        }

        private void acceptLoop() {
            while (!closed.get() && !serverSocket.isClosed()) {
                try {
                    Socket sock = serverSocket.accept();
                    sock.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(sock.getInputStream());
                    OutputStream out = sock.getOutputStream();
                    int role = in.readUnsignedByte();
                    int slotOrFlag = in.readInt();
                    if (role == (ROLE_DOWNSTREAM_CLIENT & 0xFF)) {
                        out.write(ROLE_DOWNSTREAM_CLIENT);
                        out.flush();
                        downstreamClients.add(new DownstreamClientEntry(slotOrFlag, sock, out));
                    } else if (role == (ROLE_UPSTREAM_ORIGIN & 0xFF)) {
                        out.write(ROLE_UPSTREAM_ORIGIN);
                        out.flush();
                        activeUpstreamSockets.add(sock);
                        CompletableFuture.runAsync(() -> upstreamReadLoop(sock, in));
                    } else {
                        closeQuietly(sock);
                    }
                } catch (IOException ex) {
                    if (closed.get()) {
                        break;
                    }
                }
            }
        }

        private void upstreamReadLoop(Socket upstreamSocket, DataInputStream in) {
            try {
                while (!closed.get() && !upstreamSocket.isClosed()) {
                    int frameLen;
                    try {
                        frameLen = in.readInt();
                    } catch (EOFException eof) {
                        break;
                    }
                    if (frameLen <= 0 || frameLen > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                        break;
                    }
                    byte[] mtcfBytes = in.readNBytes(frameLen);
                    if (mtcfBytes.length != frameLen) {
                        break;
                    }
                    broadcastUpstreamMtcfFrame(mtcfBytes);
                }
            } catch (IOException ignored) {
                // Upstream closed or reset
            } finally {
                activeUpstreamSockets.remove(upstreamSocket);
                closeQuietly(upstreamSocket);
            }
        }

        public static Socket connectRegisteredClientSocket(
                String host,
                int port,
                int playerSlotIndex
        ) throws IOException {
            Socket sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, port), 10_000);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());
            out.writeByte(ROLE_DOWNSTREAM_CLIENT);
            out.writeInt(playerSlotIndex);
            out.flush();
            int ack = in.readUnsignedByte();
            if (ack != (ROLE_DOWNSTREAM_CLIENT & 0xFF)) {
                sock.close();
                throw new IOException("Unexpected downstream relay registration ACK: " + ack);
            }
            return sock;
        }

        public static Socket connectUpstreamOriginSocket(String host, int port) throws IOException {
            Socket sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, port), 10_000);
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());
            out.writeByte(ROLE_UPSTREAM_ORIGIN);
            out.writeInt(0);
            out.flush();
            int ack = in.readUnsignedByte();
            if (ack != (ROLE_UPSTREAM_ORIGIN & 0xFF)) {
                sock.close();
                throw new IOException("Unexpected upstream relay registration ACK: " + ack);
            }
            return sock;
        }

        public static void writeUpstreamMtcfFrame(OutputStream out, byte[] mtcfWireBytes) throws IOException {
            Objects.requireNonNull(out, "out");
            Objects.requireNonNull(mtcfWireBytes, "mtcfWireBytes");
            byte[] header = new byte[4];
            ByteBuffer.wrap(header).putInt(mtcfWireBytes.length);
            out.write(header);
            out.write(mtcfWireBytes);
            out.flush();
        }

        public static byte[] readNextDownstreamFrame(DataInputStream in) throws IOException {
            int len = in.readInt();
            if (len < 0 || len > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                throw new IOException("Invalid downstream MTCF frame length: " + len);
            }
            byte[] payload = in.readNBytes(len);
            if (payload.length != len) {
                throw new EOFException("Truncated downstream MTCF frame: expected " + len + ", got " + payload.length);
            }
            return payload;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(serverSocket);
            for (DownstreamClientEntry client : downstreamClients) {
                closeQuietly(client.socket());
            }
            downstreamClients.clear();
            for (Socket up : activeUpstreamSockets) {
                closeQuietly(up);
            }
            activeUpstreamSockets.clear();
        }

        private static void closeQuietly(AutoCloseable c) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
