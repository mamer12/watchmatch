package io.watchmatch;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-Java checks of Adb framing/keys, Watchdog's window, HomeMode's target and service lists. From the root:
 *
 *   mkdir -p out/test && javac -cp $ANDROID_JAR -d out/test app/src/io/watchmatch/{Adb,Watchdog,HomeMode,ModeJob}.java \
 *       app/test/io/watchmatch/ModeLogicCheck.java out/gen/io/watchmatch/Config.java ... && java -cp out/test io.watchmatch.ModeLogicCheck
 */
public final class ModeLogicCheck {
    private static int failed;

    static void ok(boolean c, String what) { if (!c) { failed++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

    public static void main(String[] a) throws Exception {
        // Framing
        byte[] data = {1, 2, (byte) 255};
        ByteBuffer b = ByteBuffer.wrap(Adb.frame(0x4e584e43, 7, 8, data)).order(ByteOrder.LITTLE_ENDIAN);
        ok(b.capacity() == 27 && b.getInt() == 0x4e584e43 && b.getInt() == 7 && b.getInt() == 8 && b.getInt() == 3
                && b.getInt() == 258 && b.getInt() == ~0x4e584e43, "frame header");
        ok(Adb.checksum(new byte[] {(byte) 200, (byte) 200}) == 400, "checksum uses unsigned bytes");
        // Signing block
        byte[] tok = new byte[20]; Arrays.fill(tok, (byte) 9);
        byte[] blk = Adb.signingBlock(tok);
        ok(blk.length == 256 && blk[0] == 0 && blk[1] == 1 && blk[2] == (byte) 0xff && blk[220] == 0x00 && blk[221] == 0x30
                && blk[255] == 9 && blk[235] == 0x14, "signing block layout");
        // Public key struct
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
        byte[] st = Adb.publicKeyStruct(pk);
        ByteBuffer s = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN);
        ok(st.length == 524 && s.getInt() == 64, "key struct length");
        int n0inv = s.getInt();
        byte[] nle = new byte[256]; s.get(nle);
        BigInteger n = new BigInteger(1, rev(nle));
        ok(n.equals(pk.getModulus()), "modulus little-endian");
        ok((int) (n.intValue() * n0inv) == -1, "n0inv: n0 * n0inv == -1 mod 2^32");
        byte[] rr = new byte[256]; s.get(rr);
        ok(new BigInteger(1, rev(rr)).equals(BigInteger.ONE.shiftLeft(4096).mod(n)), "rr = 2^4096 mod n");
        ok(s.getInt() == 65537, "exponent");
        byte[] blob = Adb.publicKeyBlob(pk);
        ok(blob[blob.length - 1] == 0 && new String(blob).contains(" watchmatch@tv"), "blob name + NUL");
        // Watchdog
        long now = 10_000_000L;
        ok(Watchdog.parse("9999000\nx\n  9900000 \n" + (now + 5) + "\n" + (now - 300000) + "\n" + (now - 299999) + "\n", now).size() == 3,
                "watchdog window drops old, junk and future");
        ok(Watchdog.recent(Arrays.asList(now - 1L, now - 2L), now).size() == 2, "two crashes < limit");
        // HomeMode
        List<String> t = HomeMode.targets("com.google.android.tvlauncher/.Home", "com.x.dream/.D", true, true);
        ok(t.subList(0, 4).equals(Arrays.asList("com.google.android.tvlauncher", HomeMode.TV_RECOMMENDATIONS,
                HomeMode.GOOGLE_SETUP, "com.x.dream")), "targets order");
        ok(t.contains("com.google.android.katniss") && t.contains("com.tcl.logkit"), "targets lists");
        ok(!HomeMode.targets("io.watchmatch/.HomeActivity", "io.watchmatch/.Screensaver", false, false).contains("io.watchmatch"),
                "targets skip ourselves");
        ok(HomeMode.targets("a.b/.C", "a.b/.D", false, false).indexOf("a.b") == 0
                && HomeMode.targets("a.b/.C", "a.b/.D", false, false).lastIndexOf("a.b") == 0, "dream same package not repeated");
        ok(HomeMode.mergeServices("null", Arrays.asList("io.watchmatch/io.watchmatch.RemoteKeyService")).size() == 1, "merge from null");
        ok(HomeMode.mergeServices("a.b/.C:io.watchmatch/io.watchmatch.RemoteKeyService:", Arrays.<String>asList()).equals(Arrays.asList("a.b/.C")),
                "merge drops ours");
        ok(HomeMode.mergeServices("a.b/.C;rm -rf", Arrays.<String>asList()) == null, "merge rejects unsafe entry");
        ok(HomeMode.parsePackages("package:a.b\npackage:c.d\nnoise\n").size() == 2, "parse packages");
        ok(HomeMode.median(Arrays.asList(30L, 10L, 20L)) == 20 && HomeMode.median(Arrays.asList(5L, 9L)) == 9, "median");
        ok(ModeJob.jobId("apply") >= 1000 && ModeJob.jobId("apply") < 2000, "job id range");
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    private static byte[] rev(byte[] x) { byte[] r = new byte[x.length]; for (int i = 0; i < x.length; i++) r[i] = x[x.length - 1 - i]; return r; }
}
