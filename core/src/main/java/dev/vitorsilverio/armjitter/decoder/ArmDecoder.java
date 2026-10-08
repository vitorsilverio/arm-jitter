package dev.vitorsilverio.armjitter.decoder;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.arch.DecoderExtension;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decodetable.DecodeRow;
import dev.vitorsilverio.armjitter.decodetable.DecodeTable;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.ArrayList;
import java.util.List;

/// Decoder ARM32 (A32) por tabela (E15.16a): as linhas vivem nas classes `A32*Rows`, com a
/// feature como coluna — este arquivo só roteia.
///
/// - `cond = 1111` é um espaço de encoding à parte desde o ARMv5 (E6), com tabela própria
///   ({@link A32UnconditionalRows}): o que ela não reconhece nunca cai no dispatch condicional.
/// - O resto passa pelas camadas de {@link A32Layers#CONDITIONAL_LAYERS}, em ordem.
/// - O que nenhuma linha casa vai para as {@link DecoderExtension} da arquitetura (coprocessor,
///   VFP, NEON) e, sem resposta, é indefinido (G8).
public final class ArmDecoder implements InstructionDecoder {
    private static final int CONDITION_SHIFT = 28;
    private static final int UNCONDITIONAL = 0xF;
    private static final int WORD_ALIGNMENT_MASK = ~3;

    private final ArmArchitecture architecture;
    private final DecodeTable<ArmFeature, DecodedInstruction> unconditionalTable;
    private final List<DecodeTable<ArmFeature, DecodedInstruction>> conditionalLayers;

    /// Decoder para a arquitetura base (ARMv4T / GBA).
    public ArmDecoder() {
        this(ArmArchitecture.ARMV4T);
    }

    /// Decoder ligado a uma arquitetura: instruções ARMv5+ (CLZ, etc.) só são
    /// decodificadas se a arquitetura as suporta; o resto cai para UNIMPLEMENTED.
    public ArmDecoder(ArmArchitecture architecture) {
        this.architecture = architecture;
        this.unconditionalTable = DecodeTable.forFeatures(A32UnconditionalRows.ROWS, architecture::has);
        List<DecodeTable<ArmFeature, DecodedInstruction>> layers = new ArrayList<>();
        for (List<DecodeRow<ArmFeature, DecodedInstruction>> layer : A32Layers.CONDITIONAL_LAYERS) {
            layers.add(DecodeTable.forFeatures(layer, architecture::has));
        }
        this.conditionalLayers = List.copyOf(layers);
    }

    /// Decodifica uma instrução ARM32 no endereço informado.
    @Override
    public DecodedInstruction decode(AddressSpace memory, int address) {
        int raw = memory.fetch32(address & WORD_ALIGNMENT_MASK);
        DecodedInstruction decoded = raw >>> CONDITION_SHIFT == UNCONDITIONAL
                ? unconditionalTable.decode(raw, address)
                : decodeConditional(raw, address);
        if (decoded != null) {
            return decoded;
        }
        Condition condition = decodeCondition(raw >>> CONDITION_SHIFT);
        for (DecoderExtension extension : architecture.decoderExtensions()) {
            decoded = extension.tryDecode(raw, address, condition);
            if (decoded != null) {
                return decoded;
            }
        }
        return DecodedInstruction.unimplemented(address, raw, InstructionSet.ARM, condition);
    }

    private DecodedInstruction decodeConditional(int raw, int address) {
        for (DecodeTable<ArmFeature, DecodedInstruction> layer : conditionalLayers) {
            DecodedInstruction decoded = layer.decode(raw, address);
            if (decoded != null) {
                return decoded;
            }
        }
        return null;
    }

    /// Converte o nibble de condição ARM para `Condition`.
    public static Condition decodeCondition(int bits) {
        return switch (bits & 0xF) {
            case 0x0 -> Condition.EQ;
            case 0x1 -> Condition.NE;
            case 0x2 -> Condition.CS;
            case 0x3 -> Condition.CC;
            case 0x4 -> Condition.MI;
            case 0x5 -> Condition.PL;
            case 0x6 -> Condition.VS;
            case 0x7 -> Condition.VC;
            case 0x8 -> Condition.HI;
            case 0x9 -> Condition.LS;
            case 0xA -> Condition.GE;
            case 0xB -> Condition.LT;
            case 0xC -> Condition.GT;
            case 0xD -> Condition.LE;
            default -> Condition.AL;
        };
    }
}
