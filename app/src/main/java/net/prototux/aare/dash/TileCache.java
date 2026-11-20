package net.prototux.aare.dash;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Map tiles of the dashboard kept on the device (wish O109): the map need not load them again on every drive.
 * The browser's tile requests (…/z/x/y.png and the like) are answered from here; a tile not here yet is fetched
 * with the browser's own headers and cookies, stored and handed on. Up to the size chosen in the phone app
 * ({@link #KEY_MB}, 0 = off); beyond it the tiles used longest ago go first.
 */
public final class TileCache {
    private static final String TAG = "IDmateTiles";
    public static final String PREFS = "dash_ui";
    public static final String KEY_MB = "tile_cache_mb";
    public static final int DEFAULT_MB = 1024;
    public static final int[] CHOICES_MB = {0, 256, 1024, 2048, 5120};

    private static final Pattern TILE = Pattern.compile(".*/\\d{1,2}/\\d+/\\d+(@\\d+x)?\\.(png|jpe?g|webp|pbf|mvt)(\\?.*)?$");
    private static final Executor TRIM = Executors.newSingleThreadExecutor();
    private static volatile long sSizeBytes = -1;
    private static final java.util.Set<String> sSeen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private TileCache() {}

    public static File dir(Context c) { return new File(c.getCacheDir(), "maptiles"); }

    public static int limitMb(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MB, DEFAULT_MB);
    }

    /** Bytes kept now (counted once, then followed). */
    public static long sizeBytes(Context c) {
        if (sSizeBytes < 0) {
            long sum = 0;
            File[] fs = dir(c).listFiles();
            if (fs != null) for (File f : fs) sum += f.length();
            sSizeBytes = sum;
        }
        return sSizeBytes;
    }

    public static void clear(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        sSizeBytes = 0;
    }

    /** The answer for a tile request, or null to let the browser load it itself (not a tile, cache off, failure). */
    static WebResourceResponse answer(Context c, WebResourceRequest rq) {
        if (!"GET".equalsIgnoreCase(rq.getMethod())) return null;
        String url = rq.getUrl().toString();
        if (!TILE.matcher(url).matches()) {
            // which requests pass by (digits hidden: tile numbers tell where the car is)
            if (net.prototux.aare.BuildConfig.DEBUG && sSeen.add(url.replaceAll("\\d+", "N").replaceAll("\\?.*", "?…"))) Log.i(TAG, "not a tile: " + url.replaceAll("\\d+", "N").replaceAll("\\?.*", "?…"));
            return null;
        }
        int limit = limitMb(c);
        if (limit <= 0) {
            if (!MateTiles.isOurs(url)) return null;
            /* the stand-in address of our map (O175) must be answered here even without the store */
            byte[] body = MateTiles.fetch(c, url);
            if (body == null) body = MateTiles.offline(c, url);   /* without the net: the region package (O175) */
            return MateTiles.response(body);
        }
        File d = dir(c);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String mime = mime(url);
        File f = new File(d, key(url));
        if (f.isFile() && f.length() > 0 && MateTiles.isOurs(url)) {
            f.setLastModified(System.currentTimeMillis());
            try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int n = 0; while (n < b.length) { int k = in.read(b, n, b.length - n); if (k < 0) break; n += k; } return MateTiles.response(b); }
            catch (IOException e) { return null; }
        }
        if (f.isFile() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());   /* recently used: kept longest */
            try {
                return new WebResourceResponse(mime, null, new FileInputStream(f));
            } catch (IOException e) {
                return null;
            }
        }
        if (MateTiles.isOurs(url)) {
            /* our map server's vector tiles (O175): through the gate with the token, kept like the others */
            byte[] body = MateTiles.fetch(c, url);
            if (body == null || body.length == 0) return MateTiles.response(MateTiles.offline(c, url));   /* without the net: the region package, not stored (O175) */
            try {
                File part = new File(d, f.getName() + ".part");
                try (FileOutputStream o = new FileOutputStream(part)) { o.write(body); }
                if (part.renameTo(f)) { sizeBytes(c); sSizeBytes += body.length; if (sSizeBytes > limit * 1024L * 1024L) TRIM.execute(() -> trim(c)); }
            } catch (IOException ignored) { }
            return MateTiles.response(body);
        }
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            for (Map.Entry<String, String> h : rq.getRequestHeaders().entrySet()) con.setRequestProperty(h.getKey(), h.getValue());
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) con.setRequestProperty("Cookie", cookie);
            int code = con.getResponseCode();
            String type = con.getContentType();
            if (code != 200) { con.disconnect(); return null; }
            byte[] body;
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                body = out.toByteArray();
            }
            // any tile the server answered (raster images, vector tiles "application/vnd.mapbox-vector-tile"…), never an error page
            if (body.length > 0 && (type == null || !type.startsWith("text/"))) {
                File part = new File(d, f.getName() + ".part");
                try (FileOutputStream o = new FileOutputStream(part)) { o.write(body); }
                if (part.renameTo(f)) {
                    sizeBytes(c);
                    sSizeBytes += body.length;
                    if (sSizeBytes > limit * 1024L * 1024L) TRIM.execute(() -> trim(c));
                }
            }
            return new WebResourceResponse(type != null ? type.split(";")[0] : mime, null, new ByteArrayInputStream(body));
        } catch (IOException e) {
            Log.d(TAG, "tile " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Down to 90 % of the limit, the tiles used longest ago first. */
    static void trim(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        long limit = limitMb(c) * 1024L * 1024L;
        long sum = 0;
        for (File f : fs) sum += f.length();
        if (sum <= limit) { sSizeBytes = sum; return; }
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long target = limit * 9 / 10;
        int gone = 0;
        for (File f : fs) {
            if (sum <= target) break;
            long len = f.length();
            if (f.delete()) { sum -= len; gone++; }
        }
        sSizeBytes = sum;
        Log.i(TAG, "trimmed " + gone + " tiles, " + (sum / 1024 / 1024) + " MB kept");
    }

    private static String key(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static String mime(String url) {
        String u = url.toLowerCase();
        if (u.contains(".mvt") || u.contains(".pbf")) return "application/x-protobuf";
        if (u.contains(".png")) return "image/png";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".jp")) return "image/jpeg";
        return "application/x-protobuf";
    }
}
