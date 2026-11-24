package net.leaf.anvil.common.net;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Negotiated server-to-client `.lca` chunk wire streaming, pure-Java `LCD2` (`0x4C44`) batch
 * framing, Netty `CompressionEncoder` `VarInt = 0` and proxy-safe `Deflater.NO_COMPRESSION`
 * stored-block frame bypass, strict Netty arrival-order client main-thread commit queue, and
 * bounded client `.lca` LRU revisit delta synchronization protocol (`leafanvil-ADR-003`,
 * `leafanvil-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-002, anvil-api-ADR-003, ADR-115
 * @governedBy leafanvil-ADR-003
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-005
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaWireStreamProtocol {

    public static final String CHANNEL_HANDSHAKE = "leafanvil:chunk_stream_handshake";
    public static final String CHANNEL_REGION_DICT_SYNC = "leafanvil:lca_region_dict_sync";
    public static final String CHANNEL_CHUNK_BATCH = "leafanvil:lca_chunk_batch";
    public static final String CHANNEL_CHUNK_DIGEST = "leafanvil:lca_chunk_digest";

    public static final int PROTOCOL_VERSION = 1;

    public static final int CAP_LCA_SECTION_STREAM = 1 << 0;
    public static final int CAP_BATCH_LCD2 = 1 << 1;
    public static final int CAP_CLIENT_HEIGHTMAPS = 1 << 2;
    public static final int CAP_DELTA_REVISIT_CACHE = 1 << 3;
    public static final int CAP_PROXY_SAFE_STORED_ZLIB = 1 << 4;
    public static final int CAP_ORDERLESS_DICT_AND_MULTICAST = 1 << 5;
    public static final int CAP_LCA_V3_SECTIONS = 1 << 6;
    public static final int ALL_CAPABILITIES =
            CAP_LCA_SECTION_STREAM
                    | CAP_BATCH_LCD2
                    | CAP_CLIENT_HEIGHTMAPS
                    | CAP_DELTA_REVISIT_CACHE
                    | CAP_PROXY_SAFE_STORED_ZLIB
                    | CAP_ORDERLESS_DICT_AND_MULTICAST
                    | CAP_LCA_V3_SECTIONS;

    public static final int ENTRY_CLEAN_ACK = 0;
    public static final int ENTRY_PATCH_DELTA = 1;
    public static final int ENTRY_FULL_CHUNK = 2;

    public static final int MAX_BATCH_CHUNKS = 16;
    public static final int MAX_PATCH_DELTA_SECTIONS = 4;
    public static final int DEFAULT_LCD2_BATCH_LEVEL = 3;
    public static final int MAX_DECOMPRESSED_BATCH_BYTES = 8 * 1024 * 1024;

    private static final int BATCH_MAGIC = 0x4C434142; // "LCAB"
    private static final int DIGEST_MAGIC = 0x4C434144; // "LCAD"
    private static final int DICT_SYNC_MAGIC = 0x4C434153; // "LCAS"

    /**
     * Entry header flag (`flags4` bit 1 = bit 11 of 16-bit header word) indicating that the
     * entry's `SectionWireSlice`s are encoded via {@link LcaOrderlessDictTable#encodeSectionWireSlicesWithDict}.
     */
    public static final int HEADER_FLAG_ORDERLESS_DICT_SECTIONS = 1 << 11;
    private static final int HEADER_FLAG_HAS_HEIGHTMAPS = 1 << 12;
    private static final int UNIFORM_SECTION_DIR_BIT = 0x8000;

    private LcaWireStreamProtocol() {
    }

    // =========================================================================
    // 1. Capability Handshake & Safe Mixed-Client Negotiation (Section 1)
    // =========================================================================

    /**
     * Configuration-phase capability handshake payload (`leafanvil:chunk_stream_handshake`).
     */
    public record HandshakePayload(
            int protocolVersion,
            int capabilityFlags,
            int clientCacheBudgetKiB,
            long registryParityHash64
    ) {
        private static final int WIRE_BYTES = 20;

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(WIRE_BYTES);
            buffer.putInt(protocolVersion);
            buffer.putInt(capabilityFlags);
            buffer.putInt(clientCacheBudgetKiB);
            buffer.putLong(registryParityHash64);
            return buffer.array();
        }

        public static HandshakePayload fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length != WIRE_BYTES) {
                throw new IllegalArgumentException(
                        "Invalid HandshakePayload length: expected " + WIRE_BYTES
                                + " bytes, got " + (bytes == null ? -1 : bytes.length));
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new HandshakePayload(
                    buffer.getInt(),
                    buffer.getInt(),
                    buffer.getInt(),
                    buffer.getLong()
            );
        }
    }

    /**
     * Negotiated per-connection `.lca` streaming session state.
     */
    public record NegotiatedSession(
            boolean lcaStreamActive,
            int activeCapabilities,
            int negotiatedCacheBudgetKiB,
            long registryParityHash64
    ) {
        public static final NegotiatedSession VANILLA_FALLBACK =
                new NegotiatedSession(false, 0, 0, 0L);

        public boolean isLcaStreamActive() {
            return lcaStreamActive;
        }

        public boolean hasCapability(int capabilityFlag) {
            return lcaStreamActive && (activeCapabilities & capabilityFlag) == capabilityFlag;
        }

        public boolean isBatchLcd2Enabled() {
            return hasCapability(CAP_BATCH_LCD2);
        }

        public boolean isClientHeightmapsEnabled() {
            return hasCapability(CAP_CLIENT_HEIGHTMAPS);
        }

        public boolean isDeltaRevisitCacheEnabled() {
            return hasCapability(CAP_DELTA_REVISIT_CACHE);
        }

        public boolean isProxySafeStoredZlibEnabled() {
            return hasCapability(CAP_PROXY_SAFE_STORED_ZLIB);
        }

        public boolean isOrderlessDictAndMulticastEnabled() {
            return hasCapability(CAP_ORDERLESS_DICT_AND_MULTICAST);
        }

        public boolean isV3SectionsEnabled() {
            return hasCapability(CAP_LCA_V3_SECTIONS);
        }
    }

    /**
     * Negotiates the intersection of client and server `.lca` streaming capabilities.
     * Falls back to {@link NegotiatedSession#VANILLA_FALLBACK} when the client is unnegotiated,
     * uses an incompatible protocol version, has a mismatched `registryParityHash64`, or does not
     * support {@link #CAP_LCA_SECTION_STREAM}.
     */
    public static NegotiatedSession negotiate(
            HandshakePayload client,
            int serverCapabilities,
            long serverRegistryHash64
    ) {
        if (client == null
                || client.protocolVersion() != PROTOCOL_VERSION
                || client.registryParityHash64() != serverRegistryHash64) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        int intersection = client.capabilityFlags() & serverCapabilities;
        if ((intersection & CAP_LCA_SECTION_STREAM) == 0) {
            return NegotiatedSession.VANILLA_FALLBACK;
        }
        int cacheBudgetKiB = (intersection & CAP_DELTA_REVISIT_CACHE) != 0
                ? Math.max(0, client.clientCacheBudgetKiB())
                : 0;
        return new NegotiatedSession(true, intersection, cacheBudgetKiB, serverRegistryHash64);
    }

    /**
     * Region dictionary to network registry ID synchronization payload (`leafanvil:lca_region_dict_sync`).
     */
    public record LcaRegionDictSyncPayload(
            int regionX,
            int regionZ,
            int[] dictIdToBlockStateRegistryId,
            int[] dictIdToBiomeRegistryId
    ) {
        public LcaRegionDictSyncPayload {
            dictIdToBlockStateRegistryId = dictIdToBlockStateRegistryId == null
                    ? new int[0]
                    : dictIdToBlockStateRegistryId.clone();
            dictIdToBiomeRegistryId = dictIdToBiomeRegistryId == null
                    ? new int[0]
                    : dictIdToBiomeRegistryId.clone();
        }

        public byte[] toBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(DICT_SYNC_MAGIC);
                writeVarInt(out, regionX);
                writeVarInt(out, regionZ);
                writeDeltaVarIntArray(out, dictIdToBlockStateRegistryId);
                writeDeltaVarIntArray(out, dictIdToBiomeRegistryId);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return baos.toByteArray();
        }

        public static LcaRegionDictSyncPayload fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int magic = in.readInt();
                if (magic != DICT_SYNC_MAGIC) {
                    throw new IllegalArgumentException("Invalid LcaRegionDictSyncPayload magic: 0x" + Integer.toHexString(magic));
                }
                int regionX = readVarInt(in);
                int regionZ = readVarInt(in);
                int[] blockMap = readDeltaVarIntArray(in);
                int[] biomeMap = readDeltaVarIntArray(in);
                return new LcaRegionDictSyncPayload(regionX, regionZ, blockMap, biomeMap);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LcaRegionDictSyncPayload other)) {
                return false;
            }
            return this.regionX == other.regionX
                    && this.regionZ == other.regionZ
                    && java.util.Arrays.equals(this.dictIdToBlockStateRegistryId, other.dictIdToBlockStateRegistryId)
                    && java.util.Arrays.equals(this.dictIdToBiomeRegistryId, other.dictIdToBiomeRegistryId);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.regionX;
            result = 31 * result + this.regionZ;
            result = 31 * result + java.util.Arrays.hashCode(this.dictIdToBlockStateRegistryId);
            result = 31 * result + java.util.Arrays.hashCode(this.dictIdToBiomeRegistryId);
            return result;
        }

        @Override
        public String toString() {
            return "LcaRegionDictSyncPayload[regionX=" + this.regionX
                    + ", regionZ=" + this.regionZ
                    + ", dictIdToBlockStateRegistryId=" + java.util.Arrays.toString(this.dictIdToBlockStateRegistryId)
                    + ", dictIdToBiomeRegistryId=" + java.util.Arrays.toString(this.dictIdToBiomeRegistryId)
                    + "]";
        }
    }

    // =========================================================================
    // 2. Zero-Copy (baseFrame, dirtyOverlay) Chunk-Batch LCD2 Framing
    // =========================================================================

    /**
     * Packs a 12-bit voxel position (`0..4095`) and 19-bit block-state registry ID into a non-zero
     * 32-bit `dirtyOverlay` entry (`((pos12 + 1) << 19) | (stateId19 & 0x7FFFF)`).
     */
    public static int packDirtyOverlayEntry(int pos12, int stateId19) {
        if (pos12 < 0 || pos12 > 0xFFF) {
            throw new IllegalArgumentException("pos12 out of range [0..4095]: " + pos12);
        }
        if (stateId19 < 0 || stateId19 > 0x7FFFF) {
            throw new IllegalArgumentException("stateId19 out of range [0..524287]: " + stateId19);
        }
        return (((pos12 & 0xFFF) + 1) << 19) | (stateId19 & 0x7FFFF);
    }

    public static int unpackDirtyOverlayPos12(int packedEntry) {
        return ((packedEntry >>> 19) & 0x1FFF) - 1;
    }

    public static int unpackDirtyOverlayStateId19(int packedEntry) {
        return packedEntry & 0x7FFFF;
    }

    /**
     * Computes the 10-bit region-local chunk index `((chunkZ & 31) << 5) | (chunkX & 31)`.
     */
    public static int toLocalChunkIndex10(int chunkX, int chunkZ) {
        return ((chunkZ & 31) << 5) | (chunkX & 31);
    }

    /**
     * Encodes the `2-byte` (`16-bit`) entry descriptor word combining `entryType` (`bits 15..14`),
     * `flags4` (`bits 13..10`), and `localChunkIndex10` (`bits 9..0`).
     */
    public static short packEntryHeaderWord16(int entryType, int flags4, int localChunkIndex10) {
        if (entryType < 0 || entryType > 2) {
            throw new IllegalArgumentException("Invalid entryType: " + entryType);
        }
        if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
            throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
        }
        return (short) (((entryType & 0x3) << 14) | ((flags4 & 0xF) << 10) | (localChunkIndex10 & 0x3FF));
    }

    public static int unpackEntryType(short headerWord16) {
        return (headerWord16 >>> 14) & 0x3;
    }

    public static int unpackEntryFlags4(short headerWord16) {
        return (headerWord16 >>> 10) & 0xF;
    }

    public static int unpackLocalChunkIndex10(short headerWord16) {
        return headerWord16 & 0x3FF;
    }

    /**
     * Encodes a uniform `.lca` section directory word (`2 B`) with inline `skyLight2` (`0..3`)
     * and `majorityStateId` (`0..8191`), requiring `0` section body bytes.
     */
    public static short encodeUniformDirectoryWord(int majorityStateId, int skyLight2) {
        return (short) (UNIFORM_SECTION_DIR_BIT
                | ((skyLight2 & 0x3) << 13)
                | (majorityStateId & 0x1FFF));
    }

    public static boolean isUniformDirectoryWord(short directoryWord16) {
        return (directoryWord16 & UNIFORM_SECTION_DIR_BIT) != 0;
    }

    /**
     * Represents a single vertical section in `.lca` wire format: either a `2-byte` uniform
     * directory word (`0 B` body) or a non-uniform `baseFrame` slice + optional `dirtyOverlay`
     * `int[]` delta entries (`leafanvil-ADR-002`).
     */
    public record SectionWireSlice(
            int sectionIndex,
            short directoryWord16,
            byte[] baseFrameSlice,
            int[] dirtyOverlayEntries
    ) {
        public SectionWireSlice {
            if (sectionIndex < 0 || sectionIndex >= 32) {
                throw new IllegalArgumentException("sectionIndex out of range [0..31]: " + sectionIndex);
            }
            baseFrameSlice = baseFrameSlice == null ? new byte[0] : baseFrameSlice;
            dirtyOverlayEntries = dirtyOverlayEntries == null ? new int[0] : dirtyOverlayEntries;
        }

        public static SectionWireSlice uniform(int sectionIndex, int majorityStateId, int skyLight2) {
            return new SectionWireSlice(
                    sectionIndex,
                    encodeUniformDirectoryWord(majorityStateId, skyLight2),
                    new byte[0],
                    new int[0]
            );
        }

        public static SectionWireSlice nonUniform(
                int sectionIndex,
                byte[] baseFrameSlice,
                int[] dirtyOverlayEntries
        ) {
            Objects.requireNonNull(baseFrameSlice, "baseFrameSlice");
            short dirWord = (short) (baseFrameSlice.length & 0x7FFF);
            return new SectionWireSlice(sectionIndex, dirWord, baseFrameSlice, dirtyOverlayEntries);
        }

        public boolean isUniform() {
            return isUniformDirectoryWord(directoryWord16)
                    && baseFrameSlice.length == 0
                    && dirtyOverlayEntries.length == 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SectionWireSlice other)) {
                return false;
            }
            return this.sectionIndex == other.sectionIndex
                    && this.directoryWord16 == other.directoryWord16
                    && java.util.Arrays.equals(this.baseFrameSlice, other.baseFrameSlice)
                    && java.util.Arrays.equals(this.dirtyOverlayEntries, other.dirtyOverlayEntries);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.sectionIndex;
            result = 31 * result + this.directoryWord16;
            result = 31 * result + java.util.Arrays.hashCode(this.baseFrameSlice);
            result = 31 * result + java.util.Arrays.hashCode(this.dirtyOverlayEntries);
            return result;
        }

        @Override
        public String toString() {
            return "SectionWireSlice[sectionIndex=" + this.sectionIndex
                    + ", directoryWord16=" + this.directoryWord16
                    + ", baseFrameSlice=" + java.util.Arrays.toString(this.baseFrameSlice)
                    + ", dirtyOverlayEntries=" + java.util.Arrays.toString(this.dirtyOverlayEntries)
                    + "]";
        }
    }

    /**
     * Sealed interface for the 3 chunk entry types carried in a `ClientboundLcaChunkBatchPayload`.
     */
    public sealed interface BatchChunkEntry
            permits EntryCleanAck, EntryPatchDelta, EntryFullChunk {
        int entryType();

        int localChunkIndex10();
    }

    /**
     * `ENTRY_CLEAN_ACK = 0`: `2-byte` compact revisit acknowledgment when the client's cached
     * `(lastUpdateTick, secDedSyndrome64)` matches the server chunk.
     */
    public record EntryCleanAck(int localChunkIndex10) implements BatchChunkEntry {
        public EntryCleanAck {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
        }

        @Override
        public int entryType() {
            return ENTRY_CLEAN_ACK;
        }

        public short toWord16() {
            return packEntryHeaderWord16(ENTRY_CLEAN_ACK, 0, localChunkIndex10);
        }

        public byte[] toBytes() {
            short word = toWord16();
            return new byte[]{(byte) (word >>> 8), (byte) word};
        }

        public static EntryCleanAck fromBytes(byte[] bytes) {
            if (bytes == null || bytes.length != 2) {
                throw new IllegalArgumentException("EntryCleanAck requires exactly 2 bytes");
            }
            short word = (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
            if (unpackEntryType(word) != ENTRY_CLEAN_ACK) {
                throw new IllegalArgumentException("Not an ENTRY_CLEAN_ACK word: 0x" + Integer.toHexString(word & 0xFFFF));
            }
            return new EntryCleanAck(unpackLocalChunkIndex10(word));
        }
    }

    /**
     * `ENTRY_PATCH_DELTA = 1`: compact revisit delta entry carrying `localChunkIndex10`,
     * `newLastUpdateTick`, `newSecDedSyndrome64`, `dirtySectionBitmask32`, and only the `1..4`
     * modified `SectionWireSlice`s.
     */
    public record EntryPatchDelta(
            int localChunkIndex10,
            long newLastUpdateTick,
            long newSecDedSyndrome64,
            int dirtySectionBitmask32,
            List<SectionWireSlice> modifiedSections
    ) implements BatchChunkEntry {
        public EntryPatchDelta {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
            Objects.requireNonNull(modifiedSections, "modifiedSections");
            if (Integer.bitCount(dirtySectionBitmask32) != modifiedSections.size()) {
                throw new IllegalArgumentException(
                        "dirtySectionBitmask32 bitCount (" + Integer.bitCount(dirtySectionBitmask32)
                                + ") != modifiedSections.size() (" + modifiedSections.size() + ")");
            }
            modifiedSections = List.copyOf(modifiedSections);
        }

        @Override
        public int entryType() {
            return ENTRY_PATCH_DELTA;
        }

        public byte[] toBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                writeEntryTo(out, this, true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return baos.toByteArray();
        }
    }

    /**
     * `ENTRY_FULL_CHUNK = 2`: full `.lca` wire chunk containing `chunkX`, `chunkZ`,
     * `lastUpdateTick`, `secDedSyndrome64`, per-section `2 B` uniform directory word or zero-copy
     * `baseFrame` slice + optional `dirtyOverlay` `int[]` entries, and optional heightmap delta bytes.
     */
    public record EntryFullChunk(
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            long secDedSyndrome64,
            List<SectionWireSlice> sections,
            byte[] heightmapDeltaBytes
    ) implements BatchChunkEntry {
        public EntryFullChunk {
            Objects.requireNonNull(sections, "sections");
            sections = List.copyOf(sections);
            heightmapDeltaBytes = heightmapDeltaBytes == null ? new byte[0] : heightmapDeltaBytes;
        }

        public static EntryFullChunk createWithComputedSyndrome(
                int chunkX,
                int chunkZ,
                long lastUpdateTick,
                List<SectionWireSlice> sections,
                byte[] heightmapDeltaBytes
        ) {
            byte[] canonicalSlab = serializeCanonicalChunkSlab(chunkX, chunkZ, sections, heightmapDeltaBytes);
            long syndrome = computeSecDedSyndrome64(lastUpdateTick, canonicalSlab);
            return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, syndrome, sections, heightmapDeltaBytes);
        }

        @Override
        public int entryType() {
            return ENTRY_FULL_CHUNK;
        }

        @Override
        public int localChunkIndex10() {
            return toLocalChunkIndex10(chunkX, chunkZ);
        }

        public byte[] toCanonicalSlabBytes() {
            return serializeCanonicalChunkSlab(chunkX, chunkZ, sections, heightmapDeltaBytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EntryFullChunk other)) {
                return false;
            }
            return this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && this.lastUpdateTick == other.lastUpdateTick
                    && this.secDedSyndrome64 == other.secDedSyndrome64
                    && java.util.Objects.equals(this.sections, other.sections)
                    && java.util.Arrays.equals(this.heightmapDeltaBytes, other.heightmapDeltaBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + this.chunkX;
            result = 31 * result + this.chunkZ;
            result = 31 * result + Long.hashCode(this.lastUpdateTick);
            result = 31 * result + Long.hashCode(this.secDedSyndrome64);
            result = 31 * result + java.util.Objects.hashCode(this.sections);
            result = 31 * result + java.util.Arrays.hashCode(this.heightmapDeltaBytes);
            return result;
        }

        @Override
        public String toString() {
            return "EntryFullChunk[chunkX=" + this.chunkX
                    + ", chunkZ=" + this.chunkZ
                    + ", lastUpdateTick=" + this.lastUpdateTick
                    + ", secDedSyndrome64=" + this.secDedSyndrome64
                    + ", sections=" + this.sections
                    + ", heightmapDeltaBytes=" + java.util.Arrays.toString(this.heightmapDeltaBytes)
                    + "]";
        }
    }

    /**
     * Paced tick batch of `1..16` `.lca` chunk entries (`leafanvil:lca_chunk_batch`).
     */
    public record ClientboundLcaChunkBatchPayload(
            int regionX,
            int regionZ,
            List<BatchChunkEntry> entries
    ) {
        public ClientboundLcaChunkBatchPayload {
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty() || entries.size() > MAX_BATCH_CHUNKS) {
                throw new IllegalArgumentException(
                        "Batch size must be in [1.." + MAX_BATCH_CHUNKS + "], got " + entries.size());
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * Serializes a `ClientboundLcaChunkBatchPayload` into an uncompressed wire byte array,
     * omitting heightmap bytes (`0 B` wire cost) when {@link #CAP_CLIENT_HEIGHTMAPS} is active in
     * the session.
     */
      public static byte[] serializeRawChunkBatch(
              ClientboundLcaChunkBatchPayload batch,
              NegotiatedSession session
      ) {
          return serializeRawChunkBatch(batch, session, null, null);
      }

      /**
       * Serializes a `ClientboundLcaChunkBatchPayload` into an uncompressed wire byte array,
       * omitting heightmap bytes (`0 B` wire cost) when {@link #CAP_CLIENT_HEIGHTMAPS} is active in
       * the session and substituting recurring non-uniform `SectionWireSlice`s via
       * {@link LcaOrderlessDictTable#encodeSectionWireSlicesWithDict} when
       * {@link #CAP_ORDERLESS_DICT_AND_MULTICAST} is active (`REQ-NET-ODCT-001`).
       */
      public static byte[] serializeRawChunkBatch(
              ClientboundLcaChunkBatchPayload batch,
              NegotiatedSession session,
              LcaOrderlessDictTable dictTableOrNull,
              LcaOrderlessDictTable.KnownSlotBitSet targetKnownSlotsOrNull
      ) {
          Objects.requireNonNull(batch, "batch");
          Objects.requireNonNull(session, "session");
          boolean omitHeightmaps = session.isClientHeightmapsEnabled();
          boolean allowV3Sections = session.isV3SectionsEnabled();
          boolean useOrderlessDict = session.isOrderlessDictAndMulticastEnabled()
                  && dictTableOrNull != null
                  && targetKnownSlotsOrNull != null;
          ByteArrayOutputStream baos = new ByteArrayOutputStream(batch.entries().size() * 1024);
          try (DataOutputStream out = new DataOutputStream(baos)) {
              out.writeInt(BATCH_MAGIC);
              writeVarInt(out, batch.regionX());
              writeVarInt(out, batch.regionZ());
              out.writeByte(batch.entries().size());
              for (BatchChunkEntry entry : batch.entries()) {
                  writeEntryTo(
                          out,
                          entry,
                          omitHeightmaps,
                          allowV3Sections,
                          useOrderlessDict ? dictTableOrNull : null,
                          useOrderlessDict ? targetKnownSlotsOrNull : null
                  );
              }
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
          return baos.toByteArray();
      }

      /**
       * Returns {@code true} if any chunk entry in {@code batch} contains a {@code 0x0003}-extended
       * section slice ({@code secExtFlags} {@code 0x01}/{@code 0x02} or Eikonal light residuals).
       */
      public static boolean containsV3ExtendedSection(ClientboundLcaChunkBatchPayload batch) {
          Objects.requireNonNull(batch, "batch");
          for (BatchChunkEntry entry : batch.entries()) {
              List<SectionWireSlice> slices = null;
              if (entry instanceof EntryFullChunk full) {
                  slices = full.sections();
              } else if (entry instanceof EntryPatchDelta patch) {
                  slices = patch.modifiedSections();
              }
              if (slices == null) continue;
              for (SectionWireSlice s : slices) {
                  if (s != null && !isUniformDirectoryWord(s.directoryWord16())
                          && RawChunkFrameSnapshot.isV3ExtendedSectionFrame(s.baseFrameSlice())) {
                      return true;
                  }
              }
          }
          return false;
      }

      private static List<SectionWireSlice> adaptSectionsForSession(
              List<SectionWireSlice> sections,
              boolean allowV3Sections
      ) {
          if (allowV3Sections || sections == null || sections.isEmpty()) {
              return sections;
          }
          List<SectionWireSlice> adapted = null;
          for (int i = 0; i < sections.size(); i++) {
              SectionWireSlice s = sections.get(i);
              if (s != null && !isUniformDirectoryWord(s.directoryWord16())
                      && RawChunkFrameSnapshot.isV3ExtendedSectionFrame(s.baseFrameSlice())) {
                  byte[] downgraded = RawChunkFrameSnapshot.downgradeV3BlockSectionFrameToV2(s.baseFrameSlice());
                  if (RawChunkFrameSnapshot.isV3ExtendedSectionFrame(downgraded)) {
                      throw new IllegalStateException(
                              "Cannot stream v3 Eikonal section slice (sectionIndex=" + s.sectionIndex()
                                      + ") to legacy client without CAP_LCA_V3_SECTIONS");
                  }
                  if (adapted == null) {
                      adapted = new ArrayList<>(sections);
                  }
                  adapted.set(i, SectionWireSlice.nonUniform(s.sectionIndex(), downgraded, s.dirtyOverlayEntries()));
              }
          }
          return adapted != null ? adapted : sections;
      }

      /**
       * Serializes and compresses a `ClientboundLcaChunkBatchPayload` in a single pass using pure-Java
       * `LCD2` (`0x4C44`) intra-batch framing when {@link #CAP_BATCH_LCD2} is active.
       */
      public static byte[] encodeChunkBatchPayload(
              ClientboundLcaChunkBatchPayload batch,
              NegotiatedSession session
      ) throws IOException {
          return encodeChunkBatchPayload(batch, session, null, null);
      }

      /**
       * Serializes (with optional `ODCT` orderless section dictionary substitution) and compresses a
       * `ClientboundLcaChunkBatchPayload` using pure-Java `LCD2` (`0x4C44`) framing when
       * {@link #CAP_BATCH_LCD2} is active (`REQ-NET-ODCT-001`).
       */
      public static byte[] encodeChunkBatchPayload(
              ClientboundLcaChunkBatchPayload batch,
              NegotiatedSession session,
              LcaOrderlessDictTable dictTableOrNull,
              LcaOrderlessDictTable.KnownSlotBitSet targetKnownSlotsOrNull
      ) throws IOException {
          byte[] rawBatch = serializeRawChunkBatch(batch, session, dictTableOrNull, targetKnownSlotsOrNull);
          if (session.isBatchLcd2Enabled()) {
              return compressBatchLcd2(rawBatch, DEFAULT_LCD2_BATCH_LEVEL);
          }
          return rawBatch;
      }

      /**
       * Decompresses (via pure-Java `LCD2` `0x4C44` framing) and deserializes a
       * `ClientboundLcaChunkBatchPayload`.
       */
      public static ClientboundLcaChunkBatchPayload decodeChunkBatchPayload(
              byte[] wirePayload,
              NegotiatedSession session
      ) throws IOException {
          return decodeChunkBatchPayload(wirePayload, session, null);
      }

      /**
       * Decompresses (via pure-Java `LCD2` `0x4C44` framing), expands any `ODCT`-encoded
       * `SectionWireSlice`s against {@code clientDictTableOrNull} (`REQ-NET-ODCT-002`), and
       * deserializes a `ClientboundLcaChunkBatchPayload`.
       */
      public static ClientboundLcaChunkBatchPayload decodeChunkBatchPayload(
              byte[] wirePayload,
              NegotiatedSession session,
              LcaOrderlessDictTable clientDictTableOrNull
      ) throws IOException {
          Objects.requireNonNull(wirePayload, "wirePayload");
          Objects.requireNonNull(session, "session");
          byte[] rawBatch = session.isBatchLcd2Enabled()
                  ? decodeLcd2BatchFrame(wirePayload, MAX_DECOMPRESSED_BATCH_BYTES)
                  : wirePayload;
          return deserializeRawChunkBatch(rawBatch, clientDictTableOrNull);
      }

      public static ClientboundLcaChunkBatchPayload deserializeRawChunkBatch(byte[] rawBatch) throws IOException {
          return deserializeRawChunkBatch(rawBatch, null);
      }

      public static ClientboundLcaChunkBatchPayload deserializeRawChunkBatch(
              byte[] rawBatch,
              LcaOrderlessDictTable clientDictTableOrNull
      ) throws IOException {
          Objects.requireNonNull(rawBatch, "rawBatch");
          try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawBatch))) {
              int magic = in.readInt();
              if (magic != BATCH_MAGIC) {
                  throw new IOException("Invalid ClientboundLcaChunkBatchPayload magic: 0x" + Integer.toHexString(magic));
              }
              int regionX = readVarInt(in);
              int regionZ = readVarInt(in);
              int entryCount = in.readUnsignedByte();
              if (entryCount < 1 || entryCount > MAX_BATCH_CHUNKS) {
                  throw new IOException("Invalid batch entry count: " + entryCount);
              }
              List<BatchChunkEntry> entries = new ArrayList<>(entryCount);
              for (int i = 0; i < entryCount; i++) {
                  entries.add(readEntryFrom(in, regionX, regionZ, clientDictTableOrNull));
              }
              return new ClientboundLcaChunkBatchPayload(regionX, regionZ, entries);
          }
      }

      /**
       * Single-pass pure-Java `LCD2` (`0x4C44`) intra-batch compressor (`leafanvil-ADR-003` Section 3.3).
       */
      public static byte[] compressBatchLcd2(byte[] rawBatch, int lcd2Level) throws IOException {
          Objects.requireNonNull(rawBatch, "rawBatch");
          if (lcd2Level <= 0) {
              return encodeLcd2PassthroughFrame(rawBatch);
          }
          return encodeLcd2BatchFrame(rawBatch);
      }

      /**
       * Rewrites only the `4-32 B` `VarInt[K]` section palette header of an `.lca` `SectionFrame`
       * from disk dictionary indices to host runtime `BlockState` registry IDs while copying the
       * Hilbert / Delta-Roaring voxel body and Gray light nibbles verbatim (`leafanvil-ADR-005`).
       */
      public static byte[] rewriteSectionFramePaletteHeader(byte[] sectionFrame, int[] dictIdToRuntimeId) {
          return io.github.dailystruggle.anvil.RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(
                  sectionFrame, dictIdToRuntimeId);
      }

      private static boolean hasNonUniformSectionSlice(List<SectionWireSlice> sections) {
          for (int i = 0; i < sections.size(); i++) {
              SectionWireSlice s = sections.get(i);
              if (s != null && !isUniformDirectoryWord(s.directoryWord16())) {
                  return true;
              }
          }
          return false;
      }

      private static void writeEntryTo(
              DataOutputStream out,
              BatchChunkEntry entry,
              boolean omitHeightmaps
      ) throws IOException {
          writeEntryTo(out, entry, omitHeightmaps, true, null, null);
      }

      private static void writeEntryTo(
              DataOutputStream out,
              BatchChunkEntry entry,
              boolean omitHeightmaps,
              boolean allowV3Sections,
              LcaOrderlessDictTable dictTableOrNull,
              LcaOrderlessDictTable.KnownSlotBitSet targetKnownSlotsOrNull
      ) throws IOException {
          if (entry instanceof EntryCleanAck cleanAck) {
              out.writeShort(cleanAck.toWord16());
              return;
          }
          if (entry instanceof EntryPatchDelta patch) {
              List<SectionWireSlice> modSections =
                      adaptSectionsForSession(patch.modifiedSections(), allowV3Sections);
              boolean useOrderless = dictTableOrNull != null
                      && targetKnownSlotsOrNull != null
                      && hasNonUniformSectionSlice(modSections);
              int flags4 = useOrderless ? (HEADER_FLAG_ORDERLESS_DICT_SECTIONS >>> 10) : 0;
              short headerWord = packEntryHeaderWord16(ENTRY_PATCH_DELTA, flags4, patch.localChunkIndex10());
              out.writeShort(headerWord);
              writeVarLong(out, patch.newLastUpdateTick());
              out.writeLong(patch.newSecDedSyndrome64());
              out.writeInt(patch.dirtySectionBitmask32());
              if (useOrderless) {
                  byte[] dictEncoded = dictTableOrNull.encodeSectionWireSlicesWithDict(
                          modSections,
                          targetKnownSlotsOrNull
                  );
                  writeVarInt(out, dictEncoded.length);
                  out.write(dictEncoded);
              } else {
                  for (SectionWireSlice section : modSections) {
                      writeSectionWireSlice(out, section);
                  }
              }
              return;
          }
          if (entry instanceof EntryFullChunk full) {
              List<SectionWireSlice> fullSections =
                      adaptSectionsForSession(full.sections(), allowV3Sections);
              byte[] hmBytes = omitHeightmaps ? new byte[0] : full.heightmapDeltaBytes();
              boolean useOrderless = dictTableOrNull != null
                      && targetKnownSlotsOrNull != null
                      && hasNonUniformSectionSlice(fullSections);
              int flags4 = (hmBytes.length > 0 ? (HEADER_FLAG_HAS_HEIGHTMAPS >>> 10) : 0)
                      | (useOrderless ? (HEADER_FLAG_ORDERLESS_DICT_SECTIONS >>> 10) : 0);
              short headerWord = packEntryHeaderWord16(ENTRY_FULL_CHUNK, flags4, full.localChunkIndex10());
              out.writeShort(headerWord);
              writeVarInt(out, full.chunkX());
              writeVarInt(out, full.chunkZ());
              writeVarLong(out, full.lastUpdateTick());
              out.writeLong(full.secDedSyndrome64());
              if (useOrderless) {
                  byte[] dictEncoded = dictTableOrNull.encodeSectionWireSlicesWithDict(
                          fullSections,
                          targetKnownSlotsOrNull
                  );
                  writeVarInt(out, dictEncoded.length);
                  out.write(dictEncoded);
              } else {
                  out.writeByte(fullSections.size());
                  int overlayMask = 0;
                  for (int i = 0; i < fullSections.size(); i++) {
                      SectionWireSlice slice = fullSections.get(i);
                      out.writeShort(slice.directoryWord16());
                      if (slice.dirtyOverlayEntries().length > 0) {
                          overlayMask |= (1 << i);
                      }
                  }
                  out.writeInt(overlayMask);
                  for (int i = 0; i < fullSections.size(); i++) {
                      SectionWireSlice slice = fullSections.get(i);
                      if (!isUniformDirectoryWord(slice.directoryWord16())) {
                          int bodyLen = slice.directoryWord16() & 0x7FFF;
                          out.write(slice.baseFrameSlice(), 0, bodyLen);
                      }
                      if ((overlayMask & (1 << i)) != 0) {
                          writeVarInt(out, slice.dirtyOverlayEntries().length);
                          for (int packedOverlay : slice.dirtyOverlayEntries()) {
                              out.writeInt(packedOverlay);
                          }
                      }
                  }
              }
              if (hmBytes.length > 0) {
                  writeVarInt(out, hmBytes.length);
                  out.write(hmBytes, 0, hmBytes.length);
              }
          }
      }

      private static BatchChunkEntry readEntryFrom(
              DataInputStream in,
              int regionX,
              int regionZ
      ) throws IOException {
          return readEntryFrom(in, regionX, regionZ, null);
      }

      private static BatchChunkEntry readEntryFrom(
              DataInputStream in,
              int regionX,
              int regionZ,
              LcaOrderlessDictTable clientDictTableOrNull
      ) throws IOException {
          short headerWord = in.readShort();
          int entryType = unpackEntryType(headerWord);
          int flags4 = unpackEntryFlags4(headerWord);
          int localIndex10 = unpackLocalChunkIndex10(headerWord);
          boolean orderlessDictEncoded = ((flags4 << 10) & HEADER_FLAG_ORDERLESS_DICT_SECTIONS) != 0;

          if (entryType == ENTRY_CLEAN_ACK) {
              return new EntryCleanAck(localIndex10);
          }
          if (entryType == ENTRY_PATCH_DELTA) {
              long newTick = readVarLong(in);
              long newSyndrome = in.readLong();
              int dirtyMask = in.readInt();
              List<SectionWireSlice> modified;
              if (orderlessDictEncoded) {
                  if (clientDictTableOrNull == null) {
                      throw new IOException(
                              "Cannot decode HEADER_FLAG_ORDERLESS_DICT_SECTIONS EntryPatchDelta without client LcaOrderlessDictTable"
                      );
                  }
                  int dictLen = readVarInt(in);
                  if (dictLen < 0 || dictLen > MAX_DECOMPRESSED_BATCH_BYTES) {
                      throw new IOException("Invalid orderless patch section payload length: " + dictLen);
                  }
                  byte[] dictPayload = new byte[dictLen];
                  in.readFully(dictPayload);
                  modified = clientDictTableOrNull.decodeSectionWireSlicesWithDict(dictPayload);
              } else {
                  int dirtyCount = Integer.bitCount(dirtyMask);
                  modified = new ArrayList<>(dirtyCount);
                  int remainingMask = dirtyMask;
                  while (remainingMask != 0) {
                      int sectionIdx = Integer.numberOfTrailingZeros(remainingMask);
                      remainingMask &= (remainingMask - 1);
                      modified.add(readSectionWireSlice(in, sectionIdx));
                  }
              }
              return new EntryPatchDelta(localIndex10, newTick, newSyndrome, dirtyMask, modified);
          }
          if (entryType == ENTRY_FULL_CHUNK) {
              int chunkX = readVarInt(in);
              int chunkZ = readVarInt(in);
              long lastUpdateTick = readVarLong(in);
              long syndrome64 = in.readLong();
              List<SectionWireSlice> sections;
              if (orderlessDictEncoded) {
                  if (clientDictTableOrNull == null) {
                      throw new IOException(
                              "Cannot decode HEADER_FLAG_ORDERLESS_DICT_SECTIONS EntryFullChunk without client LcaOrderlessDictTable"
                      );
                  }
                  int dictLen = readVarInt(in);
                  if (dictLen < 0 || dictLen > MAX_DECOMPRESSED_BATCH_BYTES) {
                      throw new IOException("Invalid orderless full-chunk section payload length: " + dictLen);
                  }
                  byte[] dictPayload = new byte[dictLen];
                  in.readFully(dictPayload);
                  sections = clientDictTableOrNull.decodeSectionWireSlicesWithDict(dictPayload);
              } else {
                  int sectionCount = in.readUnsignedByte();
                  short[] dirWords = new short[sectionCount];
                  for (int i = 0; i < sectionCount; i++) {
                      dirWords[i] = in.readShort();
                  }
                  int overlayMask = in.readInt();
                  sections = new ArrayList<>(sectionCount);
                  for (int i = 0; i < sectionCount; i++) {
                      short dirWord = dirWords[i];
                      byte[] baseSlice;
                      if (isUniformDirectoryWord(dirWord)) {
                          baseSlice = new byte[0];
                      } else {
                          int bodyLen = dirWord & 0x7FFF;
                          baseSlice = new byte[bodyLen];
                          in.readFully(baseSlice);
                      }
                      int[] overlay;
                      if ((overlayMask & (1 << i)) != 0) {
                          int overlayLen = readVarInt(in);
                          overlay = new int[overlayLen];
                          for (int j = 0; j < overlayLen; j++) {
                              overlay[j] = in.readInt();
                          }
                      } else {
                          overlay = new int[0];
                      }
                      sections.add(new SectionWireSlice(i, dirWord, baseSlice, overlay));
                  }
              }
              boolean hasHeightmaps = ((flags4 << 10) & HEADER_FLAG_HAS_HEIGHTMAPS) != 0;
              byte[] hmBytes = new byte[0];
              if (hasHeightmaps) {
                  int hmLen = readVarInt(in);
                  hmBytes = new byte[hmLen];
                  in.readFully(hmBytes);
              }
              return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, syndrome64, sections, hmBytes);
          }
          throw new IOException("Unknown BatchChunkEntry type: " + entryType + " in region (" + regionX + "," + regionZ + ")");
      }

    private static void writeSectionWireSlice(DataOutputStream out, SectionWireSlice slice) throws IOException {
        out.writeShort(slice.directoryWord16());
        if (!isUniformDirectoryWord(slice.directoryWord16())) {
            int bodyLen = slice.directoryWord16() & 0x7FFF;
            out.write(slice.baseFrameSlice(), 0, bodyLen);
        }
        writeVarInt(out, slice.dirtyOverlayEntries().length);
        for (int packed : slice.dirtyOverlayEntries()) {
            out.writeInt(packed);
        }
    }

    private static SectionWireSlice readSectionWireSlice(DataInputStream in, int sectionIndex) throws IOException {
        short dirWord = in.readShort();
        byte[] baseSlice;
        if (isUniformDirectoryWord(dirWord)) {
            baseSlice = new byte[0];
        } else {
            int bodyLen = dirWord & 0x7FFF;
            baseSlice = new byte[bodyLen];
            in.readFully(baseSlice);
        }
        int overlayCount = readVarInt(in);
        int[] overlay = new int[overlayCount];
        for (int i = 0; i < overlayCount; i++) {
            overlay[i] = in.readInt();
        }
        return new SectionWireSlice(sectionIndex, dirWord, baseSlice, overlay);
    }

    // =========================================================================
    // 3. Netty CompressionEncoder VarInt = 0 & Proxy-Safe Stored-Zlib Bypass
    // =========================================================================

    /**
     * Wraps a compressed `.lca` batch payload in a single Minecraft Netty packet
     * frame `[VarInt(1 + payload.length), VarInt(0), payload...]`.
     *
     * <p>Setting `dataLength = 0` (`VarInt = 0`) instructs Minecraft's Netty framing pipeline that
     * the packet payload is not `Zlib`-compressed, bypassing `CompressionEncoder` `Deflater` and
     * `CompressionDecoder` `Inflater` without sub-slicing the packet into `256 B` fragments.</p>
     */
    public static byte[] encodeNettyVarIntZeroBypassFrame(byte[] lcd2CompressedPayload) {
        Objects.requireNonNull(lcd2CompressedPayload, "lcd2CompressedPayload");
        int packetLength = 1 + lcd2CompressedPayload.length;
        int packetLengthVarIntBytes = varIntByteLength(packetLength);
        byte[] frame = new byte[packetLengthVarIntBytes + 1 + lcd2CompressedPayload.length];
        int offset = writeVarIntToArray(frame, 0, packetLength);
        frame[offset++] = 0x00; // VarInt(0): dataLength = 0 (uncompressed Netty frame bypass)
        System.arraycopy(lcd2CompressedPayload, 0, frame, offset, lcd2CompressedPayload.length);
        return frame;
    }

    /**
     * Decodes a Netty `VarInt = 0` bypass frame `[VarInt(1 + payload.length), VarInt(0), payload...]`
     * and returns a zero-Zlib copy of the inner `.lca` batch payload.
     */
    public static byte[] decodeNettyVarIntZeroBypassFrame(byte[] nettyFrame) {
        Objects.requireNonNull(nettyFrame, "nettyFrame");
        if (nettyFrame.length < 2) {
            throw new IllegalArgumentException("Netty bypass frame too short: " + nettyFrame.length);
        }
        int[] cursor = new int[]{0};
        int packetLength = readVarIntFromArray(nettyFrame, cursor);
        int remainingAfterLength = nettyFrame.length - cursor[0];
        if (packetLength != remainingAfterLength) {
            throw new IllegalArgumentException(
                    "Netty frame length mismatch: header declared " + packetLength
                            + " bytes, but " + remainingAfterLength + " bytes remain");
        }
        int dataLengthStart = cursor[0];
        int dataLength = readVarIntFromArray(nettyFrame, cursor);
        if (dataLength != 0) {
            throw new IllegalArgumentException(
                    "Expected Netty bypass dataLength VarInt == 0, got " + dataLength);
        }
        int dataLengthVarIntBytes = cursor[0] - dataLengthStart;
        int payloadOffset = cursor[0];
        int payloadLength = packetLength - dataLengthVarIntBytes;
        return Arrays.copyOfRange(nettyFrame, payloadOffset, payloadOffset + payloadLength);
    }

    /**
     * Wraps a compressed `.lca` batch payload in a proxy-safe RFC 1950 / RFC 1951
     * `Deflater.NO_COMPRESSION` (`level = 0` stored-block) Netty packet frame
     * `[VarInt(packetLength), VarInt(dataLength), 0x78, 0x01, storedBlocks..., Adler32]`.
     *
     * <p>Produces a valid non-zero `dataLength` header and RFC 1950 Zlib stream (adding only `11 B`
     * for batches `<= 65,535 B`) with zero LZ77/Huffman CPU overhead, guaranteeing compatibility
     * with Velocity and BungeeCord reverse proxies that reject `dataLength == 0` frames larger than
     * `network-compression-threshold` (`256 B`).</p>
     */
    public static byte[] encodeProxySafeZlibStoredFrame(byte[] lcd2CompressedPayload) {
        Objects.requireNonNull(lcd2CompressedPayload, "lcd2CompressedPayload");
        int uncompressedLen = lcd2CompressedPayload.length;
        int blockCount = Math.max(1, (uncompressedLen + 65534) / 65535);
        int zlibStreamLen = 2 + (blockCount * 5) + uncompressedLen + 4;
        int dataLenVarIntBytes = varIntByteLength(uncompressedLen);
        int packetLength = dataLenVarIntBytes + zlibStreamLen;
        int packetLenVarIntBytes = varIntByteLength(packetLength);

        byte[] frame = new byte[packetLenVarIntBytes + packetLength];
        int pos = writeVarIntToArray(frame, 0, packetLength);
        pos = writeVarIntToArray(frame, pos, uncompressedLen);

        // RFC 1950 Zlib header (CMF=0x78, FLG=0x01 -> no preset dict, level 0, valid mod-31 check)
        frame[pos++] = 0x78;
        frame[pos++] = 0x01;

        int remaining = uncompressedLen;
        int srcOffset = 0;
        for (int b = 0; b < blockCount; b++) {
            int chunkLen = Math.min(65535, remaining);
            boolean isFinal = (b == blockCount - 1);
            frame[pos++] = (byte) (isFinal ? 0x01 : 0x00); // BFINAL=1/0, BTYPE=00 (stored block)
            frame[pos++] = (byte) (chunkLen & 0xFF);
            frame[pos++] = (byte) ((chunkLen >>> 8) & 0xFF);
            int nlen = ~chunkLen & 0xFFFF;
            frame[pos++] = (byte) (nlen & 0xFF);
            frame[pos++] = (byte) ((nlen >>> 8) & 0xFF);
            if (chunkLen > 0) {
                System.arraycopy(lcd2CompressedPayload, srcOffset, frame, pos, chunkLen);
                pos += chunkLen;
                srcOffset += chunkLen;
                remaining -= chunkLen;
            }
        }

        Adler32 adler = new Adler32();
        adler.update(lcd2CompressedPayload);
        long checksum = adler.getValue();
        frame[pos++] = (byte) ((checksum >>> 24) & 0xFF);
        frame[pos++] = (byte) ((checksum >>> 16) & 0xFF);
        frame[pos++] = (byte) ((checksum >>> 8) & 0xFF);
        frame[pos] = (byte) (checksum & 0xFF);
        return frame;
    }

    /**
     * Decodes a proxy-safe `Deflater.NO_COMPRESSION` stored-block Netty frame using standard
     * `Inflater` (verifying 100% compatibility with vanilla/Velocity `CompressionDecoder`).
     */
    public static byte[] decodeProxySafeZlibStoredFrame(byte[] nettyFrame) {
        Objects.requireNonNull(nettyFrame, "nettyFrame");
        int[] cursor = new int[]{0};
        int packetLength = readVarIntFromArray(nettyFrame, cursor);
        int remainingAfterPacketLen = nettyFrame.length - cursor[0];
        if (packetLength != remainingAfterPacketLen) {
            throw new IllegalArgumentException(
                    "Stored-Zlib frame length mismatch: expected " + packetLength + ", got " + remainingAfterPacketLen);
        }
        int dataLenStart = cursor[0];
        int uncompressedLen = readVarIntFromArray(nettyFrame, cursor);
        if (uncompressedLen <= 0) {
            throw new IllegalArgumentException("Expected non-zero dataLength for proxy-safe stored-Zlib frame");
        }
        int zlibOffset = cursor[0];
        int zlibLength = packetLength - (zlibOffset - dataLenStart);

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(nettyFrame, zlibOffset, zlibLength);
            byte[] out = new byte[uncompressedLen];
            int total = 0;
            while (total < uncompressedLen && !inflater.finished()) {
                int count = inflater.inflate(out, total, uncompressedLen - total);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                total += count;
            }
            if (total != uncompressedLen) {
                throw new IllegalArgumentException("Incomplete stored-Zlib inflation: " + total + " != " + uncompressedLen);
            }
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("Malformed stored-Zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    // =========================================================================
    // 4. Bounded Client .lca Slab LRU Cache & 3-Way SEC-DED Revisit Resolution
    // =========================================================================

    /**
     * Computes a 64-bit Extended Hamming `(72, 64)` SEC-DED syndrome over `(lastUpdateTick, slabBytes)`.
     */
    public static long computeSecDedSyndrome64(long lastUpdateTick, byte[] slabBytes) {
        return LcaRemoteStreamCodec.computeSecDedSyndrome64(lastUpdateTick, slabBytes);
    }

    /**
     * Verifies both the Extended Hamming `(72, 64)` SEC-DED parity code and the recomputed syndrome
     * of `(lastUpdateTick, slabBytes)`.
     */
    public static boolean verifySecDedSyndrome64(
            long lastUpdateTick,
            byte[] slabBytes,
            long expectedSyndrome64
    ) {
        return LcaRemoteStreamCodec.verifySecDedSyndrome64(lastUpdateTick, slabBytes, expectedSyndrome64);
    }

    /**
     * Key for `ClientChunkSlabCache` entries.
     */
    public record ChunkCacheKey(String dimension, int chunkX, int chunkZ) {
        public ChunkCacheKey {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    /**
     * Cached client `.lca` chunk slab entry with `lastUpdateTick` and 64-bit SEC-DED syndrome.
     */
    public record CachedChunkSlab(
            String dimension,
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            long secDedSyndrome64,
            byte[] slabBytes
    ) {
        public CachedChunkSlab {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(slabBytes, "slabBytes");
        }

        public int byteSize() {
            return slabBytes.length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CachedChunkSlab other)) {
                return false;
            }
            return java.util.Objects.equals(this.dimension, other.dimension)
                    && this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && this.lastUpdateTick == other.lastUpdateTick
                    && this.secDedSyndrome64 == other.secDedSyndrome64
                    && java.util.Arrays.equals(this.slabBytes, other.slabBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.dimension);
            result = 31 * result + this.chunkX;
            result = 31 * result + this.chunkZ;
            result = 31 * result + Long.hashCode(this.lastUpdateTick);
            result = 31 * result + Long.hashCode(this.secDedSyndrome64);
            result = 31 * result + java.util.Arrays.hashCode(this.slabBytes);
            return result;
        }

        @Override
        public String toString() {
            return "CachedChunkSlab[dimension=" + this.dimension
                    + ", chunkX=" + this.chunkX
                    + ", chunkZ=" + this.chunkZ
                    + ", lastUpdateTick=" + this.lastUpdateTick
                    + ", secDedSyndrome64=" + this.secDedSyndrome64
                    + ", slabBytes=" + java.util.Arrays.toString(this.slabBytes)
                    + "]";
        }
    }

    /**
     * Bounded LRU client `.lca` chunk slab cache (`leafanvil-ADR-003` Section 4.1).
     */
    public static final class ClientChunkSlabCache {
        public static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;

        private final long maxBytes;
        private final LinkedHashMap<ChunkCacheKey, CachedChunkSlab> lruMap;
        private long currentBytes;
        private long evictionCount;

        public ClientChunkSlabCache(long maxBytes) {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be > 0: " + maxBytes);
            }
            this.maxBytes = maxBytes;
            this.lruMap = new LinkedHashMap<>(256, 0.75f, true);
        }

        public ClientChunkSlabCache() {
            this(DEFAULT_MAX_BYTES);
        }

        public synchronized void put(String dimension, EntryFullChunk fullChunk) {
            Objects.requireNonNull(fullChunk, "fullChunk");
            byte[] canonicalSlab = fullChunk.toCanonicalSlabBytes();
            long syndrome = computeSecDedSyndrome64(fullChunk.lastUpdateTick(), canonicalSlab);
            putRaw(new CachedChunkSlab(
                    dimension,
                    fullChunk.chunkX(),
                    fullChunk.chunkZ(),
                    fullChunk.lastUpdateTick(),
                    syndrome,
                    canonicalSlab
            ));
        }

        public synchronized void putRaw(CachedChunkSlab entry) {
            Objects.requireNonNull(entry, "entry");
            int entryBytes = entry.byteSize();
            if (entryBytes > maxBytes) {
                return;
            }
            ChunkCacheKey key = new ChunkCacheKey(entry.dimension(), entry.chunkX(), entry.chunkZ());
            CachedChunkSlab previous = lruMap.remove(key);
            if (previous != null) {
                currentBytes -= previous.byteSize();
            }
            Iterator<Map.Entry<ChunkCacheKey, CachedChunkSlab>> it = lruMap.entrySet().iterator();
            while (currentBytes + entryBytes > maxBytes && it.hasNext()) {
                Map.Entry<ChunkCacheKey, CachedChunkSlab> eldest = it.next();
                currentBytes -= eldest.getValue().byteSize();
                it.remove();
                evictionCount++;
            }
            lruMap.put(key, entry);
            currentBytes += entryBytes;
        }

        public synchronized CachedChunkSlab get(String dimension, int chunkX, int chunkZ) {
            return lruMap.get(new ChunkCacheKey(dimension, chunkX, chunkZ));
        }

        public synchronized boolean evict(String dimension, int chunkX, int chunkZ) {
            CachedChunkSlab removed = lruMap.remove(new ChunkCacheKey(dimension, chunkX, chunkZ));
            if (removed != null) {
                currentBytes -= removed.byteSize();
                evictionCount++;
                return true;
            }
            return false;
        }

        /**
         * Injects a single-bit flip into a cached chunk slab for SEC-DED fault-injection testing.
         */
        public synchronized void corruptSingleBit(
                String dimension,
                int chunkX,
                int chunkZ,
                int byteIndex,
                int bitIndex
        ) {
            CachedChunkSlab entry = lruMap.get(new ChunkCacheKey(dimension, chunkX, chunkZ));
            if (entry == null) {
                throw new IllegalArgumentException("Chunk not present in cache: (" + chunkX + "," + chunkZ + ")");
            }
            if (byteIndex < 0 || byteIndex >= entry.slabBytes().length) {
                throw new IllegalArgumentException("byteIndex out of bounds: " + byteIndex);
            }
            if (bitIndex < 0 || bitIndex > 7) {
                throw new IllegalArgumentException("bitIndex out of bounds [0..7]: " + bitIndex);
            }
            entry.slabBytes()[byteIndex] ^= (byte) (1 << bitIndex);
        }

        public synchronized ServerboundLcaChunkDigestPayload buildRegionDigest(
                String dimension,
                int regionX,
                int regionZ
        ) {
            Objects.requireNonNull(dimension, "dimension");
            List<ChunkDigestEntry> digestEntries = new ArrayList<>();
            for (CachedChunkSlab slab : lruMap.values()) {
                if (dimension.equals(slab.dimension())
                        && (slab.chunkX() >> 5) == regionX
                        && (slab.chunkZ() >> 5) == regionZ) {
                    digestEntries.add(new ChunkDigestEntry(
                            toLocalChunkIndex10(slab.chunkX(), slab.chunkZ()),
                            slab.lastUpdateTick(),
                            slab.secDedSyndrome64()
                    ));
                }
            }
            return new ServerboundLcaChunkDigestPayload(dimension, regionX, regionZ, digestEntries);
        }

        public synchronized long currentBytes() {
            return currentBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }

        public synchronized int size() {
            return lruMap.size();
        }

        public synchronized long evictionCount() {
            return evictionCount;
        }
    }

    /**
     * Per-chunk revisit digest entry in {@link ServerboundLcaChunkDigestPayload}.
     */
    public record ChunkDigestEntry(
            int localChunkIndex10,
            long lastUpdateTick,
            long secDedSyndrome64
    ) {
        public ChunkDigestEntry {
            if (localChunkIndex10 < 0 || localChunkIndex10 > 0x3FF) {
                throw new IllegalArgumentException("localChunkIndex10 out of range [0..1023]: " + localChunkIndex10);
            }
        }
    }

    /**
     * Client-to-server region revisit digest payload (`leafanvil:lca_chunk_digest`).
     */
    public record ServerboundLcaChunkDigestPayload(
            String dimension,
            int regionX,
            int regionZ,
            List<ChunkDigestEntry> entries
    ) {
        public ServerboundLcaChunkDigestPayload {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(entries, "entries");
            entries = List.copyOf(entries);
        }

        public ChunkDigestEntry findChunk(int localChunkIndex10) {
            for (ChunkDigestEntry entry : entries) {
                if (entry.localChunkIndex10() == localChunkIndex10) {
                    return entry;
                }
            }
            return null;
        }

        public byte[] toBytes() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 + entries.size() * 18);
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(DIGEST_MAGIC);
                out.writeUTF(dimension);
                writeVarInt(out, regionX);
                writeVarInt(out, regionZ);
                writeVarInt(out, entries.size());
                for (ChunkDigestEntry entry : entries) {
                    out.writeShort(entry.localChunkIndex10());
                    writeVarLong(out, entry.lastUpdateTick());
                    out.writeLong(entry.secDedSyndrome64());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return baos.toByteArray();
        }

        public static ServerboundLcaChunkDigestPayload fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int magic = in.readInt();
                if (magic != DIGEST_MAGIC) {
                    throw new IllegalArgumentException("Invalid ServerboundLcaChunkDigestPayload magic: 0x" + Integer.toHexString(magic));
                }
                String dimension = in.readUTF();
                int regionX = readVarInt(in);
                int regionZ = readVarInt(in);
                int count = readVarInt(in);
                List<ChunkDigestEntry> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int localIdx = in.readUnsignedShort();
                    long tick = readVarLong(in);
                    long syndrome = in.readLong();
                    list.add(new ChunkDigestEntry(localIdx, tick, syndrome));
                }
                return new ServerboundLcaChunkDigestPayload(dimension, regionX, regionZ, list);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Resolves the optimal server-to-client revisit chunk entry (`Case A`: `2 B` `ENTRY_CLEAN_ACK`,
     * `Case B`: `ENTRY_PATCH_DELTA` for `1..4` dirty sections, `Case C`: `ENTRY_FULL_CHUNK` on cache
     * miss, `> 4` mutated sections, or SEC-DED mismatch).
     */
    public static BatchChunkEntry resolveServerRevisitEntry(
            EntryFullChunk currentServerChunk,
            ChunkDigestEntry clientDigest,
            long expectedBaselineSecDed64,
            int dirtySectionBitmask32
    ) {
        Objects.requireNonNull(currentServerChunk, "currentServerChunk");
        if (clientDigest == null) {
            return currentServerChunk;
        }
        if (clientDigest.secDedSyndrome64() != expectedBaselineSecDed64) {
            return currentServerChunk;
        }
        if (dirtySectionBitmask32 == 0
                && clientDigest.lastUpdateTick() == currentServerChunk.lastUpdateTick()
                && clientDigest.secDedSyndrome64() == currentServerChunk.secDedSyndrome64()) {
            return new EntryCleanAck(currentServerChunk.localChunkIndex10());
        }
        int dirtyCount = Integer.bitCount(dirtySectionBitmask32);
        if (dirtyCount >= 1 && dirtyCount <= MAX_PATCH_DELTA_SECTIONS) {
            List<SectionWireSlice> modifiedSlices = new ArrayList<>(dirtyCount);
            int mask = dirtySectionBitmask32;
            while (mask != 0) {
                int sectionIdx = Integer.numberOfTrailingZeros(mask);
                mask &= (mask - 1);
                if (sectionIdx >= currentServerChunk.sections().size()) {
                    return currentServerChunk;
                }
                modifiedSlices.add(currentServerChunk.sections().get(sectionIdx));
            }
            return new EntryPatchDelta(
                    currentServerChunk.localChunkIndex10(),
                    currentServerChunk.lastUpdateTick(),
                    currentServerChunk.secDedSyndrome64(),
                    dirtySectionBitmask32,
                    modifiedSlices
            );
        }
        return currentServerChunk;
    }

    /**
     * Outcome of applying a revisit chunk entry (`ENTRY_CLEAN_ACK`, `ENTRY_PATCH_DELTA`, or
     * `ENTRY_FULL_CHUNK`) against the client's {@link ClientChunkSlabCache}.
     */
    public record ClientRevisitApplyResult(
            EntryFullChunk resolvedChunk,
            boolean secDedCorruptedAndEvicted,
            boolean requestedFullChunkFallback
    ) {
    }

    /**
     * Applies an inbound `BatchChunkEntry` on the client against `ClientChunkSlabCache`.
     */
    public static ClientRevisitApplyResult applyClientRevisitEntry(
            ClientChunkSlabCache clientCache,
            String dimension,
            int regionX,
            int regionZ,
            BatchChunkEntry inboundEntry,
            EntryFullChunk serverFallbackSupplierIfCorrupt
    ) {
        Objects.requireNonNull(clientCache, "clientCache");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(inboundEntry, "inboundEntry");

        if (inboundEntry instanceof EntryFullChunk fullChunk) {
            clientCache.put(dimension, fullChunk);
            return new ClientRevisitApplyResult(fullChunk, false, false);
        }

        int localIdx = inboundEntry.localChunkIndex10();
        int chunkX = (regionX << 5) | (localIdx & 31);
        int chunkZ = (regionZ << 5) | ((localIdx >>> 5) & 31);

        CachedChunkSlab cached = clientCache.get(dimension, chunkX, chunkZ);
        if (cached == null
                || !verifySecDedSyndrome64(cached.lastUpdateTick(), cached.slabBytes(), cached.secDedSyndrome64())) {
            boolean corrupted = (cached != null);
            if (corrupted) {
                clientCache.evict(dimension, chunkX, chunkZ);
            }
            if (serverFallbackSupplierIfCorrupt != null) {
                BatchChunkEntry fallback = resolveServerRevisitEntry(serverFallbackSupplierIfCorrupt, null, 0L, -1);
                if (fallback instanceof EntryFullChunk fullFallback) {
                    clientCache.put(dimension, fullFallback);
                    return new ClientRevisitApplyResult(fullFallback, corrupted, true);
                }
            }
            return new ClientRevisitApplyResult(null, corrupted, true);
        }

        EntryFullChunk restoredBase = deserializeCanonicalChunkSlab(
                cached.slabBytes(),
                cached.lastUpdateTick(),
                cached.secDedSyndrome64()
        );

        if (inboundEntry instanceof EntryCleanAck) {
            return new ClientRevisitApplyResult(restoredBase, false, false);
        }

        if (inboundEntry instanceof EntryPatchDelta patch) {
            List<SectionWireSlice> mergedSections = new ArrayList<>(restoredBase.sections());
            int mask = patch.dirtySectionBitmask32();
            int modCursor = 0;
            while (mask != 0) {
                int sectionIdx = Integer.numberOfTrailingZeros(mask);
                mask &= (mask - 1);
                mergedSections.set(sectionIdx, patch.modifiedSections().get(modCursor++));
            }
            EntryFullChunk mergedChunk = new EntryFullChunk(
                    restoredBase.chunkX(),
                    restoredBase.chunkZ(),
                    patch.newLastUpdateTick(),
                    patch.newSecDedSyndrome64(),
                    mergedSections,
                    restoredBase.heightmapDeltaBytes()
            );
            clientCache.put(dimension, mergedChunk);
            return new ClientRevisitApplyResult(mergedChunk, false, false);
        }

        throw new IllegalStateException("Unhandled BatchChunkEntry: " + inboundEntry);
    }

    // =========================================================================
    // 5. Strict Netty Arrival-Order Client Commit Sequencer (Invariant 3)
    // =========================================================================

    /**
     * Hydrated client chunk ready to be attached onto `ClientLevel` on the client main thread.
     */
    public record HydratedClientChunkCommit(
            String dimension,
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            long secDedSyndrome64,
            LcaSectionBacking[] sectionBackings,
            byte[] heightmapBytes
    ) {
        public HydratedClientChunkCommit(
                String dimension,
                int chunkX,
                int chunkZ,
                long lastUpdateTick,
                long secDedSyndrome64,
                LcaSectionBacking[] sectionBackings
        ) {
            this(dimension, chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, sectionBackings, new byte[0]);
        }

        public HydratedClientChunkCommit {
            heightmapBytes = heightmapBytes == null ? new byte[0] : heightmapBytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HydratedClientChunkCommit other)) {
                return false;
            }
            return java.util.Objects.equals(this.dimension, other.dimension)
                    && this.chunkX == other.chunkX
                    && this.chunkZ == other.chunkZ
                    && this.lastUpdateTick == other.lastUpdateTick
                    && this.secDedSyndrome64 == other.secDedSyndrome64
                    && java.util.Arrays.equals(this.sectionBackings, other.sectionBackings)
                    && java.util.Arrays.equals(this.heightmapBytes, other.heightmapBytes);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + java.util.Objects.hashCode(this.dimension);
            result = 31 * result + this.chunkX;
            result = 31 * result + this.chunkZ;
            result = 31 * result + Long.hashCode(this.lastUpdateTick);
            result = 31 * result + Long.hashCode(this.secDedSyndrome64);
            result = 31 * result + java.util.Arrays.hashCode(this.sectionBackings);
            result = 31 * result + java.util.Arrays.hashCode(this.heightmapBytes);
            return result;
        }

        @Override
        public String toString() {
            return "HydratedClientChunkCommit[dimension=" + this.dimension
                    + ", chunkX=" + this.chunkX
                    + ", chunkZ=" + this.chunkZ
                    + ", lastUpdateTick=" + this.lastUpdateTick
                    + ", secDedSyndrome64=" + this.secDedSyndrome64
                    + ", sectionBackings=" + java.util.Arrays.toString(this.sectionBackings)
                    + ", heightmapBytesLen=" + this.heightmapBytes.length
                    + "]";
        }
    }

    /**
     * Enforces strict Netty packet arrival order when background workers decode `.lca` batches
     * and verify SEC-DED syndromes off-thread while single-block (`ClientboundBlockUpdatePacket`)
     * or multi-block (`ClientboundSectionBlocksUpdatePacket`) updates arrive on Netty.
     *
     * <p>Guarantees that hydrated {@link LcaSectionBacking} instances from batch `seq` are always
     * attached to `ClientLevel` on the main thread before any block update packet with `seq' > seq`
     * mutates that chunk (`leafanvil-ADR-003` Section 5).</p>
     */
    public static final class ClientOrderedBatchCommitQueue {
        private final AtomicLong nextArrivalSequence = new AtomicLong(0L);
        private final ConcurrentSkipListMap<Long, Runnable> readyActionsBySequence = new ConcurrentSkipListMap<>();
        private long nextCommitSequence = 0L;

        /**
         * Reserves the next monotonic Netty arrival sequence number on the Netty event-loop thread.
         */
        public long reserveNettyArrivalSequence() {
            return this.nextArrivalSequence.getAndIncrement();
        }

        /**
         * Publishes a completed off-thread batch hydration (or ordered main-thread block mutation)
         * at its reserved Netty arrival sequence number.
         */
        public synchronized void publishReadyAtSequence(long sequenceNumber, Runnable mainThreadCommitAction) {
            Objects.requireNonNull(mainThreadCommitAction, "mainThreadCommitAction");
            this.readyActionsBySequence.put(sequenceNumber, mainThreadCommitAction);
        }

        /**
         * Enqueues a main-thread block mutation (`ClientboundBlockUpdatePacket` or
         * `ClientboundSectionBlocksUpdatePacket`) at the current Netty arrival sequence number.
         */
        public synchronized long enqueueOrderedBlockMutation(Runnable mainThreadBlockMutation) {
            long seq = reserveNettyArrivalSequence();
            publishReadyAtSequence(seq, mainThreadBlockMutation);
            return seq;
        }

        /**
         * Drains and executes all contiguous ready actions (`nextCommitSequence, nextCommitSequence + 1, ...`)
         * on the client main thread in strict Netty arrival order.
         */
        public synchronized int drainReadyInStrictNettyOrder() {
            int executed = 0;
            while (true) {
                Runnable nextAction = this.readyActionsBySequence.remove(this.nextCommitSequence);
                if (nextAction == null) {
                    break;
                }
                this.nextCommitSequence++;
                nextAction.run();
                executed++;
            }
            return executed;
        }

        public synchronized long nextCommitSequence() {
            return this.nextCommitSequence;
        }

        public synchronized int pendingOutOfOrderCount() {
            return this.readyActionsBySequence.size();
        }
    }

    /**
     * Hydrates an {@link EntryFullChunk} into an array of {@link LcaSectionBacking} instances off
     * the render/main thread so main-thread commit is a pure $O(1)$ reference assignment per section.
     */
    public static HydratedClientChunkCommit hydrateClientChunkOffThread(
            String dimension,
            EntryFullChunk resolvedChunk,
            java.util.function.Function<SectionWireSlice, LcaSectionBacking> sectionHydrator
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(resolvedChunk, "resolvedChunk");
        Objects.requireNonNull(sectionHydrator, "sectionHydrator");
        int count = resolvedChunk.sections().size();
        LcaSectionBacking[] backings = new LcaSectionBacking[count];
        for (int i = 0; i < count; i++) {
            backings[i] = sectionHydrator.apply(resolvedChunk.sections().get(i));
        }
        return new HydratedClientChunkCommit(
                dimension,
                resolvedChunk.chunkX(),
                resolvedChunk.chunkZ(),
                resolvedChunk.lastUpdateTick(),
                resolvedChunk.secDedSyndrome64(),
                backings,
                resolvedChunk.heightmapDeltaBytes()
        );
    }

    /**
     * Default off-thread section hydrator converting a {@link SectionWireSlice} into an
     * {@link LcaSectionBacking}.
     */
    public static LcaSectionBacking hydrateDefaultSectionBacking(SectionWireSlice wireSlice) {
        Objects.requireNonNull(wireSlice, "wireSlice");
        if (wireSlice.isUniform()) {
            int word16 = wireSlice.directoryWord16();
            int majorityStateId = word16 & 0x1FFF;
            int skyLight2 = (word16 >>> 13) & 3;
            return new LcaSectionBacking(new byte[0], majorityStateId).withUniformSkyLight2(skyLight2);
        }
        LcaSectionBacking backing = new LcaSectionBacking(wireSlice.baseFrameSlice(), 0);
        int[] overlayEntries = wireSlice.dirtyOverlayEntries();
        for (int i = 0; i < overlayEntries.length; i++) {
            int entry32 = overlayEntries[i];
            int pos12 = LcaSectionBacking.unpackPos12(entry32);
            int stateId19 = LcaSectionBacking.unpackStateId19(entry32);
            int x = pos12 & 15;
            int z = (pos12 >>> 4) & 15;
            int y = (pos12 >>> 8) & 15;
            backing.setStateId(x, y, z, stateId19, 1L + i);
        }
        return backing;
    }

    // =========================================================================
    // 6. Inter-Server Zero-Translation Chunk & Section Delta Sync (`LCAS`)
    // =========================================================================

    /** Magic header for inter-server `.lca` chunk delta and pre-warm batches (`"LCAS"`). */
    public static final int INTER_SERVER_UPDATE_MAGIC = 0x4C434153;

    /**
     * Single chunk update entry in an inter-server synchronization batch, carrying the expected
     * pre-update {@code previousBaselineSecDed64} (`0L` for full-chunk initial handoff) and the
     * zero-translation {@link BatchChunkEntry} ({@link EntryPatchDelta} for section overlay deltas
     * or {@link EntryFullChunk} for full chunk transfer/refill).
     */
    public record InterServerChunkDeltaEntry(
            int chunkX,
            int chunkZ,
            long previousBaselineSecDed64,
            BatchChunkEntry chunkEntry
    ) {
        public InterServerChunkDeltaEntry {
            Objects.requireNonNull(chunkEntry, "chunkEntry");
        }

        public int localChunkIndex10() {
            return toLocalChunkIndex10(chunkX, chunkZ);
        }
    }

    /**
     * Batch envelope for streaming `.lca` section deltas (`EntryPatchDelta`) or full pre-warmed
     * chunks (`EntryFullChunk`) between backend servers in a multi-server network.
     */
    public record InterServerChunkUpdatePayload(
            String sourceServerId,
            String dimension,
            int regionX,
            int regionZ,
            long epochSequence,
            List<InterServerChunkDeltaEntry> entries
    ) {
        public InterServerChunkUpdatePayload {
            Objects.requireNonNull(sourceServerId, "sourceServerId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("InterServerChunkUpdatePayload entries must not be empty");
            }
            entries = List.copyOf(entries);
        }
    }

    /**
     * Outcome status for applying an {@link InterServerChunkDeltaEntry} on a peer server node.
     */
    public enum InterServerApplyStatus {
        APPLIED,
        SKIPPED_UNLOADED_FENCE,
        NEEDS_FULL_CHUNK_REFILL,
        SYNDROME_MISMATCH
    }

    /**
     * Serializes (and optionally `LCD2`-compresses) an {@link InterServerChunkUpdatePayload} for
     * transmission over Redis, Postgres, or proxy plugin-message channels.
     */
    public static byte[] encodeInterServerUpdatePayload(
            InterServerChunkUpdatePayload payload,
            boolean lcd2Compressed
    ) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.entries().size() * 512);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(INTER_SERVER_UPDATE_MAGIC);
            out.writeUTF(payload.sourceServerId());
            out.writeUTF(payload.dimension());
            writeVarInt(out, payload.regionX());
            writeVarInt(out, payload.regionZ());
            writeVarLong(out, payload.epochSequence());
            writeVarInt(out, payload.entries().size());
            for (InterServerChunkDeltaEntry delta : payload.entries()) {
                writeVarInt(out, delta.chunkX());
                writeVarInt(out, delta.chunkZ());
                out.writeLong(delta.previousBaselineSecDed64());
                writeEntryTo(out, delta.chunkEntry(), false);
            }
        }
        byte[] raw = baos.toByteArray();
        if (lcd2Compressed) {
            return compressBatchLcd2(raw, DEFAULT_LCD2_BATCH_LEVEL);
        }
        return raw;
    }

    /**
     * Decompresses (when {@code lcd2Compressed == true}) and deserializes an
     * {@link InterServerChunkUpdatePayload}.
     */
    public static InterServerChunkUpdatePayload decodeInterServerUpdatePayload(
            byte[] wireBytes,
            boolean lcd2Compressed
    ) throws IOException {
        Objects.requireNonNull(wireBytes, "wireBytes");
        byte[] raw = lcd2Compressed
                ? decodeLcd2BatchFrame(wireBytes, MAX_DECOMPRESSED_BATCH_BYTES)
                : wireBytes;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != INTER_SERVER_UPDATE_MAGIC) {
                throw new IOException("Invalid InterServerChunkUpdatePayload magic: 0x" + Integer.toHexString(magic));
            }
            String sourceServerId = in.readUTF();
            String dimension = in.readUTF();
            int regionX = readVarInt(in);
            int regionZ = readVarInt(in);
            long epochSeq = readVarLong(in);
            int count = readVarInt(in);
            if (count < 1 || count > 1024) {
                throw new IOException("Invalid InterServerChunkUpdatePayload entry count: " + count);
            }
            List<InterServerChunkDeltaEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int chunkX = readVarInt(in);
                int chunkZ = readVarInt(in);
                long prevBaseline = in.readLong();
                BatchChunkEntry entry = readEntryFrom(in, regionX, regionZ);
                entries.add(new InterServerChunkDeltaEntry(chunkX, chunkZ, prevBaseline, entry));
            }
            return new InterServerChunkUpdatePayload(
                    sourceServerId,
                    dimension,
                    regionX,
                    regionZ,
                    epochSeq,
                    entries
            );
        }
    }

    // =========================================================================
    // Internal Canonical Slab & VarInt Helpers
    // =========================================================================

    private static byte[] serializeCanonicalChunkSlab(
            int chunkX,
            int chunkZ,
            List<SectionWireSlice> sections,
            byte[] heightmapDeltaBytes
    ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeByte(sections.size());
            for (SectionWireSlice slice : sections) {
                writeSectionWireSlice(out, slice);
            }
            byte[] hm = heightmapDeltaBytes == null ? new byte[0] : heightmapDeltaBytes;
            writeVarInt(out, hm.length);
            out.write(hm);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    private static EntryFullChunk deserializeCanonicalChunkSlab(
            byte[] slabBytes,
            long lastUpdateTick,
            long secDedSyndrome64
    ) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(slabBytes))) {
            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            int sectionCount = in.readUnsignedByte();
            List<SectionWireSlice> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                sections.add(readSectionWireSlice(in, i));
            }
            int hmLen = readVarInt(in);
            byte[] hmBytes = new byte[hmLen];
            in.readFully(hmBytes);
            return new EntryFullChunk(chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, sections, hmBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int varIntByteLength(int value) {
        int v = value;
        int bytes = 1;
        while ((v & ~0x7F) != 0) {
            bytes++;
            v >>>= 7;
        }
        return bytes;
    }

    private static int writeVarIntToArray(byte[] target, int offset, int value) {
        int v = value;
        int pos = offset;
        while ((v & ~0x7F) != 0) {
            target[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        target[pos++] = (byte) v;
        return pos;
    }

    private static int readVarIntFromArray(byte[] source, int[] cursor) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            if (cursor[0] >= source.length) {
                throw new IllegalArgumentException("Truncated VarInt in byte array");
            }
            read = source[cursor[0]++];
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt is too big (> 5 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt is too big (> 5 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeVarLong(DataOutputStream out, long value) throws IOException {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.writeByte((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        int numRead = 0;
        long result = 0L;
        byte read;
        do {
            read = in.readByte();
            long value = (read & 0x7FL);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 10) {
                throw new IOException("VarLong is too big (> 10 bytes)");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeDeltaVarIntArray(DataOutputStream out, int[] values) throws IOException {
        writeVarInt(out, values.length);
        int prev = 0;
        for (int v : values) {
            int delta = v - prev;
            int zigZag = (delta << 1) ^ (delta >> 31);
            writeVarInt(out, zigZag);
            prev = v;
        }
    }

    private static int[] readDeltaVarIntArray(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        int[] result = new int[len];
        int prev = 0;
        for (int i = 0; i < len; i++) {
            int zigZag = readVarInt(in);
            int delta = (zigZag >>> 1) ^ -(zigZag & 1);
            prev += delta;
            result[i] = prev;
        }
        return result;
    }

    private static byte[] encodeLcd2PassthroughFrame(byte[] rawBatch) throws IOException {
        return LcaRemoteStreamCodec.encodeLcd2PassthroughFrame(rawBatch);
    }

    private static byte[] encodeLcd2BatchFrame(byte[] rawBatch) throws IOException {
        return LcaRemoteStreamCodec.encodeLcd2BatchFrame(rawBatch);
    }

    private static byte[] decodeLcd2BatchFrame(byte[] src, int maxDecompressedBytes) throws IOException {
        return LcaRemoteStreamCodec.decodeLcd2BatchFrame(src, maxDecompressedBytes);
    }
}
