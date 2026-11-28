package net.leaf.anvil.fabric.mixin;

import io.github.dailystruggle.anvil.LcaSectionBacking;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.leaf.anvil.common.LcaSectionBridge;
import net.leaf.anvil.common.LcaSectionBridge.PalettedContainerShellBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Live `PalettedContainer` shell Mixin on Fabric (`leafanvil-ADR-002` Section 5.B.3).
 *
 * <p>Enforces three critical runtime invariants:</p>
 * <ol>
 *   <li><b>`ZeroBitStorage` Heap Eviction (`Tier 3`):</b> Evicts vanilla's `SimpleBitStorage`
 *       `long[256..512]` and `Palette` to a shared `0-bit` `ZeroBitStorage` (`long[0]`) shell once
 *       {@link LcaSectionBacking} is attached so the JVM GC reclaims the backing arrays.</li>
 *   <li><b>Multi-block update &amp; AI query interception (`Auditor Gate 2C`):</b> Direct container
 *       reads, writes, {@code getAndSetUnchecked}, {@code count}, {@code getAll}, and
 *       {@code maybeHas} predicates route through {@link LcaSectionBacking}.</li>
 *   <li><b>Non-pinning `Copy-on-Read` for client meshing, chunk saves &amp; vanilla packets (`Invariant 2`):</b>
 *       {@link PalettedContainer#copy()}, {@code pack}, {@code write}, and {@code getSerializedSize}
 *       materialize transient snapshots from `LcaSectionBacking#materializeEphemeralStateIds` without
 *       permanently pinning `SimpleBitStorage` `long[]` onto the live `LevelChunkSection`.</li>
 * </ol>
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, leafanvil-ADR-005, leafanvil-ADR-006, anvil-api-ADR-003, rtp-fabric-ADR-002, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
@Mixin(value = PalettedContainer.class, priority = 1500)
public abstract class MixinPalettedContainer<T> implements PalettedContainerShellBridge {

    @Unique
    private volatile LcaSectionBacking leafanvil$ownerBacking;

    @Unique
    private volatile boolean leafanvil$ephemeralSnapshot;

    @Unique
    private volatile boolean leafanvil$zeroBitShellEvicted;

    @Unique
    private volatile boolean leafanvil$baseUniform;

    @Override
    public LcaSectionBacking leafanvil$getOwnerBacking() {
        return this.leafanvil$ownerBacking;
    }

    @Override
    public void leafanvil$setOwnerBacking(LcaSectionBacking backing) {
        this.leafanvil$ownerBacking = backing;
        if (backing == null) {
            this.leafanvil$zeroBitShellEvicted = false;
            this.leafanvil$baseUniform = false;
        } else {
            this.leafanvil$baseUniform = backing.isBaseUniform();
        }
    }

    @Override
    public boolean leafanvil$isEphemeralSnapshot() {
        return this.leafanvil$ephemeralSnapshot;
    }

    @Override
    public void leafanvil$setEphemeralSnapshot(boolean ephemeral) {
        this.leafanvil$ephemeralSnapshot = ephemeral;
    }

    @Override
    public boolean leafanvil$isZeroBitShellEvicted() {
        return this.leafanvil$zeroBitShellEvicted;
    }

    @Unique
    private static boolean leafanvil$isCleanUniform(LcaSectionBacking backing) {
        return backing != null && backing.isCleanUniform();
    }

    @Unique
    private boolean leafanvil$seedingPreEvictionCopy;

    @Unique
    private static int leafanvil$cachedGlobalBits;

    @Unique
    private static int leafanvil$globalPaletteBits() {
        int bits = leafanvil$cachedGlobalBits;
        if (bits <= 0) {
            int regSize = Math.max(2, Block.BLOCK_STATE_REGISTRY.size());
            bits = 32 - Integer.numberOfLeadingZeros(regSize - 1);
            leafanvil$cachedGlobalBits = bits;
        }
        return bits;
    }

    @Override
    public int leafanvil$evictVanillaBitStorageToZeroBitShell() {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing == null || this.leafanvil$ephemeralSnapshot) {
            return 0;
        }
        int freed = LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                this,
                Math.max(0, backing.uniformStateId())
        );
        this.leafanvil$zeroBitShellEvicted =
                freed > 0 || LcaSectionBridge.inspectPalettedContainerRawLongArrayBytes(this) == 0;
        return freed;
    }
    @Inject(method = "get(III)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void leafanvil$onGet(int x, int y, int z, CallbackInfoReturnable<T> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot) {
            if (((x | y | z) & ~15) == 0) {
                if (!this.leafanvil$zeroBitShellEvicted) {
                    return;
                }
                if (this.leafanvil$baseUniform
                        && backing.dirtyCount() == 0
                        && backing.frozenOverlay() == null) {
                    return;
                }
            }
            int stateId = backing.getStateId(x & 15, y & 15, z & 15);
            cir.setReturnValue((T) Block.stateById(stateId));
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "get(I)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onGetByIndex(int index, CallbackInfoReturnable<T> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot) {
            if ((index & ~4095) == 0) {
                if (!this.leafanvil$zeroBitShellEvicted) {
                    return;
                }
                if (this.leafanvil$baseUniform
                        && backing.dirtyCount() == 0
                        && backing.frozenOverlay() == null) {
                    return;
                }
            }
            int x = index & 15;
            int z = (index >>> 4) & 15;
            int y = (index >>> 8) & 15;
            int stateId = backing.getStateId(x, y, z);
            cir.setReturnValue((T) Block.stateById(stateId));
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
    private void leafanvil$onGetAndSet(int x, int y, int z, T value, CallbackInfoReturnable<T> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && value instanceof BlockState bs) {
            if (!this.leafanvil$zeroBitShellEvicted && (((x | y | z) & ~15) == 0)) {
                return; // Handled in O(1) at RETURN using NMS's exact returned previous BlockState
            }
            int mx = x & 15;
            int my = y & 15;
            int mz = z & 15;
            int prevId = backing.setAndGetPreviousStateId(
                    mx, my, mz, Block.getId(bs), 0L, null
            );
            cir.setReturnValue((T) Block.stateById(prevId));
        }
    }

    @Inject(method = "getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;", at = @At("RETURN"))
    private void leafanvil$onGetAndSetReturn(int x, int y, int z, T value, CallbackInfoReturnable<T> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null
                && !this.leafanvil$ephemeralSnapshot
                && !this.leafanvil$zeroBitShellEvicted
                && (((x | y | z) & ~15) == 0)
                && value instanceof BlockState bs) {
            T prev = cir.getReturnValue();
            int prevId = (prev instanceof BlockState pbs) ? Block.getId(pbs) : 0;
            backing.recordDirtyVoxel(
                    LcaSectionBacking.pos12(x & 15, y & 15, z & 15),
                    prevId,
                    Block.getId(bs),
                    0L
            );
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(
            method = "getAndSetUnchecked(IIILjava/lang/Object;)Ljava/lang/Object;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void leafanvil$onGetAndSetUnchecked(
            int x,
            int y,
            int z,
            T value,
            CallbackInfoReturnable<T> cir
    ) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && value instanceof BlockState bs) {
            if (!this.leafanvil$zeroBitShellEvicted && (((x | y | z) & ~15) == 0)) {
                return;
            }
            int mx = x & 15;
            int my = y & 15;
            int mz = z & 15;
            int prevId = backing.setAndGetPreviousStateId(
                    mx, my, mz, Block.getId(bs), 0L, null
            );
            cir.setReturnValue((T) Block.stateById(prevId));
        }
    }

    @Inject(
            method = "getAndSetUnchecked(IIILjava/lang/Object;)Ljava/lang/Object;",
            at = @At("RETURN"),
            require = 0
    )
    private void leafanvil$onGetAndSetUncheckedReturn(
            int x,
            int y,
            int z,
            T value,
            CallbackInfoReturnable<T> cir
    ) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null
                && !this.leafanvil$ephemeralSnapshot
                && !this.leafanvil$zeroBitShellEvicted
                && (((x | y | z) & ~15) == 0)
                && value instanceof BlockState bs) {
            T prev = cir.getReturnValue();
            int prevId = (prev instanceof BlockState pbs) ? Block.getId(pbs) : 0;
            backing.recordDirtyVoxel(
                    LcaSectionBacking.pos12(x & 15, y & 15, z & 15),
                    prevId,
                    Block.getId(bs),
                    0L
            );
        }
    }

    @Inject(method = "set(IIILjava/lang/Object;)V", at = @At("HEAD"), cancellable = true)
    private void leafanvil$onSet(int x, int y, int z, T value, CallbackInfo ci) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && value instanceof BlockState bs) {
            int mx = x & 15;
            int my = y & 15;
            int mz = z & 15;
            backing.setAndGetPreviousStateId(
                    mx, my, mz, Block.getId(bs), 0L, null
            );
            if (!this.leafanvil$zeroBitShellEvicted && (((x | y | z) & ~15) == 0)) {
                return;
            }
            ci.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "maybeHas", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onMaybeHas(Predicate<T> predicate, CallbackInfoReturnable<Boolean> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && predicate != null) {
            if (!this.leafanvil$zeroBitShellEvicted
                    && backing.dirtyCount() == 0
                    && backing.frozenOverlay() == null) {
                return;
            }
            cir.setReturnValue(backing.testMaybeHas(stateId -> predicate.test((T) Block.stateById(stateId))));
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "count", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onCount(
            PalettedContainer.CountConsumer<T> consumer,
            CallbackInfo ci
    ) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && consumer != null) {
            if (!this.leafanvil$zeroBitShellEvicted
                    && backing.dirtyCount() == 0
                    && backing.frozenOverlay() == null) {
                return;
            }
            if (leafanvil$isCleanUniform(backing) && this.leafanvil$zeroBitShellEvicted) {
                return;
            }
            backing.forEachStateCount((stateId, count) -> {
                if (count > 0) {
                    consumer.accept((T) Block.stateById(stateId), count);
                }
            });
            ci.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onGetAll(Consumer<T> consumer, CallbackInfo ci) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && consumer != null) {
            if (!this.leafanvil$zeroBitShellEvicted
                    && backing.dirtyCount() == 0
                    && backing.frozenOverlay() == null) {
                return;
            }
            int[] voxels = backing.materializeEphemeralStateIds(null, null);
            int prevId = -1;
            T prevState = null;
            for (int pos12 = 0; pos12 < LcaSectionBacking.BULK_4096_CAPACITY; pos12++) {
                int id = voxels[pos12];
                if (id != prevId || prevState == null) {
                    prevId = id;
                    prevState = (T) Block.stateById(id);
                }
                consumer.accept(prevState);
            }
            ci.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "pack", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onPack(
            Strategy<T> strategy,
            CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> cir
    ) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot) {
            if (!this.leafanvil$zeroBitShellEvicted
                    && backing.dirtyCount() == 0
                    && backing.frozenOverlay() == null) {
                return;
            }
            if (leafanvil$isCleanUniform(backing) && this.leafanvil$zeroBitShellEvicted) {
                return;
            }
            PalettedContainer<T> ephemeral = ((PalettedContainer<T>) (Object) this).copy();
            cir.setReturnValue(ephemeral.pack(strategy));
        }
    }

    @Inject(method = "read", at = @At("HEAD"), require = 0)
    private void leafanvil$onRead(FriendlyByteBuf buf, CallbackInfo ci) {
        this.leafanvil$ownerBacking = null;
        this.leafanvil$zeroBitShellEvicted = false;
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onWrite(FriendlyByteBuf buf, CallbackInfo ci) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && this.leafanvil$zeroBitShellEvicted) {
            if (leafanvil$isCleanUniform(backing)) {
                return;
            }
            int globalBits = leafanvil$globalPaletteBits();
            byte[] cleanSlice = backing.peekPrebuiltCleanVanillaWireSlice(globalBits);
            if (cleanSlice != null) {
                buf.writeBytes(cleanSlice);
                ci.cancel();
                return;
            }
            backing.writeVanillaPalettedContainer(
                    globalBits,
                    new LcaSectionBacking.VanillaWireSink() {
                        @Override
                        public void writeByte(int b) {
                            buf.writeByte(b);
                        }

                        @Override
                        public void writeVarInt(int v) {
                            buf.writeVarInt(v);
                        }

                        @Override
                        public void writeLong(long word) {
                            buf.writeLong(word);
                        }
                    }
            );
            ci.cancel();
        }
    }

    @Inject(method = "getSerializedSize", at = @At("HEAD"), cancellable = true, require = 0)
    private void leafanvil$onGetSerializedSize(CallbackInfoReturnable<Integer> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && this.leafanvil$zeroBitShellEvicted) {
            if (leafanvil$isCleanUniform(backing)) {
                return;
            }
            int globalBits = leafanvil$globalPaletteBits();
            byte[] cleanSlice = backing.peekPrebuiltCleanVanillaWireSlice(globalBits);
            if (cleanSlice != null) {
                cir.setReturnValue(cleanSlice.length);
                return;
            }
            cir.setReturnValue(backing.computeVanillaSerializedSize(globalBits));
        }
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "copy", at = @At("RETURN"))
    private void leafanvil$onCopyForMesher(CallbackInfoReturnable<PalettedContainer<T>> cir) {
        LcaSectionBacking backing = this.leafanvil$ownerBacking;
        if (backing != null && !this.leafanvil$ephemeralSnapshot && !this.leafanvil$seedingPreEvictionCopy) {
            PalettedContainer<T> snapshotContainer = cir.getReturnValue();
            if (snapshotContainer instanceof PalettedContainerShellBridge snapshotShell) {
                snapshotShell.leafanvil$setEphemeralSnapshot(true);
                snapshotShell.leafanvil$setOwnerBacking(null);
            }
            if (LcaSectionBridge.isSuppressCopyHydrationForDirectSave()) {
                return;
            }
            if (!this.leafanvil$zeroBitShellEvicted
                    && backing.dirtyCount() == 0
                    && backing.frozenOverlay() == null) {
                return;
            }
            if (leafanvil$isCleanUniform(backing) && this.leafanvil$zeroBitShellEvicted) {
                return;
            }
            if (leafanvil$hydrateEphemeralFromBacking(
                    snapshotContainer,
                    backing,
                    leafanvil$globalPaletteBits()
            )) {
                return;
            }
            int[] ephemeralVoxels = backing.materializeEphemeralStateIds(null, null);
            int initialShellStateId = Math.max(0, backing.uniformStateId());
            boolean hasInitialState = false;
            if (this.leafanvil$zeroBitShellEvicted) {
                for (int pos12 = 0; pos12 < LcaSectionBacking.BULK_4096_CAPACITY; pos12++) {
                    if (ephemeralVoxels[pos12] == initialShellStateId) {
                        hasInitialState = true;
                        break;
                    }
                }
            }
            if (!hasInitialState) {
                initialShellStateId = Math.max(0, ephemeralVoxels[0]);
                LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                        snapshotContainer,
                        initialShellStateId
                );
            }
            int prevId = -1;
            T prevState = null;
            for (int pos12 = 0; pos12 < LcaSectionBacking.BULK_4096_CAPACITY; pos12++) {
                int stateId = ephemeralVoxels[pos12];
                if (stateId != initialShellStateId) {
                    if (stateId != prevId || prevState == null) {
                        prevId = stateId;
                        prevState = (T) Block.stateById(stateId);
                    }
                    int x = pos12 & 15;
                    int z = (pos12 >>> 4) & 15;
                    int y = (pos12 >>> 8) & 15;
                    snapshotContainer.getAndSetUnchecked(x, y, z, prevState);
                }
            }
        }
    }

    @Unique
    private static boolean leafanvil$hydrateEphemeralFromBacking(
            PalettedContainer<?> snapshotContainer,
            LcaSectionBacking backing,
            int globalBits
    ) {
        if (snapshotContainer == null || backing == null) {
            return false;
        }
        try {
            if (backing.isCleanUniform()) {
                LcaSectionBridge.evictPalettedContainerBitStorageToZeroBitShell(
                        snapshotContainer,
                        Math.max(0, backing.uniformStateId())
                );
                return true;
            }
            byte[] cleanSlice = backing.peekPrebuiltCleanVanillaWireSlice(globalBits);
            if (cleanSlice != null) {
                snapshotContainer.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(cleanSlice)));
                return true;
            }
            int estBytes = Math.max(64, backing.computeVanillaSerializedSize(globalBits));
            ByteBuf rawBuf = Unpooled.buffer(estBytes);
            try {
                FriendlyByteBuf fbuf = new FriendlyByteBuf(rawBuf);
                backing.writeVanillaPalettedContainer(
                        globalBits,
                        new LcaSectionBacking.VanillaWireSink() {
                            @Override
                            public void writeByte(int b) {
                                fbuf.writeByte(b);
                            }

                            @Override
                            public void writeVarInt(int v) {
                                fbuf.writeVarInt(v);
                            }

                            @Override
                            public void writeLong(long word) {
                                fbuf.writeLong(word);
                            }
                        }
                );
                snapshotContainer.read(fbuf);
                return true;
            } finally {
                rawBuf.release();
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(MixinPalettedContainer.class.getName()).log(
                    java.util.logging.Level.WARNING,
                    "[LeafAnvil] Single-pass MixinPalettedContainer hydration failed; falling back: " + t.getMessage(),
                    t
            );
            return false;
        }
    }
}
