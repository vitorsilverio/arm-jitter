import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.codegen.executor.IrBlockExecutor;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmBlockCompiler;
import dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy;
import dev.vitorsilverio.armjitter.ir.IrBlock;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir.ShiftType;
import dev.vitorsilverio.armjitter.support.IrOpSamples;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.RecordComponent;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/// E15.7 — oráculo de bytecode do backend ASM de 32 bits. Roda com o classpath de ANTES e com o de
/// DEPOIS (um processo por configuração) e as saídas são comparadas com `diff`.
///
/// Uso: `java -cp <classes>;<test-classes>;<asm.jar> AsmBytecodeOracle.java <config> <plain|norm>`
/// com `config` = `v4t` | `v5` | `v6`.
public final class AsmBytecodeOracle {
    private static final int SINGLE_PER_RECORD = 400;
    private static final int MULTI_BLOCKS = 30000;
    private static final String OLD_HELPERS = "dev/vitorsilverio/armjitter/codegen/jvm/AsmRuntimeHelpers";
    private static final Set<String> NEW_HELPERS = Set.of(
            "dev/vitorsilverio/armjitter/codegen/jvm/AsmFlagHelpers",
            "dev/vitorsilverio/armjitter/codegen/jvm/AsmMemoryHelpers",
            "dev/vitorsilverio/armjitter/codegen/jvm/AsmIntegerHelpers",
            "dev/vitorsilverio/armjitter/codegen/jvm/AsmSystemHelpers",
            "dev/vitorsilverio/armjitter/codegen/jvm/AsmVfpHelpers");

    public static void main(String[] args) throws Exception {
        String config = args[0];
        boolean normalize = args[1].equals("norm");
        AsmBlockCompiler compiler = switch (config) {
            case "v4t" -> new AsmBlockCompiler(false, false, new IrBlockExecutor(ArmArchitecture.ARMV4T));
            case "v5" -> new AsmBlockCompiler(true, false, new IrBlockExecutor(ArmArchitecture.ARMV5TE));
            case "v6" -> new AsmBlockCompiler(true, true, new IrBlockExecutor(ArmArchitecture.ARMV6K));
            default -> throw new IllegalArgumentException(config);
        };
        Random random = new Random(0xE157L ^ config.hashCode());
        List<Class<? extends IrOp>> records = IrOpSamples.recordsOf(IrOp.class);
        StringBuilder out = new StringBuilder();
        List<IrOp> supported = new ArrayList<>();
        int lines = 0;
        for (Class<? extends IrOp> recordClass : records) {
            for (int i = 0; i < SINGLE_PER_RECORD; i++) {
                IrOp op = randomOp(recordClass, random);
                if (op == null) {
                    continue;
                }
                boolean supports = AsmNativePolicy.supports(op);
                if (supports) {
                    supported.add(op);
                }
                out.append(recordClass.getSimpleName()).append(' ').append(supports).append(' ');
                emit(out, compiler, List.of(op), normalize);
                lines++;
            }
        }
        for (int b = 0; b < MULTI_BLOCKS; b++) {
            int size = 1 + random.nextInt(6);
            List<IrOp> ops = new ArrayList<>();
            for (int k = 0; k < size; k++) {
                ops.add(supported.get(random.nextInt(supported.size())));
            }
            out.append("block ").append(b).append(' ');
            emit(out, compiler, ops, normalize);
            lines++;
        }
        System.out.print(out);
        System.err.println("[e15.7-oracle] " + config + " linhas=" + lines);
    }

    private static void emit(StringBuilder out, AsmBlockCompiler compiler, List<IrOp> ops, boolean normalize) {
        IrBlock block = new IrBlock(0x8000, 0x8000 + 4 * ops.size(), ops);
        out.append(AsmNativePolicy.supports(block));
        out.append(" C=").append(digest(() -> compiler.compile("Oracle", block), normalize));
        out.append(" P=").append(digest(() -> compiler.compilePerOp("Oracle", block), normalize));
        out.append('\n');
    }

    private interface Compile {
        byte[] run();
    }

    private static String digest(Compile compile, boolean normalize) {
        byte[] bytes;
        try {
            bytes = compile.run();
        } catch (RuntimeException e) {
            return "EXC:" + e.getClass().getName() + ":" + e.getMessage();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize ? normalized(bytes) : bytes)).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /// Reescreve o owner das chamadas estáticas às classes novas de helper para `AsmRuntimeHelpers`,
    /// pelo mesmo pipeline `ClassReader`→`ClassWriter` nos dois lados.
    private static byte[] normalized(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        super.visitMethodInsn(opcode, NEW_HELPERS.contains(owner) ? OLD_HELPERS : owner, n, d, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static IrOp randomOp(Class<? extends IrOp> recordClass, Random random) {
        Map<String, Object> fields = new HashMap<>();
        for (RecordComponent c : recordClass.getRecordComponents()) {
            Object v = randomValue(c.getType(), random);
            if (v != null) {
                fields.put(c.getName(), v);
            }
        }
        try {
            return IrOpSamples.sample(recordClass, fields);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object randomValue(Class<?> type, Random r) {
        if (type == int.class) return r.nextInt(4) == 0 ? r.nextInt() : r.nextInt(18) - 1;
        if (type == long.class) return r.nextLong();
        if (type == boolean.class) return r.nextBoolean();
        if (type == IrOperand.class) return randomOperand(r);
        if (type.isEnum()) {
            Object[] values = type.getEnumConstants();
            return values[r.nextInt(values.length)];
        }
        return null;
    }

    private static IrOperand randomOperand(Random r) {
        return switch (r.nextInt(3)) {
            case 0 -> new IrOperand.Immediate(r.nextInt());
            case 1 -> new IrOperand.Register(r.nextInt(16), r.nextInt(3) - 1);
            default -> new IrOperand.ShiftedRegister(r.nextInt(16), ShiftType.values()[r.nextInt(ShiftType.values().length)],
                    r.nextInt(32), r.nextInt(17) - 1, r.nextInt(3) - 1, r.nextInt(3) - 1, r.nextBoolean(), r.nextBoolean());
        };
    }
}
