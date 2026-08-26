package dev.vitorsilverio.armjitter.debug;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gdb64ServerTest {
    private final ArrayMemory64 memory = new ArrayMemory64();

    @Test
    void readsGeneralRegistersInLittleEndianAarch64Layout() throws IOException {
        Aarch64Core cpu = new Aarch64Core(memory);
        cpu.setX(0, 0x1122334455667788L);
        cpu.setSp(0x0000000012345678L);
        cpu.setProgramCounter(0x0000000040000000L);

        List<String> replies = run(cpu, () -> { }, packet("g") + packet("D"));

        String g = replies.get(0);
        assertEquals("8877665544332211", g.substring(0, 16), "x0 little-endian");
        assertEquals("7856341200000000", g.substring(31 * 16, 32 * 16), "sp little-endian");
        assertEquals("0000004000000000", g.substring(32 * 16, 33 * 16), "pc little-endian");
        assertEquals(33 * 16 + 8, g.length(), "x0-x30 + sp + pc + cpsr");
    }

    @Test
    void readsMemoryAsHex() throws IOException {
        memory.write8(0x10, 0xAB);
        memory.write8(0x11, 0xCD);
        Aarch64Core cpu = new Aarch64Core(memory);

        List<String> replies = run(cpu, () -> { }, packet("m10,2") + packet("D"));

        assertEquals("abcd", replies.get(0));
    }

    @Test
    void writesMemoryFromHex() throws IOException {
        Aarch64Core cpu = new Aarch64Core(memory);

        List<String> replies = run(cpu, () -> { }, packet("M20,2:cdef") + packet("D"));

        assertEquals("OK", replies.get(0));
        assertEquals(0xCD, memory.read8(0x20));
        assertEquals(0xEF, memory.read8(0x21));
    }

    @Test
    void writeWatchpointStopsWhenTheWatchedDoublewordChanges() throws IOException {
        Aarch64Core cpu = new Aarch64Core(memory);
        int[] calls = {0};
        Runnable stepOne = () -> {
            if (++calls[0] == 3) {
                memory.write64(0x100, 0x6666666666666666L);
            }
        };

        List<String> replies = run(cpu, stepOne, packet("Z2,100,8") + packet("c") + packet("g") + packet("D"));

        assertEquals("OK", replies.get(0), "Z2 accepted");
        assertEquals("T05watch:100;", replies.get(1), "stops with a write-watchpoint reply at 0x100");
        assertEquals(0x6666666666666666L, memory.read64(0x100));
        assertTrue(calls[0] >= 3, "stepped until the watched doubleword changed");
    }

    @Test
    void addressesAboveTwoGibDoNotOverflowLikeA32BitInt() throws IOException {
        Aarch64Core cpu = new Aarch64Core(memory);
        memory.write8(0xFFFF_FFFFL, 0x42);

        List<String> replies = run(cpu, () -> { }, packet("mffffffff,1") + packet("D"));

        assertEquals("42", replies.get(0));
    }

    @Test
    void readingUnmappedMemoryRepliesWithErrorInsteadOfPropagating() throws IOException {
        AddressSpace64 segfaultingMemory = new SegfaultingMemory64();
        Aarch64Core cpu = new Aarch64Core(segfaultingMemory);

        List<String> replies = run(cpu, segfaultingMemory, () -> { },
                packet("m8000000,4") + packet("M8000000,1:ff") + packet("D"));

        assertEquals("E01", replies.get(0), "read of unmapped address reports E01, not a crash");
        assertEquals("E01", replies.get(1), "write of unmapped address reports E01, not a crash");
    }

    // ---- helpers ----

    private List<String> run(Aarch64Core cpu, Runnable stepOne, String input) throws IOException {
        return run(cpu, memory, stepOne, input);
    }

    private List<String> run(Aarch64Core cpu, AddressSpace64 memory, Runnable stepOne, String input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Gdb64Server server = new Gdb64Server(cpu, memory, stepOne,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)), out);
        server.run();
        return extractPackets(out.toString(StandardCharsets.US_ASCII));
    }

    /// Extrai o conteúdo (payloads) de cada pacote de resposta `$...#` do output capturado.
    private static List<String> extractPackets(String output) {
        List<String> packets = new ArrayList<>();
        int i = 0;
        while ((i = output.indexOf('$', i)) >= 0) {
            int end = output.indexOf('#', i);
            packets.add(output.substring(i + 1, end));
            i = end + 1;
        }
        return packets;
    }

    private static String packet(String data) {
        int checksum = 0;
        for (int i = 0; i < data.length(); i++) {
            checksum = (checksum + data.charAt(i)) & 0xFF;
        }
        return "$" + data + "#" + String.format("%02x", checksum);
    }

    /// 4 GiB (esparsa por mapa, `long`-endereçada) de memória plana para os testes do protocolo —
    /// backing array indexado por `int` (16-bit window real usada pelos testes), mas aceita
    /// endereços `long` de até 32 bits sem sinal para exercitar a faixa alta (achado real B6.x:
    /// `(int)` de um endereço fora de `[Integer.MIN_VALUE, MAX_VALUE]` precisa de máscara
    /// explícita, não de `Math.toIntExact`).
    private static final class ArrayMemory64 implements AddressSpace64 {
        private final byte[] data = new byte[0x10000];

        private int index(long address) {
            return (int) (address & 0xFFFF);
        }

        @Override
        public int read8(long address) {
            return data[index(address)] & 0xFF;
        }

        @Override
        public int read16(long address) {
            return read8(address) | (read8(address + 1) << 8);
        }

        @Override
        public int read32(long address) {
            return read16(address) | (read16(address + 2) << 16);
        }

        @Override
        public void write8(long address, int value) {
            data[index(address)] = (byte) value;
        }

        @Override
        public void write16(long address, int value) {
            write8(address, value);
            write8(address + 1, value >> 8);
        }

        @Override
        public void write32(long address, int value) {
            write16(address, value);
            write16(address + 2, value >> 16);
        }
    }

    /// Simula um hospedeiro cujo barramento lança para endereço fora da faixa mapeada — mesma
    /// ideia de {@code GdbServerTest.SegfaultingMemory}, ver o javadoc lá.
    private static final class SegfaultingMemory64 implements AddressSpace64 {
        @Override
        public int read8(long address) {
            throw new RuntimeException("unmapped: 0x" + Long.toHexString(address));
        }

        @Override
        public int read16(long address) {
            return read8(address);
        }

        @Override
        public int read32(long address) {
            return read8(address);
        }

        @Override
        public void write8(long address, int value) {
            throw new RuntimeException("unmapped: 0x" + Long.toHexString(address));
        }

        @Override
        public void write16(long address, int value) {
            write8(address, value);
        }

        @Override
        public void write32(long address, int value) {
            write8(address, value);
        }
    }
}
