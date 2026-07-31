# Compila e roda o smoke test em C da API C do arm-jitter (task A9 PR1).
#
# Pre-requisitos (mesma receita de RELATORIO-A5.md):
#   - mvn -Pnative-lib -DskipTests package ja rodado neste modulo (gera
#     target/arm_jitter.dll + arm_jitter.lib + arm_jitter.h + graal_isolate.h).
#   - Visual Studio 2022 com "Desktop development with C++" instalado.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File build-and-run-smoke.ps1
#   -VcVarsPath permite apontar para outra instalacao do VS.

param(
    [string]$VcVarsPath = "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
)

$ErrorActionPreference = "Stop"
$targetDir = Join-Path $PSScriptRoot "target"
$smokeSrc = Join-Path $PSScriptRoot "src\test\c\smoke.c"

if (-not (Test-Path (Join-Path $targetDir "arm_jitter.lib"))) {
    throw "arm_jitter.lib nao encontrado em $targetDir. Rode mvn -Pnative-lib -DskipTests package primeiro."
}

# cl.exe so existe depois do vcvars64.bat carregar o ambiente MSVC; isso so
# funciona via cmd.exe (nao e um script PowerShell), entao delegamos a
# compilacao e execucao inteira a um bloco cmd /c dentro do diretorio target.
$cmd = "call ""$VcVarsPath"" && cd /d ""$targetDir"" && cl.exe /nologo /I . ""$smokeSrc"" arm_jitter.lib /Fe:smoke.exe && .\smoke.exe"
cmd.exe /c $cmd
exit $LASTEXITCODE
