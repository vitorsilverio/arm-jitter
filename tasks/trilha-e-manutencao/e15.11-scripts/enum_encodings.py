"""E15.11 — grava o encoding de cada constante de `Aarch64SystemRegisterId` a partir do dump do decoder antigo.

Entrada: saída do `SysregDump` (`NOME [op0,op1,crn,crm,op2]`, uma linha por registrador com encoding único).
Uso: python enum_encodings.py <dump.txt> <Aarch64SystemRegisterId.java>
"""
import re
import sys

FEATURES = {
    "ALLINT": "NMI", "PAN": "PAN", "UAO": "UAO", "DIT": "DIT", "FPMR": "FP8",
    "ZCR_EL1": "SVE", "ZCR_EL2": "SVE", "ZCR_EL3": "SVE",
    "SVCR": "SCALABLE_MATRIX_EXTENSION", "SMCR_EL1": "SCALABLE_MATRIX_EXTENSION",
    "SMCR_EL2": "SCALABLE_MATRIX_EXTENSION", "SMCR_EL3": "SCALABLE_MATRIX_EXTENSION",
    "ID_AA64SMFR0_EL1": "SCALABLE_MATRIX_EXTENSION",
    "RGSR_EL1": "MEMORY_TAGGING", "GCR_EL1": "MEMORY_TAGGING",
    "MPUIR_EL1": "PMSA", "PRSELR_EL1": "PMSA", "PRBAR_EL1": "PMSA", "PRLAR_EL1": "PMSA", "PRENR_EL1": "PMSA",
}

encodings = {}
for line in open(sys.argv[1], encoding="utf-8"):
    name, enc = line.split(" ", 1)
    encodings[name] = [int(x) for x in enc.strip().strip("[]").split(",")]

path = sys.argv[2]
text = open(path, encoding="utf-8", newline="").read()
done = set()


def replace(match):
    name = match.group(1)
    if name not in encodings:
        return match.group(0)
    done.add(name)
    args = ", ".join(str(x) for x in encodings[name])
    if name in FEATURES:
        args += ", Aarch64Feature." + FEATURES[name]
    return "    %s(%s)%s" % (name, args, match.group(2))


text = re.sub(r"^    ([A-Z][A-Z_0-9]*)([,;])", replace, text, flags=re.M)
missing = set(encodings) - done
if missing:
    sys.exit("constantes não achadas: %s" % sorted(missing))
open(path, "w", encoding="utf-8", newline="").write(text)
print("constantes com encoding:", len(done))
