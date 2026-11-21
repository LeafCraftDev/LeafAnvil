package net.leaf.anvil.fabric;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import net.leaf.anvil.common.NmsRegionStorageInterceptor;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NmsRegionStorageInterceptor} against the real Mojang
 * {@link RegionFileStorage}, {@link ChunkPos}, and {@link CompoundTag} classes from Minecraft 26.1.2
 * (`anvil-api-ADR-002`, `anvil-api-ADR-003`, `rtp-fabric-ADR-002`).
 */
@DisplayName("anvil-api-ADR-003: Real Mojang RegionFileStorage Runtime Interception (.lca)")
public class FabricRealNmsRegionFileStorageTest {

    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: Real net.minecraft.world.level.chunk.storage.RegionFileStorage writes and reads .lca directly")
    void realMojangRegionFileStorageSavesAndLoadsLca(@TempDir Path tempDir) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LcaRegionReader.register();
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        Constructor<RegionFileStorage> ctor = RegionFileStorage.class.getDeclaredConstructor(
                RegionStorageInfo.class, Path.class, boolean.class);
        ctor.setAccessible(true);
        Method writeMethod = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
        writeMethod.setAccessible(true);

        try (LcaRegionFileStorage lcaStorage = new LcaRegionFileStorage(regionDir);
             RegionFileStorage mojangStorage = ctor.newInstance(null, regionDir, false)) {

            assertTrue(NmsRegionStorageInterceptor.installOnRegionFileStorage(mojangStorage, lcaStorage),
                    "Interceptor must attach to real Mojang RegionFileStorage");

            ChunkPos pos = new ChunkPos(5, 9);
            assertNull(mojangStorage.read(pos), "Unwritten chunk must return null");

            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putInt("DataVersion", 4671);
            chunkTag.putInt("xPos", 5);
            chunkTag.putInt("yPos", -4);
            chunkTag.putInt("zPos", 9);
            chunkTag.putString("Status", "minecraft:full");

            ListTag sections = new ListTag();
            CompoundTag sec0 = new CompoundTag();
            sec0.putByte("Y", (byte) 0);
            CompoundTag blockStates = new CompoundTag();
            ListTag palette = new ListTag();
            CompoundTag stone = new CompoundTag();
            stone.putString("Name", "minecraft:stone");
            palette.add(stone);
            blockStates.put("palette", palette);
            sec0.put("block_states", blockStates);
            sections.add(sec0);
            chunkTag.put("sections", sections);

            // Invoke real Mojang RegionFileStorage#write(ChunkPos, CompoundTag)
            writeMethod.invoke(mojangStorage, pos, chunkTag);
            mojangStorage.flush();

            Path lcaFile = regionDir.resolve("r.0.0.lca");
            Path mcaFile = regionDir.resolve("r.0.0.mca");
            assertTrue(Files.exists(lcaFile), "Real Mojang RegionFileStorage must save to r.0.0.lca");
            assertFalse(Files.exists(mcaFile), "Real Mojang RegionFileStorage must not create r.0.0.mca");

            // Invoke real Mojang RegionFileStorage#read(ChunkPos)
            CompoundTag loaded = mojangStorage.read(pos);
            assertNotNull(loaded, "Real Mojang RegionFileStorage must load CompoundTag from r.0.0.lca");
            assertEquals(4671, loaded.getIntOr("DataVersion", 0));
            assertEquals(5, loaded.getIntOr("xPos", 0));
            assertEquals(9, loaded.getIntOr("zPos", 0));
            assertEquals("minecraft:full", loaded.getStringOr("Status", ""));

            // Delete chunk via write(pos, null) -> RegionFile#clear(ChunkPos)
            writeMethod.invoke(mojangStorage, pos, null);
            assertNull(mojangStorage.read(pos), "Cleared chunk must return null from r.0.0.lca");

            // Simulate C2ME's C2MEStorageThread#lambda$writeChunk$1 + lambda$writeChunk$2 pipeline:
            // 1. Retrieve the RegionFile bridge for (7, 11) and read its RegionFileVersion field
            ChunkPos c2mePos = new ChunkPos(7, 11);
            chunkTag.putInt("xPos", 7);
            chunkTag.putInt("zPos", 11);
            Method getRegionFile = RegionFileStorage.class.getDeclaredMethod("getRegionFile", ChunkPos.class);
            getRegionFile.setAccessible(true);
            Object rfBridge = getRegionFile.invoke(mojangStorage, c2mePos);
            assertNotNull(rfBridge);

            java.lang.reflect.Field versionField = net.minecraft.world.level.chunk.storage.RegionFile.class
                    .getDeclaredField("version");
            versionField.setAccessible(true);
            net.minecraft.world.level.chunk.storage.RegionFileVersion rfVersion =
                    (net.minecraft.world.level.chunk.storage.RegionFileVersion) versionField.get(rfBridge);
            assertNotNull(rfVersion);
            assertEquals(NmsRegionStorageInterceptor.LCA_PREENCODED_VERSION_ID, rfVersion.getId(),
                    "Intercepted RegionFile.version must use LCA_PREENCODED_VERSION_ID (0x4C) for C2ME worker pool pre-encoding");

            // 2. Run C2ME's worker-pool supplier logic: [4B len][1B version.getId()][version.wrap(out)]
            java.io.ByteArrayOutputStream rawOut = new java.io.ByteArrayOutputStream(8096);
            rawOut.write(new byte[4]);
            rawOut.write(rfVersion.getId());
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(rfVersion.wrap(rawOut))) {
                net.minecraft.nbt.NbtIo.write(chunkTag, dos);
            }
            byte[] framedBytes = rawOut.toByteArray();
            java.nio.ByteBuffer c2meBuf = java.nio.ByteBuffer.wrap(framedBytes);
            c2meBuf.putInt(0, framedBytes.length - 5 + 1);

            // 3. Commit on C2MEStorageThread via RegionFile#write(ChunkPos, ByteBuffer)
            Method rfWriteBuf = rfBridge.getClass().getDeclaredMethod(
                    "write", ChunkPos.class, java.nio.ByteBuffer.class);
            rfWriteBuf.setAccessible(true);
            rfWriteBuf.invoke(rfBridge, c2mePos, c2meBuf);

            CompoundTag loadedC2me = mojangStorage.read(c2mePos);
            assertNotNull(loadedC2me, "C2ME pre-encoded ChunkFrame slab must round-trip through RegionFileStorage.read");
            assertEquals(7, loadedC2me.getIntOr("xPos", 0));
            assertEquals(11, loadedC2me.getIntOr("zPos", 0));
        }
    }
}
