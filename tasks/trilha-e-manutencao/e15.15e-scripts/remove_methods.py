# E15.15e — remove do Aarch64Decoder os sub-decoders do two-register misc migrados para tabela (mesmo método do
# e15.15d-scripts/remove_methods.py). Cada método é localizado pela linha de assinatura EXATA; o corte vai do
# início do bloco de comentário `///` imediatamente acima até a chave de fechamento com 4 espaços de recuo.
# Constantes saem pela linha de declaração, com o `///` de cima.
import sys

PATH = sys.argv[1]
SIGNATURES = [
    "    private static boolean isTwoRegisterMiscSlot(int rm) {",
    "    private static boolean fpUnaryOpHasScalarForm(Ir64VectorFpUnaryOp op) {",
    "    private static boolean isFp16TwoRegisterMiscBit22Set(int esz) {",
    "    private static Ir64VectorUnaryOp decodeVectorUnaryOpcode(boolean u, int opcode, boolean scalar) {",
    "    private void validateVectorUnaryEsz(int word, long address, boolean scalar, Ir64VectorUnaryOp op, boolean q,",
    "    private static Ir64VectorUnaryOp decodeVectorUnaryByteOnlyOpcode(boolean u, int esz) {",
    "    private void validateScalarUnaryEsz(int word, long address, boolean scalar, Ir64VectorUnaryOp op, int esz) {",
    "    private static Ir64VectorNarrowUnaryOp decodeVectorNarrowUnaryOpcode(boolean u, int opcode, boolean scalar) {",
    "    private static Ir64VectorFpUnaryOp decodeVectorFpUnaryRmZeroOpcode(boolean u, boolean a, int opcode) {",
    "    private static Ir64VectorFpUnaryOp decodeVectorFpUnaryRmOneOpcode(boolean u, boolean a, int opcode) {",
    "    private static boolean isDirectedRoundingToIntegral(Ir64VectorFpUnaryOp op) {",
]
CONSTANTS = [
    "    private static final int ADVSIMD_INT_RM_TWO_REG_MISC = 0b0_0000;",
    "    private static final int ADVSIMD_TWO_REG_MISC_BYTE_ONLY_OPCODE = 0b0_1011;",
    "    private static final int ADVSIMD_INT_RM_NARROW_UNARY = 0b0_0001;",
    "    private static final int ADVSIMD_TWO_REG_MISC_SHLL_OPCODE = 0b0_0111;",
    "    private static final int ADVSIMD_URECPE_URSQRTE_OPCODE = 0b1_1001;",
    "    private static final int ADVSIMD_FRECPX_OPCODE = 0b1_1111;",
    "    private static final int ADVSIMD_FCVTXN_OPCODE = 0b0_1101;",
    "    private static final int ADVSIMD_FCVTXN_SCALAR_SIZE = 0b01;",
    "    private static final int ADVSIMD_FCVTL_OPCODE = 0b0_1111;",
    "    private static final int ADVSIMD_INT_RM_FP16_TWO_REG_MISC = 0b1_1000;",
    "    private static final int ADVSIMD_INT_RM_FP16_NARROW_UNARY = 0b1_1001;",
]


def unique(signature):
    matches = [i for i, line in enumerate(lines) if line == signature]
    if len(matches) != 1:
        sys.exit(f"linha não única ({len(matches)}): {signature}")
    return matches[0]


lines = open(PATH, encoding="utf-8", newline="").read().split("\n")
for signature in SIGNATURES:
    start = end = unique(signature)
    while start > 0 and lines[start - 1].startswith("    ///"):
        start -= 1
    while lines[end] != "    }":
        end += 1
    if end + 1 < len(lines) and lines[end + 1] == "":
        end += 1
    print(f"removendo {signature.strip()[:60]}…: linhas {start + 1}-{end + 1}")
    del lines[start:end + 1]
for constant in CONSTANTS:
    start = end = unique(constant)
    while start > 0 and lines[start - 1].startswith("    ///"):
        start -= 1
    print(f"removendo {constant.strip()[:60]}…: linhas {start + 1}-{end + 1}")
    del lines[start:end + 1]
open(PATH, "w", encoding="utf-8", newline="").write("\n".join(lines))
