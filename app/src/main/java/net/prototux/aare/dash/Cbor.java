/*
 * IDmate Auto: liest ein CBOR-Telegramm des Sticks (RFC 8949) und gibt es als JSON-Text weiter, damit alle
 * Quellen durch denselben Leser gehen (Telemetry.accept). Nur der Ausschnitt, den die Telegramme benutzen:
 * Map, Text, Ganzzahl, Float32/64, Bool, null. Alles andere, zu tiefe Schachtelung und ueberzaehlige Bytes
 * werden abgelehnt (null) - die Daten kommen ueber Funk.
 * Ohne Android-Klassen, damit der Leser am PC gegen den Aufbau der Firmware pruefbar ist (tools/CborCheck.java).
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import java.nio.charset.StandardCharsets;

final class Cbor {
    private static final int MAX_DEPTH = 4, MAX_ITEMS = 64;

    private final byte[] b;
    private int i;
    private final StringBuilder out = new StringBuilder();

    private Cbor(byte[] data) {
        b = data;
    }

    /** JSON-Text des Telegramms oder null, wenn es kein gueltiges ist. */
    static String toJson(byte[] data) {
        if (data == null || data.length == 0 || data.length > 512) return null;
        Cbor c = new Cbor(data);
        try {
            c.value(0);
            return c.i == data.length ? c.out.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int u8() {
        return b[i++] & 0xff;
    }

    private long length(int info) {
        if (info < 24) return info;
        if (info == 24) return u8();
        if (info == 25) return (u8() << 8) | u8();
        if (info == 26) return ((long) u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        throw new IllegalArgumentException("Laenge");
    }

    private void value(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Tiefe");
        int ib = u8(), major = ib >> 5, info = ib & 31;
        switch (major) {
            case 0:
                out.append(length(info));
                return;
            case 1:
                out.append(-1 - length(info));
                return;
            case 3: {
                int n = (int) length(info);
                if (n > b.length - i) throw new IllegalArgumentException("Text");
                quote(new String(b, i, n, StandardCharsets.UTF_8));
                i += n;
                return;
            }
            case 5: {
                long n = length(info);
                if (n > MAX_ITEMS) throw new IllegalArgumentException("Map");
                out.append('{');
                for (int k = 0; k < n; k++) {
                    if (k > 0) out.append(',');
                    if (((b[i] & 0xff) >> 5) != 3) throw new IllegalArgumentException("Schluessel");
                    value(depth + 1);
                    out.append(':');
                    value(depth + 1);
                }
                out.append('}');
                return;
            }
            case 7:
                if (ib == 0xF4) { out.append("false"); return; }
                if (ib == 0xF5) { out.append("true"); return; }
                if (ib == 0xF6) { out.append("null"); return; }
                if (ib == 0xFA) {
                    int bits = (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
                    number(Float.intBitsToFloat(bits), true);
                    return;
                }
                if (ib == 0xFB) {
                    long bits = 0;
                    for (int k = 0; k < 8; k++) bits = (bits << 8) | u8();
                    number(Double.longBitsToDouble(bits), false);
                    return;
                }
                throw new IllegalArgumentException("Typ");
            default:
                throw new IllegalArgumentException("Typ");
        }
    }

    /** Float32 hat sieben gueltige Stellen: so ausgeben, dass aus 71.5f nicht 71.5 0000305 wird. */
    private void number(double d, boolean single) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null");
            return;
        }
        out.append(single ? Float.toString((float) d) : Double.toString(d));
    }

    private void quote(String s) {
        out.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }
}
