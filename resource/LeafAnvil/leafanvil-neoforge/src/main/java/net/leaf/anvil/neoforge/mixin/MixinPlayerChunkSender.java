package net.leaf.anvil.neoforge.mixin;

import net.leaf.anvil.common.LeafAnvilRuntime;
import net.leaf.anvil.common.net.LcaWireStreamProtocol.NegotiatedSession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts `PlayerChunkSender#sendNextChunks` on NeoForge so negotiated `LCA_NET_V1` players are
 * tracked through {@link LeafAnvilRuntime#chunkCoordinator()} while unmodified vanilla clients
 * remain on the standard packet path (`leafanvil-ADR-003` Section 3.3).
 *
 * @governedBy leafanvil-ADR-003, leafanvil-ADR-002, ADR-033, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(PlayerChunkSender.class)
public abstract class MixinPlayerChunkSender {

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void leafanvil$onSendNextChunks(
            ServerPlayer player,
            CallbackInfo ci
    ) {
        if (player == null) {
            return;
        }
        LeafAnvilRuntime runtime = LeafAnvilRuntime.activeInstance();
        if (runtime == null) {
            return;
        }
        NegotiatedSession session = runtime.chunkCoordinator().getPlayerSession(player.getUUID());
        if (session.isLcaStreamActive() && player.level() instanceof ServerLevel) {
            runtime.chunkCoordinator().getOrCreateRegionFence(
                    player.level().dimension().identifier().toString(),
                    player.chunkPosition().x() >> 5,
                    player.chunkPosition().z() >> 5
            );
        }
    }
}
