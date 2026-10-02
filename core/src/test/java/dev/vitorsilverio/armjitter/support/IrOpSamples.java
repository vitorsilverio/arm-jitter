package dev.vitorsilverio.armjitter.support;

import dev.vitorsilverio.armjitter.advsimd.AdvSimdThreeSameOp;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.ir.IrOp;
import dev.vitorsilverio.armjitter.ir.IrOperand;
import dev.vitorsilverio.armjitter.ir64.AdvSimdFpOp64;
import dev.vitorsilverio.armjitter.ir64.AdvSimdIntegerOp64;
import dev.vitorsilverio.armjitter.ir64.CryptoOp64;
import dev.vitorsilverio.armjitter.ir64.IntegerOp64;
import dev.vitorsilverio.armjitter.ir64.Ir64AluOp;
import dev.vitorsilverio.armjitter.ir64.Ir64AtomicOp;
import dev.vitorsilverio.armjitter.ir64.Ir64CryptoSha3Op;
import dev.vitorsilverio.armjitter.ir64.Ir64Op;
import dev.vitorsilverio.armjitter.ir64.Ir64VectorThreeSameOp;
import dev.vitorsilverio.armjitter.ir64.MemoryOp64;
import dev.vitorsilverio.armjitter.ir64.SveIntegerOp64;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Uma instância representativa por `record` permitido de {@link IrOp} e de {@link Ir64Op} (task
/// E15.1) — a entrada dos testes de contrato (`IrOpContractTest`/`Ir64OpContractTest`), que passam
/// TODO `record` do IR por cada ponto de roteamento.
///
/// A enumeração é **reflexiva** ({@link Class#getPermittedSubclasses()}, descendo sub-interfaces
/// seladas recursivamente): um `record` novo entra nos testes de contrato sem ninguém lembrar de
/// listá-lo. A instanciação usa o construtor canônico com o valor default de cada tipo de
/// componente (mesma técnica de `truffle/.../JitCoverageReport#instantiate`, copiada para cá porque
/// o módulo `truffle` não é visível do `core`).
///
/// Um `record` com todos os campos em default pode ser uma combinação inválida (registrador `0`
/// como base e destino com writeback, tamanho de acesso `0`, ...). Esses casos têm o campo
/// corrigido em {@link #OVERRIDES} — por NOME de componente, para que a sobrescrita quebre alto
/// (`IllegalStateException`) se o componente for renomeado ou removido.
public final class IrOpSamples {
    private IrOpSamples() {
    }

    /// Sobrescritas por `record`: nome do componente → valor. Só entram aqui campos cujo default
    /// do tipo é inválido para aquele `record`; o motivo fica ao lado de cada entrada.
    private static final Map<Class<?>, Map<String, Object>> OVERRIDES = new HashMap<>();

    private static void override(Class<?> recordClass, String component, Object value) {
        OVERRIDES.computeIfAbsent(recordClass, ignored -> new HashMap<>()).put(component, value);
    }

    /// Tamanho de acesso de uma palavra — `sizeBytes == 0` não é um acesso válido.
    private static final int WORD_BYTES = 4;
    /// `esz` de precisão simples (binary32) — as famílias de ponto flutuante só aceitam fp16/fp32
    /// (e fp64 no A64), nunca `esz == 0`.
    private static final int ESZ_SINGLE_PRECISION = 2;
    /// `esz` de halfword — menor tamanho de elemento aceito pelas formas "por elemento".
    private static final int ESZ_HALFWORD = 1;
    /// Vias do dot-product de 4 vias (`SDOT` com elemento de 32 bits soma 4 bytes): `ways == 0`
    /// dividiria por zero.
    private static final int DOT_PRODUCT_FOUR_WAYS = 4;

    /// Records de {@link IrOp} que só existem no perfil M (ARMv8-M/ARMv8.1-M), além de todos os
    /// `Mve*`: o executor os entrega ao `MProfileExceptionModel`, então a amostra precisa de um
    /// core de perfil M (ver {@link #requiresMProfile}).
    private static final Set<Class<? extends IrOp>> M_PROFILE_RECORDS = Set.of(
            IrOp.MProfileSystemRegister.class, IrOp.Nocp.class, IrOp.VfpSysregMemoryTransfer.class,
            IrOp.SecureGateway.class, IrOp.SecureBranchExchange.class, IrOp.VlldmVlstm.class,
            IrOp.Vscclrm.class, IrOp.LoopStart.class, IrOp.LoopEnd.class,
            IrOp.LoopClearTailPredication.class, IrOp.ClearMultiple.class, IrOp.Vpst.class,
            IrOp.Vpnot.class, IrOp.Vpsel.class, IrOp.Vctp.class, IrOp.VprTransfer.class,
            IrOp.AdvanceVpt.class, IrOp.AdvanceEci.class);
    private static final String MVE_RECORD_PREFIX = "Mve";

    static {
        // ── 32 bits ──────────────────────────────────────────────────────────────────────────
        override(IrOp.Load.class, "sizeBytes", WORD_BYTES);
        override(IrOp.Store.class, "sizeBytes", WORD_BYTES);
        override(IrOp.LoadExclusive.class, "sizeBytes", WORD_BYTES);
        override(IrOp.StoreExclusive.class, "sizeBytes", WORD_BYTES);
        override(IrOp.Swap.class, "sizeBytes", WORD_BYTES);
        // `SRS`: o modo-alvo é o campo de 5 bits do CPSR; `0` não é modo nenhum.
        override(IrOp.StoreReturnState.class, "targetMode", CpuMode.SUPERVISOR.bits());
        for (Class<?> fpRecord : List.of(
                IrOp.NeonFpThreeSame.class, IrOp.NeonFpPairwise.class, IrOp.NeonFpThreeSameByElement.class,
                IrOp.NeonComplex.class, IrOp.NeonComplexByElement.class,
                IrOp.MveVectorFpComplexMultiply.class, IrOp.MveVectorFpTwoOp.class,
                IrOp.MveVectorFpComplexAdd.class, IrOp.MveVectorFpComplexMultiplyAccumulate.class,
                IrOp.MveVectorFpScalar.class, IrOp.MveVectorFpScalarFma.class,
                IrOp.MveVectorFpConvert.class, IrOp.MveVectorFpUnary.class)) {
            override(fpRecord, "esz", ESZ_SINGLE_PRECISION);
        }
        // "Por elemento" só existe para a família de multiplicação (a primeira constante é `ADD`).
        override(IrOp.NeonThreeSameByElement.class, "op", AdvSimdThreeSameOp.MUL);
        override(IrOp.NeonThreeSameByElement.class, "esz", ESZ_HALFWORD);

        // ── 64 bits ──────────────────────────────────────────────────────────────────────────
        // O grupo lógico nunca carrega `ADD`/`SUB` (primeiras constantes de `Ir64AluOp`).
        override(IntegerOp64.LogicalShiftedRegister.class, "opcode", Ir64AluOp.AND);
        for (Class<?> fpRecord : List.of(
                AdvSimdFpOp64.FpArithmeticThreeSame.class, AdvSimdFpOp64.FpArithmeticPairwise.class,
                AdvSimdFpOp64.FpArithmeticUnary.class, AdvSimdFpOp64.FpArithmeticThreeSameByElement.class,
                AdvSimdFpOp64.FpComplexAdd.class, AdvSimdFpOp64.FpComplexMultiplyAccumulate.class,
                AdvSimdFpOp64.FpComplexMultiplyAccumulateByElement.class, AdvSimdFpOp64.FpScaleByInt.class,
                AdvSimdFpOp64.FpAbsoluteMaxMin.class)) {
            override(fpRecord, "esz", ESZ_SINGLE_PRECISION);
        }
        override(AdvSimdIntegerOp64.ArithmeticThreeSameByElement.class, "op", Ir64VectorThreeSameOp.MUL);
        override(AdvSimdIntegerOp64.ArithmeticThreeSameByElement.class, "esz", ESZ_HALFWORD);
        // `EOR3`/`BCAX` (primeiras constantes) são do record de QUATRO registradores.
        override(CryptoOp64.Sha3TwoSourceRotate.class, "op", Ir64CryptoSha3Op.RAX1);
        // As formas de par (`LDCLRP`/`LDSETP`/`SWPP`) só existem para `CLR`/`SET`/`SWP`.
        override(MemoryOp64.AtomicMemoryOpPair.class, "operation", Ir64AtomicOp.CLR);
        override(SveIntegerOp64.MultiplyIndexed.class, "esz", ESZ_SINGLE_PRECISION);
        override(SveIntegerOp64.MultiplyIndexed.class, "ways", DOT_PRODUCT_FOUR_WAYS);
    }

    /// `true` quando a amostra precisa de um core de perfil M (`MProfileExceptionModel` + preset
    /// ARMv8.1-M com MVE) para ser executada.
    public static boolean requiresMProfile(IrOp op) {
        Class<?> recordClass = op.getClass();
        return M_PROFILE_RECORDS.contains(recordClass) || recordClass.getSimpleName().startsWith(MVE_RECORD_PREFIX);
    }

    /// Uma instância por `record` permitido de {@link IrOp}, na ordem do `permits`.
    public static List<IrOp> irOps() {
        List<IrOp> samples = new ArrayList<>();
        for (Class<? extends IrOp> recordClass : recordsOf(IrOp.class)) {
            samples.add(sample(recordClass));
        }
        return samples;
    }

    /// Uma instância por `record` permitido de {@link Ir64Op}, na ordem do `permits`.
    public static List<Ir64Op> ir64Ops() {
        List<Ir64Op> samples = new ArrayList<>();
        for (Class<? extends Ir64Op> recordClass : recordsOf(Ir64Op.class)) {
            samples.add(sample(recordClass));
        }
        return samples;
    }

    /// Todos os `record` alcançáveis a partir de uma interface selada, descendo sub-interfaces
    /// seladas recursivamente (a E15.2/E15.3 agrupa os records por família em sub-interfaces).
    public static <T> List<Class<? extends T>> recordsOf(Class<T> sealedRoot) {
        List<Class<? extends T>> records = new ArrayList<>();
        collectRecords(sealedRoot, records);
        return records;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collectRecords(Class<?> sealedType, List<Class<? extends T>> records) {
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null) {
            throw new IllegalStateException(sealedType.getName() + " não é selado nem `record`");
        }
        for (Class<?> subclass : permitted) {
            if (subclass.isRecord()) {
                records.add((Class<? extends T>) subclass);
            } else {
                collectRecords(subclass, records);
            }
        }
    }

    /// Instancia um `record` pelo construtor canônico: cada componente recebe a sobrescrita de
    /// {@link #OVERRIDES}, se houver, ou o valor default do seu tipo.
    public static <T> T sample(Class<? extends T> recordClass) {
        return sample(recordClass, Map.of());
    }

    /// Variante de {@link #sample(Class)} com campos escolhidos pelo chamador (nome do componente
    /// → valor), por cima das sobrescritas de {@link #OVERRIDES} — para os casos em que o roteamento
    /// depende de um campo (os carve-outs condicionais da política de emissão nativa).
    @SuppressWarnings("unchecked")
    public static <T> T sample(Class<? extends T> recordClass, Map<String, Object> fields) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Map<String, Object> overrides = new HashMap<>(OVERRIDES.getOrDefault(recordClass, Map.of()));
        overrides.putAll(fields);
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            String name = components[i].getName();
            args[i] = overrides.containsKey(name) ? overrides.remove(name) : defaultValue(types[i]);
        }
        if (!overrides.isEmpty()) {
            throw new IllegalStateException("sobrescrita de componente inexistente em "
                    + recordClass.getName() + ": " + overrides.keySet());
        }
        try {
            Constructor<?> constructor = recordClass.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return (T) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("não foi possível instanciar " + recordClass.getName(), e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == IrOperand.class) {
            return new IrOperand.Immediate(0);
        }
        if (type == Condition.class) {
            // Não a primeira constante (`EQ`): com `Z=0` a op seria pulada e o teste de contrato
            // compararia dois estados intocados — passaria sem exercitar nada.
            return Condition.AL;
        }
        if (type == Ir64Op.class) {
            // `StreamingRestricted.inner`: uma operação REAL (não `Cycle`, que o executor recusa
            // fora do tratamento inline de `executeBlock`).
            return sample(IntegerOp64.MoveWide.class);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalStateException("tipo de componente sem valor default: " + type.getName());
    }
}
