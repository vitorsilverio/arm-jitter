package dev.vitorsilverio.armjitter.debug;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GdbServerTest {
    private final ArrayMemory memory = new ArrayMemory();

    @Test
    void readsGeneralRegistersInLittleEndianArmLayout() throws IOException {
        ArmCore cpu = new ArmCore(memory, SwiDispatcher.empty());
        cpu.setRegister(0, 0x12345678);
        cpu.setRegister(15, 0x08000000);

        List<String> replies = run(cpu, () -> { }, packet("g") + packet("D"));

        String g = replies.get(0);
        assertEquals("78563412", g.substring(0, 8), "r0 little-endian");
        assertEquals("00000008", g.substring(15 * 8, 16 * 8), "r15 little-endian");
        assertEquals(336, g.length(), "r0-15 + f0-7 + fps + cpsr");
    }

    @Test
    void readsMemoryAsHex() throws IOException {
        memory.write8(0x10, 0xAB);
        memory.write8(0x11, 0xCD);
        ArmCore cpu = new ArmCore(memory, SwiDispatcher.empty());

        List<String> replies = run(cpu, () -> { }, packet("m10,2") + packet("D"));

        assertEquals("abcd", replies.get(0));
    }

    @Test
    void writesMemoryFromHex() throws IOException {
        ArmCore cpu = new ArmCore(memory, SwiDispatcher.empty());

        List<String> replies = run(cpu, () -> { }, packet("M20,2:cdef") + packet("D"));

        assertEquals("OK", replies.get(0));
        assertEquals(0xCD, memory.read8(0x20));
        assertEquals(0xEF, memory.read8(0x21));
    }

    @Test
    void writeWatchpointStopsWhenTheWatchedWordChanges() throws IOException {
        ArmCore cpu = new ArmCore(memory, SwiDispatcher.empty());
        int[] calls = {0};
        Runnable stepOne = () -> {
            if (++calls[0] == 3) {
                memory.write32(0x100, 0x66666666);
            }
        };

        List<String> replies = run(cpu, stepOne, packet("Z2,100,4") + packet("c") + packet("g") + packet("D"));

        assertEquals("OK", replies.get(0), "Z2 accepted");
        assertEquals("T05watch:100;", replies.get(1), "stops with a write-watchpoint reply at 0x100");
        assertEquals(0x66666666, memory.read32(0x100));
        assertTrue(calls[0] >= 3, "stepped until the watched word changed");
    }

    // ---- helpers ----

    private List<String> run(ArmCore cpu, Runnable stepOne, String input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GdbServer server = new GdbServer(cpu, memory, stepOne,
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

    /// 64 KB de memória plana para os testes do protocolo.
    private static final class ArrayMemory implements AddressSpace {
        private final byte[] data = new byte[0x10000];

        @Override
        public int read8(int address) {
            return data[address & 0xFFFF] & 0xFF;
        }

        @Override
        public int read16(int address) {
            return read8(address) | (read8(address + 1) << 8);
        }

        @Override
        public int read32(int address) {
            return read16(address) | (read16(address + 2) << 16);
        }

        @Override
        public void write8(int address, int value) {
            data[address & 0xFFFF] = (byte) value;
        }

        @Override
        public void write16(int address, int value) {
            write8(address, value);
            write8(address + 1, value >> 8);
        }

        @Override
        public void write32(int address, int value) {
            write16(address, value);
            write16(address + 2, value >> 16);
        }
    }
}
