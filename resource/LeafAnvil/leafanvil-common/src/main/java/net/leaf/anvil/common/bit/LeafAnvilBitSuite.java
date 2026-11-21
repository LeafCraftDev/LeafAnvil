package net.leaf.anvil.common.bit;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitRunner;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;
import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.net.LcaWireStreamProtocol;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.ClientboundLcaChunkBatchPayload;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.EntryFullChunk;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.SectionWireSlice;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Built-In Test ({@code [LCA bit]}) suite registering {@code PBIT} and {@code CBIT} self-tests and
 * runtime class/reflection compatibility gates for {@code LeafAnvil} and {@code LeafAnvil-Client}
 * ({@code leafanvil-ADR-001}, {@code leafanvil-ADR-002}, {@code leafanvil-ADR-003},
 * {@code anvil-api-ADR-003}, {@code ADR-113}, {@code ADR-126}, {@code S-004}).
 *
 * <p>Wrong or missing runtime class names, unresolvable reflective method/field bindings, or a
 * zero intercepted {@code RegionFileStorage} count on an enabled world are recorded as {@code ERROR}
 * BIT findings denoting platform incompatibility, disabling the affected function (or aborting
 * plugin/mod load when core functionality cannot operate).</p>
 *
 * @governedBy leafanvil-ADR-001
 * @governedBy leafanvil-ADR-002
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-113
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LeafAnvilBitSuite implements BuiltInTestable {

    public static final BitCode LCA_001_CODEC_WIRE_PBIT =
            new BitCode(
                    1,
                    "LCA_CODEC_WIRE_PBIT",
                    Level.ERROR,
                    BitTier.PBIT,
                    "codec",
                    "LcaWireAndSectionCodec",
                    "Verify .lca v2 LcaSectionBacking 3D Hilbert/SEC-DED-64 and LCD2 (0x4C44) wire batch round-trip.");

    public static final BitCode LCA_002_DISK_STORAGE_COMPAT =
            new BitCode(
                    2,
                    "LCA_DISK_STORAGE_COMPAT",
                    Level.ERROR,
                    BitTier.CBIT,
                    "storage",
                    "NmsRegionStorageInterceptor",
                    "Verify NMS RegionFileStorage/RegionFile class compatibility and non-zero intercepted storage count.");

    public static final BitCode LCA_003_SECTION_BRIDGE_COMPAT =
            new BitCode(
                    3,
                    "LCA_SECTION_BRIDGE_COMPAT",
                    Level.ERROR,
                    BitTier.CBIT,
                    "section",
                    "LevelChunkSectionBridge",
                    "Verify NMS Block.getId, LevelChunkSection, and PalettedContainer state extraction compatibility.");

    public static final BitCode LCA_004_NETWORK_CARRIER_COMPAT =
            new BitCode(
                    4,
                    "LCA_NETWORK_CARRIER_COMPAT",
                    Level.ERROR,
                    BitTier.CBIT,
                    "carrier",
                    "PlatformNetworkAndCommandHooks",
                    "Verify platform carrier networking, accessor, and command class bindings.");

    private final LeafAnvilRuntime runtime;
    private final BitRunner runner;
    private final Map<String, Integer> interceptedStoragesByWorld = new ConcurrentHashMap<>();
    private final List<BitFinding> diskCompatibilityFindings = new CopyOnWriteArrayList<>();
    private final List<BitFinding> sectionCompatibilityFindings = new CopyOnWriteArrayList<>();
    private final List<BitFinding> carrierCompatibilityFindings = new CopyOnWriteArrayList<>();

    public LeafAnvilBitSuite(LeafAnvilRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.runner = new BitRunner();
        registerComponents();
    }

    private void registerComponents() {
        runner.register(
                new BitComponent(
                        "lca-codec-wire-pbit",
                        "codec",
                        BitTier.PBIT,
                        BitSideEffect.SAFE,
                        2000L,
                        this::runCodecAndWirePbit));
        runner.register(
                new BitComponent(
                        "lca-compat-pbit",
                        "compat",
                        BitTier.PBIT,
                        BitSideEffect.SAFE,
                        2000L,
                        this::runStartupCompatibilityPbit));
        runner.register(
                new BitComponent(
                        "lca-storage-cbit",
                        "storage",
                        BitTier.CBIT,
                        BitSideEffect.SAFE,
                        2000L,
                        this::runDiskStorageCbit));
        runner.register(
                new BitComponent(
                        "lca-section-cbit",
                        "section",
                        BitTier.CBIT,
                        BitSideEffect.SAFE,
                        2000L,
                        this::runSectionBridgeCbit));
        runner.register(
                new BitComponent(
                        "lca-carrier-cbit",
                        "carrier",
                        BitTier.CBIT,
                        BitSideEffect.SAFE,
                        2000L,
                        this::runCarrierHooksCbit));
    }

    /**
     * Verifies that {@code className} is loadable from {@code loader}; on failure, records a BIT
     * incompatibility finding under {@code code} and disables the corresponding function.
     *
     * @return loaded {@link Class}, or {@code null} if missing/incompatible
     */
    public Class<?> verifyRequiredClass(
            BitCode code,
            String source,
            String className,
            ClassLoader loader
    ) {
        Objects.requireNonNull(code, "code");
        if (className == null || className.isBlank()) {
            recordIncompatibility(
                    code,
                    source != null ? source : "ClassProbe",
                    "<blank>",
                    "Blank class name requested for runtime binding",
                    "valid fully-qualified class name");
            return null;
        }
        if (!className.startsWith("net.leaf.anvil.")
                && !className.startsWith("io.github.dailystruggle.")
                && !className.startsWith("net.minecraft.")
                && !className.startsWith("net.fabricmc.")
                && !className.startsWith("net.neoforged.")
                && !className.startsWith("org.bukkit.")
                && !className.startsWith("ca.spottedleaf.")) {
            recordIncompatibility(
                    code,
                    source != null ? source : "ClassProbe",
                    className,
                    "Disallowed runtime class namespace for '" + className + "'",
                    "net.leaf.anvil.*, io.github.dailystruggle.*, net.minecraft.*, net.fabricmc.*, net.neoforged.*, or org.bukkit.*");
            return null;
        }
        try {
            ClassLoader effectiveLoader = loader != null ? loader : LeafAnvilBitSuite.class.getClassLoader();
            return Class.forName(className, false, effectiveLoader);
        } catch (Throwable t) {
            recordIncompatibility(
                    code,
                    source != null ? source : "ClassProbe",
                    className,
                    "Incompatible or missing runtime class '" + className + "': " + t.getClass().getSimpleName(),
                    className + " present on runtime classpath");
            return null;
        }
    }

    /**
     * Records a runtime class/member or interception incompatibility finding and disables the
     * affected subsystem (or the entire runtime if core functionality cannot operate).
     */
    public void recordIncompatibility(
            BitCode code,
            String source,
            String path,
            String message,
            String expected
    ) {
        Objects.requireNonNull(code, "code");
        BitFinding finding = BitFinding.of(
                code,
                source != null ? source : code.subsystem(),
                path != null ? path : "runtime",
                message != null ? message : "Runtime incompatibility detected",
                expected != null ? expected : "compatible runtime class/method layout",
                code.remediation());

        if (code.id() == LCA_002_DISK_STORAGE_COMPAT.id()) {
            diskCompatibilityFindings.add(finding);
            runtime.disableDiskStorage(finding.message());
        } else if (code.id() == LCA_003_SECTION_BRIDGE_COMPAT.id()) {
            sectionCompatibilityFindings.add(finding);
            runtime.disableSectionAndWireStreaming(finding.message());
        } else if (code.id() == LCA_004_NETWORK_CARRIER_COMPAT.id()) {
            carrierCompatibilityFindings.add(finding);
            runtime.handleCarrierIncompatibility(finding.path(), finding.message());
        } else {
            carrierCompatibilityFindings.add(finding);
            runtime.disableAllFunctions(finding.message());
        }

        runtime.accessor().log(
                java.util.logging.Level.SEVERE,
                "[LCA bit] INCOMPATIBLE " + code.key() + " (" + finding.source() + ":" + finding.path()
                        + ") - " + finding.message() + " [expected: " + finding.expected() + "]");
    }

    /**
     * Records the number of {@code RegionFileStorage} instances intercepted for an attached world
     * strictly for BIT verification. If {@code interceptedCount <= 0} on an enabled disk-storage
     * runtime, records a {@link #LCA_002_DISK_STORAGE_COMPAT} BIT failure and disables disk storage.
     */
    public void recordWorldInterception(String worldName, int interceptedCount) {
        String key = (worldName == null ? "unknown" : worldName.trim().toLowerCase(Locale.ROOT));
        interceptedStoragesByWorld.put(key, interceptedCount);
        if (interceptedCount <= 0) {
            recordIncompatibility(
                    LCA_002_DISK_STORAGE_COMPAT,
                    "NmsRegionStorageInterceptor",
                    "world:" + key,
                    "Intercepted 0 RegionFileStorage instances on world '" + worldName
                            + "' — incompatible ServerLevel/RegionFileStorage field layout",
                    ">= 1 intercepted RegionFileStorage instance (region/, entities/, poi/)");
        }
    }

    /**
     * Returns the BIT-tracked intercepted {@code RegionFileStorage} count for {@code worldName}.
     */
    public int interceptedCountForWorld(String worldName) {
        if (worldName == null) {
            return 0;
        }
        return interceptedStoragesByWorld.getOrDefault(worldName.trim().toLowerCase(Locale.ROOT), 0);
    }

    /**
     * Clears recorded world interception state when a world is detached.
     */
    public void clearWorldInterception(String worldName) {
        if (worldName != null) {
            interceptedStoragesByWorld.remove(worldName.trim().toLowerCase(Locale.ROOT));
        }
    }

    private BitReport runCodecAndWirePbit() {
        List<BitFinding> findings = new ArrayList<>();
        try {
            int[] voxels = new int[4096];
            for (int i = 0; i < 4096; i++) {
                voxels[i] = (i & 15) == 0 ? 2 : 1;
            }
            LcaSectionBacking.EncodedCompactFrame encodedFrame =
                    LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0);
            LcaSectionBacking backing = new LcaSectionBacking(
                    encodedFrame.frameBytes(),
                    encodedFrame.majorityStateId());
            if (backing.getStateId(0, 0, 0) != 2 || backing.getStateId(1, 0, 0) != 1) {
                findings.add(
                        BitFinding.of(
                                LCA_001_CODEC_WIRE_PBIT,
                                "LcaSectionBacking",
                                "encodeCompactSectionFrame",
                                "Section baseFrame round-trip voxel verification failed",
                                "lossless 4096-voxel round-trip",
                                LCA_001_CODEC_WIRE_PBIT.remediation()));
            } else {
                SectionWireSlice slice = SectionWireSlice.nonUniform(
                        0, encodedFrame.frameBytes(), new int[0]);
                EntryFullChunk fullEntry = EntryFullChunk.createWithComputedSyndrome(
                        0,
                        0,
                        1L,
                        List.of(slice),
                        new byte[0]);
                ClientboundLcaChunkBatchPayload batch = new ClientboundLcaChunkBatchPayload(
                        0,
                        0,
                        List.of(fullEntry));
                NegotiatedSession session = new NegotiatedSession(
                        true,
                        LcaWireStreamProtocol.ALL_CAPABILITIES,
                        4096,
                        123456789L);
                byte[] wireBytes = LcaWireStreamProtocol.encodeChunkBatchPayload(batch, session);
                ClientboundLcaChunkBatchPayload decoded =
                        LcaWireStreamProtocol.decodeChunkBatchPayload(wireBytes, session);
                if (decoded.entries().size() != 1) {
                    findings.add(
                            BitFinding.of(
                                    LCA_001_CODEC_WIRE_PBIT,
                                    "LcaWireStreamProtocol",
                                    "encodeChunkBatchPayload",
                                    "Decoded LCD2 wire batch entry count mismatch: " + decoded.entries().size(),
                                    "1 decoded EntryFullChunk",
                                    LCA_001_CODEC_WIRE_PBIT.remediation()));
                }
            }
        } catch (Throwable t) {
            findings.add(
                    BitFinding.of(
                            LCA_001_CODEC_WIRE_PBIT,
                            "LcaWireAndSectionCodec",
                            "pbit",
                            "Exception during .lca codec/wire PBIT: " + t.getMessage(),
                            "clean codec and wire framing round-trip",
                            LCA_001_CODEC_WIRE_PBIT.remediation()));
        }
        if (!findings.isEmpty()) {
            runtime.disableAllFunctions("LCA_001_CODEC_WIRE_PBIT failed");
        }
        return BitReport.of(findings);
    }

    private BitReport runStartupCompatibilityPbit() {
        List<BitFinding> findings = new ArrayList<>();
        findings.addAll(diskCompatibilityFindings);
        findings.addAll(sectionCompatibilityFindings);
        findings.addAll(carrierCompatibilityFindings);
        return BitReport.of(findings);
    }

    private BitReport runDiskStorageCbit() {
        List<BitFinding> findings = new ArrayList<>(diskCompatibilityFindings);
        for (Map.Entry<String, Integer> entry : interceptedStoragesByWorld.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                findings.add(
                        BitFinding.of(
                                LCA_002_DISK_STORAGE_COMPAT,
                                "NmsRegionStorageInterceptor",
                                "world:" + entry.getKey(),
                                "World '" + entry.getKey() + "' has 0 intercepted RegionFileStorage instances",
                                ">= 1 intercepted RegionFileStorage instance",
                                LCA_002_DISK_STORAGE_COMPAT.remediation()));
            }
        }
        return BitReport.of(findings);
    }

    private BitReport runSectionBridgeCbit() {
        return BitReport.of(new ArrayList<>(sectionCompatibilityFindings));
    }

    private BitReport runCarrierHooksCbit() {
        return BitReport.of(new ArrayList<>(carrierCompatibilityFindings));
    }

    @Override
    public PbitResult executePbit() {
        long start = System.nanoTime();
        List<BitResult> results = runner.run(BitSelector.parse("tier:pbit"), Runnable::run);
        BitReport agg = runner.aggregate(results);
        long elapsed = System.nanoTime() - start;
        if (agg.health() == BitHealth.FAILED) {
            return PbitResult.failed(
                    "LeafAnvil",
                    elapsed,
                    "PBIT failed with " + agg.findings().size() + " incompatibility/codec finding(s)",
                    null);
        }
        return PbitResult.ok(
                "LeafAnvil",
                elapsed,
                "PBIT passed (" + results.size() + " components, health=" + agg.health() + ")");
    }

    @Override
    public BitHealth getHealthStatus() {
        List<BitResult> results = runner.run(BitSelector.parse("all"), Runnable::run);
        return runner.aggregate(results).health();
    }

    /**
     * Executes all registered {@code PBIT} and {@code CBIT} components and formats the results
     * for {@code /leafanvil bit} and console diagnostics.
     */
    public List<String> executeAllAndFormat() {
        List<BitResult> results = runner.run(BitSelector.parse("all"), Runnable::run);
        BitReport agg = runner.aggregate(results);
        List<String> lines = new ArrayList<>();
        lines.add(
                "[LCA bit] Health="
                        + agg.health()
                        + " | Components="
                        + results.size()
                        + " | Findings="
                        + agg.findings().size()
                        + " | DiskActive="
                        + runtime.isDiskStorageOperational()
                        + " | SectionWireActive="
                        + runtime.isSectionWireOperational());
        for (BitResult res : results) {
            lines.add(
                    "[LCA bit] "
                            + res.component().tier()
                            + " "
                            + res.component().id()
                            + ": "
                            + res.report().health()
                            + " ("
                            + res.durationMillis()
                            + "ms)");
            for (BitFinding f : res.report().findings()) {
                lines.add(
                        "[LCA bit]   "
                                + f.severity()
                                + " LCA_"
                                + String.format(Locale.ROOT, "%03d", f.codeId())
                                + " ("
                                + f.code()
                                + ") "
                                + f.source()
                                + ":"
                                + f.path()
                                + " - "
                                + f.message());
            }
        }
        return Collections.unmodifiableList(lines);
    }

    public BitRunner runner() {
        return runner;
    }
}
