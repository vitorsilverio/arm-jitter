# E9 — investigar a "regressão" de `docs/COBERTURA-ISA.md` achada pela B10.6b/B10.6c

**Trilha:** E · **Depende de:** — · **Repo:** arm-jitter

## Contexto

A sessão que fechou `B10.6b`/`B10.6c` (2026-08-27) regenerou `docs/COBERTURA-ISA.md` e viu 5
células de A32 (`MRS_bank`/`MSR_bank`/`ERET`/`HVC`/`SMC`) virarem `✅`→`❌` — instrução que
sessões anteriores (`B9.8.2`-`B9.8.5`) já tinham implementado. Decidiu NÃO commitar a tabela
(raiz não investigada, fora do orçamento daquela task) e documentou o achado como pendência.
Esta sessão foi priorizada pelo usuário para investigar essa pendência.

Task escrita e executada nesta sessão (não existia como linha em nenhum plano mestre).

## Investigação

Reproduzido rodando `gerar-cobertura-isa.sh` (decode trees já em cache local, `mvn -o -pl core
test-compile` + `IsaCoverageReport` direto) e comparando com o `docs/COBERTURA-ISA.md` commitado
(que datava de ANTES das sessões `B9.8.x`/`B9.9`/`B10.6b`-`c` — nenhuma delas tinha regenerado a
tabela de verdade, só a B10.6b/c tentou e descartou). O diff tem 3 categorias, nenhuma delas bug:

1. **Ganhos reais** (T32: `LDRxT`/`STRxT`/`LDRBT`/`STRBT`/etc. `❌`→`✅`, `HVC`/`SMC` T32
   `❌`→`✅` para `v7-A`) — efeito esperado de `B9.9`/`B9.8.2`/`B9.8.3`, nunca medido antes.
2. **"Regressão" em `MRS_bank`/`MSR_bank`/`ERET`** (A32, `✅`→`❌` nas 5 arquiteturas) — **falso
   positivo antigo corrigido**, não uma regressão de verdade. Antes de `B9.8.4`/`B9.8.5`
   existirem, esses encodings não tinham decode dedicado em `ArmDecoder` — caíam em algum caminho
   genérico que devolvia uma instrução (qualquer uma, não `UNIMPLEMENTED`) e o medidor contava
   como suporte, violando o invariante **G8** silenciosamente (a ferramenta não tinha como saber:
   `probeOnce` só desconta pra `FALLBACK` quando `group.simd()==true`, e o grupo `a32.decode`
   principal é `simd=false` — qualquer decode não-`UNIMPLEMENTED` conta `SUPPORTED`, mesmo se for a
   instrução ERRADA). `B9.8.4`/`B9.8.5` deram a essas 3 instruções decode dedicado e correto — que
   inclui, DE PROPÓSITO (ver comentário em `ArmDecoder.java` linhas 415-436), um gate por
   `ArmFeature.VIRTUALIZATION_EXTENSIONS`. Confirmado lendo `b9.8.4-eret-real.md`/
   `b9.8.5-mrs-msr-bank.md`: essa feature foi criada **sem nenhum preset habilitando ainda**
   ("nenhum consumidor modela V7VE hoje, aditivo puro" — decisão explícita, não esquecimento).
   Ou seja: o `❌` atual é o valor CORRETO — nenhuma arquitetura deste projeto declara Virtualization
   Extensions hoje, e o decoder agora recusa esses encodings de verdade em vez de confundi-los com
   outra coisa.
3. **`HVC`/`SMC` A32 parcialmente `✅`→`❌`** (deixam de ser `✅` em `v4T`/`v5TE`, e `HVC`
   deixa de ser `✅` em `v6K`/`MPCore` também) — mesmo mecanismo do item 2: antes do decode
   dedicado (`B9.8.2`/`B9.8.3`) essas duas instruções também eram um falso positivo universal;
   agora respeitam os gates reais (`ArmFeature.SECURE_MONITOR_CALL` para `SMC`, ativo desde `v6K`;
   `ArmFeature.HYPERVISOR_CALL` para `HVC`, só em `v7-A`) — coerente com a arquitetura real (Security
   Extensions chegou no ARMv6K; Virtualization Extensions só no ARMv7VE/`v7-A` neste projeto).

**Conclusão: não há bug nem instabilidade do medidor.** O diff inteiro é o efeito esperado — e
correto — de `B9.8.2`-`B9.8.5`/`B9.9` terem substituído misdecodes silenciosos (G8) por gates de
`ArmFeature` reais. A tabela committada antes desta sessão estava desatualizada (nenhuma sessão
tinha regenerado desde antes de `B9.8.x`), não errada por instabilidade de medição.

## Não inclui

- Criar um preset novo com `VIRTUALIZATION_EXTENSIONS` habilitado (ex.: um `v7-A+VE` para
  representar Cortex-A15/Cortex-A7) — nenhum consumidor real pede Hyp/Monitor mode de 32 bits
  hoje; vira trabalho pendente igual a qualquer outra célula `❌` do inventário, não urgente.
  Registrado aqui para não se perder (consistente com a "Regra máxima" do `tasks/README.md`: a
  feature não é "fora do escopo", só não tem preset ainda).

## Aceite

`docs/COBERTURA-ISA.md` regenerado e COMMITADO com a explicação acima — ao contrário da sessão
anterior, que descartou a tabela por não ter investigado a raiz.

## Validação

Nenhum arquivo de produção tocado (achado é sobre uma feature-gate já existente e documentada,
não um bug de código) — G5-invariante não se aplica, mesma disciplina de `E5`/`G6.1`.

## Resultado

✅ (2026-08-27) — raiz identificada: não é regressão nem instabilidade do medidor. `docs/
COBERTURA-ISA.md` estava desatualizado (última regeneração real anterior a `B9.8.x`); `B9.8.2`-
`B9.8.5` deram decode dedicado e corretamente gateado por `ArmFeature` a `MRS_bank`/`MSR_bank`/
`ERET`/`HVC`/`SMC`, substituindo um falso positivo universal (G8: encoding caía num caminho
genérico não-`UNIMPLEMENTED`) por `❌` honesto onde a feature real (`VIRTUALIZATION_EXTENSIONS`/
`HYPERVISOR_CALL`/`SECURE_MONITOR_CALL`) não é declarada por nenhum preset. Tabela regenerada e
commitada nesta sessão: global 73%→73% (2774→2771/3777, mesma faixa — sem gatilho de release);
v4T 66%→64%, v5TE 71%→70%, v6K 97%→96%, MPCore 96%→95%, v7-A 97%→98% (T32 subiu mais do que A32
caiu). Nenhum código de produção alterado. **Candidata registrada, não pega automaticamente**:
preset novo com Virtualization Extensions (Hyp/Monitor 32-bit) para as 5 células ficarem `✅` de
verdade, sem consumidor real pedindo isso hoje.
