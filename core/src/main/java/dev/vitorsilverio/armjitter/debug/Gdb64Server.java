package dev.vitorsilverio.armjitter.debug;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.memory.AddressSpace64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Stub mínimo do protocolo de série remota GDB para um {@link Aarch64Core} — irmão A64 do
/// {@link GdbServer} (ARM32), classe própria em vez de generalizar os dois num tipo comum: os
/// mundos de 32 e 64 bits já são independentes por desenho em todo o resto do arm-jitter (ver
/// {@link AddressSpace64}), registradores/endereços são larguras diferentes (`int` vs `long`) e o
/// layout do pacote `g` é o do AArch64 real (`x0`-`x30`/`sp`/`pc`/`cpsr`), não o layout ARM legado
/// (r0-r15 + FPA) que {@link GdbServer} usa. Duplicar o enquadramento de pacote (~40 linhas) é
/// mais simples e mais seguro do que uma abstração compartilhada entre dois protocolos que só têm
/// a moldura em comum.
///
/// Registradores do pacote `g`/`p`/`P` (mesma ordem que `gdb-multiarch`/`aarch64-*-gdb` esperam
/// SEM descrição de target via `qXfer:features:read`, layout padrão de `aarch64-tdep.c` do GDB):
/// `x0`-`x30` (regnum 0-30, 8 bytes cada), `sp` (regnum 31, 8 bytes), `pc` (regnum 32, 8 bytes),
/// `cpsr` (regnum 33, 4 bytes — aqui só `NZCV`+`DAIF.I` são reais, ver {@link
/// dev.vitorsilverio.armjitter.core64.PstateRegister#toSpsrFormat()}; os demais bits ficam zero,
/// mesma honestidade de escopo do resto do épico A64).
///
/// Escopo: watchpoints de escrita são detectados por comparação de valor após cada instrução
/// (watchpoints de leitura e acesso não são suportados) — mesma limitação do {@link GdbServer}.
public final class Gdb64Server {
    private static final int SIGTRAP = 5;
    private static final int SIGINT = 2;
    private static final int GENERAL_REGISTER_COUNT = 31; // x0..x30
    /// Consulta o soquete por uma interrupção (Ctrl-C) apenas a cada N passos para manter `continue` rápido.
    private static final int INTERRUPT_POLL_INTERVAL = 0x10000;

    private final Aarch64Core cpu;
    private final AddressSpace64 memory;
    private final Runnable stepOne;
    private final InputStream in;
    private final OutputStream out;

    private final Set<Long> breakpoints = new HashSet<>();
    private final List<Watchpoint> watchpoints = new ArrayList<>();
    private String lastStopReply = "S05";

    public Gdb64Server(Aarch64Core cpu, AddressSpace64 memory, Runnable stepOne, InputStream in, OutputStream out) {
        this.cpu = cpu;
        this.memory = memory;
        this.stepOne = stepOne;
        this.in = in;
        this.out = out;
    }

    /// Escuta na {@code porta}, aceita um único cliente e o atende até que se desconecte.
    /// Bloqueia a thread chamadora (intenção: a thread de emulação, interrompida até que o gdb se conecte).
    public static void listenAndServe(int port, Aarch64Core cpu, AddressSpace64 memory, Runnable stepOne) {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[gdb64] listening on port " + port + " — waiting for a client (e.g. aarch64-none-elf-gdb 'target remote :" + port + "')");
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                System.out.println("[gdb64] client connected");
                new Gdb64Server(cpu, memory, stepOne, socket.getInputStream(), socket.getOutputStream()).run();
            }
            System.out.println("[gdb64] client disconnected");
        } catch (IOException exception) {
            System.err.println("[gdb64] server error: " + exception.getMessage());
        }
    }

    /// Executa o loop do protocolo até que o cliente se desconecte/mate a conexão ou a conexão se feche.
    public void run() throws IOException {
        Mode mode = Mode.HALTED;
        while (true) {
            if (mode == Mode.HALTED) {
                String packet = readPacket();
                if (packet == null) {
                    return;
                }
                mode = handlePacket(packet);
                if (mode == Mode.DETACH) {
                    return;
                }
            } else {
                stepOne.run();
                Watchpoint hit = firstChangedWatchpoint();
                if (hit != null) {
                    stop("T05watch:" + Long.toHexString(hit.address) + ";");
                    mode = Mode.HALTED;
                    continue;
                }
                if (mode == Mode.RUNNING && breakpoints.contains(cpu.pc())) {
                    stop("T05swbreak:;");
                    mode = Mode.HALTED;
                    continue;
                }
                if (mode == Mode.STEPPING) {
                    stop("S05");
                    mode = Mode.HALTED;
                    continue;
                }
                if (interruptRequested()) {
                    stop("S" + hex2(SIGINT));
                    mode = Mode.HALTED;
                }
            }
        }
    }

    private long stepCounter;

    private boolean interruptRequested() throws IOException {
        if ((++stepCounter & (INTERRUPT_POLL_INTERVAL - 1)) != 0) {
            return false;
        }
        while (in.available() > 0) {
            int b = in.read();
            if (b == 0x03) {
                return true;
            }
            // ignore stray acks while running
        }
        return false;
    }

    private void stop(String reply) throws IOException {
        lastStopReply = reply;
        sendPacket(reply);
    }

    private Mode handlePacket(String packet) throws IOException {
        if (packet.isEmpty()) {
            sendPacket("");
            return Mode.HALTED;
        }
        char command = packet.charAt(0);
        String body = packet.substring(1);
        switch (command) {
            case '?' -> sendPacket(lastStopReply);
            case 'g' -> sendPacket(readRegisters());
            case 'G' -> {
                writeRegisters(body);
                sendPacket("OK");
            }
            case 'm' -> sendPacket(readMemory(body));
            case 'M' -> sendPacket(writeMemory(body));
            case 'p' -> sendPacket(readRegister(body));
            case 'P' -> sendPacket(writeRegister(body));
            case 'c' -> {
                resumeAt(body);
                return Mode.RUNNING;
            }
            case 's' -> {
                resumeAt(body);
                return Mode.STEPPING;
            }
            case 'Z' -> sendPacket(addBreakpoint(body));
            case 'z' -> sendPacket(removeBreakpoint(body));
            case 'q' -> sendPacket(handleQuery(body));
            case 'H' -> sendPacket("OK");
            case 'D' -> {
                sendPacket("OK");
                return Mode.DETACH;
            }
            case 'k' -> {
                return Mode.DETACH;
            }
            default -> sendPacket(""); // unsupported -> empty reply
        }
        return Mode.HALTED;
    }

    private String handleQuery(String body) {
        if (body.startsWith("Supported")) {
            return "PacketSize=1000;swbreak+;hwbreak+";
        }
        if (body.startsWith("Attached")) {
            return "1";
        }
        if (body.equals("C")) {
            return "QC0";
        }
        if (body.equals("fThreadInfo")) {
            return "m0";
        }
        if (body.equals("sThreadInfo")) {
            return "l";
        }
        return "";
    }

    private void resumeAt(String body) {
        if (!body.isEmpty()) {
            cpu.setProgramCounter(Long.parseUnsignedLong(body, 16));
        }
    }

    // ---- registers ----

    private String readRegisters() {
        StringBuilder sb = new StringBuilder((GENERAL_REGISTER_COUNT + 2) * 16 + 8);
        for (int i = 0; i < GENERAL_REGISTER_COUNT; i++) {
            sb.append(doublewordLe(cpu.x(i)));
        }
        sb.append(doublewordLe(cpu.sp()));
        sb.append(doublewordLe(cpu.pc()));
        sb.append(wordLe((int) cpu.pstate().toSpsrFormat()));
        return sb.toString();
    }

    private void writeRegisters(String hex) {
        int offset = 0;
        for (int i = 0; i < GENERAL_REGISTER_COUNT && offset + 16 <= hex.length(); i++, offset += 16) {
            cpu.setX(i, parseDoublewordLe(hex, offset));
        }
        if (offset + 16 <= hex.length()) {
            cpu.setSp(parseDoublewordLe(hex, offset));
            offset += 16;
        }
        if (offset + 16 <= hex.length()) {
            cpu.setProgramCounter(parseDoublewordLe(hex, offset));
            offset += 16;
        }
        if (offset + 8 <= hex.length()) {
            cpu.pstate().setFromSpsrFormat(Integer.toUnsignedLong(parseWordLe(hex, offset)));
        }
    }

    private String readRegister(String body) {
        int reg = Integer.parseInt(body, 16);
        if (reg >= 0 && reg < GENERAL_REGISTER_COUNT) {
            return doublewordLe(cpu.x(reg));
        }
        if (reg == GENERAL_REGISTER_COUNT) {
            return doublewordLe(cpu.sp());
        }
        if (reg == GENERAL_REGISTER_COUNT + 1) {
            return doublewordLe(cpu.pc());
        }
        if (reg == GENERAL_REGISTER_COUNT + 2) {
            return wordLe((int) cpu.pstate().toSpsrFormat());
        }
        return "00000000"; // registrador FP/SIMD, ainda não exposto ao gdb
    }

    private String writeRegister(String body) {
        int eq = body.indexOf('=');
        if (eq < 0) {
            return "E01";
        }
        int reg = Integer.parseInt(body.substring(0, eq), 16);
        String hexValue = body.substring(eq + 1);
        if (reg >= 0 && reg < GENERAL_REGISTER_COUNT) {
            cpu.setX(reg, parseDoublewordLe(hexValue, 0));
        } else if (reg == GENERAL_REGISTER_COUNT) {
            cpu.setSp(parseDoublewordLe(hexValue, 0));
        } else if (reg == GENERAL_REGISTER_COUNT + 1) {
            cpu.setProgramCounter(parseDoublewordLe(hexValue, 0));
        } else if (reg == GENERAL_REGISTER_COUNT + 2) {
            cpu.pstate().setFromSpsrFormat(Integer.toUnsignedLong(parseWordLe(hexValue, 0)));
        }
        return "OK";
    }

    // ---- memory ----

    /// Endereço inválido/não mapeado é reportado ao gdb como `E01` em vez de deixar a exceção do
    /// hospedeiro atravessar e derrubar a sessão inteira — mesma proteção de {@link GdbServer}.
    private String readMemory(String body) {
        int comma = body.indexOf(',');
        long address = Long.parseUnsignedLong(body.substring(0, comma), 16);
        int length = Integer.parseInt(body.substring(comma + 1), 16);
        StringBuilder sb = new StringBuilder(length * 2);
        try {
            for (int i = 0; i < length; i++) {
                sb.append(hex2(memory.read8(address + i) & 0xFF));
            }
        } catch (RuntimeException outOfRange) {
            return "E01";
        }
        return sb.toString();
    }

    /// Mesma proteção contra endereço inválido de {@link #readMemory} — ver o javadoc lá.
    private String writeMemory(String body) {
        int comma = body.indexOf(',');
        int colon = body.indexOf(':');
        long address = Long.parseUnsignedLong(body.substring(0, comma), 16);
        int length = Integer.parseInt(body.substring(comma + 1, colon), 16);
        String data = body.substring(colon + 1);
        try {
            for (int i = 0; i < length; i++) {
                memory.write8(address + i, Integer.parseInt(data.substring(i * 2, i * 2 + 2), 16));
            }
        } catch (RuntimeException outOfRange) {
            return "E01";
        }
        return "OK";
    }

    // ---- breakpoints / watchpoints ----

    private String addBreakpoint(String body) {
        String[] parts = body.split(",");
        int type = Integer.parseInt(parts[0]);
        long address = Long.parseUnsignedLong(parts[1], 16);
        int length = parts.length > 2 ? Integer.parseInt(parts[2], 16) : 1;
        switch (type) {
            case 0, 1 -> breakpoints.add(address);          // breakpoint de PC sw/hw
            case 2 -> watchpoints.add(new Watchpoint(address, length, readRegion(address, length))); // escrita
            default -> {
                return ""; // watchpoints de leitura/acesso não suportados
            }
        }
        return "OK";
    }

    private String removeBreakpoint(String body) {
        String[] parts = body.split(",");
        int type = Integer.parseInt(parts[0]);
        long address = Long.parseUnsignedLong(parts[1], 16);
        switch (type) {
            case 0, 1 -> breakpoints.remove(address);
            case 2 -> watchpoints.removeIf(w -> w.address == address);
            default -> {
                return "";
            }
        }
        return "OK";
    }

    private Watchpoint firstChangedWatchpoint() {
        for (Watchpoint watchpoint : watchpoints) {
            long current = readRegion(watchpoint.address, watchpoint.length);
            if (current != watchpoint.lastValue) {
                watchpoint.lastValue = current;
                return watchpoint;
            }
        }
        return null;
    }

    private long readRegion(long address, int length) {
        return switch (length) {
            case 1 -> memory.read8(address) & 0xFFL;
            case 2 -> memory.read16(address) & 0xFFFFL;
            case 4 -> memory.read32(address) & 0xFFFFFFFFL;
            case 8 -> memory.read64(address);
            default -> {
                long value = 0;
                for (int i = 0; i < length && i < 8; i++) {
                    value |= (memory.read8(address + i) & 0xFFL) << (i * 8);
                }
                yield value;
            }
        };
    }

    // ---- packet framing ----

    private String readPacket() throws IOException {
        int c;
        do {
            c = in.read();
            if (c == -1) {
                return null;
            }
        } while (c != '$'); // pula acks/lixo até o início do pacote
        StringBuilder sb = new StringBuilder();
        int checksum = 0;
        while ((c = in.read()) != '#') {
            if (c == -1) {
                return null;
            }
            sb.append((char) c);
            checksum = (checksum + c) & 0xFF;
        }
        int expected = (digit(in.read()) << 4) | digit(in.read());
        if (expected != checksum) {
            out.write('-');
            out.flush();
            return readPacket();
        }
        out.write('+');
        out.flush();
        return sb.toString();
    }

    private void sendPacket(String data) throws IOException {
        int checksum = 0;
        for (int i = 0; i < data.length(); i++) {
            checksum = (checksum + data.charAt(i)) & 0xFF;
        }
        String message = "$" + data + "#" + hex2(checksum);
        out.write(message.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        // O ack ('+') do cliente é consumido pela próxima chamada de readPacket().
    }

    private static int digit(int c) {
        return Character.digit(c, 16);
    }

    private static String hex2(int value) {
        return String.format("%02x", value & 0xFF);
    }

    private static String wordLe(int value) {
        return hex2(value) + hex2(value >> 8) + hex2(value >> 16) + hex2(value >> 24);
    }

    private static String doublewordLe(long value) {
        return wordLe((int) value) + wordLe((int) (value >>> 32));
    }

    private static int parseWordLe(String hex, int offset) {
        int b0 = Integer.parseInt(hex.substring(offset, offset + 2), 16);
        int b1 = Integer.parseInt(hex.substring(offset + 2, offset + 4), 16);
        int b2 = Integer.parseInt(hex.substring(offset + 4, offset + 6), 16);
        int b3 = Integer.parseInt(hex.substring(offset + 6, offset + 8), 16);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long parseDoublewordLe(String hex, int offset) {
        long low = Integer.toUnsignedLong(parseWordLe(hex, offset));
        long high = Integer.toUnsignedLong(parseWordLe(hex, offset + 8));
        return low | (high << 32);
    }

    private enum Mode {
        HALTED, RUNNING, STEPPING, DETACH
    }

    private static final class Watchpoint {
        private final long address;
        private final int length;
        private long lastValue;

        private Watchpoint(long address, int length, long lastValue) {
            this.address = address;
            this.length = length;
            this.lastValue = lastValue;
        }
    }
}
