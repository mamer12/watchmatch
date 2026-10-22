package io.watchmatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the QR encoder on a plain JVM: Reed-Solomon and format-bit vectors from the standard, the version and capacity
 * limits, the fixed patterns, a decoder written here that reads the symbol back (format bits, mask, data and error
 * correction, both blocks of version 6), and vectors cross-checked once against an independent encoder (segno) for all
 * eight masks. From the repository's root:
 *
 *   mkdir -p out/test && javac -d out/test app/src/io/watchmatch/Qr.java app/test/io/watchmatch/QrCheck.java \
 *       && java -cp out/test io.watchmatch.QrCheck
 */
public final class QrCheck {
    private static int passed, failed;

    private static void eq(String what, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    private static final String[] FORMAT_L = {"111011111000100", "111001011110011", "111110110101010", "111100010011101",
        "110011000101111", "110001100011000", "110110001000001", "110100101110110"};
    private static final String JOIN_URL = "https://watch-me-plus.vercel.app/?join=K7M2QX4P";
    /** Version 1, "abc", mask 0, from segno. */
    private static final String[] ABC_MASK0 = {
        "111111100101101111111", "100000100111001000001", "101110101101101011101", "101110100101001011101",
        "101110100010101011101", "100000100000101000001", "111111101010101111111", "000000001101100000000",
        "111011111111011000100", "111100010110001001011", "111101100100100010011", "001100001000001000000",
        "111011111000101010001", "000000001111010101011", "111111101001011101011", "100000101101110111001",
        "101110101011011100101", "101110100100001000110", "101110101000100010001", "100000101010001000110",
        "111111101010101010111"};
    /** SHA-256 of all eight masks of the join link, as segno made them (rows joined by newlines, "MASK n" before each). */
    private static final String JOIN_HASH = "2e2b8597b7a0ff2663ac2b9782b4f69d5307fcb97270d232cdf885149cc2b278";

    public static void main(String[] args) throws Exception {
        vectors();
        limits();
        patterns();
        roundTrips();
        crossChecked();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String bits15(int v) {
        return String.format("%15s", Integer.toBinaryString(v)).replace(' ', '0');
    }

    private static void vectors() {
        for (int m = 0; m < 8; m++) eq("format bits, L, mask " + m, FORMAT_L[m], bits15(Qr.formatBits(m)));
        // The standard's "HELLO WORLD" example: its 10 error correction bytes.
        byte[] data = {32, 91, 11, 120, (byte) 209, 114, (byte) 220, 77, 67, 64, (byte) 236, 17, (byte) 236, 17, (byte) 236, 17};
        int[] want = {196, 35, 39, 119, 235, 215, 231, 226, 93, 23};
        byte[] ec = Qr.reedSolomon(data, 10);
        for (int i = 0; i < want.length; i++) eq("reed-solomon byte " + i, want[i], ec[i] & 0xFF);
        // "abc": mode 0100, length 3, the bytes, the terminator, then 0xEC 0x11 padding.
        byte[] cw = Qr.dataCodewords("abc".getBytes(StandardCharsets.UTF_8), 1);
        eq("abc data codewords (first 16)", "40 36 16 26 30 ec 11 ec 11 ec 11 ec 11 ec 11 ec", hex(cw));
        eq("one error-correction block adds 7", 26, Qr.interleave(cw, 1).length);
        eq("version 6 has two blocks and 172 codewords", 172, Qr.interleave(new byte[136], 6).length);
    }

    private static void limits() {
        int[][] capacity = {{1, 17}, {2, 32}, {3, 53}, {4, 78}, {5, 106}, {6, 134}};
        for (int[] c : capacity) {
            eq("version " + c[0] + " holds " + c[1], c[0], Qr.versionFor(c[1]));
            eq("one more byte needs a bigger one (" + c[0] + ")", c[0] == 6 ? 0 : c[0] + 1, Qr.versionFor(c[1] + 1));
        }
        eq("too long is refused", null, Qr.encode(repeat('a', 135)));
        eq("the join link is 47 bytes: version 3", 3, Qr.versionFor(JOIN_URL.length()));
        eq("version 3 is 29 modules", 29, Qr.encode(JOIN_URL).length);
        eq("empty text still works", 21, Qr.encode("").length);
    }

    private static void patterns() {
        boolean[][] m = Qr.encode(JOIN_URL);
        int n = m.length;
        boolean finders = true;
        for (int[] o : new int[][] {{0, 0}, {n - 7, 0}, {0, n - 7}}) {
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 7; x++) {
                    boolean ring = x == 0 || y == 0 || x == 6 || y == 6, core = x >= 2 && x <= 4 && y >= 2 && y <= 4;
                    if (m[o[1] + y][o[0] + x] != (ring || core)) finders = false;
                }
            }
        }
        eq("three finder patterns", true, finders);
        boolean timing = true;
        for (int i = 8; i < n - 8; i++) if (m[6][i] != (i % 2 == 0) || m[i][6] != (i % 2 == 0)) timing = false;
        eq("timing patterns alternate", true, timing);
        eq("the dark module", true, m[n - 8][8]);
        boolean align = m[22][22] && !m[22][23] && m[20][20] && m[24][24] && m[22][20];
        eq("the alignment pattern of version 3", true, align);
        eq("a separator is light", false, m[7][0]);
    }

    private static void roundTrips() {
        String[] texts = {"", "a", "abc", "hello world abc", JOIN_URL, repeat('x', 17), repeat('y', 32), repeat('z', 53), repeat('q', 78),
            repeat('r', 106), repeat('Z', 134), "çã é ✓ unicode"};
        for (String t : texts) {
            for (int mask : new int[] {-1, 0, 3, 7}) {
                boolean[][] m = Qr.encode(t, mask);
                String got = decode(m);
                eq("decodes \"" + (t.length() > 12 ? t.substring(0, 12) + "..." : t) + "\" (mask " + mask + ")", t, got);
            }
        }
    }

    private static void crossChecked() throws Exception {
        boolean[][] abc = Qr.encode("abc", 0);
        for (int y = 0; y < ABC_MASK0.length; y++) eq("abc mask 0 row " + y, ABC_MASK0[y], row(abc[y]));
        StringBuilder all = new StringBuilder();
        for (int mask = 0; mask < 8; mask++) {
            all.append("MASK ").append(mask).append('\n');
            for (boolean[] r : Qr.encode(JOIN_URL, mask)) all.append(row(r)).append('\n');
            all.append('\n');
        }
        byte[] sum = MessageDigest.getInstance("SHA-256").digest(all.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder h = new StringBuilder();
        for (byte b : sum) h.append(String.format("%02x", b & 0xFF));
        eq("the join link's eight masks match the independent encoder", JOIN_HASH, h.toString());
    }

    // ---- a decoder, written separately from the encoder's placement ------------------------

    /** The text in a symbol (version 1 to 6, ECC L), or "?" plus the reason. Checks the error correction too. */
    private static String decode(boolean[][] m) {
        int n = m.length, version = (n - 17) / 4;
        // The format bits, from the copy around the top-left finder.
        int bits = 0;
        int[][] pos = {{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8}, {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8}};
        for (int i = 0; i < 15; i++) if (m[pos[i][1]][pos[i][0]]) bits |= 1 << i;
        int format = bits ^ 0x5412;
        if ((format >> 13) != 1) return "?bad ecc";
        int mask = (format >> 10) & 7;
        boolean[][] fn = new boolean[n][n];
        reserve(fn, version);
        // Read the data and error-correction bits in the zigzag, unmasked.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int cur = 0, count = 0;
        boolean up = true;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int i = 0; i < n; i++) {
                int y = up ? n - 1 - i : i;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (fn[y][x]) continue;
                    boolean bit = m[y][x] ^ Qr.masked(mask, x, y);
                    cur = cur << 1 | (bit ? 1 : 0);
                    if (++count == 8) {
                        out.write(cur);
                        cur = count = 0;
                    }
                }
            }
            up = !up;
        }
        byte[] all = out.toByteArray();
        int[][] spec = {{19, 7, 1}, {34, 10, 1}, {55, 15, 1}, {80, 20, 1}, {108, 26, 1}, {136, 18, 2}};
        int dataLen = spec[version - 1][0], ecLen = spec[version - 1][1], blocks = spec[version - 1][2];
        if (all.length < dataLen + blocks * ecLen) return "?short";
        int per = dataLen / blocks;
        byte[] data = new byte[dataLen];
        for (int b = 0; b < blocks; b++) {
            byte[] block = new byte[per];
            byte[] ec = new byte[ecLen];
            for (int i = 0; i < per; i++) block[i] = all[i * blocks + b];
            for (int i = 0; i < ecLen; i++) ec[i] = all[dataLen + i * blocks + b];
            byte[] again = Qr.reedSolomon(block, ecLen);
            for (int i = 0; i < ecLen; i++) if (again[i] != ec[i]) return "?error correction differs";
            System.arraycopy(block, 0, data, b * per, per);
        }
        // Mode 0100, the count, the bytes.
        if ((data[0] & 0xF0) != 0x40) return "?not byte mode";
        int len = (data[0] & 0x0F) << 4 | (data[1] & 0xF0) >> 4;
        byte[] text = new byte[len];
        for (int i = 0; i < len; i++) text[i] = (byte) ((data[1 + i] & 0x0F) << 4 | (data[2 + i] & 0xF0) >> 4);
        return new String(text, StandardCharsets.UTF_8);
    }

    private static void reserve(boolean[][] fn, int version) {
        int n = fn.length;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean finder = (x < 9 && y < 9) || (x >= n - 8 && y < 9) || (x < 9 && y >= n - 8);
                if (finder || x == 6 || y == 6) fn[y][x] = true;
            }
        }
        if (version >= 2) {
            int c = new int[] {0, 0, 18, 22, 26, 30, 34}[version];
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) fn[c + dy][c + dx] = true;
        }
    }

    private static String row(boolean[] r) {
        StringBuilder b = new StringBuilder();
        for (boolean c : r) b.append(c ? '1' : '0');
        return b.toString();
    }

    private static String hex(byte[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(a.length, 16); i++) b.append(i > 0 ? " " : "").append(String.format("%02x", a[i] & 0xFF));
        return b.toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }
}
