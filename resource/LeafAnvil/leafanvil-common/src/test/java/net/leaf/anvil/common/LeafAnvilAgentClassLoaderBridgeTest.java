package net.leaf.anvil.common;

import net.leaf.anvil.agent.LcaAgentSectionCarrier;
import net.leaf.anvil.agent.LeafAnvilAgentBootstrap;
import net.leaf.anvil.agent.LeafAnvilPremain;
import net.leaf.anvil.agent.LeafAnvilSectionClassTransformer;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator.TrackedChunkColumn;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientChunkSlabCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import io.github.dailystruggle.anvil.LcaSectionBacking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated multi-`URLClassLoader` verification suite for `net.leaf.anvil.agent` (`leafanvil-ADR-005`).
 *
 * <p>Proves Paperclip `AppClassLoader` `premain` safety (zero `org.bukkit.*` or `:anvil-api`
 * classpath dependencies), load-time JVMS transformation of NMS-shaped `LevelChunkSection` and
 * `SerializableChunkData`, zero-`LinkageError` `LcaAgentSectionCarrier` sharing across sibling
 * plugin classloaders, `(x & 15, y & 15, z & 15)` local coordinate masking, and structured
 * `WARNING` fallback on exotic fork descriptor drift (`REQ-RTP-S-004`).</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LeafAnvilAgentClassLoaderBridgeTest {

    public static final class SyntheticBlockState {
        public final int runtimeId;

        public SyntheticBlockState(int runtimeId) {
            this.runtimeId = runtimeId;
        }
    }

    public static final class SyntheticCompoundTag {
        public final String source;

        public SyntheticCompoundTag(String source) {
            this.source = source;
        }
    }

    public static final class SyntheticLevel {}

    public static final class SyntheticChunk {}

    public static final class SyntheticHeight {}

    public static final class SyntheticFactory {}

    /**
     * NMS-shaped `LevelChunkSection` fixture containing branches (`StackMapTable` frames) and
     * scalar non-empty/ticking counters mirroring Paper/Moonrise `LevelChunkSection`.
     */
    public static class LevelChunkSection {
        private final SyntheticBlockState[] states = new SyntheticBlockState[4096];
        private final SyntheticBlockState air = new SyntheticBlockState(0);
        public int nonEmptyCount;
        public int moonriseTickingCount;

        public LevelChunkSection() {
            for (int i = 0; i < states.length; i++) {
                states[i] = air;
            }
        }

        public SyntheticBlockState setBlockState(
                int x, int y, int z, SyntheticBlockState state, boolean useLocks
        ) {
            int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            SyntheticBlockState prev = states[idx];
            states[idx] = state;
            if (prev.runtimeId == 0 && state.runtimeId != 0) {
                nonEmptyCount++;
            } else if (prev.runtimeId != 0 && state.runtimeId == 0) {
                nonEmptyCount--;
            }
            if ((state.runtimeId & 1) == 1 && (prev.runtimeId & 1) == 0) {
                moonriseTickingCount++;
            } else if ((state.runtimeId & 1) == 0 && (prev.runtimeId & 1) == 1) {
                moonriseTickingCount--;
            }
            return useLocks ? prev : states[idx];
        }
    }

    /**
     * NMS-shaped `SerializableChunkData` fixture with `write()`, `copyOf(...)`, and `parse(...)`.
     */
    public static class SerializableChunkData {
        public final String origin;
        public static final AtomicInteger VANILLA_WRITE_CALLS = new AtomicInteger();
        public static final AtomicInteger VANILLA_PARSE_CALLS = new AtomicInteger();

        public SerializableChunkData(String origin) {
            this.origin = origin;
        }

        public static SerializableChunkData copyOf(SyntheticLevel level, SyntheticChunk chunk) {
            return new SerializableChunkData(level != null && chunk != null ? "vanilla-copyOf" : "null-copyOf");
        }

        public static SerializableChunkData parse(
                SyntheticHeight height, SyntheticFactory factory, SyntheticCompoundTag tag
        ) {
            VANILLA_PARSE_CALLS.incrementAndGet();
            return new SerializableChunkData("vanilla-parse:" + (tag != null ? tag.source : "null"));
        }

        public SyntheticCompoundTag write() {
            VANILLA_WRITE_CALLS.incrementAndGet();
            return new SyntheticCompoundTag("vanilla-write:" + origin);
        }
    }

    /**
     * Exotic-fork `LevelChunkSection` with an incompatible `setBlockState` signature to verify
     * fail-safe `WARNING` fallback (`REQ-RTP-S-004`).
     */
    public static class ExoticForkSection {
        public Object setBlockState(long packedBlockPos, Object state) {
            return state;
        }
    }

    private static final AtomicReference<Class<?>> TRANSFORMED_CODEC_CLASS_REF = new AtomicReference<>();

    @AfterEach
    void cleanupHooks() {
        TRANSFORMED_CODEC_CLASS_REF.set(null);
        LeafAnvilAgentBootstrap.clearHooks();
        LeafAnvilAgentBootstrap.resetFallbackWarningsForTest();
        LeafAnvilAgentBootstrap.resetAgentActiveForTest();
    }

    @Test
    @DisplayName("Paperclip AppClassLoader premain boots net.leaf.anvil.agent with zero org.bukkit / :anvil-api classes on classpath")
    void paperclipAppClassLoaderPremainIsolation(@TempDir Path tempDir) throws Exception {
        Path agentOnlyJar = tempDir.resolve("agent-only.jar");
        String[] agentClasses = {
                "net/leaf/anvil/agent/LcaAgentSectionCarrier.class",
                "net/leaf/anvil/agent/LeafAnvilAgentBootstrap.class",
                "net/leaf/anvil/agent/LeafAnvilAgentBootstrap$CarrierState.class",
                "net/leaf/anvil/agent/LeafAnvilPremain.class",
                "net/leaf/anvil/agent/LeafAnvilSectionClassTransformer.class",
                "net/leaf/anvil/agent/LeafAnvilSectionClassTransformer$TargetKind.class",
                "net/leaf/anvil/agent/LeafAnvilSectionClassTransformer$MemberBytes.class",
                "net/leaf/anvil/agent/LeafAnvilSectionClassTransformer$MethodEntry.class",
                "net/leaf/anvil/agent/LeafAnvilSectionClassTransformer$ParsedClassFile.class"
        };
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(agentOnlyJar))) {
            for (String entryName : agentClasses) {
                byte[] bytes = readClassBytes(entryName);
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(bytes);
                jos.closeEntry();
            }
        }

        AtomicReference<ClassFileTransformer> registeredTransformer = new AtomicReference<>();
        AtomicReference<java.util.jar.JarFile> capturedBootstrapJar = new AtomicReference<>();
        Instrumentation mockInst = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("addTransformer".equals(method.getName()) && args != null && args.length >= 1) {
                            registeredTransformer.set((ClassFileTransformer) args[0]);
                            return null;
                        }
                        if ("appendToBootstrapClassLoaderSearch".equals(method.getName())
                                && args != null && args.length == 1
                                && args[0] instanceof java.util.jar.JarFile jf) {
                            capturedBootstrapJar.set(jf);
                            return null;
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return null;
                    }
                }
        );

        try (URLClassLoader isolatedAppLoader = new URLClassLoader(
                new URL[] {agentOnlyJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()
        )) {
            Class<?> premainCls = Class.forName("net.leaf.anvil.agent.LeafAnvilPremain", true, isolatedAppLoader);
            assertSame(isolatedAppLoader, premainCls.getClassLoader());
            Method premainMethod = premainCls.getMethod("premain", String.class, Instrumentation.class);
            premainMethod.invoke(null, "", mockInst);

            boolean active = (boolean) premainCls.getMethod("isAgentActive").invoke(null);
            assertTrue(active, "LeafAnvilPremain.isAgentActive() must be true after premain");
            assertNotNull(registeredTransformer.get(), "premain must register LeafAnvilSectionClassTransformer");

            java.util.jar.JarFile bridgeJar = capturedBootstrapJar.get();
            assertNotNull(bridgeJar, "premain must register a bootstrap bridge JarFile via appendToBootstrapClassLoaderSearch");
            Path bridgeJarPath = Path.of(bridgeJar.getName());
            assertNotNull(bridgeJar.getJarEntry("net/leaf/anvil/agent/LcaAgentSectionCarrier.class"));
            assertNotNull(bridgeJar.getJarEntry("net/leaf/anvil/agent/LeafAnvilAgentBootstrap.class"));
            assertNotNull(bridgeJar.getJarEntry("net/leaf/anvil/agent/LeafAnvilAgentBootstrap$CarrierState.class"));
            assertNull(bridgeJar.getJarEntry("net/leaf/anvil/agent/LeafAnvilPremain.class"),
                    "Bridge jar must only contain pure carrier/bootstrap classes");
            bridgeJar.close();

            String prefix = "net/leaf/anvil/common/LeafAnvilAgentClassLoaderBridgeTest$";
            LeafAnvilSectionClassTransformer localTransformer = new LeafAnvilSectionClassTransformer(prefix);
            byte[] transformedSec = localTransformer.transformLevelChunkSection(
                    prefix + "LevelChunkSection", readClassBytes(prefix + "LevelChunkSection.class"));
            assertNotNull(transformedSec);

            try (URLClassLoader platformPlusBootstrapLoader = new URLClassLoader(
                    new URL[] {bridgeJarPath.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader()
            )) {
                TransformingChildLoader paperclipServerLoader = new TransformingChildLoader(platformPlusBootstrapLoader);
                paperclipServerLoader.define(
                        LeafAnvilAgentClassLoaderBridgeTest.class.getName(),
                        readClassBytes("net/leaf/anvil/common/LeafAnvilAgentClassLoaderBridgeTest.class"));
                paperclipServerLoader.define(
                        SyntheticBlockState.class.getName(),
                        readClassBytes(prefix + "SyntheticBlockState.class"));
                Class<?> serverSecCls = paperclipServerLoader.define(
                        LevelChunkSection.class.getName(), transformedSec);
                Object serverSecInstance = serverSecCls.getConstructor().newInstance();
                Class<?> carrierCls = Class.forName(
                        "net.leaf.anvil.agent.LcaAgentSectionCarrier", true, paperclipServerLoader);
                assertSame(platformPlusBootstrapLoader, carrierCls.getClassLoader());
                assertTrue(carrierCls.isInstance(serverSecInstance),
                        "PlatformClassLoader-parented Paperclip ServerClassLoader must link LcaAgentSectionCarrier without NoClassDefFoundError");

                try (URLClassLoader pluginLoader = new URLClassLoader(
                        new URL[] {agentOnlyJar.toUri().toURL()},
                        paperclipServerLoader
                )) {
                    Class<?> pluginCarrierCls = Class.forName(
                            "net.leaf.anvil.agent.LcaAgentSectionCarrier", true, pluginLoader);
                    assertSame(carrierCls, pluginCarrierCls,
                            "PluginClassLoader and ServerClassLoader must share identical LcaAgentSectionCarrier");
                    Class<?> pluginPremainCls = Class.forName(
                            "net.leaf.anvil.agent.LeafAnvilPremain", true, pluginLoader);
                    assertSame(pluginLoader, pluginPremainCls.getClassLoader());
                    boolean pluginSeesActive = (boolean) pluginPremainCls.getMethod("isAgentActive").invoke(null);
                    assertTrue(pluginSeesActive,
                            "PluginClassLoader copy of LeafAnvilPremain must still observe isAgentActive() == true via bootstrap bridge");
                }
            }
        }
    }

    @Test
    @DisplayName("Enclosed cave section at Y >= 128 encodes skyConst4 = 0 instead of 15 in LcaInMemoryChunkCoordinator")
    void enclosedCaveAtHighAltitudeEncodesZeroSkyConst() {
        LcaInMemoryChunkCoordinator coordinator =
                new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(1024 * 1024));
        try {
            LcaSectionBridge.RawSectionSnapshot[] snaps = new LcaSectionBridge.RawSectionSnapshot[24];
            long[] rawWords = new long[256];
            java.util.Arrays.fill(rawWords, 0x1111111111111111L);
            rawWords[69] &= ~(0xFL << 16);
            snaps[14] = new LcaSectionBridge.RawSectionSnapshot(
                    14, false, 0, 4, rawWords, new int[] {0, 1}, 4095, 0, 0);

            assertTrue(coordinator.submitChunkSnapshotForCompaction(
                    "world", 0, 0, new Object(), new Object[24], snaps, 15, 1L,
                    id -> id != 0, id -> false, id -> false));
            assertTrue(coordinator.awaitPendingBackgroundCompactions(5_000L));

            AtomicReference<LcaSectionBacking> builtCaveSec = new AtomicReference<>();
            coordinator.drainCompletedChunkCompactions(4, completed -> builtCaveSec.set(completed.builtBackings()[14]));
            assertNotNull(builtCaveSec.get());
            byte[] baseFrame = builtCaveSec.get().baseFrame();
            assertTrue(baseFrame.length >= 2);
            int cursor = 0;
            while (cursor < baseFrame.length && (baseFrame[cursor++] & 0x80) != 0) {
                // skip VarInt majorityStateId
            }
            int packedLightByte = baseFrame[cursor] & 0xFF;
            int skyConst4 = (packedLightByte >>> 4) & 0xF;
            assertEquals(0, skyConst4,
                    "Enclosed cave section at sectionIndex=14 (Y >= 128) must encode skyConst4 = 0 instead of 15");
        } finally {
            coordinator.shutdownCompactionPool();
        }
    }

    @Test
    @DisplayName("Transformed LevelChunkSection & SerializableChunkData bridge across ServerClassLoader and sibling PluginClassLoaders with zero LinkageError")
    void transformedNmsClassesBridgeAcrossSiblingClassLoadersAndCoordinator() throws Exception {
        String prefix = "net/leaf/anvil/common/LeafAnvilAgentClassLoaderBridgeTest$";
        LeafAnvilSectionClassTransformer transformer = new LeafAnvilSectionClassTransformer(prefix);

        byte[] rawSectionBytes = readClassBytes(prefix + "LevelChunkSection.class");
        byte[] transformedSectionBytes = transformer.transformLevelChunkSection(
                prefix + "LevelChunkSection", rawSectionBytes);
        assertNotNull(transformedSectionBytes, "LevelChunkSection fixture must be transformed");

        byte[] rawCodecBytes = readClassBytes(prefix + "SerializableChunkData.class");
        byte[] transformedCodecBytes = transformer.transformChunkCodecClass(
                prefix + "SerializableChunkData", rawCodecBytes);
        assertNotNull(transformedCodecBytes, "SerializableChunkData fixture must be transformed");

        // Define the enclosing NestHost alongside the transformed NMS fixture classes in the child ServerClassLoader
        // so JVMS §5.4.4 NestHost/NestMembers share the same defining ServerClassLoader while net.leaf.anvil.agent.*
        // resolves from the parent AppClassLoader!
        ClassLoader parentAppLoader = getClass().getClassLoader();
        TransformingChildLoader serverClassLoader = new TransformingChildLoader(parentAppLoader);
        byte[] outerHostBytes = readClassBytes("net/leaf/anvil/common/LeafAnvilAgentClassLoaderBridgeTest.class");
        serverClassLoader.define(LeafAnvilAgentClassLoaderBridgeTest.class.getName(), outerHostBytes);
        Class<?> transformedSectionCls = serverClassLoader.define(
                LevelChunkSection.class.getName(), transformedSectionBytes);
        Class<?> transformedCodecCls = serverClassLoader.define(
                SerializableChunkData.class.getName(), transformedCodecBytes);
        TRANSFORMED_CODEC_CLASS_REF.set(transformedCodecCls);

        // Create two sibling child URLClassLoaders simulating shaded LeafAnvil and unshaded LeafGen PluginClassLoaders
        try (URLClassLoader leafAnvilPluginLoader = new URLClassLoader(new URL[0], serverClassLoader);
             URLClassLoader leafGenPluginLoader = new URLClassLoader(new URL[0], serverClassLoader)) {

            assertSame(
                    Class.forName(LcaAgentSectionCarrier.class.getName(), false, leafAnvilPluginLoader),
                    Class.forName(LcaAgentSectionCarrier.class.getName(), false, leafGenPluginLoader),
                    "Both isolated PluginClassLoaders must resolve the identical AppClassLoader LcaAgentSectionCarrier"
            );

            Object sectionInstance = transformedSectionCls.getConstructor().newInstance();
            LcaAgentSectionCarrier carrier = assertInstanceOf(LcaAgentSectionCarrier.class, sectionInstance);

            // LeafGen mounts a Stage K3 .lca baseFrame directly onto the section via LcaAgentSectionCarrier
            byte[] stageK3Frame = new byte[] {0x10, 0x02, 0x01, 0x05, 0x7F};
            carrier.leafanvil$mountBaseFrame(stageK3Frame, 0, 4671, 0xCAFE_BABE_1234_5678L);
            assertArrayEquals(stageK3Frame, carrier.leafanvil$getBaseFrame());
            assertEquals(4671, carrier.leafanvil$getDataVersion());
            assertEquals(0xCAFE_BABE_1234_5678L, carrier.leafanvil$getBaselineSecDed64());
            assertEquals(0, carrier.leafanvil$getDirtyCount());

            // Wire LeafAnvil coordinator + MethodHandle hooks
            LcaInMemoryChunkCoordinator coordinator = new LcaInMemoryChunkCoordinator(new ClientChunkSlabCache(64));
            try {
                LcaSectionBacking[] backings = new LcaSectionBacking[] {
                        new LcaSectionBacking(stageK3Frame, 0)
                };
                TrackedChunkColumn col = coordinator.attachChunkColumn(
                        "minecraft:overworld", 2, -3, backings, new Object[] {sectionInstance}, 100L, false);
                assertEquals(0, coordinator.dirtyChunkCount());

                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle stateResolver = lookup.findStatic(
                        LeafAnvilAgentClassLoaderBridgeTest.class,
                        "extractSyntheticStateId",
                        MethodType.methodType(int.class, Object.class)
                );
                AtomicInteger listenerCalls = new AtomicInteger();
                MethodHandle mutationHook = lookup.bind(
                        new MutationForwarder(coordinator, listenerCalls),
                        "onMutate",
                        MethodType.methodType(void.class, Object.class, int.class, int.class, int.class)
                );
                MethodHandle serializeHook = lookup.findStatic(
                        LeafAnvilAgentClassLoaderBridgeTest.class,
                        "directSerializeHook",
                        MethodType.methodType(Object.class, Object.class, Object.class)
                );
                MethodHandle hydrateHook = lookup.findStatic(
                        LeafAnvilAgentClassLoaderBridgeTest.class,
                        "directHydrateHook",
                        MethodType.methodType(Object.class, Object.class, Object.class, Object.class)
                );
                LeafAnvilAgentBootstrap.registerBlockStateIdResolver(stateResolver);
                LeafAnvilAgentBootstrap.registerChunkCodecHooks(serializeHook, hydrateHook, mutationHook);

                Method setBlockState = transformedSectionCls.getMethod(
                        "setBlockState", int.class, int.class, int.class, SyntheticBlockState.class, boolean.class);

                // Pass unmasked world coordinates (18, -3, 35) -> must mask to (2, 13, 3)
                SyntheticBlockState prev = (SyntheticBlockState) setBlockState.invoke(
                        sectionInstance, 18, -3, 35, new SyntheticBlockState(77), true);
                assertEquals(0, prev.runtimeId);
                assertEquals(1, transformedSectionCls.getField("nonEmptyCount").getInt(sectionInstance));
                assertEquals(1, transformedSectionCls.getField("moonriseTickingCount").getInt(sectionInstance));
                assertEquals(1, carrier.leafanvil$getDirtyCount());
                assertEquals(1, listenerCalls.get());
                assertEquals(1, coordinator.dirtyChunkCount(), "Inline setBlockState hook must admit chunk into DirtyChunkLruCache");
                assertEquals(77, col.section(0).getStateId(2, 13, 3));
                assertFalse(col.section(0).hasHydratedCompactBaseIndex(), "Agent write must leave CompactBaseIndex == null");

                // 100 repeated overwrites of same coordinate keep dirtyCount == 1
                for (int r = 0; r < 100; r++) {
                    setBlockState.invoke(sectionInstance, 2, 13, 3, new SyntheticBlockState(80 + (r & 1)), true);
                }
                assertEquals(1, carrier.leafanvil$getDirtyCount());

                // Verify SerializableChunkData direct serialize & hydrate hooks bypass vanilla write/parse when active
                Object codecInstance = transformedCodecCls.getConstructor(String.class).newInstance("direct-lca");
                Method writeMethod = transformedCodecCls.getMethod("write");
                SyntheticCompoundTag directTag = (SyntheticCompoundTag) writeMethod.invoke(codecInstance);
                assertEquals("bypassed-compound-tag", directTag.source);

                Object fallbackInstance = transformedCodecCls.getConstructor(String.class).newInstance("use-vanilla");
                SyntheticCompoundTag fallbackTag = (SyntheticCompoundTag) writeMethod.invoke(fallbackInstance);
                assertEquals("vanilla-write:use-vanilla", fallbackTag.source);

                Method parseMethod = transformedCodecCls.getMethod(
                        "parse", SyntheticHeight.class, SyntheticFactory.class, SyntheticCompoundTag.class);
                Object directParsed = parseMethod.invoke(
                        null, new SyntheticHeight(), new SyntheticFactory(), new SyntheticCompoundTag("lca-fast"));
                assertEquals("hydrated-from-lca", transformedCodecCls.getField("origin").get(directParsed));
            } finally {
                coordinator.shutdownCompactionPool();
            }
        }
    }

    @Test
    @DisplayName("Exotic NMS fork with altered LevelChunkSection descriptor logs one structured WARNING (REQ-RTP-S-004) and skips transformation cleanly")
    void exoticNmsDescriptorLogsStructuredWarningAndSkipsCleanly() throws Exception {
        LeafAnvilAgentBootstrap.resetFallbackWarningsForTest();
        String internalName = "net/leaf/anvil/common/LeafAnvilAgentClassLoaderBridgeTest$ExoticForkSection";
        byte[] rawBytes = readClassBytes(internalName + ".class");
        LeafAnvilSectionClassTransformer transformer = new LeafAnvilSectionClassTransformer();

        byte[] out1 = transformer.transformLevelChunkSection(internalName, rawBytes);
        byte[] out2 = transformer.transformLevelChunkSection(internalName, rawBytes);
        assertNull(out1, "Mismatched descriptor must skip bytecode modification cleanly");
        assertNull(out2);
        assertEquals(1L, LeafAnvilAgentBootstrap.fallbackWarningCount(),
                "Repeated mismatch on same class must emit exactly one structured WARNING");
    }

    public static int extractSyntheticStateId(Object stateObj) {
        return (stateObj instanceof SyntheticBlockState s) ? s.runtimeId : 0;
    }

    public static Object directSerializeHook(Object selfOrLevel, Object chunkOrNull) throws Exception {
        if (selfOrLevel != null && chunkOrNull == null) {
            String origin = (String) selfOrLevel.getClass().getField("origin").get(selfOrLevel);
            if ("direct-lca".equals(origin)) {
                return new SyntheticCompoundTag("bypassed-compound-tag");
            }
        }
        return null;
    }

    public static Object directHydrateHook(Object h, Object f, Object tagObj) throws Exception {
        if (tagObj instanceof SyntheticCompoundTag tag && "lca-fast".equals(tag.source)) {
            Class<?> cls = TRANSFORMED_CODEC_CLASS_REF.get();
            if (cls != null) {
                return cls.getConstructor(String.class).newInstance("hydrated-from-lca");
            }
        }
        return null;
    }

    public static final class MutationForwarder {
        private final LcaInMemoryChunkCoordinator coordinator;
        private final AtomicInteger counter;

        MutationForwarder(LcaInMemoryChunkCoordinator coordinator, AtomicInteger counter) {
            this.coordinator = coordinator;
            this.counter = counter;
        }

        public void onMutate(Object section, int pos12, int prevId, int newId) {
            counter.incrementAndGet();
            coordinator.handleAgentSectionMutation(section, pos12, prevId, newId, 105L);
        }
    }

    private static final class TransformingChildLoader extends ClassLoader {
        TransformingChildLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }

    private static byte[] readClassBytes(String resourcePath) throws IOException {
        try (InputStream in = LeafAnvilAgentClassLoaderBridgeTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Missing class resource: " + resourcePath);
            return in.readAllBytes();
        }
    }
}
