# B5 — 3DS enablement (checklist do lado arm-jitter) **[REFINAR]**

**Trilha:** B · **Depende de:** B1.6 + VFPv2 (B3.2–B3.4) · **Repo:** hospedeiro novo (irmão de gbaemu/ndsemu)

## Contexto

Um emulador de 3DS é um PROJETO HOSPEDEIRO novo (como gbaemu/ndsemu); o arm-jitter só
precisa entregar os cores corretos. Hardware do 3DS:

| Core | Arquitetura | Estado no arm-jitter |
|------|-------------|----------------------|
| ARM11 MPCore ×2 (aplicação) | ARMv6K + VFPv2 | B1.* + parte VFP de B3 |
| ARM9 (segurança/DS-compat) | ARMv5TE | ✅ já coberto |

## O que o arm-jitter precisa entregar (checklist)

- [ ] B1.1–B1.6 (ARMv6K completo com emissão nativa).
- [ ] VFPv2 (subconjunto de B3: banco S/D, FPSCR, aritmética básica — o 3DS OS usa).
- [ ] Monitor de exclusividade com visão global por `AddressSpace` (2 cores ARM11
      compartilham memória — evoluir a nota deixada em B1.4).
- [ ] Preset `ArmArchitecture` para o MPCore (ARMv6K + VFPV2 feature).
- [ ] MMU: o 3DS usa MMU de verdade (kernel Horizon) — B4.1 é pré-requisito para
      emulação LLE do OS. Alternativa HLE (implementar serviços do Horizon no
      hospedeiro, sem MMU) reduz drasticamente o custo — decisão do hospedeiro, não
      do arm-jitter.

## O que fica no hospedeiro (fora do arm-jitter)

Dois cores ARM11 + escalonamento entre eles, GPU PICA200, DSP, serviços/HLE,
timing MPCore, periféricos, cartucho/filesystem.

## Refinamento

Detalhar quando B1.6 concluir e o hospedeiro nascer; o primeiro marco realista é
homebrew 3DS (libctru) com HLE mínimo, não jogos comerciais.
