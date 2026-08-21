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

## Resultado

🟡 **B5.1 ✅ 2026-07-16** — lógica extraída para `core/ExclusiveMonitor.java`, compartilhável via `ArmCore.setExclusiveMonitor` (default = monitor próprio, zero-diff); reserva é POR CORE dentro do monitor compartilhado (`IdentityHashMap<ArmCore,Reservation>`, não um único slot global — necessário para o cenário (c) da spec, reservas em endereços distintos não podem se derrubar); `STREX`/escrita comum de QUALQUER core que bata a região consome/abre a reserva, sucesso só para o dono (`ArmCore.exclusiveMonitorCovers`/`notifyOrdinaryWrite`); hook de escrita comum ligado em TODOS os pontos de store de baixo nível dos 2 backends (`IrExecutionSupport.write{8,16,32}Arm7` + crossed word/halfword do `UNALIGNED_ACCESS`, `AsmRuntimeHelpers.store{Byte,Half,Word}` + crossed) — não só o `IrOp.Store`/`STR` simples da spec, para cobrir também LDM/STM, LDRD/STRD e SWP sem lacuna. 4 testes novos (`ExclusiveMonitorSharedTest`, cenários a-d da spec) + suíte `ArmV6ExclusiveAccessTest`/`ArmV6ExclusiveNativeEquivalenceTest` (B1.4) intacta. `mvn -o test` raiz (614+13) + gbaemu (239) + ndsemu (175) verdes. B5.2 segue bloqueada por B3.3-B3.6 (VFP). — **B5.2 ✅ 2026-07-23** — preset `ArmArchitecture.ARM11_MPCORE` (`extending(ARMV6K, "ARM11-MPCore", VFPV2)` + `withDecoderExtensions(VfpDecoder, CoprocessorDecoder)`, mesmo padrão ovo-e-galinha de `ARMV7A_FEATURES`/`ARMV6K_THUMB2_FEATURES` para o construtor de `VfpDecoder` precisar da arquitetura já com `VFPV2`). Sem Thumb-2 (nenhuma chamada a `withThumb32DecoderExtensions`, herda a lista vazia de `ARMV6K` — `THUMB2` fica de fora do feature set, então `ThumbDecoder` mantém o par legado `LONG_BRANCH_PREFIX`/`LONG_BRANCH_SUFFIX` para `BL`/`BLX` em vez do fechamento de instrução única da B2.6). 5 testes novos em `ArmArchitectureTest` (features herdadas de ARMv6K + VFPV2; decodifica VADD.F32 via `VFP_ALU`; `BL`/`BLX` cai no par legado, não em `LONG_BRANCH_32`; CP15 não ganha extensão nova além de `VfpDecoder`+`CoprocessorDecoder` herdado). **Fecha o épico B5 por completo.** `mvn -o test` raiz + gbaemu + ndsemu verdes.
