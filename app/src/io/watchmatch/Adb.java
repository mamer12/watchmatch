package io.watchmatch;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * A tiny client for the TV's own adbd on loopback. It lets the app run fixed shell commands (settings, pm, appops)
 * without a computer. One Adb per thread: it is not thread-safe.
 */
final class Adb implements Closeable {
    interface Approval { void waiting(); }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_KEY = 3;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024, MAX_MESSAGE = 1 << 20;
    private static final int PORT = 5555, CONNECT_MS = 3000, HANDSHAKE_MS = 10000, SHELL_MS = 20000;
    private static final String KEY_NAME = "watchmatch@tv";
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final Object KEY_LOCK = new Object();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextLocal = 1;

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    static Adb connect(Context c, int approvalMs, Approval approval) throws IOException {
        KeyPair keys = loadKeys(c.getFilesDir());
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), CONNECT_MS);
        } catch (IOException e) {
            closeQuietly(s);
            throw new IOException("debugging is off on the TV (port " + PORT + " closed)");
        }
        boolean offered = false;
        try {
            s.setSoTimeout(HANDSHAKE_MS);
            Adb adb = new Adb(s);
            adb.send(CNXN, VERSION, MAX_DATA, ascii("host::\0"));
            int tokens = 0;
            while (true) {
                Msg m;
                try {
                    m = adb.read();
                } catch (SocketTimeoutException e) {
                    throw new IOException(offered ? "debugging permission wasn't accepted on the TV"
                            : "the TV's ADB didn't respond");
                }
                if (m.cmd == CNXN) {
                    s.setSoTimeout(SHELL_MS);
                    return adb;
                }
                if (m.cmd == STLS) throw new IOException("the TV asked for TLS (wireless debugging); use USB debugging");
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply from ADB");
                tokens++;
                if (tokens == 1) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                } else if (tokens == 2) {
                    adb.send(AUTH, AUTH_KEY, 0, publicKeyBlob((RSAPublicKey) keys.getPublic()));
                    offered = true;
                    if (approval != null) approval.waiting();
                    s.setSoTimeout(approvalMs);
                } else {
                    throw new IOException("the TV rejected the app's key");
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e;
        }
    }

    String shell(String command) throws IOException {
        int local = nextLocal++;
        send(OPEN, local, 0, ascii("shell:" + command + "\0"));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                text.write(m.data, 0, m.data.length);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == CLSE) {
                if (remote == 0 && m.arg0 == 0) throw new IOException("ADB refused the command");
                send(CLSE, local, m.arg0, new byte[0]);
                return new String(text.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    @Override public void close() {
        closeQuietly(socket);
    }

    // ---- Messages

    private static final class Msg {
        final int cmd, arg0, arg1;
        final byte[] data;
        Msg(int cmd, int arg0, int arg1, byte[] data) { this.cmd = cmd; this.arg0 = arg0; this.arg1 = arg1; this.data = data; }
    }

    static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        return sum;
    }

    /** Header plus data, as it goes on the wire. */
    static byte[] frame(int cmd, int arg0, int arg1, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(cmd ^ 0xFFFFFFFF);
        b.put(data);
        return b.array();
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        out.write(frame(cmd, arg0, arg1, data));
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] head = new byte[24];
        ((DataInputStream) in).readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), len = b.getInt();
        b.getInt();
        int magic = b.getInt();
        if (magic != (cmd ^ 0xFFFFFFFF)) throw new IOException("invalid ADB message");
        if (len < 0 || len > MAX_MESSAGE) throw new IOException("ADB message too large");
        byte[] data = new byte[len];
        ((DataInputStream) in).readFully(data);
        return new Msg(cmd, arg0, arg1, data);
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    // ---- Signing and the public key

    /** adbd wants the 20-byte token signed as if it were already a SHA-1 digest. */
    static byte[] signingBlock(byte[] token) {
        byte[] block = new byte[256];
        int fill = block.length - 3 - SHA1_PREFIX.length - token.length;
        block[1] = 1;
        Arrays.fill(block, 2, 2 + fill, (byte) 0xff);
        int at = 3 + fill;
        System.arraycopy(SHA1_PREFIX, 0, block, at, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, at + SHA1_PREFIX.length, token.length);
        return block;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            return rsa.doFinal(signingBlock(token));
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB signature: " + e.getMessage());
        }
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[size];
        for (int i = 0; i < size && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    /** adbd's RSAPublicKey struct: 64 words, n0inv, modulus, 2^4096 mod n, exponent (524 bytes, little-endian). */
    static byte[] publicKeyStruct(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = two32.subtract(n.mod(two32).modInverse(two32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv).put(littleEndian(n, 256)).put(littleEndian(rr, 256)).putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    static byte[] publicKeyBlob(RSAPublicKey key) {
        String text = java.util.Base64.getEncoder().encodeToString(publicKeyStruct(key)) + " " + KEY_NAME + "\0";
        return ascii(text);
    }

    // ---- Key storage

    private static KeyPair loadKeys(File dir) throws IOException {
        synchronized (KEY_LOCK) {
            File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
            try {
                if (priv.exists() && pub.exists()) {
                    KeyFactory f = KeyFactory.getInstance("RSA");
                    PrivateKey sk = f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
                    PublicKey pk = f.generatePublic(new X509EncodedKeySpec(readAll(pub)));
                    if (pk instanceof RSAPublicKey) return new KeyPair(pk, sk);
                }
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                // fall through: a partial write after a power cut leaves an unreadable pair
            }
            if (priv.exists() || pub.exists()) AppLog.w("ADB key unreadable, generating a new one");
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                KeyPair pair = g.generateKeyPair();
                writeAtomically(priv, pair.getPrivate().getEncoded());
                writeAtomically(pub, pair.getPublic().getEncoded());
                return pair;
            } catch (GeneralSecurityException e) {
                throw new IOException("ADB key: " + e.getMessage());
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream s = new FileInputStream(f)) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) all.write(buf, 0, n);
            return all.toByteArray();
        }
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream s = new FileOutputStream(tmp)) {
            s.write(bytes);
            s.flush();
            s.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("could not save " + target.getName());
    }
}
