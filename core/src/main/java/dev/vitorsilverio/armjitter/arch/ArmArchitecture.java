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
    /// 32 bits que não inclui `B.W`/`TBB`/`TBH`. A task B7.4 original anexou este decoder por
    /// engano (ele não aparece na enumeração do javadoc logo acima, que já dizia "BL... barreiras
    /// ... MRS/MSR — todos cobertos por Thumb2MiscDecoder"): sem esta correção, `ARMV6M` aceitava
    /// silenciosamente `B.W`/`TBB`/`TBH`, que a arquitetura real rejeita (G8).
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
                    new dev.vitorsilverio.armjitter.decoder.Thumb2MiscDecoder(ARMV6M_FEATURES)));

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
