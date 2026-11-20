/*
 * IDmate Auto: Buehne des Dashboards, gewoehnliche Ansichten direkt in Mates Auto-Oberflaeche (19.11.2026: "alle
 * Funktionen des Dashboards ... soll ja in Mate liegen. Also muss Mate auch selber das Dashboard zeichnen" - frueher
 * eine Car-App, die Mate selbst als Wirt betrieb, mit virtueller Anzeige auf der uebergebenen Flaeche):
 *   links  die ORIGINALE Dashboard-Karte von IDmate samt Reichweitenanzeige (Web-Ansicht, Adresse
 *          <Lese-API>/display: der Server meldet das Geraet als Anzeige an und zeigt nur die Karte),
 *   rechts die Werte (DashPainter).
 * Das Token geht nur einmal als Kopfzeile der ersten Anfrage an den Server, danach gilt dessen
 * Sitzungs-Cookie; in die Seite gelangt es nicht. Die Web-Ansicht darf nur den Server der Lese-API und
 * die Kartenkacheln laden, Links nach aussen folgen ihr nicht.
 *
 * Die Karte kommt vom Server, ihre Bedienung macht die App selbst (Vorgabe des Benutzers): Die Knoepfe der Seite
 * werden ausgeblendet; eigene Knoepfe (Fadenkreuz, Umkreis, Plus, Minus) liegen unten an der Fahrerseite ueber der
 * Karte und steuern sie ueber ein kleines Skript (MAP_SCRIPT). Ablauf: Seite laedt mit der Uebersicht, nach 3 s geht
 * der Umkreis aus und die Karte gleitet zum Fahrzeug (Fadenkreuz aktiv), danach erscheinen Plus/Minus. Umkreis =
 * schnell hinaus zur Uebersicht, Fadenkreuz = wieder hinein. Die Automatik laeuft nur einmal je App-Start.
 *
 * Beruehrungen kommen als echte Finger bei der Web-Ansicht an (Ziehen, Spreizen); langes Druecken oeffnet "Navigieren".
 *
 * Hinweis: Web-Inhalte auf der Flaeche erlaubt Google fuer Apps im Play Store nicht. Diese App ist fuer den
 * Eigengebrauch (Sideload).
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.Map;

final class CarStage {
    private static final String TAG = "IDmateStage";

    private final Context mCtx;
    private final Hub mHub;
    private final DashPainter mPainter;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** The car screen's context the views were made with: another one (a new connection) builds them anew. */
    private Context mHostCtx;
    private FrameLayout mRoot, mMapFrame;
    private WebView mWeb;
    private View mDash;
    private float mDp = 1;
    /** Where the finger went down on the map last (for the long press), in the page's pixels. */
    private float mDownX, mDownY;
    private boolean mTouching;
    /** System L-003 / I-001: the page with the range, loaded anew every 10 minutes (the range is fetched once per load). */
    private static final long MAP_REFRESH_MS = 10 * 60_000L;
    private Link mLink;
    private WebView mNextWeb;
    private long mLoadedAt;
    private final Runnable mRefresh = new Runnable() {
        @Override public void run() {
            mMain.removeCallbacks(this);
            mMain.postDelayed(this, MAP_REFRESH_MS);
            /* not while parked (done on coming back), the driver touches the map or a new page is on its way */
            if (mParked || mLink == null || mMapFrame == null || mNextWeb != null || mTouching) return;
            String then = mMapMode.startsWith("near") ? "near" : "overview";
            Log.i(TAG, "Karte: Umkreis wird neu geladen (" + then + ")");
            mLoadedAt = SystemClock.uptimeMillis();
            addWeb(mLink, then);
        }
    };
    /**
     * While the car charges fast, the range on the map is loaded anew every minute (19.11.2026: "Wenn das Dashboard
     * Schnellladen erkennt, dann sollte er ... die Umkreisreichweite nachladen ... Sagen wir alle 1 Minute ... ich möchte
     * einen smoothen Übergang von der Karte 1 zur Karte 2"): the new page beneath the shown one, faded in once its map
     * stands - the same way as the 10-minute refresh.
     */
    private static final long FAST_CHARGE_REFRESH_MS = 60_000L;
    private boolean mFastCharging;

    private boolean fastCharging() {
        return Boolean.TRUE.equals(mHub.telemetry.flag("charging")) && Boolean.TRUE.equals(mHub.telemetry.flag("dc"));
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (mDash != null) mDash.invalidate();
            pollMap();
            boolean fast = fastCharging();
            if (fast != mFastCharging) {
                mFastCharging = fast;
                net.prototux.aare.DiagLog.INSTANCE.add("dashboard: " + (fast ? "fast charging - the range on the map anew every minute" : "no fast charging any more - the range every 10 minutes again"));
            }
            if (fast && mLink != null && !mParked && mNextWeb == null && !mTouching && SystemClock.uptimeMillis() - mLoadedAt > FAST_CHARGE_REFRESH_MS)
                mMain.post(mRefresh);
            mMain.postDelayed(this, 1000);
        }
    };

    /* ── Eigene Bedienung der Server-Karte ─────────────────────────────────── */

    private static final int B_CENTER = 0, B_RANGE = 1, B_WIND = 2, B_RAIN = 3, B_PLUS = 4, B_MINUS = 5;
    private static boolean sAutoDone;       /* die Automatik (hineingleiten) nur einmal je App-Start */
    private final MapButton[] mButtons = new MapButton[6];
    private String mWindOn;   /* zuletzt gemeldeter Stand des Wind-Knopfs */
    private LinearLayout mButtonCol;
    private String mMapMode = "";

    /**
     * Laeuft in der Seite. Fasst von ihr nur an: die Leaflet-Karte (map), die Fahrzeugmarke (marker), den
     * Umkreis-Schalter (#mapRange) und die Merker, mit denen die Seite ihre eigene Automatik aussetzt
     * (mapTouched, lastUserMove, rangeFitKm). Fehlt etwas davon, bleibt der Zustand "wait" und nichts passiert.
     */
    private static final String MAP_SCRIPT = """
        (function () {
          if (window.__mate) return;
          var M = window.__mate = { mode: 'wait', gl: 0, touch: 0, since: 0 };
          function hideOwn() {
            var c = document.querySelector('.map-ctl'); if (c) c.style.display = 'none';
            // the legend of the wind arrows: top left, clear of the app's own buttons at the bottom
            // (top right since 17.11.2026: top left it lay over Mate's own map buttons in the short map column)
            // 18.11.2026 (IDmate 1.2.347, "damit diese mehr von der Karte freigeben, sollen sie standardmäßig eingeklappt
            // sein. Eine oben, eine unten"): the rain legend stays top left as IDmate puts it, the wind legend goes to
            // the bottom right - bottom left are Mate's own buttons
            var l = document.getElementById('windLegend'); if (l) { l.style.left = 'auto'; l.style.right = '12px'; l.style.top = 'auto'; l.style.bottom = '12px'; }
          }
          hideOwn();
          // both legends folded to their title at first, once per device; what the driver opens or folds afterwards
          // IDmate keeps itself (map_lg_* in the page's storage), also over the 10-minute reload
          function foldOnce() {
            try {
              if (localStorage.getItem('__mate_lg')) return;
              var heads = document.querySelectorAll('.lg-head');
              if (!heads.length) return;
              localStorage.setItem('__mate_lg', '1');
              ['wind', 'rain'].forEach(function (k) {
                localStorage.setItem('map_lg_' + k, '0');
                var h = document.querySelector('.lg-head[data-lg="' + k + '"]');
                if (h && h.getAttribute('aria-expanded') === 'true') h.click();
              });
            } catch (e) {}
          }
          foldOnce();
          // O175: our map server's vector map in IDmate's day colours instead of the page's base map. Only where the
          // host gives the address (window.__mateTiles - Mate does, the IDmate app's copy of this script does not).
          function ownBase() {
            try {
              if (!window.__mateTiles || M.base || !map || !window.IDmateMap || !window.protomapsL) return;
              var old = []; map.eachLayer(function (l) { if (l instanceof L.GridLayer) old.push(l); });
              M.base = IDmateMap.baseLayer(window.__mateTiles, { maxZoom: 19 }).addTo(map);
              if (M.base.bringToBack) M.base.bringToBack();
              old.forEach(function (l) { map.removeLayer(l); });
            } catch (e) {}
          }
          // System I-007 (17.11.2026: after reloading, the range was there but no map below it): the vector map's
          // scripts may come later than 1.5 s - tried every second for 20 s until it takes
          (function tryBase(n) { ownBase(); if (!M.base && n < 20) setTimeout(function () { tryBase(n + 1); }, 1000); })(0);
          // No icons and points of interest of IDmate on the dashboard's map (18.11.2026: "ich möchte die Ladesymbole von
          // IDMate nicht im Dashboard haben. Also alle Icons und POIs von IDMate nicht mit ins Dashboard laden"): every
          // marker but the car and Mate's own pin is taken off the map, now and whenever the page adds one again.
          function strip(l) {
            try { if (l instanceof L.Marker && l !== marker && l !== M.pin) { map.removeLayer(l); return 1; } } catch (e) {}
            return 0;
          }
          (function noPois(n) {
            try {
              if (map && marker) {
                var gone = 0; map.eachLayer(function (l) { gone += strip(l); });
                if (!M.poisOff) { M.poisOff = true; map.on('layeradd', function (ev) { if (strip(ev.layer)) M.poisGone = (M.poisGone || 0) + 1; }); }
                M.poisGone = (M.poisGone || 0) + gone;
                return;
              }
            } catch (e) {}
            if (n < 30) setTimeout(function () { noPois(n + 1); }, 1000);
          })(0);
          function ok() { try { return !!(map && marker); } catch (e) { return false; } }
          function fitted() { try { return rangeFitKm != null; } catch (e) { return false; } }
          function hold() { try { mapTouched = true; lastUserMove = Date.now() + 9e12; if (rangeFitKm == null) rangeFitKm = 1e9; } catch (e) {} }
          function range(on) { try { if (rangeHidden === on) document.getElementById('mapRange').click(); } catch (e) {} }
          // Every level on the way gets its tiles first (at most 1.2 s), then the glide runs on real tiles, not on
          // scaled ones (17.11.2026)
          function glide(center, zoom, ms) {
            var run = ++M.gl, za = map.getZoom(), ca = map.getCenter(), wait = [];
            var lo = Math.min(za, zoom), hi = Math.max(za, zoom);
            for (var lz = Math.ceil(lo); lz <= Math.floor(hi); lz++) {
              var f = hi === lo ? 1 : (lz - za) / (zoom - za);
              var mid = L.latLng(ca.lat + (L.latLng(center).lat - ca.lat) * f, ca.lng + (L.latLng(center).lng - ca.lng) * f);
              wait = wait.concat(warmVector(mid, lz));
            }
            var go = function () { if (run === M.gl) glideNow(run, center, zoom, ms); };
            if (!wait.length) return go();
            var done = false, once = function () { if (!done) { done = true; go(); } };
            Promise.all(wait).then(once); setTimeout(once, 1200);
          }
          function glideNow(run, center, zoom, ms) {
            var z0 = map.getZoom(), s0 = Math.pow(2, z0), c0 = map.project(map.getCenter(), 0),
                c1 = map.project(L.latLng(center), 0), t0 = performance.now(), touched = M.touch;
            // Optical zoom: while gliding, the tiles on screen are only scaled - no level in between is fetched (they
            // never came fast enough). The tiles of the target are fetched from the start, so they are there on arrival.
            var tl = tiles(), uz = tl ? tl.options.updateWhenZooming : true;
            if (tl) tl.options.updateWhenZooming = false;
            warm(center, zoom);
            map._moveStart(true, false);
            function end(last) {
              if (tl) tl.options.updateWhenZooming = uz;
              if (last) map._move(L.latLng(center), zoom);
              map._moveEnd(true); if (run === M.gl) M.gl = 0;
            }
            function step(now) {
              if (run !== M.gl) return;
              if (M.touch !== touched) return end(false);
              var t = Math.min(1, (now - t0) / ms), e = t * t * t * (t * (6 * t - 15) + 10);
              if (t >= 1) return end(true);
              // Zooming in, the target comes to the middle of the screen at an even pace. Zooming out, the same rule
              // threw the middle far past the start and swung it back (k > 1) - there the start leaves the middle at
              // an even pace instead; k stays between 0 and 1 either way.
              var z = z0 + (zoom - z0) * e, s = Math.pow(2, z),
                  k = zoom >= z0 ? s0 * (1 - e) / s : 1 - Math.pow(2, zoom) * e / s;
              map._move(map.unproject(L.point(c1.x - (c1.x - c0.x) * k, c1.y - (c1.y - c0.y) * k), 0), z, { flyTo: true });
              if (M.tint) try { M.tint(); } catch (e2) {}       // the range tint follows the glide as before (see calm)
              requestAnimationFrame(step);
            }
            requestAnimationFrame(step);
          }
          // Kept ready: the tiles of what can come next - one zoom step in and out around the view, and the two fixed
          // views (near the car, the overview). They go into the browser's cache, so a zoom or a change of view finds
          // them there and has nothing to build up. Modest on purpose: a view's worth per step, each tile asked once.
          var warmed = {}, warmCount = 0;
          function tiles() { var t = null; try { map.eachLayer(function (l) { if (!t && l instanceof L.TileLayer && l._url) t = l; }); } catch (e) {} return t; }
          // The vector base (our map server's, O175): its data comes through its view, which keeps what it fetched -
          // asked for here, a level is there before the map reaches it (17.11.2026: "alle Karten fuers Reinzoomen parat
          // statt Pixel-Zoom"). Returns the requests, so a glide can wait for them.
          function warmVector(center, zoom) {
            var out = [];
            try {
              var v = M.base && M.base.views && M.base.views.get(''); if (!v || center == null || isNaN(zoom)) return out;
              var z = Math.round(zoom); if (z < 0 || z > 19) return out;
              var ts = 256, s = map.getSize(), p = map.project(L.latLng(center), z), n = Math.pow(2, z),
                  x0 = Math.floor((p.x - s.x / 2) / ts), x1 = Math.floor((p.x + s.x / 2) / ts),
                  y0 = Math.floor((p.y - s.y / 2) / ts), y1 = Math.floor((p.y + s.y / 2) / ts);
              for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) {
                if (y < 0 || y >= n) continue;
                var c = { z: z, x: ((x % n) + n) % n, y: y }, k = 'v' + z + '/' + c.x + '/' + c.y;
                if (warmed[k]) continue;
                warmed[k] = 1; warmCount++;
                try { var pr = v.getDisplayTile(c); if (pr && pr.then) out.push(pr.catch(function () {})); } catch (e) {}
              }
            } catch (e) {}
            return out;
          }
          function warm(center, zoom) {
            warmVector(center, zoom);
            var t = tiles(); if (!t || center == null || isNaN(zoom)) return;
            var o = t.options, z = Math.round(zoom), lo = o.minNativeZoom || o.minZoom || 0, hi = o.maxNativeZoom || o.maxZoom || 19;
            if (z < lo || z > hi || warmCount > 1500) return;
            var ts = 256, s = map.getSize(), p = map.project(L.latLng(center), z), n = Math.pow(2, z),
                x0 = Math.floor((p.x - s.x / 2) / ts), x1 = Math.floor((p.x + s.x / 2) / ts),
                y0 = Math.floor((p.y - s.y / 2) / ts), y1 = Math.floor((p.y + s.y / 2) / ts);
            for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) {
              if (y < 0 || y >= n) continue;
              var c = { x: ((x % n) + n) % n, y: o.tms ? n - 1 - y : y, z: z + (o.zoomOffset || 0) }, k = c.z + '/' + c.x + '/' + c.y;
              if (warmed[k]) continue;
              warmed[k] = 1; warmCount++;
              try {
                var im = new Image();
                im.src = L.Util.template(t._url, L.Util.extend({ s: t._getSubdomain(c), r: '' }, o, c));
              } catch (e) {}
            }
          }
          var warmTimer = 0;
          function warmSoon() {
            clearTimeout(warmTimer);
            warmTimer = setTimeout(function () {
              if (!ok() || M.gl) return;
              var c = map.getCenter(), z = map.getZoom();
              warm(c, z - 1); warm(c, z + 1);
              warm(marker.getLatLng(), 15); warm(marker.getLatLng(), 14);
              try { var b = (rangeArea && areaBounds) ? areaBounds : (rangeOuter ? rangeOuter.getBounds() : null);
                    if (b) warm(b.getCenter(), map.getBoundsZoom(b, false, L.point(24, 24))); } catch (e) {}
            }, 700);
          }
          M.warmed = function () { return warmCount; };
          M.near = function (ms) {
            if (!ok()) return;
            M.mode = 'near'; hold(); range(false);
            glide(marker.getLatLng(), 15, ms || 1400);
          };
          M.overview = function () {
            if (!ok()) return;
            M.mode = 'overview'; hold(); range(true);
            var b = null;
            try { if (rangeArea && areaBounds) b = areaBounds; } catch (e) {}
            try { if (!b && rangeOuter) b = rangeOuter.getBounds(); } catch (e) {}
            if (b) glide(b.getCenter(), map.getBoundsZoom(b, false, L.point(24, 24)), 900);
            else glide(marker.getLatLng(), 9, 900);
          };
          M.zoom = function (d) { if (!ok()) return; M.gl = 0; M.touch = Date.now(); map.setZoom(Math.round(map.getZoom()) + d); };
          // Spreizen/Kneifen stufenlos um den Punkt unter den Fingern, wie in Mate Navi (18.11.2026: "Das Rein- und
          // Rauszoomen in der Dashboard-Map sollte genauso funktionieren wie in der Navi-App") - x, y in Bildpunkten der Ansicht
          // 18.11.2026 ("Das Zoomen und Verschieben in der Dashboard-Karte ruckelt und stockt die ganze Zeit. Das
          // funktioniert bei dem Navi problemlos"): setZoomAround without animation set the whole map up anew for every
          // step of the fingers - all tiles thrown away and drawn again, the range, wind and rain too. Now the way
          // Leaflet's own pinch goes: the tiles and layers are only scaled while the fingers move, and the map is set
          // up once, 180 ms after the last step (the car's screen tells no end of a pinch).
          M.pinch = function (x, y, f) {
            if (!ok() || !(f > 0)) return; M.gl = 0; M.touch = Date.now();
            var r = window.devicePixelRatio || 1, p = L.point(x / r, y / r);
            if (!map._move || !map._moveStart || !map._resetView) {        // another Leaflet: the plain way
              var snap = map.options.zoomSnap, zz = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), map.getZoom() + Math.log2(f)));
              map.options.zoomSnap = 0;
              try { map.setZoomAround(p, zz, { animate: false }); } finally { map.options.zoomSnap = snap; }
              return;
            }
            var s = M.pz;
            if (!s) {
              if (map._stop) map._stop();
              s = M.pz = { z: map.getZoom(), ll: map.containerPointToLatLng(p), drawn: 0 };
              map._moveStart(true, false);
            }
            s.z = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), s.z + Math.log2(f)));
            // the place first under the fingers stays under them
            s.c = map.unproject(map.project(s.ll, s.z).subtract(p.subtract(map.getSize().divideBy(2))), s.z);
            map._move(s.c, s.z, { pinch: true, round: false });
            // the page's range tint is not scaled with the map: drawn anew a few times a second meanwhile
            if (M.tint && Date.now() - s.drawn > 120) { s.drawn = Date.now(); try { M.tint(); } catch (e) {} }
            clearTimeout(M.pzEnd);
            M.pzEnd = setTimeout(function () {
              var e = M.pz; M.pz = null;
              if (!e) return;
              var snap = map.options.zoomSnap;
              map.options.zoomSnap = 0;       // stays where the fingers left it, between the levels too
              try { map._resetView(e.c, e.z); } finally { map.options.zoomSnap = snap; }
            }, 180);
          };
          // While a finger draws the map the page drew its range tint anew on every step. The tint lies in the map's own
          // pane and moves with it (a fifth of the map beyond each edge), so it is enough when the move has ended - the
          // page does that itself (moveend). Its drawing is kept for the pinch above.
          function calm() {
            try {
              var l = map._events && map._events.move;
              if (M.tint || !l) return;
              for (var i = l.length - 1; i >= 0; i--) {
                if (/requestAnimationFrame\\(draw\\)/.test(String(l[i].fn))) { M.tint = l[i].fn; map.off('move', l[i].fn, l[i].ctx); }
              }
            } catch (e) {}
          }
          // langes Druecken: ein Punkt an der Stelle, Antwort "lat,lon" fuer das Popup "Navigieren"
          M.pick = function (x, y) {
            if (!ok()) return '';
            var r = window.devicePixelRatio || 1, ll = map.containerPointToLatLng(L.point(x / r, y / r));
            if (M.pin) map.removeLayer(M.pin);
            M.pin = L.circleMarker(ll, { radius: 10, color: '#0E3B36', weight: 3, fillColor: '#1DE9B6', fillOpacity: 1 }).addTo(map);
            M.touch = Date.now();
            return ll.lat.toFixed(6) + ',' + ll.lng.toFixed(6);
          };
          M.unpick = function () { try { if (M.pin) map.removeLayer(M.pin); } catch (e) {} M.pin = null; };
          // 1 = arrows on, 0 = switched off, h = the page has no wind (yet), x = the page has no such switch
          function windOn() { var b = document.getElementById('mapWind'); return !b ? 'x' : b.hidden ? 'h' : b.getAttribute('aria-pressed') === 'true' ? 1 : 0; }
          M.wind = function () { M.windAuto = false; try { var b = document.getElementById('mapWind'); if (b) b.click(); } catch (e) {} };
          // IDmate's rain layer (1.2.345, 18.11.2026): its own switch, pressed from Mate's button; 1 on, 0 off, h/x none
          function rainOn() { var b = document.getElementById('mapRain'); return !b ? 'x' : b.hidden ? 'h' : b.getAttribute('aria-pressed') === 'true' ? 1 : 0; }
          M.rain = function () { try { var b = document.getElementById('mapRain'); if (b) b.click(); } catch (e) {} };
          // Wind arrows belong to the overview: zoomed in (level 12 on) they are switched off, and on again once the map
          // is back out - only if they were on and the driver has not switched them himself since (17.11.2026)
          function windByZoom() {
            try {
              var z = map.getZoom(), w = windOn(), b = document.getElementById('mapWind');
              if (!b) return;
              if (z >= 12 && w === 1 && !M.gl) { b.click(); M.windAuto = true; }
              else if (z < 12 && M.windAuto && w === 0) { b.click(); M.windAuto = false; }
            } catch (e) {}
          }
          // mode | zoom | range shown (for the log; the map itself is never looked at) | wind
          function rangeOn() { try { return rangeHidden ? 'Umkreis aus' : 'Umkreis an'; } catch (e) { return 'Umkreis ?'; } }
          M.state = function () { return M.mode + '|' + (ok() ? map.getZoom().toFixed(1) : '') + '|' + rangeOn() + '|' + windOn() + '|' + rainOn(); };
          setInterval(function () {
            hideOwn();
            if (!ok()) return;
            calm();
            if (M.mode === 'wait') {
              if (!M.since) {
                M.since = Date.now();
                try { tiles().options.keepBuffer = 4; } catch (e) {}
                map.on('moveend zoomend', warmSoon); warmSoon();
                ['pointerdown', 'touchstart', 'wheel'].forEach(function (ev) {
                  map.getContainer().addEventListener(ev, function () { M.touch = Date.now(); }, { passive: true, capture: true });
                });
              }
              // The page remembers the range switch for good, and the near view switches it off: without this the
              // overview came up without the range after every start, until the button was tapped.
              range(true);
              // loaded: the overview is fitted (or there is no range to fit); three seconds later glide in, once
              var waited = Date.now() - M.since;
              if (window.__mateNoAuto) { if (fitted() || waited > 4000) { M.mode = 'overview'; hold(); if (window.__mateThen === 'near') M.near(1); } }
              else if ((fitted() && waited > 3000) || waited > 9000) M.near(3000);
              return;
            }
            hold();
            windByZoom();
            // near view follows the car again once the map has been left alone for half a minute
            if (M.mode === 'near' && !M.gl && Date.now() - M.touch > 30000) {
              var p = map.latLngToContainerPoint(marker.getLatLng()), s = map.getSize();
              if (Math.abs(p.x - s.x / 2) + Math.abs(p.y - s.y / 2) > 12) map.panTo(marker.getLatLng());
            }
          }, 500);
        })();
        """;

    private void js(String code) {
        if (mWeb != null) mWeb.evaluateJavascript(code, null);
    }

    /** Zustand der Karte abfragen und die eigenen Knoepfe danach stellen. */
    private void pollMap() {
        if (mWeb == null) return;
        mWeb.evaluateJavascript("window.__mate ? __mate.state() : ''", v -> {
            if (v == null || mButtonCol == null) return;
            String st = v.replace("\"", "");
            String mode = st.contains("|") ? st.substring(0, st.indexOf('|')) : "";
            /* Ansicht und Umkreis: jeden Wechsel ins Protokoll (die Karte selbst sieht niemand an) */
            String seen = mode + (st.contains("Umkreis an") ? "+" : "-");
            if (!seen.equals(mMapMode)) {
                Log.i(TAG, "Karte: " + (mode.isEmpty() ? "ohne Steuerung" : mode) + " Zoom " + st.substring(st.indexOf('|') + 1));
                mMapMode = seen;
            }
            boolean ready = mode.equals("near") || mode.equals("overview");
            if (mode.equals("near")) sAutoDone = true;
            mButtonCol.setVisibility(ready ? View.VISIBLE : View.GONE);
            mButtons[B_CENTER].setActive(mode.equals("near"));
            mButtons[B_RANGE].setActive(mode.equals("overview"));
            String[] parts = st.split("\\|", -1);
            String wind = parts.length > 3 ? parts[3] : "x";
            String rain = parts.length > 4 ? parts[4] : "x";
            mButtons[B_RAIN].setActive(rain.equals("1"));
            /* only where the page has rain (IDmate 1.2.345 with its rain layer on) */
            mButtons[B_RAIN].setVisibility(ready && (rain.equals("1") || rain.equals("0")) ? View.VISIBLE : View.GONE);
            mButtons[B_WIND].setActive(wind.equals("1"));
            /* nur zeigen, wenn die Seite Wind hat */
            mButtons[B_WIND].setVisibility(ready && (wind.equals("1") || wind.equals("0")) ? View.VISIBLE : View.GONE);
            int[] at = new int[2];
            mButtons[B_WIND].getLocationOnScreen(at);
            String now = wind + (mButtons[B_WIND].isShown() && at[1] > 0 ? " bei " + (at[0] + mButtons[B_WIND].getWidth() / 2) + "," + (at[1] + mButtons[B_WIND].getHeight() / 2) : "");
            if (ready && !now.equals(mWindOn)) {
                mWindOn = now;
                Log.i(TAG, "Karte: Wind " + now);
            }
            boolean zoom = ready && sAutoDone && mCtx.getSharedPreferences("dash_ui", Context.MODE_PRIVATE).getBoolean("map_zoom_buttons", true);
            mButtons[B_PLUS].setVisibility(zoom ? View.VISIBLE : View.GONE);
            mButtons[B_MINUS].setVisibility(zoom ? View.VISIBLE : View.GONE);
        });
    }

    private void pressed(int kind) {
        switch (kind) {
            case B_CENTER: js("window.__mate && __mate.near(1400)"); break;
            case B_RANGE: js("window.__mate && __mate.overview()"); break;
            case B_WIND: js("window.__mate && __mate.wind()"); break;
            case B_RAIN: js("window.__mate && __mate.rain()"); break;
            case B_PLUS: js("window.__mate && __mate.zoom(1)"); break;
            default: js("window.__mate && __mate.zoom(-1)");
        }
        mMain.postDelayed(this::pollMap, 150);
    }

    /** Ein eigener Kartenknopf: Glasflaeche wie die Knoepfe von IDmate, Zeichen gezeichnet, aktiv in Tuerkis. */
    private final class MapButton extends View {
        private final int mKind;
        private boolean mActive;
        private final android.graphics.Paint mP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        MapButton(Context c, int kind) {
            super(c);
            mKind = kind;
        }

        void setActive(boolean on) {
            if (on != mActive) {
                mActive = on;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = 10 * mDp, cx = w / 2, cy = h / 2, u = w / 52f;
            mP.setStyle(android.graphics.Paint.Style.FILL);
            mP.setColor(mActive ? 0xF20E3B36 : 0xE6161B22);
            cv.drawRoundRect(0, 0, w, h, r, r, mP);
            mP.setStyle(android.graphics.Paint.Style.STROKE);
            mP.setStrokeWidth(1.2f * mDp);
            mP.setColor(mActive ? 0xFF1DE9B6 : 0xFF30363D);
            cv.drawRoundRect(mDp, mDp, w - mDp, h - mDp, r, r, mP);
            mP.setColor(mActive ? 0xFF1DE9B6 : 0xFFF0F6FC);
            mP.setStrokeWidth(2.6f * u);
            mP.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            switch (mKind) {
                case B_CENTER:      /* Fadenkreuz */
                    cv.drawCircle(cx, cy, 8 * u, mP);
                    cv.drawLine(cx, cy - 15 * u, cx, cy - 10 * u, mP);
                    cv.drawLine(cx, cy + 10 * u, cx, cy + 15 * u, mP);
                    cv.drawLine(cx - 15 * u, cy, cx - 10 * u, cy, mP);
                    cv.drawLine(cx + 10 * u, cy, cx + 15 * u, cy, mP);
                    mP.setStyle(android.graphics.Paint.Style.FILL);
                    cv.drawCircle(cx, cy, 2.6f * u, mP);
                    break;
                case B_RANGE:       /* Umkreis: gestrichelter Kreis um einen Punkt */
                    mP.setPathEffect(new android.graphics.DashPathEffect(new float[] { 5 * u, 5 * u }, 0));
                    cv.drawCircle(cx, cy, 13 * u, mP);
                    mP.setPathEffect(null);
                    mP.setStyle(android.graphics.Paint.Style.FILL);
                    cv.drawCircle(cx, cy, 3.2f * u, mP);
                    break;
                case B_WIND:        /* Windpfeile: drei Linien, die mittlere mit Pfeilspitze */
                    cv.drawLine(cx - 12 * u, cy - 7 * u, cx + 3 * u, cy - 7 * u, mP);
                    cv.drawLine(cx - 12 * u, cy, cx + 12 * u, cy, mP);
                    cv.drawLine(cx + 12 * u, cy, cx + 7 * u, cy - 4.5f * u, mP);
                    cv.drawLine(cx + 12 * u, cy, cx + 7 * u, cy + 4.5f * u, mP);
                    cv.drawLine(cx - 12 * u, cy + 7 * u, cx, cy + 7 * u, mP);
                    break;
                case B_RAIN:        /* Regen: eine Wolke, drei schräge Tropfenstriche darunter */
                    cv.drawArc(cx - 12 * u, cy - 12 * u, cx - 2 * u, cy - 2 * u, 90, 180, false, mP);
                    cv.drawArc(cx - 6 * u, cy - 16 * u, cx + 8 * u, cy - 2 * u, 180, 180, false, mP);
                    cv.drawArc(cx + 3 * u, cy - 11 * u, cx + 13 * u, cy - 1 * u, 270, 180, false, mP);
                    cv.drawLine(cx - 7 * u, cy - 2 * u, cx + 8 * u, cy - 2 * u, mP);
                    cv.drawLine(cx - 6 * u, cy + 4 * u, cx - 8 * u, cy + 9 * u, mP);
                    cv.drawLine(cx, cy + 4 * u, cx - 2 * u, cy + 9 * u, mP);
                    cv.drawLine(cx + 6 * u, cy + 4 * u, cx + 4 * u, cy + 9 * u, mP);
                    break;
                case B_PLUS:
                    cv.drawLine(cx, cy - 10 * u, cx, cy + 10 * u, mP);
                    cv.drawLine(cx - 10 * u, cy, cx + 10 * u, cy, mP);
                    break;
                default:
                    cv.drawLine(cx - 10 * u, cy, cx + 10 * u, cy, mP);
            }
        }
    }

    /** The stage in use, for the debug tool that makes the map's renderer crash (debug builds only). */
    static volatile CarStage sCurrent;

    /** Debug: crash the map's renderer, as the device's memory guard would end it - the map must come back by itself. */
    static boolean debugCrashRenderer() {
        CarStage st = sCurrent;
        if (st == null) return false;
        st.mMain.post(() -> { if (st.mWeb != null) st.mWeb.loadUrl("chrome://crash"); });
        return true;
    }

    /** Debug: runs [code] in the dashboard's page and hands back its answer (map layers, zoom - never the map's picture). */
    static boolean debugJs(String code, android.webkit.ValueCallback<String> answer) {
        CarStage st = sCurrent;
        if (st == null) return false;
        st.mMain.post(() -> { if (st.mWeb != null) st.mWeb.evaluateJavascript(code, answer); else answer.onReceiveValue("no page"); });
        return true;
    }

    CarStage(Context ctx, Hub hub) {
        mCtx = ctx;
        mHub = hub;
        mPainter = new DashPainter(ctx, hub);
        sLast = this;
    }

    private static volatile CarStage sLast;

    /**
     * Memory is tight (net.prototux.aare.Memory): a stage kept behind another area is taken down now instead of after
     * ten minutes - its page is the largest thing Mate holds without showing it. Coming back builds it anew.
     * True when there was one to take down.
     */
    public static boolean dropParked() {
        final CarStage s = sLast;
        if (s == null || !s.mParked || s.mRoot == null) return false;
        s.mMain.post(() -> { if (s.mParked) { Log.i(TAG, "Buehne abgebaut: Speicher knapp"); s.stop(); } });
        return true;
    }

    Hub hub() {
        return mHub;
    }

    boolean running() {
        return mRoot != null;
    }

    /* Vorpuffern: so lange nach dem Zeichnen der Seite warten (Kacheln laden), dann so lange einblenden */
    private static final long MAP_SETTLE_MS = 600, MAP_FADE_MS = 350;
    /** The new range over the old one: slow enough to be seen as one map changing, not a page loading. */
    private static final long SWAP_FADE_MS = 900;

    /* Flaeche weg (ein anderer Bereich ist vorn): Buehne behalten, damit die Karte beim Zurueckkommen sofort steht */
    private static final long PARK_MAX_MS = 10 * 60 * 1000L;
    private boolean mParked;
    private int mDpiI;
    private final Runnable mParkEnd = () -> { if (mParked) { Log.i(TAG, "Buehne nach Pause abgebaut"); stop(); } };

    /** Not on the screen any more (another area in front): the views are kept, at rest, to come back at once. Taken down after a longer pause. */
    void park() {
        if (mRoot == null || mParked) return;
        if (mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
        mParked = true;
        mMain.removeCallbacks(mTick);
        mMain.removeCallbacks(mParkEnd);
        mMain.postDelayed(mParkEnd, PARK_MAX_MS);
        /*
         * 18.11.2026 ("Die App laeuft aktuell ziemlich unperformant ... Die Navi-Karte soll so fluessig wie moeglich
         * laufen"): the kept page went on drawing behind the other areas - the wind's moving lines, the rain, the map -
         * for up to ten minutes, taking the processor and the graphics from the navigation's map. Now it rests while
         * it is not seen (its animations and drawing stop) and wakes when the dashboard comes back.
         */
        rest(true);
        hidePick();
        Log.i(TAG, "Buehne geparkt");
    }

    /** The pages of the kept stage at rest (not seen) or awake again; a page still loading under the shown one too. */
    private void rest(boolean on) {
        for (WebView w : new WebView[] { mWeb, mNextWeb }) {
            if (w == null) continue;
            try {
                if (on) w.onPause(); else w.onResume();
            } catch (RuntimeException e) {
                Log.w(TAG, "Seite " + (on ? "anhalten" : "wecken") + ": " + e);
            }
        }
    }

    /**
     * The stage's views for the car screen [ctx] at [dpi]: the kept ones when they were made for the same screen
     * (nothing loads anew), else built anew. Null when there is no map (no link to IDmate): the caller paints the values.
     */
    View view(Context ctx, int dpi) {
        if (mRoot != null && mHostCtx == ctx && mDpiI == dpi) {
            if (mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
            if (mParked) {
                mParked = false;
                rest(false);
                mMain.removeCallbacks(mParkEnd);
                mMain.removeCallbacks(mTick);
                mMain.post(mTick);
                /* back after a pause longer than the refresh: the range at once (L-003) */
                if (mLink != null && SystemClock.uptimeMillis() - mLoadedAt > MAP_REFRESH_MS) mMain.post(mRefresh);
                Log.i(TAG, "Buehne fortgesetzt");
            }
            return mRoot;
        }
        return start(ctx, dpi) ? mRoot : null;
    }

    /** Buehne aufbauen. Liefert false, wenn das Geraet es nicht zulaesst (dann zeichnet MainScreen selbst). */
    @SuppressLint("SetJavaScriptEnabled")
    boolean start(Context host, int dpi) {
        stop();
        Link link = Link.load(mCtx);
        if (link == null) { mHub.telemetry.note("stage", "keine Karte: keine Verbindung zu IDMate eingerichtet"); return false; }
        /* IDMate liefert die Kartenansicht nur ueber HTTPS (Ausnahme: Testserver auf dem eigenen Geraet) */
        if (!link.secure() && !link.url.startsWith("http://127.0.0.1")) {
            mHub.telemetry.note("stage", "keine Karte: Verbindung ohne HTTPS");
            return false;
        }
        try {
            /* Mate's size on the car screen ([dpi], as the rest of Mate's car screen): the own buttons and the page alike */
            mDp = dpi / 160f;
            mDpiI = dpi;
            mHostCtx = host;

            mRoot = new FrameLayout(host);
            mRoot.setBackgroundColor(DashPainter.BG);
            LinearLayout row = new LinearLayout(mHostCtx);
            mMapFrame = new FrameLayout(mHostCtx);
            mMapFrame.setBackgroundColor(DashPainter.PANEL);
            final float radius = 18 * mDp;
            /*
             * The rounded corners are painted over the map instead of cutting the map to them (18.11.2026: "die Karte
             * vom Dashboard laeuft immer noch unfluessig"): a page cut to a rounded outline is drawn into a picture of
             * its own first and laid in from there, with every frame of a drag or a pinch.
             */
            mMapFrame.setForeground(new android.graphics.drawable.Drawable() {
                private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                private final android.graphics.Path mPath = new android.graphics.Path();
                @Override public void draw(android.graphics.Canvas c) {
                    android.graphics.Rect b = getBounds();
                    mPaint.setColor(DashPainter.BG);
                    mPath.reset();
                    mPath.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                    mPath.addRect(b.left, b.top, b.right, b.bottom, android.graphics.Path.Direction.CW);
                    mPath.addRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, android.graphics.Path.Direction.CW);
                    c.drawPath(mPath, mPaint);
                }
                @Override public void setAlpha(int a) { }
                @Override public void setColorFilter(android.graphics.ColorFilter f) { }
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            });

            addWeb(link, null);
            mLink = link;
            mLoadedAt = SystemClock.uptimeMillis();
            mMain.removeCallbacks(mRefresh);
            mMain.postDelayed(mRefresh, MAP_REFRESH_MS);

            /* eigene Knoepfe: unten an der Fahrerseite, ueber dem Platz der Bedienleiste der Auto-Oberflaeche */
            mButtonCol = new LinearLayout(mHostCtx);
            mButtonCol.setOrientation(LinearLayout.VERTICAL);
            mButtonCol.setVisibility(View.GONE);
            mMapMode = "";
            for (int k = 0; k < mButtons.length; k++) {
                mButtons[k] = new MapButton(mHostCtx, k);
                final int kind = k;
                mButtons[k].setOnClickListener(v -> { hidePick(); pressed(kind); });
                /* 18.11.2026 ("die Buttons auf der Map nur 2/3 so gross wie jetzt, und der Minus-Button wird nicht richtig
                   dargestellt"): 56 -> 38 dp, the gap 14 -> 10 dp - all six fit on the map again, the minus was cut off below */
                LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams((int) (38 * mDp), (int) (38 * mDp));
                lb.topMargin = (int) (10 * mDp);
                mButtonCol.addView(mButtons[k], lb);
            }
            FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START);
            lc.setMargins((int) (10 * mDp), 0, 0, (int) (64 * mDp));
            mMapFrame.addView(mButtonCol, lc);

            mDash = new View(mHostCtx) {
                private final Rect mArea = new Rect();
                @Override protected void onDraw(Canvas cv) {
                    mArea.set(0, 0, getWidth(), getHeight());
                    mPainter.paint(cv, mArea, mDp, false);
                }
            };
            LinearLayout.LayoutParams lpMap = new LinearLayout.LayoutParams(0, -1, 56);
            lpMap.setMargins((int) (24 * mDp), (int) (24 * mDp), 0, (int) (24 * mDp));
            row.addView(mMapFrame, lpMap);
            row.addView(mDash, new LinearLayout.LayoutParams(0, -1, 44));
            mRoot.addView(row, new FrameLayout.LayoutParams(-1, -1));
            /* narrow (a share of the split): only the values, no map */
            mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (mMapFrame == null) return;
                int vis = (r - l) > 1.35f * (b - t) ? View.VISIBLE : View.GONE;
                if (mMapFrame.getVisibility() != vis) mMapFrame.post(() -> { if (mMapFrame != null) mMapFrame.setVisibility(vis); });
            });
            Log.i(TAG, "Buehne in Mates Oberflaeche, " + dpi + " dpi");

            mMain.post(mTick);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Buehne nicht moeglich: " + e);
            mHub.telemetry.note("stage", e.getClass().getSimpleName() + ": " + e.getMessage());
            stop();
            return false;
        }
    }

    /** The map's browser in the map frame, below the buttons, loading the server's map page. Again after its renderer died. */
    /**
     * [then] null: the first page (or after the renderer died). Otherwise the 10-minute refresh (L-003): the new page is
     * loaded invisibly beneath the shown one, put in the same view ("near"/"overview"), faded in, and the old one goes -
     * the range is new without the map blinking.
     */
    private void addWeb(final Link link, final String then) {
        final WebView web = new WebView(mHostCtx);
        if (then == null) mWeb = web; else mNextWeb = web;
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);          /* die Karte ist eine Leaflet-Seite */
        ws.setDomStorageEnabled(true);          /* merkt sich die Kartenschalter */
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setSupportMultipleWindows(false);
        web.setBackgroundColor(DashPainter.PANEL);
        /* the page at Mate's size, as it was on its own display of that density (a percentage, 100 = one pixel per CSS pixel) */
        web.setInitialScale(Math.round(mDp * 100));
        web.setAlpha(0f);                       /* unsichtbar, bis die Karte steht (onPageFinished) */
        watchTouches(web);
        CookieManager.getInstance().setAcceptCookie(true);
        final String origin = origin(link.url);
        web.setWebViewClient(new WebViewClient() {
            /* Kacheln der Karte aus dem Speicher auf dem Geraet (O109), sonst geholt und abgelegt */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest rq) {
                return TileCache.answer(mCtx, rq);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest rq) {
                /* die Seite bleibt auf dem Server der Lese-API; alles andere wird nicht geoeffnet */
                return !origin.equals(origin(rq.getUrl().toString()));
            }
            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail detail) {
                /* Der Renderer der Karte wurde beendet (Speichermangel am Geraet): nur die Karte neu aufbauen. Ohne diese
                   Behandlung beendet WebView die ganze App - Mate fiele mitten in der Fahrt aus. */
                Log.w(TAG, "Karte: Renderer beendet (" + (detail.didCrash() ? "Absturz" : "Speicher") + "), wird neu aufgebaut");
                mHub.telemetry.note("stage", "Karte neu aufgebaut (Renderer beendet)");
                net.prototux.aare.DiagLog.INSTANCE.add("dashboard page: its browser part " + (detail.didCrash() ? "crashed" : "was ended for memory") + " - the page is built anew");
                if (mWeb == v) {
                    if (mMapFrame != null) mMapFrame.removeView(v);
                    v.destroy();
                    mWeb = null;
                    mMain.postDelayed(() -> { if (mWeb == null && mMapFrame != null) addWeb(link, null); }, 1500);
                } else {
                    if (mNextWeb == v) { mNextWeb = null; if (mMapFrame != null) mMapFrame.removeView(v); }
                    v.destroy();
                }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest rq, android.webkit.WebResourceError err) {
                if (!rq.isForMainFrame()) return;
                mHub.telemetry.note("stage", "Seite nicht geladen: " + err.getDescription() + " (" + err.getErrorCode() + ")");
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest rq, android.webkit.WebResourceResponse r) {
                if (!rq.isForMainFrame()) return;
                mHub.telemetry.note("stage", "Seite nicht geladen: der Server antwortete " + r.getStatusCode());
            }
            @Override public void onPageFinished(WebView v, String url) {
                mHub.telemetry.note("stage", "Seite geladen" + (v == mNextWeb ? " (Nachladen)" : ""));
                /* die Bedienung der Karte uebernimmt die App */
                if (v == mNextWeb) {
                    /* the refresh: no glide in, the view the driver had */
                    v.evaluateJavascript("window.__mateNoAuto=true;window.__mateThen='" + then + "';window.__mateTiles=" + MateTiles.PATH_JS + ";" + MAP_SCRIPT, null);
                    swapWhenReady(v, 0);
                    return;
                }
                v.evaluateJavascript((sAutoDone ? "window.__mateNoAuto=true;" : "") + "window.__mateTiles=" + MateTiles.PATH_JS + ";" + MAP_SCRIPT, null);
                /* Erst zeigen, wenn die Seite gezeichnet ist und die ersten Kacheln da sind: weich einblenden statt
                   dem Aufbau zusehen lassen. */
                v.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long id) {
                        mMain.postDelayed(() -> {
                            if (mWeb == v) {
                                v.animate().alpha(1f).setDuration(MAP_FADE_MS).start();
                                Log.i(TAG, "Karte eingeblendet");
                            }
                        }, MAP_SETTLE_MS);
                    }
                });
            }
        });
        /* beneath everything (a refreshed page beneath the shown one) */
        mMapFrame.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + link.token);
        web.loadUrl(link.url + "/display", headers);
    }

    /**
     * The refreshed page takes the shown one's place once its map has its view (at most ~12 s; a page that never gets
     * there is dropped and the old one stays).
     */
    private void swapWhenReady(final WebView next, final int tries) {
        if (next != mNextWeb) return;
        next.evaluateJavascript("window.__mate ? __mate.mode : ''", m -> {
            if (next != mNextWeb) return;
            String mode = m == null ? "" : m.replace("\"", "");
            boolean ready = mode.equals("near") || mode.equals("overview");
            if (!ready && tries < 24) { mMain.postDelayed(() -> swapWhenReady(next, tries + 1), 500); return; }
            if (!ready) {
                Log.w(TAG, "Karte: neu geladene Seite ohne Karte, die alte bleibt");
                mNextWeb = null;
                if (mMapFrame != null) mMapFrame.removeView(next);
                next.destroy();
                return;
            }
            /* settled a moment (tiles, the glide to the car), then over the old one and faded in */
            mMain.postDelayed(() -> {
                if (next != mNextWeb || mMapFrame == null) return;
                final WebView old = mWeb;
                mNextWeb = null;
                mWeb = next;
                /* the stage was put away meanwhile: the new page rests as the old one did */
                if (mParked) rest(true);
                /* above the old page, below the buttons */
                mMapFrame.removeView(next);
                mMapFrame.addView(next, old != null ? mMapFrame.indexOfChild(old) + 1 : 0, new FrameLayout.LayoutParams(-1, -1));
                next.animate().alpha(1f).setDuration(SWAP_FADE_MS).withEndAction(() -> {
                    if (old != null) {
                        if (mMapFrame != null) mMapFrame.removeView(old);
                        old.destroy();
                    }
                    Log.i(TAG, "Karte: neuer Umkreis eingeblendet");
                }).start();
            }, 1500);
        });
    }

    private static String origin(String url) {
        Uri u = Uri.parse(url);
        return u.getScheme() + "://" + u.getHost() + ":" + u.getPort();
    }

    void stop() {
        mParked = false;
        mMain.removeCallbacks(mParkEnd);
        mMain.removeCallbacks(mTick);
        mMain.removeCallbacks(mRefresh);
        mTouching = false;
        try {
            if (mRoot != null && mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
            if (mWeb != null) {
                mWeb.stopLoading();
                mWeb.destroy();
            }
            if (mNextWeb != null) mNextWeb.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "Abbau: " + e);
        }
        mWeb = null;
        mNextWeb = null;
        mLink = null;
        mButtonCol = null;
        mDash = null;
        mRoot = null;
        mMapFrame = null;
        mPick = null;
        mHostCtx = null;
    }

    void changed() {
        if (mDash != null) mDash.postInvalidate();
    }

    /* ── Beruehrungen ─────────────────────────────────────────────────────── */

    /**
     * The map's page gets the fingers as they are; here only: where one went down (for the long press), a tap beside the
     * popup closes it, and a held map is not reloaded meanwhile.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void watchTouches(WebView web) {
        web.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mTouching = true;
                    hidePick();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mTouching = false;
                    break;
                default:
                    break;
            }
            return false;
        });
        web.setOnLongClickListener(v -> {
            java.util.function.Consumer<String> go = sGo;
            if (go == null || v != mWeb) return false;
            longPress(mDownX, mDownY, go);
            return true;
        });
    }

    /** Where a place picked on the map goes ("lat,lon"): the car's navigation; set by the car screen. */
    static volatile java.util.function.Consumer<String> sGo;

    /** Debug and the screen's "Neu laden": the page built anew. */
    void reload() {
        Context host = mHostCtx;
        int dpi = mDpiI;
        android.view.ViewGroup parent = mRoot != null && mRoot.getParent() instanceof android.view.ViewGroup ? (android.view.ViewGroup) mRoot.getParent() : null;
        if (host == null) return;
        stop();
        if (start(host, dpi) && parent != null) parent.addView(mRoot, new android.view.ViewGroup.LayoutParams(-1, -1));
    }

    /* ── Langes Druecken: Popup "Navigieren" ─────────────────────────────── */

    private PickPopup mPick;
    private String mPickAt;
    private java.util.function.Consumer<String> mGo;
    private final Runnable mPickHide = this::hidePick;

    /**
     * Langes Druecken auf die Karte (18.11.2026: "Langer Klick. Navigieren-Popup"): ein Punkt dort und ein Popup
     * "Navigieren"; getippt geht der Ort ("lat,lon") an [go] - die Navigation des Autos (Mate Navi oder die gewaehlte App).
     * [x], [y] in den Pixeln der Seite.
     */
    private void longPress(float x, float y, java.util.function.Consumer<String> go) {
        if (mWeb == null || mMapFrame == null) return;
        final int px = (int) x, py = (int) y;
        mWeb.evaluateJavascript(String.format(java.util.Locale.US, "window.__mate ? __mate.pick(%d,%d) : ''", px, py), v -> {
            String at = v == null ? "" : v.replace("\"", "");
            if (!at.matches("-?\\d+\\.\\d+,-?\\d+\\.\\d+") || mMapFrame == null) return;
            mPickAt = at;
            mGo = go;
            if (mPick == null) {
                mPick = new PickPopup(mMapFrame.getContext());
                mPick.setOnTouchListener((pv, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) pickTap(e.getX());
                    return true;
                });
                mMapFrame.addView(mPick, new FrameLayout.LayoutParams((int) (300 * mDp), (int) (76 * mDp)));
            }
            /* ueber dem Punkt, ganz auf der Karte */
            float w = 300 * mDp, h = 76 * mDp;
            mPick.setX(Math.max(8 * mDp, Math.min(mMapFrame.getWidth() - w - 8 * mDp, px - w / 2)));
            mPick.setY(py - h - 24 * mDp >= 8 * mDp ? py - h - 24 * mDp : Math.min(mMapFrame.getHeight() - h - 8 * mDp, py + 24 * mDp));
            mPick.setVisibility(View.VISIBLE);
            mPick.bringToFront();
            mMain.removeCallbacks(mPickHide);
            mMain.postDelayed(mPickHide, 12000);
            Log.i(TAG, "Karte lange gedrueckt: Popup Navigieren");
        });
    }

    private void hidePick() {
        mMain.removeCallbacks(mPickHide);
        if (mPick == null || mPick.getVisibility() != View.VISIBLE) return;
        mPick.setVisibility(View.GONE);
        js("window.__mate && __mate.unpick()");
    }

    /** A tap on the popup at [x] (its own pixels): "Navigieren" on the larger part, the cross closes it. */
    private void pickTap(float x) {
        if (mPick == null || mPick.getVisibility() != View.VISIBLE) return;
        boolean go = x < mPick.getWidth() - mPick.getHeight();
        String at = mPickAt;
        java.util.function.Consumer<String> g = mGo;
        hidePick();
        if (go && at != null && g != null) {
            Log.i(TAG, "Navigieren zum gedrueckten Punkt");
            g.accept(at);
        }
    }

    /** Das Popup: links "Navigieren" mit Pfeil (der grosse Teil), rechts ein Kreuz zum Schliessen. */
    private final class PickPopup extends View {
        private final android.graphics.Paint mP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final String mText = mCtx.getString(R.string.dash_pick_navigate);

        PickPopup(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = 16 * mDp, split = w - h;
            mP.setStyle(android.graphics.Paint.Style.FILL);
            mP.setColor(0xF2161B22);
            cv.drawRoundRect(0, 0, w, h, r, r, mP);
            mP.setColor(0xF20E3B36);
            cv.drawRoundRect(0, 0, split, h, r, r, mP);
            cv.drawRect(split - r, 0, split, h, mP);
            mP.setStyle(android.graphics.Paint.Style.STROKE);
            mP.setStrokeWidth(1.2f * mDp);
            mP.setColor(0xFF1DE9B6);
            cv.drawRoundRect(mDp, mDp, w - mDp, h - mDp, r, r, mP);
            mP.setColor(0xFF30363D);
            cv.drawLine(split, 10 * mDp, split, h - 10 * mDp, mP);
            /* Pfeil der Navigation */
            float cx = 36 * mDp, cy = h / 2, u = mDp;
            android.graphics.Path a = new android.graphics.Path();
            a.moveTo(cx, cy - 14 * u); a.lineTo(cx + 11 * u, cy + 13 * u); a.lineTo(cx, cy + 7 * u); a.lineTo(cx - 11 * u, cy + 13 * u); a.close();
            mP.setStyle(android.graphics.Paint.Style.FILL);
            mP.setColor(0xFF1DE9B6);
            cv.drawPath(a, mP);
            mP.setColor(0xFFF0F6FC);
            mP.setTextSize(24 * mDp);
            mP.setFakeBoldText(true);
            cv.drawText(mText, 62 * mDp, cy + 8.5f * mDp, mP);
            /* Kreuz */
            float xc = split + h / 2, k = 11 * mDp;
            mP.setStyle(android.graphics.Paint.Style.STROKE);
            mP.setStrokeWidth(3 * mDp);
            mP.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            mP.setColor(0xFFF0F6FC);
            cv.drawLine(xc - k, cy - k, xc + k, cy + k, mP);
            cv.drawLine(xc - k, cy + k, xc + k, cy - k, mP);
        }
    }
}
