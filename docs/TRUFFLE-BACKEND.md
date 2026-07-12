# Backend Truffle/GraalVM — detalhes e benchmarks

Conteúdo de referência para o backend `TRUFFLE` (módulo opcional `arm-jitter-truffle`,
trilha A do roadmap — ver [tasks/README.md](../tasks/README.md) para o índice completo
de tasks A0-A6). O [README.md](../README.md) principal só traz a tabela de decisão
rápida; este documento tem os números e o histórico por trás dela.

## Por que existe

O único motivo real da trilha A é viabilizar JIT dentro de **native-image** — o backend
ASM define classes em runtime (`defineClass`), que native-image não suporta, então um
binário nativo hoje ficaria preso no interpretador (`INTERPRETED_IR`) sem o Truffle.
Ganho de performance em JVM normal **não** é a motivação: lá o ASM continua sendo a
escolha (ver benchmarks abaixo).

`TruffleJitRuntimeFactory.truffleArmThumb(cacheEntries, hotThreshold[, architecture])`
espelha `JitRuntimeFactory.armThumb(...)`: mesmo pipeline tiered (frio interpretado +
quente em background) e mesmo otimizador `StandardIrOptimizer.gba()` (aplicado no nível
do `JitRuntime`, já que `TruffleCodeEmitter` — ao contrário do `AsmCodeEmitter` — não
recebe otimizador no construtor).

## Bench ASM vs Truffle (task A4)

Bench honesto coletado na task A4 (não reaproveitado do spike A0): compara os
emissores de PRODUÇÃO (`AsmCodeEmitter`/`TruffleCodeEmitter`, não o protótipo
descartável da A0) executando o mesmo bloco ALU reto (`MOV`/`ADD`/`SUB` +
`Cycle`/`Fetch` por instrução, sem otimizador — igual à metodologia do
`RELATORIO-A0.md`) em tamanhos crescentes, best-of-5 × 2.000.000 execuções, mesma
máquina/sessão:

| Ambiente | JVM (`java -version`) | Bloco | ASM (`JVM_BYTECODE`) | Truffle (Graal) | truffle/asm |
|----------|------------------------|------:|----------------------:|-----------------:|------------:|
| JBR 25 puro + Truffle **Unchained** (`-XX:+EnableJVMCI --upgrade-module-path=<compiler 25.0.1> --module-path=<truffle-api/runtime/compiler> --add-modules=org.graalvm.truffle`) | `openjdk 25.0.3` (JBR-25.0.3+9-480.61) | 20 instr | 3,93 ns | 20,36 ns | 5,17× (pior) |
| idem | idem | 80 instr | 58,36 ns | **43,51 ns** | 0,75× (melhor) |
| idem | idem | 320 instr | 734,62 ns | **134,62 ns** | 0,18× (5,5× melhor) |
| GraalVM standalone (`E:\graalvm-jdk-25.0.3+9.1`, instalado pelo usuário) | `Java HotSpot(TM) 64-Bit Server VM 25.0.3` — banner `Oracle GraalVM 25.0.3+9.1 (build 25.0.3+9-LTS-jvmci-b01)` | 20 instr | 3,70 ns | 19,40 ns | 5,24× (pior) |
| idem | idem | 80 instr | **15,30 ns** | 33,70 ns | 2,20× (pior) |
| idem | idem | 320 instr | 693,00 ns | **96,60 ns** | 0,14× (7,18× melhor) |

`Truffle.getRuntime().getName()` confirmado como `"GraalVM CE"` na via JBR/Unchained e
como `"Oracle GraalVM"` na via standalone — em ambos os casos prova de compilação real
pelo Graal (não fallback interpretado). "JBR 25 puro" e "Unchained" são, na prática, **a
mesma via hoje**: o JBR usa o JVMCI embutido para rodar o Graal community como compilador
de Truffle sem precisar trocar de JDK (confirmado pela A0 e reconfirmado aqui) — não
existe uma segunda via "JBR sem Unchained" que compile Truffle de verdade neste ambiente,
então a tabela não duplica a linha.

**Nota sobre a distribuição standalone:** o instalador que o usuário baixou
(`E:\graalvm-jdk-25.0.3+9.1`) é **Oracle GraalVM**, não GraalVM CE — o `release` do
diretório lista módulos enterprise (`com.oracle.graal.graal_enterprise`,
`com.oracle.svm.enterprise.truffle`) que não existem na distribuição community pura (desde
o GraalVM 21 a Oracle passou a distribuir um único binário "Oracle GraalVM" gratuito via
GFTC, com o compilador Graal Enterprise embutido, em vez do antigo binário separado
"GraalVM CE"). Não há hoje uma distribuição GraalVM CE 25 LTS instalada nesta máquina;
os números acima são da via standalone realmente disponível, e são a melhor aproximação
possível de "Graal fora do JBR" — mantendo a ressalva no nome da coluna.

**Achado não previsto:** a via standalone tem um C2 (HotSpot) que compila o bloco reto de
80 instruções MUITO melhor que o C2 do JBR (15,3 ns vs 58,36 ns — quase 4× mais rápido no
mesmo hardware/sessão) — troca a posição do crossover: no JBR/Unchained o Truffle já vence
a partir de 80 instruções; na via standalone o ASM ainda vence a 80 e só perde a 320. O
lado Truffle/Graal, em contraste, é consistente entre as duas vias (43,51 vs 33,70 ns
a 80; 134,62 vs 96,60 ns a 320 — a via standalone é sempre igual ou mais rápida). A
diferença está inteiramente do lado do C2/ASM: builds de HotSpot diferentes (JBR vs
Oracle GraalVM), mesma versão 25.0.3, produzem qualidade de código bem diferente para um
método reto gigante — não é um artefato de metodologia (verificado com 3 execuções
consecutivas, resultado estável em todas). Conclusão prática: o ponto de crossover onde
compensa trocar para Truffle **depende da JVM de destino**, não é um número fixo.

**Recomendação:** para o tamanho de bloco típico de jogo (pequeno — a maioria dos blocos
reais em gbaemu/ndsemu tem poucas dezenas de instruções), o **ASM continua a escolha
certa** em qualquer uma das 3 linhas medidas — vence com folga a 20 instruções (5–5,2×) e
é o backend padrão dos dois consumidores (não muda, G3). O Truffle vale a pena quando o
bloco/trace é grande o suficiente para o C2 degradar, mas ONDE exatamente isso acontece é
JVM-dependente (a partir de ~80 instruções no JBR/Unchained; só a partir de algum ponto
entre 80 e 320 na via standalone) — candidato natural são os *loop-superblocos* da trilha
C (`C0`), que devem medir no ambiente de destino em vez de assumir o crossover de uma
única JVM. gbaemu/ndsemu não têm nenhuma integração com o backend Truffle hoje (fora de
escopo da A4 — factory é opt-in, sem wiring nos consumidores); os benches acima medem os
emissores de produção diretamente dentro do arm-jitter, não dentro de um jogo real via
ROM.

## Native-image (task A5) — resultado parcial

Demo feita no repo `armbox` (não gbaemu/ndsemu, decisão explícita do usuário — ver
`tasks/trilha-a-truffle/a5-native-image-demo.md`). Resultado: **🟡 parcial**, relatório
completo em [`tasks/trilha-a-truffle/RELATORIO-A5.md`](../tasks/trilha-a-truffle/RELATORIO-A5.md).

- ✅ Binário nativo (`armbox.exe`, GraalVM 25 + `native-maven-plugin`) executa ELFs reais
  com stdout/exit idênticos à JVM; backend ASM é recusado cedo e com mensagem clara sob
  native-image (`ImageInfo.inImageCode()`).
- 🔴 **Compilação real dos blocos NÃO acontece** — nem no binário nativo nem na JVM
  (JBR + Truffle Unchained): todo bloco ARM real (loop de verdade, diferente dos blocos
  sintéticos do bench acima) sofre bailout de partial evaluation. Causa raiz: o
  dispatcher único de `IrBlockExecutor#executeOp` (decisão de A2/A3) não é uma estrutura
  que o Graal consegue especializar/podar. Consequência medida: `--truffle` ficou **mais
  lento** que `--interp` num loop real, porque paga o custo de tentar compilar sem nunca
  conseguir.
- Task de continuação registrada: [A6 — Especialização de nós Truffle por `IrOp`](../tasks/trilha-a-truffle/a6-especializacao-nos-truffle.md).
