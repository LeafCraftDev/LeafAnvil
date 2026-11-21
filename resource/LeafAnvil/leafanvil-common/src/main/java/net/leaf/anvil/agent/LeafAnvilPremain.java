package net.leaf.anvil.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Dual-mode `-javaagent:plugins/LeafAnvil.jar` premain entrypoint (`leafanvil-ADR-005`).
 *
 * <p>Executes on `AppClassLoader` before Paperclip unpacks `org.bukkit.*` or `net.minecraft.*`,
 * extracting a minimal zero-dependency bootstrap bridge jar (`LcaAgentSectionCarrier` +
 * `LeafAnvilAgentBootstrap`) onto `BootstrapClassLoader` so `PlatformClassLoader`-parented server
 * `URLClassLoader`s and sibling `PluginClassLoader`s share direct `.lca` section/chunk hooks
 * without `NoClassDefFoundError` or `LinkageError`.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LeafAnvilPremain {

    private static final System.Logger LOGGER = System.getLogger(LeafAnvilPremain.class.getName());
    private static final String[] BOOTSTRAP_BRIDGE_ENTRIES = {
            "net/leaf/anvil/agent/LcaAgentSectionCarrier.class",
            "net/leaf/anvil/agent/LeafAnvilAgentBootstrap.class",
            "net/leaf/anvil/agent/LeafAnvilAgentBootstrap$CarrierState.class"
    };

    private static final AtomicBoolean AGENT_ACTIVE = new AtomicBoolean(false);
    private static volatile Instrumentation instrumentationInstance;

    private LeafAnvilPremain() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        if (inst == null) {
            return;
        }
        if (AGENT_ACTIVE.compareAndSet(false, true)) {
            instrumentationInstance = inst;
            registerBootstrapCarrierBridge(inst);
            markBootstrapAgentActive();
            String customPkg = (agentArgs != null && !agentArgs.isBlank()) ? agentArgs.trim() : null;
            inst.addTransformer(new LeafAnvilSectionClassTransformer(customPkg), false);
        }
    }

    private static void registerBootstrapCarrierBridge(Instrumentation inst) {
        Path bridgeJar = null;
        try {
            ClassLoader cl = LeafAnvilPremain.class.getClassLoader();
            bridgeJar = Files.createTempFile("leafanvil-agent-bridge-", ".jar");
            bridgeJar.toFile().deleteOnExit();
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(bridgeJar))) {
                for (String entryName : BOOTSTRAP_BRIDGE_ENTRIES) {
                    try (InputStream in = (cl != null)
                            ? cl.getResourceAsStream(entryName)
                            : ClassLoader.getSystemResourceAsStream(entryName)) {
                        if (in == null) {
                            throw new IOException("Missing agent bridge class resource: " + entryName);
                        }
                        jos.putNextEntry(new JarEntry(entryName));
                        in.transferTo(jos);
                        jos.closeEntry();
                    }
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(bridgeJar.toFile()));
        } catch (Throwable t) {
            if (bridgeJar != null) {
                try {
                    Files.deleteIfExists(bridgeJar);
                } catch (IOException ignoredCleanup) {
                    // Best-effort temp file cleanup after failure
                }
            }
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "[LeafAnvil Agent] Failed registering bootstrap classloader bridge jar: " + t.getMessage(),
                    t
            );
        }
    }

    private static void markBootstrapAgentActive() {
        try {
            LeafAnvilAgentBootstrap.markAgentActive();
        } catch (Throwable t) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "[LeafAnvil Agent] Failed marking bootstrap agent active: " + t.getMessage(),
                    t
            );
        }
    }

    public static boolean isAgentActive() {
        return AGENT_ACTIVE.get() || isBootstrapAgentActive();
    }

    private static boolean isBootstrapAgentActive() {
        try {
            return LeafAnvilAgentBootstrap.isAgentActive();
        } catch (Throwable t) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "[LeafAnvil Agent] Failed reading bootstrap agent state: " + t.getMessage(),
                    t
            );
            return false;
        }
    }

    public static Instrumentation instrumentation() {
        return instrumentationInstance;
    }
}
