package net.leaf.anvil.common.net;

import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Shared, lock-free, atomically-updated orderless dictionary table (`4,096` slots, 2-way
 * content-addressed associative buckets, `slotId12` + `tag8`/`tag4` verification hash) and
 * `512-byte` (`long[64]`) per-session known-slot bitset for {@code LeafAnvil} and
 * {@code LeafAnvil-Net} (`leafanvil-ADR-003`, `leafanvil-ADR-004`).
 * <p>
 * Unlike sequential sliding-window or epoch-Incrementing dictionaries, slot indices are derived
 * deterministically from content hash (`slotA`, `slotB`) and promoted via CAS without shifting
 * existing slots. Concurrent region workers can learn and reference recurring chunk section motifs,
 * entity metadata archetypes, and registry/component strings without head-of-line epoch ordering.
 * <p>
 * Written with fixed-size primitive arrays and flat records for 1:1 Rust (`[u64; 64]`, `u16`) and
 * cross-language auto-translation.
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
public final class LcaOrderlessDictTable {

    public static final int TABLE_SLOT_COUNT = 4096;
    public static final int SLOT_INDEX_MASK = TABLE_SLOT_COUNT - 1;
    public static final int BITSET_LONG_WORDS = TABLE_SLOT_COUNT >>> 6; // 64 longs = 512 bytes
    public static final int DEFAULT_PROMOTION_THRESHOLD = 2;
    public static final int MIN_PATTERN_BYTES = 4;
    public static final int MAX_PATTERN_BYTES = 4096;

    public static final int ORDERLESS_FRAME_MAGIC = 0x4F444354; // "ODCT"

    public static final byte KIND_SECTION_SLICE_OR_PALETTE = 0;
    public static final byte KIND_ENTITY_METADATA_ARCHETYPE = 1;
    public static final byte KIND_REGISTRY_OR_COMPONENT_UTF8 = 2;
    public static final byte KIND_KINEMATIC_TEMPLATE = 3;

    public static final int TOKEN_RAW_LITERAL = 0;
    public static final int TOKEN_SLOT_REFERENCE = 1;

    private final int promotionThreshold;
    private final AtomicReferenceArray<DictSlotEntry> slots = new AtomicReferenceArray<>(TABLE_SLOT_COUNT);
    private final AtomicIntegerArray candidateObservationCounts = new AtomicIntegerArray(TABLE_SLOT_COUNT);
    private final AtomicInteger populatedSlotCount = new AtomicInteger();

    public LcaOrderlessDictTable() {
        this(DEFAULT_PROMOTION_THRESHOLD);
    }

    public LcaOrderlessDictTable(int promotionThreshold) {
        if (promotionThreshold < 1) {
            throw new IllegalArgumentException("promotionThreshold must be >= 1: " + promotionThreshold);
        }
        this.promotionThreshold = promotionThreshold;
    }

    public int promotionThreshold() {
        return promotionThreshold;
    }

    public int populatedSlotCount() {
        return populatedSlotCount.get();
    }

    /**
     * Deterministic 32-bit FNV-1a + Murmur3 finalizer hash over `(kind, patternBytes)`.
     */
    public static int computeContentHash32(byte kind, byte[] patternBytes) {
        Objects.requireNonNull(patternBytes, "patternBytes");
        int h = 0x811C9DC5;
        h ^= (kind & 0xFF);
        h *= 0x01000193;
        for (int i = 0; i < patternBytes.length; i++) {
            h ^= (patternBytes[i] & 0xFF);
            h *= 0x01000193;
        }
        h ^= (h >>> 16);
        h *= 0x85EBCA6B;
        h ^= (h >>> 13);
        h *= 0xC2B2AE35;
        h ^= (h >>> 16);
        return h;
    }

    /**
     * Primary associative bucket index (`0..4095`).
     */
    public static int primarySlotIndex12(int hash32) {
        return hash32 & SLOT_INDEX_MASK;
    }

    /**
     * Secondary associative bucket index (`0..4095`), guaranteed distinct from {@link #primarySlotIndex12(int)}.
     */
    public static int secondarySlotIndex12(int hash32) {
        int primary = hash32 & SLOT_INDEX_MASK;
        int step = ((hash32 >>> 12) & SLOT_INDEX_MASK) | 1;
        return (primary ^ step) & SLOT_INDEX_MASK;
    }

    /**
     * Non-zero 8-bit content verification tag extracted from upper hash bits.
     */
    public static byte computeTag8(int hash32) {
        int raw = (hash32 >>> 24) & 0xFF;
        return (byte) (raw == 0 ? 0xA5 : raw);
    }

    /**
     * Non-zero 4-bit inline wire reference tag extracted from {@code tag8}.
     */
    public static int computeTag4(byte tag8) {
        int nib = (tag8 & 0x0F);
        return nib == 0 ? 0x05 : nib;
    }

    /**
     * Packs `(slotId12, tag8)` into a 16-bit wire reference word (`(slotId12 << 4) | tag4`).
     */
    public static int packRefWord16(int slotId12, byte tag8) {
        if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
            throw new IllegalArgumentException("slotId12 out of range 0..4095: " + slotId12);
        }
        return ((slotId12 & SLOT_INDEX_MASK) << 4) | (computeTag4(tag8) & 0x0F);
    }

    public static int unpackSlotId12(int refWord16) {
        return (refWord16 >>> 4) & SLOT_INDEX_MASK;
    }

    public static int unpackTag4(int refWord16) {
        return refWord16 & 0x0F;
    }

    /**
     * Observes a candidate byte pattern in lock-free orderless fashion.
     * Returns the active {@link DictSlotEntry} if the pattern is already resident or crosses the
     * promotion threshold on this observation; returns {@code null} if the pattern is below the
     * promotion threshold or both 2-way associative slots are already occupied by other pinned patterns.
     */
    public DictSlotEntry observeOrLookup(byte kind, byte[] patternBytes) {
        Objects.requireNonNull(patternBytes, "patternBytes");
        if (patternBytes.length < MIN_PATTERN_BYTES || patternBytes.length > MAX_PATTERN_BYTES) {
            return null;
        }
        int hash32 = computeContentHash32(kind, patternBytes);
        byte tag8 = computeTag8(hash32);
        int slotA = primarySlotIndex12(hash32);
        int slotB = secondarySlotIndex12(hash32);

        DictSlotEntry existingA = slots.get(slotA);
        if (existingA != null && existingA.matches(tag8, kind, patternBytes)) {
            return existingA;
        }
        DictSlotEntry existingB = slots.get(slotB);
        if (existingB != null && existingB.matches(tag8, kind, patternBytes)) {
            return existingB;
        }

        int count = candidateObservationCounts.incrementAndGet(slotA);
        if (count < promotionThreshold) {
            return null;
        }

        return tryInsertIntoAssociativeSlots(slotA, slotB, tag8, kind, patternBytes);
    }

    /**
     * Immediately interns {@code patternBytes} (bypassing the multi-observation threshold) if
     * either associative bucket slot is free or already matches.
     */
    public DictSlotEntry putOrGetImmediate(byte kind, byte[] patternBytes) {
        Objects.requireNonNull(patternBytes, "patternBytes");
        if (patternBytes.length < MIN_PATTERN_BYTES || patternBytes.length > MAX_PATTERN_BYTES) {
            return null;
        }
        int hash32 = computeContentHash32(kind, patternBytes);
        byte tag8 = computeTag8(hash32);
        int slotA = primarySlotIndex12(hash32);
        int slotB = secondarySlotIndex12(hash32);
        return tryInsertIntoAssociativeSlots(slotA, slotB, tag8, kind, patternBytes);
    }

    public DictSlotEntry putOrGetUtf8Symbol(String symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return putOrGetImmediate(
                KIND_REGISTRY_OR_COMPONENT_UTF8,
                symbol.getBytes(StandardCharsets.UTF_8)
        );
    }

    public DictSlotEntry getSlot(int slotId12) {
        if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
            return null;
        }
        return slots.get(slotId12);
    }

    /**
     * Apply a learned {@link DictLearnEntry} received over the wire on a client or peer node.
     */
    public DictSlotEntry applyLearnedEntry(DictLearnEntry learnEntry) throws IOException {
        Objects.requireNonNull(learnEntry, "learnEntry");
        int slotId = learnEntry.slotId12();
        if (slotId < 0 || slotId >= TABLE_SLOT_COUNT) {
            throw new IOException("Invalid orderless dictionary slotId12: " + slotId);
        }
        int expectedHash = computeContentHash32(learnEntry.kind(), learnEntry.patternBytes());
        byte expectedTag8 = computeTag8(expectedHash);
        if (expectedTag8 != learnEntry.tag8()) {
            throw new IOException(
                    "Orderless dictionary learn entry tag8 mismatch at slot " + slotId
                            + ": expected 0x" + Integer.toHexString(expectedTag8 & 0xFF)
                            + " but got 0x" + Integer.toHexString(learnEntry.tag8() & 0xFF)
            );
        }
        DictSlotEntry incoming = new DictSlotEntry(
                slotId,
                learnEntry.tag8(),
                learnEntry.kind(),
                learnEntry.patternBytes()
        );
        while (true) {
            DictSlotEntry current = slots.get(slotId);
            if (current != null) {
                if (current.matches(incoming.tag8(), incoming.kind(), incoming.patternBytes())) {
                    return current;
                }
                slots.set(slotId, incoming);
                return incoming;
            }
            if (slots.compareAndSet(slotId, null, incoming)) {
                populatedSlotCount.incrementAndGet();
                return incoming;
            }
        }
    }

    /**
     * Resolves a 16-bit wire slot reference (`(slotId12 << 4) | tag4`), failing closed with an
     * {@link IOException} if the slot is unpopulated or its 4-bit verification tag mismatches
     * (`REQ-RTP-S-004`).
     */
    public DictSlotEntry resolveVerifiedReference(int refWord16) throws IOException {
        int slotId12 = unpackSlotId12(refWord16);
        int wireTag4 = unpackTag4(refWord16);
        DictSlotEntry entry = getSlot(slotId12);
        if (entry == null) {
            throw new IOException("Orderless dictionary slot miss at slotId12=" + slotId12);
        }
        int expectedTag4 = computeTag4(entry.tag8());
        if (wireTag4 != expectedTag4) {
            throw new IOException(
                    "Orderless dictionary tag4 mismatch at slotId12=" + slotId12
                            + ": wire=0x" + Integer.toHexString(wireTag4)
                            + ", expected=0x" + Integer.toHexString(expectedTag4)
            );
        }
        return entry;
    }

    /**
     * Encodes a batch of recurring byte slices (such as chunk section base frames, entity metadata
     * packets, or component strings) against a target session's {@link KnownSlotBitSet}.
     * <p>
     * Slices that match a promoted slot in this table are emitted as a `2-byte` `refWord16`; if the
     * target session has not yet learned that slot, a {@link DictLearnEntry} is prepended once in the
     * frame header and marked in {@code targetKnownSlots}.
     */
    public OrderlessEncodedBatch encodeSlicesForTarget(
            byte kind,
            List<byte[]> rawSlices,
            KnownSlotBitSet targetKnownSlots
    ) throws IOException {
        Objects.requireNonNull(rawSlices, "rawSlices");
        Objects.requireNonNull(targetKnownSlots, "targetKnownSlots");

        List<DictLearnEntry> learnEntries = new ArrayList<>();
        KnownSlotBitSet newlyLearnedMask = new KnownSlotBitSet();
        int[] sliceRefWords = new int[rawSlices.size()];
        int rawTotalBytes = 0;
        int referenceHitCount = 0;

        for (int i = 0; i < rawSlices.size(); i++) {
            byte[] slice = Objects.requireNonNull(rawSlices.get(i), "rawSlices[i]");
            rawTotalBytes += slice.length;
            DictSlotEntry slot = observeOrLookup(kind, slice);
            if (slot != null) {
                sliceRefWords[i] = slot.refWord16();
                referenceHitCount++;
                if (!targetKnownSlots.isKnown(slot.slotId12())
                        && !newlyLearnedMask.isKnown(slot.slotId12())) {
                    learnEntries.add(new DictLearnEntry(
                            slot.slotId12(),
                            slot.tag8(),
                            slot.kind(),
                            slot.patternBytes()
                    ));
                    newlyLearnedMask.markKnown(slot.slotId12());
                }
            } else {
                sliceRefWords[i] = -1;
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, rawTotalBytes / 2));
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(ORDERLESS_FRAME_MAGIC);
            writeVarInt(out, learnEntries.size());
            for (int i = 0; i < learnEntries.size(); i++) {
                DictLearnEntry learn = learnEntries.get(i);
                out.writeShort(packRefWord16(learn.slotId12(), learn.tag8()));
                out.writeByte(learn.kind());
                out.writeByte(learn.tag8());
                writeVarInt(out, learn.patternBytes().length);
                out.write(learn.patternBytes());
            }

            writeVarInt(out, rawSlices.size());
            for (int i = 0; i < rawSlices.size(); i++) {
                int refWord = sliceRefWords[i];
                if (refWord >= 0) {
                    out.writeByte(TOKEN_SLOT_REFERENCE);
                    out.writeShort(refWord);
                } else {
                    byte[] raw = rawSlices.get(i);
                    out.writeByte(TOKEN_RAW_LITERAL);
                    writeVarInt(out, raw.length);
                    out.write(raw);
                }
            }
        }

        targetKnownSlots.unionFrom(newlyLearnedMask);
        byte[] wireBytes = baos.toByteArray();
        return new OrderlessEncodedBatch(
                wireBytes,
                rawTotalBytes,
                learnEntries.size(),
                referenceHitCount,
                rawSlices.size() - referenceHitCount,
                newlyLearnedMask
        );
    }

    /**
     * Decodes a wire frame produced by {@link #encodeSlicesForTarget}, applying any inline
     * {@link DictLearnEntry} records into this dictionary table before expanding all `2-byte`
     * slot references and literal slices.
     */
    public List<byte[]> decodeSlicesFromWire(byte[] wireBytes) throws IOException {
        Objects.requireNonNull(wireBytes, "wireBytes");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wireBytes))) {
            int magic = in.readInt();
            if (magic != ORDERLESS_FRAME_MAGIC) {
                throw new IOException("Invalid orderless dictionary frame magic: 0x" + Integer.toHexString(magic));
            }
            readInlineLearnEntries(in);

            int sliceCount = readVarInt(in);
            if (sliceCount < 0 || sliceCount > 65536) {
                throw new IOException("Invalid orderless batch sliceCount: " + sliceCount);
            }
            List<byte[]> decoded = new ArrayList<>(sliceCount);
            for (int i = 0; i < sliceCount; i++) {
                decoded.add(readSingleDecodedSlice(in, i));
            }
            return decoded;
        }
    }

    private void readInlineLearnEntries(DataInputStream in) throws IOException {
        int learnCount = readVarInt(in);
        if (learnCount < 0 || learnCount > TABLE_SLOT_COUNT) {
            throw new IOException("Invalid orderless dictionary learnCount: " + learnCount);
        }
        for (int i = 0; i < learnCount; i++) {
            int refWord16 = in.readUnsignedShort();
            int slotId12 = unpackSlotId12(refWord16);
            byte kind = in.readByte();
            byte tag8 = in.readByte();
            if (unpackTag4(refWord16) != computeTag4(tag8)) {
                throw new IOException("Corrupted inline DictLearnEntry refWord16/tag8 at slot " + slotId12);
            }
            int len = readVarInt(in);
            if (len < MIN_PATTERN_BYTES || len > MAX_PATTERN_BYTES) {
                throw new IOException("Invalid DictLearnEntry pattern length: " + len);
            }
            byte[] pattern = in.readNBytes(len);
            if (pattern.length != len) {
                throw new IOException("Truncated DictLearnEntry pattern at slot " + slotId12);
            }
            applyLearnedEntry(new DictLearnEntry(slotId12, tag8, kind, pattern));
        }
    }

    private byte[] readSingleDecodedSlice(DataInputStream in, int sliceIndex) throws IOException {
        int token = in.readUnsignedByte();
        if (token == TOKEN_SLOT_REFERENCE) {
            int refWord16 = in.readUnsignedShort();
            DictSlotEntry resolved = resolveVerifiedReference(refWord16);
            return resolved.patternBytes();
        }
        if (token == TOKEN_RAW_LITERAL) {
            int len = readVarInt(in);
            if (len < 0 || len > LcaWireStreamProtocol.MAX_DECOMPRESSED_BATCH_BYTES) {
                throw new IOException("Invalid raw literal length: " + len);
            }
            byte[] raw = in.readNBytes(len);
            if (raw.length != len) {
                throw new IOException("Truncated raw literal at index " + sliceIndex);
            }
            return raw;
        }
        throw new IOException("Unknown orderless slice token: " + token);
    }

    /**
     * Compresses a list of chunk {@link SectionWireSlice}s by interning recurring non-uniform
     * section `baseFrameBytes` into this orderless dictionary table.
     */
    public byte[] encodeSectionWireSlicesWithDict(
            List<SectionWireSlice> sections,
            KnownSlotBitSet targetKnownSlots
    ) throws IOException {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(targetKnownSlots, "targetKnownSlots");

        List<byte[]> nonUniformFrames = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            SectionWireSlice s = sections.get(i);
            if (!LcaWireStreamProtocol.isUniformDirectoryWord(s.directoryWord16())) {
                nonUniformFrames.add(s.baseFrameSlice());
            }
        }
        OrderlessEncodedBatch encodedNonUniform = encodeSlicesForTarget(
                KIND_SECTION_SLICE_OR_PALETTE,
                nonUniformFrames,
                targetKnownSlots
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream(128 + encodedNonUniform.wireBytes().length);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            writeVarInt(out, sections.size());
            int overlayMask = 0;
            for (int i = 0; i < sections.size(); i++) {
                SectionWireSlice s = sections.get(i);
                out.writeByte(s.sectionIndex());
                out.writeShort(s.directoryWord16());
                if (s.dirtyOverlayEntries().length > 0) {
                    overlayMask |= (1 << i);
                }
            }
            out.writeInt(overlayMask);
            for (int i = 0; i < sections.size(); i++) {
                if ((overlayMask & (1 << i)) != 0) {
                    int[] overlay = sections.get(i).dirtyOverlayEntries();
                    writeVarInt(out, overlay.length);
                    for (int j = 0; j < overlay.length; j++) {
                        out.writeInt(overlay[j]);
                    }
                }
            }
            writeVarInt(out, encodedNonUniform.wireBytes().length);
            out.write(encodedNonUniform.wireBytes());
        }
        return baos.toByteArray();
    }

    /**
     * Reconstructs {@link SectionWireSlice}s encoded via {@link #encodeSectionWireSlicesWithDict}.
     */
    public List<SectionWireSlice> decodeSectionWireSlicesWithDict(byte[] encodedBytes) throws IOException {
        Objects.requireNonNull(encodedBytes, "encodedBytes");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encodedBytes))) {
            int count = readVarInt(in);
            if (count < 0 || count > 32) {
                throw new IOException("Invalid section slice count: " + count);
            }
            int[] sectionIndices = new int[count];
            short[] dirWords = new short[count];
            for (int i = 0; i < count; i++) {
                sectionIndices[i] = in.readUnsignedByte();
                dirWords[i] = in.readShort();
            }
            int overlayMask = in.readInt();
            int[][] overlays = new int[count][];
            for (int i = 0; i < count; i++) {
                if ((overlayMask & (1 << i)) != 0) {
                    int overlayLen = readVarInt(in);
                    if (overlayLen < 0 || overlayLen > 4096) {
                        throw new IOException("Invalid section overlay length: " + overlayLen);
                    }
                    int[] ov = new int[overlayLen];
                    for (int j = 0; j < overlayLen; j++) {
                        ov[j] = in.readInt();
                    }
                    overlays[i] = ov;
                } else {
                    overlays[i] = new int[0];
                }
            }
            int batchLen = readVarInt(in);
            byte[] batchBytes = in.readNBytes(batchLen);
            if (batchBytes.length != batchLen) {
                throw new IOException("Truncated orderless section payload");
            }
            List<byte[]> nonUniformFrames = decodeSlicesFromWire(batchBytes);
            int nonUniformCursor = 0;
            List<SectionWireSlice> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (LcaWireStreamProtocol.isUniformDirectoryWord(dirWords[i])) {
                    result.add(new SectionWireSlice(sectionIndices[i], dirWords[i], new byte[0], overlays[i]));
                } else {
                    if (nonUniformCursor >= nonUniformFrames.size()) {
                        throw new IOException("Missing non-uniform section frame at index " + i);
                    }
                    byte[] baseFrame = nonUniformFrames.get(nonUniformCursor++);
                    result.add(new SectionWireSlice(sectionIndices[i], dirWords[i], baseFrame, overlays[i]));
                }
            }
            return result;
        }
    }

    private DictSlotEntry tryInsertIntoAssociativeSlots(
            int slotA,
            int slotB,
            byte tag8,
            byte kind,
            byte[] patternBytes
    ) {
        while (true) {
            DictSlotEntry curA = slots.get(slotA);
            if (curA != null) {
                if (curA.matches(tag8, kind, patternBytes)) {
                    return curA;
                }
                break;
            }
            DictSlotEntry candidateA = new DictSlotEntry(slotA, tag8, kind, patternBytes);
            if (slots.compareAndSet(slotA, null, candidateA)) {
                populatedSlotCount.incrementAndGet();
                return candidateA;
            }
        }

        while (true) {
            DictSlotEntry curB = slots.get(slotB);
            if (curB != null) {
                if (curB.matches(tag8, kind, patternBytes)) {
                    return curB;
                }
                return null;
            }
            DictSlotEntry candidateB = new DictSlotEntry(slotB, tag8, kind, patternBytes);
            if (slots.compareAndSet(slotB, null, candidateB)) {
                populatedSlotCount.incrementAndGet();
                return candidateB;
            }
        }
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IOException("VarInt too wide");
            }
        }
    }

    /**
     * Immutable content-addressed slot entry in {@link LcaOrderlessDictTable}.
     */
    public record DictSlotEntry(
            int slotId12,
            byte tag8,
            byte kind,
            byte[] patternBytes
    ) {
        public DictSlotEntry {
            Objects.requireNonNull(patternBytes, "patternBytes");
            patternBytes = patternBytes.clone();
        }

        public int refWord16() {
            return packRefWord16(slotId12, tag8);
        }

        public int byteLength() {
            return patternBytes.length;
        }

        public boolean matches(byte candidateTag8, byte candidateKind, byte[] candidateBytes) {
            return this.tag8 == candidateTag8
                    && this.kind == candidateKind
                    && Arrays.equals(this.patternBytes, candidateBytes);
        }

        @Override
        public byte[] patternBytes() {
            return patternBytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DictSlotEntry other)) {
                return false;
            }
            return this.slotId12 == other.slotId12
                    && this.tag8 == other.tag8
                    && this.kind == other.kind
                    && java.util.Arrays.equals(this.patternBytes, other.patternBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.slotId12;
            result = 31 * result + this.tag8;
            result = 31 * result + this.kind;
            result = 31 * result + java.util.Arrays.hashCode(this.patternBytes);
            return result;
        }

        @Override
        public String toString() {
            return "DictSlotEntry[slotId12=" + this.slotId12
                    + ", tag8=" + this.tag8
                    + ", kind=" + this.kind
                    + ", patternBytes=" + java.util.Arrays.toString(this.patternBytes)
                    + "]";
        }
    }

    /**
     * Inline wire dictionary learning record prepended when a target client's {@link KnownSlotBitSet}
     * does not yet contain {@code slotId12}.
     */
    public record DictLearnEntry(
            int slotId12,
            byte tag8,
            byte kind,
            byte[] patternBytes
    ) {
        public DictLearnEntry {
            Objects.requireNonNull(patternBytes, "patternBytes");
            patternBytes = patternBytes.clone();
        }

        @Override
        public byte[] patternBytes() {
            return patternBytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DictLearnEntry other)) {
                return false;
            }
            return this.slotId12 == other.slotId12
                    && this.tag8 == other.tag8
                    && this.kind == other.kind
                    && java.util.Arrays.equals(this.patternBytes, other.patternBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.slotId12;
            result = 31 * result + this.tag8;
            result = 31 * result + this.kind;
            result = 31 * result + java.util.Arrays.hashCode(this.patternBytes);
            return result;
        }

        @Override
        public String toString() {
            return "DictLearnEntry[slotId12=" + this.slotId12
                    + ", tag8=" + this.tag8
                    + ", kind=" + this.kind
                    + ", patternBytes=" + java.util.Arrays.toString(this.patternBytes)
                    + "]";
        }
    }

    /**
     * Result of encoding a slice batch against a target {@link KnownSlotBitSet}.
     */
    public record OrderlessEncodedBatch(
            byte[] wireBytes,
            int originalRawBytes,
            int inlineLearnEntryCount,
            int slotReferenceHitCount,
            int rawLiteralCount,
            KnownSlotBitSet newlyLearnedSlots
    ) {
        public OrderlessEncodedBatch {
            Objects.requireNonNull(wireBytes, "wireBytes");
            Objects.requireNonNull(newlyLearnedSlots, "newlyLearnedSlots");
            wireBytes = wireBytes.clone();
        }

        @Override
        public byte[] wireBytes() {
            return wireBytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OrderlessEncodedBatch other)) {
                return false;
            }
            return java.util.Arrays.equals(this.wireBytes, other.wireBytes)
                    && this.originalRawBytes == other.originalRawBytes
                    && this.inlineLearnEntryCount == other.inlineLearnEntryCount
                    && this.slotReferenceHitCount == other.slotReferenceHitCount
                    && this.rawLiteralCount == other.rawLiteralCount
                    && java.util.Objects.equals(this.newlyLearnedSlots, other.newlyLearnedSlots);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Arrays.hashCode(this.wireBytes);
            result = 31 * result + this.originalRawBytes;
            result = 31 * result + this.inlineLearnEntryCount;
            result = 31 * result + this.slotReferenceHitCount;
            result = 31 * result + this.rawLiteralCount;
            result = 31 * result + java.util.Objects.hashCode(this.newlyLearnedSlots);
            return result;
        }

        @Override
        public String toString() {
            return "OrderlessEncodedBatch[wireBytes=" + java.util.Arrays.toString(this.wireBytes)
                    + ", originalRawBytes=" + this.originalRawBytes
                    + ", inlineLearnEntryCount=" + this.inlineLearnEntryCount
                    + ", slotReferenceHitCount=" + this.slotReferenceHitCount
                    + ", rawLiteralCount=" + this.rawLiteralCount
                    + ", newlyLearnedSlots=" + this.newlyLearnedSlots
                    + "]";
        }
    }

    /**
     * Fixed `512-byte` (`long[64]`) per-session bitset tracking which of the `4,096` orderless
     * dictionary slots have already been synchronized to a client (`[u64; 64]` in Rust).
     */
    public static final class KnownSlotBitSet {
        private final long[] words = new long[BITSET_LONG_WORDS];

        public KnownSlotBitSet() {
        }

        public synchronized boolean isKnown(int slotId12) {
            if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
                return false;
            }
            int wordIdx = slotId12 >>> 6;
            long mask = 1L << (slotId12 & 63);
            return (words[wordIdx] & mask) != 0L;
        }

        public synchronized void markKnown(int slotId12) {
            if (slotId12 < 0 || slotId12 >= TABLE_SLOT_COUNT) {
                throw new IllegalArgumentException("slotId12 out of range 0..4095: " + slotId12);
            }
            int wordIdx = slotId12 >>> 6;
            words[wordIdx] |= (1L << (slotId12 & 63));
        }

        public synchronized void clear() {
            Arrays.fill(words, 0L);
        }

        public synchronized int knownSlotCount() {
            int total = 0;
            for (int i = 0; i < BITSET_LONG_WORDS; i++) {
                total += Long.bitCount(words[i]);
            }
            return total;
        }

        public synchronized KnownSlotBitSet copy() {
            KnownSlotBitSet clone = new KnownSlotBitSet();
            System.arraycopy(this.words, 0, clone.words, 0, BITSET_LONG_WORDS);
            return clone;
        }

        public synchronized void intersectWith(KnownSlotBitSet other) {
            Objects.requireNonNull(other, "other");
            long[] otherWords = other.snapshotWords();
            for (int i = 0; i < BITSET_LONG_WORDS; i++) {
                this.words[i] &= otherWords[i];
            }
        }

        public synchronized void unionFrom(KnownSlotBitSet other) {
            Objects.requireNonNull(other, "other");
            long[] otherWords = other.snapshotWords();
            for (int i = 0; i < BITSET_LONG_WORDS; i++) {
                this.words[i] |= otherWords[i];
            }
        }

        public synchronized long[] snapshotWords() {
            return words.clone();
        }

        /**
         * Computes the common-denominator intersection (`bitwise AND`) across multiple recipient
         * bitsets in $O(M \times 64)$ word operations.
         */
        public static KnownSlotBitSet intersectAll(List<KnownSlotBitSet> recipientBitSets) {
            Objects.requireNonNull(recipientBitSets, "recipientBitSets");
            KnownSlotBitSet intersection = new KnownSlotBitSet();
            if (recipientBitSets.isEmpty()) {
                return intersection;
            }
            long[] first = recipientBitSets.get(0).snapshotWords();
            System.arraycopy(first, 0, intersection.words, 0, BITSET_LONG_WORDS);
            for (int r = 1; r < recipientBitSets.size(); r++) {
                long[] next = recipientBitSets.get(r).snapshotWords();
                for (int w = 0; w < BITSET_LONG_WORDS; w++) {
                    intersection.words[w] &= next[w];
                }
            }
            return intersection;
        }
    }
}
