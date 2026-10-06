# E15.15d — remove do Aarch64Decoder os sub-decoders migrados para tabela (mesmo método do
# e15.15a-scripts/remove_methods.py). Cada método é localizado pela linha de assinatura EXATA; o corte
# vai do início do bloco de comentário `///` imediatamente acima até a chave de fechamento com 4
# espaços de recuo. Constantes saem pela linha de declaração, com o `///` de cima.
import sys

PATH = sys.argv[1]
SIGNATURES = [
    "    private static Ir64VectorFpPairwiseOp decodeVectorFpScalarPairwiseOpcode(boolean a, int opcode) {",
    "    private static Ir64CryptoAesOp decodeCryptoAesOpcode(int opcode) {",
    "    private static Ir64CryptoShaTwoRegisterOp decodeCryptoShaTwoRegisterOpcode(int opcode) {",
    "    private static Ir64VectorAcrossLanesOp decodeVectorAcrossLanesOpcode(boolean u, int rm, int opcode) {",
    "    private static Ir64VectorFpAcrossLanesOp decodeVectorFpAcrossLanesOpcode(int opcode, int esz) {",
    "    private Ir64Op decodeAdvancedSimdThreeDifferent(int word, long address, boolean scalar, boolean q, int esz,",
]
CONSTANTS = [
    "    private static final int ADVSIMD_INT_RM_ACROSS_LANES_MASK = 0b1_1110;",
    "    private static final int ADVSIMD_INT_RM_ACROSS_LANES_PATTERN = 0b1_0000;",
    "    private static final int ADVSIMD_AES_RM = 0b0_1000;",
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
