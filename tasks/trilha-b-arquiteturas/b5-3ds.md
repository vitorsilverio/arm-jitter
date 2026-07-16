# B5 — 3DS enablement: entregas do lado arm-jitter (REFINADA)

**Trilha:** B · **Depende de:** B3.3–B3.6 (VFP) · **Repo:** arm-jitter
**Refinada 2026-07-15.** O emulador de 3DS em si é um projeto hospedeiro novo
(irmão de gbaemu/ndsemu) e NÃO é especificado aqui; esta task cobre só o que o
arm-jitter precisa entregar para esse hospedeiro nascer. São 2 PRs pequenos.

## PR B5.1 — Monitor de exclusividade global entre cores

**Problema:** os 2 ARM11 do 3DS compartilham memória; `LDREX`/`STREX` (base dos
atomics do kernel Horizon) precisam de visão global — hoje o monitor vive DENTRO
de cada `ArmCore` (B1.4: `markExclusive`/`exclusiveMonitorCovers`/
`clearExclusiveMonitor` em `ArmCore.java:569-597`), então um `STREX` do core B não
derruba a reserva do core A.

**Especificação:**
1. Extrair a lógica para `core/ExclusiveMonitor.java` (endereço `long` + tamanho,
   como hoje). `ArmCore` ganha construtor/setter aceitando um monitor COMPARTILHADO;
   default = monitor próprio (zero-diff, G3).
2. Semântica multi-core mínima (suficiente para atomics de SO; documentar):
   o monitor guarda `(coreId, address, size)`; `STREX` de QUALQUER core sobre região
   coberta limpa a reserva; só o core dono passa no check. Escrita COMUM (`STR`) de
   outro core sobre a região também limpa (hook barato: checar no caminho de store
   apenas quando `monitor.isArmed()` — um load de campo + branch).
3. Testes: 2 `ArmCore` sobre o mesmo `AddressSpace` + monitor compartilhado —
   (a) core A LDREX, core B STREX no mesmo endereço → B falha, A falha depois;
   (b) core A LDREX, core B STR na região → STREX de A falha;
   (c) reservas em endereços distintos não interferem;
   (d) regressão: um core sozinho com monitor default = comportamento B1.4 intacto
   (suite `ArmV6ExclusiveNativeEquivalenceTest` verde sem mudança).

## PR B5.2 — Preset do MPCore

```java
/// ARM11 MPCore do 3DS: ARMv6K + VFPv2. Sem Thumb-2 (o MPCore é ARMv6K, não v6T2).
public static final ArmArchitecture ARM11_MPCORE = extending(ARMV6K, "ARM11-MPCore",
        ArmFeature.VFPV2)
        .withDecoderExtensions(List.of(new VfpDecoder(), new CoprocessorDecoder()));
```

Testes: VFP decodifica; Thumb-2 de 32 bits NÃO (um `LDR.W` → comportamento legado
BL/BLX de par, já que sem `THUMB2` o caminho B2.6 não ativa); CP15 continua no bus
do hospedeiro.

## Fora do arm-jitter (registrar e não fazer)

Escalonamento dos 2 cores, GPU PICA200, DSP, HLE de serviços Horizon, timing,
cartucho/FS — tudo do hospedeiro. **MMU:** LLE do Horizon exigiria B4.1; a
recomendação registrada para o hospedeiro é começar HLE (sem MMU), que só precisa
destes 2 PRs + B3. Primeiro marco realista do hospedeiro: homebrew libctru com HLE
mínimo de `srv:`/`gsp::Gpu`.

## Validação

`mvn -o test` raiz + gbaemu/ndsemu verdes (G5). Commits `B5.1:`/`B5.2:`.
