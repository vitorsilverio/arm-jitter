# Como contribuir

Este é um projeto pessoal, mas issues e pull requests são bem-vindos.

## Antes de abrir um PR

- Abra uma issue descrevendo o problema/ideia primeiro (use os templates de
  [bug](.github/ISSUE_TEMPLATE/bug.yml) ou [feature](.github/ISSUE_TEMPLATE/feature.yml)) —
  evita trabalho duplicado ou um PR que não se encaixa na direção do projeto.
- Compile e teste com **JBR 25** (a JDK do IntelliJ), não a JDK do sistema:

  ```bash
  mvn -o test
  ```

- Toda mudança de comportamento vem com teste automatizado cobrindo o caso novo.
- Mudanças aqui podem afetar os consumidores (`gbaemu`, `ndsemu`, `armbox`,
  `virtual-arm-box`, `n3dsemu`) — se sua mudança for relevante para eles, rode as
  suítes desses repos também antes de abrir o PR.
- Mantenha o estilo do código existente; não introduza dependências novas sem discutir
  antes na issue.

## Estrutura do repositório

O trabalho em andamento e o histórico de decisões técnicas vivem em [`tasks/`](tasks/README.md)
— é o "diário de bordo" do projeto, útil para entender por que algo foi feito de um jeito
específico antes de propor uma mudança.

## Dúvidas

Abra uma issue ou veja a seção de contato no [README](README.md).
