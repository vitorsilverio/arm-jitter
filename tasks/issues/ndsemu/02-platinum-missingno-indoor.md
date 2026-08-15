## Resumo

Pokémon Platinum: ao entrar em ambientes internos (casas), o personagem aparece corrompido
("missingno" — sprite com dados errados).

## Como reproduzir

**Reproduz em execução limpa e headless**, sem savestate: `NavProbe`/`IndoorProbe` (probes na
raiz do repo) navegam até a casa e o defeito aparece.

## Estado da investigação

- **Teoria de "corrupção acumulada" REFUTADA**: o defeito reproduz a partir de um boot novo,
  então não depende de horas de jogo nem de restauração de savestate.
- O binding do personagem **aponta para território de MAPA**, e há **zero uploads de dados do
  personagem** — ou seja, o bookkeeping de MMDL diverge em algum ponto.
- Engenharia reversa em andamento quando a investigação parou.

## Bugs vizinhos JÁ RESOLVIDOS (para não confundir)

Estes eram do mesmo jogo e já fecharam, pela abordagem de "ler melonDS/GBATEK primeiro, não
diffar savestate":
- grama-sobre-personagem (`d6e3e5a`): ordenação e profundidade exatas do hardware — Y-sort,
  translúcidos por último, Z quantizado `*0x200`, comparação estrita.
- rachaduras no mapa (`d09a3ca`): cobertura de borda do DS no rasterizador.

## Labels sugeridas

`bug`, `compat`, `gpu`
