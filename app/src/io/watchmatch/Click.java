package io.watchmatch;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** The home screen's two soft sounds, synthesized once into the cache and played quietly. */
final class Click {
    private static final float MOVE_VOLUME = 0.14f, OPEN_VOLUME = 0.18f;

    private SoundPool pool;
    private int moveId, openId;

    Click(Context c) {
        try {
            File move = ensure(c, "click-move.wav", 1450, 0.009, 0.030);
            File open = ensure(c, "click-open.wav", 880, 0.022, 0.090);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool p = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
            pool = p;
            moveId = p.load(move.getPath(), 1);
            openId = p.load(open.getPath(), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        play(moveId, MOVE_VOLUME);
    }

    void open() {
        play(openId, OPEN_VOLUME);
    }

    void release() {
        SoundPool p = pool;
        pool = null;
        if (p != null) p.release();
    }

    private void play(int id, float volume) {
        SoundPool p = pool;
        if (p != null) p.play(id, volume, volume, 1, 0, 1f);
    }

    private static File ensure(Context c, String name, double hz, double decay, double seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (!f.exists() || f.length() <= 44) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(Tone.wav(hz, decay, seconds));
            }
        }
        return f;
    }

    /** The synthesis, apart from the Android parts so it can be checked in plain Java. */
    static final class Tone {
        static final int RATE = 48000;

        private Tone() {}

        /** Mono 16-bit PCM WAV: a sine plus a faint inharmonic partial, 1.5 ms attack, exponential decay. */
        static byte[] wav(double hz, double decay, double seconds) {
            int n = (int) Math.round(RATE * seconds);
            byte[] b = new byte[44 + 2 * n];
            put(b, 0, "RIFF");
            int4(b, 4, 36 + 2 * n);
            put(b, 8, "WAVE");
            put(b, 12, "fmt ");
            int4(b, 16, 16);
            int2(b, 20, 1);
            int2(b, 22, 1);
            int4(b, 24, RATE);
            int4(b, 28, RATE * 2);
            int2(b, 32, 2);
            int2(b, 34, 16);
            put(b, 36, "data");
            int4(b, 40, 2 * n);
            for (int i = 0; i < n; i++) {
                double t = (double) i / RATE;
                double env = Math.min(1, t / 0.0015) * Math.exp(-t / decay);
                double s = 0.75 * Math.sin(2 * Math.PI * hz * t) + 0.25 * Math.sin(2 * Math.PI * hz * 2.01 * t);
                int v = (int) Math.round(s * env * 0.7 * 32767);
                int2(b, 44 + 2 * i, v);
            }
            return b;
        }

        private static void put(byte[] b, int at, String s) {
            for (int i = 0; i < s.length(); i++) b[at + i] = (byte) s.charAt(i);
        }

        private static void int2(byte[] b, int at, int v) {
            b[at] = (byte) v;
            b[at + 1] = (byte) (v >> 8);
        }

        private static void int4(byte[] b, int at, int v) {
            int2(b, at, v);
            int2(b, at + 2, v >> 16);
        }
    }
}
