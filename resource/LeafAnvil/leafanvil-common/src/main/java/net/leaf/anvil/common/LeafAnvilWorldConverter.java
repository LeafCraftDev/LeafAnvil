package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.AnvilIoPool;
import io.github.dailystruggle.anvil.AnvilReader;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.anvil.LcaRegionWriter;
import io.github.dailystruggle.anvil.LinearRegionReader;
import io.github.dailystruggle.anvil.RegionFileReader;
import io.github.dailystruggle.anvil.RegionFormatRegistry;
import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.TaskHandle;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch world converter, slab compactor, and 4-tier SEC-DED bit-rot healer across `region/`,
 * `entities/`, and `poi/` directories (`leafanvil-ADR-001`, `anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LeafAnvilWorldConverter {

    private static final Pattern LEGACY_REGION_FILE_PATTERN =
            Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|linear|b_linear)$");
    private static final Pattern LCA_REGION_FILE_PATTERN =
            Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.lca$");
    private static final List<String> STORAGE_SUBFOLDERS = List.of("region", "entities", "poi");

    private LeafAnvilWorldConverter() {
    }

    /**
     * Summary of a batch conversion, compaction, or bit-rot healing operation.
     */
    public record OperationReport(
            int filesScanned,
            int filesConvertedOrModified,
            long bytesBefore,
            long bytesAfter,
            int bitRotRepairs
    ) {
    }

    /**
     * Converts all `.mca` (and `.linear` when registered) region files under `dimensionDir`
     * (`region/`, `entities/`, `poi/`) to `.lca`, preserving originals as `.bak` when requested.
     */
    public static OperationReport convertWorldToLca(
            Path dimensionDir, boolean modeBLcd2, boolean keepBackups
    ) throws IOException {
        return convertWorldToLca(dimensionDir, modeBLcd2 ? 1 : 0, keepBackups, null);
    }

    public static OperationReport convertWorldToLca(
            Path dimensionDir, int lcd2Level, boolean keepBackups
    ) throws IOException {
        return convertWorldToLca(dimensionDir, lcd2Level, keepBackups, null);
    }

    /**
     * Converts all `.mca` (and `.linear` when registered) region files under `dimensionDir`
     * (`region/`, `entities/`, `poi/`) to `.lca`, coordinating with `liveSession` if active.
     */
    public static OperationReport convertWorldToLca(
            Path dimensionDir,
            boolean modeBLcd2,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession
    ) throws IOException {
        return convertWorldToLca(dimensionDir, modeBLcd2 ? 1 : 0, keepBackups, liveSession);
    }

    /**
     * Live progress, throughput, and ETA telemetry snapshot for batch conversion and healing.
     */
    public record ProgressSnapshot(
            String phase,
            Path dimensionDir,
            int processedCandidates,
            int totalCandidates,
            int filesConvertedOrModified,
            int workerThreads,
            double regionsPerSecond,
            long etaSeconds,
            boolean pausedByOperator,
            boolean throttledByHealth
    ) {
        public String formatSummary(String worldLabel) {
            String label = (worldLabel == null || worldLabel.isBlank()) ? "world" : worldLabel;
            double pct = totalCandidates <= 0
                    ? 100.0
                    : Math.min(100.0, (processedCandidates * 100.0) / totalCandidates);
            String etaText = etaSeconds < 0L
                    ? "--:--"
                    : String.format(Locale.ROOT, "%02d:%02d", etaSeconds / 60L, etaSeconds % 60L);
            String stateSuffix = pausedByOperator
                    ? " [PAUSED]"
                    : (throttledByHealth ? " [TPS-BACKOFF]" : "");
            return String.format(
                    Locale.ROOT,
                    "%s [%s]: %d/%d regions (%.1f%%, %.1f rgn/s, ETA %s, workers=%d)%s",
                    label,
                    phase,
                    processedCandidates,
                    totalCandidates,
                    pct,
                    regionsPerSecond,
                    etaText,
                    workerThreads,
                    stateSuffix);
        }
    }

    /**
     * Scheduler-backed worker permit cap, {@link BudgetPacer} duty-cycle, tick-health gate, and
     * cooperative pause/cancel configuration for batch world conversion and bit-rot healing.
     */
    public record PacingOptions(
            int workerThreads,
            double dutyCycle,
            long pulseWindowMillis,
            double minTps,
            long maxOvertimeMs,
            Executor workerExecutor,
            BiFunction<Runnable, Long, TaskHandle> pulseTimerRegistrar,
            DoubleSupplier tpsSupplier,
            LongSupplier overtimeMsSupplier,
            BooleanSupplier pausedSupplier,
            BooleanSupplier cancelledSupplier,
            Consumer<ProgressSnapshot> progressCallback,
            long progressIntervalMillis
    ) {
        public static final double TPS_RESUME_HYSTERESIS = 0.5;

        public static PacingOptions defaultBounded() {
            int autoWorkers = LeafAnvilConfig.resolveConvertWorkerThreads(
                    0, LcaInMemoryChunkCoordinator.detectC2mePresent());
            return new PacingOptions(
                    autoWorkers,
                    1.0,
                    200L,
                    0.0,
                    -1L,
                    AnvilIoPool.get(),
                    null,
                    () -> 20.0,
                    () -> 0L,
                    () -> false,
                    () -> false,
                    null,
                    5_000L);
        }

        public static PacingOptions forRuntime(
                LeafAnvilRuntime runtime,
                int workerOverride,
                LeafAnvilRuntime.BatchTaskHandle handle,
                Consumer<ProgressSnapshot> progressCallback
        ) {
            LeafAnvilConfig cfg = runtime != null ? runtime.config() : LeafAnvilConfig.defaults();
            ServerAccessor accessor = runtime != null ? runtime.accessor() : null;
            int configured = workerOverride > 0 ? workerOverride : cfg.convertWorkerThreads();
            int workers = LeafAnvilConfig.resolveConvertWorkerThreads(
                    configured, LcaInMemoryChunkCoordinator.detectC2mePresent());
            Executor asyncExecutor = accessor != null && accessor.scheduler().isPresent()
                    ? task -> accessor.scheduler().get().runAsync(task)
                    : AnvilIoPool.get();
            BiFunction<Runnable, Long, TaskHandle> timerRegistrar = runtime != null
                    ? (pulse, periodMs) -> {
                        long periodTicks = Math.max(1L, Math.round(periodMs / 50.0));
                        TaskHandle th = runtime.scheduleAsyncPulseTimer(pulse, 1L, periodTicks).orElse(null);
                        if (th != null && handle != null) {
                            handle.bindTimerHandle(th);
                        }
                        return th;
                    }
                    : null;
            return new PacingOptions(
                    workers,
                    cfg.convertDutyCycle(),
                    200L,
                    cfg.convertMinTps(),
                    cfg.convertMaxOvertimeMs(),
                    asyncExecutor,
                    timerRegistrar,
                    accessor != null ? () -> accessor.getTPS(20) : () -> 20.0,
                    accessor != null ? accessor::overTime : () -> 0L,
                    handle != null ? handle::isPaused : () -> false,
                    handle != null ? handle::isCancelled : () -> false,
                    progressCallback,
                    5_000L);
        }

        int resolvedWorkerThreads() {
            return LeafAnvilConfig.resolveConvertWorkerThreads(
                    workerThreads, LcaInMemoryChunkCoordinator.detectC2mePresent());
        }

        double clampedDutyCycle() {
            if (!Double.isFinite(dutyCycle)) {
                return 0.50;
            }
            return Math.max(0.05, Math.min(1.0, dutyCycle));
        }

        double sampleTps() {
            if (tpsSupplier == null) {
                return 20.0;
            }
            double raw = tpsSupplier.getAsDouble();
            return (raw <= 0.0 || !Double.isFinite(raw)) ? 20.0 : Math.min(20.0, raw);
        }

        long sampleOvertimeMs() {
            if (overtimeMsSupplier == null) {
                return 0L;
            }
            return Math.max(0L, overtimeMsSupplier.getAsLong());
        }

        boolean isPaused() {
            return pausedSupplier != null && pausedSupplier.getAsBoolean();
        }

        boolean isCancelled() {
            return cancelledSupplier != null && cancelledSupplier.getAsBoolean();
        }

        Executor resolvedAsyncExecutor() {
            return workerExecutor != null ? workerExecutor : AnvilIoPool.get();
        }
    }

    @FunctionalInterface
    private interface RegionWorkItem {
        void execute() throws IOException;
    }

    /**
     * Off-thread world conversion to `.lca` (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @SuppressWarnings("deprecation")
    public static OperationReport convertWorldToLca(
            Path dimensionDir,
            int lcd2Level,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession
    ) throws IOException {
        return convertWorldToLca(
                dimensionDir, lcd2Level, keepBackups, liveSession, PacingOptions.defaultBounded());
    }

    /**
     * Off-thread paced world conversion to `.lca` (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @SuppressWarnings("deprecation")
    public static OperationReport convertWorldToLca(
            Path dimensionDir,
            int lcd2Level,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing
    ) throws IOException {
        return awaitOperationReport(
                startConvertWorldToLcaAsync(dimensionDir, lcd2Level, keepBackups, liveSession, pacing, null));
    }

    /**
     * Non-blocking scheduler-paced world conversion to `.lca` (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @SuppressWarnings("deprecation")
    public static CompletableFuture<OperationReport> startConvertWorldToLcaAsync(
            Path dimensionDir,
            int lcd2Level,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing,
            LeafAnvilRuntime.BatchTaskHandle batchHandle
    ) {
        PacingOptions opts = pacing != null ? pacing : PacingOptions.defaultBounded();
        Executor asyncExecutor = opts.resolvedAsyncExecutor();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger converted = new AtomicInteger();
        AtomicLong bytesBefore = new AtomicLong();
        AtomicLong bytesAfter = new AtomicLong();
        return CompletableFuture.supplyAsync(
                        () -> collectLcaWorkItems(
                                dimensionDir,
                                lcd2Level,
                                keepBackups,
                                liveSession,
                                scanned,
                                converted,
                                bytesBefore,
                                bytesAfter),
                        asyncExecutor)
                .thenCompose(workItems ->
                        startPacedRegionWork("convert->lca", dimensionDir, workItems, converted, opts, batchHandle))
                .thenApply(v -> new OperationReport(
                        scanned.get(), converted.get(), bytesBefore.get(), bytesAfter.get(), 0));
    }

    /**
     * Off-thread candidate region collector for `.lca` conversion (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static List<RegionWorkItem> collectLcaWorkItems(
            Path dimensionDir,
            int lcd2Level,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            AtomicInteger scanned,
            AtomicInteger converted,
            AtomicLong bytesBefore,
            AtomicLong bytesAfter
    ) {
        try {
            LcaRegionReader.register();
            LinearRegionReader.registerIfAvailable();
            List<RegionWorkItem> workItems = new ArrayList<>();
            for (Path folder : resolveStorageFolders(dimensionDir)) {
                int storageKind = LcaRegionFileStorage.inferStorageKindFromPath(folder);
                LcaRegionFileStorage liveStorage = liveSession != null ? liveSession.storageForFolder(folder) : null;
                java.util.LinkedHashMap<Long, Path> legacyByRegionKey = new java.util.LinkedHashMap<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                    for (Path file : stream) {
                        String name = file.getFileName().toString();
                        if (LCA_REGION_FILE_PATTERN.matcher(name).matches()) {
                            scanned.incrementAndGet();
                            continue;
                        }
                        Matcher m = LEGACY_REGION_FILE_PATTERN.matcher(name);
                        if (!m.matches()) {
                            continue;
                        }
                        scanned.incrementAndGet();
                        int rx = Integer.parseInt(m.group(1));
                        int rz = Integer.parseInt(m.group(2));
                        long key = LcaRegionFileStorage.packRegionKey(rx, rz);
                        Path existing = legacyByRegionKey.get(key);
                        if (existing == null || legacyPriority(name) < legacyPriority(existing.getFileName().toString())) {
                            legacyByRegionKey.put(key, file);
                        }
                    }
                }
                for (Path candidate : legacyByRegionKey.values()) {
                    workItems.add(() -> convertSingleRegionToLca(
                            folder,
                            candidate,
                            storageKind,
                            lcd2Level,
                            keepBackups,
                            liveStorage,
                            converted,
                            bytesBefore,
                            bytesAfter));
                }
            }
            return workItems;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Off-thread single-region transcode to `.lca` (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @SuppressWarnings("deprecation")
    private static void convertSingleRegionToLca(
            Path folder,
            Path file,
            int storageKind,
            int lcd2Level,
            boolean keepBackups,
            LcaRegionFileStorage liveStorage,
            AtomicInteger converted,
            AtomicLong bytesBefore,
            AtomicLong bytesAfter
    ) throws IOException {
        String name = file.getFileName().toString();
        Matcher m = LEGACY_REGION_FILE_PATTERN.matcher(name);
        if (!m.matches()) {
            return;
        }
        String ext = m.group(3);
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        RegionFileReader reader = "mca".equals(ext)
                ? AnvilReader.INSTANCE
                : RegionFormatRegistry.getReader("." + ext);
        if (reader == null) {
            return;
        }
        if (liveStorage != null) {
            liveStorage.closeRegion(rx, rz);
        }
        Path targetLca = folder.resolve("r." + rx + "." + rz + ".lca");
        if (hasValidLcaArchiveOnDisk(targetLca)) {
            return;
        }
        if (!Files.exists(file)) {
            return;
        }
        byte[] srcBytes = Files.readAllBytes(file);
        if (srcBytes.length < 32) {
            return;
        }
        bytesBefore.addAndGet(srcBytes.length);
        byte[] lcaBytes = LcaRegionWriter.transcodeFromReader(
                reader, srcBytes, rx, rz, lcd2Level, storageKind);
        Path tmpLca = folder.resolve("r." + rx + "." + rz + "." + Thread.currentThread().getId() + ".lca.tmp");
        LcaRegionWriter.writeBytesAtomicallyDurable(targetLca, tmpLca, lcaBytes);
        LcaRegionReader.invalidateFileCache(targetLca);
        if (liveStorage != null) {
            liveStorage.invalidateRegionCacheState(rx, rz);
        }
        bytesAfter.addAndGet(lcaBytes.length);
        if (keepBackups) {
            Path bak = folder.resolve(name + ".bak");
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(file);
        }
        converted.incrementAndGet();
    }

    /**
     * Reversibly converts `.lca` region files under `dimensionDir` (`region/`, `entities/`, `poi/`)
     * back to vanilla `.mca` (`4 KiB` Zlib-compressed Anvil sectors).
     */
    public static OperationReport convertWorldToMca(
            Path dimensionDir, boolean keepBackups
    ) throws IOException {
        return convertWorldToMca(dimensionDir, keepBackups, null);
    }

    /**
     * Reversibly converts `.lca` region files under `dimensionDir` (`region/`, `entities/`, `poi/`)
     * back to vanilla `.mca`, coordinating with `liveSession` if active (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public static OperationReport convertWorldToMca(
            Path dimensionDir,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession
    ) throws IOException {
        return convertWorldToMca(dimensionDir, keepBackups, liveSession, PacingOptions.defaultBounded());
    }

    /**
     * Off-thread paced world conversion to `.mca` (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public static OperationReport convertWorldToMca(
            Path dimensionDir,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing
    ) throws IOException {
        return awaitOperationReport(
                startConvertWorldToMcaAsync(dimensionDir, keepBackups, liveSession, pacing, null));
    }

    /**
     * Non-blocking scheduler-paced world conversion to `.mca` (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public static CompletableFuture<OperationReport> startConvertWorldToMcaAsync(
            Path dimensionDir,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing,
            LeafAnvilRuntime.BatchTaskHandle batchHandle
    ) {
        PacingOptions opts = pacing != null ? pacing : PacingOptions.defaultBounded();
        Executor asyncExecutor = opts.resolvedAsyncExecutor();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger converted = new AtomicInteger();
        AtomicLong bytesBefore = new AtomicLong();
        AtomicLong bytesAfter = new AtomicLong();
        return CompletableFuture.supplyAsync(
                        () -> collectMcaWorkItems(
                                dimensionDir, keepBackups, liveSession, scanned, converted, bytesBefore, bytesAfter),
                        asyncExecutor)
                .thenCompose(workItems ->
                        startPacedRegionWork("convert->mca", dimensionDir, workItems, converted, opts, batchHandle))
                .thenApply(v -> new OperationReport(
                        scanned.get(), converted.get(), bytesBefore.get(), bytesAfter.get(), 0));
    }

    /**
     * Off-thread candidate region collector for `.mca` conversion (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static List<RegionWorkItem> collectMcaWorkItems(
            Path dimensionDir,
            boolean keepBackups,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            AtomicInteger scanned,
            AtomicInteger converted,
            AtomicLong bytesBefore,
            AtomicLong bytesAfter
    ) {
        try {
            LcaRegionReader.register();
            List<RegionWorkItem> workItems = new ArrayList<>();
            for (Path folder : resolveStorageFolders(dimensionDir)) {
                LcaRegionFileStorage liveStorage = liveSession != null ? liveSession.storageForFolder(folder) : null;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.*.lca")) {
                    for (Path file : stream) {
                        String name = file.getFileName().toString();
                        Matcher m = LCA_REGION_FILE_PATTERN.matcher(name);
                        if (!m.matches()) {
                            continue;
                        }
                        scanned.incrementAndGet();
                        int rx = Integer.parseInt(m.group(1));
                        int rz = Integer.parseInt(m.group(2));
                        workItems.add(() -> convertSingleRegionToMca(
                                folder,
                                file,
                                name,
                                rx,
                                rz,
                                keepBackups,
                                liveStorage,
                                converted,
                                bytesBefore,
                                bytesAfter));
                    }
                }
            }
            return workItems;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Off-thread single-region transcode to `.mca` (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static void convertSingleRegionToMca(
            Path folder,
            Path file,
            String name,
            int rx,
            int rz,
            boolean keepBackups,
            LcaRegionFileStorage liveStorage,
            AtomicInteger converted,
            AtomicLong bytesBefore,
            AtomicLong bytesAfter
    ) throws IOException {
        if (liveStorage != null) {
            liveStorage.closeRegion(rx, rz);
        }
        if (!Files.exists(file)) {
            return;
        }
        byte[] lcaBytes = Files.readAllBytes(file);
        if (!isLcaArchive(lcaBytes)) {
            return;
        }
        bytesBefore.addAndGet(lcaBytes.length);
        byte[] mcaBytes = LcaRegionWriter.transcodeToMca(lcaBytes);
        Path targetMca = folder.resolve("r." + rx + "." + rz + ".mca");
        Path tmpMca = folder.resolve("r." + rx + "." + rz + "." + Thread.currentThread().getId() + ".mca.tmp");
        LcaRegionWriter.writeBytesAtomicallyDurable(targetMca, tmpMca, mcaBytes);
        bytesAfter.addAndGet(mcaBytes.length);
        if (keepBackups) {
            Path bak = folder.resolve(name + ".bak");
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(file);
        }
        LcaRegionReader.invalidateFileCache(file);
        if (liveStorage != null) {
            liveStorage.invalidateRegionCacheState(rx, rz);
        }
        converted.incrementAndGet();
    }

    /**
     * Runs the 4-tier `.lca` SEC-DED + slab reverse-scan + section quarantine + CoW rollback
     * bit-rot healer across all `r.<x>.<z>.lca` files in `dimensionDir`.
     */
    public static OperationReport healAndVerifyWorld(Path dimensionDir) throws IOException {
        return healAndVerifyWorld(dimensionDir, null);
    }

    /**
     * Off-thread 4-tier `.lca` bit-rot healer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     * Runs the 4-tier `.lca` bit-rot healer across all `r.<x>.<z>.lca` files in `dimensionDir`,
     * coordinating with `liveSession` if active.
     */
    public static OperationReport healAndVerifyWorld(
            Path dimensionDir, LcaRegionFileStorage.LcaWorldStorageSession liveSession
    ) throws IOException {
        return healAndVerifyWorld(dimensionDir, liveSession, PacingOptions.defaultBounded());
    }

    /**
     * Off-thread paced 4-tier `.lca` bit-rot healer (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public static OperationReport healAndVerifyWorld(
            Path dimensionDir,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing
    ) throws IOException {
        return awaitOperationReport(
                startHealAndVerifyWorldAsync(dimensionDir, liveSession, pacing, null));
    }

    /**
     * Non-blocking scheduler-paced 4-tier `.lca` bit-rot healer (`REQ-RTP-S-002`, `REQ-RTP-S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public static CompletableFuture<OperationReport> startHealAndVerifyWorldAsync(
            Path dimensionDir,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            PacingOptions pacing,
            LeafAnvilRuntime.BatchTaskHandle batchHandle
    ) {
        PacingOptions opts = pacing != null ? pacing : PacingOptions.defaultBounded();
        Executor asyncExecutor = opts.resolvedAsyncExecutor();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger modified = new AtomicInteger();
        AtomicLong bytesTotal = new AtomicLong();
        AtomicInteger totalRepairs = new AtomicInteger();
        return CompletableFuture.supplyAsync(
                        () -> collectHealWorkItems(
                                dimensionDir, liveSession, scanned, modified, bytesTotal, totalRepairs),
                        asyncExecutor)
                .thenCompose(workItems ->
                        startPacedRegionWork("heal", dimensionDir, workItems, modified, opts, batchHandle))
                .thenApply(v -> new OperationReport(
                        scanned.get(),
                        modified.get(),
                        bytesTotal.get(),
                        bytesTotal.get(),
                        totalRepairs.get()));
    }

    /**
     * Off-thread candidate region collector for `.lca` bit-rot healing (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static List<RegionWorkItem> collectHealWorkItems(
            Path dimensionDir,
            LcaRegionFileStorage.LcaWorldStorageSession liveSession,
            AtomicInteger scanned,
            AtomicInteger modified,
            AtomicLong bytesTotal,
            AtomicInteger totalRepairs
    ) {
        try {
            LcaRegionReader.register();
            List<RegionWorkItem> workItems = new ArrayList<>();
            for (Path folder : resolveStorageFolders(dimensionDir)) {
                LcaRegionFileStorage liveStorage = liveSession != null ? liveSession.storageForFolder(folder) : null;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "r.*.*.lca")) {
                    for (Path file : stream) {
                        scanned.incrementAndGet();
                        workItems.add(() -> healSingleRegionLca(
                                folder,
                                file,
                                liveStorage,
                                modified,
                                bytesTotal,
                                totalRepairs));
                    }
                }
            }
            return workItems;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Off-thread single-region 4-tier `.lca` bit-rot healer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static void healSingleRegionLca(
            Path folder,
            Path file,
            LcaRegionFileStorage liveStorage,
            AtomicInteger modified,
            AtomicLong bytesTotal,
            AtomicInteger totalRepairs
    ) throws IOException {
        String name = file.getFileName().toString();
        Matcher m = LCA_REGION_FILE_PATTERN.matcher(name);
        boolean matched = m.matches();
        int rx = matched ? Integer.parseInt(m.group(1)) : 0;
        int rz = matched ? Integer.parseInt(m.group(2)) : 0;
        if (liveStorage != null && matched) {
            liveStorage.closeRegion(rx, rz);
        }
        if (!Files.exists(file)) {
            return;
        }
        byte[] lcaBytes = Files.readAllBytes(file);
        bytesTotal.addAndGet(lcaBytes.length);
        if (lcaBytes.length < 72
                || java.nio.ByteBuffer.wrap(lcaBytes, 0, 8).getLong() != LcaRegionReader.LCA_SUPERBLOCK_MAGIC) {
            return;
        }
        LcaRegionReader.RecoveryReport report = LcaRegionReader.healRegionBitRot(lcaBytes);
        int repairs = report.secDedHealedPointers()
                + report.slabScanReconstructedPointers()
                + report.chunkHeaderSecDedHealedWords()
                + report.quarantinedSections();
        if (repairs > 0) {
            totalRepairs.addAndGet(repairs);
            modified.incrementAndGet();
            Path tmp = folder.resolve(
                    file.getFileName().toString() + "." + Thread.currentThread().getId() + ".heal.tmp");
            LcaRegionWriter.writeBytesAtomicallyDurable(file, tmp, lcaBytes);
            LcaRegionReader.invalidateFileCache(file);
            if (liveStorage != null && matched) {
                liveStorage.invalidateRegionCacheState(rx, rz);
            }
        }
    }

    private static OperationReport awaitOperationReport(
            CompletableFuture<OperationReport> future
    ) throws IOException {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException("World storage batch operation failed: " + cause.getMessage(), cause);
        }
    }

    private static CompletableFuture<Void> startPacedRegionWork(
            String phase,
            Path dimensionDir,
            List<RegionWorkItem> workItems,
            AtomicInteger modifiedCounter,
            PacingOptions pacing,
            LeafAnvilRuntime.BatchTaskHandle batchHandle
    ) {
        PacingOptions opts = pacing != null ? pacing : PacingOptions.defaultBounded();
        if (workItems == null || workItems.isEmpty() || opts.isCancelled()) {
            if (opts.progressCallback() != null) {
                int total = workItems != null ? workItems.size() : 0;
                opts.progressCallback().accept(new ProgressSnapshot(
                        phase,
                        dimensionDir,
                        0,
                        total,
                        modifiedCounter.get(),
                        Math.max(1, opts.resolvedWorkerThreads()),
                        0.0,
                        0L,
                        false,
                        false));
            }
            return CompletableFuture.completedFuture(null);
        }
        PacedRegionSession session = new PacedRegionSession(
                phase, dimensionDir, workItems, modifiedCounter, opts, batchHandle);
        return session.start();
    }

    private static final class PacedRegionSession {
        private final String phase;
        private final Path dimensionDir;
        private final int totalCandidates;
        private final int workerLimit;
        private final ConcurrentLinkedQueue<RegionWorkItem> queue;
        private final Semaphore inFlightGate;
        private final AtomicInteger inFlightCount = new AtomicInteger(0);
        private final AtomicInteger completedCount = new AtomicInteger(0);
        private final AtomicInteger modifiedCounter;
        private final AtomicBoolean pulseActive = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean tpsBackoff = new AtomicBoolean(false);
        private final AtomicBoolean reportedPauseState = new AtomicBoolean(false);
        private final AtomicBoolean reportedHealthState = new AtomicBoolean(false);
        private final AtomicLong cooldownUntilNanos = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger windowStartCompleted = new AtomicInteger(0);
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        private final AtomicReference<TaskHandle> timerRef = new AtomicReference<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final BudgetPacer pacer = BudgetPacer.ofEstimator(
                EwmaEstimator.ofTaskPipe(TimeUnit.MILLISECONDS.toNanos(15)), 1.05);
        private final PacingOptions opts;
        private final Executor asyncExecutor;
        private final LeafAnvilRuntime.BatchTaskHandle batchHandle;
        private final long progressIntervalMs;
        private volatile double smoothedRate = 0.0;

        PacedRegionSession(
                String phase,
                Path dimensionDir,
                List<RegionWorkItem> workItems,
                AtomicInteger modifiedCounter,
                PacingOptions opts,
                LeafAnvilRuntime.BatchTaskHandle batchHandle
        ) {
            this.phase = phase;
            this.dimensionDir = dimensionDir;
            this.totalCandidates = workItems.size();
            this.workerLimit = Math.max(1, Math.min(opts.resolvedWorkerThreads(), totalCandidates));
            this.queue = new ConcurrentLinkedQueue<>(workItems);
            this.inFlightGate = new Semaphore(workerLimit);
            this.modifiedCounter = modifiedCounter;
            this.opts = opts;
            this.asyncExecutor = opts.resolvedAsyncExecutor();
            this.batchHandle = batchHandle;
            this.progressIntervalMs = Math.max(10L, opts.progressIntervalMillis());
        }

        CompletableFuture<Void> start() {
            if (batchHandle != null) {
                batchHandle.bindResumeTrigger(this::pulse);
            }
            if (opts.progressCallback() != null) {
                opts.progressCallback().accept(new ProgressSnapshot(
                        phase,
                        dimensionDir,
                        0,
                        totalCandidates,
                        modifiedCounter.get(),
                        workerLimit,
                        0.0,
                        -1L,
                        false,
                        false));
            }
            Runnable inlineSlot = claimPulseAndDispatch(true);
            if (!finished.get()) {
                try {
                    long timerPeriodMs = Math.max(10L, Math.min(100L, Math.max(10L, opts.pulseWindowMillis()) / 2L));
                    TaskHandle handle = opts.pulseTimerRegistrar() != null
                            ? opts.pulseTimerRegistrar().apply(this::pulse, timerPeriodMs)
                            : null;
                    if (handle == null) {
                        handle = scheduleDelayedExecutorTimer(this::pulse, timerPeriodMs);
                    }
                    timerRef.set(handle);
                    if (finished.get()) {
                        TaskHandle stale = timerRef.getAndSet(null);
                        if (stale != null) {
                            stale.cancel();
                        }
                    }
                } catch (Throwable timerErr) {
                    recordFailure(timerErr);
                }
            }
            if (inlineSlot != null) {
                inlineSlot.run();
            }
            return completion;
        }

        void pulse() {
            Runnable unexpectedInline = claimPulseAndDispatch(false);
            if (unexpectedInline != null) {
                unexpectedInline.run();
            }
        }

        private Runnable claimPulseAndDispatch(boolean claimCallerWorkerSlot) {
            if (finished.get()) {
                return null;
            }
            if (!pulseActive.compareAndSet(false, true)) {
                tryComplete();
                return null;
            }
            try {
                if (opts.isCancelled() || firstFailure.get() != null || queue.isEmpty()) {
                    return null;
                }
                if (checkOperatorPausedGate()) {
                    return null;
                }
                double tps = opts.sampleTps();
                if (checkTickHealthGate(tps, opts.sampleOvertimeMs())) {
                    return null;
                }
                double effectiveDuty = computeEffectiveDutyCycle(tps);
                if (effectiveDuty < 0.999
                        && (inFlightCount.get() > 0 || System.nanoTime() < cooldownUntilNanos.get())) {
                    return null;
                }
                return dispatchBoundedWave(effectiveDuty, claimCallerWorkerSlot);
            } finally {
                pulseActive.set(false);
                tryComplete();
            }
        }

        private boolean checkOperatorPausedGate() {
            if (!opts.isPaused()) {
                reportedPauseState.set(false);
                return false;
            }
            cooldownUntilNanos.set(Long.MIN_VALUE);
            long nowMs = System.currentTimeMillis();
            if (opts.progressCallback() != null
                    && (reportedPauseState.compareAndSet(false, true)
                            || nowMs - windowStartMs.get() >= progressIntervalMs)) {
                windowStartMs.set(nowMs);
                windowStartCompleted.set(completedCount.get());
                opts.progressCallback().accept(buildSnapshot(
                        phase,
                        dimensionDir,
                        completedCount.get(),
                        totalCandidates,
                        modifiedCounter.get(),
                        workerLimit,
                        smoothedRate,
                        true,
                        false));
            }
            return true;
        }

        private boolean checkTickHealthGate(double tps, long overtimeMs) {
            boolean backoff = updateTpsBackoffState(tpsBackoff.get(), tps, opts.minTps());
            tpsBackoff.set(backoff);
            boolean overtimeExceeded = opts.maxOvertimeMs() >= 0L && overtimeMs > opts.maxOvertimeMs();
            if (!backoff && !overtimeExceeded) {
                reportedHealthState.set(false);
                return false;
            }
            long nowMs = System.currentTimeMillis();
            if (opts.progressCallback() != null
                    && (reportedHealthState.compareAndSet(false, true)
                            || nowMs - windowStartMs.get() >= progressIntervalMs)) {
                windowStartMs.set(nowMs);
                windowStartCompleted.set(completedCount.get());
                opts.progressCallback().accept(buildSnapshot(
                        phase,
                        dimensionDir,
                        completedCount.get(),
                        totalCandidates,
                        modifiedCounter.get(),
                        workerLimit,
                        smoothedRate,
                        false,
                        true));
            }
            return true;
        }

        private double computeEffectiveDutyCycle(double tps) {
            double effectiveDuty = opts.clampedDutyCycle();
            if (opts.minTps() > 0.0 && tps < 19.8 && 19.8 > opts.minTps()) {
                double scale = Math.max(0.25, Math.min(1.0, (tps - opts.minTps()) / (19.8 - opts.minTps())));
                effectiveDuty = Math.max(0.05, effectiveDuty * scale);
            }
            return effectiveDuty;
        }

        private Runnable dispatchBoundedWave(double effectiveDuty, boolean claimCallerWorkerSlot) {
            long pulseBudgetNanos = effectiveDuty >= 0.999
                    ? Long.MAX_VALUE
                    : Math.max(
                            1L,
                            Math.round(TimeUnit.MILLISECONDS.toNanos(
                                    Math.max(10L, opts.pulseWindowMillis())) * effectiveDuty));
            long predictedWaveNanos = 0L;
            boolean dispatchedAny = false;
            final long waveStartNanos = System.nanoTime();
            Runnable inlineSlot = null;

            while (!opts.isCancelled() && !opts.isPaused() && firstFailure.get() == null) {
                if (!pacer.canExecute(predictedWaveNanos, pulseBudgetNanos, dispatchedAny)) {
                    break;
                }
                if (queue.isEmpty() || !inFlightGate.tryAcquire()) {
                    break;
                }
                RegionWorkItem item = queue.poll();
                if (item == null) {
                    inFlightGate.release();
                    break;
                }
                inFlightCount.incrementAndGet();
                dispatchedAny = true;
                predictedWaveNanos += Math.max(1L, pacer.predictedCostNanos() / workerLimit);
                if (claimCallerWorkerSlot && inlineSlot == null) {
                    inlineSlot = () -> executeWorkerSlot(item, waveStartNanos, effectiveDuty);
                } else {
                    dispatchItem(item, waveStartNanos, effectiveDuty);
                }
            }
            return inlineSlot;
        }

        private void dispatchItem(RegionWorkItem item, long waveStartNanos, double waveDuty) {
            try {
                asyncExecutor.execute(() -> executeWorkerSlot(item, waveStartNanos, waveDuty));
            } catch (Throwable dispatchFailure) {
                recordFailure(dispatchFailure);
                inFlightCount.decrementAndGet();
                tryComplete();
                inFlightGate.release();
            }
        }

        private void executeWorkerSlot(RegionWorkItem initialItem, long waveStartNanos, double initialWaveDuty) {
            double activeDuty = initialWaveDuty;
            RegionWorkItem current = initialItem;
            try {
                while (current != null) {
                    long startNanos = System.nanoTime();
                    try {
                        if (!opts.isCancelled() && firstFailure.get() == null) {
                            current.execute();
                        }
                    } catch (Throwable t) {
                        recordFailure(t);
                    } finally {
                        pacer.recordDuration(Math.max(1L, System.nanoTime() - startNanos));
                        completedCount.incrementAndGet();
                        maybeEmitPeriodicProgress();
                    }

                    if (activeDuty < 0.999
                            || opts.isCancelled()
                            || firstFailure.get() != null
                            || checkOperatorPausedGate()) {
                        break;
                    }
                    double tps = opts.sampleTps();
                    if (checkTickHealthGate(tps, opts.sampleOvertimeMs())) {
                        break;
                    }
                    activeDuty = computeEffectiveDutyCycle(tps);
                    if (activeDuty < 0.999) {
                        break;
                    }
                    current = queue.poll();
                }
            } finally {
                if (activeDuty < 0.999 && !queue.isEmpty()) {
                    long waveWallNanos = Math.max(1L, System.nanoTime() - waveStartNanos);
                    long restNanos = Math.min(
                            TimeUnit.SECONDS.toNanos(2),
                            Math.max(
                                    TimeUnit.MILLISECONDS.toNanos(1),
                                    Math.round(waveWallNanos * ((1.0 - activeDuty) / activeDuty))));
                    long candidateCooldown = System.nanoTime() + restNanos;
                    cooldownUntilNanos.accumulateAndGet(candidateCooldown, Math::max);
                }
                inFlightCount.decrementAndGet();
                tryComplete();
                inFlightGate.release();
            }
        }

        private void maybeEmitPeriodicProgress() {
            if (opts.progressCallback() == null) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            long prevWindowMs = windowStartMs.get();
            if (nowMs - prevWindowMs < progressIntervalMs) {
                return;
            }
            if (!windowStartMs.compareAndSet(prevWindowMs, nowMs)) {
                return;
            }
            int done = completedCount.get();
            int prevDone = windowStartCompleted.getAndSet(done);
            smoothedRate = updateSmoothedRate(smoothedRate, done - prevDone, nowMs - prevWindowMs);
            opts.progressCallback().accept(buildSnapshot(
                    phase,
                    dimensionDir,
                    done,
                    totalCandidates,
                    modifiedCounter.get(),
                    workerLimit,
                    smoothedRate,
                    false,
                    false));
        }

        private void tryComplete() {
            boolean shouldFinish = opts.isCancelled() || firstFailure.get() != null || queue.isEmpty();
            if (!shouldFinish || inFlightCount.get() > 0) {
                return;
            }
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (batchHandle != null) {
                batchHandle.bindResumeTrigger(null);
            }
            TaskHandle handle = timerRef.getAndSet(null);
            if (handle != null) {
                handle.cancel();
            }
            Throwable err = firstFailure.get();
            if (err != null) {
                completion.completeExceptionally(err);
                return;
            }
            if (opts.progressCallback() != null) {
                int done = completedCount.get();
                long nowMs = System.currentTimeMillis();
                int prevDone = windowStartCompleted.getAndSet(done);
                if (done > prevDone) {
                    smoothedRate = updateSmoothedRate(
                            smoothedRate, done - prevDone, Math.max(1L, nowMs - windowStartMs.get()));
                }
                opts.progressCallback().accept(buildSnapshot(
                        phase,
                        dimensionDir,
                        done,
                        totalCandidates,
                        modifiedCounter.get(),
                        workerLimit,
                        smoothedRate,
                        false,
                        false));
            }
            completion.complete(null);
        }

        private void recordFailure(Throwable t) {
            if (!firstFailure.compareAndSet(null, t)) {
                Throwable primary = firstFailure.get();
                if (primary != null && primary != t) {
                    primary.addSuppressed(t);
                }
            }
        }
    }

    private static TaskHandle scheduleDelayedExecutorTimer(Runnable pulse, long periodMillis) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Executor delayed = CompletableFuture.delayedExecutor(
                Math.max(5L, periodMillis), TimeUnit.MILLISECONDS, AnvilIoPool.get());
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (cancelled.get()) {
                return;
            }
            try {
                pulse.run();
            } finally {
                if (!cancelled.get()) {
                    delayed.execute(self[0]);
                }
            }
        };
        delayed.execute(self[0]);
        return new TaskHandle() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    private static boolean updateTpsBackoffState(boolean currentlyPaused, double tps, double minTps) {
        if (minTps <= 0.0) {
            return false;
        }
        if (!currentlyPaused && tps < minTps) {
            return true;
        }
        if (currentlyPaused && tps >= Math.min(20.0, minTps + PacingOptions.TPS_RESUME_HYSTERESIS)) {
            return false;
        }
        return currentlyPaused;
    }

    private static double updateSmoothedRate(double previousRate, int deltaCompleted, long deltaMillis) {
        if (deltaCompleted <= 0) {
            return previousRate;
        }
        double instantRate = (deltaCompleted * 1000.0) / Math.max(1L, deltaMillis);
        return previousRate <= 0.0 ? instantRate : (0.7 * previousRate + 0.3 * instantRate);
    }

    private static ProgressSnapshot buildSnapshot(
            String phase,
            Path dimensionDir,
            int done,
            int total,
            int modified,
            int workers,
            double rate,
            boolean paused,
            boolean throttled
    ) {
        int remaining = Math.max(0, total - done);
        long etaSeconds = remaining == 0
                ? 0L
                : (rate > 0.05 ? Math.max(1L, Math.round(remaining / rate)) : -1L);
        return new ProgressSnapshot(
                phase,
                dimensionDir,
                done,
                total,
                modified,
                workers,
                rate,
                etaSeconds,
                paused,
                throttled);
    }

    /**
     * Resolves all `region/`, `entities/`, and `poi/` subdirectories under `dimensionDir`, covering both
     * direct dimension roots (`1.20`-`1.21.11` and explicit dimension paths) and unified `26.1+`
     * `dimensions/<namespace>/<dimension>/` world roots.
     */
    public static List<Path> resolveStorageFolders(Path dimensionDir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (dimensionDir == null || !Files.isDirectory(dimensionDir)) {
            return out;
        }
        for (String sub : STORAGE_SUBFOLDERS) {
            Path direct = dimensionDir.resolve(sub);
            if (Files.isDirectory(direct)) {
                out.add(direct);
            }
        }
        Path unifiedDims = dimensionDir.resolve("dimensions");
        if (Files.isDirectory(unifiedDims)) {
            try (DirectoryStream<Path> nsStream = Files.newDirectoryStream(unifiedDims)) {
                for (Path nsDir : nsStream) {
                    if (!Files.isDirectory(nsDir)) {
                        continue;
                    }
                    try (DirectoryStream<Path> dimStream = Files.newDirectoryStream(nsDir)) {
                        for (Path dimPath : dimStream) {
                            if (!Files.isDirectory(dimPath)) {
                                continue;
                            }
                            for (String sub : STORAGE_SUBFOLDERS) {
                                Path subFolder = dimPath.resolve(sub);
                                if (Files.isDirectory(subFolder) && !out.contains(subFolder)) {
                                    out.add(subFolder);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (String legacyDim : List.of("DIM-1", "DIM1")) {
            Path legacyDir = dimensionDir.resolve(legacyDim);
            if (Files.isDirectory(legacyDir)) {
                for (String sub : STORAGE_SUBFOLDERS) {
                    Path subFolder = legacyDir.resolve(sub);
                    if (Files.isDirectory(subFolder) && !out.contains(subFolder)) {
                        out.add(subFolder);
                    }
                }
            }
        }
        return out;
    }

    private static int legacyPriority(String fileName) {
        if (fileName.endsWith(".mca")) {
            return 0;
        }
        if (fileName.endsWith(".linear")) {
            return 1;
        }
        return 2;
    }

    /**
     * Off-thread `.lca` magic header probe (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private static boolean hasValidLcaArchiveOnDisk(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        long minLen = (long) LcaRegionReader.SUPERBLOCK_SIZE
                + LcaRegionReader.POINTER_DIRECTORY_SIZE
                + LcaRegionReader.FOOTER_SIZE;
        if (Files.size(path) < minLen) {
            return false;
        }
        try (java.nio.channels.FileChannel ch =
                java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(8);
            while (header.hasRemaining()) {
                if (ch.read(header) < 0) {
                    return false;
                }
            }
            header.flip();
            return header.getLong() == LcaRegionReader.LCA_SUPERBLOCK_MAGIC;
        }
    }

    private static boolean isLcaArchive(byte[] bytes) {
        if (bytes == null || bytes.length < LcaRegionReader.SUPERBLOCK_SIZE
                + LcaRegionReader.POINTER_DIRECTORY_SIZE + LcaRegionReader.FOOTER_SIZE) {
            return false;
        }
        long magic = java.nio.ByteBuffer.wrap(bytes, 0, 8).getLong();
        return magic == LcaRegionReader.LCA_SUPERBLOCK_MAGIC;
    }
}
