# Fila de execução — para agentes com contexto limitado

**Este arquivo é enxuto de propósito** (limpo em 2026-08-28 — estava com 1113 linhas de narrativa
histórica, causando sessões novas perderem tempo tentando pegar tasks já fechadas). Status de cada
task vive no `INDICE.md` da trilha correspondente (`tasks/README.md` tem a tabela de trilhas); o
histórico narrativo completo (o que foi feito, achados, decisões) vive na própria task, seção
`## Resultado`, ou em `tasks/FILA-HISTORICO.md` para sessões antigas sem task própria. **Antes de
pegar qualquer coisa, confira o `INDICE.md` da trilha — não confie em texto solto sobre "o que falta"
sem checar o status real ali.**

## Regras de sessão (obrigatórias)

1. **1 sessão = 1 task** (ou 1 PR, se a task tiver múltiplos PRs). Nunca emendar a próxima task na
   mesma conversa — abrir sessão nova, contexto limpo.
2. Toda sessão começa lendo `tasks/README.md` INTEIRO (protocolo + invariantes G1-G8), depois o
   `INDICE.md` da trilha (confirma que a task está ⬜/não pega uma já ✅), depois SÓ o arquivo da
   task + os fontes que ela cita. Não explorar o repo além disso.
3. Se a task mandar "PARE e pergunte/reporte", encerrar a sessão e devolver ao usuário.
4. Nunca pegar itens de "Pendências que EXIGEM modelo forte" (`tasks/README.md`) nem da seção
   "🧑 Bloqueadas no usuário" abaixo.
5. Ao fechar: suites verdes (arm-jitter `mvn -o test` com JBR 25) + G5 nos consumidores relevantes,
   status atualizado no `INDICE.md` da trilha, seção `## Resultado` na própria task, 1 commit
   começando com o ID (`B11.x: ...`), `git push`.
6. **NUNCA duas sessões simultâneas no MESMO checkout/repo** — "paralelo" vale só entre repos
   DIFERENTES. Commits sempre com paths explícitos (`git add <arquivos da SUA task>`), nunca
   `git add -A`.
7. **Ao fechar uma task, não reescreva a narrativa aqui** — só atualize o `INDICE.md` da trilha (o
   `## Resultado` da própria task já é o histórico). Este arquivo só muda quando o estado descrito
   abaixo ("Onde estamos") muda de verdade.

## Disciplina de custo

1. **G5 "leve" durante iteração, G5 completo só uma vez por sessão**, pouco antes do commit final.
2. **Backend INTERPRETED em boot de sistema real é caro** — rode só quando o JIT já confirmar o
   marco; se não terminar em ~10-15min, documente "não concluído" e siga.
3. **Nunca lance um teste/boot longo em background e pare a sessão "esperando notificação"** — rode
   bloqueante com timeout alto, ou faça polling dentro da mesma chamada.
4. **Orçamento de ~60-80 tool-calls por sessão de investigação aberta.** Se a causa raiz não foi
   isolada, pare, documente o que foi descartado/aprendido e devolva.

## 🔒 Congelamento de subprojetos até 100% de cobertura (decisão do usuário, 2026-08-27)

**Nenhuma task de `armbox`/`gbaemu`/`ndsemu`/`virtual-arm-box`/`n3dsemu` deve ser pega** — nem
investigação, nem feature, nem bugfix — **enquanto `docs/COBERTURA-ISA.md` não mostrar cobertura
completa das arquiteturas/perfis/features/modos ARM alvo.** Só trabalho de cobertura de ISA no
`arm-jitter` é elegível agora. Ver `tasks/README.md` e a memória do agente
`feedback-100-cobertura-antes-subprojetos`. `1.4.0` fica reservada para 100% — ver `tasks/README.md`
para as regras de release (suspensas até lá).

## Onde estamos (atualizado 2026-09-12, após B19.24 fechar)

**B19.24 FECHADA 2026-09-12** — A64 `FEAT_FAMINMAX` (`FAMAX`/`FAMIN`, `_h`+`_sd`, 4 células), a
correção de misdecode que a B19.11e já tinha antecipado como MESMA causa raiz de `FSCALE_h`.
Confirmado: `FAMAX_h`/`FAMIN_h` viviam no espaço `bit21=0` "AdvSIMD copy" sem decoder dedicado,
produzindo `VectorInsertGeneral`/`VectorInsertElement` por engano (opcode PRÓPRIO `0b0_0011`,
nunca colide com `FSCALE_h`/FCVTN/RDM/FP16 no mesmo espaço); `FAMAX_sd`/`FAMIN_sd` nunca foram
misdecode de verdade (`❌` honesto no MESMO opcode de `MUL`/`MULX`, keys `0b010`/`0b110` que
nenhum dos dois usa). **Achado que corrige o número da própria spec**: "4 células" media 4
LINHAS, mas o delta real foi **8 células** — `FEAT_FAMINMAX` é `ARMv9.4-A` (não só `ARMv9.5-A`),
e `ARMv9.5-A` estende `ARMv9.4-A`, então cada linha mede `✅` nas duas colunas. Novo record
`Ir64Op.VectorFpAbsoluteMaxMin` (`Kind` 145, interpretado apenas), deliberadamente separado de
`VectorFpArithmeticThreeSame` (o `MAX`/`MIN` genérico compara com sinal, não por valor absoluto).
Núcleo `AdvSimdLanes.fpAbsoluteMaxMin`: compara por `|valor|`, devolve o operando ORIGINAL do
vencedor (sinal preservado — `FAMAX(-5.0,3.0)=-5.0`, não `5.0`); empate de magnitude (incl. `NaN`)
cai para `Math.max`/`Math.min` com sinal, mesma disciplina de `MAX`/`MIN` já usada no núcleo.
`docs/COBERTURA-ISA.md`: `ARMv9.4-A` 1117→1121/1125, `ARMv9.5-A` 1134→1138/1146, global
19077→19085/19215 (99% inalterado no arredondamento). `docs/COBERTURA-JIT.md` regenerado
(`Ir64Op.Kind` 145 total). `mvn -o test` verde (3777; as mesmas 3 falhas pré-existentes) +
`install` + `javadoc:jar`. **G5 completo** nos 5 consumidores. Ver **Resultado** na task.

**Pegáveis a seguir na família B19.11/FP8**: `B19.11b` (`FEAT_FP8FMA`, 4 células), `B19.11c`/
`B19.11d` (`FP8_DOT_PRODUCT_2WAY`/`4WAY`, 2+2 células) — specs prontas desde 2026-09-10, nenhuma
bloqueada. `C12.5`/`C12.10` (emissão JIT nativa A64) também seguem pegáveis, dimensão 2 do
roadmap.

## Onde estamos (atualizado 2026-09-12, após B19.11e fechar)

**B19.11e FECHADA 2026-09-12** — A64 `FSCALE` (`_h`/`_sd`, 2 células, `FEAT_FP8`), a correção de
misdecode (não feature nova) da família FP8. **Causa raiz do `⚠️` medido**: `FSCALE_h` vive no
espaço `bit21=0` "AdvSIMD copy" (`INS`/`DUP`, B8.12) sem nenhum decoder dedicado antes desta task,
então `decodeAdvancedSimdCopy` lia `Rm` (o registrador `Vm` real) como se fosse `imm5` de
`INS_element`/`INS_general` — produzia `VectorInsertElement`/`VectorInsertGeneral` sempre que os
bits baixos de `Rm` formassem um `esz` válido (quase sempre, para um registrador real). `FSCALE_sd`
NUNCA foi misdecode de verdade — `decodeVectorFpThreeSameOpcode` já devolvia `null` corretamente
para sua key `(u=1,a=1)`, media `❌` honesto (decode ausente, não confusão). **Achado que abre
caminho para a B19.24 (ainda `⬜`)**: `FAMAX`/`FAMIN` sofrem da MESMA classe de bug no MESMO espaço
de encoding — causa raiz já documentada no `## Resultado` desta task para reuso direto. Novo record
`Ir64Op.VectorFpScaleByInt` (`Kind` 144) deliberadamente separado de `VectorFpArithmeticThreeSame`
(o núcleo genérico interpretaria o expoente inteiro de `Rm` como ponto flutuante, semântica
errada); núcleo `AdvSimdLanes.fpScaleByInt` delega a `Math.scalb` (reproduz `FPScale` do ARM DDI
0487 exatamente nos casos especiais, testado com escala positiva/negativa/overflow/underflow/NaN).
`docs/COBERTURA-ISA.md`: as 2 células `⚠️`/`❌`→`✅` em `ARMv9.5-A` (19075→19077/19215, 99%
inalterado; `ARMv9.5-A` 98% inalterado). `docs/COBERTURA-JIT.md` regenerado (`Ir64Op.Kind`
144→145, só interpretado). `mvn -o test` verde (3756; as mesmas 3 falhas pré-existentes) +
`install` + `javadoc:jar`. **G5 completo** nos 5 consumidores. Ver **Resultado** na task.

**Pegáveis a seguir na família B19.11/FP8**: `B19.11b` (`FEAT_FP8FMA`, 4 células),
`B19.11c`/`B19.11d` (`FP8_DOT_PRODUCT_2WAY`/`4WAY`, 2+2 células) — todas com spec pronta desde
2026-09-10, nenhuma bloqueada. `B19.24` (`FEAT_FAMINMAX`, misdecode de `FAMAX`/`FAMIN`, mesma
classe de bug desta task, causa raiz já documentada) também pegável.

## Onde estamos (atualizado 2026-09-12, após B19.22 fechar)

**B19.22 FECHADA 2026-09-12** — A64 `FEAT_CMPBR` (`CB_cond` 4 formas + `CB_cond_imm`, 5 células, só
`ARMv9.5-A`). Mapeamento de `cc` (3 bits) confirmado contra o QEMU real (`trans_CB_cond`/
`trans_CB_cond_imm`) em vez do manual — achado do próprio comentário-fonte do QEMU: a forma
imediata usa `LT`/`LTU` exatamente onde a forma registrador usaria `GE`/`GEU` no MESMO valor de
`cc`, por isso duas tabelas de mapeamento próprias, nunca uma conversão única. Toolchain aceitou a
extensão (`aarch64-linux-gnu-as -march=armv9.5-a+cmpbr`, WSL/`binutils 2.46`), ao contrário do risco
que a spec antecipava — não precisou montar nada à mão. Novo enum `Ir64CompareBranchCondition`
(deliberadamente separado de `Ir64Condition`, nunca lê `NZCV`); executor reusa
`signExtendFromSize`/`zeroTruncateToSize` (já identidade em `DOUBLEWORD`, sem `if` extra).
**Achado não previsto pela spec**: `StandardIr64BlockLifter.isTerminal` precisava dos 2 `Kind`
novos — sem isso o lifter continuaria decodificando depois de um `CB_cond`/`CB_cond_imm` dentro do
MESMO bloco JIT (mesma classe de bug que `Branch64`/`CompareBranch64`/`Svc` já evitam ali).
`Ir64Op.Kind` 142→144. `docs/COBERTURA-ISA.md`: as 5 células ❌→✅ só em `ARMv9.5-A`
(1127→1132/1146; global 19070→19075/19215, 99% inalterado no arredondamento). `docs/COBERTURA-JIT.md`
regenerado. `mvn -o test` verde (3739; as mesmas 3 falhas pré-existentes) + `install`. **G5
completo** nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-12, após B19.18 fechar)

**B19.18 FECHADA 2026-09-12** — A64 `FEAT_FRINTTS` (`FRINT32Z`/`FRINT32X`/`FRINT64Z`/`FRINT64X`,
escalar+vetorial, 8 células). Escalar: 4 opcodes novos no MESMO campo de `FRINTN`/etc.
("Floating-point data-processing, 1 source"), record próprio `Ir64Op.Fp64RoundRangeLimited`
(`Kind` 141, não reaproveita `Fp64Round`). Vetorial: MESMO slot `Rm=00001` de `FSQRT_v`/`FRINTx_v`
(B8.9) — `FRINT32Z_v`/`FRINT32X_v` ganham opcode próprio (`0b11101`), `FRINT64Z_v`/`FRINT64X_v`
COMPARTILHAM o opcode de `SQRT_v` (`0b11111`), distinguidos só pelo bit `a`(23) — `a=1`→`SQRT`,
`a=0`→`FRINT64*`, nunca colidem (confirmado bit a bit contra corpus real). **Achado que corrige o
algoritmo de saturação** (medido contra o QEMU real, não assumido da spec): o sentinela de
overflow é `±2^31`/`±2^63` EXATOS (potência de dois cheia, não `2^31-1`) — `2^31` em si é um
resultado VÁLIDO, só valores estritamente maiores saturam; `±Infinito` satura como qualquer
overflow (não é caso especial, ao contrário de `NaN`, que passa intocado). **Achado que corrige o
exemplo do Aceite da própria task**: `2.5` NÃO diferencia truncamento de RNE (os dois dão `2.0`,
coincidência de `2` ser par) — o teste usa `1.5` em vez disso. `docs/COBERTURA-ISA.md`: as 8
células ❌→✅ de `ARMv8.5-A` em diante (88 células/11 colunas), global 98%→99% (18982→19070/19215).
`docs/COBERTURA-JIT.md` regenerado (`Ir64Op.Kind` 141→142, só interpretado). `mvn -o test` verde
(3714; as mesmas 3 falhas pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver
**Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.21 fechar)

**B19.21 FECHADA 2026-09-11** — A64 `FEAT_CSSC` residual, forma de registrador geral (`CTZ`/
`SMAX`/`SMIN`/`UMAX`/`UMIN`, 5 células). `CTZ` reusa 100% `Ir64Op.DataProcessing1Source` (novo
valor no enum `Ir64OneSourceOp`, zero record novo); `SMAX`/`SMIN`/`UMAX`/`UMIN` ganharam record
novo (`Ir64Op.MinMaxGeneral`, `Kind` 136, NÃO nativo por decisão da spec) decodificado no mesmo
campo de opcode de `SUBP`/`IRG`/`GMI`/`PACGA`/`CRC32*`. **Bug real achado e corrigido ANTES do
commit, medido pelo delta de `docs/COBERTURA-ISA.md`**: a checagem inicial de `CTZ` só comparava o
opcode de 6 bits, sem exigir `Rm=00000` — como `AUTDA` (`FEAT_PAuth`, B19.15, ainda não
implementada) mede o MESMO opcode de 6 bits com `Rm=00001` quando `Z=0`, `CTZ` teria absorvido essa
forma de `AUTDA` (misdecode G8), detectado porque `AUTDA` saltou de `❌` para `✅` por engano na
primeira rodada; corrigido checando `Rm==0` explicitamente, com teste de regressão dedicado.
`docs/COBERTURA-ISA.md`: `ARMv8.9-A`/`ARMv9.4-A`/`ARMv9.5-A` cada +5, global 97% (18780→18795/
19215). `docs/COBERTURA-JIT.md` regenerado (`Ir64Op.Kind` 136→137). `mvn -o test` verde (3622; as
mesmas 3 falhas pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver **Resultado**
na task.

## Onde estamos (atualizado 2026-09-11, após B19.14 fechar)

**B19.14 FECHADA 2026-09-11** — A64 `FEAT_MTE2` (Memory Tagging Extension, ARMv8.5-A), o MAIOR
degrau residual do épico B19 (26 células): `STG`/`LDG`/`STZG`/`ST2G`/`STZ2G`/`STGM`/`LDGM`/`STZGM`/
`STGP`/`SUBP`/`SUBPS`/`IRG`/`GMI`/`SETGP`/`SETGM`/`SETGE`. Armazenamento de tags FUNCIONAL (mapa
esparso em `Aarch64Core`, indexado por granule de 16 bytes), sem checagem de tag em `LDR`/`STR`
comuns (G8, decisão consciente). **Bug real achado e corrigido ANTES de decodificar**: o topo de
`decodeMemoryCopyAndSet` só checava `bit21` para separar `FEAT_LSE128` (`LDCLRP`/`LDSETP`/`SWPP`,
B19.25) — como a família de tag inteira (prefixo `0xD9`) TAMBÉM fixa `bit21=1` por coincidência de
encoding, colidiria com `FEAT_LSE128` se as duas features fossem declaradas juntas sem `FEAT_MOPS`;
corrigido checando `bits[31:30]` DEPOIS de `bit21=1` (não antes — a forma `X` de `LDAPR_i`/`STLR_i`,
B19.19, também mede `bits[31:30]="11"` na sua variante de 64 bits, e uma primeira tentativa de
checar isso ANTES de `bit21` quebrou 3 testes pré-existentes, capturado pela suíte). **Achado que
inverte a intuição do nome `p`/`w` do QEMU**: o mapeamento real `variant`→endereçamento é
`01`→`POST_INDEX`, `10`→`OFFSET`, `11`→`PRE_INDEX` (o INVERSO do que os nomes sugerem — confirmado
lendo o C real, não a nomenclatura). `SETGP`/`SETGM`/`SETGE` já tinham sido desbloqueadas (❌
honesto, não mais `⚠️`) pelo efeito colateral da B19.16 — bastou decodificar de verdade, sem
investigação de causa raiz nesta sessão. `STGP` grava a tag do PRÓPRIO endereço de destino, não de
`Rt`/`Rt2` (achado via `trans_STGP` do QEMU). `STZGM` grava a tag dos 4 bits BAIXOS de `Rt` direto
(não `bits[59:56]` como `STG`) — achado via `HELPER(stzgm_tags)`. **Achado de correção própria**:
o armazenamento de tags precisa indexar por um endereço "físico" (sem a tag lógica em `bits[59:56]`
do próprio ponteiro), senão `IRG` seguido de `STG`/`LDG` no MESMO ponteiro tagueado quebraria —
corrigido com `Aarch64Core.stripAllocationTag`. `IRG` usa o algoritmo LFSR determinístico real do
QEMU (sem entropia, savestates reprodutíveis) e `GCR_EL1.Exclude` É consumido de verdade (mais
simples que a alternativa "sempre zero" cogitada pela spec). 7 records novos em `Ir64Op`
(`Ir64Op.Kind` 129→136, nenhum nativo). Vetores golden via `aarch64-linux-gnu-as
-march=armv8.8-a+memtag+mops` (WSL). `docs/COBERTURA-ISA.md`: global 96%→97% (18512→18780/19215),
`ARMv8.5-A` 95%→97% (+23, não +26 — `SETGP`/`SETGM`/`SETGE` continuam `❌` honesto onde `MTE2`
existe sem `MOPS`, requisito real da arquitetura). `mvn -o test` verde (3609; as mesmas 3 falhas
pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.19 fechar)

**B19.19 FECHADA 2026-09-11** — A64 `FEAT_LRCPC2` (`LDAPR_i` 6 formas + `STLR_i`, 7 células).
Reuso total: os campos de `LDAPR_i`/`STLR_i` (`size`/`opc`/`imm9`/`Rn`/`Rt`) caem nas MESMAS
posições de bit que `LDUR`/`STUR` já decodificados — zero código de campo novo, só o gate de
`Aarch64Feature.LRCPC2` + despacho por bits[11:10] (`00`=`LDAPR_i`/`STLR_i`, `01`=MOPS) dentro de
`decodeMemoryCopyAndSet`, ANTES do gate de `MEMORY_COPY_SET` (features independentes). Nenhum
`Ir64Op.Kind` novo — vira `Load64`/`Store64` comuns, `docs/COBERTURA-JIT.md` zero-diff. Vetores
golden via `aarch64-linux-gnu-as -march=armv8.4-a` (WSL). `docs/COBERTURA-ISA.md`: global 95%→96%
(18428→18512/19215). `mvn -o test` verde (3590; as mesmas 3 falhas pré-existentes) + `install`.
**G5 completo** nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.23 fechar)

**B19.23 FECHADA 2026-09-11** — A64 `FEAT_DotProd` residual (`SDOT_v`/`UDOT_v`/`SDOT_vi`/`UDOT_vi`,
4 células), reusando 100% o record `Ir64Op.VectorIntegerDotProduct`/`VectorIntegerDotProductByElement`
e o núcleo `AdvSimdLanes.dotProduct`/`dotProductByElement` que `USDOT`/`SUDOT` (B19.12) já deixaram
prontos — zero código de núcleo novo, só decode. **Achado que corrige a spec e um comentário
pré-existente**: apesar de o `## Resultado` da B19.12 já ter revisado a premissa "já ✅" (frase
copiada de um engano da própria B19.12), o Javadoc do bloco `USDOT_v` ainda afirmava que "`U=1` no
MESMO opcode de `USDOT_v` seria `UDOT_v`" — **errado**: `UDOT_v` vive num opcode VIZINHO
(`0b10010`, não `0b10011`), confirmado byte a byte contra `aarch64-linux-gnu-as -march=armv8.2-a+dotprod`
(WSL). Achado de decode: `SDOT_vi`/`UDOT_vi` usam `Rm` de 5 bits LIVRES (`V0`-`V31`), diferente do
`Rm` restrito a `V0`-`V15` de `USDOT_vi`. `docs/COBERTURA-ISA.md`: as 4 células ❌→✅ de `ARMv8.2-A`
em diante (56 células/14 colunas), global 18372→18428/19215 (95% inalterado no arredondamento).
`mvn -o test` verde (3584; as mesmas 3 falhas pré-existentes) + `install`. **G5 completo** nos 5
consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.28 fechar)

**B19.28 FECHADA 2026-09-11** — A64 `FEAT_SME` residual (`MSR SVCR<mask>, #imm`, 1 célula), o degrau
mais simples da lista nomeada pela B19.9 depois de B19.17/B19.29. Diferente de quase todas as outras
19 tasks desta varredura: **não decodifica com sucesso nunca** — nenhum estado ZA/streaming-SVE é
modelado (épico B18 continua sem infraestrutura própria, confirmado antes de codar). O ganho real é
trocar o `default` genérico (`op2=0b011` caía junto com qualquer encoding realmente desconhecido) por
um `case` próprio que decodifica `mask`/`imm` de verdade e produz recusa NOMEADA e distinguível
(G8) quando `FEAT_SME` está presente (`ARMV9_2_A`) — vs. o `unsupported` genérico de sempre quando a
feature está ausente. Encoding confirmado byte a byte contra `aarch64-linux-gnu-as -march=armv9-a+sme`
(WSL): `msr svcrsm, #1` monta `0xd503437f` (alias `smstart sm`), batendo com o cálculo manual feito a
partir da spec da task. `docs/COBERTURA-ISA.md` zero-diff (esperado e documentado — a metodologia de
medição não distingue "recusa nomeada" de "decode ausente"). `mvn -o test` verde (3563; as mesmas 3
falhas pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.26 fechar)

**B19.26 FECHADA 2026-09-11** — A64 `FEAT_FP16` residual (`FMOV_hx`/`FMOV_xh`/`FCVT_s_hs`/
`FCVT_s_hd`/`FCVT_s_sh`/`FCVT_s_dh`, 6 células), o degrau mais barato do lote nomeado pela B19.9.
Dois `Kind`/record NOVOS (não reaproveita `Fp64GeneralRegisterMove`/`Fp64Convert`, ver Armadilhas):
`Fp64HalfPrecisionGeneralRegisterMove` (`FMOV_hx`/`xh` — o lado FP é sempre `H`, `sf` é ignorado de
propósito porque produz o MESMO estado final nos dois valores, confirmado byte a byte contra
`aarch64-linux-gnu-as -march=armv8.2-a+fp16`, WSL) e `Fp64ConvertHalfPrecision` (as 4 combinações que
faltavam de `FCVT` meia↔simples/dupla, reusando `AdvSimdLanes.halfBits`/`halfToFloat`). Nenhum dos
dois `Kind` entra em `Ir64NativePolicy` — caem no interpretador automaticamente, satisfazendo o "não
adicionar caso nativo" sem lógica extra. `docs/COBERTURA-ISA.md`: as 6 células saem de `❌` para `✅`
em `ARMv8.2-A`+ (14 colunas), global 18288→18372/19215 (95% inalterado no arredondamento).
`docs/COBERTURA-JIT.md` regenerado (`Ir64Op.Kind` 127→129, ambos só interpretados). `mvn -o test`
verde (3561; as mesmas 3 falhas pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver
**Resultado** na task.

## Onde estamos (atualizado 2026-09-11, após B19.25 fechar)

**B19.25 FECHADA 2026-09-11** — A64 `FEAT_LSE128` (`LDCLRP`/`LDSETP`/`SWPP`, 3 células, ARMv9.4-A).
Bug de estrutura achado e corrigido antes de decodificar: `decodeMemoryCopyAndSet` (B19.16) checava
`Aarch64Feature.MEMORY_COPY_SET` ANTES de olhar o bit que separa "Memory Copy/Set" de "Atomic
128-bit" — movido o desvio do bit `atomic128` para o topo, cada família checa só a própria feature
agora. **Achado que revisa a premissa da spec**: ao contrário do que a spec assumia por analogia
com `CASP` (companheiro derivado como `rt|1`), `Rt`/`Rt2` são dois campos de encoding
INDEPENDENTES, sem relação par/ímpar (confirmado: o assembler aceita `ldclrp x2, x4, [x5]`); e ao
contrário de `AtomicMemoryOp`/`CASP` (que separam operando de destino), aqui o MESMO par `(Rt,Rt2)`
é operando de entrada E recebe o valor antigo — semântica in-place, confirmada lendo
`do_atomic128_ld` em `target/arm/tcg/translate-a64.c` do QEMU (mesma revisão fixada por E11, via
`curl` direto do GitHub). Novo record `Ir64Op.AtomicMemoryOpPair` (`Kind` 126→127), reusa
`Ir64AtomicOp.CLR/SET/SWP` já existentes. `docs/COBERTURA-ISA.md`: global 95% (18282→18288/19215),
`ARMv9.4-A` 92% (1042→1045/1125), `ARMv9.5-A` 91%→92% (1052→1055/1146). `mvn -o test` verde (3545;
as mesmas 3 falhas pré-existentes) + `install`. **G5 completo** nos 5 consumidores. Ver
**Resultado** na task.

## Onde estamos (atualizado 2026-09-10, após B19.16 fechar)

**B19.16 FECHADA 2026-09-10** — A64 `FEAT_MOPS` (`SETP`/`SETM`/`SETE`/`CPYFP`/`CPYFM`/`CPYFE`/
`CPYP`/`CPYM`/`CPYE`, 9 células). Javadoc de `MEMORY_COPY_SET` corrigido (o "caminho genérico" que
afirmava nunca existiu). Causa raiz do `⚠️` de `CPYP`/`CPYM`/`CPYE`: o bit `V`(26) nesta região do
encoding não é um seletor SIMD&FP de verdade — é parte do opcode MOPS/tag, e `decodeLoadsAndStores`
checava `vectorForm` ANTES do bucket reservado (bit24=1), então essas 6 instruções (+ a família
`SETGP`/`SETGM`/`SETGE` da B19.14) caíam em `decodeFpLoadLiteral` por engano — mesma classe de bug
que a B11.3 já tinha corrigido do lado GPR. **Achado de segunda ordem, achado só medindo o delta
pós-fix**: `LDAPR_i`/`STLR_i` (`FEAT_LRCPC2`, B19.19, ainda ⬜) compartilham o MESMO prefixo de 6
bits com `SETP`/`CPYFx` — só bits[11:10] distingue (`01` MOPS, `00` `LDAPR_i`/`STLR_i`); sem esse
campo checado, `LDAPUR`/`LDAPURB`/`STLUR` seriam absorvidas como `CPYFM`/`CPYFE`/`SETP` (7 células
com `✅` falso — pego ANTES de commitar, medindo o delta de `docs/COBERTURA-ISA.md`: +80 ao invés
dos +45 esperados). `docs/COBERTURA-ISA.md`: `ARMv8.8-A` 92%→94%, global 94%→95%
(18237→18282/19215). Efeito colateral: `SETGP`/`SETGM`/`SETGE` saem de `⚠️` (misdecode) para `❌`
honesto — destrava a B19.14 sem falso-positivo. `docs/COBERTURA-JIT.md` regenerado (`Ir64Op.Kind`
124→126, ambos só interpretados). `mvn -o test` verde (3533; as mesmas 3 falhas pré-existentes,
confirmadas independentes). **G5 completo** nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-10, após B19.29 fechar)

**B19.29 FECHADA 2026-09-10** — A64 `FEAT_JSCVT` (`FJCVTZS`, 1 célula), a segunda mais barata do
lote nomeado pela B19.9 (depois da B19.17/CRC32). Record próprio (`Fp64JavascriptConvert`, não
reaproveita `Fp64IntegerConvert`) porque a regra de overflow/NaN (produz `0`, não satura) e a
semântica de `NZCV.Z` (exatidão da conversão, não "resultado zero") são incompatíveis com o record
genérico. Decode: `type=DOUBLE` é parte FIXA do encoding (checado ANTES de
`decodeFpDoublePrecision`, mesmo padrão de `BFCVT`), `sf=1` não existe para esta forma. 12 testes
novos (decoder + executor), G5 completo verde nos 5 consumidores. `docs/COBERTURA-ISA.md`:
`FJCVTZS` ❌→✅ de `ARMv8.3-A` em diante (16 células), global 94% (18237/19215, sem mudar o
percentual arredondado). `docs/COBERTURA-JIT.md` regenerado (também corrigiu de carona uma
defasagem: `CRC32`/`B19.17` não tinha sido regenerado ali ainda). Confirmado que as 3 falhas
pré-existentes de `mvn test` (2 em `Aarch64Fp16VersionCurationTest`, 1 em
`IsaCoverageReportA64CurationGuardTest`) são independentes desta task (via `git stash`). Ver
**Resultado** na task.

## Onde estamos (atualizado 2026-09-10, após B19.17 fechar)

**B19.17 FECHADA 2026-09-10** — `FEAT_CRC32` (`CRC32{B,H,W,X}`/`CRC32C{B,H,W,X}`, 8 células), o
degrau mais barato do lote nomeado pela B19.9. Confirmado que **não existe núcleo de CRC-32 do
lado 32 bits** (a spec cogitava reuso); implementado direto no executor A64 (algoritmo bit-a-bit
refletido padrão, sem complemento de entrada/saída — quem chama fornece `Wn=~0` para reproduzir o
CRC-32 "clássico"). Decode no MESMO subgrupo de `PACGA` (`opc2=00`), campo de 6 bits reaproveitado
(`top4` distingue `CRC32`/`CRC32C`, `size` distingue B/H/W/`X`); `X` é a ÚNICA forma com `sf=1`,
qualquer outra combinação é reservada (G8). Vetores golden clássicos batidos exatamente
(`0xCBF43926` IEEE, `0xE3069283` Castagnoli). `docs/COBERTURA-ISA.md`: `ARMv8.1-A` 98%→99%.
`Ir64Op.Kind` 121→122. G5 completo verde nos 5 consumidores. Ver **Resultado** na task.

## Onde estamos (atualizado 2026-09-10, após B19.9 fechar o épico B19)

**B19.9 FECHADA 2026-09-10** — fechamento do épico B19 (zero decode). Remedição: `ARMv8.0-A`
82%→99% (858/862, os 174 `❌` do início do épico); `docs/COBERTURA-ISA.md` já estava em dia
(zero-diff contra a sessão anterior no mesmo dia). **Varredura completa das 114 células `❌`/`⚠️`
que ainda restam na tabela A64** — todas já mapeadas a um `Aarch64Feature` existente (decode puro,
nenhuma precisa de feature nova nem de decisão de versão) — agrupadas em **19 degraus novos
nomeados, nenhuma célula sem destino** (regra máxima):

| Task | Feature | Linhas |
|---|---|---:|
| B19.14 | `MEMORY_TAGGING` (`FEAT_MTE2`) | 26 |
| B19.15 | `POINTER_AUTHENTICATION` (resíduo) | 10 |
| B19.16 | `MEMORY_COPY_SET` (`FEAT_MOPS`) | 9 |
| B19.17 | `CRC32` (`FEAT_CRC32`) | 8 |
| B19.18 | `DIRECTED_ROUNDING_TO_INTEGRAL` (`FEAT_FRINTTS`) | 8 |
| B19.19 | `LRCPC2` | 7 |
| B19.20 | `COMPLEX_NUMBER_ARITHMETIC` (`FEAT_FCMA`) | 6 |
| B19.21 | `COMMON_SHORT_SEQUENCE_COMPRESSION` (`FEAT_CSSC`) | 5 |
| B19.22 | `COMPARE_AND_BRANCH` (`FEAT_CMPBR`) | 5 |
| B19.23 | `DOT_PRODUCT` (residual A64) | 4 |
| B19.24 | `FP_ABSOLUTE_MAX_MIN` (`FEAT_FAMINMAX`) | 4 |
| B19.25 | `LSE128` | 3 |
| B19.26 | `FP16` (residual — `FMOV`/`FCVT` escalares fora do inventário da B19.5) | 6 |
| B19.27 | `GUARDED_CONTROL_STACK` (`FEAT_GCS`) | 1 |
| B19.28 | `SCALABLE_MATRIX_EXTENSION` (`FEAT_SME`) | 1 |
| B19.29 | `JAVASCRIPT_CONVERT` (`FEAT_JSCVT`) | 1 |
| B19.11c (irmã da B19.11b) | `FP8_DOT_PRODUCT_2WAY` | 2 |
| B19.11d (irmã da B19.11b/c) | `FP8_DOT_PRODUCT_4WAY` | 2 |
| B19.11e (irmã da B19.11b/c/d) | `FP8` — misdecode `FSCALE`, deixado de fora pela B19.11 | 2 |
| B19.11b (já registrada pela B19.11) | `FEAT_FP8FMA` (nova, sem constante ainda) | 4 |

Nenhuma dessas 19 tem arquivo de spec escrito ainda — só o nome/escopo/tamanho, registrados no
`## Resultado` da B19.9. **Correção sobre estimativas antigas** desta própria fila/README:
`FEAT_FCMA` mede 6 (não 4); "família FP8" mede 10 em 4 sub-grupos, não 7. **Achado novo**: as 6
linhas de `FEAT_FP16` residual (`FMOV_hx`/`FMOV_xh`/`FCVT_s_hs`/`FCVT_s_hd`/`FCVT_s_sh`/`FCVT_s_dh`)
não fazem parte do inventário de 84 linhas que a escada B19.5.1-B19.5.6 fechou — são `FMOV`/`FCVT`
escalares puros, não as formas `_h` de família aritmética que aquele plano mediu. `docs/VALIDACAO-ARQUITETURAS.md`
atualizado (linha AArch64: números antigos de 2026-09-02 trocados pelos atuais, épico B19 marcado
FECHADO). `mvn -o test` verde (3495; as mesmas 2 falhas pré-existentes de
`Aarch64Fp16VersionCurationTest`, confirmadas independentes via `git stash`) + `install`. **G5
completo** (não leve) nos 5 consumidores — todos verdes, `core/src/main` intocado. Ver **Resultado**
na task.

**B19.11a FECHADA 2026-09-10** — `FPMR` (Floating-point Mode Register) via `MRS`/`MSR`, gateado por
`Aarch64Feature.FP8` (mesmo `CRn`/`CRm` de `FPCR`/`FPSR`, só `op2` muda). Nasceu de uma sessão
anterior no MESMO dia que tentou executar a **B19.11** e mediu que TODAS as 12 linhas de
`FEAT_FP8` (não só as de acumulação) leem campos de `FPMR` de verdade — ao contrário de
`FPCR`/`FPSR` (B8.15, armazenamento puro), aqui os 7 getters de campo (`fp8SourceFormat1/2`,
`fp8DestinationFormat`, `fp8NarrowScale`, `fp8WidenScale`/`fp8WidenScale2`,
`fp8OverflowSaturatesToMaxNormal`) têm que decompor o valor corretamente, senão a B19.11 não
destrava nada. As duas Armadilhas que a spec deixou em aberto (mapeamento `F1CVTL`→`LSCALE` ×
`F2CVTL`→`LSCALE2`, e se `LSCALE`/`LSCALE2` usam só `[3:0]`) foram resolvidas por medição real
(`WebSearch`/`WebFetch` contra pseudocódigo ARM), não por suposição. Encoding confirmado byte a
byte via `aarch64-linux-gnu-as` real (WSL). `docs/COBERTURA-ISA.md` inalterado (MRS/MSR register é
decode genérico, sem linha própria na tabela — mesmo achado de B8.15). **Destrava a B19.11**, agora
pegável. Ver **Resultado** na task.

**B19.13 FECHADA 2026-09-09** — A64 `FEAT_FHM` (`FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2`, vetorial +
indexado, 8 linhas), gateadas por `Aarch64Feature.FP16_FUSED_MULTIPLY_ADD_LONG` (independente de
`FP16`, testado). Reusou 100% `AdvSimdLanes.fpFusedMultiplyAddLong`/`fpFusedMultiplyAddLongByElement`
que a **B13.20** deixou prontos — zero código novo no núcleo. **Achados de decode** (medidos via
`arm-linux-gnu-as`/`objdump`, WSL, **e** via QEMU real `translate-a64.c`/`vec_helper.c` do commit
fixado pela E11, buscados por `WebFetch` — sem toolchain devkitA64 nesta sessão): (1) a forma
vetorial vive no espaço NORMAL de "three same (FP)" (`bit21=1`), não no subespaço de meia precisão
que a B19.5.5 abriu, com `Q` sendo largura de VERDADE (não seletor de metade) e `top` vindo só de
`U`; (2) a indexada vive em `sizeField=WORD` (não `size=00`) reaproveitando o layout `@qrrx_h` de
`BFMLAL_vi`, interceptada ANTES do `switch` genérico; (3) `top` lê um BLOCO CONTÍGUO
(`laneOffset=top?lanes:0`), não o padrão par/ímpar de `BFMLALB`/`BFMLALT` — confirma que a
generalização `laneOffsetN`/`laneOffsetM` da B13.20 já era a certa. **Mesmo achado da B13.20 sobre
o Aceite de fusão**: fundir×não-fundir nunca difere para `FMLAL`/`FMLSL` (produto de 2 lanes f16
alargadas sempre exato em `float`) — testado diretamente em vez de forçar um caso impossível.
Atualizou 2 testes pré-existentes (B19.5.5/B19.5.6) que assumiam `unsupported` sob `ARMV8_2_A` antes
do gate existir. `Ir64Op.Kind` 117→119. `docs/COBERTURA-ISA.md` global 92%→93%,
`docs/COBERTURA-JIT.md` regenerado (118→120 `Kind`, os 2 novos só interpretados). G5 verde nos 5
consumidores. Ver **Resultado** na task.

**B13.20 FECHADA 2026-09-09** — `neon-shared`: `VFML`/`VFMSL`/`VFML_scalar`/`VFMSL_scalar`
(`FEAT_FHM`, 4 linhas). Nem esta nem a irmã A64 (**B19.13**, ainda ⬜) tinham semântica prévia —
`AdvSimdLanes.fpFusedMultiplyAddLong`/`fpFusedMultiplyAddLongByElement` nascem aqui, generalizados
(`laneOffsetN`/`laneOffsetM` independentes) para a B19.13 reusar. `ArmFeature` nova
(`FP16_FUSED_MULTIPLY_ADD_LONG`, mirror do lado A64). Encoding lido direto de
`target/isa-decode/neon-shared.decode` (já em cache local, GPL não versionado) e confirmado byte a
byte contra `arm-linux-gnueabihf-as -march=armv8.2-a+fp16fml` (WSL). `IrOp.Kind` 99→101.
**Achado que revisa o Aceite da própria task**: fundir×não-fundir NUNCA difere para `FMLAL`/`FMLSL`
(produto de duas lanes f16 alargadas sempre cabe exato em `float`, sem arredondamento
intermediário) — documentado em vez de forçar teste sintético. **Achado de decode** (mesma classe
da B13.18): as formas `_scalar` colidem com o espaço pré-existente de `CoprocessorRegisterDecoder`
(`MCR`/`MRC`) sem a feature — não é regressão. `docs/COBERTURA-ISA.md` byte a byte idêntica,
`docs/COBERTURA-JIT.md` regenerado. Ver **Resultado** na task.

**B13.19 FECHADA 2026-09-09** — `neon-shared`: `VSMMLA`/`VUMMLA`/`VUSMMLA` (`FEAT_I8MM` matricial,
3 linhas). A B19.12 (irmã A64, fechada 2026-09-06) já tinha posto a semântica matricial
(`AdvSimdLanes.matrixMultiplyAccumulate`) no núcleo compartilhado — reusada aqui sem nenhuma
mudança. Encoding derivado do padrão de bits da própria spec e confirmado byte a byte contra
`arm-linux-gnueabihf-as -march=armv8.6-a+i8mm` (WSL, `arm-none-eabi-as` indisponível neste
ambiente). `IrOp.Kind` 98→99. `docs/COBERTURA-ISA.md` byte a byte idêntica (nenhum preset declara
`INT8_MATRIX_MULTIPLY`), `docs/COBERTURA-JIT.md` regenerado. Ver **Resultado** na task.

**B19.5.6 FECHADA 2026-09-09** — as 8 linhas indexadas de `FEAT_FP16` (`FMUL_si`/`FMLA_si`/
`FMLS_si`/`FMULX_si`/`FMUL_vi`/`FMLA_vi`/`FMLS_vi`/`FMULX_vi`), reusando 100% o esquema de índice
`H:L:M`/estreitamento de `Rm` de `size=01` — zero `Kind`/record novo, zero mudança de executor.
**Fecha a escada B19.5 inteira** (88 linhas `_h`: B19.5.1 fundação + B19.5.3 17 + B19.5.4 49 +
B19.5.5 14 + B19.5.6 8). Achado que corrige a spec: `FMLAL_vi` (`FEAT_FHM`) não usa `size=00` de
verdade (usa `size=10`, sem risco de colisão real). `docs/COBERTURA-ISA.md` global 92%→93%. Ver
**Resultado** na task. **Nota**: esta sessão também achou que a tabela "Pegáveis AGORA" abaixo
estava desatualizada — `B13.13` (fechada 2026-09-09, sessão anterior no mesmo dia) e `B19.12`
(fechada 2026-09-06) já constavam ✅ no `INDICE.md` da trilha B antes desta sessão começar; não
confie nesta tabela sem checar o índice real de cada trilha, mesmo aviso já repetido acima.

A fila anterior estava **drenada e não dizia isso** (listava 6 tasks já fechadas como pegáveis, e 13
arquivos de task ainda tinham `**Status:** ⬜` no cabeçalho — todos corrigidos). Depois disso, uma
rodada de spec longa escreveu **45 specs**, todas medidas contra `target/isa-decode/` **e** contra o
código.

**Resultado: os quatro épicos que tinham escada medida estão INTEIRAMENTE especificados.**

| Épico | Dimensão | Estado da especificação |
|---|---|---|
| **B19** — gap remanescente do A64 | 1 (decode) | ✅ **ÉPICO FECHADO** (B19.9, 2026-09-10) — B19.1-B19.13 todas ✅; 114 células remanescentes viraram 19 degraus novos nomeados (B19.14-B19.29, B19.11b-e), **specs escritas 2026-09-10** (ver abaixo), todos pegáveis |
| **B13** — NEON/AdvSIMD 32 bits | 1 (decode) | ✅ **completo** — B13.1-B13.8 feitas; B13.9-B13.22 com spec |
| **C12** — emissão JIT nativa | 2 | ✅ **completo** — C12.1 feita; C12.2-C12.10 com spec |
| **A10** — Truffle | 3 | ✅ **completo** — A10.1 feita, A10.2 absorvida; A10.3-A10.9 com spec |

O que **não** foi especificado são os 7 épicos ainda em `📋 plano`, que nunca tiveram escada medida —
ver "O que ainda precisa de spec".

### ✅ Pegáveis AGORA

Lista revalidada em 2026-09-10 (sessão pós-B19.9): **B19.11 e B13.21 fecharam mais cedo no mesmo
dia, removidas da lista.** **C12.5/C12.10 não reconferidos nesta rodada** (trilha C fora do escopo
da B19.9), mantidos abaixo por não terem sido tocados. **Os 20 degraus nomeados pela B19.9 ganharam
spec própria nesta sessão** (abaixo) e entram na lista de pegáveis — nenhum tem dependência aberta
além da própria B19.9 (✅) e, no caso da família FP8, de B19.11/B19.11a (✅).

| Task | O que | Tamanho |
|---|---|---|
| **[C12.5](trilha-c-perf/c12.5-a64-loadstore-fp-simd-nativo.md)** | Emissão nativa A64: load/store FP/SIMD (4 escalares + 3 estruturadas) | 46/96 → 53/96 |
| **[C12.10](trilha-c-perf/c12.10-a64-sistema-nativo.md)** | Emissão nativa A64: os 8 `Kind` de sistema (`SYSTEM_REGISTER`, `EXCEPTION_RETURN`, `PRIVILEGED_CALL`, ...) | 8 `Kind` |
| **[B19.11c](trilha-b-arquiteturas/b19.11c-a64-fp8-dot-2way.md)** / **[B19.11d](trilha-b-arquiteturas/b19.11d-a64-fp8-dot-4way.md)** | `FDOT_hb`/`FDOT_sb` (FP8 dot product 2-way/4-way) | 2+2 células |
| demais degraus B19.14-B19.29/B19.11b/e | ver tabela completa abaixo | — |

### Specs novas 2026-09-10: os 20 degraus nomeados pela B19.9, todas com arquivo próprio agora

A **B19.9** (fechamento do épico B19) tinha enumerado **19 degraus novos** (mais a já registrada
B19.11b) cobrindo as 114 células `❌`/`⚠️` remanescentes da tabela A64 — cada um já mapeado a um
`Aarch64Feature` existente (decode puro, sem decisão de versão em aberto). Nesta sessão, todas
ganharam arquivo `tasks/trilha-b-arquiteturas/b19.NN-*.md` completo (Contexto/Objetivo/Inclui/Não
inclui/Passos/Aceite/Armadilhas), no mesmo padrão de B19.10-B19.13 — **ainda não executadas**
(`## Resultado` pendente em todas), mas prontas para uma sessão comum pegar:

`B19.14` (`b19.14-a64-mte2.md`, MTE2, 26) · `B19.15` (`b19.15-a64-pauth-residual.md`, PAuth, 10) ·
`B19.16` (`b19.16-a64-mops.md`, MOPS, 9) · `B19.17` (`b19.17-a64-crc32.md`, CRC32, 8) ·
`B19.18` (`b19.18-a64-frintts.md`, FRINTTS, 8) · `B19.19` (`b19.19-a64-lrcpc2.md`, LRCPC2, 7) ·
`B19.20` (`b19.20-a64-fcma.md`, FCMA, 6) · `B19.21` (`b19.21-a64-cssc-residual.md`, CSSC, 5) ·
`B19.22` (`b19.22-a64-cmpbr.md`, CMPBR, 5) · `B19.23` (`b19.23-a64-dotprod-residual.md`, DotProd residual, 4) ·
`B19.24` (`b19.24-a64-faminmax.md`, FAMINMAX, 4) · `B19.25` (`b19.25-a64-lse128.md`, LSE128, 3) ·
`B19.26` (`b19.26-a64-fp16-residual.md`, FP16 residual, 6) · `B19.27` (`b19.27-a64-gcs.md`, GCS, 1) ·
`B19.28` (`b19.28-a64-sme-svcr.md`, SME `MSR_i_SVCR`, 1) · `B19.29` (`b19.29-a64-jscvt.md`, JSCVT `FJCVTZS`, 1) ·
`B19.11b` (`b19.11b-a64-fp8-fma.md`, `FEAT_FP8FMA` nova, 4) ·
`B19.11c` (`b19.11c-a64-fp8-dot-2way.md`, FP8_DOT_2WAY, 2) ·
`B19.11d` (`b19.11d-a64-fp8-dot-4way.md`, FP8_DOT_4WAY, 2) ·
`B19.11e` (`b19.11e-a64-fscale-misdecode.md`, FP8 `FSCALE` misdecode, 2).

Achados de decode registrados nas próprias specs (confirmar na sessão de execução, não foram
implementados): (1) `FEAT_PAuth` (B19.15) — não existe núcleo real de pointer authentication no
projeto, `PACGA` é placeholder determinístico; (2) `FEAT_MOPS` (B19.16) — o Javadoc de
`MEMORY_COPY_SET` afirma decode "via caminho genérico" para `SETP`/`SETM`/`SETE`, mas nenhum dos 9
mnemônicos tem decoder de verdade (Javadoc a corrigir); (3) `FAMAX`/`FAMIN` (B19.24) e `FSCALE`
(B19.11e) medem `⚠️` (misdecode), não `❌` puro — a task tem que achar a instrução vizinha que está
roubando o encoding antes de corrigir.

**B19.10 FECHADA 2026-09-06** — as 13 linhas de cripto A64 SHA-512/SM3/SM4 (mesmo prefixo `0xCE`
que a B11.12 abriu pela metade para `FEAT_SHA3`); achado real que corrige a spec: o campo que
separa `SM3TT1A/1B/2A/2B` é bits[11:10] (não bits[13:12] como a spec dizia) — confirmado via
corpus real `aarch64-linux-gnu-as`. `docs/COBERTURA-ISA.md` global 87%→88%. Ver **Resultado** na
task. **Nota**: esta tabela "Pegáveis AGORA" já estava parcialmente desatualizada antes desta
sessão (B13.12/B19.5.3/B19.6/B19.7/B19.12 acima já constavam ✅ no `INDICE.md` da trilha B — não
confie nela sem checar o índice real de cada trilha, mesmo aviso do topo deste arquivo).

**B13.15 FECHADA 2026-09-06** — as 7 linhas de cripto A32 (`AESE`/`AESD`/`AESMC`/`AESIMC`/`SHA1H`/
`SHA1SU1`/`SHA256SU0`, `ArmFeature.CRYPTO` nova, separada de `ADVANCED_SIMD`); migração D1 completa
(`advsimd.AdvSimdCrypto` novo, A64 passou a delegar, zero-diff); achado que corrige a spec: o
número do plano (~15) contava também as formas de 3 registradores, que **não existem** em
`neon-dp.decode`. **Achado que revisa a premissa da própria task**: ela assumia ser "a última do
sub-espaço `size==0b11`" e mandava trocar o `null` residual por `unimplemented` — mas **B13.13**
(conversões `VRINT*`/`VCVT*`) segue `⬜`, então essa troca NÃO foi feita (ficaria sem espaço para
B13.13 registrar seu decoder depois). `docs/COBERTURA-ISA.md` byte a byte idêntica (nenhum preset
declara `CRYPTO`). G5 verde nos 5 consumidores. Ver **Resultado** na task.

**Bloqueadas por dependência aberta** (não pegar ainda): C12.6 (RFC, depende de C12.5), C12.8
(depende de C12.6+B13.22), A10.7 (depende da RFC C12.6), B13.16 (depende de B13.9-B13.15, todas ✅
— formalmente pegável, mas é um adaptador T32 melhor deixado para depois que `neon-shared` fechar,
ver B13.21), B13.21 (dependência formal B13.19 ✅/B19.7 ✅, mas sua spec pede fechar o arquivo
inteiro — pegar só depois de B13.20), B13.22 (preset, depende do arquivo `neon-shared` fechado),
B19.9 (fechamento do épico B19, depende de B19.10-B19.13 — B19.10/B19.12/B19.13 ✅, só B19.11 ainda
⬜).

**Ordem sugerida**: qualquer uma das três acima. **B13.18 FECHADA 2026-09-05** — `VSDOT`/`VUDOT`/
`VUSDOT` (vetorial) + as 4 formas `_scalar` (`FEAT_DotProd`/`FEAT_I8MM`, DUAS features), núcleo
`AdvSimdLanes.dotProduct`/`dotProductByElement` NOVO (achado: nem `SDOT_v`/`UDOT_v` nem
`USDOT`/`SUDOT` do A64 têm decoder ainda, ao contrário do que a spec da B19.12 registrava — não há
semântica A64 para migrar). Achado colateral (pré-existente, não introduzido por esta task):
`VUDOT_scalar`/`VSUDOT_scalar` colidem estruturalmente com `CoprocessorRegisterDecoder` — sem a
feature, decodificam como `COPROCESSOR` (coprocessador 13, inerte), não `UNIMPLEMENTED`, mesmo
comportamento de antes desta task. `IrOp.Kind` 89→91. `docs/COBERTURA-ISA.md` byte a byte idêntica
(zero-diff, nenhum preset declara as features novas). G5 verde em gbaemu/ndsemu/armbox. **B13.17
FECHADA 2026-09-05** — `VCMLA`/`VCADD`
(vetorial) + `VCMLA_scalar` (`FEAT_FCMA`), `ArmFeature.COMPLEX_NUMBER_ARITHMETIC` nova (nenhum
preset a declara); cria o `NeonSharedDecoder` (devolve `null` para as 19 linhas ainda sem dono,
B13.18-B13.21 completam o arquivo); núcleo `AdvSimdLanes.fpComplexAdd`/`fpComplexMultiplyAccumulate`
NOVO (sem semântica A64 prévia para migrar — exceção do épico, A64 reusa quando `FCMLA`/`FCADD`
ganharem decoder); layout medido byte a byte contra `arm-none-eabi-as -march=armv8.3-a` (devkitARM)
e confirmado que A32/T32 produzem o MESMO `raw32` (dispensa a B13.16 para este arquivo, achado já
esperado pela spec). `IrOp.Kind` 87→89. `docs/COBERTURA-ISA.md` byte a byte idêntica,
`docs/COBERTURA-JIT.md` regenerado. G5 verde nos 5 consumidores. **B19.8 FECHADA 2026-09-05** —
`LUTI2`/`LUTI4`
(`FEAT_LUT`) gateados como quinto caso real do padrão da B11.4 (feature checada antes de
EXT/permute/TBL, zero colisão pré-existente); achado que corrige a spec original: a tabela é `Rn`
e os índices são `Rm` (não o inverso), confirmado contra o fonte real do QEMU e contra a mesma
convenção que `TBL`/`TBX` já usa neste projeto. `docs/COBERTURA-ISA.md` global 84%→85%,
`ARMv9.5-A` 901→905/1146; `docs/COBERTURA-JIT.md` também regenerado (achado incidental: precisa de
`mvn install` do `core` antes, senão o `exec:java` do `truffle` mede um jar velho). **C12.4 FECHADA
2026-09-05** — os 6 `Kind` de FP
escalar restante (`FMADD`/`FCSEL`/`FCCMP`/`FRINT*`/conversão geral/`FMOV` cru) via o MESMO
mecanismo de reconstrução de record que B6.5.4/C12.3 (zero aritmética nova), ASM 64 bits 40→46 de
96, G5 zero-diff nos 5 consumidores. **C12.7 FECHADA 2026-09-05** — os 20 records via `IrOpInterop`
cercado de flush/reload (não helpers dedicados: 5 usam `IrExecutionSupport`, package-private, ver
**Resultado** na task), ASM 32 bits 37→57 de 84, G5 zero-diff nos 5 consumidores. **A10.6 FECHADA
2026-09-04** — Truffle 32 bits 40→42/84 (`DspDualMultiply`/`DspTopWordMultiply`
no `MultiplyOpNode`). **E13 FECHADA 2026-09-04** — 12 células `✅`→`·` em `v6K`/`MPCore`
(não 14, correção de número da spec), G5 verde nos 5 consumidores incl. n3dsemu (ARM11 MPCore, sem
regressão). **E12 FECHADA 2026-09-04** — era a que consertava a MEDIÇÃO, e o denominador agora é
honesto (ver abaixo). **B19.5.2 FECHADA 2026-09-04**, **E11 FECHADA 2026-09-03**.

### ⚠️ A E12 mudou o denominador: números anteriores a 2026-09-04 estão obsoletos

A E12 tirou de `docs/isa-nao-aplicavel.tsv` 111 linhas que escondiam **134 linhas da tabela A64** nas
16 colunas de versão, e reescopou 2 linhas `*` que escondiam mais 9. O denominador global cresceu
**1240 células** e o **global caiu de 89% para 84%** — revelação de trabalho, não regressão
(precedentes B9.11 e B19.5.2). Duas consequências para quem for planejar:

1. **`⚠️` voltou a existir na tabela** (66 células): ao parar de esconder, descobriu-se que 10
   mnemônicos que mediriam `✅` na verdade **misdecodificam** (G8) — `CPY*`/`SETG*` viram
   `FpLoadLiteral64`, `LDRA` vira `NOP_HINT`, `FAMAX`/`FAMIN`/`FSCALE` viram `VectorInsert*`. Isso
   virou **task nova** (ver "O que ainda precisa de spec").
2. **`SEVL` ganhou 16 células `✅`** que a TSV apagava — a tabela também estava SUB-reportando.

### As 4 dimensões (o mapa: [`ROADMAP-100-ARM.md`](ROADMAP-100-ARM.md))

Um `✅` em `docs/COBERTURA-ISA.md` **não** significa que algum backend compile a instrução.

| # | Dimensão | Onde se mede | Estado |
|---|---|---|---|
| 1 | Decode + interpretado | `docs/COBERTURA-ISA.md` | **84%** (pós-E12) · A64 `ARMv8.0-A` 97% (851/877) · `ARMv9.5-A` 77% (891/1146) |
| 2 | Emissão JIT nativa | `docs/COBERTURA-JIT.md` | ASM 32 **57 ✅ + 9 ⚠️ / 84** (pós-C12.7) · ASM 64 **46/96** (pós-C12.4) |
| 3 | Truffle | `docs/COBERTURA-JIT.md` | 32 bits **66/84** (pós-A10.5) · 64 bits **0/96** (não existe) |
| 4 | Catálogo de processadores | — | downstream de 1 |

### Os achados desta rodada que mudam decisões

1. ~~**A tabela de cobertura mede um alvo MÓVEL.**~~ **RESOLVIDO pela E11 (2026-09-03).**
   `gerar-cobertura-isa.sh` agora fixa `QEMU_REV` num SHA (`2931a675e9d3…`), invalida o cache por
   `target/isa-decode/.rev`, e a revisão aparece no cabeçalho de `docs/COBERTURA-ISA.md`. Contra a
   revisão FIXADA o único delta é `t16` 86→87 (`MAYBE_UNDEF_T1_HINT`); `sve`/`sme` ficam 929/623 (o
   +18/+28 que a rodada de spec viu era de commits POSTERIORES ao SHA — B17/B18 seguem corretos).
   `MAYBE_UNDEF_T1_HINT` curado para v4T/v5TE ⇒ **v4T/v5TE seguem 100%** e T16 segue com **0 `❌`**;
   a manchete da B22.6 continua verdadeira, agora ancorada na revisão. O gap de *gating* que o
   commit expôs (espaço de hint T1 INTEIRO é v6T2+ em perfil A — reverte a B9.14 para v6K/MPCore)
   virou a task **E13**.
2. ~~**A curadoria `A64` da TSV esconde trabalho onde a feature EXISTE.**~~ **RESOLVIDO pela E12
   (2026-09-04)** — e a spec da E12 estava **errada em 3 pontos**, corrigidos por re-medição antes de
   executar: eram **111** linhas `A64` (não 123) atingindo **134** da tabela; **8** features sem
   constante (não 2); e havia um **segundo mecanismo** de esconderijo que a spec não citava (linhas
   com arquitetura `*`, que apagavam até 16 células `✅` REAIS de `SEVL`). `isAarch64VersionColumn`
   foi removido e `IsaCoverageReportA64CurationGuardTest` fecha as duas portas. **Lição registrada**:
   a spec v1 mediu por amostragem do TEXTO das justificativas em vez de contra a tabela — é o mesmo
   erro que o rodapé desta seção já alertava ("escrever spec sem medir"), e a sessão de execução
   acertou ao PARAR e reportar em vez de forçar os números.
3. **A escada do B19 tinha 4 grupos sem dono**, achados ao classificar as 116 linhas `❌`: cripto
   SHA-512/SM3/SM4 (13, no MESMO prefixo `0xCE` que a B11.12 abriu e deixou pela metade), `FEAT_FP8`
   (12, a única sem constante em `Aarch64Feature`), `FEAT_I8MM` (6) e `FEAT_FHM` (8, feature própria
   e **não** `FEAT_FP16`). ⇒ B19.10-B19.13.
4. **A escada do C12 tinha 8 `Kind` de sistema sem dono** — a conta 16+6+7+35+**8**=72 só fecha com
   eles. ⇒ **C12.10**.
5. **`VUZP`/`VTRN`/`VZIP` do A32 escrevem DOIS registradores** e não são as `UZP1`/`UZP2`/`TRN1`/…
   do A64 (seis instruções de UM destino). Mapear 1:1 pelo nome produziria metade do resultado.
6. **T32 é transformação mecânica do A32** (`1111_001p_q…` ↔ `111p_1111_q…`, 24 bits baixos
   idênticos) ⇒ B13.16 é um adaptador que delega. E **`neon-shared` dispensa a B13.16**: seu
   encoding já é o mesmo para os dois.
7. **`AdvSIMDExpandImm` não existe no projeto e falta nos DOIS lados** (`Vimm_1r` A32 / `Vimm` A64)
   ⇒ a spec da B13.9 põe o algoritmo no núcleo desde o início, e a B19.6 o reusa.
8. **A pergunta do SIMD nos backends é UMA só** (emitir lane a lane × chamar `AdvSimdLanes` × não
   fazer): **C12.6 é a RFC, A10.7 aplica**. Idem C12.9 ↔ A10.9, que remedem o mesmo arquivo.

### Correção de número importante (B19.5.2)

A primeira versão daquela spec dizia "+192 células". **Errado**: 12 das 96 linhas a curar já são `·`
— mas pela curadoria grossa da TSV. O efeito real são **168 `❌→·`** (v8.0/v8.1) **e 168 `·→❌`**
(v8.2..v9.5, trabalho revelado). **O global fica inalterado em 89%**: a task não infla o número,
torna-o honesto. v8.0/v8.1 88%→97%, v8.2+ 88%→**87%**.

### O que fechou recentemente (detalhe no `INDICE.md` de cada trilha, nunca aqui)

`B13.7` · `B13.8` · `B13.9` · `B13.10` · `B13.11` · `B19.4` · `B19.5.1` · `B19.5.2` · `E10` · `E11`
· `E12` · `E13` · `A10.1` · `A10.3` · `A10.4` · `A10.5` · `A10.6` · `C12.1` · `C12.2` · `C12.3` ·
`C12.4` · `C12.7` · `B19.8` · `B13.12` · `B13.17` · `B13.18` · `B19.5.3` · `B19.6` · `B19.7` ·
`B19.10` · `B19.12` · `B13.14` · `B19.5.4` · `B19.5.5` · `B13.15` · `B13.19` · `B13.20` · `B19.5.6` ·
`B19.13` · `B19.11a` · `B19.9` ·
**épicos `B19` e `B22` inteiros**.

### O que AINDA precisa de spec

Os **7 épicos em `📋 plano`**, que nunca tiveram escada medida — **~84 degraus**:

| Épico | O que | Degraus |
|---|---|---:|
| [B14](trilha-b-arquiteturas/b14-plano-vfp-armv8-32bit.md) | VFP incondicional ARMv8-A de 32 bits | 7 |
| [B15](trilha-b-arquiteturas/b15-plano-armv8m.md) | ARMv7E-M / ARMv8-M / ARMv8.1-M | 7 |
| [B16](trilha-b-arquiteturas/b16-plano-mve-helium.md) | MVE / Helium | 14 |
| [B17](trilha-b-arquiteturas/b17-plano-sve.md) | SVE / SVE2 | 26 |
| [B18](trilha-b-arquiteturas/b18-plano-sme.md) | SME / SME2 | 13 |
| [B20](trilha-b-arquiteturas/b20-plano-perfil-r.md) | Perfil R (PMSA/MPU) | 9 |
| [B21](trilha-b-arquiteturas/b21-plano-arm-26-bits.md) | ARMv1-ARMv3, modelo de 26 bits | 8 |

Mais: a task irmã **"NEON FP16 AArch32"** (registrada por B13.6/B13.8/B13.11/B13.13), os **9 `⚠️`
condicionais** de 32 bits (registrados pela C12.7), o **cache de registradores do `Ir64BlockCompiler`**
(dívida da B6.4) e a task de fechamento do catálogo de processadores.

**Duas tasks NOVAS que a E12 mediu e não executou** (ela era zero-decode):

1. **Misdecode A64 / dívida G8** — 10 linhas, 66 células `⚠️`, repro determinístico em
   `IsaCoverageReport.AARCH64_MISDECODED`: `CPYP`/`CPYM`/`CPYE`/`SETGP`/`SETGM`/`SETGE` decodificam
   como `FpLoadLiteral64` (a **mesma classe de bug que a B11.3 corrigiu** para o `LDR (literal)`
   INTEIRO — sobrou o caminho de ponto flutuante), `LDRA` cai no catch-all de hint-space, e
   `FAMAX`/`FAMIN`/`FSCALE` colidem com o espaço de `INS`/`MOV` vetorial.
2. **Coluna `ARMv9.6-A`** — `FEAT_FPRCVT`, `FEAT_F8F16MM` e `FEAT_F8F32MM` já existem como constante
   (14 linhas do inventário) e nenhum preset as declara. Criar a coluna exige auditar TODAS as
   features contra a v9.6 e muda o denominador global.

**Os grupos que a E12 revelou** e seguem sem degrau no B19: `FEAT_MTE2` (26 linhas), `FEAT_PAuth`
(10), `FEAT_MOPS` (9), `FEAT_CRC32` (8, novo), `FEAT_FRINTTS` (8), `FEAT_LRCPC2` (7), família FP8 (7),
`FEAT_FCMA` (6), `FEAT_I8MM` (6), `FEAT_BF16` (5), `FEAT_CSSC` (5), `FEAT_CMPBR` (5), `FEAT_DotProd`
(4), `FEAT_LSE128` (3).

**Escrever spec sem medir é o erro que esta rodada pegou três vezes** (o inventário FP16 errava por 4
linhas e misturava 5 features; a escada do B19 tinha 4 grupos sem dono; a do C12, 8 `Kind`). Cada
spec custa medição instrução a instrução contra o oráculo **e** contra o código — os 7 épicos acima
são trabalho de várias sessões, e **B17/B18 sozinhos somam 39 degraus sobre inventários de 947 e 651
linhas**.

**Sonnet executa; 1 sessão = 1 task.**

### Tasks que EXISTEM mas não são pegáveis (para não serem redescobertas a cada sessão)

- **`B4.0.5`** (armbox fase 3: fork/execve/pipes) — tem spec, está ⬜, e é **bloqueada pelo
  congelamento de subprojetos** acima. Não pegar até 100% de cobertura de ISA.
- **`B6.5.1`** (banco FP escalar A64) — consta ⬜ no `INDICE.md` da trilha B, mas é **resíduo de
  bookkeeping**: o entregável existe (`core64/Aarch64FpRegisters.java`, depois alargado pela B8.6) e
  B6.5.2-B6.5.4 fecharam em cima dele. Não pegar.

## 🧑 Bloqueadas no usuário (agente NÃO pega; planejar presença)

| Task | Arquivo | O que precisa do usuário | Destrava depois |
|------|---------|--------------------------|-----------------|
| **C7** — `PagedAddressSpace` no ndsemu | `trilha-c-perf/c7-paged-address-space-ndsemu.md` | Validação de gameplay (boot dos 4 jogos de referência) — **também bloqueada pelo congelamento de subprojetos** | **C9** |
| C10 aceites #1/#2 pendentes | — | Medição fps MKDS + asmcheck JUS com ROM real — **também bloqueada pelo congelamento** | fecha C10 |
| ~~B6.6.6~~ **EM ESPERA** — hospedeiro `virt64` (kernel arm64 mínimo até shell) | `trilha-b-arquiteturas/b6.6.6-aarch64-virt64-host.md` | Toolchain resolvido (WSL2+Ubuntu); falta kernel arm64 mainline real + o gap `LDR`/`STR` SIMD&FP reg-imediato do B6.2 se o initramfs precisar dele | fecha o épico B6.6 |
| **B6.2 aceite #2** — busybox estático aarch64 (armbox) | `trilha-b-arquiteturas/b6-aarch64.md` (seção B6.2) | Gap real de decode A64 (`LDR`/`STR` SIMD&FP reg-imediato) — verificar se já foi fechado por B8.13/B8.20 antes de reabrir | fecha B6.2 |

## Fila de BUGS de compat (trilha D) — sessões separadas, **bloqueadas pelo congelamento de subprojetos** até 100% de ISA

| Task | O que é | Quem pode executar |
|------|---------|--------------------|
| **D6** — BIOS lenta/interrompida (gbaemu) | Timing/waitstate/handoff | ⚠️ MODELO FORTE |
| (sem task) Platinum billboard do char invisível — divergência de alocação de VRAM de textura | ndsemu | ⚠️ MODELO FORTE |
| **PROJETO WiFi** (multi-sessão) — Fase 1 shipped, falta Fase 2+ (handshake WM ARM9↔ARM7) | ndsemu | ⚠️ MODELO FORTE |
| Platinum não boota em INTERPRETED — race de boot cross-CPU | ndsemu | ⚠️ MODELO FORTE |
| Divergência ASM×interp no JUS | ver pendência 6 do `tasks/README.md` | ⚠️ MODELO FORTE |
