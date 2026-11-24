package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LinearRegionReader;
import io.github.dailystruggle.bit.PbitResult;
import io.github.dailystruggle.server.api.AbstractModServerAccessor;
import io.github.dailystruggle.server.api.ManagedBlockingIoExecutor;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.TaskHandle;
import io.github.dailystruggle.server.api.TaskScheduler;
import net.leaf.anvil.common.bit.LeafAnvilBitSuite;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Cross-platform lifecycle and `.lca` (LeafCraft Anvil) world storage coordinator for {@code LeafAnvil}
 * across Paper, Spigot, Folia, Fabric, and NeoForge ({@code leafanvil-ADR-001}, {@code leafanvil-ADR-002},
 * {@code leafanvil-ADR-003}, {@code anvil-api-ADR-002}, {@code anvil-api-ADR-003}).
 *
 * <p>When packaged inside the free {@code MIT}-licensed {@code LeafAnvil-Net} artifact (where
 * {@code NmsRegionStorageInterceptor}, {@code LeafAnvilWorldConverter}, and {@code LeafAnvilCommand}
 * are excluded), {@link #isDiskStorageAvailable()} returns {@code false} and the runtime operates
 * strictly in wire-only {@code LCA_NET_V1} TX + in-memory section compaction mode without modifying
 * on-disk world storage files.</p>
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 * @trace REQ-RTP-S-007
 */
public final class LeafAnvilRuntime implements AutoCloseable {

    private static final String INTERCEPTOR_CLASS_NAME =
            "net.leaf.anvil.common.NmsRegionStorageInterceptor";
    private static final String INTERCEPTOR_RESOURCE_PATH =
            "net/leaf/anvil/common/NmsRegionStorageInterceptor.class";
    private static final String COMMAND_CLASS_NAME =
            "net.leaf.anvil.common.command.LeafAnvilCommand";
    private static final String COMMAND_RESOURCE_PATH =
            "net/leaf/anvil/common/command/LeafAnvilCommand.class";
    private static final boolean DISK_STORAGE_AVAILABLE = probeClassResourceAvailable(INTERCEPTOR_RESOURCE_PATH);
    private static final boolean ADMIN_COMMAND_AVAILABLE = probeClassResourceAvailable(COMMAND_RESOURCE_PATH);
    private static final AtomicBoolean GLOBAL_SERVER_TX_REGISTERED = new AtomicBoolean(false);

    private static volatile LeafAnvilRuntime activeInstance;

    private final ServerAccessor accessor;
    private final Map<String, LcaRegionFileStorage.LcaWorldStorageSession> worldSessions = new ConcurrentHashMap<>();
    private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean batchTaskRunning = new AtomicBoolean(false);
    private final AtomicReference<BatchTaskHandle> activeBatchTask = new AtomicReference<>();
    private final AtomicBoolean diskStorageDisabled = new AtomicBoolean(false);
    private final AtomicBoolean sectionWireDisabled = new AtomicBoolean(false);
    private final AtomicBoolean loadAborted = new AtomicBoolean(false);
    private final LcaWireStreamProtocol.ClientChunkSlabCache wireStreamCache =
            new LcaWireStreamProtocol.ClientChunkSlabCache();
    private final ManagedBlockingIoExecutor fallbackIoExecutor =
            new ManagedBlockingIoExecutor("LeafAnvil-Fallback");
    private final LcaInMemoryChunkCoordinator chunkCoordinator;
    private final LeafAnvilBitSuite bitSuite;

    private volatile LeafAnvilConfig config = LeafAnvilConfig.defaults();
    private volatile boolean started = false;

    public LeafAnvilRuntime(ServerAccessor accessor) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.chunkCoordinator = new LcaInMemoryChunkCoordinator(
                wireStreamCache,
                LcaInMemoryChunkCoordinator.detectC2mePresent(),
                blockingIoExecutor());
        this.bitSuite = new LeafAnvilBitSuite(this);
        activeInstance = this;
        prewarmAnvilClasses();
        LcaRegionReader.register();
        LinearRegionReader.registerIfAvailable();
    }

    private static boolean probeClassResourceAvailable(String resourcePath) {
        ClassLoader cl = LeafAnvilRuntime.class.getClassLoader();
        return cl != null && cl.getResource(resourcePath) != null;
    }

    /**
     * Returns {@code true} when the commercial on-disk `.lca` {@code NmsRegionStorageInterceptor}
     * is packaged in the current runtime jar, or {@code false} when running in wire-only
     * {@code LeafAnvil-Net} TX+RX mode.
     */
    public static boolean isDiskStorageAvailable() {
        return DISK_STORAGE_AVAILABLE;
    }

    /**
     * Atomically claims the process-wide server TX registration slot so installing both
     * {@code LeafAnvil.jar} and {@code LeafAnvil-Net.jar} on the same server never double-hooks
     * server chunk lifecycle or {@code LCA_NET_V1} payload channels.
     */
    public static boolean tryAcquireGlobalServerTxRegistration() {
        return GLOBAL_SERVER_TX_REGISTERED.compareAndSet(false, true);
    }

    /**
     * Resets the process-wide server TX registration latch for unit tests.
     */
    public static void resetGlobalServerTxRegistrationForTests() {
        GLOBAL_SERVER_TX_REGISTERED.set(false);
    }

    public static LeafAnvilRuntime activeInstance() {
        return activeInstance;
    }

    /**
     * Creates a zero-command, zero-effects {@link ServerAccessor} for wire-only
     * {@code LeafAnvil-Net} execution so the jar never needs {@code commands-api},
     * {@code effects-api}, or full platform accessor bindings.
     */
    public static ServerAccessor createWireOnlyAccessor(
            String platform,
            Path rootFolder,
            java.util.logging.Logger logger
    ) {
        final String resolvedPlatform = (platform != null && !platform.isBlank()) ? platform : "paper";
        final Path resolvedRoot = (rootFolder != null) ? rootFolder : Path.of("config", "leafanvil");
        final java.util.logging.Logger resolvedLogger =
                (logger != null) ? logger : java.util.logging.Logger.getLogger("LeafAnvil-Net");
        return new ServerAccessor() {
            @Override
            public String getServerVersion() {
                return "26.1";
            }

            @Override
            public String getPlatform() {
                return resolvedPlatform;
            }

            @Override
            public Integer getServerIntVersion() {
                return 26;
            }

            @Override
            public Path getRootFolder() {
                return resolvedRoot;
            }

            @Override
            public java.io.File getPluginDirectory() {
                return resolvedRoot.toFile();
            }

            @Override
            public void log(Level level, String message) {
                resolvedLogger.log(level != null ? level : Level.INFO, message);
            }

            @Override
            public void log(Level level, String message, Throwable throwable) {
                resolvedLogger.log(level != null ? level : Level.WARNING, message, throwable);
            }

            private final ManagedBlockingIoExecutor wireIoExecutor =
                    new ManagedBlockingIoExecutor("LeafAnvil-Wire");

            @Override
            public Executor blockingIoExecutor() {
                return wireIoExecutor;
            }

            @Override
            public boolean shutdownBlockingIo(Duration timeout) {
                return wireIoExecutor.shutdownAndDrain(timeout);
            }
        };
    }

    /**
     * Eagerly loads and initializes all `LeafAnvil` and `anvil-api` classes from the plugin/mod
     * code source at startup so that shutdown chunk saves (`MinecraftServer#stopServer` ->
     * `saveAllChunks`) running after `PaperPluginClassLoader` closes its `JarFile` never trigger
     * late classfile lookups on a closed zip file.
     */
    private static void prewarmAnvilClasses() {
        ClassLoader cl = LeafAnvilRuntime.class.getClassLoader();
        try {
            java.security.CodeSource cs = LeafAnvilRuntime.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path codePath = Path.of(cs.getLocation().toURI());
                if (java.nio.file.Files.isRegularFile(codePath)) {
                    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(codePath.toFile())) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jf.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (!name.endsWith(".class") || name.contains("module-info")) {
                                continue;
                            }
                            if (name.startsWith("net/leaf/anvil/common/")
                                    || name.contains("/dailystruggle/anvil/")
                                    || name.startsWith("io/github/dailystruggle/anvil/")) {
                                String binaryName = name.substring(0, name.length() - 6).replace('/', '.');
                                try {
                                    Class.forName(binaryName, true, cl);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Loads or reloads `config.yml` and `messages.yml` from the platform data directory.
     */
    public void load() {
        try {
            this.config = LeafAnvilConfig.loadOrCreate(accessor.getRootFolder());
        } catch (IOException e) {
            accessor.log(Level.WARNING, "[LeafAnvil] Failed to load config.yml; using defaults: " + e.getMessage(), e);
            this.config = LeafAnvilConfig.defaults();
        }
        this.chunkCoordinator.setMaxServerUploadKbps(this.config.maxServerUploadKbps());
    }

    /**
     * Starts PBIT self-tests, command registration (`/leafanvil`, `/lca`), and tick execution for
     * `CommandsAPI` when on-disk `.lca` admin commands are packaged in the jar.
     */
    public void start() {
        if (started) {
            return;
        }
        load();
        PbitResult pbit = bitSuite.executePbit();
        if (!pbit.passed()) {
            accessor.log(Level.SEVERE, "[LCA bit] " + pbit.diagnosticMessage());
            if (shouldAbortLoad()) {
                started = false;
                return;
            }
        }
        started = true;

        if (!ADMIN_COMMAND_AVAILABLE || !isDiskStorageOperational()) {
            return;
        }
        Runnable executeTick = registerAdminCommandsIfAvailable();
        if (executeTick != null) {
            Runnable tickTask = () -> {
                if (started) {
                    executeTick.run();
                }
            };
            accessor.scheduler().ifPresentOrElse(
                    s -> {
                        TaskHandle handle = s.runSyncTimer(tickTask, 1L, 1L);
                        if (handle != null) {
                            subscriptions.add(handle::cancel);
                        }
                    },
                    () -> accessor.scheduleRepeatingTask(tickTask, 1L, 1L)
            );
        }
    }

    private Runnable registerAdminCommandsIfAvailable() {
        ClassLoader cl = LeafAnvilRuntime.class.getClassLoader();
        Class<?> cmdClass = bitSuite.verifyRequiredClass(
                LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                "LeafAnvilRuntime",
                COMMAND_CLASS_NAME,
                cl);
        if (cmdClass == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cmdClass.getConstructor(String.class, ServerAccessor.class, LeafAnvilRuntime.class);
            Object rootCmd = ctor.newInstance("leafanvil", accessor, this);
            accessor.registerCommands(rootCmd, "lca");
            Object aliasCmd = ctor.newInstance("lca", accessor, this);
            accessor.registerCommands(aliasCmd);
            Method executeMethod = resolveCommandsApiExecuteMethod(cmdClass);
            if (executeMethod == null) {
                return () -> {
                };
            }
            return () -> {
                try {
                    executeMethod.invoke(null);
                } catch (Throwable t) {
                    accessor.log(Level.WARNING, "[LeafAnvil] CommandsAPI tick failed: " + t.getMessage(), t);
                }
            };
        } catch (Throwable t) {
            bitSuite.recordIncompatibility(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                    "LeafAnvilRuntime",
                    COMMAND_CLASS_NAME,
                    "Incompatible LeafAnvilCommand constructor or registration: " + t.getMessage(),
                    "LeafAnvilCommand(String, ServerAccessor, LeafAnvilRuntime)");
            return null;
        }
    }

    private Method resolveCommandsApiExecuteMethod(Class<?> cmdClass) {
        ClassLoader cl = cmdClass.getClassLoader();
        if (cl == null) {
            return null;
        }
        try {
            if (cl.getResource("io/github/dailystruggle/commandsapi/common/CommandsAPI.class") != null) {
                Class<?> apiClass = Class.forName(
                        "io.github.dailystruggle.commandsapi.common.CommandsAPI", true, cl);
                return apiClass.getMethod("execute");
            }
            if (cl.getResource("net/leaf/anvil/shaded/io/github/dailystruggle/commandsapi/common/CommandsAPI.class") != null) {
                Class<?> shadedClass = Class.forName(
                        "net.leaf.anvil.shaded.io.github.dailystruggle.commandsapi.common.CommandsAPI", true, cl);
                return shadedClass.getMethod("execute");
            }
        } catch (Throwable t) {
            bitSuite.recordIncompatibility(
                    LeafAnvilBitSuite.LCA_004_NETWORK_CARRIER_COMPAT,
                    "LeafAnvilRuntime",
                    "CommandsAPI#execute",
                    "Incompatible CommandsAPI class or execute() method: " + t.getMessage(),
                    "CommandsAPI.execute() present");
        }
        return null;
    }

    /**
     * Attaches `.lca` storage (`region/`, `entities/`, `poi/`) to a world and intercepts its
     * live NMS `RegionFileStorage` instances when {@link #isDiskStorageOperational()} is true.
     */
    public LcaRegionFileStorage.LcaWorldStorageSession attachAndInterceptWorld(
            String worldName,
            Path dimensionDirectory,
            int minSectionY,
            int sectionCount,
            Object worldOrServerLevelHandle
    ) {
        if (worldName == null || dimensionDirectory == null) {
            throw new IllegalArgumentException("worldName and dimensionDirectory must not be null");
        }
        if (!DISK_STORAGE_AVAILABLE || diskStorageDisabled.get() || loadAborted.get()) {
            return null;
        }
        String key = worldName.trim().toLowerCase(Locale.ROOT);
        LeafAnvilConfig cfg = this.config;
        Path resolvedDimDir = invokeResolveDimensionDirectory(
                worldOrServerLevelHandle, dimensionDirectory);
        LcaRegionFileStorage.LcaWorldStorageSession session = worldSessions.computeIfAbsent(
                key,
                k -> new LcaRegionFileStorage.LcaWorldStorageSession(
                        resolvedDimDir,
                        minSectionY,
                        sectionCount,
                        cfg.defaultDataVersion(),
                        cfg.lcd2Level(),
                        cfg.autoMigrateLegacy())
        );
        if (cfg.enabled() && worldOrServerLevelHandle != null) {
            int hooked = invokeInterceptWorldStorages(worldOrServerLevelHandle, session);
            bitSuite.recordWorldInterception(key, hooked);
            if (hooked <= 0) {
                worldSessions.remove(key);
                try {
                    session.close();
                } catch (IOException closeEx) {
                    accessor.log(
                            Level.WARNING,
                            "[LeafAnvil] Failed to close unattached world session '" + worldName + "': " + closeEx.getMessage(),
                            closeEx);
                }
                return null;
            }
            accessor.log(Level.INFO, "[LeafAnvil] Attached .lca storage to world '" + worldName
                    + "' (" + resolvedDimDir + ").");
        }
        return session;
    }

    private Path invokeResolveDimensionDirectory(Object worldOrServerLevelHandle, Path fallbackDirectory) {
        Class<?> interceptorClass = bitSuite.verifyRequiredClass(
                LeafAnvilBitSuite.LCA_002_DISK_STORAGE_COMPAT,
                "LeafAnvilRuntime",
                INTERCEPTOR_CLASS_NAME,
                LeafAnvilRuntime.class.getClassLoader());
        if (interceptorClass == null) {
            return fallbackDirectory;
        }
        try {
            Method resolveMethod = interceptorClass.getMethod(
                    "resolveDimensionDirectory", Object.class, Path.class);
            Object resolved = resolveMethod.invoke(null, worldOrServerLevelHandle, fallbackDirectory);
            return resolved instanceof Path p ? p : fallbackDirectory;
        } catch (Throwable t) {
            bitSuite.recordIncompatibility(
                    LeafAnvilBitSuite.LCA_002_DISK_STORAGE_COMPAT,
                    "LeafAnvilRuntime",
                    INTERCEPTOR_CLASS_NAME + "#resolveDimensionDirectory",
                    "Failed to invoke resolveDimensionDirectory: " + t.getMessage(),
                    "resolveDimensionDirectory(Object, Path)");
            return fallbackDirectory;
        }
    }

    private int invokeInterceptWorldStorages(
            Object worldOrServerLevelHandle,
            LcaRegionFileStorage.LcaWorldStorageSession session
    ) {
        Class<?> interceptorClass = bitSuite.verifyRequiredClass(
                LeafAnvilBitSuite.LCA_002_DISK_STORAGE_COMPAT,
                "LeafAnvilRuntime",
                INTERCEPTOR_CLASS_NAME,
                LeafAnvilRuntime.class.getClassLoader());
        if (interceptorClass == null) {
            return 0;
        }
        try {
            Method interceptMethod = interceptorClass.getMethod(
                    "interceptWorldStorages",
                    Object.class,
                    LcaRegionFileStorage.LcaWorldStorageSession.class
            );
            Object count = interceptMethod.invoke(null, worldOrServerLevelHandle, session);
            return count instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            bitSuite.recordIncompatibility(
                    LeafAnvilBitSuite.LCA_002_DISK_STORAGE_COMPAT,
                    "LeafAnvilRuntime",
                    INTERCEPTOR_CLASS_NAME + "#interceptWorldStorages",
                    "RegionFileStorage interception failed: " + t.getMessage(),
                    "interceptWorldStorages(Object, LcaWorldStorageSession)");
            return 0;
        }
    }

    public LcaRegionFileStorage.LcaWorldStorageSession getSession(String worldName) {
        if (worldName == null) {
            return null;
        }
        return worldSessions.get(worldName.trim().toLowerCase(Locale.ROOT));
    }

    public Map<String, LcaRegionFileStorage.LcaWorldStorageSession> worldSessions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(worldSessions));
    }

    public LeafAnvilBitSuite bitSuite() {
        return bitSuite;
    }

    public boolean isDiskStorageOperational() {
        return DISK_STORAGE_AVAILABLE && config.enabled() && !diskStorageDisabled.get() && !loadAborted.get();
    }

    public boolean isSectionWireOperational() {
        return !sectionWireDisabled.get() && !loadAborted.get();
    }

    public boolean shouldAbortLoad() {
        if (loadAborted.get()) {
            return true;
        }
        boolean diskCanOperate = DISK_STORAGE_AVAILABLE && !diskStorageDisabled.get();
        boolean wireCanOperate = !sectionWireDisabled.get();
        return !diskCanOperate && !wireCanOperate;
    }

    public void disableDiskStorage(String reason) {
        if (diskStorageDisabled.compareAndSet(false, true)) {
            accessor.log(Level.SEVERE, "[LeafAnvil] Disabling .lca on-disk storage function due to BIT incompatibility: " + reason);
        }
        if (sectionWireDisabled.get()) {
            loadAborted.set(true);
        }
    }

    public void disableSectionAndWireStreaming(String reason) {
        if (sectionWireDisabled.compareAndSet(false, true)) {
            accessor.log(Level.SEVERE, "[LeafAnvil] Disabling in-memory section compaction and LCA_NET_V1 wire streaming due to BIT incompatibility: " + reason);
        }
        if (!DISK_STORAGE_AVAILABLE || diskStorageDisabled.get()) {
            loadAborted.set(true);
        }
    }

    public void handleCarrierIncompatibility(String targetPath, String reason) {
        String p = targetPath != null ? targetPath : "";
        if (p.contains("BukkitPluginServerAccessor")) {
            disableDiskStorage(reason);
        } else if (p.contains("Networking") || p.contains("ConnectionEvents") || p.contains("Payload")) {
            disableSectionAndWireStreaming(reason);
        } else if (!DISK_STORAGE_AVAILABLE) {
            disableAllFunctions(reason);
        }
    }

    public void disableAllFunctions(String reason) {
        diskStorageDisabled.set(true);
        sectionWireDisabled.set(true);
        if (loadAborted.compareAndSet(false, true)) {
            accessor.log(Level.SEVERE, "[LeafAnvil] Aborting LeafAnvil runtime due to fatal BIT failure: " + reason);
        }
    }

    public void flushWorld(String worldName) {
        LcaRegionFileStorage.LcaWorldStorageSession session = getSession(worldName);
        if (session != null) {
            try {
                session.flush();
            } catch (IOException e) {
                accessor.log(Level.WARNING, "[LeafAnvil] Failed to flush world '" + worldName + "': " + e.getMessage(), e);
            }
        }
    }

    public void flushAll() {
        for (Map.Entry<String, LcaRegionFileStorage.LcaWorldStorageSession> e : worldSessions.entrySet()) {
            try {
                e.getValue().flush();
            } catch (IOException ex) {
                accessor.log(Level.WARNING, "[LeafAnvil] Failed to flush world '" + e.getKey() + "': " + ex.getMessage(), ex);
            }
        }
    }

    public void detachWorld(String worldName) {
        if (worldName == null) {
            return;
        }
        String key = worldName.trim().toLowerCase(Locale.ROOT);
        bitSuite.clearWorldInterception(key);
        chunkCoordinator.clearWorld(key);
        LcaRegionFileStorage.LcaWorldStorageSession session = worldSessions.remove(key);
        if (session != null) {
            invokeUninstallWorldStorages(session);
            try {
                session.close();
            } catch (IOException e) {
                accessor.log(Level.WARNING, "[LeafAnvil] Failed to close world session '" + worldName + "': " + e.getMessage(), e);
            }
        }
    }

    private void invokeUninstallWorldStorages(LcaRegionFileStorage.LcaWorldStorageSession session) {
        try {
            Class<?> interceptorClass = Class.forName(
                    INTERCEPTOR_CLASS_NAME, false, LeafAnvilRuntime.class.getClassLoader());
            Method uninstallMethod = interceptorClass.getMethod(
                    "uninstallWorldStorages", LcaRegionFileStorage.LcaWorldStorageSession.class);
            uninstallMethod.invoke(null, session);
        } catch (ReflectiveOperationException e) {
            accessor.log(
                    Level.FINE,
                    "[LeafAnvil] Could not restore intercepted caches on detach: " + e.getMessage(),
                    e);
        }
    }

    public LeafAnvilConfig config() {
        return config;
    }

    public ServerAccessor accessor() {
        return accessor;
    }

    /**
     * Returns the bounded `.lca` chunk wire-stream slab LRU cache (`leafanvil-ADR-003` Section 4).
     */
    public LcaWireStreamProtocol.ClientChunkSlabCache wireStreamCache() {
        return wireStreamCache;
    }

    /**
     * Returns the in-memory `LcaSectionBacking` and wire-stream coordinator (`leafanvil-ADR-002` & `leafanvil-ADR-003`).
     */
    public LcaInMemoryChunkCoordinator chunkCoordinator() {
        return chunkCoordinator;
    }

    /**
     * Negotiates a client `.lca` chunk streaming session against the server's capabilities and
     * 64-bit block-state registry parity hash, falling back to
     * {@link LcaWireStreamProtocol.NegotiatedSession#VANILLA_FALLBACK} for unmodified vanilla
     * clients or mismatched registries (`leafanvil-ADR-003` Section 1).
     */
    public LcaWireStreamProtocol.NegotiatedSession negotiateWireSession(
            LcaWireStreamProtocol.HandshakePayload clientHandshake,
            long serverRegistryHash64
    ) {
        if (!isSectionWireOperational()) {
            return LcaWireStreamProtocol.NegotiatedSession.VANILLA_FALLBACK;
        }
        return LcaWireStreamProtocol.negotiate(
                clientHandshake,
                LcaWireStreamProtocol.ALL_CAPABILITIES,
                serverRegistryHash64
        );
    }

    /**
     * Negotiates a client `.lca` chunk streaming session against explicit server capability flags
     * and 64-bit block-state registry parity hash (`leafanvil-ADR-003` Section 1).
     */
    public LcaWireStreamProtocol.NegotiatedSession negotiateWireSession(
            LcaWireStreamProtocol.HandshakePayload clientHandshake,
            int serverCapabilities,
            long serverRegistryHash64
    ) {
        if (!isSectionWireOperational()) {
            return LcaWireStreamProtocol.NegotiatedSession.VANILLA_FALLBACK;
        }
        return LcaWireStreamProtocol.negotiate(
                clientHandshake,
                serverCapabilities,
                serverRegistryHash64
        );
    }

    /**
     * Lock-free handle for inspecting, pausing, resuming, or cooperatively cancelling an active
     * scheduler-pulsed batch storage task ({@code REQ-RTP-S-002}, {@code REQ-RTP-S-005}).
     */
    public static final class BatchTaskHandle {
        private final String operationName;
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<TaskHandle> timerHandle = new AtomicReference<>();
        private final AtomicReference<Runnable> resumeTrigger = new AtomicReference<>();
        private volatile String progressSummary = "starting";

        public BatchTaskHandle(String operationName) {
            this.operationName = (operationName == null || operationName.isBlank()) ? "storage" : operationName;
        }

        public String operationName() {
            return operationName;
        }

        public boolean isPaused() {
            return paused.get();
        }

        public boolean pause() {
            return paused.compareAndSet(false, true);
        }

        public boolean resume() {
            if (paused.compareAndSet(true, false)) {
                Runnable trigger = resumeTrigger.get();
                if (trigger != null && !cancelled.get()) {
                    trigger.run();
                }
                return true;
            }
            return false;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean cancel() {
            if (cancelled.compareAndSet(false, true)) {
                paused.set(false);
                releaseTimerHandle();
                Runnable trigger = resumeTrigger.get();
                if (trigger != null) {
                    trigger.run();
                }
                return true;
            }
            return false;
        }

        public void bindTimerHandle(TaskHandle handle) {
            TaskHandle prev = timerHandle.getAndSet(handle);
            if (prev != null && prev != handle) {
                prev.cancel();
            }
            if (cancelled.get() && handle != null) {
                handle.cancel();
            }
        }

        public void bindResumeTrigger(Runnable trigger) {
            resumeTrigger.set(trigger);
        }

        public void releaseTimerHandle() {
            TaskHandle h = timerHandle.getAndSet(null);
            if (h != null) {
                h.cancel();
            }
        }

        public String progressSummary() {
            return progressSummary;
        }

        public void updateProgressSummary(String summary) {
            this.progressSummary = (summary == null || summary.isBlank()) ? "running" : summary;
        }
    }

    public BatchTaskHandle activeBatchTask() {
        return activeBatchTask.get();
    }

    /**
     * Returns the lifecycle-managed blocking-I/O {@link Executor} from {@link ServerAccessor},
     * falling back to a runtime-owned {@link ManagedBlockingIoExecutor} (drained in
     * {@link #close()}) only when a bare/test accessor provides neither an I/O executor nor a
     * scheduler.
     */
    public Executor blockingIoExecutor() {
        try {
            Executor exec = accessor.blockingIoExecutor();
            return exec != null ? exec : fallbackIoExecutor;
        } catch (IllegalStateException ignored) {
            return fallbackIoExecutor;
        }
    }

    /**
     * Dispatches asynchronous work via {@link ServerAccessor#scheduler()} (`TaskScheduler#runAsync`),
     * falling back to {@link #blockingIoExecutor()} when no scheduler is attached so work never
     * blocks primary/region threads or {@code ForkJoinPool.commonPool()}.
     */
    public void dispatchAsyncWork(Runnable work) {
        Objects.requireNonNull(work, "work");
        accessor.scheduler().ifPresentOrElse(
                s -> s.runAsync(work),
                () -> {
                    if (accessor.isPrimaryThread()) {
                        CompletableFuture.runAsync(work, blockingIoExecutor())
                                .exceptionally(ex -> {
                                    accessor.log(
                                            Level.WARNING,
                                            "[LeafAnvil] Unhandled async storage worker failure: " + ex.getMessage(),
                                            ex);
                                    return null;
                                });
                    } else {
                        work.run();
                    }
                }
        );
    }

    /**
     * Schedules a tick-cadenced asynchronous pulse via {@link ServerAccessor#scheduler()}
     * (`TaskScheduler#runAsyncTimer`), or via {@link AbstractModServerAccessor#scheduleRepeatingTask}
     * + {@link #blockingIoExecutor()} on modded carriers (`REQ-RTP-S-002`, `REQ-RTP-S-005`).
     */
    public Optional<TaskHandle> scheduleAsyncPulseTimer(Runnable pulse, long delayTicks, long periodTicks) {
        Objects.requireNonNull(pulse, "pulse");
        Optional<TaskScheduler> sched = accessor.scheduler();
        if (sched.isPresent()) {
            return Optional.of(sched.get().runAsyncTimer(pulse, delayTicks, periodTicks));
        }
        if (accessor instanceof AbstractModServerAccessor modAccessor) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Executor ioExec = blockingIoExecutor();
            modAccessor.scheduleRepeatingTask(
                    () -> {
                        if (!cancelled.get()) {
                            ioExec.execute(pulse);
                        }
                    },
                    delayTicks,
                    periodTicks);
            return Optional.of(new TaskHandle() {
                @Override
                public void cancel() {
                    cancelled.set(true);
                }

                @Override
                public boolean isCancelled() {
                    return cancelled.get();
                }
            });
        }
        return Optional.empty();
    }

    /**
     * Starts a multi-pulse asynchronous batch storage task (`convert` or `heal`) whose single-flight
     * lease remains active across scheduler pulses until {@code releaseLease} runs (`REQ-RTP-S-002`,
     * `REQ-RTP-S-005`, `REQ-RTP-S-007`).
     */
    public boolean beginPulsedBatchTask(
            UUID callerId,
            String operationName,
            BiConsumer<BatchTaskHandle, Runnable> starter
    ) {
        Objects.requireNonNull(starter, "starter");
        if (!batchTaskRunning.compareAndSet(false, true)) {
            accessor.sendMessage(callerId, config.msgBusy(), true);
            return false;
        }
        BatchTaskHandle handle = new BatchTaskHandle(operationName);
        activeBatchTask.set(handle);
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseLease = () -> {
            if (released.compareAndSet(false, true)) {
                handle.releaseTimerHandle();
                activeBatchTask.compareAndSet(handle, null);
                batchTaskRunning.set(false);
            }
        };
        dispatchAsyncWork(() -> {
            try {
                starter.accept(handle, releaseLease);
            } catch (Throwable t) {
                releaseLease.run();
                accessor.log(Level.WARNING, "[LeafAnvil] Async storage task failed: " + t.getMessage(), t);
                accessor.sendMessage(callerId, "[LeafAnvil] Error during storage operation: " + t.getMessage(), true);
            }
        });
        return true;
    }

    /**
     * Runs an asynchronous batch storage task (`convert`, `compact`, or `heal`), enforcing the
     * single-flight busy guard (`REQ-RTP-S-005`, `REQ-RTP-S-007`).
     */
    public boolean runBatchTaskAsync(UUID callerId, Runnable task) {
        Objects.requireNonNull(task, "task");
        return beginPulsedBatchTask(callerId, "storage", (handle, releaseLease) -> {
            try {
                task.run();
            } finally {
                releaseLease.run();
            }
        });
    }

    @Override
    public void close() {
        started = false;
        BatchTaskHandle runningBatch = activeBatchTask.get();
        if (runningBatch != null) {
            runningBatch.cancel();
        }
        chunkCoordinator.shutdownCompactionPool();
        accessor.shutdownBlockingIo(Duration.ofSeconds(5));
        fallbackIoExecutor.shutdownAndDrain(Duration.ofSeconds(2));
        for (AutoCloseable sub : subscriptions) {
            try {
                sub.close();
            } catch (Exception e) {
                accessor.log(Level.FINE, "[LeafAnvil] Error closing subscription: " + e.getMessage(), e);
            }
        }
        subscriptions.clear();

        Collection<LcaRegionFileStorage.LcaWorldStorageSession> sessions = new ArrayList<>(worldSessions.values());
        worldSessions.clear();
        for (LcaRegionFileStorage.LcaWorldStorageSession session : sessions) {
            try {
                session.close();
            } catch (IOException e) {
                accessor.log(Level.WARNING, "[LeafAnvil] Error closing world session: " + e.getMessage(), e);
            }
        }
    }
}
