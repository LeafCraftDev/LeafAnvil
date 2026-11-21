package net.leaf.anvil.neoforge.mixin;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.RawChunkFrameSnapshot;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.DirectCoWSaveTicket;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;

/**
 * Direct `SerializableChunkData` <-> `.lca` `ChunkFrame` + sparse `DirtyChunkLruCache` Mixin on
 * NeoForge (`leafanvil-ADR-005` Workstream 2, `leafanvil-ADR-006`).
 *
 * <p>Avoids adding non-static fields onto the Java `record` `SerializableChunkData` by tracking
 * pending {@link DirectCoWSaveTicket}s in a weak identity map, suppressing 24-section
 * `PalettedContainer#copy()` voxel inflation during `copyOf` and committing section-spliced CoW
 * slabs directly during off-thread `write()` without constructing `CompoundTag("sections")` trees.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy rtp-neoforge-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@Mixin(SerializableChunkData.class)
public abstract class MixinSerializableChunkData {

    @Unique
    private static final Map<SerializableChunkData, DirectCoWSaveTicket> LEAFANVIL_DIRECT_TICKETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Unique
    private static final ThreadLocal<DirectCoWSaveTicket> LEAFANVIL_PENDING_COPY_TICKET =
            new ThreadLocal<>();

    @Inject(method = "copyOf", at = @At("HEAD"), require = 0)
    private static void leafanvil$beforeCopyOf(
            ServerLevel level,
            ChunkAccess chunk,
            CallbackInfoReturnable<SerializableChunkData> cir
    ) {
        LEAFANVIL_PENDING_COPY_TICKET.remove();
        LcaSectionBridge.setSuppressCopyHydrationForDirectSave(false);
        if (level == null || chunk == null || chunk.getPersistedStatus() != ChunkStatus.FULL) {
            return;
        }
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt == null) {
            return;
        }
        String worldKey = level.dimension().identifier().toString();
        ChunkPos pos = chunk.getPos();
        DirectCoWSaveTicket ticket = rt.chunkCoordinator()
                .captureDirectCoWSaveTicket(worldKey, pos.x(), pos.z());
        if (ticket != null) {
            LEAFANVIL_PENDING_COPY_TICKET.set(ticket);
            LcaSectionBridge.setSuppressCopyHydrationForDirectSave(true);
        }
    }

    @Inject(method = "copyOf", at = @At("RETURN"), require = 0)
    private static void leafanvil$afterCopyOf(
            ServerLevel level,
            ChunkAccess chunk,
            CallbackInfoReturnable<SerializableChunkData> cir
    ) {
        LcaSectionBridge.setSuppressCopyHydrationForDirectSave(false);
        DirectCoWSaveTicket ticket = LEAFANVIL_PENDING_COPY_TICKET.get();
        LEAFANVIL_PENDING_COPY_TICKET.remove();
        SerializableChunkData result = cir.getReturnValue();
        if (ticket != null && result != null) {
            LEAFANVIL_DIRECT_TICKETS.put(result, ticket);
        }
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onWrite(CallbackInfoReturnable<CompoundTag> cir) {
        SerializableChunkData self = (SerializableChunkData) (Object) this;
        DirectCoWSaveTicket ticket = LEAFANVIL_DIRECT_TICKETS.remove(self);
        if (ticket == null) {
            return;
        }
        if (self.blendingData() != null
                || self.belowZeroRetrogen() != null
                || self.carvingMask() != null
                || self.attachmentData() != null
                || self.auxLightData() != null
                || (self.blockEntities() != null && !self.blockEntities().isEmpty())
                || (self.entities() != null && !self.entities().isEmpty())) {
            return;
        }
        RawChunkFrameSnapshot coldSnap = ticket.column() != null ? ticket.column().coldSnapshot() : null;
        if (coldSnap == null || self.lightCorrect() != coldSnap.hasIsLightOn()) {
            return;
        }
        int flushMask = ticket.flushMask();
        if (flushMask != 0 && self.sectionData() != null) {
            int secCount = ticket.column().sectionCount();
            byte[][] skyOverrides = new byte[secCount][];
            byte[][] blkOverrides = new byte[secCount][];
            int minSecY = self.minSectionY();
            int expandedLightMask = (flushMask | (flushMask << 1) | (flushMask >>> 1))
                    & ((secCount >= 32) ? -1 : ((1 << secCount) - 1));
            for (SerializableChunkData.SectionData sd : self.sectionData()) {
                int idx = sd.y() - minSecY;
                if (idx >= 0 && idx < secCount && (expandedLightMask & (1 << idx)) != 0) {
                    if (sd.skyLight() != null && !sd.skyLight().isEmpty()) {
                        skyOverrides[idx] = sd.skyLight().getData().clone();
                    }
                    if (sd.blockLight() != null && !sd.blockLight().isEmpty()) {
                        blkOverrides[idx] = sd.blockLight().getData().clone();
                    }
                }
            }
            ticket = ticket.withSectionLightOverrides(skyOverrides, blkOverrides, expandedLightMask);
        }
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt == null) {
            return;
        }
        try {
            if (rt.chunkCoordinator().commitDirectCoWSaveTicketSync(ticket)) {
                CompoundTag marker = new CompoundTag();
                RawChunkFrameSnapshot snap = ticket.column().coldSnapshot();
                marker.putInt("DataVersion", snap != null ? snap.dataVersion() : rt.config().defaultDataVersion());
                marker.putInt("xPos", self.chunkPos().x());
                marker.putInt("yPos", self.minSectionY());
                marker.putInt("zPos", self.chunkPos().z());
                marker.putString("Status", "minecraft:full");
                marker.putByte(LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG, (byte) 1);
                cir.setReturnValue(marker);
            }
        } catch (Throwable t) {
            rt.accessor().log(
                    Level.WARNING,
                    "[LeafAnvil] Direct SerializableChunkData CoW commit failed for "
                            + self.chunkPos() + "; falling back to standard NBT serializer: " + t.getMessage(),
                    t
            );
        }
    }

    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private void leafanvil$onReadReturn(
            ServerLevel level,
            PoiManager poiManager,
            RegionStorageInfo regionInfo,
            ChunkPos pos,
            CallbackInfoReturnable<ProtoChunk> cir
    ) {
        ProtoChunk proto = cir.getReturnValue();
        if (level == null || pos == null || proto == null) {
            return;
        }
        LeafAnvilRuntime rt = LeafAnvilRuntime.activeInstance();
        if (rt == null) {
            return;
        }
        String worldKey = level.dimension().identifier().toString();
        LcaInMemoryChunkCoordinator coord = rt.chunkCoordinator();
        LcaInMemoryChunkCoordinator.WorldVoxelStorageBinding binding = coord.getWorldVoxelStorageBinding(worldKey);
        if (binding == null || binding.voxelStorage() == null) {
            return;
        }
        try {
            RawChunkFrameSnapshot snap = binding.voxelStorage()
                    .readRawChunkFrame(pos.x(), pos.z(), binding.canonicalToRuntimeId());
            if (snap != null && snap.sectionCount() == proto.getSections().length) {
                coord.attachColdChunkFrameColumn(
                        worldKey,
                        pos.x(),
                        pos.z(),
                        snap,
                        proto.getSections(),
                        level.getGameTime(),
                        false
                );
            }
        } catch (Throwable t) {
            rt.accessor().log(
                    Level.WARNING,
                    "[LeafAnvil] Cold ChunkFrame mount on SerializableChunkData#read failed for "
                            + pos + ": " + t.getMessage(),
                    t
            );
        }
    }
}
