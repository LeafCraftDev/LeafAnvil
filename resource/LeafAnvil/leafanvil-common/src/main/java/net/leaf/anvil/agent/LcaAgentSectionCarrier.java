package net.leaf.anvil.agent;

/**
 * Unshaded JDK-only `AppClassLoader` carrier interface injected onto `LevelChunkSection` when
 * `-javaagent:plugins/LeafAnvil.jar` is active (`leafanvil-ADR-005`, `leafanvil-ADR-006`).
 *
 * <p>Uses exclusively JDK bootstrap types (`byte[]`, `int[]`, `int`, `long`) so Paper's
 * `ServerClassLoader`, `LeafAnvil`'s shaded `PluginClassLoader`, and `LeafGen`'s unshaded
 * `PluginClassLoader` share direct `.lca` `SectionFrame` baselines and sparse `4-byte` block
 * mutations with zero `LinkageError` or `ClassCastException`.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public interface LcaAgentSectionCarrier {

    byte[] leafanvil$getBaseFrame();

    void leafanvil$mountBaseFrame(byte[] baseFrame, int uniformStateId, int dataVersion, long secDed64);

    void leafanvil$recordBlockMutation(int pos12, int prevStateId19, int newStateId19, long currentTick);

    int leafanvil$getDirtyCount();

    int[] leafanvil$snapshotDirtyOverlay();

    void leafanvil$clearDirtyAfterCompaction(byte[] newBaseFrame, long newSecDed64);

    default int leafanvil$getUniformStateId() {
        return LeafAnvilAgentBootstrap.getUniformStateIdForSection(this);
    }

    default int leafanvil$getDataVersion() {
        return LeafAnvilAgentBootstrap.getDataVersionForSection(this);
    }

    default long leafanvil$getBaselineSecDed64() {
        return LeafAnvilAgentBootstrap.getSecDed64ForSection(this);
    }
}
