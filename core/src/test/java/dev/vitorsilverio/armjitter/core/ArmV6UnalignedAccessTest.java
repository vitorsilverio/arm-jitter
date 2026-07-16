package dev.vitorsilverio.armjitter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.support.TestAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

/// `ArmFeature.UNALIGNED_ACCESS` (task B1.7): `LDR`/`STR`/`LDRH`/`STRH` desalinhados fazem o
/// acesso "atravessado" ARMv6+ sob a feature, em vez da rotação ARMv4T legada. Cobre os testes
/// mínimos da task: pin do legado (regressão G2 permanente), valores atravessados concretos por
/// offset, fronteira de página, e a exclusão de "loads para PC" (que continuam alinhando mesmo
/// sob a feature).
class ArmV6UnalignedAccessTest {
    /// `LDR Rd,[Rn]` (offset imediato 0, cond AL).
    private static int ldr(int rn, int rd) {
        return 0xE590_0000 | (rn << 16) | (rd << 12);
    }

    /// `STR Rd,[Rn]` (offset imediato 0, cond AL).
    private static int str(int rn, int rd) {
        return 0xE580_0000 | (rn << 16) | (rd << 12);
    }

    /// `LDRH Rd,[Rn]` (offset imediato 0, unsigned, cond AL).
    private static int ldrh(int rn, int rd) {
        return 0xE1D0_00B0 | (rn << 16) | (rd << 12);
    }

    /// `LDRSH Rd,[Rn]` (offset imediato 0, cond AL).
    private static int ldrsh(int rn, int rd) {
        return 0xE1D0_00F0 | (rn << 16) | (rd << 12);
    }

    /// `STRH Rd,[Rn]` (offset imediato 0, cond AL).
    private static int strh(int rn, int rd) {
        return 0xE1C0_00B0 | (rn << 16) | (rd << 12);
    }

    private static ArmCore legacyCore(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV5TE);
    }

    private static ArmCore unalignedCore(TestAddressSpace memory) {
        return new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
    }

    // ── LOAD word: pin do legado (rotação ARMv4T/v5TE) ───────────────────────────

    @Test
    void legacyUnalignedWordLoadRotatesAtEveryOffsetWithoutTheFeature() {
        // bytes 11 22 33 44 55 66 77 88 em 0..7; sem a feature, LDR desalinhado alinha para
        // baixo e rotaciona o valor lido — comportamento ARMv4T/v5TE, NÃO deve mudar (G2).
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88);
        memory.put32(16, ldr(0, 1));
        ArmCore core = legacyCore(memory);

        core.setRegister(0, 1);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x1144_3322, core.register(1), "offset+1 rotacionado");

        core.setRegister(0, 2);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x2211_4433, core.register(1), "offset+2 rotacionado");

        core.setRegister(0, 3);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x3322_1144, core.register(1), "offset+3 rotacionado");
    }

    @Test
    void legacyUnalignedHalfwordLoadRotatesWithoutTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33);
        memory.put32(16, ldrh(0, 1));
        ArmCore core = legacyCore(memory);
        core.setRegister(0, 1);
        core.setProgramCounter(16);

        core.step();

        // aligned=0, valor=0x2211, ímpar -> rotateRight(0x00002211, 8) = 0x11000022.
        assertEquals(0x1100_0022, core.register(1));
    }

    @Test
    void legacySignedHalfwordLoadBecomesByteReadOnOddAddressWithoutTheFeature() {
        // LDRSH desalinhado em v4T/v5TE: vira leitura de byte (sign-extend do byte no endereço
        // ímpar), quirk documentado no executor — pinado aqui, não deve mudar sob a feature.
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x00, 0x80); // byte no endereço ímpar (1) = 0x80 -> -128
        memory.put32(16, ldrsh(0, 1));
        ArmCore core = legacyCore(memory);
        core.setRegister(0, 1);
        core.setProgramCounter(16);

        core.step();

        assertEquals(-128, core.register(1));
    }

    // ── LOAD word/halfword: acesso atravessado sob a feature ─────────────────────

    @Test
    void unalignedWordLoadIsCrossedAtEveryOffsetWithTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88);
        memory.put32(16, ldr(0, 1));
        ArmCore core = unalignedCore(memory);

        core.setRegister(0, 1);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x5544_3322, core.register(1), "offset+1 atravessado (exemplo da task B1.7)");

        core.setRegister(0, 2);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x6655_4433, core.register(1), "offset+2 atravessado");

        core.setRegister(0, 3);
        core.setProgramCounter(16);
        core.step();
        assertEquals(0x7766_5544, core.register(1), "offset+3 atravessado");
    }

    @Test
    void unalignedUnsignedHalfwordLoadIsCrossedWithTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33);
        memory.put32(16, ldrh(0, 1));
        ArmCore core = unalignedCore(memory);
        core.setRegister(0, 1);
        core.setProgramCounter(16);

        core.step();

        assertEquals(0x3322, core.register(1));
    }

    @Test
    void unalignedSignedHalfwordLoadIsCrossedAndSignExtendedWithTheFeature() {
        // bytes no endereço 3 e 4: 0x00, 0xF2 -> halfword atravessado 0xF200, sinal-estendido.
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 3, 0x00, 0xF2);
        memory.put32(16, ldrsh(0, 1));
        ArmCore core = unalignedCore(memory);
        core.setRegister(0, 3);
        core.setProgramCounter(16);

        core.step();

        assertEquals((short) 0xF200, core.register(1));
    }

    // ── LOAD para PC: continua alinhando mesmo sob a feature ─────────────────────

    @Test
    void loadToProgramCounterKeepsLegacyAlignmentEvenWithTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        writeBytes(memory, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88);
        memory.put32(16, ldr(0, 15)); // LDR pc,[r0]
        ArmCore core = unalignedCore(memory);
        core.setRegister(0, 1);
        core.setProgramCounter(16);

        core.step();

        // Mesmo valor do teste "legacy" (rotacionado), NÃO o atravessado 0x5544_3322.
        assertEquals(0x1144_3322, core.programCounter());
    }

    // ── STORE word/halfword: pin do legado + atravessado sob a feature ───────────

    @Test
    void legacyUnalignedWordStoreIgnoresLowAddressBitsWithoutTheFeature() {
        // TestAddressSpace "modela RAM normal": write16 força alinhamento (bit 0 ignorado). Sem
        // a feature, write32Arm7 delega direto a write32(address,value) = duas write16 (low16 em
        // `address`, high16 em `address+2`), cada uma alinhada PARA BAIXO independentemente — não
        // é um alinhamento de word único, é o comportamento real e pré-existente, pinado aqui tal
        // como é (G2): offset 1 coincide com offset 0 (ambas as metades alinham para 0/2); offset
        // 2 e 3 coincidem entre si (alinham para 2/4).
        for (int offset : new int[] {1, 2, 3}) {
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(16, str(0, 1));
            ArmCore core = legacyCore(memory);
            core.setRegister(0, offset);
            core.setRegister(1, 0xAABB_CCDD);
            core.setProgramCounter(16);

            core.step();

            int lowAligned = offset & ~1;
            int highAligned = (offset + 2) & ~1;
            assertEquals(0xDD, memory.read8(lowAligned), "offset=" + offset);
            assertEquals(0xCC, memory.read8(lowAligned + 1), "offset=" + offset);
            assertEquals(0xBB, memory.read8(highAligned), "offset=" + offset);
            assertEquals(0xAA, memory.read8(highAligned + 1), "offset=" + offset);
        }
    }

    @Test
    void unalignedWordStoreIsCrossedAtEveryOffsetWithTheFeature() {
        for (int offset : new int[] {1, 2, 3}) {
            TestAddressSpace memory = new TestAddressSpace(32);
            memory.put32(16, str(0, 1));
            ArmCore core = unalignedCore(memory);
            core.setRegister(0, offset);
            core.setRegister(1, 0xAABB_CCDD);
            core.setProgramCounter(16);

            core.step();

            assertEquals(0xDD, memory.read8(offset), "offset=" + offset);
            assertEquals(0xCC, memory.read8(offset + 1), "offset=" + offset);
            assertEquals(0xBB, memory.read8(offset + 2), "offset=" + offset);
            assertEquals(0xAA, memory.read8(offset + 3), "offset=" + offset);
        }
    }

    @Test
    void legacyUnalignedHalfwordStoreIgnoresLowAddressBitWithoutTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(16, strh(0, 1));
        ArmCore core = legacyCore(memory);
        core.setRegister(0, 1); // ímpar -> write16Arm7 delega direto, TestAddressSpace alinha para 0
        core.setRegister(1, 0xBEEF);
        core.setProgramCounter(16);

        core.step();

        assertEquals(0xEF, memory.read8(0));
        assertEquals(0xBE, memory.read8(1));
    }

    @Test
    void unalignedHalfwordStoreIsCrossedWithTheFeature() {
        TestAddressSpace memory = new TestAddressSpace(32);
        memory.put32(16, strh(0, 1));
        ArmCore core = unalignedCore(memory);
        core.setRegister(0, 1);
        core.setRegister(1, 0xBEEF);
        core.setProgramCounter(16);

        core.step();

        assertEquals(0xEF, memory.read8(1));
        assertEquals(0xBE, memory.read8(2));
    }

    // ── Fronteira de página/região do AddressSpace ────────────────────────────────

    @Test
    void unalignedWordLoadCrossesAddressSpacePageBoundaryCorrectly() {
        // Páginas de 256 bytes MAPEADAS SEPARADAMENTE (dois arrays distintos, "fáceis de testar"
        // como no PagedAddressSpaceTest) para provar que o acesso atravessado compõe byte a byte
        // via AddressSpace, e não "lê largo e recorta" (o que quebraria exatamente nesta
        // fronteira). pageShift pequeno (ex. 2) explodiria a tabela de páginas em memória — daí
        // 8 (256 bytes), suficiente para exercitar a fronteira sem custo de alocação.
        int pageShift = 8;
        int pageSize = 1 << pageShift;
        TestAddressSpace openBus = new TestAddressSpace(8192);
        openBus.put32(0x1000, ldr(0, 1));
        PagedAddressSpace memory = new PagedAddressSpace(pageShift, openBus);
        byte[] page0 = new byte[pageSize];
        page0[pageSize - 2] = 0x11;
        page0[pageSize - 1] = 0x22;
        byte[] page1 = new byte[pageSize];
        page1[0] = 0x33;
        page1[1] = 0x44;
        memory.mapRam(0, page0);
        memory.mapRam(pageSize, page1);
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, pageSize - 2); // últimos 2 bytes da 1a pagina + primeiros 2 da 2a
        core.setProgramCounter(0x1000);

        core.step();

        assertEquals(0x4433_2211, core.register(1));
    }

    private static void writeBytes(TestAddressSpace memory, int base, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            memory.write8(base + i, bytes[i]);
        }
    }
}
