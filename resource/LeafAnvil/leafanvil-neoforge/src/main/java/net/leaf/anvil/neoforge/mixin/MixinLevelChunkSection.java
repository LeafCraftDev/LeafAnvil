package net.leaf.anvil.neoforge.mixin;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import net.leaf.anvil.common.LcaSectionBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Live `LevelChunkSection` Mixin on NeoForge binding {@link LcaSectionBacking} (`leafanvil-ADR-002`
 * & `leafanvil-ADR-003`).
 *
 * <p>Masks `(x & 15)`, `(y & 15)`, `(z & 15)` at entry, evicts `this.states` to a `0-bit`
 * `ZeroBitStorage` shell on attachment, and publishes `nonEmptyBlockCount` after updating
 * `dirtyOverlay` and `dirtyOctantMask64` (`Invariant 1`).</p>
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, ADR-033, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(LevelChunkSection.class)
public abstract class MixinLevelChunkSection implements LcaSectionBridge {

    @Shadow
    private short nonEmptyBlockCount;

    @Shadow
    private short tickingBlockCount;

    @Shadow
    private short tickingFluidCount;

    @Shadow
    @Final
    private PalettedContainer<BlockState> states;

    @Unique
    private volatile LcaSectionBacking leafanvil$backing;

    @Unique
    private volatile boolean leafanvil$baseUniform;

    @Unique
    private volatile boolean leafanvil$shellEvicted;

    @Override
    public LcaSectionBacking leafanvil$getBacking() {
        return this.leafanvil$backing;
    }

    @Override
    public void leafanvil$setBacking(LcaSectionBacking backing) {
        leafanvil$setBacking(backing, true);
    }

    @Override
    public void leafanvil$setBacking(LcaSectionBacking backing, boolean evictToZeroBitShell) {
        this.leafanvil$backing = backing;
        if (backing != null) {
            this.tickingBlockCount = (short) backing.tickingBlockCount();
            this.tickingFluidCount = (short) backing.tickingFluidCount();
            this.nonEmptyBlockCount = (short) backing.nonEmptyBlockCount();
            this.leafanvil$baseUniform = backing.isBaseUniform();
        } else {
            this.leafanvil$baseUniform = false;
            this.leafanvil$shellEvicted = false;
        }
        if (this.states instanceof PalettedContainerShellBridge shell) {
            shell.leafanvil$setOwnerBacking(backing);
            if (backing != null && evictToZeroBitShell) {
                shell.leafanvil$evictVanillaBitStorageToZeroBitShell();
                this.leafanvil$shellEvicted = shell.leafanvil$isZeroBitShellEvicted();
            } else {
                this.leafanvil$shellEvicted = false;
            }
        }
    }

    @Override
    public boolean leafanvil$isZeroBitShellEvicted() {
        return this.leafanvil$shellEvicted;
    }

    @Override
    public int leafanvil$evictToZeroBitShell() {
        if (this.leafanvil$backing != null
                && !this.leafanvil$shellEvicted
                && this.states instanceof PalettedContainerShellBridge shell) {
            int freed = shell.leafanvil$evictVanillaBitStorageToZeroBitShell();
            this.leafanvil$shellEvicted = shell.leafanvil$isZeroBitShellEvicted();
            return freed;
        }
        return 0;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void leafanvil$onGetBlockState(
            int x,
            int y,
            int z,
            CallbackInfoReturnable<BlockState> cir
    ) {
        LcaSectionBacking backing = this.leafanvil$backing;
        if (backing != null) {
            if (((x | y | z) & ~15) == 0) {
                if (!this.leafanvil$shellEvicted) {
                    return;
                }
                if (this.leafanvil$baseUniform
                        && backing.dirtyCount() == 0
                        && backing.frozenOverlay() == null) {
                    return;
                }
            }
            int stateId = backing.getStateId(x & 15, y & 15, z & 15);
            cir.setReturnValue(Block.stateById(stateId));
        }
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private int leafanvil$maskSetX(int x) {
        return x & 15;
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            ordinal = 1,
            argsOnly = true
    )
    private int leafanvil$maskSetY(int y) {
        return y & 15;
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            ordinal = 2,
            argsOnly = true
    )
    private int leafanvil$maskSetZ(int z) {
        return z & 15;
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN")
    )
    private void leafanvil$onSetBlockStateReturnSyncCounts(
            int x,
            int y,
            int z,
            BlockState state,
            boolean useLocks,
            CallbackInfoReturnable<BlockState> cir
    ) {
        LcaSectionBacking backing = this.leafanvil$backing;
        if (backing != null) {
            this.tickingBlockCount = (short) backing.tickingBlockCount();
            this.tickingFluidCount = (short) backing.tickingFluidCount();
            this.nonEmptyBlockCount = (short) backing.nonEmptyBlockCount();
        }
    }

    @Inject(method = "hasOnlyAir", at = @At("HEAD"), cancellable = true)
    private void leafanvil$onHasOnlyAir(CallbackInfoReturnable<Boolean> cir) {
        LcaSectionBacking backing = this.leafanvil$backing;
        if (backing != null) {
            cir.setReturnValue(backing.hasOnlyAir());
        }
    }

    @Inject(method = "recalcBlockCounts", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onRecalcBlockCounts(CallbackInfo ci) {
        LcaSectionBacking backing = this.leafanvil$backing;
        if (backing != null) {
            this.tickingBlockCount = (short) backing.tickingBlockCount();
            this.tickingFluidCount = (short) backing.tickingFluidCount();
            this.nonEmptyBlockCount = (short) backing.nonEmptyBlockCount();
            ci.cancel();
        }
    }

    @Inject(method = "read", at = @At("HEAD"), require = 0)
    private void leafanvil$onReadVanillaPacket(FriendlyByteBuf buf, CallbackInfo ci) {
        this.leafanvil$backing = null;
        if (this.states instanceof PalettedContainerShellBridge shell) {
            shell.leafanvil$setOwnerBacking(null);
        }
    }

    @Inject(method = "write", at = @At("HEAD"))
    private void leafanvil$onWriteVanillaPacketFallback(FriendlyByteBuf buf, CallbackInfo ci) {
        LcaSectionBacking backing = this.leafanvil$backing;
        if (backing != null) {
            this.nonEmptyBlockCount = (short) backing.nonEmptyBlockCount();
        }
    }
}
