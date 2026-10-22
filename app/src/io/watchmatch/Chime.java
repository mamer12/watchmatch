package io.watchmatch;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Voice search's own soft chimes, made like Click's sounds (synthesized once into WAVs in the cache, played quietly
 * through SoundPool): rising when it starts listening, a single high note when it heard you, falling when it stops
 * without a sentence. Never touches the TV's volume.
 */
final class Chime {
    private static final int RATE = 48_000;
    private static final float NOTE_S = 0.07f, TAIL_S = 0.05f, VOLUME = 0.22f;
    private static final float LOW = 880f, HIGH = 1320f;
    private SoundPool pool;
    private int start, done, stop;

    Chime(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            start = pool.load(wav(c, "chime-start-1.wav", LOW, HIGH), 1);
            done = pool.load(wav(c, "chime-done-1.wav", HIGH), 1);
            stop = pool.load(wav(c, "chime-stop-1.wav", HIGH, LOW), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("voice chimes unavailable: " + e.getMessage());
            release();
        }
    }

    void start() { play(start); }

    void done() { play(done); }

    void stop() { play(stop); }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    private void play(int id) {
        if (pool != null) pool.play(id, VOLUME, VOLUME, 1, 0, 1f);
    }

    /** The notes one after the other, each ringing on under the next: 3 ms attack, a soft exponential decay. */
    private static String wav(Context c, String name, float... notes) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int per = Math.round(RATE * NOTE_S), n = per * notes.length + Math.round(RATE * TAIL_S);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < notes.length; j++) {
                int from = j * per;
                if (i < from) break;
                double t = (double) (i - from) / RATE;
                double env = Math.min(1, t / 0.003) * Math.exp(-t / 0.055);
                s += env * (0.85 * Math.sin(2 * Math.PI * notes[j] * t) + 0.15 * Math.sin(2 * Math.PI * notes[j] * 2 * t));
            }
            // A 4 ms fade at the very end: no click.
            double end = Math.min(1, (double) (n - i) / (RATE * 0.004));
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, s * 0.5 * end)) * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
