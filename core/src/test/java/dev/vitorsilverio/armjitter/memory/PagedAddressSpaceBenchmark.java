package dev.vitorsilverio.armjitter.memory;

/// Task C3 — microbench manual (loop, sem JMH): `PagedAddressSpace` vs uma `AddressSpace`
/// sintética de if-chain por região (o padrão hoje usado em gbaemu/ndsemu). Não é um `@Test`
/// automatizado — roda via `main`; o número vai para o índice de tasks (`tasks/README.md`).
public final class PagedAddressSpaceBenchmark {
    private static final int REGION_SIZE = 1 << 18; // 256KB, como EWRAM do GBA
    private static final int ITERATIONS = 200_000_000;

    /// `AddressSpace` de referência: decide a região por uma cadeia de comparações, como os
    /// barramentos atuais dos hospedeiros — com a MESMA profundidade de branches do mapa de
    /// memória real do GBA (BIOS/WRAM_ON_BOARD/WRAM_ON_CHIP/IO/PALETTE/VRAM/OAM/ROM/SRAM), não
    /// um atalho de 2 branches: um if-chain raso sub-representaria o custo real de predição
    /// que o dispatch O(1) está tentando eliminar.
    private static final class IfChainAddressSpace implements AddressSpace {
        private final byte[] bios = new byte[0x4000];
        private final byte[] iwram = new byte[REGION_SIZE >>> 3];
        private final byte[] io = new byte[0x400];
        private final byte[] palette = new byte[0x400];
        private final byte[] vram = new byte[0x18000];
        private final byte[] oam = new byte[0x400];
        private final byte[] ewram = new byte[REGION_SIZE];
        private final byte[] rom = new byte[0x1000];

        @Override
        public int read32(int address) {
            int region = address >>> 24;
            byte[] backing;
            int mask;
            if (region == 0x00) { backing = bios; mask = bios.length - 1; }
            else if (region == 0x03) { backing = iwram; mask = iwram.length - 1; }
            else if (region == 0x04) { backing = io; mask = io.length - 1; }
            else if (region == 0x05) { backing = palette; mask = palette.length - 1; }
            else if (region == 0x06) { backing = vram; mask = vram.length - 1; }
            else if (region == 0x07) { backing = oam; mask = oam.length - 1; }
            else if (region == 0x02) { backing = ewram; mask = REGION_SIZE - 1; }
            else if (region >= 0x08) { backing = rom; mask = rom.length - 1; }
            else { return 0; }
            int offset = address & mask;
            return (backing[offset] & 0xFF) | ((backing[offset + 1] & 0xFF) << 8)
                    | ((backing[offset + 2] & 0xFF) << 16) | ((backing[offset + 3] & 0xFF) << 24);
        }

        @Override public int read8(int address) { throw new UnsupportedOperationException(); }
        @Override public int read16(int address) { throw new UnsupportedOperationException(); }
        @Override public void write8(int address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write16(int address, int value) { throw new UnsupportedOperationException(); }
        @Override public void write32(int address, int value) {
            if (address >>> 24 == 0x02) {
                int offset = address & (REGION_SIZE - 1);
                ewram[offset] = (byte) value;
                ewram[offset + 1] = (byte) (value >>> 8);
                ewram[offset + 2] = (byte) (value >>> 16);
                ewram[offset + 3] = (byte) (value >>> 24);
            }
        }
    }

    public static void main(String[] args) {
        IfChainAddressSpace ifChain = new IfChainAddressSpace();
        PagedAddressSpace paged = new PagedAddressSpace(14, new AddressSpace() { // páginas de 16KB
            @Override public int read8(int a) { return 0; }
            @Override public int read16(int a) { return 0; }
            @Override public int read32(int a) { return 0; }
            @Override public void write8(int a, int v) { }
            @Override public void write16(int a, int v) { }
            @Override public void write32(int a, int v) { }
        });
        paged.mapRam(0x0200_0000, new byte[REGION_SIZE]);
        paged.mapRam(0x0300_0000, new byte[REGION_SIZE >>> 3]);

        for (int i = 0; i < 5; i++) {
            long ifChainNs = benchRead(ifChain, 0x0200_0000, REGION_SIZE);
            long pagedNs = benchRead(paged, 0x0200_0000, REGION_SIZE);
            System.out.printf(
                    "run %d: if-chain=%.2f ns/op  paged=%.2f ns/op  paged/if-chain=%.2fx%n",
                    i, ifChainNs / (double) ITERATIONS, pagedNs / (double) ITERATIONS,
                    pagedNs / (double) ifChainNs);
        }
    }

    private static long benchRead(AddressSpace space, int base, int regionSize) {
        int checksum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            checksum += space.read32(base + ((i * 4) & (regionSize - 4)));
        }
        long elapsed = System.nanoTime() - start;
        if (checksum == 42) {
            throw new AssertionError("unreachable, só para impedir DCE do checksum");
        }
        return elapsed;
    }
}
