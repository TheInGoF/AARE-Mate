package net.prototux.aare.dash;

import android.content.Context;

/** What the phone app shows of the dashboard's connections (17.11.2026: the circle stayed empty when connected). */
public final class DashStatus {
    private DashStatus() {}

    /** The connected IDmate server's name (or its address), null when not connected. */
    public static String server(Context c) {
        Link l = Link.load(c);
        if (l == null) return null;
        return l.name != null && !l.name.isEmpty() ? l.name : l.url;
    }

    /** Whether a stick is remembered. */
    public static boolean stick(Context c) {
        String a = c.getSharedPreferences("dash_stick", Context.MODE_PRIVATE).getString("address", null);
        return a != null && !a.isEmpty();
    }
}
