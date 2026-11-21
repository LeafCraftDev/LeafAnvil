package net.leaf.anvil.common;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionWriter;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.TaskHandle;
import io.github.dailystruggle.server.api.TaskScheduler;
import net.leaf.anvil.common.LeafAnvilWorldConverter.OperationReport;
import net.leaf.anvil.common.LeafAnvilWorldConverter.PacingOptions;
import net.leaf.anvil.common.LeafAnvilWorldConverter.ProgressSnapshot;
import net.leaf.anvil.common.command.LeafAnvilCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for scheduler-backed worker-core bounds, {@code BudgetPacer} duty-cycle pulses,
 * TPS/overtime hysteresis backoff, and {@code pause}/{@code resume}/{@code cancel} controls in
 * {@link LeafAnvilWorldConverter}, {@link LeafAnvilConfig}, {@link LeafAnvilRuntime}, and
 * {@link LeafAnvilCommand}.
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy commands-api-ADR-001
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 * @trace REQ-RTP-S-007
 */
class LeafAnvilConverterPacingTest {

    @Test
    @DisplayName("convertWorldToLca executes on bounded scheduler/AnvilIoPool workers and never uses ForkJoinPool.commonPool")
    void convertWorldToLcaNeverUsesForkJoinCommonPool(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("common_pool_repro_world");
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);

        byte[] sampleMca = buildSampleMcaBytes();
        int regionCount = 16;
        for (int rx = 0; rx < regionCount; rx++) {
            Files.write(regionDir.resolve("r." + rx + ".0.mca"), sampleMca);
        }

        ForkJoinPool common = ForkJoinPool.commonPool();
        long stealsBefore = common.getStealCount();

        OperationReport report = LeafAnvilWorldConverter.convertWorldToLca(worldDir, 1, false);
        assertEquals(regionCount, report.filesConvertedOrModified());

        long stealsDelta = common.getStealCount() - stealsBefore;
        assertEquals(
                0L,
                stealsDelta,
                "Forced world conversion must never saturate ForkJoinPool.commonPool()");
    }

    @Test
    @DisplayName("Worker permit cap strictly bounds concurrent TaskScheduler.runAsync region dispatches")
    void workerPermitCapBoundsConcurrentDispatches(@TempDir Path tempDir) throws Exception {
        byte[] sampleMca = buildSampleMcaBytes();
        for (int maxWorkers : new int[]{2, 1}) {
            Path worldDir = tempDir.resolve("bounded_workers_world_" + maxWorkers);
            Path regionDir = worldDir.resolve("region");
            Files.createDirectories(regionDir);

            int regionCount = 10;
            for (int rx = 0; rx < regionCount; rx++) {
                Files.write(regionDir.resolve("r." + rx + ".0.mca"), sampleMca);
            }

            AtomicInteger activeWorkers = new AtomicInteger(0);
            AtomicInteger peakWorkers = new AtomicInteger(0);
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                PacingOptions pacing = new PacingOptions(
                        maxWorkers,
                        1.0,
                        50L,
                        0.0,
                        -1L,
                        task -> pool.execute(() -> {
                            int active = activeWorkers.incrementAndGet();
                            peakWorkers.accumulateAndGet(active, Math::max);
                            try {
                                CountDownLatch tinyHold = new CountDownLatch(1);
                                tinyHold.await(8L, TimeUnit.MILLISECONDS);
                                task.run();
                                CountDownLatch retireHold = new CountDownLatch(1);
                                retireHold.await(2L, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                activeWorkers.decrementAndGet();
                            }
                        }),
                        null,
                        () -> 20.0,
                        () -> 0L,
                        () -> false,
                        () -> false,
                        null,
                        100L);

                OperationReport report = LeafAnvilWorldConverter.convertWorldToLca(
                        worldDir, 1, false, null, pacing);
                assertEquals(regionCount, report.filesConvertedOrModified());
                assertTrue(
                        peakWorkers.get() >= 1 && peakWorkers.get() <= maxWorkers,
                        "Peak concurrent workers (" + peakWorkers.get() + ") must never exceed configured cap " + maxWorkers);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("Duty-cycle pulses pause on low TPS with +0.5 TPS hysteresis and tick-overtime spikes")
    void dutyCyclePulsesHonorTpsHysteresisAndOvertimeBackoff(@TempDir Path tempDir) throws Exception {
        Path worldDir = tempDir.resolve("tps_backoff_world");
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);

        byte[] sampleMca = buildSampleMcaBytes();
        int regionCount = 5;
        for (int rx = 0; rx < regionCount; rx++) {
            Files.write(regionDir.resolve("r." + rx + ".0.mca"), sampleMca);
        }

        ManualPulseTaskScheduler scheduler = new ManualPulseTaskScheduler();
        AtomicReference<Double> liveTps = new AtomicReference<>(16.5);
        AtomicLong liveOvertimeMs = new AtomicLong(0L);
        List<ProgressSnapshot> snapshots = new CopyOnWriteArrayList<>();

        PacingOptions pacing = new PacingOptions(
                1,
                0.50,
                20L,
                18.5,
                10L,
                task -> scheduler.runAsync(task),
                (pulse, periodMs) -> scheduler.runAsyncTimer(pulse, 1L, 1L),
                liveTps::get,
                liveOvertimeMs::get,
                () -> false,
                () -> false,
                snapshots::add,
                10L);

        CompletableFuture<OperationReport> fut = LeafAnvilWorldConverter.startConvertWorldToLcaAsync(
                worldDir, 1, false, null, pacing, null);

        // Initial start sees TPS 16.5 < 18.5 -> must hold in TPS backoff with 0 files converted
        assertFalse(fut.isDone());
        assertTrue(snapshots.stream().anyMatch(ProgressSnapshot::throttledByHealth),
                "Low TPS must emit throttledByHealth progress snapshot");
        assertEquals(0L, countLcaFiles(regionDir), "No region files may convert while TPS < minTps");

        // TPS rises to 18.8 (above 18.5, below hysteresis 18.5 + 0.5 = 19.0) -> still paused
        liveTps.set(18.8);
        scheduler.fireTimers();
        assertEquals(0L, countLcaFiles(regionDir), "Hysteresis (+0.5 TPS) must hold backoff until 19.0 TPS");

        // TPS recovers to 19.5, but tick overtime is 25ms (> 10ms max) -> still paused
        liveTps.set(19.5);
        liveOvertimeMs.set(25L);
        scheduler.fireTimers();
        assertEquals(0L, countLcaFiles(regionDir), "Overtime > maxOvertimeMs must hold backoff");

        // Overtime clears -> pulses advance one wave at a time until all 5 regions convert
        liveOvertimeMs.set(0L);
        for (int i = 0; i < 20 && !fut.isDone(); i++) {
            scheduler.fireTimers();
            if (!fut.isDone()) {
                CountDownLatch waitRest = new CountDownLatch(1);
                waitRest.await(6L, TimeUnit.MILLISECONDS);
            }
        }
        OperationReport report = fut.get(5, TimeUnit.SECONDS);
        assertEquals(regionCount, report.filesConvertedOrModified());
        assertEquals(regionCount, countLcaFiles(regionDir));
        assertTrue(scheduler.allTimersCancelled(), "Timer TaskHandle must be cancelled on completion (S-002)");
    }

    @Test
    @DisplayName("/leafanvil convert supports workers=<n>, status telemetry, busy guard, and pause/resume/cancel via TaskScheduler")
    void commandSupportsPauseResumeCancelAndWorkersOverride(@TempDir Path tempDir) throws Exception {
        Path dataDir = tempDir.resolve("plugin_cfg");
        Path worldDir = tempDir.resolve("live_world");
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.createDirectories(worldDir.resolve("entities"));
        Files.createDirectories(worldDir.resolve("poi"));

        byte[] sampleMca = buildSampleMcaBytes();
        for (int rx = 0; rx < 4; rx++) {
            Files.write(regionDir.resolve("r." + rx + ".0.mca"), sampleMca);
        }

        ManualPulseTaskScheduler scheduler = new ManualPulseTaskScheduler();
        List<String> messages = new CopyOnWriteArrayList<>();
        ServerAccessor accessor = Mockito.mock(ServerAccessor.class);
        Mockito.when(accessor.getRootFolder()).thenReturn(dataDir);
        Mockito.when(accessor.scheduler()).thenReturn(Optional.of(scheduler));
        Mockito.when(accessor.getTPS(Mockito.anyInt())).thenReturn(20.0);
        Mockito.when(accessor.overTime()).thenReturn(0L);
        Mockito.doAnswer(inv -> {
            messages.add(inv.getArgument(1, String.class));
            return null;
        }).when(accessor).sendMessage(Mockito.any(), Mockito.anyString(), Mockito.anyBoolean());

        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("config.yml"), "enabled: false\nbackup-migrated-legacy-files: false\n");

        try (LeafAnvilRuntime runtime = new LeafAnvilRuntime(accessor)) {
            runtime.load();
            LcaRegionFileStorage.LcaWorldStorageSession session =
                    runtime.attachAndInterceptWorld("world", worldDir, -4, 24, null);
            assertNotNull(session);

            LeafAnvilCommand cmd = new LeafAnvilCommand("leafanvil", accessor, runtime);
            UUID admin = UUID.randomUUID();

            // Queue async tasks rather than running inline so we can inspect mid-flight state deterministically
            scheduler.setDeferAsyncWorkers(true);

            assertTrue(cmd.getCommandLookup().get("CONVERT").onCommand(
                    admin,
                    Map.of("world", List.of("world"), "format", List.of("lca"), "workers", List.of("1")),
                    null));

            // Drain starter task + async directory collector -> 1st region worker is queued, 3 remain in PacedRegionSession
            scheduler.drainNextQueuedWorker();
            scheduler.drainNextQueuedWorker();
            assertNotNull(runtime.activeBatchTask(), "BatchTaskHandle must be active while conversion is in flight");

            // Concurrent batch request must be rejected with configurable msgBusy (S-007)
            assertTrue(cmd.getCommandLookup().get("CONVERT").onCommand(
                    admin, Map.of("world", List.of("world")), null));
            assertTrue(messages.stream().anyMatch(m -> m.contains("Another storage task is currently running")));

            // Pause active conversion and check /leafanvil status
            assertTrue(cmd.getCommandLookup().get("PAUSE").onCommand(admin, Map.of(), null));
            assertTrue(runtime.activeBatchTask().isPaused());
            messages.clear();
            assertTrue(cmd.onCommand(admin, Map.of(), null));
            assertTrue(messages.stream().anyMatch(m -> m.contains("activeTask=convert(lca) [PAUSED]")),
                    "/leafanvil status must display PAUSED activeTask state");

            // Drain the 1 in-flight worker; while paused, firing timers must NOT queue another region worker
            scheduler.drainAllQueuedWorkers();
            assertEquals(1L, countLcaFiles(regionDir));
            scheduler.fireTimers();
            assertEquals(0, scheduler.queuedWorkerCount(), "Paused conversion must not schedule new region workers");

            // Resume conversion -> triggers pulse -> queues 2nd region worker
            CountDownLatch restLatch = new CountDownLatch(1);
            restLatch.await(6L, TimeUnit.MILLISECONDS);
            assertTrue(cmd.getCommandLookup().get("RESUME").onCommand(admin, Map.of(), null));
            assertFalse(runtime.activeBatchTask().isPaused());
            scheduler.drainAllQueuedWorkers();
            assertEquals(2L, countLcaFiles(regionDir));

            // Cancel conversion mid-run -> cancels timer handle, leaves remaining 2 .mca files intact, frees lease
            assertTrue(cmd.getCommandLookup().get("CANCEL").onCommand(admin, Map.of(), null));
            scheduler.drainAllQueuedWorkers();
            assertNull(runtime.activeBatchTask(), "Cancelled task must release activeBatchTask lease");
            assertTrue(scheduler.allTimersCancelled(), "Cancel must cancel registered TaskHandle (S-002)");
            assertEquals(2L, countLcaFiles(regionDir), "Remaining queued regions must stop on cancel");
            assertTrue(messages.stream().anyMatch(m -> m.contains("Conversion cancelled after converting 2 region files")));

            // Subsequent conversion can now run immediately and convert the remaining 2 regions
            scheduler.setDeferAsyncWorkers(false);
            assertTrue(cmd.getCommandLookup().get("CONVERT").onCommand(
                    admin,
                    Map.of("world", List.of("world"), "format", List.of("lca"), "workers", List.of("2")),
                    null));
            for (int i = 0; i < 20 && runtime.activeBatchTask() != null; i++) {
                CountDownLatch wait = new CountDownLatch(1);
                wait.await(6L, TimeUnit.MILLISECONDS);
                scheduler.fireTimers();
            }
            assertNull(runtime.activeBatchTask());
            assertEquals(4L, countLcaFiles(regionDir));
            session.close();
        }
    }

    @Test
    @DisplayName("LeafAnvilConfig parses and clamps worker-core, duty-cycle, TPS, and overtime settings")
    void configParsesAndClampsPacingSettings(@TempDir Path tempDir) throws Exception {
        assertEquals(1, LeafAnvilConfig.resolveConvertWorkerThreads(0, 2, false));
        assertEquals(4, LeafAnvilConfig.resolveConvertWorkerThreads(0, 16, false));
        assertEquals(4, LeafAnvilConfig.resolveConvertWorkerThreads(0, 32, false));
        assertEquals(2, LeafAnvilConfig.resolveConvertWorkerThreads(0, 16, true));
        assertEquals(6, LeafAnvilConfig.resolveConvertWorkerThreads(6, 16, false));
        assertEquals(8, LeafAnvilConfig.resolveConvertWorkerThreads(32, 8, false));

        Path cfgDir = tempDir.resolve("pacing_cfg");
        Files.createDirectories(cfgDir);
        Files.writeString(
                cfgDir.resolve("config.yml"),
                "convert-worker-threads: 3\n"
                        + "convert-duty-cycle: 0.35\n"
                        + "convert-min-tps: 19.0\n"
                        + "convert-max-overtime-ms: 15\n");
        LeafAnvilConfig parsed = LeafAnvilConfig.loadOrCreate(cfgDir);
        assertEquals(3, parsed.convertWorkerThreads());
        assertEquals(0.35, parsed.convertDutyCycle(), 1e-6);
        assertEquals(19.0, parsed.convertMinTps(), 1e-6);
        assertEquals(15L, parsed.convertMaxOvertimeMs());

        LeafAnvilConfig clamped = new LeafAnvilConfig(
                true, true, true, 1, 0.35, 4671, 0, false, -5, 0.001, 25.0, -99L, null, null, null);
        assertEquals(0, clamped.convertWorkerThreads());
        assertEquals(0.05, clamped.convertDutyCycle(), 1e-6);
        assertEquals(20.0, clamped.convertMinTps(), 1e-6);
        assertEquals(-1L, clamped.convertMaxOvertimeMs());
    }

    private static long countLcaFiles(Path regionDir) throws IOException {
        try (var stream = Files.list(regionDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".lca")).count();
        }
    }

    static byte[] buildSampleMcaBytes() throws IOException {
        LcaRegionWriter.LcaLiveArchive archive =
                LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 0);
        root.put("yPos", -4);
        root.put("zPos", 0);
        root.put("Status", "minecraft:full");

        LinkedHashMap<String, Object> section0 = new LinkedHashMap<>();
        section0.put("Y", (byte) 0);
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", List.of(
                Map.of("Name", "minecraft:air"),
                Map.of("Name", "minecraft:stone")));
        long[] data = new long[256];
        data[0] = 1L;
        blockStates.put("data", data);
        section0.put("block_states", blockStates);
        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", List.of("minecraft:plains"));
        section0.put("biomes", biomes);

        root.put("sections", List.of(section0));
        archive.saveChunkCoW(0, 0, root);
        return LcaRegionWriter.transcodeToMca(archive.toByteArray());
    }

    /**
     * Deterministic {@link TaskScheduler} test double for driving {@code runAsyncTimer} and
     * {@code runAsync} without sleeping threads.
     */
    private static final class ManualPulseTaskScheduler implements TaskScheduler {
        private final List<TimerEntry> timers = new CopyOnWriteArrayList<>();
        private final List<Runnable> deferredWorkers = new ArrayList<>();
        private volatile boolean deferAsyncWorkers = false;

        private record TimerEntry(Runnable task, AtomicBoolean cancelled) implements TaskHandle {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        }

        void setDeferAsyncWorkers(boolean defer) {
            this.deferAsyncWorkers = defer;
        }

        synchronized int queuedWorkerCount() {
            return deferredWorkers.size();
        }

        void drainNextQueuedWorker() {
            Runnable next;
            synchronized (this) {
                next = deferredWorkers.isEmpty() ? null : deferredWorkers.remove(0);
            }
            if (next != null) {
                next.run();
            }
        }

        void drainAllQueuedWorkers() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = deferredWorkers.isEmpty() ? null : deferredWorkers.remove(0);
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }

        void fireTimers() {
            for (TimerEntry entry : timers) {
                if (!entry.isCancelled()) {
                    entry.task().run();
                }
            }
        }

        boolean allTimersCancelled() {
            return !timers.isEmpty() && timers.stream().allMatch(TimerEntry::isCancelled);
        }

        @Override
        public TaskHandle runAsync(Runnable task) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            if (deferAsyncWorkers) {
                synchronized (this) {
                    deferredWorkers.add(() -> {
                        if (!cancelled.get()) {
                            task.run();
                        }
                    });
                }
            } else {
                task.run();
            }
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

        @Override
        public TaskHandle runSync(Runnable task) {
            task.run();
            return new TaskHandle() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }

        @Override
        public TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
            TimerEntry entry = new TimerEntry(task, new AtomicBoolean(false));
            timers.add(entry);
            return entry;
        }

        @Override
        public boolean isPrimaryThread() {
            return false;
        }
    }
}
