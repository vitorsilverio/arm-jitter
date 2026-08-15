## Resumo

Funcionalidades da GUI que o **gbaemu já tem** e o ndsemu não. Portá-las.

## Itens

- [ ] **ROMs recentes** no menu.
- [ ] **Editor de configuração da firmware/BIOS**: nome do usuário, aniversário, MAC — hoje
      esses valores vêm do dump e não são editáveis pela interface.
- [ ] **Gamepad** via `input4j` (o gbaemu já usa, carregado por reflexão em
      `controller/GamepadController` para o emulador continuar compilando/rodando sem o jar).

## Referência

O gbaemu tem os três implementados; o código é o modelo direto (`ui/`, `controller/`).

## Labels sugeridas

`feature`
