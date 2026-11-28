package net.leaf.anvil.fabric.client;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side bridge implemented on {@code ClientChunkCache} by {@code MixinClientChunkCache}
 * so inbound {@code LCA_NET_V1} chunk batches can instantiate or update {@code LevelChunk}s
 * directly in {@code ClientChunkCache} without requiring {@code ClientboundLevelChunkWithLightPacket}.
 *
 * @governedBy leafanvil-ADR-003, goldleaf-ADR-005
 * @trace REQ-RTP-S-002, REQ-RTP-S-004, REQ-RTP-S-005
 */
public interface LcaClientChunkInstaller {

    Set<Long> PENDING_LIGHT_ENABLE = ConcurrentHashMap.newKeySet();

    static long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkZ) << 32) | (chunkX & 0xFFFFFFFFL);
    }

    static void markPendingLightEnable(int chunkX, int chunkZ) {
        PENDING_LIGHT_ENABLE.add(packChunkKey(chunkX, chunkZ));
    }

    static boolean consumePendingLightEnable(int chunkX, int chunkZ) {
        return PENDING_LIGHT_ENABLE.remove(packChunkKey(chunkX, chunkZ));
    }

    static void clearPendingLightEnable() {
        PENDING_LIGHT_ENABLE.clear();
    }

    LevelChunk leafanvil$installOrUpdateLcaChunk(
            int chunkX,
            int chunkZ,
            LcaSectionBacking[] backings,
            long lastUpdateTick,
            LcaInMemoryChunkCoordinator coordinator,
            String dimension,
            byte[] heightmapBytes
    );

    default LevelChunk leafanvil$installOrUpdateLcaChunk(
            int chunkX,
            int chunkZ,
            LcaSectionBacking[] backings,
            long lastUpdateTick,
            LcaInMemoryChunkCoordinator coordinator,
            String dimension
    ) {
        return leafanvil$installOrUpdateLcaChunk(
                chunkX, chunkZ, backings, lastUpdateTick, coordinator, dimension, new byte[0]
        );
    }
}
