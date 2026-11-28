package net.leaf.anvil.fabric.mixin;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.leaf.anvil.common.net.LcaClientWireReceiver;
import net.leaf.anvil.fabric.client.LcaClientChunkInstaller;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Client-only {@link ClientChunkCache} Mixin enabling direct {@code LevelChunk} instantiation
 * and registration from inbound {@code LCA_NET_V1} chunk batches without requiring a preceding
 * vanilla {@code ClientboundLevelChunkWithLightPacket} ({@code leafanvil-ADR-003},
 * {@code goldleaf-ADR-005}).
 *
 * @governedBy leafanvil-ADR-003, goldleaf-ADR-005, rtp-fabric-ADR-002, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache implements LcaClientChunkInstaller {

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    private static boolean isValidChunk(LevelChunk chunk, int x, int z) {
        throw new AssertionError();
    }

    @Unique
    private static final Heightmap.Types[] LEAFANVIL$WIRE_HEIGHTMAP_TYPES = {
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR
    };

    @Unique
    private static volatile boolean leafanvil$reflectionInit;
    @Unique
    private static Field leafanvil$storageField;
    @Unique
    private static Field leafanvil$chunksField;
    @Unique
    private static Method leafanvil$inRangeMethod;
    @Unique
    private static Method leafanvil$getIndexMethod;
    @Unique
    private static Method leafanvil$replaceMethod;
    @Unique
    private static Method leafanvil$refreshEmptyMethod;

    @Unique
    private static void leafanvil$ensureStorageReflection() throws ReflectiveOperationException {
        if (leafanvil$reflectionInit) {
            return;
        }
        synchronized (MixinClientChunkCache.class) {
            if (leafanvil$reflectionInit) {
                return;
            }
            Field storageF = ClientChunkCache.class.getDeclaredField("storage");
            storageF.setAccessible(true);
            Class<?> storageCls = storageF.getType();
            Field chunksF = storageCls.getDeclaredField("chunks");
            chunksF.setAccessible(true);
            Method inRangeM = storageCls.getDeclaredMethod("inRange", int.class, int.class);
            inRangeM.setAccessible(true);
            Method getIndexM = storageCls.getDeclaredMethod("getIndex", int.class, int.class);
            getIndexM.setAccessible(true);
            Method replaceM = storageCls.getDeclaredMethod("replace", int.class, LevelChunk.class);
            replaceM.setAccessible(true);
            Method refreshM = storageCls.getDeclaredMethod("refreshEmptySections", LevelChunk.class);
            refreshM.setAccessible(true);

            leafanvil$storageField = storageF;
            leafanvil$chunksField = chunksF;
            leafanvil$inRangeMethod = inRangeM;
            leafanvil$getIndexMethod = getIndexM;
            leafanvil$replaceMethod = replaceM;
            leafanvil$refreshEmptyMethod = refreshM;
            leafanvil$reflectionInit = true;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public LevelChunk leafanvil$installOrUpdateLcaChunk(
            int chunkX,
            int chunkZ,
            LcaSectionBacking[] backings,
            long lastUpdateTick,
            LcaInMemoryChunkCoordinator coordinator,
            String dimension,
            byte[] heightmapBytes
    ) {
        if (backings == null || backings.length == 0 || coordinator == null || this.level == null) {
            return null;
        }
        try {
            leafanvil$ensureStorageReflection();
            Object storageObj = leafanvil$storageField.get(this);
            if (storageObj == null || !(boolean) leafanvil$inRangeMethod.invoke(storageObj, chunkX, chunkZ)) {
                return null;
            }
            int index = (int) leafanvil$getIndexMethod.invoke(storageObj, chunkX, chunkZ);
            AtomicReferenceArray<LevelChunk> chunks =
                    (AtomicReferenceArray<LevelChunk>) leafanvil$chunksField.get(storageObj);
            LevelChunk chunk = chunks.get(index);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            boolean created = false;
            if (!isValidChunk(chunk, chunkX, chunkZ)) {
                chunk = new LevelChunk(this.level, pos);
                created = true;
            }
            LevelChunkSection[] sections = chunk.getSections();
            if (sections == null || sections.length == 0) {
                return null;
            }
            coordinator.attachChunkColumn(dimension, chunkX, chunkZ, backings, sections, lastUpdateTick, true);
            leafanvil$installWireHeightmaps(chunk, heightmapBytes);
            if (created) {
                leafanvil$replaceMethod.invoke(storageObj, index, chunk);
            } else {
                leafanvil$refreshEmptyMethod.invoke(storageObj, chunk);
            }
            // Defer enableChunkLight until after ClientboundLightUpdatePacket runs applyLightData
            // inside ClientLevel#queueLightUpdate (matching vanilla handleLevelChunkWithLight order).
            LcaClientChunkInstaller.markPendingLightEnable(chunkX, chunkZ);
            this.level.onChunkLoaded(pos);
            return chunk;
        } catch (Throwable t) {
            LcaClientWireReceiver.logWarning(
                    "[LeafAnvil-Client] Failed installing LCA_NET_V1 chunk ("
                            + chunkX + "," + chunkZ + ") into ClientChunkCache: " + t.getMessage(),
                    t
            );
            return null;
        }
    }

    @Unique
    private static void leafanvil$installWireHeightmaps(LevelChunk chunk, byte[] heightmapBytes) {
        if (heightmapBytes == null || heightmapBytes.length < 3) {
            return;
        }
        if (heightmapBytes[0] != (byte) 'H' || heightmapBytes[1] != (byte) 1) {
            return;
        }
        int count = heightmapBytes[2] & 0xFF;
        ByteBuffer buf = ByteBuffer.wrap(heightmapBytes, 3, heightmapBytes.length - 3);
        for (int i = 0; i < count && buf.remaining() >= 3; i++) {
            int typeIdx = buf.get() & 0xFF;
            int longCount = buf.getShort() & 0xFFFF;
            if (buf.remaining() < (longCount << 3)) {
                return;
            }
            long[] raw = new long[longCount];
            for (int j = 0; j < longCount; j++) {
                raw[j] = buf.getLong();
            }
            if (typeIdx < LEAFANVIL$WIRE_HEIGHTMAP_TYPES.length) {
                chunk.setHeightmap(LEAFANVIL$WIRE_HEIGHTMAP_TYPES[typeIdx], raw);
            }
        }
    }

    @Inject(method = "drop", at = @At("HEAD"), require = 0)
    private void leafanvil$onDropChunkClearPendingLight(ChunkPos pos, CallbackInfo ci) {
        if (pos != null) {
            LcaClientChunkInstaller.consumePendingLightEnable(pos.x(), pos.z());
        }
    }
}
