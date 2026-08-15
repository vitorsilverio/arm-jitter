# G6 — [REFINAR] ROMs comerciais `.cia` / `.3ds` no n3dsemu

**Trilha:** G · **Depende de:** G5 · **Repo:** n3dsemu
**🧑 BLOQUEADA NO USUÁRIO** — precisa de dumps de um console físico que hoje não temos.
**[REFINAR]:** esta é uma especificação de alto nível. **Não execute.** Ela vira spec
detalhada numa rodada nova, depois que a G5 fechar e o usuário tiver os dumps.

## Contexto e decisão

O usuário decidiu em 2026-08-15: começar por `.3dsx` (homebrew), e ROM comercial fica
registrada como task [REFINAR] para detalhar quando o marco homebrew fechar e os dumps
existirem. Ver RFC-N3DSEMU.md, decisão **D3**.

## O que essa task vai precisar (levantamento, para o refinamento futuro)

### Assets que só o usuário pode fornecer (dump do próprio console)

- `boot9.bin` (ARM9 BootROM) — contém as chaves de raiz usadas na derivação das chaves NCCH.
- `boot11.bin` (ARM11 BootROM) — necessário para alguns caminhos de derivação.
- `aeskeydb.bin` (opcional, dependendo da abordagem).
- Uma ROM `.3ds`/`.cia` de um jogo que o usuário possua.

Sem `boot9.bin` **não há caminho legítimo**: as chaves não são deriváveis nem publicáveis.
Se o usuário não puder dumpar, esta task não acontece — e isso é um resultado aceitável.

### Trabalho técnico previsto

1. **Contêiner**: `.3ds`/`.cci` = NCSD com partições; `.cia` = contêiner de instalação com
   ticket + TMD + conteúdos. Parsear os dois.
2. **NCCH**: cada partição tem cabeçalho NCCH, `ExeFS` (com `.code`, `icon`, `banner`) e
   `RomFS`. Ambos criptografados (AES-CTR, e AES-CBC no ticket).
3. **Derivação de chave**: `KeyX` (do boot9) + `KeyY` (do cabeçalho NCCH) → chave normal por
   um *keyscrambler*. Há três gerações de criptografia NCCH (`0x2C`, `0x25`, `0x18`, `0x1B`).
4. **Carregamento**: `.code` do ExeFS, com o `ExHeader` dando endereços, tamanhos, e o
   **conjunto de serviços** que o processo pode acessar.
5. **RomFS montado** como `romfs:` — a partir daí `fs:USER` deixa de ser stub e precisa de
   implementação real (`OpenFile`, `Read`, `GetSize`, diretórios).
6. **Muito mais serviço em HLE**: jogos comerciais usam `ndsp` (áudio), `cam`, `mic`, `am`,
   `ns`, `cfg` completo, `ir`, `boss`, `frd`... Cada um é trabalho próprio.
7. **Save**: `fs:USER` com `SaveData` persistente em disco.

### Ordem provável (o refinamento vai decidir)

G6.1 contêiner + cripto + carregamento do `.code` → G6.2 RomFS + `fs:USER` real →
G6.3 `ndsp`/áudio → G6.4 o resto dos serviços, guiado por um jogo alvo escolhido.

## Perguntas para o usuário no refinamento

1. Ele consegue/quer dumpar `boot9.bin` do próprio console?
2. Qual jogo é o alvo? (a escolha muda drasticamente quais serviços importam)
3. Áudio entra junto ou fica para depois?
