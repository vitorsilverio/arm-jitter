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

    // ── Achado real (task F3/virtual-arm-box): endereço JÁ ALINHADO NUNCA deve atravessar ──────

    /// `TestAddressSpace` (RAM de verdade, usada para o código buscado pelo core) mas com um
    /// único endereço "registrador MMIO simulado" onde os 3 bytes vizinhos do word alinhado
    /// (offsets +1/+2/+3) SEMPRE leem `0`/ignoram escrita — o padrão real de `default -> 0`/
    /// `default -> {}` de {@code Bcm2835Ic} e companhia (só o offset 0 do registrador "existe";
    /// os vizinhos são "desconhecidos"). Um acesso "atravessado" byte a byte a este endereço NUNCA
    /// recompõe o valor real do registrador (só o byte 0 bate, os outros 3 leem 0 em vez do
    /// conteúdo real), enquanto um `read32`/`write32` direto sempre acerta — exatamente a
    /// distinção que
    /// {@link dev.vitorsilverio.armjitter.codegen.executor.IrExecutionSupport#readWordForLoad}
    /// precisa fazer. Modela a causa raiz real do bloqueio de M2 da task F3 (`IRQ_PENDING_BASIC`
    /// do `Bcm2835Ic` lido como `0` mesmo com o bit certo armado, porque o driver do kernel usa
    /// `LDR` alinhado).
    private static final class SparseRegisterAddressSpace implements dev.vitorsilverio.armjitter.memory.AddressSpace {
        /// Backing de RAM comum, usado só para o código buscado pelo core (`put32` das
        /// instruções) — fora do endereço do "registrador MMIO simulado" este barramento se
        /// comporta como {@link TestAddressSpace}.
        private final byte[] code;
        private final int registerAddress;
        /// Valor AUTORITATIVO do registrador — só `read32`/`write32` diretos o tocam, exatamente
        /// como {@code Bcm2835Ic#read32}/`write32` fazem por cima do estado real do periférico,
        /// desacoplado do que `read8` (byte a byte) consegue enxergar nos offsets vizinhos.
        private int registerValue;

        SparseRegisterAddressSpace(int size, int registerAddress) {
            this.code = new byte[size];
            this.registerAddress = registerAddress;
        }

        void put32(int address, int value) {
            code[address] = (byte) value;
            code[address + 1] = (byte) (value >>> 8);
            code[address + 2] = (byte) (value >>> 16);
            code[address + 3] = (byte) (value >>> 24);
        }

        @Override
        public int read8(int address) {
            if (address == registerAddress) {
                return registerValue & 0xFF;
            }
            if (address > registerAddress && address <= registerAddress + 3) {
                return 0; // offset "desconhecido" do periférico simulado.
            }
            return code[address] & 0xFF;
        }

        @Override
        public int read16(int address) {
            return read8(address) | (read8(address + 1) << 8);
        }

        @Override
        public int read32(int address) {
            return address == registerAddress ? registerValue : (read16(address) | (read16(address + 2) << 16));
        }

        @Override
        public void write8(int address, int value) {
            code[address] = (byte) value;
        }

        @Override
        public void write16(int address, int value) {
            int aligned = address & ~1;
            write8(aligned, value);
            write8(aligned + 1, value >>> 8);
        }

        @Override
        public void write32(int address, int value) {
            if (address == registerAddress) {
                registerValue = value;
                return;
            }
            write16(address, value);
            write16(address + 2, value >>> 16);
        }
    }

    @Test
    void alignedWordLoadReadsTheFullMmioRegisterInsteadOfCrossingWithTheFeature() {
        SparseRegisterAddressSpace memory = new SparseRegisterAddressSpace(64, 32);
        memory.write32(32, 0x0000_0100); // valor real do achado F3: IRQ_PENDING_BASIC, bit 8 armado
        memory.put32(16, ldr(0, 1)); // LDR r1,[r0]
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, 32); // endereço JÁ ALINHADO
        core.setProgramCounter(16);

        core.step();

        assertEquals(0x0000_0100, core.register(1),
                "acesso alinhado sob UNALIGNED_ACCESS deve ler a word inteira via read32, não "
                        + "atravessar byte a byte (regressão do bug real da F3)");
    }

    @Test
    void alignedWordStoreWritesTheFullMmioRegisterInsteadOfCrossingWithTheFeature() {
        SparseRegisterAddressSpace memory = new SparseRegisterAddressSpace(64, 32);
        memory.put32(16, str(0, 1)); // STR r1,[r0]
        ArmCore core = new ArmCore(memory, SwiDispatcher.empty(), ArmArchitecture.ARMV6K);
        core.setRegister(0, 32); // endereço JÁ ALINHADO
        core.setRegister(1, 0xAABB_CCDD);
        core.setProgramCounter(16);

        core.step();

        assertEquals(0xAABB_CCDD, memory.read32(32),
                "escrita alinhada sob UNALIGNED_ACCESS deve escrever a word inteira via write32");
    }
}
