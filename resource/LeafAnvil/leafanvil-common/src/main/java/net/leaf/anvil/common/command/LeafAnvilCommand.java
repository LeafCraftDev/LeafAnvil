package net.leaf.anvil.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.server.api.AbstractModServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessor;
import net.leaf.anvil.common.LeafAnvilConfig;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.LeafAnvilWorldConverter;
import net.leaf.anvil.common.LeafAnvilWorldConverter.OperationReport;
import net.leaf.anvil.common.LeafAnvilWorldConverter.PacingOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Platform-neutral `/leafanvil` (alias `/lca`) command tree built on `CommandsAPI`
 * (`commands-api-ADR-001`, `leafanvil-ADR-001`, `anvil-api-ADR-003`).
 *
 * <ul>
 *   <li>`/leafanvil status` - Inspect attached worlds, BIT health, open `.lca` handles, and migrated regions</li>
 *   <li>`/leafanvil bit` - Execute Built-In Test (`[LCA bit]`) PBIT and CBIT compatibility diagnostics</li>
 *   <li>{@code /leafanvil convert [world=<name|all>] [format=<lca|mca>] [workers=<n|auto>]} - Paced region conversion between `.mca`/`.linear` and `.lca`</li>
 *   <li>{@code /leafanvil compact [world=<name|all>]} - Flush and compact fragmented `.lca` `256B` slabs</li>
 *   <li>{@code /leafanvil heal [world=<name|all>] [workers=<n|auto>]} - Paced 4-tier SEC-DED + reverse-scan + quarantine + CoW rollback bit-rot recovery</li>
 *   <li>`/leafanvil pause` / `/leafanvil resume` / `/leafanvil cancel` - Cooperative batch task controls</li>
 *   <li>`/leafanvil reload` - Reload `config.yml` and `messages.yml`</li>
 * </ul>
 *
 * @governedBy commands-api-ADR-001
 * @governedBy leafanvil-ADR-001
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 * @trace REQ-RTP-S-007
 */
public final class LeafAnvilCommand implements TreeCommand {

    private final String name;
    private final ServerAccessor accessor;
    private final LeafAnvilRuntime runtime;
    private final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();
    private final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();
    private final StatusSub statusSub;

    public LeafAnvilCommand(String name, ServerAccessor accessor, LeafAnvilRuntime runtime) {
        this.name = (name == null || name.isBlank()) ? "leafanvil" : name.trim();
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.statusSub = new StatusSub(this);

        addSubCommand(this.statusSub);
        addSubCommand(new BitSub(this));
        addSubCommand(new ConvertSub(this));
        addSubCommand(new CompactSub(this));
        addSubCommand(new HealSub(this));
        addSubCommand(new PauseSub(this));
        addSubCommand(new ResumeSub(this));
        addSubCommand(new CancelSub(this));
        addSubCommand(new ReloadSub(this));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String permission() {
        return "leafanvil.admin";
    }

    @Override
    public String description() {
        return "Manage .lca (LeafCraft Anvil) world save format, migration, slab compaction, and SEC-DED bit-rot healing.";
    }

    @Override
    public CommandsAPICommand parent() {
        return null;
    }

    @Override
    public long avgTime() {
        return 0L;
    }

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
        return parameterLookup;
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
        return commandLookup;
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return true;
        }
        return statusSub.onCommand(callerId, parameterValues, null);
    }

    @Override
    public void msgNoPermission(UUID callerId, String permission) {
        String raw = runtime.config().msgNoPermission();
        accessor.log(Level.WARNING, raw + " (" + permission + ")");
        accessor.sendMessage(callerId, raw, true);
    }

    @Override
    public void msgInvalidCommand(UUID callerId, String argument) {
        String raw = runtime.config().msgInvalidCommand().replace("%arg%", String.valueOf(argument));
        accessor.log(Level.WARNING, raw);
        accessor.sendMessage(callerId, raw, true);
    }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        String raw = runtime.config().msgInvalidCommand()
                .replace("%arg%", parameterName + ":" + parameterValue);
        accessor.log(Level.WARNING, raw);
        accessor.sendMessage(callerId, raw, true);
    }

    CommandParameter createWorldParameter() {
        return new CommandParameter(
                "leafanvil.admin",
                "Target world name or 'all'",
                (callerId, value) -> value != null && !value.isBlank()
        ) {
            @Override
            public Set<String> values() {
                Set<String> names = new LinkedHashSet<>(runtime.worldSessions().keySet());
                names.add("all");
                return names;
            }
        };
    }

    CommandParameter createWorkersParameter() {
        return new CommandParameter(
                "leafanvil.admin",
                "Concurrent converter worker permits ('auto' or 1..64)",
                (callerId, value) -> isValidWorkersArgument(value)
        ) {
            @Override
            public Set<String> values() {
                return Set.of("auto", "1", "2", "4");
            }
        };
    }

    static boolean isValidWorkersArgument(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if ("auto".equalsIgnoreCase(trimmed)) {
            return true;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed >= 1 && parsed <= 64;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static int parseWorkersOverride(Map<String, List<String>> params) {
        List<String> vals = params != null ? params.get("workers") : null;
        if (vals == null || vals.isEmpty() || vals.get(0) == null) {
            return 0;
        }
        String raw = vals.get(0).trim();
        if (raw.isEmpty() || "auto".equalsIgnoreCase(raw)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    String resolveSessionLabel(LcaRegionFileStorage.LcaWorldStorageSession session) {
        for (Map.Entry<String, LcaRegionFileStorage.LcaWorldStorageSession> e : runtime.worldSessions().entrySet()) {
            if (e.getValue() == session) {
                return e.getKey();
            }
        }
        Path dir = session.dimensionDirectory();
        Path fileName = dir != null ? dir.getFileName() : null;
        return fileName != null ? fileName.toString() : "world";
    }

    boolean hasLiveServerPulseScheduler() {
        return accessor.scheduler().isPresent() || accessor instanceof AbstractModServerAccessor;
    }

    List<LcaRegionFileStorage.LcaWorldStorageSession> resolveTargetSessions(Map<String, List<String>> params) {
        List<String> worldVals = params != null ? params.get("world") : null;
        String target = (worldVals != null && !worldVals.isEmpty()) ? worldVals.get(0) : "all";
        if (target == null || target.isBlank() || "all".equalsIgnoreCase(target)) {
            return new ArrayList<>(runtime.worldSessions().values());
        }
        LcaRegionFileStorage.LcaWorldStorageSession single = runtime.getSession(target);
        return single != null ? List.of(single) : List.of();
    }

    private abstract static class BaseSub implements TreeCommand {
        final LeafAnvilCommand parent;
        private final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();
        private final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

        BaseSub(LeafAnvilCommand parent) {
            this.parent = parent;
        }

        @Override
        public String permission() {
            return "leafanvil.admin";
        }

        @Override
        public CommandsAPICommand parent() {
            return parent;
        }

        @Override
        public long avgTime() {
            return 0L;
        }

        @Override
        public Map<String, CommandParameter> getParameterLookup() {
            return parameterLookup;
        }

        @Override
        public Map<String, CommandsAPICommand> getCommandLookup() {
            return commandLookup;
        }

        @Override
        public void msgNoPermission(UUID callerId, String permission) {
            parent.msgNoPermission(callerId, permission);
        }

        @Override
        public void msgInvalidCommand(UUID callerId, String argument) {
            parent.msgInvalidCommand(callerId, argument);
        }

        @Override
        public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
            parent.msgBadParameter(callerId, parameterName, parameterValue);
        }
    }

    private static final class StatusSub extends BaseSub {
        StatusSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "status";
        }

        @Override
        public String description() {
            return "Display .lca world storage status and open region counters.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            LeafAnvilConfig cfg = parent.runtime.config();
            Map<String, LcaRegionFileStorage.LcaWorldStorageSession> sessions = parent.runtime.worldSessions();
            String workersText = cfg.convertWorkerThreads() <= 0
                    ? cfg.effectiveConvertWorkerThreads() + "(auto)"
                    : String.valueOf(cfg.effectiveConvertWorkerThreads());
            parent.accessor.sendMessage(callerId, "[LeafAnvil] Format: .lca (enabled=" + cfg.enabled()
                    + ", autoMigrate=" + cfg.autoMigrateLegacy()
                    + ", lcd2Level=" + cfg.lcd2Level()
                    + ", modeBLcd2=" + cfg.modeBLcd2()
                    + ", convertWorkers=" + workersText
                    + ", dutyCycle=" + String.format(Locale.ROOT, "%.2f", cfg.convertDutyCycle())
                    + ", attachedWorlds=" + sessions.size()
                    + ", bitHealth=" + parent.runtime.bitSuite().getHealthStatus() + ")", false);
            LeafAnvilRuntime.BatchTaskHandle activeTask = parent.runtime.activeBatchTask();
            if (activeTask != null) {
                String state = activeTask.isPaused() ? "PAUSED" : "RUNNING";
                parent.accessor.sendMessage(callerId, " - activeTask=" + activeTask.operationName()
                        + " [" + state + "] " + activeTask.progressSummary(), false);
            }
            for (Map.Entry<String, LcaRegionFileStorage.LcaWorldStorageSession> e : sessions.entrySet()) {
                LcaRegionFileStorage.LcaWorldStorageSession s = e.getValue();
                parent.accessor.sendMessage(callerId, " - " + e.getKey()
                        + ": openRegions=" + s.totalOpenRegionCount()
                        + ", migrated=" + s.totalMigratedRegionCount()
                        + " (" + s.dimensionDirectory() + ")", false);
            }
            return true;
        }
    }

    private static final class BitSub extends BaseSub {
        BitSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "bit";
        }

        @Override
        public String description() {
            return "Execute Built-In Test ([LCA bit]) PBIT/CBIT codec and runtime compatibility diagnostics.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            List<String> lines = parent.runtime.bitSuite().executeAllAndFormat();
            for (String line : lines) {
                parent.accessor.sendMessage(callerId, line, false);
            }
            return true;
        }
    }

    private static final class ConvertSub extends BaseSub {
        ConvertSub(LeafAnvilCommand parent) {
            super(parent);
            addParameter("world", parent.createWorldParameter());
            addParameter("format", new CommandParameter(
                    "leafanvil.admin",
                    "Target region format (lca or mca)",
                    (id, v) -> v != null && Set.of("lca", "mca").contains(v.toLowerCase(Locale.ROOT))
            ) {
                @Override
                public Set<String> values() {
                    return Set.of("lca", "mca");
                }
            });
            addParameter("workers", parent.createWorkersParameter());
        }

        @Override
        public String name() {
            return "convert";
        }

        @Override
        public String description() {
            return "Convert world region files between .mca/.linear and .lca.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            List<String> fmtVals = parameterValues != null ? parameterValues.get("format") : null;
            String format = (fmtVals != null && !fmtVals.isEmpty())
                    ? fmtVals.get(0).toLowerCase(Locale.ROOT)
                    : "lca";
            int workersOverride = parseWorkersOverride(parameterValues);
            List<LcaRegionFileStorage.LcaWorldStorageSession> targets = parent.resolveTargetSessions(parameterValues);
            if (targets.isEmpty()) {
                parent.accessor.sendMessage(callerId, "[LeafAnvil] No matching attached worlds found.", true);
                return true;
            }
            parent.runtime.beginPulsedBatchTask(callerId, "convert(" + format + ")", (handle, releaseLease) -> {
                AtomicInteger totalConverted = new AtomicInteger();
                AtomicLong totalBefore = new AtomicLong();
                AtomicLong totalAfter = new AtomicLong();
                LeafAnvilConfig cfg = parent.runtime.config();

                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (LcaRegionFileStorage.LcaWorldStorageSession s : targets) {
                    String worldLabel = parent.resolveSessionLabel(s);
                    chain = chain.thenCompose(ignored -> {
                        if (handle.isCancelled()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        try {
                            s.flush();
                        } catch (IOException e) {
                            parent.accessor.log(
                                    Level.WARNING,
                                    "[LeafAnvil] Flush error in " + s.dimensionDirectory() + ": " + e.getMessage(),
                                    e);
                            return CompletableFuture.completedFuture(null);
                        }
                        PacingOptions pacing = PacingOptions.forRuntime(
                                parent.runtime,
                                workersOverride,
                                handle,
                                snap -> {
                                    String summary = snap.formatSummary(worldLabel);
                                    handle.updateProgressSummary(summary);
                                    if (snap.processedCandidates() > 0
                                            && snap.processedCandidates() < snap.totalCandidates()) {
                                        parent.accessor.sendMessage(callerId, "[LeafAnvil] " + summary, false);
                                    }
                                });
                        CompletableFuture<OperationReport> step = "mca".equals(format)
                                ? LeafAnvilWorldConverter.startConvertWorldToMcaAsync(
                                        s.dimensionDirectory(), cfg.backupMigratedLegacyFiles(), s, pacing, handle)
                                : LeafAnvilWorldConverter.startConvertWorldToLcaAsync(
                                        s.dimensionDirectory(), cfg.lcd2Level(), cfg.backupMigratedLegacyFiles(), s, pacing, handle);
                        return step.handle((rep, err) -> {
                            if (err != null) {
                                Throwable cause = err.getCause() != null ? err.getCause() : err;
                                parent.accessor.log(
                                        Level.WARNING,
                                        "[LeafAnvil] Conversion error in " + s.dimensionDirectory() + ": " + cause.getMessage(),
                                        cause);
                            } else if (rep != null) {
                                totalConverted.addAndGet(rep.filesConvertedOrModified());
                                totalBefore.addAndGet(rep.bytesBefore());
                                totalAfter.addAndGet(rep.bytesAfter());
                            }
                            return null;
                        });
                    });
                }

                CompletableFuture<Void> finalized = chain.whenComplete((ignored, err) -> {
                    releaseLease.run();
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        parent.accessor.log(
                                Level.WARNING, "[LeafAnvil] Conversion pipeline failed: " + cause.getMessage(), cause);
                        parent.accessor.sendMessage(
                                callerId, "[LeafAnvil] Error during conversion: " + cause.getMessage(), true);
                        return;
                    }
                    if (handle.isCancelled()) {
                        parent.accessor.sendMessage(callerId, "[LeafAnvil] Conversion cancelled after converting "
                                + totalConverted.get() + " region files to ." + format
                                + " (" + totalBefore.get() + " B -> " + totalAfter.get() + " B).", false);
                    } else {
                        parent.accessor.sendMessage(callerId, "[LeafAnvil] Converted " + totalConverted.get()
                                + " region files to ." + format + " (" + totalBefore.get() + " B -> " + totalAfter.get() + " B).", false);
                    }
                });
                if (!parent.hasLiveServerPulseScheduler()) {
                    finalized.join();
                }
            });
            return true;
        }
    }

    private static final class CompactSub extends BaseSub {
        CompactSub(LeafAnvilCommand parent) {
            super(parent);
            addParameter("world", parent.createWorldParameter());
        }

        @Override
        public String name() {
            return "compact";
        }

        @Override
        public String description() {
            return "Flush and compact fragmented 256B CoW slabs across open .lca regions.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            List<LcaRegionFileStorage.LcaWorldStorageSession> targets = parent.resolveTargetSessions(parameterValues);
            parent.runtime.runBatchTaskAsync(callerId, () -> {
                int compacted = 0;
                double threshold = parent.runtime.config().compactFragmentationThreshold();
                for (LcaRegionFileStorage.LcaWorldStorageSession s : targets) {
                    try {
                        compacted += s.compactOpenRegions(threshold);
                        s.flush();
                    } catch (IOException e) {
                        parent.accessor.log(Level.WARNING, "[LeafAnvil] Compaction error in " + s.dimensionDirectory() + ": " + e.getMessage(), e);
                    }
                }
                parent.accessor.sendMessage(callerId, "[LeafAnvil] Compacted and flushed " + compacted + " open .lca region files.", false);
            });
            return true;
        }
    }

    private static final class HealSub extends BaseSub {
        HealSub(LeafAnvilCommand parent) {
            super(parent);
            addParameter("world", parent.createWorldParameter());
            addParameter("workers", parent.createWorkersParameter());
        }

        @Override
        public String name() {
            return "heal";
        }

        @Override
        public String description() {
            return "Run 4-tier SEC-DED bit-rot verification and self-healing across .lca regions.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            int workersOverride = parseWorkersOverride(parameterValues);
            List<LcaRegionFileStorage.LcaWorldStorageSession> targets = parent.resolveTargetSessions(parameterValues);
            parent.runtime.beginPulsedBatchTask(callerId, "heal", (handle, releaseLease) -> {
                AtomicInteger scanned = new AtomicInteger();
                AtomicInteger modified = new AtomicInteger();
                AtomicInteger repairs = new AtomicInteger();

                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (LcaRegionFileStorage.LcaWorldStorageSession s : targets) {
                    String worldLabel = parent.resolveSessionLabel(s);
                    chain = chain.thenCompose(ignored -> {
                        if (handle.isCancelled()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        try {
                            s.flush();
                        } catch (IOException e) {
                            parent.accessor.log(
                                    Level.WARNING,
                                    "[LeafAnvil] Flush error in " + s.dimensionDirectory() + ": " + e.getMessage(),
                                    e);
                            return CompletableFuture.completedFuture(null);
                        }
                        PacingOptions pacing = PacingOptions.forRuntime(
                                parent.runtime,
                                workersOverride,
                                handle,
                                snap -> {
                                    String summary = snap.formatSummary(worldLabel);
                                    handle.updateProgressSummary(summary);
                                    if (snap.processedCandidates() > 0
                                            && snap.processedCandidates() < snap.totalCandidates()) {
                                        parent.accessor.sendMessage(callerId, "[LeafAnvil] " + summary, false);
                                    }
                                });
                        return LeafAnvilWorldConverter.startHealAndVerifyWorldAsync(
                                        s.dimensionDirectory(), s, pacing, handle)
                                .handle((rep, err) -> {
                                    if (err != null) {
                                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                                        parent.accessor.log(
                                                Level.WARNING,
                                                "[LeafAnvil] Bit-rot heal error in " + s.dimensionDirectory() + ": " + cause.getMessage(),
                                                cause);
                                    } else if (rep != null) {
                                        scanned.addAndGet(rep.filesScanned());
                                        modified.addAndGet(rep.filesConvertedOrModified());
                                        repairs.addAndGet(rep.bitRotRepairs());
                                    }
                                    return null;
                                });
                    });
                }

                CompletableFuture<Void> finalized = chain.whenComplete((ignored, err) -> {
                    releaseLease.run();
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        parent.accessor.log(
                                Level.WARNING, "[LeafAnvil] Bit-rot heal pipeline failed: " + cause.getMessage(), cause);
                        parent.accessor.sendMessage(
                                callerId, "[LeafAnvil] Error during bit-rot heal: " + cause.getMessage(), true);
                        return;
                    }
                    if (handle.isCancelled()) {
                        parent.accessor.sendMessage(callerId, "[LeafAnvil] Bit-rot heal cancelled after scanning "
                                + scanned.get() + " .lca files; healed " + modified.get()
                                + " files (" + repairs.get() + " total tier 1-4 repairs).", false);
                    } else {
                        parent.accessor.sendMessage(callerId, "[LeafAnvil] Scanned " + scanned.get()
                                + " .lca files; healed " + modified.get() + " files (" + repairs.get() + " total tier 1-4 repairs).", false);
                    }
                });
                if (!parent.hasLiveServerPulseScheduler()) {
                    finalized.join();
                }
            });
            return true;
        }
    }

    private static final class PauseSub extends BaseSub {
        PauseSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "pause";
        }

        @Override
        public String description() {
            return "Cooperatively pause the active conversion or healing batch task.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            LeafAnvilRuntime.BatchTaskHandle active = parent.runtime.activeBatchTask();
            if (active == null) {
                parent.accessor.sendMessage(callerId, "[LeafAnvil] No active storage task is currently running.", true);
                return true;
            }
            if (active.pause()) {
                parent.accessor.sendMessage(
                        callerId,
                        "[LeafAnvil] Paused " + active.operationName() + " (" + active.progressSummary() + ").",
                        false);
            } else {
                parent.accessor.sendMessage(
                        callerId, "[LeafAnvil] Active " + active.operationName() + " task is already paused.", true);
            }
            return true;
        }
    }

    private static final class ResumeSub extends BaseSub {
        ResumeSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "resume";
        }

        @Override
        public String description() {
            return "Resume a paused conversion or healing batch task.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            LeafAnvilRuntime.BatchTaskHandle active = parent.runtime.activeBatchTask();
            if (active == null) {
                parent.accessor.sendMessage(callerId, "[LeafAnvil] No active storage task is currently running.", true);
                return true;
            }
            if (active.resume()) {
                parent.accessor.sendMessage(
                        callerId, "[LeafAnvil] Resumed " + active.operationName() + " (" + active.progressSummary() + ").", false);
            } else {
                parent.accessor.sendMessage(
                        callerId, "[LeafAnvil] Active " + active.operationName() + " task is not paused.", true);
            }
            return true;
        }
    }

    private static final class CancelSub extends BaseSub {
        CancelSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "cancel";
        }

        @Override
        public String description() {
            return "Cooperatively cancel the active conversion or healing batch task.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            LeafAnvilRuntime.BatchTaskHandle active = parent.runtime.activeBatchTask();
            if (active == null) {
                parent.accessor.sendMessage(callerId, "[LeafAnvil] No active storage task is currently running.", true);
                return true;
            }
            if (active.cancel()) {
                parent.accessor.sendMessage(
                        callerId,
                        "[LeafAnvil] Cancelling " + active.operationName() + "; in-flight region writes will finish safely.",
                        false);
            } else {
                parent.accessor.sendMessage(
                        callerId, "[LeafAnvil] Cancellation already requested for " + active.operationName() + ".", true);
            }
            return true;
        }
    }

    private static final class ReloadSub extends BaseSub {
        ReloadSub(LeafAnvilCommand parent) {
            super(parent);
        }

        @Override
        public String name() {
            return "reload";
        }

        @Override
        public String description() {
            return "Reload LeafAnvil config.yml and messages.yml.";
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return parent.runtime.runBatchTaskAsync(callerId, () -> {
                parent.runtime.load();
                parent.accessor.sendMessage(callerId, "[LeafAnvil] Reloaded config.yml and messages.yml.", false);
            });
        }
    }
}
