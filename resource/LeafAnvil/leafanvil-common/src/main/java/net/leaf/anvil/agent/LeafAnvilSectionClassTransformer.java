package net.leaf.anvil.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency JVMS load-time {@link ClassFileTransformer} for Paper/Folia `-javaagent` mode
 * (`leafanvil-ADR-005`).
 *
 * <p>Transforms `LevelChunkSection` (injecting {@link LcaAgentSectionCarrier} + `setBlockState`
 * sparse dirty mutation capture) and `SerializableChunkData` / `ChunkSerializer` (routing `write`,
 * `copyOf`, and `parse` through {@link LeafAnvilAgentBootstrap} `MethodHandle` hooks while keeping
 * original NMS method bodies and `StackMapTable`s byte-for-byte intact via method-name aliasing).</p>
 *
 * <p>Fail-safe contract (`REQ-RTP-S-004`): if an exotic fork alters expected descriptors or class
 * structure, logs a single structured `WARNING` via {@link LeafAnvilAgentBootstrap#recordDescriptorFallbackWarning}
 * and returns {@code null} (untouched bytecode) so persistence falls back cleanly to Tier 1
 * `NmsRegionStorageInterceptor` + `LcaStreamingNbtEncoder`.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LeafAnvilSectionClassTransformer implements ClassFileTransformer {

    public static final String NMS_LEVEL_CHUNK_SECTION = "net/minecraft/world/level/chunk/LevelChunkSection";
    public static final String NMS_SERIALIZABLE_CHUNK_DATA = "net/minecraft/world/level/chunk/storage/SerializableChunkData";
    public static final String NMS_CHUNK_SERIALIZER = "net/minecraft/world/level/chunk/storage/ChunkSerializer";

    private static final String CARRIER_INTERFACE = "net/leaf/anvil/agent/LcaAgentSectionCarrier";
    private static final String BOOTSTRAP_INTERNAL = "net/leaf/anvil/agent/LeafAnvilAgentBootstrap";
    private static final String CARRIER_FIELD_NAME = "leafanvil$carrierState";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String OBJECT_INTERNAL = "java/lang/Object";

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_VOLATILE = 0x0040;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_SYNTHETIC = 0x1000;

    private final String customTargetPackagePrefix;

    public LeafAnvilSectionClassTransformer() {
        this(null);
    }

    /**
     * Test-friendly constructor accepting an optional package prefix (e.g. {@code "test/nms/"}) so
     * synthetic `LevelChunkSection` and `SerializableChunkData` fixtures can be transformed inside
     * isolated `URLClassLoader` hierarchies.
     */
    public LeafAnvilSectionClassTransformer(String customTargetPackagePrefix) {
        this.customTargetPackagePrefix = customTargetPackagePrefix;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        if (className == null || classfileBuffer == null || classBeingRedefined != null) {
            return null;
        }
        TargetKind kind = classifyTarget(className);
        if (kind == null) {
            return null;
        }
        try {
            return switch (kind) {
                case LEVEL_CHUNK_SECTION -> transformLevelChunkSection(className, classfileBuffer);
                case SERIALIZABLE_CHUNK_DATA -> transformChunkCodecClass(className, classfileBuffer);
            };
        } catch (Throwable t) {
            LeafAnvilAgentBootstrap.recordDescriptorFallbackWarning(
                    className, "transformer error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private enum TargetKind {
        LEVEL_CHUNK_SECTION,
        SERIALIZABLE_CHUNK_DATA
    }

    private TargetKind classifyTarget(String className) {
        if (NMS_LEVEL_CHUNK_SECTION.equals(className)) {
            return TargetKind.LEVEL_CHUNK_SECTION;
        }
        if (NMS_SERIALIZABLE_CHUNK_DATA.equals(className) || NMS_CHUNK_SERIALIZER.equals(className)) {
            return TargetKind.SERIALIZABLE_CHUNK_DATA;
        }
        if (customTargetPackagePrefix != null && className.startsWith(customTargetPackagePrefix)) {
            if (className.endsWith("/LevelChunkSection")) {
                return TargetKind.LEVEL_CHUNK_SECTION;
            }
            if (className.endsWith("/SerializableChunkData") || className.endsWith("/ChunkSerializer")) {
                return TargetKind.SERIALIZABLE_CHUNK_DATA;
            }
        }
        return null;
    }

    public byte[] transformLevelChunkSection(String className, byte[] rawClass) throws IOException {
        ParsedClassFile cf = ParsedClassFile.parse(rawClass);
        if ((cf.accessFlags & ACC_INTERFACE) != 0) {
            LeafAnvilAgentBootstrap.recordDescriptorFallbackWarning(className, "unexpected interface flag");
            return null;
        }
        for (int ifaceIdx : cf.interfaces) {
            if (CARRIER_INTERFACE.equals(cf.resolveClassInternalName(ifaceIdx))) {
                return null; // Already transformed
            }
        }

        // Locate setBlockState: prefer 5-arg (IIIL<BlockState>;Z)L<BlockState>;, fallback to 4-arg (IIIL<BlockState>;)L<BlockState>;
        MethodEntry targetSetBlockState = null;
        boolean fiveArg = false;
        for (MethodEntry m : cf.methods) {
            if ((m.accessFlags & (ACC_STATIC | ACC_ABSTRACT)) != 0) {
                continue;
            }
            String name = cf.utf8(m.nameIndex);
            String desc = cf.utf8(m.descIndex);
            if ("setBlockState".equals(name)) {
                String stateDesc5 = extractSetBlockStateType(desc, true);
                if (stateDesc5 != null) {
                    targetSetBlockState = m;
                    fiveArg = true;
                    break;
                }
                String stateDesc4 = extractSetBlockStateType(desc, false);
                if (stateDesc4 != null && targetSetBlockState == null) {
                    targetSetBlockState = m;
                    fiveArg = false;
                }
            }
        }

        if (targetSetBlockState == null) {
            LeafAnvilAgentBootstrap.recordDescriptorFallbackWarning(
                    className, "no matching setBlockState(int,int,int,BlockState[,boolean]) descriptor found");
            return null;
        }

        String origDesc = cf.utf8(targetSetBlockState.descIndex);
        int origFlags = targetSetBlockState.accessFlags;
        String renamedOrigName = "leafanvil$orig$setBlockState";

        // Rename original method in-place so its Code + StackMapTable remain 100% untouched!
        targetSetBlockState.nameIndex = cf.addUtf8(renamedOrigName);
        targetSetBlockState.accessFlags = (origFlags & ~(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE | ACC_SYNTHETIC;

        // Add LcaAgentSectionCarrier interface + volatile Object leafanvil$carrierState field
        cf.interfaces.add(cf.addClassInfo(CARRIER_INTERFACE));
        cf.fields.add(new MemberBytes(
                ACC_PRIVATE | ACC_VOLATILE | ACC_SYNTHETIC,
                cf.addUtf8(CARRIER_FIELD_NAME),
                cf.addUtf8(OBJECT_DESC),
                new byte[0]
        ));

        int fieldRefCarrier = cf.addFieldref(cf.thisClassInternalName(), CARRIER_FIELD_NAME, OBJECT_DESC);
        int origSetMethodRef = cf.addMethodref(cf.thisClassInternalName(), renamedOrigName, origDesc);
        int hookOnSetBlockState = cf.addMethodref(
                BOOTSTRAP_INTERNAL,
                "onSectionSetBlockState",
                "(Ljava/lang/Object;Ljava/lang/Object;IIILjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // Emit zero-branch wrapper for setBlockState -> calls leafanvil$orig$setBlockState then LeafAnvilAgentBootstrap.onSectionSetBlockState
        ByteArrayOutputStream setCode = new ByteArrayOutputStream(48);
        int prevLocalSlot = fiveArg ? 6 : 5;
        setCode.write(0x2A); // aload_0
        setCode.write(0x1B); // iload_1
        setCode.write(0x1C); // iload_2
        setCode.write(0x1D); // iload_3
        setCode.write(0x19); setCode.write(4); // aload 4
        if (fiveArg) {
            setCode.write(0x15); setCode.write(5); // iload 5
        }
        writeShortOp(setCode, 0xB7, origSetMethodRef); // invokespecial leafanvil$orig$setBlockState
        setCode.write(0x3A); setCode.write(prevLocalSlot); // astore prevLocalSlot

        setCode.write(0x2A); // aload_0 (receiver for putfield)
        setCode.write(0x2A); // aload_0 (arg0: section)
        setCode.write(0x2A); // aload_0 (receiver for getfield)
        writeShortOp(setCode, 0xB4, fieldRefCarrier); // getfield leafanvil$carrierState (arg1: stateObj)
        setCode.write(0x1B); // iload_1 (arg2: x)
        setCode.write(0x1C); // iload_2 (arg3: y)
        setCode.write(0x1D); // iload_3 (arg4: z)
        setCode.write(0x19); setCode.write(prevLocalSlot); // aload prevLocalSlot (arg5: prevStateObj)
        setCode.write(0x19); setCode.write(4); // aload 4 (arg6: newStateObj)
        writeShortOp(setCode, 0xB8, hookOnSetBlockState); // invokestatic onSectionSetBlockState
        writeShortOp(setCode, 0xB5, fieldRefCarrier); // putfield leafanvil$carrierState
        setCode.write(0x19); setCode.write(prevLocalSlot); // aload prevLocalSlot
        setCode.write(0xB0); // areturn

        cf.newMethods.add(cf.buildCodeMethod(
                origFlags,
                "setBlockState",
                origDesc,
                10,
                prevLocalSlot + 1,
                setCode.toByteArray(),
                null
        ));

        // Emit the 6 zero-branch LcaAgentSectionCarrier interface methods
        emitCarrierMethods(cf, fieldRefCarrier);

        return cf.toByteArray();
    }

    private static void emitCarrierMethods(ParsedClassFile cf, int fieldRefCarrier) throws IOException {
        // 1. byte[] leafanvil$getBaseFrame()
        int mGetBase = cf.addMethodref(BOOTSTRAP_INTERNAL, "getBaseFrame", "(Ljava/lang/Object;)[B");
        ByteArrayOutputStream c1 = new ByteArrayOutputStream(16);
        c1.write(0x2A); // aload_0
        writeShortOp(c1, 0xB4, fieldRefCarrier);
        writeShortOp(c1, 0xB8, mGetBase);
        c1.write(0xB0); // areturn
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$getBaseFrame", "()[B", 2, 1, c1.toByteArray(), null));

        // 2. void leafanvil$mountBaseFrame(byte[] baseFrame, int uniformStateId, int dataVersion, long secDed64)
        int mMount = cf.addMethodref(BOOTSTRAP_INTERNAL, "mountBaseFrame", "(Ljava/lang/Object;Ljava/lang/Object;[BIIJ)Ljava/lang/Object;");
        ByteArrayOutputStream c2 = new ByteArrayOutputStream(24);
        c2.write(0x2A); // aload_0 (putfield receiver)
        c2.write(0x2A); // aload_0 (section)
        c2.write(0x2A); // aload_0 (getfield receiver)
        writeShortOp(c2, 0xB4, fieldRefCarrier);
        c2.write(0x2B); // aload_1 (baseFrame)
        c2.write(0x1C); // iload_2 (uniformStateId)
        c2.write(0x1D); // iload_3 (dataVersion)
        c2.write(0x16); c2.write(4); // lload 4 (secDed64 takes slots 4 & 5)
        writeShortOp(c2, 0xB8, mMount);
        writeShortOp(c2, 0xB5, fieldRefCarrier);
        c2.write(0xB1); // return
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$mountBaseFrame", "([BIIJ)V", 8, 6, c2.toByteArray(), null));

        // 3. void leafanvil$recordBlockMutation(int pos12, int prevStateId19, int newStateId19, long currentTick)
        int mRecord = cf.addMethodref(BOOTSTRAP_INTERNAL, "recordCarrierMutation", "(Ljava/lang/Object;Ljava/lang/Object;IIIJ)Ljava/lang/Object;");
        ByteArrayOutputStream c3 = new ByteArrayOutputStream(24);
        c3.write(0x2A); // aload_0 (putfield receiver)
        c3.write(0x2A); // aload_0 (section)
        c3.write(0x2A); // aload_0 (getfield receiver)
        writeShortOp(c3, 0xB4, fieldRefCarrier);
        c3.write(0x1B); // iload_1 (pos12)
        c3.write(0x1C); // iload_2 (prevStateId19)
        c3.write(0x1D); // iload_3 (newStateId19)
        c3.write(0x16); c3.write(4); // lload 4 (currentTick takes slots 4 & 5)
        writeShortOp(c3, 0xB8, mRecord);
        writeShortOp(c3, 0xB5, fieldRefCarrier);
        c3.write(0xB1); // return
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$recordBlockMutation", "(IIIJ)V", 8, 6, c3.toByteArray(), null));

        // 4. int leafanvil$getDirtyCount()
        int mDirtyCount = cf.addMethodref(BOOTSTRAP_INTERNAL, "getDirtyCount", "(Ljava/lang/Object;)I");
        ByteArrayOutputStream c4 = new ByteArrayOutputStream(16);
        c4.write(0x2A);
        writeShortOp(c4, 0xB4, fieldRefCarrier);
        writeShortOp(c4, 0xB8, mDirtyCount);
        c4.write(0xAC); // ireturn
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$getDirtyCount", "()I", 2, 1, c4.toByteArray(), null));

        // 5. int[] leafanvil$snapshotDirtyOverlay()
        int mSnap = cf.addMethodref(BOOTSTRAP_INTERNAL, "snapshotDirtyOverlay", "(Ljava/lang/Object;)[I");
        ByteArrayOutputStream c5 = new ByteArrayOutputStream(16);
        c5.write(0x2A);
        writeShortOp(c5, 0xB4, fieldRefCarrier);
        writeShortOp(c5, 0xB8, mSnap);
        c5.write(0xB0); // areturn
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$snapshotDirtyOverlay", "()[I", 2, 1, c5.toByteArray(), null));

        // 6. void leafanvil$clearDirtyAfterCompaction(byte[] newBaseFrame, long newSecDed64)
        int mClear = cf.addMethodref(BOOTSTRAP_INTERNAL, "clearDirtyAfterCompaction", "(Ljava/lang/Object;[BJ)Ljava/lang/Object;");
        ByteArrayOutputStream c6 = new ByteArrayOutputStream(20);
        c6.write(0x2A); // aload_0 (putfield receiver)
        c6.write(0x2A); // aload_0 (getfield receiver)
        writeShortOp(c6, 0xB4, fieldRefCarrier);
        c6.write(0x2B); // aload_1 (newBaseFrame)
        c6.write(0x20); // lload_2 (newSecDed64 takes slots 2 & 3)
        writeShortOp(c6, 0xB8, mClear);
        writeShortOp(c6, 0xB5, fieldRefCarrier);
        c6.write(0xB1); // return
        cf.newMethods.add(cf.buildCodeMethod(ACC_PUBLIC | ACC_SYNTHETIC, "leafanvil$clearDirtyAfterCompaction", "([BJ)V", 5, 4, c6.toByteArray(), null));
    }

    public byte[] transformChunkCodecClass(String className, byte[] rawClass) throws IOException {
        ParsedClassFile cf = ParsedClassFile.parse(rawClass);
        if ((cf.accessFlags & ACC_INTERFACE) != 0) {
            LeafAnvilAgentBootstrap.recordDescriptorFallbackWarning(className, "unexpected interface flag");
            return null;
        }
        // Check idempotency
        for (MethodEntry m : cf.methods) {
            if (cf.utf8(m.nameIndex).startsWith("leafanvil$orig$")) {
                return null;
            }
        }

        int hookedCount = 0;
        for (MethodEntry m : cf.methods) {
            if ((m.accessFlags & ACC_ABSTRACT) != 0) {
                continue;
            }
            String name = cf.utf8(m.nameIndex);
            String desc = cf.utf8(m.descIndex);
            boolean isStatic = (m.accessFlags & ACC_STATIC) != 0;

            // 1. Instance write() -> L<CompoundTag>; (SerializableChunkData#write in 1.21.4+ / 26.1+)
            if (!isStatic && "write".equals(name) && desc.startsWith("()L") && desc.endsWith(";")) {
                String retInternal = desc.substring(3, desc.length() - 1);
                wrapRefReturningMethod(cf, m, "tryDirectSerialize1", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, false, retInternal);
                hookedCount++;
            }
            // 2. Static write(ServerLevel, ChunkAccess) -> CompoundTag OR copyOf/fromChunk(ServerLevel, ChunkAccess) -> SerializableChunkData
            else if (isStatic && ("write".equals(name) || "copyOf".equals(name) || "fromChunk".equals(name))) {
                String[] refArgsAndRet = parseAllObjectDescriptor(desc);
                if (refArgsAndRet != null && refArgsAndRet.length == 3) {
                    wrapRefReturningMethod(
                            cf, m, "tryDirectSerialize2",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            2, true, refArgsAndRet[2]);
                    hookedCount++;
                }
            }
            // 3. Static parse(LevelHeightAccessor, PalettedContainerFactory, CompoundTag) -> SerializableChunkData
            else if (isStatic && "parse".equals(name)) {
                String[] refArgsAndRet = parseAllObjectDescriptor(desc);
                if (refArgsAndRet != null && refArgsAndRet.length == 4) {
                    wrapRefReturningMethod(
                            cf, m, "tryDirectHydrate3",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            3, true, refArgsAndRet[3]);
                    hookedCount++;
                }
            }
        }

        if (hookedCount == 0) {
            LeafAnvilAgentBootstrap.recordDescriptorFallbackWarning(
                    className, "no matching write/copyOf/fromChunk/parse chunk codec descriptors found");
            return null;
        }
        return cf.toByteArray();
    }

    /**
     * Wraps a 1/2/3-object-arg reference-returning method using `same_locals_1_stack_item_frame`
     * (`tag = 64 + targetPc`) so if the `LeafAnvilAgentBootstrap` hook returns non-null, the
     * expensive NMS `CompoundTag` method is bypassed completely!
     */
    private static void wrapRefReturningMethod(
            ParsedClassFile cf,
            MethodEntry m,
            String bootstrapHookName,
            String bootstrapHookDesc,
            int argSlotCount,
            boolean isStatic,
            String returnInternalName
    ) throws IOException {
        String origName = cf.utf8(m.nameIndex);
        String origDesc = cf.utf8(m.descIndex);
        int origFlags = m.accessFlags;
        String renamedOrig = "leafanvil$orig$" + origName;

        m.nameIndex = cf.addUtf8(renamedOrig);
        m.accessFlags = (origFlags & ~(ACC_PUBLIC | ACC_PROTECTED)) | ACC_PRIVATE | ACC_SYNTHETIC;

        int hookRef = cf.addMethodref(BOOTSTRAP_INTERNAL, bootstrapHookName, bootstrapHookDesc);
        int origRef = cf.addMethodref(cf.thisClassInternalName(), renamedOrig, origDesc);
        int returnClassIdx = cf.addClassInfo(returnInternalName);
        int objectClassIdx = cf.addClassInfo(OBJECT_INTERNAL);

        ByteArrayOutputStream code = new ByteArrayOutputStream(32);
        for (int i = 0; i < argSlotCount; i++) {
            writeAload(code, i);
        }
        writeShortOp(code, 0xB8, hookRef); // invokestatic LeafAnvilAgentBootstrap.tryDirect...
        code.write(0x59); // dup
        int ifNullPos = code.size();
        code.write(0xC6); code.write(0x00); code.write(0x07); // ifnull +7 (jumps over checkcast[3] + areturn[1] = +7 from ifNullPos)
        writeShortOp(code, 0xC0, returnClassIdx); // checkcast <returnInternalName>
        code.write(0xB0); // areturn

        int fallbackPc = code.size();
        if (fallbackPc - ifNullPos != 7 || fallbackPc >= 64) {
            throw new IllegalStateException("Unexpected fallbackPc=" + fallbackPc);
        }
        code.write(0x57); // pop (consumes the duplicated null reference)
        for (int i = 0; i < argSlotCount; i++) {
            writeAload(code, i);
        }
        writeShortOp(code, isStatic ? 0xB8 : 0xB7, origRef); // invokestatic / invokespecial leafanvil$orig$...
        code.write(0xB0); // areturn

        // StackMapTable: 1 entry -> same_locals_1_stack_item_frame (tag = 64 + fallbackPc, ITEM_Object(java/lang/Object))
        ByteArrayOutputStream smt = new ByteArrayOutputStream(8);
        DataOutputStream smtOut = new DataOutputStream(smt);
        smtOut.writeShort(1); // number_of_entries = 1
        smtOut.writeByte(64 + fallbackPc);
        smtOut.writeByte(7); // ITEM_Object
        smtOut.writeShort(objectClassIdx);

        cf.newMethods.add(cf.buildCodeMethod(
                origFlags,
                origName,
                origDesc,
                Math.max(2, argSlotCount),
                argSlotCount,
                code.toByteArray(),
                smt.toByteArray()
        ));
    }

    private static void writeAload(ByteArrayOutputStream out, int slot) {
        if (slot >= 0 && slot <= 3) {
            out.write(0x2A + slot);
        } else {
            out.write(0x19);
            out.write(slot & 0xFF);
        }
    }

    private static void writeShortOp(ByteArrayOutputStream out, int opcode, int cpIndex) {
        out.write(opcode & 0xFF);
        out.write((cpIndex >>> 8) & 0xFF);
        out.write(cpIndex & 0xFF);
    }

    private static String extractSetBlockStateType(String desc, boolean requireTrailingBoolean) {
        if (!desc.startsWith("(IIIL")) {
            return null;
        }
        int semi = desc.indexOf(';', 5);
        if (semi <= 5) {
            return null;
        }
        String stateType = desc.substring(4, semi + 1); // "L...;"
        String expected = requireTrailingBoolean
                ? "(III" + stateType + "Z)" + stateType
                : "(III" + stateType + ")" + stateType;
        return expected.equals(desc) ? stateType : null;
    }

    private static String[] parseAllObjectDescriptor(String desc) {
        if (desc == null || !desc.startsWith("(")) {
            return null;
        }
        List<String> types = new ArrayList<>(4);
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            if (desc.charAt(i) != 'L') {
                return null;
            }
            int semi = desc.indexOf(';', i);
            if (semi < 0) {
                return null;
            }
            types.add(desc.substring(i + 1, semi));
            i = semi + 1;
        }
        if (i >= desc.length() || desc.charAt(i) != ')') {
            return null;
        }
        String ret = desc.substring(i + 1);
        if (!ret.startsWith("L") || !ret.endsWith(";")) {
            return null;
        }
        types.add(ret.substring(1, ret.length() - 1));
        return types.toArray(new String[0]);
    }

    // =========================================================================
    // Minimal JVMS ClassFile Parser & Writer (Supports Java 8..25+, v52..v69+)
    // =========================================================================

    private static final class MemberBytes {
        int accessFlags;
        int nameIndex;
        int descIndex;
        final byte[] attributesRaw;

        MemberBytes(int accessFlags, int nameIndex, int descIndex, byte[] attributesRaw) {
            this.accessFlags = accessFlags;
            this.nameIndex = nameIndex;
            this.descIndex = descIndex;
            this.attributesRaw = attributesRaw;
        }
    }

    private static final class MethodEntry {
        int accessFlags;
        int nameIndex;
        int descIndex;
        final byte[] attributesRaw;

        MethodEntry(int accessFlags, int nameIndex, int descIndex, byte[] attributesRaw) {
            this.accessFlags = accessFlags;
            this.nameIndex = nameIndex;
            this.descIndex = descIndex;
            this.attributesRaw = attributesRaw;
        }
    }

    private static final class ParsedClassFile {
        final int minorVersion;
        final int majorVersion;
        final List<byte[]> cpEntries = new ArrayList<>(); // index 0 unused (1-based JVMS CP)
        final List<String> cpUtf8Values = new ArrayList<>();
        final int accessFlags;
        final int thisClass;
        final int superClass;
        final List<Integer> interfaces = new ArrayList<>();
        final List<MemberBytes> fields = new ArrayList<>();
        final List<MethodEntry> methods = new ArrayList<>();
        final List<MemberBytes> newMethods = new ArrayList<>();
        final byte[] classAttributesRaw;

        private ParsedClassFile(
                int minorVersion,
                int majorVersion,
                int accessFlags,
                int thisClass,
                int superClass,
                byte[] classAttributesRaw
        ) {
            this.minorVersion = minorVersion;
            this.majorVersion = majorVersion;
            this.accessFlags = accessFlags;
            this.thisClass = thisClass;
            this.superClass = superClass;
            this.classAttributesRaw = classAttributesRaw;
        }

        static ParsedClassFile parse(byte[] bytes) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            if (buf.remaining() < 10 || buf.getInt() != 0xCAFEBABE) {
                throw new IOException("Invalid classfile magic");
            }
            int minor = buf.getShort() & 0xFFFF;
            int major = buf.getShort() & 0xFFFF;
            int cpCount = buf.getShort() & 0xFFFF;

            List<byte[]> entries = new ArrayList<>(cpCount + 32);
            List<String> utf8s = new ArrayList<>(cpCount + 32);
            entries.add(null);
            utf8s.add(null);

            int idx = 1;
            while (idx < cpCount) {
                int start = buf.position();
                int tag = buf.get() & 0xFF;
                String utfVal = null;
                switch (tag) {
                    case 1 -> { // CONSTANT_Utf8
                        int len = buf.getShort() & 0xFFFF;
                        byte[] strBytes = new byte[len];
                        buf.get(strBytes);
                        utfVal = decodeModifiedUtf8(strBytes);
                    }
                    case 3, 4 -> buf.position(buf.position() + 4); // Integer, Float
                    case 5, 6 -> buf.position(buf.position() + 8); // Long, Double (2 slots)
                    case 7, 8, 16, 19, 20 -> buf.position(buf.position() + 2); // Class, String, MethodType, Module, Package
                    case 9, 10, 11, 12, 17, 18 -> buf.position(buf.position() + 4); // Refs, NameAndType, Dynamic, InvokeDynamic
                    case 15 -> buf.position(buf.position() + 3); // MethodHandle
                    default -> throw new IOException("Unsupported constant-pool tag: " + tag);
                }
                int end = buf.position();
                byte[] rawEntry = new byte[end - start];
                System.arraycopy(bytes, start, rawEntry, 0, rawEntry.length);
                entries.add(rawEntry);
                utf8s.add(utfVal);
                idx++;
                if (tag == 5 || tag == 6) {
                    entries.add(null);
                    utf8s.add(null);
                    idx++;
                }
            }

            int accessFlags = buf.getShort() & 0xFFFF;
            int thisClass = buf.getShort() & 0xFFFF;
            int superClass = buf.getShort() & 0xFFFF;
            int ifaceCount = buf.getShort() & 0xFFFF;
            List<Integer> ifaces = new ArrayList<>(ifaceCount + 2);
            for (int i = 0; i < ifaceCount; i++) {
                ifaces.add(buf.getShort() & 0xFFFF);
            }

            int fieldCount = buf.getShort() & 0xFFFF;
            List<MemberBytes> parsedFields = new ArrayList<>(fieldCount + 2);
            for (int i = 0; i < fieldCount; i++) {
                int fFlags = buf.getShort() & 0xFFFF;
                int fName = buf.getShort() & 0xFFFF;
                int fDesc = buf.getShort() & 0xFFFF;
                byte[] attrs = sliceAttributesBlock(bytes, buf);
                parsedFields.add(new MemberBytes(fFlags, fName, fDesc, attrs));
            }

            int methodCount = buf.getShort() & 0xFFFF;
            List<MethodEntry> parsedMethods = new ArrayList<>(methodCount + 8);
            for (int i = 0; i < methodCount; i++) {
                int mFlags = buf.getShort() & 0xFFFF;
                int mName = buf.getShort() & 0xFFFF;
                int mDesc = buf.getShort() & 0xFFFF;
                byte[] attrs = sliceAttributesBlock(bytes, buf);
                parsedMethods.add(new MethodEntry(mFlags, mName, mDesc, attrs));
            }

            int classAttrsStart = buf.position();
            byte[] classAttrs = new byte[bytes.length - classAttrsStart];
            System.arraycopy(bytes, classAttrsStart, classAttrs, 0, classAttrs.length);

            ParsedClassFile cf = new ParsedClassFile(minor, major, accessFlags, thisClass, superClass, classAttrs);
            cf.cpEntries.addAll(entries);
            cf.cpUtf8Values.addAll(utf8s);
            cf.interfaces.addAll(ifaces);
            cf.fields.addAll(parsedFields);
            cf.methods.addAll(parsedMethods);
            return cf;
        }

        private static byte[] sliceAttributesBlock(byte[] raw, ByteBuffer buf) {
            int start = buf.position();
            int attrCount = buf.getShort() & 0xFFFF;
            for (int a = 0; a < attrCount; a++) {
                buf.getShort(); // attribute_name_index
                int len = buf.getInt();
                buf.position(buf.position() + len);
            }
            int end = buf.position();
            byte[] slice = new byte[end - start];
            System.arraycopy(raw, start, slice, 0, slice.length);
            return slice;
        }

        String utf8(int cpIdx) {
            return cpUtf8Values.get(cpIdx);
        }

        String resolveClassInternalName(int classInfoIdx) {
            byte[] entry = cpEntries.get(classInfoIdx);
            int nameIdx = ((entry[1] & 0xFF) << 8) | (entry[2] & 0xFF);
            return utf8(nameIdx);
        }

        String thisClassInternalName() {
            return resolveClassInternalName(thisClass);
        }

        int addUtf8(String value) throws IOException {
            for (int i = 1; i < cpUtf8Values.size(); i++) {
                if (value.equals(cpUtf8Values.get(i))) {
                    return i;
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(value.length() + 4);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(1);
            out.writeUTF(value);
            cpEntries.add(baos.toByteArray());
            cpUtf8Values.add(value);
            return cpEntries.size() - 1;
        }

        int addClassInfo(String internalName) throws IOException {
            int nameIdx = addUtf8(internalName);
            for (int i = 1; i < cpEntries.size(); i++) {
                byte[] e = cpEntries.get(i);
                if (e != null && e[0] == 7 && (((e[1] & 0xFF) << 8) | (e[2] & 0xFF)) == nameIdx) {
                    return i;
                }
            }
            byte[] entry = new byte[] {7, (byte) ((nameIdx >>> 8) & 0xFF), (byte) (nameIdx & 0xFF)};
            cpEntries.add(entry);
            cpUtf8Values.add(null);
            return cpEntries.size() - 1;
        }

        int addNameAndType(String name, String desc) throws IOException {
            int nIdx = addUtf8(name);
            int dIdx = addUtf8(desc);
            byte[] entry = new byte[] {
                    12,
                    (byte) ((nIdx >>> 8) & 0xFF), (byte) (nIdx & 0xFF),
                    (byte) ((dIdx >>> 8) & 0xFF), (byte) (dIdx & 0xFF)
            };
            cpEntries.add(entry);
            cpUtf8Values.add(null);
            return cpEntries.size() - 1;
        }

        int addFieldref(String ownerInternal, String name, String desc) throws IOException {
            return addMemberRef(9, ownerInternal, name, desc);
        }

        int addMethodref(String ownerInternal, String name, String desc) throws IOException {
            return addMemberRef(10, ownerInternal, name, desc);
        }

        private int addMemberRef(int tag, String ownerInternal, String name, String desc) throws IOException {
            int cIdx = addClassInfo(ownerInternal);
            int ntIdx = addNameAndType(name, desc);
            byte[] entry = new byte[] {
                    (byte) tag,
                    (byte) ((cIdx >>> 8) & 0xFF), (byte) (cIdx & 0xFF),
                    (byte) ((ntIdx >>> 8) & 0xFF), (byte) (ntIdx & 0xFF)
            };
            cpEntries.add(entry);
            cpUtf8Values.add(null);
            return cpEntries.size() - 1;
        }

        MemberBytes buildCodeMethod(
                int flags,
                String name,
                String desc,
                int maxStack,
                int maxLocals,
                byte[] bytecode,
                byte[] stackMapTablePayloadOrNull
        ) throws IOException {
            int nameIdx = addUtf8(name);
            int descIdx = addUtf8(desc);
            int codeAttrNameIdx = addUtf8("Code");
            int smtAttrNameIdx = stackMapTablePayloadOrNull != null ? addUtf8("StackMapTable") : 0;

            ByteArrayOutputStream codeBody = new ByteArrayOutputStream(bytecode.length + 32);
            DataOutputStream cOut = new DataOutputStream(codeBody);
            cOut.writeShort(maxStack);
            cOut.writeShort(maxLocals);
            cOut.writeInt(bytecode.length);
            cOut.write(bytecode);
            cOut.writeShort(0); // exception_table_length = 0
            if (stackMapTablePayloadOrNull != null) {
                cOut.writeShort(1); // attributes_count = 1 (StackMapTable)
                cOut.writeShort(smtAttrNameIdx);
                cOut.writeInt(stackMapTablePayloadOrNull.length);
                cOut.write(stackMapTablePayloadOrNull);
            } else {
                cOut.writeShort(0); // attributes_count = 0
            }
            byte[] codeBodyBytes = codeBody.toByteArray();

            ByteArrayOutputStream mAttrs = new ByteArrayOutputStream(codeBodyBytes.length + 10);
            DataOutputStream mOut = new DataOutputStream(mAttrs);
            mOut.writeShort(1); // method attributes_count = 1 (Code)
            mOut.writeShort(codeAttrNameIdx);
            mOut.writeInt(codeBodyBytes.length);
            mOut.write(codeBodyBytes);
            return new MemberBytes(flags, nameIdx, descIdx, mAttrs.toByteArray());
        }

        byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(0xCAFEBABE);
            out.writeShort(minorVersion);
            out.writeShort(majorVersion);
            out.writeShort(cpEntries.size());
            for (int i = 1; i < cpEntries.size(); i++) {
                byte[] entry = cpEntries.get(i);
                if (entry != null) {
                    out.write(entry);
                }
            }
            out.writeShort(accessFlags);
            out.writeShort(thisClass);
            out.writeShort(superClass);
            out.writeShort(interfaces.size());
            for (int iface : interfaces) {
                out.writeShort(iface);
            }
            out.writeShort(fields.size());
            for (MemberBytes f : fields) {
                out.writeShort(f.accessFlags);
                out.writeShort(f.nameIndex);
                out.writeShort(f.descIndex);
                if (f.attributesRaw.length == 0) {
                    out.writeShort(0);
                } else {
                    out.write(f.attributesRaw);
                }
            }
            out.writeShort(methods.size() + newMethods.size());
            for (MethodEntry m : methods) {
                out.writeShort(m.accessFlags);
                out.writeShort(m.nameIndex);
                out.writeShort(m.descIndex);
                out.write(m.attributesRaw);
            }
            for (MemberBytes m : newMethods) {
                out.writeShort(m.accessFlags);
                out.writeShort(m.nameIndex);
                out.writeShort(m.descIndex);
                out.write(m.attributesRaw);
            }
            out.write(classAttributesRaw);
            return baos.toByteArray();
        }

        private static String decodeModifiedUtf8(byte[] strBytes) throws IOException {
            ByteArrayOutputStream tmp = new ByteArrayOutputStream(strBytes.length + 2);
            tmp.write((strBytes.length >>> 8) & 0xFF);
            tmp.write(strBytes.length & 0xFF);
            tmp.write(strBytes);
            return new java.io.DataInputStream(new java.io.ByteArrayInputStream(tmp.toByteArray())).readUTF();
        }
    }
}
