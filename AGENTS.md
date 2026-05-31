# Regras do projeto arm-jitter

- Este projeto e uma biblioteca auxiliar, sem classe `Main`.
- O objetivo e servir como ARM JIT/debug runtime para emuladores de Game Boy Advance e Nintendo DS, mantendo abertura para outros dispositivos ARM no futuro.
- A execucao deve suportar fluxo continuo e step-by-step.
- A arquitetura de referencia esta em `ARQUITETURA.html` e deve orientar nomes, modulos e responsabilidades.
- APIs publicas devem ter Javadocs. Como o projeto usa Java 25, prefira o formato markdown com `///`.
- O core deve ser debugavel, rapido e modular.
- Mudancas precisam incluir ou preservar testes automatizados quando houver comportamento observavel.
- Documentacao de uso deve acompanhar a API publica.
- Nao executar comandos fora do sandbox. Quando for necessario compilar ou testar, pedir ao usuario para executar.
