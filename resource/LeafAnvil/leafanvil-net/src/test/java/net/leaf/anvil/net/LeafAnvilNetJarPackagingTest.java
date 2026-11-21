package net.leaf.anvil.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaging, license isolation, and isolated classloader TX+RX proof for the free `MIT`-licensed
 * {@code LeafAnvil-Net} multi-loader server TX + client RX jar (`leafanvil-ADR-002`,
 * `leafanvil-ADR-003`, `ADR-126`).
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, ADR-115, ADR-126
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
@DisplayName("leafanvil-ADR-003: LeafAnvil-Net MIT Server TX + Client RX Jar Packaging & Disk Isolation Proof")
class LeafAnvilNetJarPackagingTest {

    @Test
    @DisplayName("LeafAnvil-Net shadowJar bundles Paper/Fabric/NeoForge server TX + client RX classes under MIT and excludes commercial disk storage classes")
    void netJarContainsTxAndRxClassesUnderMitAndExcludesCommercialDiskClasses() throws Exception {
        String jarPath = System.getProperty("leafanvil.net.jar.path");
        assertNotNull(jarPath, "System property leafanvil.net.jar.path must be set");
        File jarFile = new File(jarPath);
        assertTrue(jarFile.isFile(), "Built LeafAnvil-Net jar must exist at " + jarPath);

        Set<String> entries = new TreeSet<>();
        String fabricModJson;
        String neoforgeModsToml;
        String fabricMixinsJson;
        String neoforgeMixinsJson;
        String bukkitPluginYml;
        String paperPluginYml;
        String paperPluginClassUtf8;
        try (JarFile jar = new JarFile(jarFile)) {
            var en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                entries.add(entry.getName());
            }
            fabricModJson = readEntryText(jar, "fabric.mod.json");
            neoforgeModsToml = readEntryText(jar, "META-INF/neoforge.mods.toml");
            fabricMixinsJson = readEntryText(jar, "leafanvil-net.fabric.mixins.json");
            neoforgeMixinsJson = readEntryText(jar, "leafanvil-net.neoforge.mixins.json");
            bukkitPluginYml = readEntryText(jar, "plugin.yml");
            paperPluginYml = readEntryText(jar, "paper-plugin.yml");
            paperPluginClassUtf8 = readEntryText(jar, "net/leaf/anvil/paper/PaperLeafAnvilPlugin.class");
        }

        // 1. Required server TX + client RX, runtime, coordinator, Paper plugin, and shaded anvil-api codec classes must be present
        assertTrue(entries.contains("net/leaf/anvil/common/LeafAnvilRuntime.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/LeafAnvilConfig.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/net/LcaClientWireReceiver.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/net/LcaWireStreamProtocol.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/net/LcaPacketDifferentialProfiler.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/net/LcaOrderlessDictTable.class"));
          assertTrue(entries.contains("net/leaf/anvil/common/net/LcaMultiTargetConsolidator.class"));
          assertTrue(entries.contains("net/leaf/anvil/common/net/LcaMultiTargetConsolidator$MultiTargetTcpFanOutRelay.class"));
          assertTrue(entries.contains("net/leaf/anvil/common/LcaInMemoryChunkCoordinator.class"));
        assertTrue(entries.contains("net/leaf/anvil/common/LcaSectionBridge.class"));
        assertTrue(entries.contains("net/leaf/anvil/paper/PaperLeafAnvilPlugin.class"));
        assertTrue(entries.contains("net/leaf/anvil/shaded/io/github/dailystruggle/anvil/LcaSectionBacking.class"));
        assertTrue(entries.contains("META-INF/leafanvil-net/LICENSE-MIT"));
        assertTrue(entries.contains("plugin.yml"));
        assertTrue(entries.contains("paper-plugin.yml"));
        assertTrue(entries.contains("leafanvil-net.fabric.mixins.json"));
        assertTrue(entries.contains("leafanvil-net.neoforge.mixins.json"));
        assertTrue(fabricMixinsJson.contains("\"MixinPlayerChunkSender\""));
        assertTrue(neoforgeMixinsJson.contains("\"MixinPlayerChunkSender\""));
        assertTrue(bukkitPluginYml.contains("name: LeafAnvil-Net"));
        assertFalse(bukkitPluginYml.contains("leafanvil.admin"));
        assertFalse(bukkitPluginYml.contains("zstd-jni"));
        assertTrue(
                entries.stream().noneMatch(e -> e.startsWith("com/github/luben/zstd/")
                        || e.endsWith(".so")
                        || e.endsWith(".dll")
                        || e.endsWith(".dylib")),
                "LeafAnvil-Net jar must be 100% pure Java with zero zstd-jni or native binary payloads"
        );
        assertTrue(paperPluginYml.contains("name: LeafAnvil-Net"));
        assertFalse(paperPluginYml.contains("leafanvil.admin"));
        assertFalse(
                paperPluginClassUtf8.contains("BukkitPluginServerAccessor;"),
                "PaperLeafAnvilPlugin must not hold direct bytecode descriptor references to BukkitPluginServerAccessor"
        );
        assertFalse(
                paperPluginClassUtf8.contains("PaperLcaRegionFileHook;"),
                "PaperLeafAnvilPlugin must not hold direct bytecode descriptor references to PaperLcaRegionFileHook"
        );
        if (entries.stream().anyMatch(e -> e.startsWith("net/leaf/anvil/fabric/"))) {
            assertTrue(entries.contains("net/leaf/anvil/fabric/FabricLeafAnvilMod.class"));
            assertTrue(entries.contains("net/leaf/anvil/fabric/client/FabricLeafAnvilClientMod.class"));
            assertTrue(entries.contains("net/leaf/anvil/fabric/mixin/MixinPlayerChunkSender.class"));
            assertTrue(entries.contains("net/leaf/anvil/fabric/mixin/MixinLevelChunkSection.class"));
            assertTrue(entries.contains("net/leaf/anvil/fabric/mixin/MixinPalettedContainer.class"));
            assertTrue(entries.contains("net/leaf/anvil/fabric/mixin/MixinCompressionEncoder.class"));
            try (JarFile jar = new JarFile(jarFile)) {
                for (String sharedMixin : new String[]{
                        "net/leaf/anvil/fabric/mixin/MixinPalettedContainer.class",
                        "net/leaf/anvil/fabric/mixin/MixinLevelChunkSection.class"
                }) {
                    String mixinUtf8 = readEntryText(jar, sharedMixin);
                    assertFalse(mixinUtf8.contains("FabricLeafAnvilMod"),
                            sharedMixin + " must stay loader-neutral for universal leafanvil.mixins.json");
                    assertFalse(mixinUtf8.contains("net/fabricmc/"),
                            sharedMixin + " must not reference net/fabricmc/* on NeoForge ModuleClassLoader");
                }
            }
        }
        if (entries.stream().anyMatch(e -> e.startsWith("net/leaf/anvil/neoforge/"))) {
            assertTrue(entries.contains("net/leaf/anvil/neoforge/NeoForgeLeafAnvilMod.class"));
            assertFalse(
                    entries.contains("net/leaf/anvil/neoforge/NeoForgeLeafAnvilMod$ServerEntrypoint.class"),
                    "NeoForge @Mod(\"leafanvil\") ServerEntrypoint must be excluded so FML only sees @Mod(\"leafanvil_net\")"
            );
            assertTrue(entries.contains("net/leaf/anvil/neoforge/client/NeoForgeLeafAnvilClientMod.class"));
            assertTrue(entries.contains("net/leaf/anvil/neoforge/client/NeoForgeLeafAnvilClientMod$NetEntrypoint.class"));
            assertTrue(entries.contains("net/leaf/anvil/neoforge/mixin/MixinPlayerChunkSender.class"));
            assertTrue(entries.contains("net/leaf/anvil/neoforge/mixin/MixinLevelChunkSection.class"));
            assertTrue(entries.contains("net/leaf/anvil/neoforge/mixin/MixinPalettedContainer.class"));
            assertTrue(entries.contains("net/leaf/anvil/neoforge/mixin/MixinCompressionEncoder.class"));
            try (JarFile jar = new JarFile(jarFile)) {
                for (String sharedMixin : new String[]{
                        "net/leaf/anvil/neoforge/mixin/MixinPalettedContainer.class",
                        "net/leaf/anvil/neoforge/mixin/MixinLevelChunkSection.class"
                }) {
                    String mixinUtf8 = readEntryText(jar, sharedMixin);
                    assertFalse(mixinUtf8.contains("NeoForgeLeafAnvilMod"),
                            sharedMixin + " must stay loader-neutral for universal leafanvil.mixins.json");
                    assertFalse(mixinUtf8.contains("net/neoforged/"),
                            sharedMixin + " must not reference net/neoforged/*");
                }
            }
        }

        // 2. Descriptors must declare MIT license, universal server+client environment, and leafanvil_net modId
        assertTrue(fabricModJson.contains("\"id\": \"leafanvil_net\""));
        assertTrue(fabricModJson.contains("\"license\": \"MIT\""));
        assertTrue(fabricModJson.contains("\"environment\": \"*\""));
        assertTrue(fabricModJson.contains("\"net.leaf.anvil.fabric.FabricLeafAnvilMod\""));
        assertTrue(fabricModJson.contains("\"net.leaf.anvil.fabric.client.FabricLeafAnvilClientMod\""));
        assertFalse(fabricModJson.contains("LicenseRef-LeafCraft-Commercial"));
        assertFalse(fabricModJson.contains("PolyForm-Noncommercial-1.0.0"));

        assertTrue(neoforgeModsToml.contains("modId = \"leafanvil_net\""));
        assertTrue(neoforgeModsToml.contains("license = \"MIT\""));
        assertTrue(neoforgeModsToml.contains("side = \"BOTH\""));
        assertFalse(neoforgeModsToml.contains("LicenseRef-LeafCraft-Commercial"));
        assertFalse(neoforgeModsToml.contains("PolyForm-Noncommercial-1.0.0"));

        // 3. Commercial on-disk .lca storage interceptor, world converter, /lca admin commands, and commands-api/effects-api must NEVER be present
        for (String entry : entries) {
            assertFalse(
                    entry.startsWith("net/leaf/anvil/common/NmsRegionStorageInterceptor"),
                    "Commercial NmsRegionStorageInterceptor leaked into LeafAnvil-Net: " + entry
            );
            assertFalse(
                    entry.startsWith("net/leaf/anvil/common/LeafAnvilWorldConverter"),
                    "Commercial LeafAnvilWorldConverter leaked into LeafAnvil-Net: " + entry
            );
            assertFalse(
                    entry.startsWith("net/leaf/anvil/common/command/"),
                    "Commercial LeafAnvilCommand leaked into LeafAnvil-Net: " + entry
            );
            assertFalse(
                    entry.contains("commandsapi/"),
                    "Unneeded commands-api class leaked into LeafAnvil-Net: " + entry
            );
            assertFalse(
                    entry.contains("effectsapi/"),
                    "Unneeded effects-api class leaked into LeafAnvil-Net: " + entry
            );
        }
        assertFalse(entries.contains("META-INF/leafanvil/LICENSE"));
    }

    @Test
    @DisplayName("Isolated LeafAnvil-Net.jar classloader runs wire-only server TX + client RX handshake and chunk streaming without disk storage or command classes")
    void isolatedNetJarRunsServerTxAndClientRxWithoutDiskStorageClasses() throws Exception {
        String jarPath = System.getProperty("leafanvil.net.jar.path");
        assertNotNull(jarPath, "System property leafanvil.net.jar.path must be set");
        File jarFile = new File(jarPath);
        assertTrue(jarFile.isFile(), "Built LeafAnvil-Net jar must exist at " + jarPath);

        // Parent-null URLClassLoader (platform classloader only) proves LeafAnvil-Net.jar is 100% self-contained
        try (URLClassLoader isolatedLoader = new URLClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                ClassLoader.getPlatformClassLoader()
        )) {
            Class<?> runtimeCls = Class.forName("net.leaf.anvil.common.LeafAnvilRuntime", true, isolatedLoader);
            Method isDiskAvailable = runtimeCls.getMethod("isDiskStorageAvailable");
            assertEquals(Boolean.FALSE, isDiskAvailable.invoke(null),
                    "LeafAnvil-Net.jar must report isDiskStorageAvailable() == false in isolated classloader");

            // Prove wire-only ServerAccessor + LeafAnvilRuntime#start works without CommandsAPI or EffectsAPI
            Class<?> accessorCls = Class.forName(
                    "net.leaf.anvil.shaded.io.github.dailystruggle.server.api.ServerAccessor", true, isolatedLoader);
            Object wireAccessor = runtimeCls
                    .getMethod("createWireOnlyAccessor", String.class, java.nio.file.Path.class, java.util.logging.Logger.class)
                    .invoke(null, "paper", java.nio.file.Files.createTempDirectory("lca-wire-only"), null);
            Object runtimeInstance = runtimeCls.getConstructor(accessorCls).newInstance(wireAccessor);
            runtimeCls.getMethod("start").invoke(runtimeInstance);
            runtimeCls.getMethod("close").invoke(runtimeInstance);
            Class<?> receiverCls = Class.forName("net.leaf.anvil.common.net.LcaClientWireReceiver", true, isolatedLoader);
            Class<?> protocolCls = Class.forName("net.leaf.anvil.common.net.LcaWireStreamProtocol", true, isolatedLoader);
            Class<?> handshakeCls = Class.forName(
                    "net.leaf.anvil.common.net.LcaWireStreamProtocol$HandshakePayload", true, isolatedLoader);

            Object clientReceiver = receiverCls.getConstructor().newInstance();
            long parityHash = 0x26_1_CAFE_BABE_1234L;
            byte[] clientHandshakeBytes = (byte[]) receiverCls
                    .getMethod("buildClientHandshakeBytes", long.class)
                    .invoke(clientReceiver, parityHash);

            Object decodedClientHs = handshakeCls.getMethod("fromBytes", byte[].class)
                    .invoke(null, (Object) clientHandshakeBytes);
            int allCaps = protocolCls.getField("ALL_CAPABILITIES").getInt(null);
            Object serverSession = protocolCls
                    .getMethod("negotiate", handshakeCls, int.class, long.class)
                    .invoke(null, decodedClientHs, allCaps, parityHash);

            // Server builds handshake ACK and client receiver activates LCA_NET_V1 stream
            Object serverAckPayload = handshakeCls
                    .getConstructor(int.class, int.class, int.class, long.class)
                    .newInstance(1, allCaps, 64 * 1024, parityHash);
            byte[] serverAckBytes = (byte[]) handshakeCls.getMethod("toBytes").invoke(serverAckPayload);

            Object clientNegotiated = receiverCls
                    .getMethod("onServerHandshakeAck", byte[].class, long.class)
                    .invoke(clientReceiver, serverAckBytes, parityHash);
            boolean active = (boolean) clientNegotiated.getClass().getMethod("lcaStreamActive").invoke(clientNegotiated);
            assertTrue(active, "Server + client running LeafAnvil-Net.jar must negotiate active LCA_NET_V1 wire session");
        }
    }

    @Test
    @DisplayName("Regression: non-air client sections with mixed block states (air, leaves, chest, spawner, stone) round-trip losslessly through LcaSectionCodec v2 baseFrame and LcaSectionBacking without collapsing to stateId = 1")
    void nonAirClientSectionsRoundTripMixedStatesWithoutCollapsingToSolidStone() {
        int[] voxels = new int[io.github.dailystruggle.anvil.LcaSectionBacking.BULK_4096_CAPACITY];
        // Majority air (0) section with oak leaves (148), chest (2501), mob spawner (2602), and stone (1)
        int chestPos = io.github.dailystruggle.anvil.LcaSectionBacking.pos12(4, 2, 7);
        int spawnerPos = io.github.dailystruggle.anvil.LcaSectionBacking.pos12(8, 3, 8);
        int stonePos = io.github.dailystruggle.anvil.LcaSectionBacking.pos12(1, 0, 1);
        voxels[chestPos] = 2501;
        voxels[spawnerPos] = 2602;
        voxels[stonePos] = 1;
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 11; z++) {
                voxels[io.github.dailystruggle.anvil.LcaSectionBacking.pos12(x, 12, z)] = 148;
            }
        }
        int expectedNonEmpty = 3 + (9 * 9);

        io.github.dailystruggle.anvil.LcaSectionBacking directBacking =
                io.github.dailystruggle.anvil.LcaSectionBacking.fromDirectVoxels(
                        voxels,
                        15,
                        0,
                        expectedNonEmpty,
                        9 * 9,
                        0,
                        id -> id != 0,
                        id -> id == 148,
                        id -> false
                );
        assertEquals(0, directBacking.uniformStateId(), "Majority state of tree-top/dungeon section must remain air (0)");
        assertEquals(expectedNonEmpty, directBacking.nonEmptyBlockCount());
        assertFalse(directBacking.hasOnlyAir());

        byte[] v2Frame = directBacking.baseFrame();
        assertTrue(v2Frame.length > 0, "Non-uniform section must produce a non-empty LCA_V002 baseFrame");

        // Hydrate via wire protocol (passes uniformStateId = 0 for non-uniform slices)
        net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice wireSlice =
                net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice.nonUniform(4, v2Frame, new int[0]);
        io.github.dailystruggle.anvil.LcaSectionBacking hydrated =
                net.leaf.anvil.common.net.LcaWireStreamProtocol.hydrateDefaultSectionBacking(wireSlice);

        assertFalse(hydrated.hasOnlyAir(), "Hydrated non-air section with majority air must not report hasOnlyAir()");
        assertEquals(expectedNonEmpty, hydrated.nonEmptyBlockCount());
        assertEquals(2501, hydrated.getStateId(4, 2, 7), "Chest block state must not collapse to stone (stateId=1)");
        assertEquals(2602, hydrated.getStateId(8, 3, 8), "Spawner block state must not collapse to stone (stateId=1)");
        assertEquals(148, hydrated.getStateId(7, 12, 7), "Canopy leaf block state must not collapse to stone (stateId=1)");
        assertEquals(0, hydrated.getStateId(0, 0, 0), "Air voxel must not collapse to stone (stateId=1)");
        assertEquals(1, hydrated.getStateId(1, 0, 1));

        int[] decodedAll = new int[io.github.dailystruggle.anvil.LcaSectionBacking.BULK_4096_CAPACITY];
        int resolvedMajority = io.github.dailystruggle.anvil.LcaSectionBacking.decodeCompactBaseFrameInto(
                v2Frame, 0, decodedAll);
        assertEquals(0, resolvedMajority);
        org.junit.jupiter.api.Assertions.assertArrayEquals(voxels, decodedAll);
    }

    private static String readEntryText(JarFile jar, String entryName) throws Exception {
        JarEntry entry = jar.getJarEntry(entryName);
        assertNotNull(entry, "Expected jar entry " + entryName);
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
