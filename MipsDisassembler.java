import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MipsDisassembler {

    private static final String[] REG = {
            "$zero", "$at", "$v0", "$v1", "$a0", "$a1", "$a2", "$a3",
            "$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7",
            "$s0", "$s1", "$s2", "$s3", "$s4", "$s5", "$s6", "$s7",
            "$t8", "$t9", "$k0", "$k1", "$gp", "$sp", "$fp", "$ra"
    };

    private final List<Integer> text = new ArrayList<>();
    private final List<Integer> dataWords = new ArrayList<>();
    private long textBase = 0x00400000L;
    private long dataBase = 0x10010000L;

    private final Map<Long, String> textLabels = new TreeMap<>();
    private final Map<Long, String> dataLabels = new TreeMap<>();

    // ---------------------------------------------------------------- input

    public void parse(List<String> lines) {
        boolean inData = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(".")) {
                String lower = line.toLowerCase();
                long addr = -1;
                int at = line.indexOf('@');
                if (at >= 0) addr = Long.decode(line.substring(at + 1).trim());

                if (lower.startsWith(".text")) {
                    inData = false;
                    if (addr >= 0) textBase = addr;
                } else if (lower.startsWith(".data")) {
                    inData = true;
                    if (addr >= 0) dataBase = addr;
                } else {
                    throw new IllegalArgumentException("Unknown directive: " + raw);
                }
                continue;
            }

            String bits = line.replaceAll("\\s+", "");
            if (!bits.matches("[01]{32}")) {
                throw new IllegalArgumentException("Expected 32 binary digits: " + raw);
            }
            int word = (int) Long.parseLong(bits, 2);
            (inData ? dataWords : text).add(word);
        }
    }

    // --------------------------------------------------------- disassembly

    public String disassemble() {
        int n = text.size();
        long textEnd = textBase + 4L * n;
        long dataEnd = dataBase + 4L * dataWords.size();

        // Pass 1a: find every branch/jump target so it can get a label.
        TreeSet<Long> targets = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            int w = text.get(i);
            long addr = textBase + 4L * i;
            int op = w >>> 26;
            if (op == 2 || op == 3) targets.add(jumpTarget(addr, w));
            else if (op == 1 || (op >= 4 && op <= 7)) targets.add(branchTarget(addr, w));
        }
        textLabels.put(textBase, "main");
        int k = 1;
        for (long t : targets) {
            if (t > textBase && t < textEnd && t % 4 == 0) textLabels.put(t, "L" + (k++));
        }

        // Pass 1b: find lui+ori pairs that build a data address (the "la" pseudoinstruction).
        boolean[] fuse = new boolean[n];
        TreeSet<Long> dataRefs = new TreeSet<>();
        for (int i = 0; i + 1 < n; i++) {
            int w = text.get(i), x = text.get(i + 1);
            if ((w >>> 26) != 0x0F || (x >>> 26) != 0x0D) continue;
            int rt = (w >>> 16) & 31;
            if (rt == 0 || ((x >>> 21) & 31) != rt || ((x >>> 16) & 31) != rt) continue;
            long a = ((long) (w & 0xFFFF) << 16) | (x & 0xFFFF);
            long nextAddr = textBase + 4L * (i + 1);
            if (a >= dataBase && a < dataEnd && !textLabels.containsKey(nextAddr)) {
                fuse[i] = true;
                dataRefs.add(a);
            }
        }
        int d = 1;
        for (long a : dataRefs) dataLabels.put(a, "data" + (d++));

        // Pass 2: emit.
        StringBuilder sb = new StringBuilder();
        if (!dataWords.isEmpty()) {
            sb.append(".data\n");
            emitData(sb);
            sb.append("\n");
        }
        sb.append(".text\n.globl main\n");

        int i = 0;
        while (i < n) {
            long addr = textBase + 4L * i;
            String label = textLabels.get(addr);
            if (label != null) sb.append("\n").append(label).append(":\n");

            int w = text.get(i);
            if (fuse[i]) {
                int x = text.get(i + 1);
                long a = ((long) (w & 0xFFFF) << 16) | (x & 0xFFFF);
                sb.append("    la ").append(REG[(w >>> 16) & 31]).append(", ")
                        .append(dataLabels.get(a)).append("\n");
                i += 2;
            } else {
                sb.append("    ").append(decode(w, addr)).append("\n");
                i++;
            }
        }
        return sb.toString();
    }

    private void emitData(StringBuilder sb) {
        // Each input line holds 4 consecutive bytes, written in memory order.
        byte[] bytes = new byte[dataWords.size() * 4];
        for (int i = 0; i < dataWords.size(); i++) {
            int w = dataWords.get(i);
            bytes[4 * i]     = (byte) (w >>> 24);
            bytes[4 * i + 1] = (byte) (w >>> 16);
            bytes[4 * i + 2] = (byte) (w >>> 8);
            bytes[4 * i + 3] = (byte) w;
        }

        int pos = 0;
        while (pos < bytes.length) {
            if (allZeroFrom(bytes, pos)) break;       // trailing alignment padding

            String label = dataLabels.get(dataBase + pos);
            sb.append(label != null ? label + ": " : "");

            int end = pos;
            while (end < bytes.length && bytes[end] != 0) end++;

            if (end == pos) {                          // stray zero byte
                sb.append(".byte 0\n");
                pos++;
            } else if (isText(bytes, pos, end) && end < bytes.length) {
                sb.append(".asciiz \"").append(escape(bytes, pos, end)).append("\"\n");
                pos = end + 1;
            } else {                                   // not a clean string: raw bytes
                sb.append(".byte ");
                for (int j = pos; j < end; j++) {
                    sb.append(bytes[j] & 0xFF).append(j + 1 < end ? ", " : "");
                }
                sb.append("\n");
                pos = end;
            }
        }
    }

    private static boolean allZeroFrom(byte[] b, int from) {
        for (int i = from; i < b.length; i++) if (b[i] != 0) return false;
        return true;
    }

    private static boolean isText(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            int c = b[i] & 0xFF;
            if (!((c >= 32 && c < 127) || c == '\n' || c == '\t')) return false;
        }
        return true;
    }

    private static String escape(byte[] b, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < to; i++) {
            char c = (char) (b[i] & 0xFF);
            switch (c) {
                case '\n': s.append("\\n"); break;
                case '\t': s.append("\\t"); break;
                case '"':  s.append("\\\""); break;
                case '\\': s.append("\\\\"); break;
                default:   s.append(c);
            }
        }
        return s.toString();
    }

    // ------------------------------------------------------ instruction decode

    private String decode(int w, long addr) {
        int op = w >>> 26;
        int rs = (w >>> 21) & 31, rt = (w >>> 16) & 31, rd = (w >>> 11) & 31;
        int sh = (w >>> 6) & 31, fn = w & 63;
        int simm = (short) w;       // sign-extended 16-bit immediate
        int uimm = w & 0xFFFF;      // zero-extended 16-bit immediate

        switch (op) {
            case 0x00: return decodeR(w, rs, rt, rd, sh, fn);
            case 0x01:
                if (rt == 0) return "bltz " + R(rs) + ", " + label(branchTarget(addr, w));
                if (rt == 1) return "bgez " + R(rs) + ", " + label(branchTarget(addr, w));
                break;
            case 0x02: return "j "   + label(jumpTarget(addr, w));
            case 0x03: return "jal " + label(jumpTarget(addr, w));
            case 0x04: return "beq " + R(rs) + ", " + R(rt) + ", " + label(branchTarget(addr, w));
            case 0x05: return "bne " + R(rs) + ", " + R(rt) + ", " + label(branchTarget(addr, w));
            case 0x06: return "blez " + R(rs) + ", " + label(branchTarget(addr, w));
            case 0x07: return "bgtz " + R(rs) + ", " + label(branchTarget(addr, w));
            case 0x08: return "addi "  + R(rt) + ", " + R(rs) + ", " + simm;
            case 0x09:
                if (rs == 0) return "li " + R(rt) + ", " + simm;
                return "addiu " + R(rt) + ", " + R(rs) + ", " + simm;
            case 0x0A: return "slti "  + R(rt) + ", " + R(rs) + ", " + simm;
            case 0x0B: return "sltiu " + R(rt) + ", " + R(rs) + ", " + simm;
            case 0x0C: return "andi "  + R(rt) + ", " + R(rs) + ", " + uimm;
            case 0x0D: return "ori "   + R(rt) + ", " + R(rs) + ", " + uimm;
            case 0x0E: return "xori "  + R(rt) + ", " + R(rs) + ", " + uimm;
            case 0x0F: return "lui "   + R(rt) + ", 0x" + Integer.toHexString(uimm);
            case 0x1C:
                if (fn == 0x02) return "mul " + R(rd) + ", " + R(rs) + ", " + R(rt);
                break;
            default: {
                String m = memMnemonic(op);
                if (m != null) return m + " " + R(rt) + ", " + simm + "(" + R(rs) + ")";
            }
        }
        return String.format(".word 0x%08x", w);
    }

    private String decodeR(int w, int rs, int rt, int rd, int sh, int fn) {
        switch (fn) {
            case 0x00:
                if (w == 0) return "nop";
                return "sll " + R(rd) + ", " + R(rt) + ", " + sh;
            case 0x02: return "srl " + R(rd) + ", " + R(rt) + ", " + sh;
            case 0x03: return "sra " + R(rd) + ", " + R(rt) + ", " + sh;
            case 0x04: return "sllv " + R(rd) + ", " + R(rt) + ", " + R(rs);
            case 0x06: return "srlv " + R(rd) + ", " + R(rt) + ", " + R(rs);
            case 0x07: return "srav " + R(rd) + ", " + R(rt) + ", " + R(rs);
            case 0x08: return "jr " + R(rs);
            case 0x09: return rd == 31 ? "jalr " + R(rs) : "jalr " + R(rd) + ", " + R(rs);
            case 0x0C: return "syscall";
            case 0x10: return "mfhi " + R(rd);
            case 0x11: return "mthi " + R(rs);
            case 0x12: return "mflo " + R(rd);
            case 0x13: return "mtlo " + R(rs);
            case 0x18: return "mult "  + R(rs) + ", " + R(rt);
            case 0x19: return "multu " + R(rs) + ", " + R(rt);
            case 0x1A: return "div "   + R(rs) + ", " + R(rt);
            case 0x1B: return "divu "  + R(rs) + ", " + R(rt);
            case 0x21:
                if (rt == 0) return "move " + R(rd) + ", " + R(rs);
                return "addu " + R(rd) + ", " + R(rs) + ", " + R(rt);
            default: {
                String m = threeReg(fn);
                if (m != null) return m + " " + R(rd) + ", " + R(rs) + ", " + R(rt);
            }
        }
        return String.format(".word 0x%08x", w);
    }

    private static String threeReg(int fn) {
        switch (fn) {
            case 0x20: return "add";
            case 0x22: return "sub";
            case 0x23: return "subu";
            case 0x24: return "and";
            case 0x25: return "or";
            case 0x26: return "xor";
            case 0x27: return "nor";
            case 0x2A: return "slt";
            case 0x2B: return "sltu";
            default:   return null;
        }
    }

    private static String memMnemonic(int op) {
        switch (op) {
            case 0x20: return "lb";
            case 0x21: return "lh";
            case 0x23: return "lw";
            case 0x24: return "lbu";
            case 0x25: return "lhu";
            case 0x28: return "sb";
            case 0x29: return "sh";
            case 0x2B: return "sw";
            default:   return null;
        }
    }

    // -------------------------------------------------------------- helpers

    private static String R(int n) { return REG[n]; }

    private static long branchTarget(long addr, int w) {
        return addr + 4 + ((long) (short) w << 2);
    }

    private static long jumpTarget(long addr, int w) {
        return ((addr + 4) & 0xF0000000L) | ((long) (w & 0x03FFFFFF) << 2);
    }

    private String label(long target) {
        String l = textLabels.get(target);
        return l != null ? l : String.format("0x%08x", target);
    }

    // ----------------------------------------------------------------- main

    public static void main(String[] args) throws IOException {
        List<String> lines;
        if (args.length > 0) {
            lines = Files.readAllLines(Paths.get(args[0]));
        } else {
            lines = new ArrayList<>();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String s; (s = in.readLine()) != null; ) lines.add(s);
        }
        MipsDisassembler dis = new MipsDisassembler();
        dis.parse(lines);
        System.out.print(dis.disassemble());
    }
}