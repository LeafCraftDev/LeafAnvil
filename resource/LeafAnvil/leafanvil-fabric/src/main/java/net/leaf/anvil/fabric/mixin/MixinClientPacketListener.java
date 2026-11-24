package net.leaf.anvil.fabric.mixin;

import net.leaf.anvil.fabric.client.LcaClientChunkInstaller;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only {@link ClientPacketListener} Mixin that invokes vanilla {@code enableChunkLight}
 * immediately after {@code applyLightData} for chunks installed via {@code LCA_NET_V1},
 * matching {@code handleLevelChunkWithLight} ordering so chunks mesh once with full lighting
 * ({@code leafanvil-ADR-003}, {@code goldleaf-ADR-005}).
 *
 * @governedBy leafanvil-ADR-003, goldleaf-ADR-005, rtp-fabric-ADR-002, ADR-115
 * @trace REQ-RTP-S-002, REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Shadow
    private ClientLevel level;

    @Shadow
    private void enableChunkLight(LevelChunk chunk, int x, int z) {
        throw new AssertionError();
    }

    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void leafanvil$enablePendingLcaChunkLight(
            int x,
            int z,
            ClientboundLightUpdatePacketData lightData,
            boolean isUpdate,
            CallbackInfo ci
    ) {
        if (isUpdate
                && this.level != null
                && LcaClientChunkInstaller.consumePendingLightEnable(x, z)) {
            LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, false);
            if (chunk != null) {
                this.enableChunkLight(chunk, x, z);
            }
        }
    }
}
