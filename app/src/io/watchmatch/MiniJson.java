package io.watchmatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the sync code, which also runs on a plain JVM (org.json is only a stub there).
 * Objects are Map, arrays are List, numbers are Long (no fraction or exponent) or Double, plus String, Boolean and null.
 */
final class MiniJson {
    private MiniJson() {}

    static Object parse(String text) {
        Reader r = new Reader(text);
        r.skip();
        Object v = r.value();
        r.skip();
        if (r.i != text.length()) throw new IllegalArgumentException("trailing characters at " + r.i);
        return v;
    }

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        put(b, v);
        return b.toString();
    }

    // ---- typed reads that never throw -------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    static String str(Object v) {
        return v instanceof String ? (String) v : v instanceof Number || v instanceof Boolean ? String.valueOf(v) : "";
    }

    static long lng(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    static double dbl(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    static boolean bool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- writing ----------------------------------------------------------------------------

    private static void put(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                put(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) b.append(',');
                first = false;
                put(b, o);
            }
            b.append(']');
        } else {
            quote(b, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ---- reading ----------------------------------------------------------------------------

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        IllegalArgumentException bad(String what) {
            return new IllegalArgumentException(what + " at " + i);
        }

        Object value() {
            if (i >= s.length()) throw bad("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skip();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skip();
                if (i >= s.length() || s.charAt(i) != '"') throw bad("key expected");
                String k = string();
                skip();
                if (i >= s.length() || s.charAt(i) != ':') throw bad(": expected");
                i++;
                skip();
                m.put(k, value());
                skip();
                if (i >= s.length()) throw bad("unexpected end");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw bad(", expected");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            skip();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skip();
                l.add(value());
                skip();
                if (i >= s.length()) throw bad("unexpected end");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw bad(", expected");
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw bad("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) throw bad("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw bad("bad \\u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);     // " \ /
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw bad("unexpected character");
            String n = s.substring(start, i);
            try {
                if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.valueOf(n);
                return Double.valueOf(n);
            } catch (NumberFormatException ex) {
                throw bad("bad number");
            }
        }
    }
}
