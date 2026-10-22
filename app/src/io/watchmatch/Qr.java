package io.watchmatch;

import java.nio.charset.StandardCharsets;

/**
 * A compact QR Code encoder: byte mode, error correction L, versions 1 to 6 (up to 134 bytes), the best of the eight
 * masks. Enough for the pairing link (https://watch-me-plus.vercel.app/?join=CODE is 47 bytes: version 3). No Android
 * (QrCheck runs it on a plain JVM). The method follows ISO/IEC 18004 and Nayuki's public-domain description of it.
 */
final class Qr {
    private Qr() {}

    /** Per version 1..6, ECC L: data codewords, ec codewords per block, blocks. */
    private static final int[][] SPEC = {
        {19, 7, 1}, {34, 10, 1}, {55, 15, 1}, {80, 20, 1}, {108, 26, 1}, {136, 18, 2},
    };
    /** The alignment pattern's second centre (the first is 6) for versions 2..6. */
    private static final int[] ALIGN = {0, 0, 18, 22, 26, 30, 34};
    /** ECC L's two format bits. */
    private static final int FORMAT_L = 1;

    static final int MAX_VERSION = 6;

    /** The version that holds `bytes` bytes, or 0 when it takes more than version 6. */
    static int versionFor(int bytes) {
        for (int v = 1; v <= MAX_VERSION; v++) if (bytes <= SPEC[v - 1][0] - 2) return v;
        return 0;
    }

    /** The module grid (true is dark, [row][column], no quiet zone), or null when the text is too long. */
    static boolean[][] encode(String text) {
        return encode(text, -1);
    }

    /** As encode(text), with mask 0 to 7 forced (-1: the best one); for checking against other encoders. */
    static boolean[][] encode(String text, int forcedMask) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = versionFor(data.length);
        if (version == 0) return null;
        byte[] codewords = interleave(dataCodewords(data, version), version);
        return build(codewords, version, forcedMask);
    }

    // ---- the data and its error correction --------------------------------------------------

    /** Mode (0100), the length, the bytes, the terminator and the 0xEC 0x11 padding, as codewords. */
    static byte[] dataCodewords(byte[] data, int version) {
        int capacity = SPEC[version - 1][0];
        BitBuffer b = new BitBuffer();
        b.put(0x4, 4);
        b.put(data.length, 8);
        for (byte x : data) b.put(x & 0xFF, 8);
        b.put(0, Math.min(4, capacity * 8 - b.length));
        b.put(0, (8 - b.length % 8) % 8);
        byte[] out = new byte[capacity];
        System.arraycopy(b.toBytes(), 0, out, 0, b.length / 8);
        for (int i = b.length / 8, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) out[i] = (byte) pad;
        return out;
    }

    /** The blocks' data, then their error correction, interleaved. */
    static byte[] interleave(byte[] data, int version) {
        int[] spec = SPEC[version - 1];
        int blocks = spec[2], ecLen = spec[1], per = data.length / blocks;
        byte[][] d = new byte[blocks][], e = new byte[blocks][];
        for (int i = 0; i < blocks; i++) {
            d[i] = java.util.Arrays.copyOfRange(data, i * per, (i + 1) * per);
            e[i] = reedSolomon(d[i], ecLen);
        }
        byte[] out = new byte[data.length + blocks * ecLen];
        int n = 0;
        for (int i = 0; i < per; i++) for (int j = 0; j < blocks; j++) out[n++] = d[j][i];
        for (int i = 0; i < ecLen; i++) for (int j = 0; j < blocks; j++) out[n++] = e[j][i];
        return out;
    }

    /** The `ecLen` Reed-Solomon check bytes of `data` over GF(256) (polynomial 0x11D). */
    static byte[] reedSolomon(byte[] data, int ecLen) {
        int[] gen = new int[ecLen];
        gen[ecLen - 1] = 1;
        int root = 1;
        for (int i = 0; i < ecLen; i++) {
            for (int j = 0; j < ecLen; j++) {
                gen[j] = multiply(gen[j], root);
                if (j + 1 < ecLen) gen[j] ^= gen[j + 1];
            }
            root = multiply(root, 2);
        }
        int[] rem = new int[ecLen];
        for (byte x : data) {
            int factor = (x & 0xFF) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecLen - 1);
            rem[ecLen - 1] = 0;
            for (int j = 0; j < ecLen; j++) rem[j] ^= multiply(gen[j], factor);
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) out[i] = (byte) rem[i];
        return out;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- the matrix -------------------------------------------------------------------------

    private static boolean[][] build(byte[] codewords, int version, int forcedMask) {
        int size = 17 + 4 * version;
        boolean[][] m = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(m, fn, version);
        place(m, fn, codewords);
        int best = 0;
        long bestScore = Long.MAX_VALUE;
        for (int mask = forcedMask >= 0 ? forcedMask : 0; mask < (forcedMask >= 0 ? forcedMask + 1 : 8); mask++) {
            applyMask(m, fn, mask);
            drawFormat(m, fn, mask);
            long score = penalty(m);
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
            applyMask(m, fn, mask);      // undo (xor)
        }
        applyMask(m, fn, best);
        drawFormat(m, fn, best);
        return m;
    }

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version) {
        int size = m.length;
        for (int i = 0; i < size; i++) {
            set(m, fn, 6, i, i % 2 == 0);       // timing: column 6
            set(m, fn, i, 6, i % 2 == 0);       // timing: row 6
        }
        finder(m, fn, 3, 3);
        finder(m, fn, size - 4, 3);
        finder(m, fn, 3, size - 4);
        if (version >= 2) {
            int[] at = {6, ALIGN[version]};
            for (int a : at) {
                for (int b : at) {
                    boolean corner = (a == 6 && b == 6) || (a == 6 && b == at[1]) || (a == at[1] && b == 6);
                    if (!corner) alignment(m, fn, a, b);
                }
            }
        }
        drawFormat(m, fn, 0);       // reserves the format areas
    }

    private static void finder(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < m.length && y >= 0 && y < m.length) set(m, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void alignment(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(m, fn, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    /** The 15 format bits for ECC L and `mask` (BCH(15,5), then xor 0x5412), most significant first. */
    static int formatBits(int mask) {
        int data = FORMAT_L << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return (data << 10 | rem) ^ 0x5412;
    }

    private static void drawFormat(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        int bits = formatBits(mask);
        for (int i = 0; i <= 5; i++) set(m, fn, 8, i, bit(bits, i));
        set(m, fn, 8, 7, bit(bits, 6));
        set(m, fn, 8, 8, bit(bits, 7));
        set(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, fn, 8, size - 15 + i, bit(bits, i));
        set(m, fn, 8, size - 8, true);      // the always-dark module
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    /** The codewords' bits in the zigzag of two-column strips from the bottom right, skipping the timing column. */
    private static void place(boolean[][] m, boolean[][] fn, byte[] codewords) {
        int size = m.length, i = 0, total = codewords.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < total) {
                        m[y][x] = bit(codewords[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    static boolean masked(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) if (!fn[y][x] && masked(mask, x, y)) m[y][x] ^= true;
        }
    }

    // ---- choosing the mask ------------------------------------------------------------------

    /** The standard four penalty rules: runs, 2x2 blocks, finder-like patterns and dark/light balance. */
    static long penalty(boolean[][] m) {
        int size = m.length;
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                StringBuilder line = new StringBuilder();
                for (int b = 0; b < size; b++) {
                    boolean cur = pass == 0 ? m[a][b] : m[b][a];
                    line.append(cur ? '1' : '0');
                    if (b > 0) {
                        boolean prev = pass == 0 ? m[a][b - 1] : m[b - 1][a];
                        if (cur == prev) {
                            run++;
                            if (run == 5) result += 3;
                            else if (run > 5) result++;
                        } else {
                            run = 1;
                        }
                    }
                }
                String s = "0000" + line + "0000";
                result += 40 * (count(s, "10111010000") + count(s, "00001011101"));
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : m) for (boolean c : row) if (c) dark++;
        int total = size * size;
        int k = (int) ((Math.abs(dark * 20L - total * 10L) + total - 1) / total) - 1;
        return result + Math.max(0, k) * 10L;
    }

    private static int count(String s, String pattern) {
        int n = 0;
        for (int i = s.indexOf(pattern); i >= 0; i = s.indexOf(pattern, i + 1)) n++;
        return n;
    }

    // ---- bits -------------------------------------------------------------------------------

    private static final class BitBuffer {
        private byte[] bytes = new byte[256];
        int length;

        void put(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (length >> 3 >= bytes.length) bytes = java.util.Arrays.copyOf(bytes, bytes.length * 2);
                if (((value >>> i) & 1) != 0) bytes[length >> 3] |= 0x80 >>> (length & 7);
                length++;
            }
        }

        byte[] toBytes() {
            return java.util.Arrays.copyOf(bytes, (length + 7) / 8);
        }
    }
}
