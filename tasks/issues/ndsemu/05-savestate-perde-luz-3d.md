## Resumo

Ao restaurar um savestate, o estado de iluminação 3D se perde — a cena volta com a luz errada.

## Como reproduzir

Salvar estado numa cena 3D iluminada, restaurar, comparar.

## Causa provável

Registradores de luz da engine 3D não estão sendo serializados no savestate (o estado da GPU
3D do DS tem registradores de configuração que só são escritos uma vez, na inicialização da
cena — se não forem salvos, a restauração fica com o default).

## Nota relacionada (bug DIFERENTE, já corrigido)

O outro problema de savestate — o JIT ficar frio por mais de 10 minutos depois de restaurar —
**já foi corrigido** com `JitRuntime.reset()`: `superblockHeads` e o estado do detector
sobreviviam ao `clear()`. Não confundir.

## Labels sugeridas

`bug`
