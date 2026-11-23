/*
 * Musik in Mate: Adresse im eigenen Netz oder VPN? Mate spricht nur HTTPS; isPrivate dient nur noch der
 * Test-Umleitung von plex.tv (MusicSetupActivity).
 */
package net.prototux.aare.music;

final class Hosts {
    private Hosts() { }

    static boolean isPrivate(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost")) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = h.split("\\.");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
        }
        if (h.startsWith("[")) return h.startsWith("[fc") || h.startsWith("[fd") || h.startsWith("[fe80") || h.equals("[::1]");
        if (!h.contains(".")) return true;
        for (String s : new String[] { ".local", ".lan", ".home.arpa", ".internal", ".fritz.box" })
            if (h.endsWith(s)) return true;
        return false;
    }
}
