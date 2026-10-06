# E15.15a — remove do Aarch64Decoder os sub-decoders migrados para tabela.
# Cada método é localizado pela linha de assinatura EXATA; o corte vai do início do bloco de
# comentário `///` imediatamente acima até a chave de fechamento com 4 espaços de recuo.
import sys

PATH = sys.argv[1]
SIGNATURES = [
    "    private Ir64Op decodeCryptoSha3(int word, long address) {",
    "    private Ir64Op decodeAdvancedSimdExtractPermuteTable(int word, long address, boolean q) {",
    "    private static Ir64Op decodeAdvancedSimdLookupTable(int word, int rm, int rn, int rd) {",
    "    private static Ir64Op decodeAdvancedSimdScalarDuplicateElement(int imm5, int rn, int rd) {",
    "    private Ir64Op decodeAdvancedSimdCopy(int word, long address, boolean q) {",
    "    private static Ir64CryptoShaThreeRegisterOp decodeCryptoShaThreeRegisterOpcode(int opcode) {",
]

lines = open(PATH, encoding="utf-8", newline="").read().split("\n")
for signature in SIGNATURES:
    matches = [i for i, line in enumerate(lines) if line == signature]
    if len(matches) != 1:
        sys.exit(f"assinatura não única ({len(matches)}): {signature}")
    start = matches[0]
    while start > 0 and lines[start - 1].startswith("    ///"):
        start -= 1
    end = matches[0]
    while lines[end] != "    }":
        end += 1
    # linha em branco depois do método some junto
    if end + 1 < len(lines) and lines[end + 1] == "":
        end += 1
    print(f"removendo {signature.strip()[:60]}…: linhas {start + 1}-{end + 1}")
    del lines[start:end + 1]
open(PATH, "w", encoding="utf-8", newline="").write("\n".join(lines))
