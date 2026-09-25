package dev.vitorsilverio.armjitter.arch64;

/// Uma extensão AArch64 opcional (`FEAT_*`, ARM DDI 0487) que pode diferir entre versões de
/// arquitetura ARMv8.x-A/ARMv9.x-A — mirror de {@link dev.vitorsilverio.armjitter.arch.ArmFeature}
/// para o lado de 64 bits (B11.1, ver `tasks/trilha-b-arquiteturas/b11-plano-aarch64-feature-gating.md`).
///
/// Cada entrada aqui é uma extensão real ainda NÃO implementada no decoder/executor A64 — todas
/// já estão catalogadas em `docs/isa-nao-aplicavel.tsv` com a versão ARM real que as introduz.
/// Esta task (B11.1) só declara a estrutura; nenhum decoder consulta {@link Aarch64Architecture#has}
/// ainda (isso é B11.2 em diante). Tudo que o A64 já implementa incondicionalmente hoje continua
/// incondicional — o baseline `ARMv8.0-A` (ver {@link Aarch64Architecture#ARMV8_0_A}) representa
/// exatamente esse estado atual (G3, sem breaking change).
public enum Aarch64Feature {
    /// `FEAT_RDM` — "Rounding Double Multiply Add" (`SQRDMLAH`/`SQRDMLSH`, formas vetorial/escalar/
    /// indexada). ARMv8.1-A. Já isolado no decoder desde B8.8/B8.19 (excluído via
    /// `docs/isa-nao-aplicavel.tsv`), candidato natural à prova de conceito de B11.4.
    RDM,
    /// `FEAT_LSE` — "Large System Extensions" (`CAS`/`CASP`, compare-and-swap atômico). ARMv8.1-A.
    /// **Achado de B11.3**: já implementado desde B8.1, sem gate — `Aarch64Architecture#ARMV8_0_A`
    /// nunca teve isso corrigido até B11.5 conectar esta feature na cobertura por versão.
    LSE,
    /// `FEAT_PAN` — "Privileged Access Never" (`MSR (immediate) PAN`, forma registrador em
    /// `MSR_reg`). ARMv8.1-A. Mesmo achado de {@link #LSE}: já implementado sem gate desde B8.3.
    PAN,
    /// `FEAT_CRC32` — `CRC32B`/`CRC32H`/`CRC32W`/`CRC32X` e as variantes `CRC32C*` (checksum CRC-32
    /// e CRC-32C sobre GPR). **OPCIONAL em ARMv8.0-A, obrigatória a partir de ARMv8.1-A** — mesmo
    /// padrão de {@link #LSE}, e por isso declarada em `ARMV8_1_A`.
    ///
    /// Acrescentada pela **E12**: as 8 linhas de `CRC32`/`CRC32C` do inventário A64 mediam `·` nas
    /// 16 colunas de versão porque `docs/isa-nao-aplicavel.tsv` as excluía com arquitetura `*` (a
    /// linha foi escrita para o lado de 32 bits, onde é correta — `CRC32` não existe em
    /// ARMv4T..ARMv7-A). Do lado A64 elas são trabalho PENDENTE, não inaplicáveis: o
    /// `Aarch64Decoder` as deixa explicitamente fora de escopo hoje.
    CRC32,
    /// `FEAT_FP16` — aritmética de ponto flutuante em meia-precisão (`_hp`/`_h`) nos caminhos
    /// escalar e vetorial AdvSIMD. ARMv8.2-A.
    FP16,
    /// `FEAT_DotProd` — `SDOT`/`UDOT` (vetorial e indexado). ARMv8.2-A.
    DOT_PRODUCT,
    /// `FEAT_FHM` — `FMLAL`/`FMLSL`/`FMLAL2`/`FMLSL2` ("FP16 fused multiply-add long", vetorial e
    /// indexado). ARMv8.2-A.
    FP16_FUSED_MULTIPLY_ADD_LONG,
    /// `FEAT_SHA512` — `SHA512H`/`SHA512H2`/`SHA512SU0`/`SHA512SU1`. ARMv8.2-A.
    SHA512,
    /// `FEAT_SM3` — `SM3SS1`/`SM3TT1A`/`SM3TT1B`/`SM3TT2A`/`SM3TT2B`/`SM3PARTW1`/`SM3PARTW2`.
    /// ARMv8.2-A.
    SM3,
    /// `FEAT_SM4` — `SM4E`/`SM4EKEY`. ARMv8.2-A.
    SM4,
    /// `FEAT_SHA3` — `EOR3`/`RAX1`/`XAR`/`BCAX`. ARMv8.2-A. **Correção de B11.12** (a nota original
    /// de B11.3, "já implementado desde B8.11b, sem gate", estava ERRADA — B8.11b só cobriu
    /// `SHA1*`/`SHA256*`; `EOR3`/`RAX1`/`XAR`/`BCAX` nunca tiveram decoder nenhum até B11.12, que os
    /// implementou já gateados, ao contrário do padrão "implementado sem gate" de {@link #LSE}/
    /// {@link #PAN}/{@link #UAO}/{@link #FLAG_MANIPULATION}).
    SHA3,
    /// `FEAT_UAO` — "Unprivileged Access Override" (`MSR (immediate) UAO`, forma registrador em
    /// `MSR_reg`). ARMv8.2-A. Mesmo achado de {@link #LSE}: já implementado sem gate desde B8.3.
    UAO,
    /// `FEAT_JSCVT` — `FJCVTZS` (conversão FP→inteiro com semântica de `ToInt32` do Javascript).
    /// ARMv8.3-A.
    JAVASCRIPT_CONVERT,
    /// `FEAT_FCMA` — aritmética de número complexo (`FCADD`/`FCMLA`, formas vetorial e indexada).
    /// ARMv8.3-A.
    COMPLEX_NUMBER_ARITHMETIC,
    /// `FEAT_PAuth` — autenticação de ponteiro real (`PACIA`/`AUTDA`/`XPACD`/`XPACI`/`BRAA`/
    /// `BLRAA`/`RETAA`/`ERETAA`/... com chave/modificador reais). As formas "hint-space"
    /// (`PACIASP`, etc.) já decodificam como NOP desde B6.6.7 mesmo sem esta feature (RES NOP no
    /// hardware real quando `FEAT_PAuth` está ausente) — só as formas com efeito real de
    /// autenticação exigem esta feature. ARMv8.3-A.
    POINTER_AUTHENTICATION,
    /// `FEAT_LRCPC` — "Load-Acquire RCpc" (`LDAPR`/`LDAPRB`/`LDAPRH`, forma registrador `[Rn]`),
    /// semântica de aquisição RCpc (release-consistent processor-consistent), mais fraca que a
    /// aquisição RCsc de `LDAR`. ARMv8.3-A. B19.1: primeiro degrau do épico B19 (fechar o gap
    /// remanescente do A64) — a ordenação RCpc é NOP observável neste interpretador single-thread
    /// (mesma simplificação de `LDAR`/`acquireRelease`), a feature existe só para o gate por
    /// versão. A forma com offset imediato (`LDAPUR`, `FEAT_LRCPC2`/ARMv8.4-A) é outra extensão,
    /// fora do escopo de B19.1.
    LRCPC,
    /// `FEAT_FlagM` — `RMIF`/`SETF8`/`SETF16` (manipulação de flags NZCV). ARMv8.4-A. **Achado de
    /// B11.3**: já implementado sem gate desde B8.2 (mesmo padrão de {@link #LSE}).
    FLAG_MANIPULATION,
    /// `FEAT_DIT` — "Data Independent Timing" (`MSR (immediate) DIT`, forma registrador em
    /// `MSR_reg`). ARMv8.4-A. Mesmo achado de {@link #LSE}: já implementado sem gate desde B8.3.
    DIT,
    /// `FEAT_LRCPC2` — "Load-acquire RCpc instructions v2": as formas com **offset imediato**
    /// (`LDAPUR`/`LDAPURB`/`LDAPURH`/`LDAPURSB`/`LDAPURSH`/`LDAPURSW` e `STLUR*`), ao contrário das
    /// formas `[Rn]` de {@link #LRCPC}. Opcional a partir de ARMv8.2-A, **obrigatória em
    /// ARMv8.4-A**. Acrescentada pela E12 (`LDAPR_i` tem 6 linhas no inventário e `STLR_i` 1 — as 7
    /// exigem esta feature, não a {@link #LRCPC} de ARMv8.3-A).
    LRCPC2,
    /// `FEAT_FRINTTS` — `FRINT32Z`/`FRINT32X`/`FRINT64Z`/`FRINT64X` (arredondamento dirigido para
    /// 32/64 bits, escalar e vetorial). ARMv8.5-A.
    DIRECTED_ROUNDING_TO_INTEGRAL,
    /// `FEAT_MTE2` — Memory Tagging Extension (`STG`/`LDG`/`IRG`/`SUBP`/`STGP`/... e o par de bits
    /// de tag por granule de memória que elas manipulam). ARMv8.5-A.
    MEMORY_TAGGING,
    /// `FEAT_FlagM2` — `AXFLAG`/`XAFLAG` (conversão entre flags NZCV reais e flags "externas" de
    /// ponto flutuante). ARMv8.5-A. **Achado de B11.3**: já implementado sem gate desde B8.2
    /// (mesmo padrão de {@link #LSE}).
    FLAG_MANIPULATION_2,
    /// `FEAT_BF16` — tipo `bfloat16` (`BFCVT`/`BFDOT`/`BFMMLA`/`BFMLAL`, formas vetorial e
    /// indexada). ARMv8.6-A.
    BFLOAT16,
    /// `FEAT_I8MM` — multiplicação de matriz inteira de 8 bits (`SMMLA`/`UMMLA`/`USMMLA`/`USDOT`/
    /// `SUDOT`, formas vetorial e indexada). ARMv8.6-A.
    INT8_MATRIX_MULTIPLY,
    /// `FEAT_WFxT` — `WFET`/`WFIT` (wait-for-event/interrupt com timeout). ARMv8.7-A. **Achado de
    /// B11.3**: já implementado sem gate desde B8.3 (mesmo padrão de {@link #LSE}).
    WFXT,
    /// `FEAT_MOPS` — operações de memória aceleradas (`SETP`/`SETM`/`SETE`, memset; `CPYFP`/
    /// `CPYFM`/`CPYFE`, memcpy forward-only; `CPYP`/`CPYM`/`CPYE`, memcpy genérico tipo
    /// `memmove`). ARMv8.8-A. **Achado de B19.16**: nenhum dos 9 tinha decode de verdade — o
    /// Javadoc anterior desta constante afirmava um "caminho genérico" para `SET*`/`CPYF*` que não
    /// existia (confirmado contra `docs/COBERTURA-ISA.md` e o comentário real do decoder).
    MEMORY_COPY_SET,
    /// `FEAT_NMI` — "Non-Maskable Interrupt" (`MSR (immediate) ALLINT`, forma registrador em
    /// `MSR_reg`). ARMv8.8-A. **Achado de B11.3**: já implementado sem gate desde B8.3 (mesmo
    /// padrão de {@link #LSE}).
    NMI,
    /// `FEAT_CSSC` — "Common Short Sequence Compression": `CTZ` (1 source), `SMAX`/`SMIN`/`UMAX`/
    /// `UMIN` (2 source), formas escalares GPR. ARMv8.9-A.
    COMMON_SHORT_SEQUENCE_COMPRESSION,
    /// `FEAT_SVE` — Scalable Vector Extension (registradores `Z`/`P`/`FFR`, vetor de comprimento
    /// escalável). Opcional a partir de ARMv8.2-A, **mandatória em ARMv9.0-A** (B17.1). Anunciada
    /// por `ID_AA64PFR0_EL1.SVE` (a fiação do registrador é B17.3). Nenhum decoder a consulta ainda.
    SVE,
    /// `FEAT_SVE2` — SVE2 (inteiro/FP/cripto/memória sobre `Z`/`P`). Introduzida com ARMv9.0-A.
    /// Nenhum preset a declara ainda: a versão em que é mandatória não foi confirmada (B17.1,
    /// Armadilha 2); núcleos reais com SVE2 entram pelo catálogo `Aarch64Processor` (B17.26).
    SVE2,
    /// `FEAT_SME` — Scalable Matrix Extension (armazenamento `ZA`, modo streaming, `SVCR`/`SMCR_ELx`).
    /// ARMv9.2-A. Desde a B18.1 o estado existe no `Aarch64Core` (`SVCR`, `SMCR_EL1/2/3`, banco `ZA`
    /// preguiçoso, `ID_AA64PFR1_EL1.SME`); ainda **sem efeito de modo streaming** (B18.2) e sem
    /// decode de `sme.decode` (B18.3+) — `MSR SVCR` (imediato) segue recusado com mensagem nomeada.
    /// É o primeiro dos oito predicados de gate que o QEMU fatia (`aa64_sme`, `aa64_sme2`,
    /// `aa64_sme2p1`, `aa64_sme_i16i64`, `aa64_sme_f64f64`, `aa64_sme_lutv2`, `aa64_sme_mop4`,
    /// `aa64_sme_tmop`); os outros sete vêm logo abaixo.
    SCALABLE_MATRIX_EXTENSION,
    /// `FEAT_SME2` — SME2 (`ZT0`, instruções multi-vetor). **Nenhum preset a declara ainda**: a
    /// versão de arquitetura que a torna mandatória/opcional não foi confirmada nesta sessão (B18.1,
    /// Armadilha 1 — feature declarada errada é pior que ausente). Habilita `ZT0` e `SMCR_ELx.EZT0`.
    SCALABLE_MATRIX_EXTENSION_2,
    /// `FEAT_SME2p1` — SME2.1. Sem preset (mesma razão de {@link #SCALABLE_MATRIX_EXTENSION_2}).
    SCALABLE_MATRIX_EXTENSION_2_1,
    /// `FEAT_SME_I16I64` — outer product de inteiros 16 bits acumulando em 64. Sem preset; só é
    /// anunciada em `ID_AA64SMFR0_EL1` pela task que a implementa (B18.5).
    SME_I16I64,
    /// `FEAT_SME_F64F64` — outer product de ponto flutuante de 64 bits. Sem preset (B18.5).
    SME_F64F64,
    /// `FEAT_SME_LUTv2` — `LUTI4` de quatro registradores/`MOVT` de `ZT0`. Sem preset (B18.6).
    SME_LUTV2,
    /// `FEAT_SME_MOP4` — outer product de quarto de tile (`MOP4`). Sem preset (B18.5).
    SME_MOP4,
    /// `FEAT_SME_TMOP` — outer product esparso (`TMOP`). Sem preset (B18.5).
    SME_TMOP,
    /// `FEAT_SME_FA64` — libera o conjunto completo de instruções A64 (AdvSIMD, estruturas `LDn`/`STn`,
    /// cripto, `FJCVTZS`) também em modo streaming, quando `SMCR_ELx.FA64 = 1` (B18.2). Sem preset: a
    /// versão de arquitetura não foi confirmada contra o manual (mesma disciplina das demais capacidades
    /// SME); sem ela, `PSTATE.SM = 1` torna essas instruções `UNDEFINED`.
    SME_FA64,
    /// `FEAT_FAMINMAX` — `FAMAX`/`FAMIN` (máximo/mínimo de valor absoluto em ponto flutuante).
    /// ARMv9.4-A.
    FP_ABSOLUTE_MAX_MIN,
    /// `FEAT_GCS` — Guarded Control Stack (`GCSSTR` e o restante da família de gerenciamento da
    /// pilha de controle protegida). ARMv9.4-A.
    GUARDED_CONTROL_STACK,
    /// `FEAT_LSE128` — atômicos de 128 bits (`LDCLRP`/`LDSETP`/`SWPP`). Opcional a partir de
    /// Armv9.3-A, **introduzida em Armv9.4-A**.
    ///
    /// ⚠️ `docs/isa-nao-aplicavel.tsv` afirmava `ARMv8.9-A` — **errado**, e corrigido pela E12 contra
    /// a página "The Armv9.4 architecture extension" da ARM. A diferença é observável: `ARMV9_4_A`
    /// estende `ARMV8_9_A`, então a versão certa deixa as 3 linhas `·` em `ARMv8.9-A` e `❌` só em
    /// `ARMv9.4-A`/`ARMv9.5-A`.
    LSE128,
    /// `FEAT_FP8` — formatos de ponto flutuante de 8 bits (OFP8 `E5M2`/`E4M3`) e as instruções de
    /// conversão/escala que os manipulam (`FSCALE` vetorial, `FCVTN`/`FCVTL` de/para `fp8`).
    /// Opcional a partir de Armv9.2-A, **introduzida em Armv9.5-A**.
    FP8,
    /// `FEAT_FP8DOT2` — produto escalar `fp8` de 2 vias com acumulação em meia precisão
    /// (`FDOT` `_hb_v`/`_hb_vi`). Armv9.5-A. O nome `FEAT_F8DP2` que aparecia em
    /// `docs/isa-nao-aplicavel.tsv` **não existe no ARM** — foi inventado a partir do mnemônico e
    /// corrigido pela E12 contra `docs/system/arm/emulation.rst` do QEMU.
    FP8_DOT_PRODUCT_2WAY,
    /// `FEAT_FP8DOT4` — produto escalar `fp8` de 4 vias com acumulação em precisão simples
    /// (`FDOT` `_sb_v`/`_sb_vi`). Armv9.5-A. Ver a nota de nome em {@link #FP8_DOT_PRODUCT_2WAY}
    /// (a TSV dizia `FEAT_F8DP4`).
    FP8_DOT_PRODUCT_4WAY,
    /// `FEAT_FP8FMA` — multiply-accumulate `fp8` FUNDIDO com acumulação em meia precisão
    /// (`FMLAL_hb_v`/`FMLAL_hb_vi`) ou precisão simples (`FMLALL_sb_v`/`FMLALL_sb_vi`). Opcional a
    /// partir de Armv9.2-A, **introduzida em Armv9.5-A** (B19.11b) — MESMO `ID_AA64FPFR0_EL1` que
    /// {@link #FP8_DOT_PRODUCT_2WAY}/{@link #FP8_DOT_PRODUCT_4WAY} (bit `F8FMA`, adjacente a
    /// `F8DP2`/`F8DP4`), confirmado via `isar_feature_aa64_f8fma`/`ID_AA64FPFR0_EL1.F8FMA` do QEMU
    /// real — a página `developer.arm.com` da versão Armv9.5-A não é fetchable diretamente (mesma
    /// limitação que a B19.11a/B19.11 já documentaram), então a versão foi inferida da MESMA leva de
    /// registrador de feature que as duas dot-product, não lida diretamente numa tabela de versões.
    FP8_FUSED_MULTIPLY_ADD,
    /// `FEAT_CMPBR` — `CB<cc>` (compare-and-branch condicional, formas registrador e imediato).
    /// ARMv9.5-A.
    COMPARE_AND_BRANCH,
    /// `FEAT_LUT` — consulta de tabela por lane com índices EMPACOTADOS (`LUTI2`/`LUTI4`
    /// AdvSIMD, B19.8). Introduzida em Armv9.5-A (confirmado contra o commit QEMU
    /// `5fbdd62ee22f929400a623b4a1725dea83b6da70`, "target/arm: Implement LUTI2, LUTI4 for
    /// AdvSIMD", gateado por `ID_AA64ISAR2_EL1.LUT`, parte da mesma série de patches que introduziu
    /// o resto da Armv9.5-A/SME2p1/SVE2p1). Não confundir com o `LUTI` de SVE/SME (`FEAT_SME2`/
    /// `FEAT_SVE2p1`, épicos B17/B18) — mesma ideia, encoding e registrador de estado diferentes.
    LOOKUP_TABLE,

    // ── Armv9.6-A: features REAIS do ARM que NENHUM preset declara ainda ─────────────────────────
    // A tabela `docs/COBERTURA-ISA.md` vai só até `ARMv9.5-A`, e criar uma coluna `ARMv9.6-A` é
    // outra task (muda o denominador global e exige auditar TODAS as features contra a v9.6). Uma
    // feature declarada por nenhuma arquitetura mede `architecture.has(f) == false` nas 16 colunas
    // ⇒ `·` em 16/16 — o mesmo resultado visual de quando elas viviam em
    // `docs/isa-nao-aplicavel.tsv`, mas pelo mecanismo CERTO: no dia em que existir uma coluna
    // `ARMv9.6-A`, as 14 linhas viram `❌` sozinhas, sem ninguém precisar lembrar de editar a TSV.
    // É o estado `NOT_IN_ANY_PRESET` que o `tasks/README.md` descreve — diagnóstico de lacuna de
    // infraestrutura, nunca exclusão (E12).

    /// `FEAT_FPRCVT` — conversão ponto flutuante ↔ inteiro **com operandos e resultado só em
    /// registrador SIMD&FP escalar**, e com tamanhos de entrada e saída diferentes (`SCVTF_simd`,
    /// `UCVTF_simd`, `FCVT{A,M,N,P,Z}{S,U}_g_simd` — 12 linhas do inventário). Opcional a partir de
    /// Armv9.5-A, **introduzida em Armv9.6-A**. Nenhum preset a declara ainda: ver o bloco acima.
    FP_INTEGER_CONVERT_SCALAR,
    /// `FEAT_F8F16MM` — multiplicação de matriz `fp8` com acumulação em **meia** precisão
    /// (`FMMLA_hb`). Armv9.6-A. A TSV dizia `FEAT_F8MM8`, nome inventado (E12). Nenhum preset a
    /// declara ainda: ver o bloco acima.
    FP8_MATRIX_MULTIPLY_FP16,
    /// `FEAT_F8F32MM` — multiplicação de matriz `fp8` com acumulação em precisão **simples**
    /// (`FMMLA_sb`). Armv9.6-A. A TSV dizia `FEAT_F8MM4`, nome inventado (E12). Nenhum preset a
    /// declara ainda: ver o bloco acima.
    FP8_MATRIX_MULTIPLY_FP32,

    // ---- B20.8: ARMv8-R AArch64 (Cortex-R82) — mirror de dev.vitorsilverio.armjitter.arch.ArmFeature
    // ---- do lado de 32 bits (B20.1), enums DIFERENTES por desenho (mesmo padrão já usado pelo
    // ---- projeto para RDM/CRC32/etc — ver Javadoc de ArmFeature#ADVANCED_SIMD_RDM).
    /// Marca um core AArch64 de **perfil R** (Cortex-R, tempo real) — mirror de
    /// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#R_PROFILE} (32-bit). Mesma honestidade de
    /// Javadoc do espelho: nenhum decoder/executor consulta isto diretamente hoje, é a AUSÊNCIA
    /// implícita de tudo que só faz sentido em perfil A (nada aqui ainda, `Aarch64Decoder` não tem
    /// um equivalente a `HYPERVISOR_CALL`/`SECURE_MONITOR_CALL` gateado por feature — `SMC`/`HVC`
    /// de 64 bits já são incondicionais). O que gateia de verdade é {@link #PMSA} (registradores de
    /// MPU) e o catálogo {@link Aarch64Processor#CORTEX_R82}.
    R_PROFILE,
    /// **PMSA** (Protected Memory System Architecture) de 64 bits — mirror de
    /// {@link dev.vitorsilverio.armjitter.arch.ArmFeature#PMSA} (32-bit). Gateia os 5 registradores
    /// de sistema novos (`MPUIR_EL1`/`PRSELR_EL1`/`PRBAR_EL1`/`PRLAR_EL1`/`PRENR_EL1`,
    /// `Aarch64Decoder#decodeSystemRegisterId`) e é consumida por
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Pmsav8SystemRegisters64}/
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Pmsav8AddressSpace64} (B20.8). **Mutuamente
    /// exclusiva com VMSA64** — nenhum preset A64 existente a declara, e o preset novo
    /// {@link Aarch64Architecture#ARMV8_R_64} não estende nenhum preset A64 existente que já tenha
    /// VMSA ligado (VMSA em A64 não é gateada por feature — é o hospedeiro que decide instalar
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Aarch64VmsaSystemRegisters} OU
    /// {@link dev.vitorsilverio.armjitter.memory.mmu.Pmsav8SystemRegisters64}, nunca os dois).
    PMSA
}
