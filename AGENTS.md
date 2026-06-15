# Regras do projeto arm-jitter

- Este projeto é uma biblioteca auxiliar, sem classe `Main`.
- O objetivo é servir como ARM JIT/debug runtime para emuladores de Game Boy Advance e Nintendo DS, mantendo abertura para outros dispositivos ARM no futuro.
- A execução deve suportar fluxo contínuo e step-by-step.
- A arquitetura de referência está em `ARQUITETURA.html` e deve orientar nomes, módulos e responsabilidades.
- APIs públicas devem ter Javadocs. Como o projeto usa Java 25, prefira o formato markdown com `///`.
- O core deve ser depurável, rápido e modular.
- Mudanças precisam incluir ou preservar testes automatizados quando houver comportamento observável.
- Documentação de uso deve acompanhar a API pública.
- Não executar comandos fora do sandbox. Quando for necessário compilar ou testar, pedir ao usuário para executar.
