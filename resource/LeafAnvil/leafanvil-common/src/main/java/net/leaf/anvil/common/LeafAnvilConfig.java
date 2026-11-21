package net.leaf.anvil.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration model and YAML loader for {@code LeafAnvil} ({@code leafanvil-ADR-001},
 * {@code anvil-api-ADR-002}, {@code anvil-api-ADR-003}, {@code REQ-RTP-S-007}).
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 * @trace REQ-RTP-S-007
 */
public final class LeafAnvilConfig {

    private final boolean enabled;
    private final boolean autoMigrateLegacy;
    private final boolean backupMigratedLegacyFiles;
    private final boolean modeBLcd2;
    private final int lcd2Level;
    private final double compactFragmentationThreshold;
    private final int defaultDataVersion;
    private final int maxServerUploadKbps;
    private final boolean coalesceTickFlushes;
    private final int convertWorkerThreads;
    private final double convertDutyCycle;
    private final double convertMinTps;
    private final long convertMaxOvertimeMs;
    private final String msgBusy;
    private final String msgInvalidCommand;
    private final String msgNoPermission;

    public static final int DEFAULT_CONVERT_WORKER_THREADS = 0;
    public static final double DEFAULT_CONVERT_DUTY_CYCLE = 0.50;
    public static final double DEFAULT_CONVERT_MIN_TPS = 18.5;
    public static final long DEFAULT_CONVERT_MAX_OVERTIME_MS = 10L;

    public LeafAnvilConfig(
            boolean enabled,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            boolean modeBLcd2,
            double compactFragmentationThreshold,
            int defaultDataVersion,
            String msgBusy,
            String msgInvalidCommand,
            String msgNoPermission
    ) {
        this(
                enabled,
                autoMigrateLegacy,
                backupMigratedLegacyFiles,
                modeBLcd2 ? 1 : 0,
                compactFragmentationThreshold,
                defaultDataVersion,
                0,
                false,
                msgBusy,
                msgInvalidCommand,
                msgNoPermission);
    }

    public LeafAnvilConfig(
            boolean enabled,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            int lcd2Level,
            double compactFragmentationThreshold,
            int defaultDataVersion,
            String msgBusy,
            String msgInvalidCommand,
            String msgNoPermission
    ) {
        this(
                enabled,
                autoMigrateLegacy,
                backupMigratedLegacyFiles,
                lcd2Level,
                compactFragmentationThreshold,
                defaultDataVersion,
                0,
                false,
                msgBusy,
                msgInvalidCommand,
                msgNoPermission);
    }

    public LeafAnvilConfig(
            boolean enabled,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            int lcd2Level,
            double compactFragmentationThreshold,
            int defaultDataVersion,
            int maxServerUploadKbps,
            boolean coalesceTickFlushes,
            String msgBusy,
            String msgInvalidCommand,
            String msgNoPermission
    ) {
        this(
                enabled,
                autoMigrateLegacy,
                backupMigratedLegacyFiles,
                lcd2Level,
                compactFragmentationThreshold,
                defaultDataVersion,
                maxServerUploadKbps,
                coalesceTickFlushes,
                DEFAULT_CONVERT_WORKER_THREADS,
                DEFAULT_CONVERT_DUTY_CYCLE,
                DEFAULT_CONVERT_MIN_TPS,
                DEFAULT_CONVERT_MAX_OVERTIME_MS,
                msgBusy,
                msgInvalidCommand,
                msgNoPermission);
    }

    public LeafAnvilConfig(
            boolean enabled,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            int lcd2Level,
            double compactFragmentationThreshold,
            int defaultDataVersion,
            int maxServerUploadKbps,
            boolean coalesceTickFlushes,
            int convertWorkerThreads,
            double convertDutyCycle,
            double convertMinTps,
            long convertMaxOvertimeMs,
            String msgBusy,
            String msgInvalidCommand,
            String msgNoPermission
    ) {
        this.enabled = enabled;
        this.autoMigrateLegacy = autoMigrateLegacy;
        this.backupMigratedLegacyFiles = backupMigratedLegacyFiles;
        this.lcd2Level = lcd2Level <= 0 ? 0 : Math.min(22, lcd2Level);
        this.modeBLcd2 = this.lcd2Level > 0;
        this.compactFragmentationThreshold = compactFragmentationThreshold;
        this.defaultDataVersion = defaultDataVersion;
        this.maxServerUploadKbps = Math.max(0, maxServerUploadKbps);
        this.coalesceTickFlushes = coalesceTickFlushes || this.maxServerUploadKbps > 0;
        this.convertWorkerThreads = Math.max(0, convertWorkerThreads);
        this.convertDutyCycle = !Double.isFinite(convertDutyCycle)
                ? DEFAULT_CONVERT_DUTY_CYCLE
                : Math.max(0.05, Math.min(1.0, convertDutyCycle));
        this.convertMinTps = !Double.isFinite(convertMinTps)
                ? DEFAULT_CONVERT_MIN_TPS
                : Math.max(0.0, Math.min(20.0, convertMinTps));
        this.convertMaxOvertimeMs = Math.max(-1L, convertMaxOvertimeMs);
        this.msgBusy = msgBusy != null ? msgBusy : "[LeafAnvil] Another storage task is currently running; please wait.";
        this.msgInvalidCommand = msgInvalidCommand != null ? msgInvalidCommand : "[LeafAnvil] Invalid command or parameter: %arg%";
        this.msgNoPermission = msgNoPermission != null ? msgNoPermission : "[LeafAnvil] You do not have permission to execute this command.";
    }

    /**
     * Resolves the bounded worker permit count for forced batch conversion and healing.
     * {@code configuredWorkers <= 0} selects the automatic quarter-core cap (`1..2` when `C2ME`
     * is present, else `1..4`), matching {@link LcaInMemoryChunkCoordinator}.
     */
    public static int resolveConvertWorkerThreads(
            int configuredWorkers,
            int availableCores,
            boolean c2mePresent
    ) {
        int cores = Math.max(1, availableCores);
        if (configuredWorkers > 0) {
            return Math.min(cores, configuredWorkers);
        }
        return c2mePresent
                ? Math.max(1, Math.min(2, cores / 6))
                : Math.max(1, Math.min(4, cores / 4));
    }

    public static int resolveConvertWorkerThreads(int configuredWorkers, boolean c2mePresent) {
        return resolveConvertWorkerThreads(
                configuredWorkers,
                Runtime.getRuntime().availableProcessors(),
                c2mePresent);
    }

    public static LeafAnvilConfig defaults() {
        return new LeafAnvilConfig(
                true,
                true,
                true,
                1,
                0.35,
                4671,
                "[LeafAnvil] Another storage task is currently running; please wait.",
                "[LeafAnvil] Invalid command or parameter: %arg%",
                "[LeafAnvil] You do not have permission to execute this command.");
    }

    public static LeafAnvilConfig loadOrCreate(Path dataDir) throws IOException {
        if (dataDir == null) {
            return defaults();
        }
        Files.createDirectories(dataDir);
        Path configFile = dataDir.resolve("config.yml");
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, defaultConfigYaml(), StandardCharsets.UTF_8);
        }
        Path messagesFile = dataDir.resolve("messages.yml");
        if (!Files.exists(messagesFile)) {
            Files.writeString(messagesFile, defaultMessagesYaml(), StandardCharsets.UTF_8);
        }
        extractBundledIcd(dataDir);

        Map<String, String> kv = new LinkedHashMap<>();
        parseSimpleYaml(configFile, kv);
        parseSimpleYaml(messagesFile, kv);

        LeafAnvilConfig d = defaults();
        int parsedLcd2Level;
        if (kv.containsKey("lcd2-level")) {
            parsedLcd2Level = parseInt(kv.get("lcd2-level"), d.lcd2Level);
        } else if (kv.containsKey("mode-b-lcd2")) {
            parsedLcd2Level = parseBool(kv.get("mode-b-lcd2"), d.modeBLcd2) ? 1 : 0;
        } else {
            parsedLcd2Level = d.lcd2Level;
        }
        int parsedMaxUploadKbps = parseInt(kv.get("max-server-upload-kbps"), d.maxServerUploadKbps);
        boolean parsedCoalesceFlushes = parseBool(
                kv.get("coalesce-tick-flushes"),
                d.coalesceTickFlushes || parsedMaxUploadKbps > 0
        );
        return new LeafAnvilConfig(
                parseBool(kv.get("enabled"), d.enabled),
                parseBool(kv.get("auto-migrate-legacy"), d.autoMigrateLegacy),
                parseBool(kv.get("backup-migrated-legacy-files"), d.backupMigratedLegacyFiles),
                parsedLcd2Level,
                parseDouble(kv.get("compact-fragmentation-threshold"), d.compactFragmentationThreshold),
                parseInt(kv.get("default-data-version"), d.defaultDataVersion),
                parsedMaxUploadKbps,
                parsedCoalesceFlushes,
                parseInt(kv.get("convert-worker-threads"), d.convertWorkerThreads),
                parseDouble(kv.get("convert-duty-cycle"), d.convertDutyCycle),
                parseDouble(kv.get("convert-min-tps"), d.convertMinTps),
                parseLong(kv.get("convert-max-overtime-ms"), d.convertMaxOvertimeMs),
                kv.getOrDefault("busy", d.msgBusy),
                kv.getOrDefault("invalid-command", d.msgInvalidCommand),
                kv.getOrDefault("no-permission", d.msgNoPermission));
    }

    private static void extractBundledIcd(Path dataDir) {
        ClassLoader cl = LeafAnvilConfig.class.getClassLoader();
        try (InputStream in = cl != null ? cl.getResourceAsStream("docs/ICD.md") : null) {
            if (in != null) {
                Path target = dataDir.resolve("ICD.md");
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static void parseSimpleYaml(Path file, Map<String, String> out) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = stripInlineYamlComment(line.substring(colon + 1)).trim();
            if (val.length() >= 2
                    && ((val.startsWith("\"") && val.endsWith("\""))
                            || (val.startsWith("'") && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }
            out.put(key, val);
        }
    }

    private static String stripInlineYamlComment(String rawVal) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < rawVal.length(); i++) {
            char c = rawVal.charAt(i);
            if (c == '\\' && inDouble && i + 1 < rawVal.length()) {
                i++;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble
                    && (i == 0 || Character.isWhitespace(rawVal.charAt(i - 1)))) {
                return rawVal.substring(0, i);
            }
        }
        return rawVal;
    }

    private static boolean parseBool(String raw, boolean def) {
        if (raw == null || raw.isBlank()) return def;
        return Boolean.parseBoolean(raw.trim());
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String raw, long def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String raw, double def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String defaultConfigYaml() {
        return """
                # LeafAnvil (.lca LeafCraft Anvil World Save Format Plugin & Mod)
                enabled: true
                # Automatically transcode existing .mca / .linear / .b_linear regions to .lca on first open
                auto-migrate-legacy: true
                # Preserve original .mca / .linear / .b_linear files as .bak after migration
                backup-migrated-legacy-files: true
                # Pure-Java LCD2 (0x4C44 Atomic Dictionary + 12-bit Huffman) Mode B compression level for .lca chunk bodies (1 = default Mode B LCD2 with SEC-DED header parity; 0 = Mode A zero-LZ direct slabs)
                lcd2-level: 1
                # Free-slab fragmentation ratio triggering automatic compaction on region close
                compact-fragmentation-threshold: 0.35
                # Default Minecraft DataVersion for newly created empty region headers
                default-data-version: 4671
                # Global usable server upload ceiling in kbps (0 = unlimited; 350 = practical 500 kbps uplink mode)
                max-server-upload-kbps: 0
                # Coalesce outbound per-tick packets into a single TCP flush per player
                coalesce-tick-flushes: false
                # Worker thread cap for /leafanvil convert and /leafanvil heal (0 = auto: 1-2 with C2ME, else 1-4 quarter-cores)
                convert-worker-threads: 0
                # Target active wall-clock duty cycle for batch conversion/healing pulses (0.05..1.0; 0.50 = 50% work / 50% rest)
                convert-duty-cycle: 0.50
                # Pause batch conversion/healing pulses when 1s server TPS drops below this floor (+0.5 TPS resume hysteresis; 0.0 = disabled)
                convert-min-tps: 18.5
                # Pause batch conversion/healing pulses when primary tick overtime exceeds this budget in ms (-1 = disabled)
                convert-max-overtime-ms: 10
                """;
    }

    private static String defaultMessagesYaml() {
        return """
                # LeafAnvil configurable user feedback messages (REQ-RTP-S-007)
                busy: "[LeafAnvil] Another storage task is currently running; please wait."
                invalid-command: "[LeafAnvil] Invalid command or parameter: %arg%"
                no-permission: "[LeafAnvil] You do not have permission to execute this command."
                """;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean autoMigrateLegacy() {
        return autoMigrateLegacy;
    }

    public boolean backupMigratedLegacyFiles() {
        return backupMigratedLegacyFiles;
    }

    public boolean modeBLcd2() {
        return modeBLcd2;
    }

    public int lcd2Level() {
        return lcd2Level;
    }

    public double compactFragmentationThreshold() {
        return compactFragmentationThreshold;
    }

    public int defaultDataVersion() {
        return defaultDataVersion;
    }

    public int maxServerUploadKbps() {
        return maxServerUploadKbps;
    }

    public boolean coalesceTickFlushes() {
        return coalesceTickFlushes;
    }

    public int convertWorkerThreads() {
        return convertWorkerThreads;
    }

    public int effectiveConvertWorkerThreads(boolean c2mePresent) {
        return resolveConvertWorkerThreads(convertWorkerThreads, c2mePresent);
    }

    public int effectiveConvertWorkerThreads() {
        return effectiveConvertWorkerThreads(LcaInMemoryChunkCoordinator.detectC2mePresent());
    }

    public double convertDutyCycle() {
        return convertDutyCycle;
    }

    public double convertMinTps() {
        return convertMinTps;
    }

    public long convertMaxOvertimeMs() {
        return convertMaxOvertimeMs;
    }

    public String msgBusy() {
        return msgBusy;
    }

    public String msgInvalidCommand() {
        return msgInvalidCommand;
    }

    public String msgNoPermission() {
        return msgNoPermission;
    }
}
