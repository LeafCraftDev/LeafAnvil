package net.leaf.anvil.neoforge.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.leaf.anvil.common.LcaInMemoryChunkCoordinator;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Netty `CompressionEncoder` Mixin on NeoForge bypassing per-packet `Zlib` `Deflater` when streaming
 * an already `LCD2`-compressed (`0x4C44`) `ClientboundLcaChunkBatchPayload` (`leafanvil-ADR-003` Section 3.3).
 *
 * @governedBy leafanvil-ADR-003, ADR-033, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(CompressionEncoder.class)
public abstract class MixinCompressionEncoder {

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void leafanvil$bypassZlibForLcd2Batch(
            ChannelHandlerContext ctx,
            ByteBuf in,
            ByteBuf out,
            CallbackInfo ci
    ) {
        if (LcaInMemoryChunkCoordinator.isOutboundLcaBatchBypassActive()) {
            VarInt.write(out, 0);
            out.writeBytes(in);
            ci.cancel();
        }
    }
}
