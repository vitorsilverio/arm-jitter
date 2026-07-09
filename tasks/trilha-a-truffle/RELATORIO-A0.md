# RELATÓRIO A0 — Spike de viabilidade Truffle (2026-07-09)

**Veredito: TRILHA A VIÁVEL — kill criterion NÃO acionado.** E com uma descoberta não
prevista: em blocos grandes o Truffle/Graal **supera** o backend ASM, porque o C2
degrada em métodos retos gigantes. Isso conecta a trilha A à task C0 (superblocos).

Protótipo no branch `spike/truffle-a0` (`spike/SpikeBlockRootNode` + `spike/SpikeMain`).
Não mergear; este relatório é o único artefato no master.

## Medições

Bloco ALU encadeado (MOV/ADD/SUB/CMP + Fetch/Cycle por instrução, sem otimizador,
equivalência interp≡asm≡truffle verificada em cada run). JBR 25.0.3, mesma máquina,
best-of-5 × 2M execuções. "Truffle (Graal)" = Truffle Unchained (ver flags abaixo).

| Bloco | INTERPRETED_IR | JVM_BYTECODE (ASM) | Truffle (Graal) | truffle/asm |
|-------|---------------:|-------------------:|----------------:|------------:|
| 20 instr | 380–390 ns | **7,2–7,9 ns** | 22,2 ns | 3,07× (pior) |
| 80 instr | — | 50,1 ns | **38,0 ns** | 0,76× (melhor) |
| 320 instr | — | 753,8 ns | **104,8 ns** | 0,14× (7× melhor) |

Truffle SEM Graal (fallback interpretado, runtime "Interpreted"): 331–343 ns ≈ nosso
interpretador — inútil como backend, como esperado.

Por instrução: ASM = 0,36 ns/instr no bloco de 20, degradando para 2,36 ns/instr no de
320 (o `execute0` de 7099 bytes FOI compilado pelo C2 tier 4 — confirmado com
`-XX:+PrintCompilation` — e `-XX:-DontCompileHugeMethods` não muda nada: é qualidade
de código do C2 sob pressão de registradores em bloco reto gigante, não bail-out).
Truffle/Graal mantém ~0,33–0,47 ns/instr em todos os tamanhos.

## Respostas às 4 perguntas

**1. Truffle JITta no JBR 25?** SIM, via Truffle Unchained — compilação real confirmada
por `TraceCompilation` (Tier 1 + Tier 2) e pelo runtime reportar "GraalVM CE". O JBR
tem JVMCI funcional. Receita exata (deps Maven Central, versão 25.0.1):

```
# módulos Truffle: truffle-api, truffle-runtime, truffle-compiler (+ transitivos org.graalvm.*)
# compilador Graal: org.graalvm.compiler:compiler + word/collections/nativeimage
java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI \
     --upgrade-module-path=<compiler-25.0.1.jar;word;collections;nativeimage> \
     --module-path <jars truffle> --add-modules org.graalvm.truffle \
     --enable-native-access=ALL-UNNAMED -cp <app> ...
```

Sem o `--upgrade-module-path` do compilador, o runtime cai SILENCIOSAMENTE para
"Interpreted" (o `truffle-compiler` do module-path é só a interface). Verificar
`Truffle.getRuntime().getName()` em runtime é obrigatório no A2+.

**2. GraalVM CE standalone?** Não testado (só há GraalVM CE 17 instalado, velho demais
para Truffle 25, que exige JDK 21+). Irrelevante na prática: o Unchained no JBR usa o
MESMO compilador community ("GraalVM CE" no nome do runtime). Testar standalone fica
para o A4 (bench oficial nos 3 ambientes).

**3. Bytecode DSL vs AST clássica?** **AST clássica.** O formato do spike — um único
`RootNode` por bloco com `IrOp[]` `@CompilationFinal` + loop `@ExplodeLoop` + switch em
`op.kind()` — é exatamente o desenho do emissor real: 2 classes, ~150 linhas, PE
funcionou de primeira, sem nós por op. O Bytecode DSL existe no truffle-api 25.0.1
(pacote `com.oracle.truffle.api.bytecode`, 108 classes) mas continua experimental e
resolve problemas que não temos (quickening/boxing-elimination para interpretadores de
linguagem); nosso IR já é flat e tipado. Reavaliar só se o PE estourar orçamento em
blocos enormes.

**4. `CompiledBlock` ↔ `CallTarget`?** Mapeia direto: `callTarget.call(core)` com
retorno `Integer` boxed. O overhead da chamada (Object[] varargs + boxing + entry) é o
que custa o 3× no bloco de 20 instruções — é o preço por-chamada, não por-instrução.
Mitigável no A2+ (perfil de argumentos/retorno) e irrelevante em blocos grandes.

## Kill criterion

"Se em NENHUMA JVM o Truffle atingir ≥50% do ASM em bloco quente" → **não acionado**:
no bloco de 80 instruções o Truffle já faz 132% do ASM, e no de 320 faz 719%. A
ressalva honesta: no tamanho de bloco TÍPICO de jogo (pequeno — a média medida no
ndsemu é de poucos ciclos por bloco), o ASM vence por 3×. O Truffle como backend
substituto direto do ASM em blocos pequenos NÃO compensa.

## Consequências para o roadmap

1. **A trilha A segue** (A1 multi-módulo → A2 emissor), com a motivação recalibrada:
   native-image (motivação original, intacta) + **blocos/traces grandes**.
2. **Descoberta para a C0 (superblocos):** o plano de fundir sequências encadeadas em
   UM método ASM esbarra exatamente na degradação do C2 medida aqui (2,4 ns/instr aos
   320). Um superbloco Truffle não degrada (0,33 ns/instr). Candidato sério a desenho
   híbrido: blocos pequenos → ASM (C2), superblocos → Truffle (Graal). Registrado na
   spec da C0.
3. Consumidores em JBR precisam apenas de flags de launcher + deps Maven — nenhuma
   troca de JDK.

## Limitações do spike (não extrapolar)

- Só ALU + Cycle/Fetch, sem memória/branches/condicionais — a paridade de cobertura é
  o trabalho de A2/A3, e loads/stores (chamadas ao `AddressSpace` do hospedeiro) podem
  se comportar diferente sob PE (`@TruffleBoundary`).
- Um único bloco quente, sem pressão de cache de código nem warmup de milhares de
  blocos como num jogo real (medir no A4 com ROM real).
- Warmup Truffle observado: ~300ms para compilar o bloco (Tier1+Tier2) vs ~1ms do
  nosso emissor ASM — warmup de jogo com milhares de blocos precisa do pool assíncrono
  (já existe no tiered) e possivelmente de limiar mais alto para o tier Truffle.
