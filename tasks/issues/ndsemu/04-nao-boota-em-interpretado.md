## Resumo

O ndsemu **não boota** com o backend INTERPRETED. Só funciona com JIT/ASM.

## Por que importa

O interpretador é o **oráculo de semântica** do projeto (invariante G1 do arm-jitter): toda
divergência entre backends é diagnosticada comparando com ele. Se o interpretado não boota,
essa ferramenta de diagnóstico não está disponível para o ndsemu — o que encarece toda
investigação de bug de compatibilidade (ver as outras issues deste repo).

Além disso, o gbaemu roda em INTERPRETED por padrão; a assimetria sugere que algo específico
do ndsemu (dual-core, IPC, timing entre ARM9/ARM7) depende de um comportamento que só o
caminho de blocos compilados produz.

## Estado da investigação

Não investigado.

## Labels sugeridas

`bug`, `needs-design`
