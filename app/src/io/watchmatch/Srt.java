package io.watchmatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SubRip subtitles: downloaded, read (UTF-8, or Windows-1256 for older Arabic files) and looked up by time. */
final class Srt {
    private static final Pattern TIME = Pattern.compile(
            "(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)");

    private final long[] starts, ends;
    private final String[] texts;

    private Srt(List<long[]> times, List<String> lines) {
        starts = new long[times.size()];
        ends = new long[times.size()];
        texts = lines.toArray(new String[0]);
        for (int i = 0; i < times.size(); i++) {
            starts[i] = times.get(i)[0];
            ends[i] = times.get(i)[1];
        }
    }

    /** The text on screen at `ms`, or null. */
    String at(long ms) {
        int lo = 0, hi = starts.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // Cues can overlap: the latest one that started and hasn't ended.
        for (int i = found; i >= 0 && i > found - 4; i--) if (ms < ends[i]) return texts[i];
        return null;
    }

    static Srt load(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return parse(decode(out.toByteArray()));
        } finally {
            c.disconnect();
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1256"));
        }
    }

    static Srt parse(String text) {
        List<long[]> times = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String[] blocks = text.replace("﻿", "").replace("\r", "").split("\n\\s*\n");
        for (String block : blocks) {
            Matcher m = TIME.matcher(block);
            if (!m.find()) continue;
            String body = block.substring(m.end()).trim()
                    .replaceAll("<[^>]*>", "")          // <i>, <font …>
                    .replaceAll("\\{[^}]*\\}", "")      // {\an8}
                    .trim();
            if (body.isEmpty()) continue;
            times.add(new long[] {ms(m, 1), ms(m, 5)});
            lines.add(body);
        }
        return new Srt(times, lines);
    }

    private static long ms(Matcher m, int g) {
        String frac = (m.group(g + 3) + "00").substring(0, 3);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000
                + Long.parseLong(frac);
    }
}
