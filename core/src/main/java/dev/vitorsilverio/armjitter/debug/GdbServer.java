package dev.vitorsilverio.armjitter.debug;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.memory.AddressSpace;

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

/// Um stub mínimo do protocolo de série remota GDB para um {@link ArmCore}, para que um depurador hospedeiro
/// (ex. arm-none-eabi-gdb) possa inspecionar e controlar a CPU emulada como o
/// stub mGBA: ler/escrever registradores e memória, definir breakpoints em PC e watchpoints de escrita,
/// continuar e avançar um passo por vez. Orienta a execução uma instrução por vez através de um
/// avançador fornecido pela hospedagem (que também deve atualizar o resto do hardware), tudo na
/// thread do chamador — não há acesso concorrente à CPU/memória.
///
/// Escopo: watchpoints de escrita são detectados por comparação de valor após cada instrução (watchpoints de
/// leitura e acesso não são suportados). O pacote `g` usa o layout ARM legado
/// (r0-r15, FPA f0-f7 + fps como zero, depois cpsr) para que o gdb funcione sem descrição de target.
public final class GdbServer {
    private static final int SIGTRAP = 5;
    private static final int SIGINT = 2;
    /// Consulta o soquete por uma interrupção (Ctrl-C) apenas a cada N passos para manter `continue` rápido.
    private static final int INTERRUPT_POLL_INTERVAL = 0x10000;

    private final ArmCore cpu;
    private final AddressSpace memory;
    private final Runnable stepOne;
    private final InputStream in;
    private final OutputStream out;

    private final Set<Integer> breakpoints = new HashSet<>();
    private final List<Watchpoint> watchpoints = new ArrayList<>();
    private String lastStopReply = "S05";

    public GdbServer(ArmCore cpu, AddressSpace memory, Runnable stepOne, InputStream in, OutputStream out) {
        this.cpu = cpu;
        this.memory = memory;
        this.stepOne = stepOne;
        this.in = in;
        this.out = out;
    }

    /// Escuta na {@code porta}, aceita um único cliente e o atende até que se desconecte.
    /// Bloqueia a thread chamadora (intenção: a thread de emulação, interrompida até que o gdb se conecte).
    public static void listenAndServe(int port, ArmCore cpu, AddressSpace memory, Runnable stepOne) {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[gdb] listening on port " + port + " — waiting for a client (e.g. arm-none-eabi-gdb 'target remote :" + port + "')");
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                System.out.println("[gdb] client connected");
                new GdbServer(cpu, memory, stepOne, socket.getInputStream(), socket.getOutputStream()).run();
            }
            System.out.println("[gdb] client disconnected");
        } catch (IOException exception) {
            System.err.println("[gdb] server error: " + exception.getMessage());
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
                    stop("T05watch:" + Integer.toHexString(hit.address) + ";");
                    mode = Mode.HALTED;
                    continue;
                }
                if (mode == Mode.RUNNING && breakpoints.contains(cpu.programCounter())) {
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
            cpu.setProgramCounter((int) Long.parseLong(body, 16));
        }
    }

    // ---- registers ----

    private String readRegisters() {
        StringBuilder sb = new StringBuilder(336);
        int[] registers = cpu.registersSnapshot();
        for (int i = 0; i < 16; i++) {
            sb.append(wordLe(registers[i]));
        }
        sb.append("0".repeat(8 * 12 * 2 + 4 * 2)); // f0..f7 (12 bytes each) + fps (4 bytes), all zero (hex chars)
        sb.append(wordLe(cpu.cpsr().get()));
        return sb.toString();
    }

    private void writeRegisters(String hex) {
        for (int i = 0; i < 16 && (i + 1) * 8 <= hex.length(); i++) {
            cpu.setRegister(i, parseWordLe(hex, i * 8));
        }
        int cpsrOffset = (16 + 8 * 3 + 1) * 8; // after r0-15, f0-7 (3 words each), fps
        if (cpsrOffset + 8 <= hex.length()) {
            cpu.setCpsr(parseWordLe(hex, cpsrOffset));
        }
    }

    private String readRegister(String body) {
        int reg = (int) Long.parseLong(body, 16);
        if (reg >= 0 && reg <= 15) {
            return wordLe(cpu.register(reg));
        }
        if (reg == 25) {
            return wordLe(cpu.cpsr().get());
        }
        return "00000000"; // FPA / fps
    }

    private String writeRegister(String body) {
        int eq = body.indexOf('=');
        if (eq < 0) {
            return "E01";
        }
        int reg = (int) Long.parseLong(body.substring(0, eq), 16);
        int value = parseWordLe(body.substring(eq + 1), 0);
        if (reg >= 0 && reg <= 15) {
            cpu.setRegister(reg, value);
        } else if (reg == 25) {
            cpu.setCpsr(value);
        }
        return "OK";
    }

    // ---- memory ----

    private String readMemory(String body) {
        int comma = body.indexOf(',');
        int address = (int) Long.parseLong(body.substring(0, comma), 16);
        int length = Integer.parseInt(body.substring(comma + 1), 16);
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(hex2(memory.read8(address + i) & 0xFF));
        }
        return sb.toString();
    }

    private String writeMemory(String body) {
        int comma = body.indexOf(',');
        int colon = body.indexOf(':');
        int address = (int) Long.parseLong(body.substring(0, comma), 16);
        int length = Integer.parseInt(body.substring(comma + 1, colon), 16);
        String data = body.substring(colon + 1);
        for (int i = 0; i < length; i++) {
            memory.write8(address + i, Integer.parseInt(data.substring(i * 2, i * 2 + 2), 16));
        }
        return "OK";
    }

    // ---- breakpoints / watchpoints ----

    private String addBreakpoint(String body) {
        String[] parts = body.split(",");
        int type = Integer.parseInt(parts[0]);
        int address = (int) Long.parseLong(parts[1], 16);
        int length = parts.length > 2 ? Integer.parseInt(parts[2], 16) : 1;
        switch (type) {
            case 0, 1 -> breakpoints.add(address);          // sw/hw PC breakpoint
            case 2 -> watchpoints.add(new Watchpoint(address, length, readRegion(address, length))); // write
            default -> {
                return ""; // read/access watchpoints not supported
            }
        }
        return "OK";
    }

    private String removeBreakpoint(String body) {
        String[] parts = body.split(",");
        int type = Integer.parseInt(parts[0]);
        int address = (int) Long.parseLong(parts[1], 16);
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

    private long readRegion(int address, int length) {
        return switch (length) {
            case 1 -> memory.read8(address) & 0xFFL;
            case 2 -> memory.read16(address) & 0xFFFFL;
            case 4 -> memory.read32(address) & 0xFFFFFFFFL;
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
        } while (c != '$'); // skip acks/junk until packet start
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
        // The ack ('+') from the client is consumed by the next readPacket().
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

    private static int parseWordLe(String hex, int offset) {
        int b0 = Integer.parseInt(hex.substring(offset, offset + 2), 16);
        int b1 = Integer.parseInt(hex.substring(offset + 2, offset + 4), 16);
        int b2 = Integer.parseInt(hex.substring(offset + 4, offset + 6), 16);
        int b3 = Integer.parseInt(hex.substring(offset + 6, offset + 8), 16);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private enum Mode {
        HALTED, RUNNING, STEPPING, DETACH
    }

    private static final class Watchpoint {
        private final int address;
        private final int length;
        private long lastValue;

        private Watchpoint(int address, int length, long lastValue) {
            this.address = address;
            this.length = length;
            this.lastValue = lastValue;
        }
    }
}
