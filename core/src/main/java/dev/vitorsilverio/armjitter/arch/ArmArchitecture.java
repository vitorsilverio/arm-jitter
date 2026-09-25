package dev.vitorsilverio.armjitter.arch;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// Uma descrição imutável de uma versão de arquitetura ARM como um **conjunto de features**
/// (mais extensões de decoder opcionais), em vez de um número de versão. Decoders e o
/// mecanismo de execução consultam {@link #has(ArmFeature)} nos poucos pontos onde versões ARM
/// diferem, então adicionar uma nova versão é apenas declarar um novo conjunto de features aqui (e, para
/// grupos de instruções realmente novos, fornecer uma {@link DecoderExtension}) — o pipeline compartilhado
/// nunca é bifurcado.
public final class ArmArchitecture {
    /// ARM7TDMI — a CPU do GBA (também o ARM7 do NDS). O conjunto base: sem features extras. O
    /// decoder de transferência SIMPLES de coprocessador (`MCR`/`MRC`) é anexado aqui (B9.13):
    /// são ARMv3+, mais antigas que o próprio ARMv4T — ao contrário de `MCRR`/`MRRC`
    /// (ARMv5TE/extensão "E", só em {@link #ARMV5TE}+), que ficariam de fora se
    /// {@link dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder} completo fosse anexado aqui
    /// (violaria G2).
    public static final ArmArchitecture ARMV4T = of("ARMv4T")
            .withDecoderExtensions(List.of(new dev.vitorsilverio.armjitter.decoder.CoprocessorRegisterDecoder()));

    /// ARM9 — a CPU principal do NDS. ARMv4T mais o conjunto de features ARMv5TE (sem Thumb-2). O
    /// decoder de coprocessador é anexado aqui para que apenas cores ARMv5 decodifiquem `MCR`/`MRC` (CP15).
    public static final ArmArchitecture ARMV5TE = of("ARMv5TE",
            ArmFeature.BLX,
            ArmFeature.BLX_IMMEDIATE,
            ArmFeature.CLZ,
            ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING,
            ArmFeature.LDRD_STRD,
            ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY,
            ArmFeature.LDM_WRITEBACK_BASE_IN_LIST,
            ArmFeature.EMPTY_RLIST_NO_TRANSFER,
            ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL,
            ArmFeature.BREAKPOINT,
            // PLD/PLDW/PLI (B4.1.5, achado real): a arquitetura ARM real introduz PLD já na
            // ARMv5TE (ARM DDI 0100I e DDI 0406C confirmam "PLD is available in ARMv5TE,
            // ARMv5TEJ e ARMv6 em diante") — não apenas na ARMv6K, onde `PRELOAD_HINTS` estava
            // erroneamente só presente antes desta correção. Sem isto, qualquer código ARMv5TE
            // real usando PLD (comum em rotinas de cópia/zero de memória otimizadas do Linux,
            // ex.: `arch/arm/lib/copy_page.S`) decodifica como instrução indefinida e faz o
            // guest entrar (incorretamente) na exceção UNDEFINED — bug real encontrado ao
            // rodar `testdata/vmlinuz-3.2.0-4-versatile` (kernel ARM926EJ-S/ARMv5TE) no
            // hospedeiro `linuxbox` (B4.1.5): a instrução real `PLD [r1]` em
            // `arch/arm/lib/copy_page.S` travava o boot. G3: aditivo — só faz MAIS raws
            // decodificarem com sucesso (PLD/PLDW/PLI viravam UNDEFINED antes), nenhum
            // consumidor existente depende desses encodings específicos virarem UNDEFINED.
            ArmFeature.PRELOAD_HINTS)
            .withDecoderExtensions(List.of(new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()));

    /// ARM11 (MPCore/ARM1176) — 3DS principal e Raspberry Pi 1/Zero. ARMv5TE mais o conjunto
    /// user-level do ARMv6/v6K (sem Thumb-2, que é ARMv6T2+). Extend/reverse/UMAAL já são
    /// decodificados e interpretados (B1.2); os demais grupos chegam nas tasks B1.3–B1.5 e a
    /// emissão nativa ASM na B1.6.
    public static final ArmArchitecture ARMV6K = extending(ARMV5TE, "ARMv6K",
            ArmFeature.EXTEND_ROTATE,
            ArmFeature.BYTE_REVERSE,
            ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD,
            ArmFeature.PACK_SATURATE,
            ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED,
            ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA,
            ArmFeature.WAIT_HINTS,
            ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.SIGNED_MULTIPLY_MEDIA,
            // B9.8.3: SMC (ARMv6K base, ver Javadoc da feature) — mais antigo que HVC
            // (ARMV7A_FEATURES), então entra já aqui e é herdado por ARM11_MPCORE/ARMV6K_THUMB2/
            // ARMV7A automaticamente.
            ArmFeature.SECURE_MONITOR_CALL);
    // PRELOAD_HINTS (PLD/PLDW/PLI) agora vem herdado de ARMV5TE (correção acima) — antes desta
    // task estava listado aqui, sugerindo (erradamente) que só ARMv6K tinha PLD.

    /// ARMv6 **pura** (B12.5): ARM1136J(F)-S, base sobre a qual `ARMv6K`/`ARMv6T2`/`ARMv6Z` se
    /// ramificam. Mesmo conjunto "ARMv6+" de {@link #ARMV6K} MENOS as extensões que a ARM real só
    /// introduziu em versões subsequentes: sem {@link ArmFeature#EXCLUSIVE_SIZED} (`LDREXB/H/D`,
    /// `STREXB/H/D`, `CLREX` — ARMv6K), sem {@link ArmFeature#WAIT_HINTS} (`WFI`/`WFE`/`SEV`/`YIELD`
    /// como instruções dedicadas — ARMv6K) e sem {@link ArmFeature#SECURE_MONITOR_CALL} (`SMC` —
    /// ARMv6K/ARMv6Z, nunca ARMv6 base; ver o Javadoc da própria feature, gate real
    /// `ENABLE_ARCH_6K` no QEMU). Sem Thumb-2 (ARMv6T2+) e sem VFP (fora do escopo desta task, ver
    /// `tasks/trilha-b-arquiteturas/b12-catalogo-processadores-arm.md`, B12.5 — variantes "(F)" da
    /// Wikipedia ficam de fora, candidatas a uma sub-task futura que componha {@link
    /// ArmFeature#VFPV2} sobre este preset). Nenhum decoder/feature novo: reaproveita
    /// {@link ArmFeature#EXTEND_ROTATE}/{@link ArmFeature#BYTE_REVERSE}/{@link ArmFeature#UMAAL}/
    /// {@link ArmFeature#PARALLEL_SIMD}/{@link ArmFeature#PACK_SATURATE}/
    /// {@link ArmFeature#EXCLUSIVE_WORD}/{@link ArmFeature#MODE_CHANGE_INSTRUCTIONS}/
    /// {@link ArmFeature#SETEND_BIG_ENDIAN_DATA}/{@link ArmFeature#UNALIGNED_ACCESS}/
    /// {@link ArmFeature#SIGNED_MULTIPLY_MEDIA}, todos já existentes e já gateados nos decoders
    /// desde B1.2-B1.5/B9.1.
    public static final ArmArchitecture ARMV6 = extending(ARMV5TE, "ARMv6",
            ArmFeature.EXTEND_ROTATE,
            ArmFeature.BYTE_REVERSE,
            ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD,
            ArmFeature.PACK_SATURATE,
            ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA,
            ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.SIGNED_MULTIPLY_MEDIA);

    /// ARMv6T2 **pura** (B12.5): ARM1156T2(F)-S, o ramo "Thumb-2 sem as extensões de
    /// multiprocessamento do ARMv6K" — arquitetura DIFERENTE de {@link #ARMV6K_THUMB2} (que é
    /// ARMv6K + Thumb-2, o núcleo de referência do 3DS/B2). `MOVW_MOVT`/`MLS_MULTIPLY`/
    /// `BIT_FIELD`/`BIT_REVERSE` são genuinamente ARMv6T2 (confirmado contra `ENABLE_ARCH_6T2` real
    /// em `target/arm/tcg/translate.c` do QEMU — mesma nota já registrada em
    /// `ArmArchitectureTest#arm11MpCoreLacksArmv6t2AndDivideAndFusedVfp`), então entram aqui; sem
    /// {@link ArmFeature#MEMORY_BARRIERS} (`DMB`/`DSB`/`ISB` são ARMv7) nem
    /// {@link ArmFeature#DIVIDE} (`SDIV`/`UDIV` são extensão opcional do ARMv7-A/R). Sem VFP (mesma
    /// nota de {@link #ARMV6}, variantes "(F)" ficam de fora). Mesmo quebra-cabeça ovo-e-galinha de
    /// {@link #ARMV6K_THUMB2_FEATURES}: as features primeiro, porque os decoders Thumb-2 recebem a
    /// arquitetura no construtor.
    private static final ArmArchitecture ARMV6T2_FEATURES = extending(ARMV6, "ARMv6T2",
            ArmFeature.THUMB2, ArmFeature.MOVW_MOVT, ArmFeature.MLS_MULTIPLY,
            ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE);

    public static final ArmArchitecture ARMV6T2 = ARMV6T2_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV6T2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV6T2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV6T2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV6T2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6T2_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv6Z **pura** (B12.5): ARM1176JZ(F)-S, o ramo "Security Extensions (TrustZone) sem
    /// Thumb-2" — arquitetura irmã de {@link #ARMV6T2} (mesma base {@link #ARMV6}, ramo diferente).
    /// Único acréscimo real é {@link ArmFeature#SECURE_MONITOR_CALL} (`SMC`, entra em Monitor
    /// mode) — **aproximação documentada**: este projeto não modela mundos seguro/não-seguro nem
    /// bancos de registrador de TrustZone além do Monitor mode já existente (usado também por
    /// {@link #ARMV6K}/{@link #ARMV7A}), então só a instrução `SMC` em si funciona, não a separação
    /// completa de mundos do TrustZone real. Sem Thumb-2 (o ARM1176JZ(F)-S real não é ARMv6T2). Sem
    /// VFP (mesma nota de {@link #ARMV6}).
    public static final ArmArchitecture ARMV6Z = extending(ARMV6, "ARMv6Z",
            ArmFeature.SECURE_MONITOR_CALL);

    /// Preset Thumb-2 do épico B2 (B2.1-B2.6) mais a paridade de encodings de B2.7: as extensões
    /// de decoder de 32 bits (`Thumb2DataProcessingDecoder`, `Thumb2RegisterDataProcessingDecoder`
    /// [B2.7 PR1], `Thumb2MultiplyDecoder` [B2.7 PR2], `Thumb2LoadStoreDecoder`,
    /// `Thumb2BranchDecoder`, `Thumb2MiscDecoder`, `Thumb2CoprocessorDecoder` [B2.7 PR3])
    /// plugadas juntas. Até B2.6 só 2 delas estavam plugadas: `BL`/`BLX`
    /// imediato, decodificado como dois halfwords independentes (`LONG_BRANCH_PREFIX`+
    /// `LONG_BRANCH_SUFFIX`), fazia `ThumbDecoder#decode` ser chamado de novo no endereço do
    /// SEGUNDO halfword — que coincide, byte a byte, com o formato de um prefixo Thumb-2 de 32
    /// bits genuíno (o "fantasma"); `Thumb2LoadStoreDecoder`/`Thumb2MiscDecoder` reivindicavam
    /// esse fantasma e engoliam o sufixo real. **B2.6 fecha isso pela raiz**: com
    /// {@link ArmFeature#THUMB2} ativo, `BL`/`BLX` imediato é decodificado como instrução ÚNICA de
    /// 32 bits (`InstructionKind#LONG_BRANCH_32`, ARM DDI 0406C A8.8.25 — fiel ao hardware real
    /// desde ARMv6T2: a execução "meio a meio" só é arquitetural até ARMv6) — `decode()` nunca mais
    /// é chamado no endereço de um sufixo em código são, o fantasma deixa de existir, e as 4
    /// extensões podem reivindicar seus espaços livremente sem colisão. `MEMORY_BARRIERS` (ARMv7)
    /// entra aqui porque `Thumb2MiscDecoder` gateia `DMB`/`DSB`/`ISB` por ela (sem a feature elas
    /// virariam UNDEFINED — no preset "fechado" devem funcionar); `WAIT_HINTS` já vem herdado de
    /// `ARMV6K`. Ainda NÃO é o ARMv7-A completo da task B3 (sem VFP, sem SDIV/UDIV, sem os demais
    /// encodings de paridade v7 — ver B2.7).
    /// Só as FEATURES de {@link #ARMV6K_THUMB2} (sem as extensões de decoder ainda) — existe
    /// separadamente porque `Thumb2LoadStoreDecoder`/`Thumb2MiscDecoder` recebem uma
    /// `ArmArchitecture` no construtor (para gatear `LDRD_STRD`/`WAIT_HINTS`/`MEMORY_BARRIERS` em
    /// tempo de decode) e `ARMV6K_THUMB2` ainda não existe no ponto em que essas extensões
    /// precisam ser construídas — quebra-cabeça do ovo e da galinha resolvido construindo as
    /// features primeiro (idêntico ao padrão já usado pelos testes de B2.3/B2.5, ex.
    /// `Thumb2MiscDecoderTest`).
    /// `MOVW_MOVT` (B3.1) entra aqui pelo mesmo motivo de `MEMORY_BARRIERS`: `MOVW`/`MOVT` Thumb-2
    /// já eram decodificados desde B2.2, mas só gateados por `THUMB2` — B3.1 passou a exigir
    /// também esta feature (mesma exigida pelo encoding ARM em
    /// {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder}, B3.1), então o
    /// preset "fechado" precisa declará-la para continuar funcionando.
    private static final ArmArchitecture ARMV6K_THUMB2_FEATURES = extending(ARMV6K, "ARMv6K+Thumb2",
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS, ArmFeature.MOVW_MOVT);

    public static final ArmArchitecture ARMV6K_THUMB2 = ARMV6K_THUMB2_FEATURES
            .withThumb32DecoderExtensions(
                    List.of(new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6K_THUMB2_FEATURES),
                            new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv7-A **user-level** — fecha o épico B3 (task B3.7). `MOVW_MOVT`/`MEMORY_BARRIERS`
    /// já são herdadas de {@link #ARMV6K_THUMB2} (declaradas em
    /// {@link #ARMV6K_THUMB2_FEATURES} desde B3.1/B2.6, repeti-las aqui seria redundante,
    /// embora inofensivo por `EnumSet` ser idempotente) — mas `MLS_MULTIPLY`/`BIT_FIELD`/
    /// `BIT_REVERSE`/`DIVIDE` (as demais features do "inteiro v7" de B3.1) **não** são
    /// herdadas (só `ARMV7A` as habilita, por decisão do épico — nenhum preset anterior
    /// precisa delas), então precisam ser declaradas aqui explicitamente, junto com
    /// {@link ArmFeature#VFPV2}.
    ///
    /// **Desvio do plano original da task, documentado (mesmo padrão de honestidade de
    /// B3.1/B3.2)**: a spec previa `.withDecoderExtensions(List.of(new VfpDecoder(), new
    /// CoprocessorDecoder(), new ArmV7MediaDecoder()))` — mas `ArmV7MediaDecoder` NUNCA foi
    /// criada. B3.1 descobriu que as 13 instruções "media" v7 (`MOVW`/`MOVT`/`MLS`/`SBFX`/
    /// `UBFX`/`BFI`/`BFC`/`RBIT`/`SDIV`/`UDIV`/`DMB`/`DSB`/`ISB`) colidem com dispatches
    /// genéricos do {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} que retornam
    /// ANTES do loop de extensões — viraram carve-outs diretos no próprio `ArmDecoder`,
    /// gateados pelas features acima (mesmo padrão de `BLX`/`CLZ`/`UMAAL`). Este preset só
    /// precisa, portanto, das features certas (já herdadas) mais {@link VfpDecoder}/
    /// {@link dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder} como extensões de
    /// decoder — sem nenhuma classe de "media decoder" plugável.
    ///
    /// `withThumb32DecoderExtensions` SUBSTITUI a lista (não soma à da base) — por isso as
    /// 7 extensões de {@link #ARMV6K_THUMB2} são listadas de novo aqui, mais
    /// {@link dev.vitorsilverio.armjitter.decoder.VfpDecoder} (regra da B3.5: `VfpDecoder`
    /// deve vir ANTES de qualquer decoder de coprocessador genérico na ordem de registro,
    /// senão `Thumb2CoprocessorDecoder` captura o encoding CP10/11 primeiro).
    ///
    /// **Bug real corrigido (achado por B4.0.3 no armbox, gcc real `-march=armv7-a -mthumb`
    /// gerando `UBFX`/`SBFX` de um struct com bitfields)**: as 5 extensões de decoder
    /// reaproveitadas de {@link #ARMV6K_THUMB2} recebiam `ARMV6K_THUMB2_FEATURES` no
    /// construtor em vez de {@link #ARMV7A_FEATURES} — cada uma guarda a `ArmArchitecture`
    /// passada ali para gatear feature em tempo de decode, então `BIT_FIELD`/`BIT_REVERSE`/
    /// `MLS_MULTIPLY`/`DIVIDE` (só ligadas em `ARMV7A_FEATURES`) nunca eram vistas por
    /// `Thumb2DataProcessingDecoder`/`Thumb2RegisterDataProcessingDecoder`/
    /// `Thumb2MultiplyDecoder`, mesmo com o preset `ARMV7A` corretamente contendo essas
    /// features — `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em encoding **Thumb-2** viravam
    /// UNDEFINED (o carve-out ARM-mode equivalente em {@link
    /// dev.vitorsilverio.armjitter.decoder.ArmDecoder} usa a `architecture` real passada a
    /// cada chamada, não sofria o problema). Todas as 5 agora recebem `ARMV7A_FEATURES`
    /// (superconjunto de `ARMV6K_THUMB2_FEATURES`, então zero-diff para as instruções que já
    /// funcionavam).
    ///
    /// **User-level only**: sem MMU/CP15-VMSA (páginas, TLB, modos privilegiados de
    /// sistema completo — isso é a task B4.1). **Sem NEON** (fora do escopo do épico B3,
    /// nenhum `IrOp`/decoder SIMD de 64/128 bits existe). `SDIV`/`UDIV` habilitados (nota
    /// v7VE da decisão nº 5 do épico `b3-armv7a-vfp.md`: nem todo core ARMv7-A tem divisão
    /// inteira em hardware — é uma extensão opcional "virtualization extensions" —, mas
    /// este preset emula um core que a possui, como o Cortex-A15/A7).
    /// Só as FEATURES de {@link #ARMV7A} — existe separadamente pelo mesmo quebra-cabeça do
    /// ovo e da galinha de {@link #ARMV6K_THUMB2_FEATURES}: {@link
    /// dev.vitorsilverio.armjitter.decoder.VfpDecoder}/{@link
    /// dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder} recebem uma {@code
    /// ArmArchitecture} no construtor (para gatear {@link ArmFeature#VFPV2} em tempo de
    /// decode) e {@code ARMV7A} ainda não existe no ponto em que essas extensões
    /// precisam ser construídas.
    private static final ArmArchitecture ARMV7A_FEATURES = extending(ARMV6K_THUMB2, "ARMv7-A",
            ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE,
            ArmFeature.DIVIDE, ArmFeature.VFPV2,
            // B9.6: VFPv4 (VFMA/VFMS/VFNMA/VFNMS) — este preset emula um core que a possui
            // (Cortex-A15/A7, mesma nota já feita para SDIV/UDIV acima); NÃO herdada por
            // ARM11_MPCORE (ver o Javadoc da feature, exclusão cronológica real).
            ArmFeature.VFP_FUSED_MULTIPLY_ACCUMULATE,
            // B9.8.2: HVC (ARMv7 base, qualquer perfil A/R — ver Javadoc da feature). NÃO herdada
            // por ARMV7M (perfil M não tem HVC, confirmado no QEMU real) nem por presets pré-v7.
            ArmFeature.HYPERVISOR_CALL,
            // B22.5: Virtualization Extensions (ERET/MRS_bank/MSR_bank A32 e T32). No ARM real
            // `HVC`, `ERET` e `MRS`/`MSR` (banked) são A MESMA extensão (ARMv7-A Virtualization
            // Extensions) — um core que tem `HVC` (Cortex-A15/A7, citados no Javadoc deste preset)
            // tem os três. Declarar `HYPERVISOR_CALL` sem esta era incoerência arquitetural (as
            // 29 células `ERET`/`MRS_bank`/`MSR_bank` que ficavam `❌` em v7-A). NÃO herdada por
            // ARMV7M (sem Hyp mode / banco por modo) nem por presets pré-v7 (posterior ao ARMv6K).
            ArmFeature.VIRTUALIZATION_EXTENSIONS);

    public static final ArmArchitecture ARMV7A = ARMV7A_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV7A_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARM11 MPCore do 3DS (2 cores compartilhando memória, B5.1 cobre o monitor de exclusividade
    /// entre eles): ARMv6K + VFPv2. **Sem Thumb-2** — o MPCore do 3DS é ARMv6K, não ARMv6T2, então
    /// `THUMB2` fica de fora e `ThumbDecoder` mantém o comportamento legado de par
    /// `LONG_BRANCH_PREFIX`/`LONG_BRANCH_SUFFIX` para `BL`/`BLX` imediato (sem o fechamento de
    /// instrução única da B2.6, que só ativa com a feature). CP15 (MMU/coprocessador de sistema)
    /// não é modelado aqui — fica no bus do hospedeiro, como em qualquer preset (ver B4.1 para MMU).
    /// Só as FEATURES existe separadamente pelo mesmo quebra-cabeça do ovo e da galinha de {@link
    /// #ARMV6K_THUMB2_FEATURES}/{@link #ARMV7A_FEATURES}: {@link
    /// dev.vitorsilverio.armjitter.decoder.VfpDecoder} recebe uma {@code ArmArchitecture} no
    /// construtor (para gatear {@link ArmFeature#VFPV2} em tempo de decode) e {@code
    /// ARM11_MPCORE} ainda não existe no ponto em que a extensão precisa ser construída.
    private static final ArmArchitecture ARM11_MPCORE_FEATURES = extending(ARMV6K, "ARM11-MPCore",
            ArmFeature.VFPV2);

    public static final ArmArchitecture ARM11_MPCORE = ARM11_MPCORE_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARM11_MPCORE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()));

    /// Cortex-M0/M0+/M1 — **ARMv6-M** (B7.4). Perfil M: {@link ArmFeature#M_PROFILE} instala o
    /// {@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel} (MSP/PSP/xPSR/EXC_RETURN,
    /// B7.2/B7.3) — quem cria o {@code ArmCore}/`JitRuntime` para este preset deve passar
    /// {@code new MProfileExceptionModel()} (o preset não força o modelo sozinho, ver B7.2).
    ///
    /// **Armadilha (spec B7.4 nº1): `THUMB2` aqui é SÓ o mecanismo de fetch de 32 bits** — o
    /// ARMv6-M **NÃO** tem o Thumb-2 largo (sem `LDR.W`, sem dataproc de 32 bits). Por isso este
    /// preset propositalmente NÃO pluga `Thumb2DataProcessingDecoder`/`Thumb2LoadStoreDecoder`: os
    /// únicos encodings de 32 bits do v6-M são `BL` (nativo do `ThumbDecoder` pós-B2.6), as
    /// barreiras `DMB`/`DSB`/`ISB` e `MRS`/`MSR` (SYSm) — todos cobertos por `Thumb2MiscDecoder`.
    /// Ninguém deve "consertar" isto plugando os decoders largos depois. `WAIT_HINTS` habilita
    /// `WFI`/`WFE`/`SEV`; `MEMORY_BARRIERS`, as barreiras. Sem `M_FAULT_MASKING`: `BASEPRI`/
    /// `FAULTMASK` e `CPS f` viram UNDEFINED (v6-M só tem `PRIMASK`). `BYTE_REVERSE` (`REV`/
    /// `REV16`/`REVSH`, formas de 16 bits) É real em ARMv6-M — confirmado no `ARMv6-M Architecture
    /// Reference Manual` (ARM DDI 0419C), seção A3.3.3: lista `REV`/`REVSH`/`REV16` como as
    /// instruções de reversão de bytes que a arquitetura fornece (achado de cobertura de ISA,
    /// B9.10 — a task B7.4 original não incluiu esta feature no preset).
    ///
    /// `BLX` (sem `BLX_IMMEDIATE`): o ARMv6-M tem `BLX` **registrador** (`BLX Rm`, ARM DDI 0419C
    /// A6.7.10) mas nunca teve `BLX` imediato (não há troca para ARM state em perfil M). A feature
    /// {@link ArmFeature#BLX} foi separada de {@link ArmFeature#BLX_IMMEDIATE} exatamente para
    /// este preset (B22.3) — declarar `BLX` cru ligaria também o `BLX` imediato T32, que a
    /// arquitetura não possui.
    private static final ArmArchitecture ARMV6M_FEATURES = of("ARMv6-M",
            ArmFeature.THUMB2, ArmFeature.M_PROFILE, ArmFeature.WAIT_HINTS, ArmFeature.MEMORY_BARRIERS,
            ArmFeature.BREAKPOINT, ArmFeature.BYTE_REVERSE, ArmFeature.BLX);

    /// `Thumb2BranchDecoder` (`B.W`/`TBB`/`TBH`) **NÃO** é anexado aqui (achado de cobertura de
    /// ISA, B9.10): o mesmo `ARMv6-M Architecture Reference Manual` (ARM DDI 0419C), seção A3.3.1,
    /// afirma que "ARMv6-M supports the 16-bit Thumb instructions from ARMv7-M, in addition to the
    /// 32-bit BL, DMB, DSB, ISB, MRS and MSR instructions" — uma lista fechada de SEIS encodings de
    /// 32 bits que não inclui `B.W`/`TBB`/`TBH`. A task B7.4 original anexou `Thumb2BranchDecoder`
    /// por engano (ele não aparece na enumeração do javadoc logo acima, que já dizia "BL...
    /// barreiras ... MRS/MSR — todos cobertos por Thumb2MiscDecoder"): sem esta correção, `ARMV6M`
    /// aceitava silenciosamente `B.W`/`TBB`/`TBH`, que a arquitetura real rejeita (G8).
    ///
    /// **B15.2 acrescenta uma SÉTIMA exceção deliberada** à mesma lista fechada:
    /// {@link dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder} (`NOCP`/`NOCP_8_1`).
    /// `target/isa-decode/m-nocp.decode` (QEMU) trata o espaço de coprocessador ausente como
    /// genérico a qualquer core `ARM_FEATURE_M`, sem distinguir v6-M de v7-M — mesma convenção que
    /// `MProfileExceptionModel#enterException(ArmCore, ArmException)` já usa (UNDEFINED→
    /// USAGE_FAULT sem gate de versão), e a mesma que `docs/COBERTURA-ISA.md`/`IsaCoverageReport`
    /// já usam para medir a célula `m-nocp.decode` × `v6-M` (aplicabilidade por
    /// `ArmFeature#M_PROFILE` cru, sem distinguir versão).
    ///
    /// **B9.11** fechou o achado colateral que a B9.10 deixou pendente: dentro do próprio
    /// `Thumb2MiscDecoder` (compartilhado com `ARMV7M`), os hints largos (`NOP.W`/`YIELD.W`/
    /// `WFE.W`/`WFI.W`/`SEV.W`/`ESB`), `CPS.W` e `UDF.W` decodificavam sob `ARMV6M` sem gate — a
    /// mesma categoria de bug do Achado 2 acima, só que dentro de um decoder compartilhado que
    /// gateia alguns mnemônicos por feature e outros não (ver
    /// {@link ArmFeature#M_PROFILE_WIDE_MISC_CONTROL}, presente só em `ARMV7M`). O alias de
    /// exception-return `SUBS PC,LR,#imm`/`ERET` (T5) foi fechado para `M_PROFILE` inteiro (v6-M
    /// E v7-M) — não existe em perfil M nenhum, que usa `EXC_RETURN` via `BX`/`POP`, não `SUBS PC`.
    public static final ArmArchitecture ARMV6M = ARMV6M_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6M_FEATURES),
                    // VMSR_VMRS/VLDR_sysreg/VSTR_sysreg (B15.3): TEM que vir ANTES de
                    // Thumb2NocpDecoder — mesmo bloco QEMU, formas específicas primeiro.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV6M_FEATURES),
                    // VLLDM_VLSTM/VSCCLRM (B15.5): mesmo motivo — TEM que vir ANTES de
                    // Thumb2NocpDecoder (formas específicas do mesmo bloco QEMU).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV6M_FEATURES),
                    // NOCP (B15.2): `docs/COBERTURA-ISA.md` mede `m-nocp.decode` como aplicável a
                    // v6-M também (mesma convenção já usada por UNDEFINED->USAGE_FAULT em
                    // MProfileExceptionModel, que não distingue v6-M/v7-M) — o v6-M real não tem
                    // Thumb-2 largo em geral, mas o espaço de coprocessador ausente é medido igual.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV6M_FEATURES)));

    /// Cortex-M4/M7 (extensão DSP) — **ARMv7-M** (B7.4, features DSP completadas pela B9.16): Thumb-2
    /// largo completo + divide + bitfield + os registradores de mascaramento de falha
    /// ({@link ArmFeature#M_FAULT_MASKING}: `BASEPRI`/`BASEPRI_MAX`/`FAULTMASK` + `CPS f`) + a
    /// extensão DSP inteira (`ArmFeature.PACK_SATURATE`/`PARALLEL_SIMD`/`SIGNED_MULTIPLY_MEDIA`/
    /// `DSP_MULTIPLY`/`UMAAL` — aritmética paralela `SADD16`-família, `SEL`, `PKH`, `SSAT`/`USAT`/
    /// `SSAT16`/`USAT16`, `USAD8`/`USADA8`, `SMLAD`/`SMLSD`/`SMLALD`/`SMLSLD`/`SMMLA`/`SMMLS`,
    /// `SMLA<x><y>`/`SMLAW<y>`/`SMUL<x><y>`/`SMULW<y>`, `UMAAL`) + o resto da base ARMv7-M que já
    /// existia antes de qualquer DSP (`ArmFeature.CLZ`, `LDRD_STRD`, `PRELOAD_HINTS`) — achado de
    /// cobertura de ISA, B9.16: `Thumb2MultiplyDecoder`/`Thumb2DataProcessingDecoder`/
    /// `Thumb2RegisterDataProcessingDecoder` já tinham decode+IR+executor completos para tudo isso
    /// (B1.3/B1.4/B2.7/B3.1/B3.2/B9.1/B9.7, todos POSTERIORES à B7.4 que criou este preset em
    /// 2026-07-23), mas o preset nunca foi atualizado com as features correspondentes — zero decode
    /// novo, só gating. **Sem VFP** (a extensão FP do Cortex-M4F está fora do escopo — "Não inclui"
    /// da B7.4), por isso nenhum `Thumb2VfpDecoder`/`VFPV2` aqui. Herda o perfil M (`M_PROFILE`/
    /// `WAIT_HINTS`/`MEMORY_BARRIERS`) de {@link #ARMV6M_FEATURES} e acrescenta o inteiro largo.
    /// Mesmo quebra-cabeça ovo-e-galinha dos outros presets: as features primeiro
    /// ({@code ARMV7M_FEATURES}), porque `Thumb2*Decoder` recebem a arquitetura no construtor.
    private static final ArmArchitecture ARMV7M_FEATURES = extending(ARMV6M_FEATURES, "ARMv7-M",
            ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE,
            ArmFeature.EXCLUSIVE_WORD, ArmFeature.EXCLUSIVE_SIZED,
            ArmFeature.MOVW_MOVT, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE,
            ArmFeature.MLS_MULTIPLY, ArmFeature.DIVIDE, ArmFeature.SATURATING,
            ArmFeature.M_FAULT_MASKING, ArmFeature.M_PROFILE_WIDE_MISC_CONTROL,
            ArmFeature.CLZ, ArmFeature.LDRD_STRD, ArmFeature.PRELOAD_HINTS,
            ArmFeature.PACK_SATURATE, ArmFeature.PARALLEL_SIMD, ArmFeature.SIGNED_MULTIPLY_MEDIA,
            ArmFeature.DSP_MULTIPLY, ArmFeature.UMAAL);

    public static final ArmArchitecture ARMV7M = ARMV7M_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV7M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV7M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV7M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV7M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV7M_FEATURES),
                    // VMSR_VMRS/VLDR_sysreg/VSTR_sysreg (B15.3): TEM que vir ANTES de
                    // Thumb2NocpDecoder — mesmo bloco QEMU, formas específicas primeiro.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV7M_FEATURES),
                    // VLLDM_VLSTM/VSCCLRM (B15.5): TEM que vir ANTES de Thumb2NocpDecoder.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV7M_FEATURES),
                    // NOCP (B15.2): SUBSTITUI Thumb2CoprocessorDecoder — perfil M não tem MCR/MRC
                    // de coprocessador genérico de verdade, ver Javadoc de Thumb2NocpDecoder
                    // (Armadilha 1 da spec).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV7M_FEATURES)));

    /// Cortex-M3/SC300 — **ARMv7-M puro, sem a extensão DSP** (B15.1). `ARMV7M` acima já inclui
    /// DSP inteira (`PACK_SATURATE`/`PARALLEL_SIMD`/`SIGNED_MULTIPLY_MEDIA`/`DSP_MULTIPLY`/`UMAAL`)
    /// desde a B9.16 — ou seja, o que se chama `ARMV7M` hoje é, na nomenclatura real do manual ARM,
    /// um **ARMv7E-M** (ver {@link #ARMV7EM}), não um ARMv7-M puro. A B12.4 (catálogo de
    /// processadores) encontrou essa lacuna ao tentar catalogar o Cortex-M3 (sem DSP): mapeá-lo
    /// para `ARMV7M` seria uma entrada factualmente errada, não uma aproximação conservadora.
    /// **G3**: `ARMV7M` permanece intocado (nome e comportamento) — este preset nasce AO LADO,
    /// nunca o substitui.
    private static final ArmArchitecture ARMV7M_PURE_FEATURES = extending(ARMV6M_FEATURES, "ARMv7-M (sem DSP)",
            ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE,
            ArmFeature.EXCLUSIVE_WORD, ArmFeature.EXCLUSIVE_SIZED,
            ArmFeature.MOVW_MOVT, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE,
            ArmFeature.MLS_MULTIPLY, ArmFeature.DIVIDE, ArmFeature.SATURATING,
            ArmFeature.M_FAULT_MASKING, ArmFeature.M_PROFILE_WIDE_MISC_CONTROL,
            ArmFeature.CLZ, ArmFeature.LDRD_STRD, ArmFeature.PRELOAD_HINTS);

    public static final ArmArchitecture ARMV7M_PURE = ARMV7M_PURE_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV7M_PURE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV7M_PURE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV7M_PURE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV7M_PURE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV7M_PURE_FEATURES),
                    // VMSR_VMRS/VLDR_sysreg/VSTR_sysreg (B15.3): TEM que vir ANTES de
                    // Thumb2NocpDecoder — mesmo bloco QEMU, formas específicas primeiro.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV7M_PURE_FEATURES),
                    // VLLDM_VLSTM/VSCCLRM (B15.5): TEM que vir ANTES de Thumb2NocpDecoder.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV7M_PURE_FEATURES),
                    // NOCP (B15.2): mesma substituição de ARMV7M acima.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV7M_PURE_FEATURES)));

    /// Cortex-M4/M7 — nome arquiteturalmente correto do preset `ARMV7M` acima (que já é, de fato,
    /// um ARMv7E-M desde a B9.16, ver Javadoc de {@link #ARMV7M_PURE}). Alias por IDENTIDADE (não
    /// uma cópia reconstruída): existe só para o catálogo de processadores (B15.7) poder nomear
    /// Cortex-M4/M7 sem reaproveitar o nome ambíguo `ARMV7M`, sem duplicar objeto nem manutenção.
    public static final ArmArchitecture ARMV7EM = ARMV7M;

    /// ARMv8-M Baseline (B15.4) — `ARMV7M_PURE` (sem DSP) + {@link ArmFeature#M_PROFILE_SECURITY}
    /// (`SG`/`BXNS`/`BLXNS`, banking de `MSP`/`PSP` por estado de segurança). **G3**: `ARMV7M_PURE`
    /// permanece intocado — este preset nasce AO LADO.
    private static final ArmArchitecture ARMV8M_BASELINE_FEATURES = extending(ARMV7M_PURE_FEATURES,
            "ARMv8-M Baseline (Security Extension)", ArmFeature.M_PROFILE_SECURITY, ArmFeature.HALT);

    public static final ArmArchitecture ARMV8M_BASELINE = ARMV8M_BASELINE_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV8M_BASELINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV8M_BASELINE_FEATURES)));

    /// ARMv8-M Mainline (B15.4) — `ARMV7M` (com DSP, ou seja ARMv7E-M na nomenclatura real, ver
    /// {@link #ARMV7M_PURE}) + {@link ArmFeature#M_PROFILE_SECURITY}. **G3**: `ARMV7M` permanece
    /// intocado.
    private static final ArmArchitecture ARMV8M_MAINLINE_FEATURES = extending(ARMV7M_FEATURES,
            "ARMv8-M Mainline (Security Extension)", ArmFeature.M_PROFILE_SECURITY, ArmFeature.HALT);

    public static final ArmArchitecture ARMV8M_MAINLINE = ARMV8M_MAINLINE_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV8M_MAINLINE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV8M_MAINLINE_FEATURES)));

    /// ARMv8.1-M (B15.6) — `ARMV8M_MAINLINE` + {@link ArmFeature#LOW_OVERHEAD_BRANCH} (`DLS`/`WLS`/
    /// `LE`, formas puras). **G3**: `ARMV8M_MAINLINE` permanece intocado — este preset nasce AO
    /// LADO. **Zero célula nova em `docs/COBERTURA-ISA.md`** (mesmo precedente da `ARMV7M_PURE`/
    /// `ARMV7EM` na B15.1): o preset ainda não entra no mapa `ARM_ARCHITECTURES` de
    /// `IsaCoverageReport` — fica para a B15.7 (fechamento do catálogo Cortex-M) decidir a
    /// curadoria de coluna nova.
    private static final ArmArchitecture ARMV8_1M_FEATURES = extending(ARMV8M_MAINLINE_FEATURES,
            "ARMv8.1-M (Low Overhead Branch)", ArmFeature.LOW_OVERHEAD_BRANCH);

    public static final ArmArchitecture ARMV8_1M = ARMV8_1M_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LowOverheadBranchDecoder(ARMV8_1M_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV8_1M_FEATURES)));

    /// `ARMV8_1M` + {@link ArmFeature#MVE_INTEGER}/{@link ArmFeature#MVE_FLOAT} (B16.1) —
    /// fundação de estado do Helium (MVE): banco `Q0`-`Q7` (gate sobre `VfpRegisters`, B13.1) +
    /// `VPR` (predicação). **Sem decode**: MESMA lista de extensões de decoder que `ARMV8_1M`,
    /// nenhuma extensão MVE ainda — `docs/COBERTURA-ISA.md` continua byte a byte idêntica (o
    /// grupo `mve.decode` só sai de `NOT_IN_ANY_PRESET` na B16.14, que também é quem decide a
    /// curadoria de coluna nova em `IsaCoverageReport`, mesmo precedente de `ARMV8_1M`/B15.6).
    /// **G3**: `ARMV8_1M` permanece intocado — este preset nasce AO LADO.
    private static final ArmArchitecture ARMV8_1M_MVE_FEATURES = extending(ARMV8_1M,
            "ARMv8.1-M+MVE (Helium)", ArmFeature.MVE_INTEGER, ArmFeature.MVE_FLOAT);

    public static final ArmArchitecture ARMV8_1M_MVE = ARMV8_1M_MVE_FEATURES
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpSystemAccessDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VlldmVlstmVscclrmDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LowOverheadBranchDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MvePredicationDecoder (B16.2): VPST/VPNOT/VPSEL vivem no MESMO espaço
                    // de bits que Thumb2NocpDecoder reivindica (forma 1) — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MvePredicationDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveLoadStoreDecoder (B16.3): VLDR_VSTR vive no MESMO espaço de bits que
                    // Thumb2NocpDecoder reivindica (forma 2) — TEM que vir antes (Armadilha 1).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveLoadStoreDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveWideningLoadStoreDecoder (B16.4): VLDSTB_H/VLDSTB_W/VLDSTH_W vivem no
                    // MESMO espaço de bits (`111.110x`) — TEM que vir antes (Armadilha 3 da task).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveWideningLoadStoreDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveGatherScatterDecoder/Thumb2MveInterleavedLoadStoreDecoder/
                    // Thumb2MveIncrementDupDecoder (B16.5): mesmo espaço de bits de forma 2/genérico
                    // que Thumb2NocpDecoder reivindica — TEM que vir antes (Armadilha 3 da task).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveGatherScatterDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveInterleavedLoadStoreDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveIncrementDupDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveVector2opDecoder (B16.6): a seção "Vector 2-op" inteira vive no
                    // MESMO espaço `bits[27:25]=111` que Thumb2NocpDecoder reivindica — TEM que vir
                    // antes (Armadilha 1 da task).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveVector2opDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveVectorOverlapDecoder (B16.7): os quatro blocos {} sobrepostos
                    // (VCVTB/T_SH/HS, VMAXNMA/VMINNMA, VSHLL T2, VQMOVUNB/T, VQMOVN_*, VMOVNB/T,
                    // VMAXA/VMINA, VMULH/VRMULH) vivem no MESMO espaço de bits `111.1110...1` que
                    // Thumb2NocpDecoder reivindica — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveVectorOverlapDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveComplexDualAccumulateDecoder (B16.7 sub-família 2): VCMUL*/VQDMLADH*/
                    // VQDMLSDH*/VQDMULL* vivem no MESMO espaço de bits `111.1110...` que
                    // Thumb2NocpDecoder reivindica — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveComplexDualAccumulateDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveVector2opFpDecoder (B16.7 sub-família 3): VADD_fp/VSUB_fp/VMUL_fp/
                    // VABD_fp/VMAXNM/VMINNM/VFMA/VFMS/VCADD90_fp/VCADD270_fp/VCMLA0/90/180/270 vivem
                    // no MESMO espaço de bits `111.1110...`/`111.1111...` que Thumb2NocpDecoder
                    // reivindica — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveVector2opFpDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveComparisonDecoder (B16.8): VCMP*/VCMP*_fp/VCMP*_scalar/
                    // VCMP*_fp_scalar vivem no MESMO espaço de bits `111.1110...` que
                    // Thumb2NocpDecoder reivindica — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveComparisonDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveVectorScalarDecoder (B16.9): as 35 formas escalares (vetor × GPR
                    // broadcast) vivem no MESMO espaço de bits `111.1110...` que Thumb2NocpDecoder
                    // reivindica — TEM que vir antes. Registrado DEPOIS de
                    // Thumb2MveComparisonDecoder (pendência da B16.8: nenhum ajuste de ordem
                    // necessário, os guardas de size/gate de cada decoder já resolvem a colisão).
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveVectorScalarDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveShiftImmediateDecoder (B16.10): deslocamentos por imediato + VSHLL T1
                    // vivem no MESMO espaço de bits `111.1110.../111.1111...` que Thumb2NocpDecoder
                    // reivindica — TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveShiftImmediateDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveNarrowingShiftDecoder (B16.11): deslocamentos estreitantes (só b/h) +
                    // VSHLC vivem no MESMO espaço de bits `111.1110...` (bit21=0/1 respectivamente)
                    // que Thumb2NocpDecoder reivindica — TEM que vir antes. Disjunto de
                    // Thumb2MveShiftImmediateDecoder por bit21/bit7 (ver Javadoc do decoder), ordem
                    // relativa entre os dois não importa.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveNarrowingShiftDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveFpConvertDecoder (B16.12): VCVT/VRINT (int<->fp, ponto fixo, modo de
                    // arredondamento) vivem no MESMO espaço de bits `111.1111...` que
                    // Thumb2NocpDecoder reivindica — TEM que vir antes. Disjunto de
                    // Thumb2MveShiftImmediateDecoder/Thumb2MveNarrowingShiftDecoder por bit4 (ver
                    // Javadoc do decoder), ordem relativa entre os três não importa.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveFpConvertDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveMoveLanesGprDecoder (B16.13a): VMOV_to_2gp/VMOV_from_2gp vivem no
                    // MESMO espaço de bits `1110_1100_0...` (extension-register load/store do VFP,
                    // que Thumb2NocpDecoder reivindica via `bits[27:24]=1110` sob M_PROFILE) — TEM
                    // que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveMoveLanesGprDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveVectorMiscDecoder (B16.13a): 1-op misc (VCLS/VCLZ/VREV*/VMVN/VABS/
                    // VNEG/VQABS/VQNEG), VABS_fp/VNEG_fp e VDUP vivem nos MESMOS espaços de bits
                    // `111.1111...`/`1110_1110...` que Thumb2NocpDecoder reivindica — TEM que vir
                    // antes. Disjunto de Thumb2MveFpConvertDecoder por bits[17:16] (ver Javadoc do
                    // decoder), ordem relativa entre os dois não importa.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveVectorMiscDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveReduceDecoder (B16.13a): VADDV/VADDLV/VABAV/Vimm_1r vivem no MESMO
                    // espaço de bits `111.1110.../111.1111...` que Thumb2NocpDecoder reivindica —
                    // TEM que vir antes.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveReduceDecoder(ARMV8_1M_MVE_FEATURES),
                    // Thumb2MveDualAccumulateDecoder (B16.13b): VMLADAV/VMLSDAV/VMLALDAV/VMLSLDAV/
                    // VRMLALDAVH/VRMLSLDAVH/VMAXV/VMINV/VMAXAV/VMINAV/VMAXNMV/VMINNMV/VMAXNMAV/
                    // VMINNMAV vivem no MESMO espaço de bits `111.1110...` que Thumb2NocpDecoder
                    // reivindica — TEM que vir antes. Verificado (não presumido) sem colisão com
                    // Thumb2MveReduceDecoder — ordem relativa entre os dois não importa.
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MveDualAccumulateDecoder(ARMV8_1M_MVE_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NocpDecoder(ARMV8_1M_MVE_FEATURES)));

    /// ARMv8-A executando em AArch32 (B14.1) — o modo que `Cortex-A32`/o lado 32-bit dos
    /// `Cortex-A5x`/`A7x` usam. `ARMV7A` + {@link ArmFeature#ARMV8_FP} (`VSEL`/`VMAXNM`/`VMINNM`/
    /// `VRINT{A,N,P,M}`/`VCVT{A,N,P,M}`, B14.4/B14.5) + {@link ArmFeature#LOAD_ACQUIRE_STORE_RELEASE}
    /// (`LDA`/`STL` e variantes, B14.2) + {@link ArmFeature#CRC32} (B14.3) + {@link ArmFeature#HALT}
    /// (`HLT`, já decodificado desde B22.1 — este é o primeiro preset a declarar a feature, então
    /// `HLT` passa a decodificar como {@link dev.vitorsilverio.armjitter.decoder.InstructionKind#HALT}
    /// em vez de cair no fallthrough genérico, ver Javadoc de {@link ArmFeature#HALT}) + {@link
    /// ArmFeature#FP16_ARITHMETIC} (`VMOVX`/`VINS`, B14.6). **Sem decode novo** nesta task além da
    /// diferença de `HLT` já citada — `LDA`/`STL`/`CRC32`/`VSEL`/`VMAXNM`/`VMINNM`/`VRINT`/`VCVT`/
    /// `VMOVX`/`VINS` ficam para B14.2-B14.6. **G3**: `ARMV7A` permanece
    /// intocado — este preset nasce AO LADO, com a MESMA lista de extensões de decoder do
    /// `ARMV7A`, reparametrizada com {@code ARMV8A_32_FEATURES} (senão as features novas ficam
    /// invisíveis em tempo de decode, o bug que a B4.0.3 caçou no armbox — ver Javadoc de
    /// {@link #ARMV7A}). **Zero célula nova em `docs/COBERTURA-ISA.md`** (mesmo precedente de
    /// {@link #ARMV7M_PURE}/B15.1 e {@link #ARMV8_1M}/B15.6): o preset ainda não entra no mapa
    /// `ARM_ARCHITECTURES` de `IsaCoverageReport` — fica para a B14.7 (fechamento do épico).
    private static final ArmArchitecture ARMV8A_32_FEATURES = extending(ARMV7A, "ARMv8-A (AArch32)",
            ArmFeature.ARMV8_FP, ArmFeature.LOAD_ACQUIRE_STORE_RELEASE, ArmFeature.CRC32, ArmFeature.HALT,
            ArmFeature.FP16_ARITHMETIC);

    public static final ArmArchitecture ARMV8A_32 = ARMV8A_32_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv8.6-A executando em AArch32 (B22.7) — {@link #ARMV8A_32} + {@link ArmFeature#JAVASCRIPT_CONVERT}
    /// (`VJCVT`, `FEAT_JSCVT`, ARMv8.3-A) + {@link ArmFeature#BFLOAT16} (`VCVTB`/`VCVTT.BF16.F32`,
    /// `FEAT_BF16`, ARMv8.6-A). **Preset CUMULATIVO** (mesma convenção das colunas A64: a versão N
    /// contém tudo das anteriores) que fecha o que a coluna `v8-A/32` (ARMv8.0) não pode medir sem
    /// violar a versão real de introdução. **Sem NEON**: `ADVANCED_SIMD` fica de fora (os grupos
    /// `neon-*` seguem `·` nesta coluna, exatamente como em `ARMV8A_32`). **G3**: `ARMV8A_32`
    /// permanece intocado — este preset nasce AO LADO, com as extensões de decoder parametrizadas
    /// pelas features NOVAS (senão elas ficam invisíveis em tempo de decode, o bug da B4.0.3).
    private static final ArmArchitecture ARMV8_6A_32_FEATURES = extending(ARMV8A_32_FEATURES,
            "ARMv8.6-A (AArch32)", ArmFeature.JAVASCRIPT_CONVERT, ArmFeature.BFLOAT16);

    public static final ArmArchitecture ARMV8_6A_32 = ARMV8_6A_32_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8_6A_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv7-A **com NEON/Advanced SIMD** (B13.22, fecha o épico B13) — `ARMV7A` +
    /// {@link ArmFeature#ADVANCED_SIMD} + {@link ArmFeature#VFPV3_D32} (banco `D0`-`D31`/`Q0`-`Q15`
    /// completo). **NEON é OPCIONAL no ARMv7-A real** (ARM DDI 0406C, "Advanced SIMD (NEON)
    /// Extension" é uma opção de implementação) — por isso este preset nasce AO LADO de
    /// {@link #ARMV7A} (G3: `ARMV7A` permanece intocado, decodifica NEON como `UNIMPLEMENTED` para
    /// sempre), nunca o substitui.
    ///
    /// **As 7 features irmãs do épico (`ADVANCED_SIMD_RDM`/`CRYPTO`/
    /// `COMPLEX_NUMBER_ARITHMETIC`/`DOT_PRODUCT`/`INT8_MATRIX_MULTIPLY`/
    /// `FP16_FUSED_MULTIPLY_ADD_LONG`/`BFLOAT16`) NÃO entram aqui** — são todas extensões de versões
    /// ARMv8.x (`FEAT_RDM`=v8.1, cripto AES/SHA=v8.0 "Cryptographic Extension" opcional só a partir
    /// de núcleos ARMv8, `FEAT_FCMA`/`FEAT_DotProd`/`FEAT_FHM`=v8.2, `FEAT_I8MM`/`FEAT_BF16`=v8.6) —
    /// nenhum Cortex-A5/A7/A8/A9/A12/A15/A17 real (ARMv7-A puro) as tem. Declará-las aqui seria
    /// entrada factualmente errada (mesmo princípio de B12.4/B12.6); ficam para um preset ARMv8-A
    /// AArch32 COM NEON, ainda não modelado (candidata a task futura, composta sobre
    /// {@link #ARMV8A_32} do mesmo jeito que este preset compõe sobre {@link #ARMV7A}). O efeito
    /// prático (curadoria de versão em `IsaCoverageReport`, `ARM32_VERSION_REQUIREMENTS`): as
    /// linhas dessas 7 features medem `·` (não aplicável) nesta coluna, nunca `❌`.
    ///
    /// **Extensões de decoder**: os 7 decoders A32 do épico B13
    /// (`NeonDataProcessingDecoder`/`NeonShiftImmediateDecoder`/`NeonModifiedImmediateDecoder`/
    /// `NeonThreeRegDifferentDecoder`/`NeonTwoRegMiscDecoder`/`NeonExtractTableDuplicateDecoder`/
    /// `NeonLoadStoreDecoder`) mais `NeonSharedDecoder` (B13.17-B13.21), ANTES de
    /// `CoprocessorDecoder` (mesma disciplina de {@link #ARMV7A}: espaço específico antes do
    /// fallback genérico cp10/11). No lado Thumb-2: `Thumb2NeonDecoder` (B13.16, transforma e
    /// delega `neon-dp`/`neon-ls`) mais `Thumb2NeonSharedDecoder` (B13.22, `neon-shared` — encoding
    /// idêntico A32/T32, só relabela {@link InstructionSet}), ambos ANTES de
    /// `Thumb2CoprocessorDecoder`.
    private static final ArmArchitecture ARMV7A_NEON_FEATURES = extending(ARMV7A, "ARMv7-A+NEON",
            ArmFeature.ADVANCED_SIMD, ArmFeature.VFPV3_D32);

    public static final ArmArchitecture ARMV7A_NEON = ARMV7A_NEON_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.VfpDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonDataProcessingDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonShiftImmediateDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonModifiedImmediateDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonThreeRegDifferentDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonTwoRegMiscDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonExtractTableDuplicateDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.NeonLoadStoreDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder(),
                    // NeonSharedDecoder TEM que vir POR ÚLTIMO: desde a B13.21 ele nunca devolve
                    // `null` (fecha o espaço com `unimplemented(...)` explícito, G8) — se algum
                    // decoder DEPOIS dele nunca fosse consultado, um preset sem as 5 features de
                    // `neon-shared` (como este) faria QUALQUER encoding do espaço incondicional que
                    // sobrasse (ex.: `MCR`/`MRC`/`CDP`/`STC`/`LDC`, que `CoprocessorDecoder` reivindica
                    // logo acima) virar `UNIMPLEMENTED` em vez de chegar até ele — achado real (não
                    // hipotético) confirmado por probe direto contra `0xEE010F10` (`MCR p15,0,r0,c1,c0,0`)
                    // durante a revisão desta task: `ARMV7A` decodifica `COPROCESSOR`, a primeira
                    // versão desta lista (`NeonSharedDecoder` antes de `CoprocessorDecoder`)
                    // decodificava `UNIMPLEMENTED`.
                    new dev.vitorsilverio.armjitter.decoder.NeonSharedDecoder(ARMV7A_NEON_FEATURES)))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2VfpDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NeonDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV7A_NEON_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder(),
                    // Thumb2NeonSharedDecoder TEM que vir POR ÚLTIMO — mesmo motivo do lado A32
                    // acima (delega a NeonSharedDecoder, herda o mesmo comportamento "nunca null").
                    new dev.vitorsilverio.armjitter.decoder.Thumb2NeonSharedDecoder(ARMV7A_NEON_FEATURES)));

    /// ARMv7-R **user-level** (B20.1) — primeiro degrau do perfil R (Cortex-R4/R5/R7/R8, catálogo na
    /// B20.6). O conjunto de instruções é o de {@link #ARMV7A} MENOS as extensões que o perfil R real
    /// não tem: **sem** {@link ArmFeature#HYPERVISOR_CALL}/{@link ArmFeature#VIRTUALIZATION_EXTENSIONS}
    /// (não há Hyp mode em ARMv7-R) nem {@link ArmFeature#SECURE_MONITOR_CALL} (sem Security
    /// Extensions/Monitor mode). **`extending(ARMV7A, ...)` não serve aqui** — só SOMA features, e
    /// {@code ARMV7A_FEATURES} já declara as três acima; por isso este preset nasce de {@link #of},
    /// lista positiva, mesmo precedente de {@link #ARMV7M_PURE} (B15.1: "não dá para subtrair via
    /// `extending`"). `DIVIDE` (`SDIV`/`UDIV`) já decodifica sob qualquer preset que a declare — é um
    /// carve-out direto em {@link dev.vitorsilverio.armjitter.decoder.ArmDecoder} gateado só por
    /// `architecture.has(ArmFeature.DIVIDE)`, sem checagem de perfil — então basta declarar a feature
    /// aqui (confirmado lendo o carve-out antes de escrever esta lista, achado 2 da spec B20.1).
    ///
    /// **Sem VFP** ({@link ArmFeature#VFPV2} de fora): os Cortex-R4/R5/R7/R8 têm variantes "sem F"
    /// reais (sem FP nenhum) — este preset nasce como o núcleo mínimo, mesma disciplina de
    /// {@link #ARMV6}/{@link #ARMV6T2}/{@link #ARMV6Z} (B12.5): variantes "(F)"/"F" ficam de fora,
    /// candidatas a uma sub-task futura que componha VFP sobre este preset. **Sem NEON.**
    ///
    /// {@link ArmFeature#R_PROFILE}/{@link ArmFeature#PMSA} não têm consumidor ainda — só a ausência
    /// das três features de virtualização/segurança acima é observável hoje. MPU
    /// ({@link dev.vitorsilverio.armjitter.memory.mpu.PmsaAddressSpace}, B20.2/B20.3) e TCM (B20.4)
    /// já existem; até lá este preset roda contra um {@code AddressSpace} plano, como qualquer
    /// preset sem MMU/MPU.
    ///
    /// **Modelo de exceção (B20.5): este preset NÃO instala nada sozinho** — o mesmo precedente de
    /// {@link #ARMV6M}/{@link dev.vitorsilverio.armjitter.core.MProfileExceptionModel} (B7.2). O
    /// {@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel} default de todo
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore} JÁ é quase correto para `ARMV7R` (mesmos
    /// modos/vetores/SPSR do perfil A), mas aceitaria `HVC`/`SMC` diretos por
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore#requestException} (o decode já os recusa,
    /// B20.1). Quem cria o `ArmCore` para `ARMV7R` deve instalar
    /// `new AProfileExceptionModel(false)` via
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore#setExceptionModel} para fechar esse caminho.
    ///
    /// **Este preset ainda não entra no mapa `ARM_ARCHITECTURES` de `IsaCoverageReport`** — a coluna
    /// `v7-R` nasce na B20.6, junto do catálogo de Cortex-R (mesmo precedente de
    /// {@link #ARMV7M_PURE}/B15.1 e {@link #ARMV8A_32}/B14.1: zero-diff em
    /// `docs/COBERTURA-ISA.md`).
    private static final ArmArchitecture ARMV7R_FEATURES = of("ArmV7-R",
            // ARMv5TE
            ArmFeature.BLX, ArmFeature.BLX_IMMEDIATE, ArmFeature.CLZ, ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING, ArmFeature.LDRD_STRD, ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY, ArmFeature.LDM_WRITEBACK_BASE_IN_LIST,
            ArmFeature.EMPTY_RLIST_NO_TRANSFER, ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL,
            ArmFeature.BREAKPOINT, ArmFeature.PRELOAD_HINTS,
            // ARMv6K (menos SECURE_MONITOR_CALL)
            ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE, ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD, ArmFeature.PACK_SATURATE, ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED, ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA, ArmFeature.WAIT_HINTS, ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.SIGNED_MULTIPLY_MEDIA,
            // ARMv6K+Thumb2
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS, ArmFeature.MOVW_MOVT,
            // ARMv7-A "inteiro v7" (menos VFPV2/VFP_FUSED_MULTIPLY_ACCUMULATE/HYPERVISOR_CALL/
            // VIRTUALIZATION_EXTENSIONS)
            ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE, ArmFeature.DIVIDE,
            // Perfil R
            ArmFeature.R_PROFILE, ArmFeature.PMSA);

    public static final ArmArchitecture ARMV7R = ARMV7R_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV7R_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV7R_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV7R_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV7R_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV7R_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    /// ARMv8-R **AArch32** (B20.7) — o salto de arquitetura do perfil R (`Cortex-R52`/`R52+`,
    /// catálogo em {@link dev.vitorsilverio.armjitter.arch.ArmProcessor#CORTEX_R52}): PMSAv8-32
    /// (base+limite, {@link dev.vitorsilverio.armjitter.memory.mpu.Pmsav8AddressSpace}) e EL2
    /// OBRIGATÓRIO, inverso da {@link #ARMV7R} (que nunca tem Hyp mode).
    ///
    /// **Lista positiva via {@link #of}, nunca `extending({@link #ARMV7R}, ...)`** (Armadilha/Não
    /// fazer da B20.7): embora `extending` só somasse aqui (`ARMV7R` já não tem
    /// {@link ArmFeature#SECURE_MONITOR_CALL}, então não haveria nada a "subtrair"), a lista
    /// positiva evita o risco de esquecer uma das features de {@link #ARMV8A_32} (B14.1) que o
    /// conjunto de instruções de ARMv8-R exige de verdade (Achado 3 da B20.7: o perfil R a partir
    /// do ARMv8 executa o A32/T32 **de ARMv8-A**, `LDA`/`STL`/`CRC32` inclusos — a MESMA razão que
    /// fez a B12.6 recusar catalogar `Cortex-A32` sem elas).
    ///
    /// **Mesma base ARMv4T-ARMv7 "inteiro" do {@link #ARMV7R}** (repetida aqui por ser lista
    /// positiva, não por terem divergido) **+ `LOAD_ACQUIRE_STORE_RELEASE`/`CRC32`/`HALT`** (as 3
    /// das 5 features de {@link #ARMV8A_32} que NÃO dependem de um banco VFP existir) **+
    /// `HYPERVISOR_CALL`/`VIRTUALIZATION_EXTENSIONS`** (EL2 existe de verdade em ARMv8-R — ao
    /// contrário do `ARMV7R`, `HVC` decodifica e executa aqui) — **sem** `SECURE_MONITOR_CALL`
    /// (ARMv8-R AArch32 não tem estado seguro/EL3, confirmado contra o manual: nenhum `SCR`/Monitor
    /// mode citado no ARM DDI 0568A.c). **Sem VFP, e por isso sem `ARMV8_FP`/`FP16_ARITHMETIC`
    /// também** (achado real desta rodada, pego pelo guard `IsaCoverageReportV8A32ColumnTest`: as
    /// outras 2 das 5 features de `ARMV8A_32` — `VSEL`/`VMAXNM`/`VMINNM`/`VRINT`/`VCVT`
    /// incondicionais e `VMOVX`/`VINS`/conversões FP16 — operam sobre registradores VFP que só
    /// existem com `VFPV2`; declará-las sem o banco de registradores seria uma composição
    /// inconsistente, pior que a ausência. Mesma decisão do `ARMV7R`: variantes "F" (com FPU)
    /// ficam de fora, candidatas a task futura que componha VFP sobre este preset — quando isso
    /// acontecer, `ARMV8_FP`/`FP16_ARITHMETIC` entram junto de `VFPV2`, nunca sozinhas.
    ///
    /// **Achado/limitação registrada (Armadilha 6 da B20.7)**: o modelo de exceção
    /// ({@link dev.vitorsilverio.armjitter.core.AProfileExceptionModel#AProfileExceptionModel(boolean)})
    /// usa UM booleano cobrindo HVC **e** SMC juntos para o caminho de
    /// {@link dev.vitorsilverio.armjitter.core.ArmCore#requestException} direto (bypass de decode)
    /// — não há como expressar "HVC sim, SMC não" nesse booleano. O caminho de DECODE normal está
    /// correto (`ArmDecoder`/`Thumb2MiscDecoder` checam `HYPERVISOR_CALL` e `SECURE_MONITOR_CALL`
    /// separadamente, confirmado lendo o código antes de escrever este preset — `SMC` decodifica
    /// `UNDEFINED` sob este preset, `HVC` decodifica e executa). Quem constrói o `ArmCore` para
    /// `ARMV8R_32` deve instalar `new AProfileExceptionModel(true)` (Hyp disponível) — o resíduo
    /// (um host que chame `core.requestException(ArmException.SMC)` diretamente, sem passar pelo
    /// decoder, seria indevidamente aceito) fica nomeado aqui, não resolvido: consertar exigiria
    /// separar o booleano de `AProfileExceptionModel` em dois, mudança maior que o orçamento desta
    /// task, candidata a task futura.
    ///
    /// **Coluna `v8-R` já entra em `ARM_ARCHITECTURES` de `IsaCoverageReport` nesta mesma task**
    /// (ao contrário do `ARMV7R`/B20.6, que teve uma B20.6 própria de fechamento — aqui a medição
    /// zero-diff nas 12 colunas antigas cabe no orçamento junto do preset).
    private static final ArmArchitecture ARMV8R_32_FEATURES = of("ArmV8-R (AArch32)",
            // ARMv5TE
            ArmFeature.BLX, ArmFeature.BLX_IMMEDIATE, ArmFeature.CLZ, ArmFeature.DSP_MULTIPLY,
            ArmFeature.SATURATING, ArmFeature.LDRD_STRD, ArmFeature.LOAD_PC_INTERWORKING,
            ArmFeature.MUL_PRESERVES_CARRY, ArmFeature.LDM_WRITEBACK_BASE_IN_LIST,
            ArmFeature.EMPTY_RLIST_NO_TRANSFER, ArmFeature.STM_BASE_IN_LIST_STORES_ORIGINAL,
            ArmFeature.BREAKPOINT, ArmFeature.PRELOAD_HINTS,
            // ARMv6K (menos SECURE_MONITOR_CALL)
            ArmFeature.EXTEND_ROTATE, ArmFeature.BYTE_REVERSE, ArmFeature.UMAAL,
            ArmFeature.PARALLEL_SIMD, ArmFeature.PACK_SATURATE, ArmFeature.EXCLUSIVE_WORD,
            ArmFeature.EXCLUSIVE_SIZED, ArmFeature.MODE_CHANGE_INSTRUCTIONS,
            ArmFeature.SETEND_BIG_ENDIAN_DATA, ArmFeature.WAIT_HINTS, ArmFeature.UNALIGNED_ACCESS,
            ArmFeature.SIGNED_MULTIPLY_MEDIA,
            // ARMv6K+Thumb2
            ArmFeature.THUMB2, ArmFeature.MEMORY_BARRIERS, ArmFeature.MOVW_MOVT,
            // ARMv7-A "inteiro v7" (menos VFPV2/VFP_FUSED_MULTIPLY_ACCUMULATE)
            ArmFeature.MLS_MULTIPLY, ArmFeature.BIT_FIELD, ArmFeature.BIT_REVERSE, ArmFeature.DIVIDE,
            // ARMv8-A de 32 bits (B14.1-B14.6) que NÃO dependem de VFP existir, obrigatórias no
            // conjunto de instruções ARMv8-R (ARMV8_FP/FP16_ARITHMETIC ficam de fora — ver Javadoc)
            ArmFeature.LOAD_ACQUIRE_STORE_RELEASE, ArmFeature.CRC32, ArmFeature.HALT,
            // EL2 obrigatório em ARMv8-R (inverso do ARMV7R) — sem SECURE_MONITOR_CALL (sem EL3)
            ArmFeature.HYPERVISOR_CALL, ArmFeature.VIRTUALIZATION_EXTENSIONS,
            // Perfil R
            ArmFeature.R_PROFILE, ArmFeature.PMSA);

    public static final ArmArchitecture ARMV8R_32 = ARMV8R_32_FEATURES
            .withDecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.CoprocessorDecoder()))
            .withThumb32DecoderExtensions(List.of(
                    new dev.vitorsilverio.armjitter.decoder.Thumb2DataProcessingDecoder(ARMV8R_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2RegisterDataProcessingDecoder(ARMV8R_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MultiplyDecoder(ARMV8R_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2LoadStoreDecoder(ARMV8R_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2BranchDecoder(),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV8R_32_FEATURES),
                    new dev.vitorsilverio.armjitter.decoder.Thumb2CoprocessorDecoder()));

    private final String name;
    private final EnumSet<ArmFeature> features;
    private final List<DecoderExtension> decoderExtensions;
    private final List<DecoderExtension> thumb32DecoderExtensions;

    private ArmArchitecture(String name, EnumSet<ArmFeature> features, List<DecoderExtension> decoderExtensions,
            List<DecoderExtension> thumb32DecoderExtensions) {
        this.name = Objects.requireNonNull(name, "name");
        this.features = features.clone();
        this.decoderExtensions = List.copyOf(decoderExtensions);
        this.thumb32DecoderExtensions = List.copyOf(thumb32DecoderExtensions);
    }

    /// Constrói uma arquitetura a partir de um nome e das features que ela suporta.
    public static ArmArchitecture of(String name, ArmFeature... features) {
        EnumSet<ArmFeature> set = EnumSet.noneOf(ArmFeature.class);
        Collections.addAll(set, features);
        return new ArmArchitecture(name, set, List.of(), List.of());
    }

    /// Constrói uma arquitetura que estende uma base: herda todas as features **e** as extensões
    /// de decoder (ARM e Thumb-2) da base, acrescentando as features extras. É como versões novas
    /// compõem sobre as anteriores (ex. ARMv6K sobre ARMv5TE) sem repetir a lista da base.
    public static ArmArchitecture extending(ArmArchitecture base, String name, ArmFeature... extraFeatures) {
        EnumSet<ArmFeature> set = base.features.clone();
        Collections.addAll(set, extraFeatures);
        return new ArmArchitecture(name, set, base.decoderExtensions, base.thumb32DecoderExtensions);
    }

    public boolean has(ArmFeature feature) {
        return features.contains(feature);
    }

    public List<DecoderExtension> decoderExtensions() {
        return decoderExtensions;
    }

    /// Extensões que decodificam o segundo halfword de uma instrução Thumb de 32 bits
    /// (`raw` recebido pela extensão é os dois halfwords combinados, primeiro halfword nos bits
    /// altos). Vazio até B2.2 registrar a primeira categoria (data processing); até lá todo
    /// candidato de 32 bits Thumb-2 cai em UNDEFINED controlado — ver {@link
    /// dev.vitorsilverio.armjitter.decoder.ThumbDecoder}.
    public List<DecoderExtension> thumb32DecoderExtensions() {
        return thumb32DecoderExtensions;
    }

    /// Retorna uma cópia desta arquitetura com as extensões de decoder ARM fornecidas, usadas para
    /// plugar grupos de instruções que uma versão futura adiciona.
    public ArmArchitecture withDecoderExtensions(List<DecoderExtension> extensions) {
        return new ArmArchitecture(name, features, extensions, thumb32DecoderExtensions);
    }

    /// Retorna uma cópia desta arquitetura com as extensões de decoder Thumb-2 (32-bit) fornecidas.
    public ArmArchitecture withThumb32DecoderExtensions(List<DecoderExtension> extensions) {
        return new ArmArchitecture(name, features, decoderExtensions, extensions);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
