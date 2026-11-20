/*
 * Musik in Mate: Musik vom eigenen Plex-Server, als Medien-App fuer Android Auto und AARE (und jede andere
 * Oberflaeche, die Medien-Apps durchsucht). Stufen: Wurzel -> Wiedergabelisten -> Titel.
 * Tippt man einen Titel an, spielt die ganze Liste ab dieser Stelle (nicht nur der eine Titel).
 * Suche: Titel ueber den Suchdienst von Plex.
 *
 * Kennungen: "root", "pl:<ratingKey>" (Liste), "tr:<ratingKey Liste>:<Nummer>" (Titel in seiner Liste),
 * "sr:<Nummer>" (Treffer der letzten Suche).
 */
package net.prototux.aare.music;

import net.prototux.aare.R;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class MusicService extends MediaLibraryService {
    private static final String TAG = "MateMusic";
    /** Kantenlaenge der Bilder, die vom Server geholt werden: reicht fuer die Wiedergabeansicht, klein genug fuer viele Kacheln. */
    private static final int ART_PX = 640;
    private static final String ROOT = "root";
    private static final String EXTRA_KEY = "plex_key";
    /** Eigene Knoepfe der Wiedergabe: Mischen und Wiederholen (aus - alles - dieser Titel). */
    private static final androidx.media3.session.SessionCommand SHUFFLE = new androidx.media3.session.SessionCommand("mate.shuffle", Bundle.EMPTY);
    private static final androidx.media3.session.SessionCommand REPEAT = new androidx.media3.session.SessionCommand("mate.repeat", Bundle.EMPTY);

    private final ListeningExecutorService mIo = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private List<Plex.Item> mFound = new ArrayList<>();
    private ExoPlayer mPlayer;
    private MediaLibrarySession mSession;
    private Callback mCallback;
    /** Wortschatz fuer Sprachwuensche: Kuenstler und Alben der Sammlung, einige Stunden gemerkt. */
    private List<net.prototux.aare.speech.VoiceMatch.Name> mVocab;
    private long mVocabAt;

    /**
     * Sprachwunsch (O56): erkannter Text -> Kuenstler oder Album der eigenen Sammlung (Abgleich nach Schreibweise und Klang,
     * VoiceMatch) -> spielt es: ein Album von vorn, einen Kuenstler gemischt. [done] bekommt, was gespielt wird, oder null.
     * Falsch, wenn Mate Musik gerade nicht laeuft.
     */
    public static boolean voice(String text, java.util.function.Consumer<String> done) {
        MusicService s = sRunning;
        if (s == null) return false;
        s.mIo.execute(() -> {
            String said = null;
            try {
                Plex plex = s.plex();
                long now = System.currentTimeMillis();
                if (s.mVocab == null || now - s.mVocabAt > 6 * 3600_000L) {
                    List<net.prototux.aare.speech.VoiceMatch.Name> v = new ArrayList<>();
                    for (Plex.Item a : plex.artists(0, 20000))
                        v.add(new net.prototux.aare.speech.VoiceMatch.Name(net.prototux.aare.speech.VoiceMatch.Kind.ARTIST, a.key, a.title, ""));
                    for (Plex.Item a : plex.albums(0, 50000))
                        v.add(new net.prototux.aare.speech.VoiceMatch.Name(net.prototux.aare.speech.VoiceMatch.Kind.ALBUM, a.key, a.title,
                                a.album != null && !a.album.isEmpty() ? a.album : (a.artist == null ? "" : a.artist)));  /* beim Album: parentTitle = Kuenstler */
                    for (Plex.Item a : plex.playlists())
                        v.add(new net.prototux.aare.speech.VoiceMatch.Name(net.prototux.aare.speech.VoiceMatch.Kind.PLAYLIST, a.key, a.title, ""));
                    s.mVocab = v;
                    s.mVocabAt = now;
                    Log.i(TAG, "Wortschatz fuer Sprachwuensche: " + v.size() + " Namen");
                }
                net.prototux.aare.speech.VoiceMatch.Hit hit = net.prototux.aare.speech.VoiceMatch.INSTANCE.pick(text, s.mVocab);
                Log.i(TAG, "Sprachwunsch \"" + text + "\" -> " + hit);
                if (hit != null) {
                    net.prototux.aare.speech.VoiceMatch.Name n = hit.getName();
                    boolean album = n.getKind() == net.prototux.aare.speech.VoiceMatch.Kind.ALBUM;
                    boolean list = n.getKind() == net.prototux.aare.speech.VoiceMatch.Kind.PLAYLIST;
                    MediaSession.MediaItemsWithStartPosition q = s.mCallback.resolve(
                            list ? "pl:" + n.getKey() : album ? "at:" + n.getKey() + ":0" : "sa:" + n.getKey(), C.TIME_UNSET);
                    if (!q.mediaItems.isEmpty()) {
                        said = album && !n.getArtist().isEmpty() ? n.getName() + " von " + n.getArtist() : n.getName();
                        /* erst ansagen, dann spielen: vorbereitet wird sofort, gestartet, wenn "Spiele ..." gesprochen ist */
                        final long after = 1500 + 70L * said.length();
                        s.mMain.post(() -> {
                            s.mPlayer.pause();
                            s.mPlayer.setMediaItems(q.mediaItems, q.startIndex, q.startPositionMs);
                            s.mPlayer.prepare();
                        });
                        s.mMain.postDelayed(() -> s.mPlayer.play(), after);
                    }
                }
                if (hit == null) {
                    /* kein Kuenstler, Album oder Liste: ein bestimmter Titel? Plex sucht ihn selbst (nicht alle Titel im Speicher) */
                    String q = net.prototux.aare.speech.VoiceMatch.INSTANCE.query(text);
                    List<Plex.Item> tracks = new ArrayList<>();
                    if (q.length() >= 3) {
                        /* nur wirklich aehnliche Titel: lose Treffer ("Vic O") nehmen sonst dem zweiten Erkennungsversuch den Platz */
                        for (Plex.Item t : plex.searchTracks(q))
                            if (net.prototux.aare.speech.VoiceMatch.INSTANCE.similarity(q, t.title) >= 0.6) tracks.add(t);
                    }
                    if (tracks.isEmpty()) {
                        /* die Erkennung trifft ein Wort oft nicht ("he Thomas"): mit dem laengsten Wort suchen und den
                           aehnlichsten Titel nehmen */
                        String longest = "";
                        for (String w : q.split(" ")) if (w.length() > longest.length()) longest = w;
                        if (longest.length() >= 4) {
                            List<Plex.Item> some = plex.searchTracks(longest);
                            Plex.Item best = null; double bestScore = 0.6;
                            for (Plex.Item t : some) {
                                double sc = net.prototux.aare.speech.VoiceMatch.INSTANCE.similarity(q, t.title);
                                if (sc > bestScore) { bestScore = sc; best = t; }
                            }
                            if (best != null) { tracks = new ArrayList<>(); tracks.add(best); }
                        }
                    }
                    Log.i(TAG, "Titelsuche \"" + q + "\" -> " + tracks.size());
                    if (!tracks.isEmpty()) {
                        s.mFound = tracks;
                        MediaSession.MediaItemsWithStartPosition qq = s.mCallback.resolve("sr:0", C.TIME_UNSET);
                        Plex.Item t = tracks.get(0);
                        said = t.artist != null && !t.artist.isEmpty() ? t.title + " von " + t.artist : t.title;
                        final long after = 1500 + 70L * said.length();
                        s.mMain.post(() -> { s.mPlayer.pause(); s.mPlayer.setMediaItems(qq.mediaItems, qq.startIndex, qq.startPositionMs); s.mPlayer.prepare(); });
                        s.mMain.postDelayed(() -> s.mPlayer.play(), after);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Sprachwunsch: " + e.getClass().getSimpleName());
            }
            final String r = said;
            s.mMain.post(() -> done.accept(r));
        });
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPlayer = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        /* the shuffle switch survives a restart; ExoPlayer draws a new random order each time it is switched on */
        mPlayer.setShuffleModeEnabled(Plex.prefs(this).getBoolean("shuffle_on", false));
        MusicSyncJob.schedule(this);
        mCallback = new Callback();
        mSession = new MediaLibrarySession.Builder(this, mPlayer, mCallback).setMediaButtonPreferences(buttons()).build();
        sRunning = this;
        /* Wo die Wiedergabe steht, wird gemerkt: nach einem Neustart (App, Tablet, neue Fahrt) geht es dort weiter. */
        mPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) { remember(); level(); }

            @Override
            public void onShuffleModeEnabledChanged(boolean on) { mSession.setMediaButtonPreferences(buttons()); }

            @Override
            public void onRepeatModeChanged(int mode) { mSession.setMediaButtonPreferences(buttons()); }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                remember();
                mMain.removeCallbacks(mKeep);
                if (playing) mMain.postDelayed(mKeep, KEEP_MS);
            }
        });
    }

    /** Die beiden Knoepfe in ihrem jetzigen Zustand: das Zeichen sagt, was eingestellt ist. */
    private ImmutableList<androidx.media3.session.CommandButton> buttons() {
        boolean shuffle = mPlayer != null && mPlayer.getShuffleModeEnabled();
        int repeat = mPlayer == null ? androidx.media3.common.Player.REPEAT_MODE_OFF : mPlayer.getRepeatMode();
        return ImmutableList.of(
                new androidx.media3.session.CommandButton.Builder(shuffle ? androidx.media3.session.CommandButton.ICON_SHUFFLE_ON
                        : androidx.media3.session.CommandButton.ICON_SHUFFLE_OFF)
                        .setDisplayName(getString(shuffle ? R.string.music_shuffle_on : R.string.music_shuffle_off))
                        .setSessionCommand(SHUFFLE).build(),
                new androidx.media3.session.CommandButton.Builder(repeat == androidx.media3.common.Player.REPEAT_MODE_ONE
                        ? androidx.media3.session.CommandButton.ICON_REPEAT_ONE : repeat == androidx.media3.common.Player.REPEAT_MODE_ALL
                        ? androidx.media3.session.CommandButton.ICON_REPEAT_ALL : androidx.media3.session.CommandButton.ICON_REPEAT_OFF)
                        .setDisplayName(getString(repeat == androidx.media3.common.Player.REPEAT_MODE_ONE ? R.string.music_repeat_one
                                : repeat == androidx.media3.common.Player.REPEAT_MODE_ALL ? R.string.music_repeat_all : R.string.music_repeat_off))
                        .setSessionCommand(REPEAT).build());
    }

    /**
     * Eine Stimme spricht (Nachricht wird vorgelesen): die Musik geht auf den eingestellten Anteil (Vorgabe ein Zehntel).
     * Der Player nimmt sich beim Verlust des Ton-Vorrangs selbst auf ein Fuenftel zurueck.
     */
    private static volatile MusicService sRunning;

    public static void voice(boolean speaking, int percent) {
        MusicService s = sRunning;
        /* der Player selbst geht auf 20 %; der Rest bis zum gewuenschten Wert kommt von hier (hoechstens 20 %) */
        float rest = Math.max(0f, Math.min(1f, percent / 20f));
        if (s != null) s.mMain.post(() -> { s.mVoice = speaking ? rest : 1f; s.volume(); });
    }

    /*
     * Lautstaerke-Anpassung: Plex misst die Lautheit jedes Titels und nennt, um wie viele dB er vom Bezugspegel abweicht.
     * Laute Titel werden um diesen Wert zurueckgenommen, leise bis zur Reserve angehoben (ueber 100 % kann der Player nicht).
     */
    private static final float LEVEL_MIN_DB = -20f;
    private float mBoostDb;     /* Anhebung ueber den Bezugspegel des Servers, aus den Einstellungen */
    private float mVoice = 1f, mLevel = 1f;
    private final java.util.Map<String, Float> mGains = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Float>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Float> e) { return size() > 300; }
    });

    private void volume() {
        if (mPlayer != null) mPlayer.setVolume(mLevel * mVoice);
    }

    @Nullable
    private static String keyOf(@Nullable MediaItem item) {
        Bundle x = item == null ? null : item.mediaMetadata.extras;
        return x == null ? null : x.getString(EXTRA_KEY);
    }

    /** Den Pegel des laufenden Titels einstellen und den des naechsten schon holen (auf dem Hauptfaden rufen). */
    private void level() {
        if (mPlayer == null) return;
        net.prototux.aare.Settings settings = new net.prototux.aare.Settings(this);
        boolean on = settings.getMusicLevel();
        mBoostDb = settings.getMusicLevelBoostDb();
        String key = on ? keyOf(mPlayer.getCurrentMediaItem()) : null;
        Float known = key == null ? null : mGains.get(key);
        /* unbekannt: bis der Wert da ist, den bisherigen Pegel halten - kein Sprung nach oben mitten im Einsatz */
        if (!on) mLevel = 1f;
        else if (known != null) mLevel = factor(known);
        volume();
        int next = mPlayer.getNextMediaItemIndex();
        String after = on && next != C.INDEX_UNSET ? keyOf(mPlayer.getMediaItemAt(next)) : null;
        for (String k : new String[] { known == null ? key : null, after }) {
            if (k == null || mGains.containsKey(k)) continue;
            mIo.execute(() -> {
                float gain = Float.NaN;
                try {
                    Plex plex = Plex.load(this);
                    if (plex != null) {
                        float[] l = plex.loudness(k);
                        gain = l[0];
                        Log.i(TAG, "Pegel " + k + ": gain " + l[0] + " dB, Album " + l[1] + " dB, Lautheit " + l[2] + ", Spitze " + l[3]);
                    }
                } catch (Exception e) {
                    return;     /* spaeter noch einmal */
                }
                mGains.put(k, gain);
                mMain.post(() -> { if (mPlayer != null && k.equals(keyOf(mPlayer.getCurrentMediaItem()))) level(); });
            });
        }
    }

    private float factor(float gainDb) {
        if (Float.isNaN(gainDb)) return 1f;
        float db = Math.max(LEVEL_MIN_DB, Math.min(0f, gainDb + mBoostDb));
        return (float) Math.pow(10, db / 20);
    }

    private static final long KEEP_MS = 15_000;
    private final android.os.Handler mMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mKeep = new Runnable() {
        @Override
        public void run() {
            remember();
            mMain.postDelayed(this, KEEP_MS);
        }
    };

    /** Titel und Stelle der Wiedergabe sichern. Treffer einer Suche ("sr:") gibt es nach einem Neustart nicht mehr. */
    private void remember() {
        MediaItem item = mPlayer.getCurrentMediaItem();
        /* Treffer einer Suche und die Mischung eines Genres lassen sich nach einem Neustart nicht wieder aufbauen */
        if (item == null || item.mediaId.startsWith("sr:") || item.mediaId.startsWith("gk:")) return;
        Plex.prefs(this).edit().putString("last_id", item.mediaId)
                .putLong("last_pos", Math.max(0, mPlayer.getCurrentPosition())).apply();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controller) {
        return mSession;
    }

    @Override
    public void onDestroy() {
        sRunning = null;
        mMain.removeCallbacks(mKeep);
        remember();
        mSession.release();
        mPlayer.release();
        mIo.shutdown();
        super.onDestroy();
    }

    /** Hinweis an die Oberflaeche im Auto: diesen Eintrag als Kachel zeigen (2 = Raster). */
    private static final String STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";

    private static MediaItem folder(String id, String title, String subtitle, android.net.Uri art) {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt(STYLE_SINGLE_ITEM, 2);
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false).setArtworkUri(art)
                .setExtras(extras).setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build()).build();
    }

    /** Erster Eintrag jeder Liste: die ganze Liste in zufaelliger Reihenfolge. */
    private static final int PAGE_MAX = 100;        /* Titel je Seite beim Blaettern */
    private static final int QUEUE_MAX = 500;       /* Titel in der Warteschlange */
    private static final int SHUFFLE_ALL = 2000;    /* bis hierher wird die ganze Liste gemischt */
    private static final int CHUNK = 500, PIECE = 20;

    private MediaItem shuffle(String key) {
        return shuffleOf("sh:" + key, R.string.music_shuffle_hint);
    }

    /** Ein Reiter der Sammlung: Wiedergabelisten, Kuenstler, Alben. */
    private static final String LISTS = "pls", ARTISTS = "ars", ALBUMS = "als", GENRES = "ges";

    private static MediaItem section(String id, String title) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build();
    }

    private MediaItem shuffleOf(String id, int hint) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(getString(R.string.music_shuffle))
                .setArtist(getString(hint))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build();
    }

    /** Titel fuer die Liste (ohne Adresse) bzw. fuer den Player (mit Adresse der Datei). */
    private MediaItem track(String id, Plex.Item t, Plex plex, boolean playable) {
        MediaMetadata.Builder m = new MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist)
                .setAlbumTitle(t.album).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);
        /* abonnierte Listen (Offline): Bild und Datei vom Geraet, sonst vom Server */
        java.io.File artFile = Offline.art(this, t.thumb);
        if (artFile != null) m.setArtworkUri(Offline.fileUri(artFile));
        else if (plex != null && t.thumb != null) m.setArtworkUri(plex.art(t.thumb, ART_PX));
        /* Bewertung (fuenf Sterne, halbe moeglich) und der Schluessel des Titels am Server, um sie zu aendern */
        m.setUserRating(t.stars > 0 ? new androidx.media3.common.StarRating(5, t.stars / 2f) : new androidx.media3.common.StarRating(5));
        Bundle extras = new Bundle();
        extras.putString(EXTRA_KEY, t.key);
        m.setExtras(extras);
        MediaItem.Builder b = new MediaItem.Builder().setMediaId(id).setMediaMetadata(m.build());
        java.io.File file = playable ? Offline.track(this, t.key) : null;
        if (file != null) b.setUri(Offline.fileUri(file));
        else if (playable && plex != null && t.part != null) b.setUri(plex.media(t.part));
        return b.build();
    }

    /** Der Ausschnitt, den der Aufrufer verlangt: media3 bricht ab, wenn eine Antwort laenger als die Seite ist. */
    private static ImmutableList<MediaItem> page(ImmutableList<MediaItem> all, int page, int pageSize) {
        if (pageSize <= 0 || pageSize == Integer.MAX_VALUE) return all;
        long from = (long) Math.max(0, page) * pageSize;
        if (from >= all.size()) return ImmutableList.of();
        return all.subList((int) from, (int) Math.min(all.size(), from + pageSize));
    }

    /** Noch kein Server verbunden (Anmeldung am Telefon steht aus). */
    private static final class NotSetUp extends IOException {
        NotSetUp() { super("nicht eingerichtet"); }
    }

    /** Was die Oberflaeche im Auto bei einem Fehler zeigt: in Worten, die sagen, was zu tun ist. Nie Adressen. */
    private <T> LibraryResult<T> failed(String what, IOException e) {
        Log.w(TAG, what + ": " + e.getClass().getSimpleName());
        return LibraryResult.ofError(new androidx.media3.session.SessionError(androidx.media3.session.SessionError.ERROR_IO,
                getString(errorText(e))));
    }

    /**
     * Warum es nicht ging, so genau wie das Geraet es weiss (Testfahrt B12: "Server-Error" am WLAN des Autos,
     * dessen Bestaetigungsseite noch offen war): kein Netz, Netz ohne Internet, Anmeldung abgelaufen, sonst der Server.
     */
    private int errorText(IOException e) {
        if (e instanceof NotSetUp) return R.string.music_error_setup;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return R.string.music_error_offline;
            if (nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return R.string.music_error_portal;
            if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        ? R.string.music_error_wifi_no_internet : R.string.music_error_offline;
            }
        } catch (RuntimeException ignored) {
        }
        String m = e.getMessage();
        if (m != null && m.contains("401")) return R.string.music_error_login;
        return R.string.music_error_server;
    }

    private Plex plex() throws IOException {
        Plex p = PlexAccount.current(this);
        if (p == null) throw new NotSetUp();
        return p;
    }

    /** Netz mit Internet (geprueft vom System)? Ohne spielen abonnierte Listen nur ihre geladenen Titel. */
    private boolean online() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Die Titel einer abonnierten Liste, ohne Netz nur die geladenen: { Index in der Liste, Titel }. Null = nicht offline. */
    private List<Object[]> offlineTracks(String key) {
        List<Plex.Item> l = Offline.list(this, key);
        if (l == null) return null;
        boolean net = online();
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++)
            if (net || Offline.track(this, l.get(i).key) != null) out.add(new Object[] { i, l.get(i) });
        return out;
    }

    private LibraryResult<ImmutableList<MediaItem>> offlineChildren(String parentId, int page, int size, @Nullable LibraryParams params) {
        if (!parentId.startsWith("pl:")) return null;
        String key = parentId.substring(3);
        List<Object[]> l = offlineTracks(key);
        if (l == null) return null;
        Plex plex = Plex.load(this);
        /* wie online: Eintrag 0 ist "Mischen", Eintrag g >= 1 der Titel g - 1 */
        ImmutableList.Builder<MediaItem> out = ImmutableList.builder();
        long from = (long) Math.max(0, page) * size;
        if (from == 0 && l.size() > 1) out.add(shuffle(key));
        int first = (int) Math.max(0, from - 1), want = from == 0 ? size - 1 : size;
        for (int i = first; i < Math.min(l.size(), first + want); i++)
            out.add(track("tr:" + key + ":" + l.get(i)[0], (Plex.Item) l.get(i)[1], plex, false));
        return LibraryResult.ofItemList(out.build(), params);
    }

    /** Warteschlange aus einer abonnierten Liste ("tr:", "pl:", "sh:"); null, wenn die Liste nicht offline vorliegt. */
    private MediaSession.MediaItemsWithStartPosition offlineQueue(String id, long positionMs) {
        if (!(id.startsWith("tr:") || id.startsWith("pl:") || id.startsWith("sh:"))) return null;
        String[] p = id.split(":");
        if (p.length < 2) return null;
        List<Object[]> l = offlineTracks(p[1]);
        if (l == null || l.isEmpty()) return null;
        Plex plex = Plex.load(this);
        List<MediaItem> out = new ArrayList<>();
        int start = 0;
        if (id.startsWith("sh:")) {
            java.util.Collections.shuffle(l, new java.security.SecureRandom());
            for (int i = 0; i < Math.min(l.size(), QUEUE_MAX); i++)
                out.add(track("tr:" + p[1] + ":" + l.get(i)[0], (Plex.Item) l.get(i)[1], plex, true));
        } else {
            int at = id.startsWith("tr:") && p.length > 2 ? Integer.parseInt(p[2]) : 0;
            /* ganz hinein, wenn sie passt; sonst ab dem angetippten Titel */
            int from = 0;
            for (int i = 0; i < l.size(); i++) if ((int) l.get(i)[0] <= at) start = i;
            if (l.size() > QUEUE_MAX) { from = start; start = 0; }
            for (int i = from; i < Math.min(l.size(), from + QUEUE_MAX); i++)
                out.add(track("tr:" + p[1] + ":" + l.get(i)[0], (Plex.Item) l.get(i)[1], plex, true));
        }
        return new MediaSession.MediaItemsWithStartPosition(out, start, positionMs);
    }

    private final class Callback implements MediaLibrarySession.Callback {
        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @Nullable LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.music_name), null, null), params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId, int page, int pageSize,
                @Nullable LibraryParams params) {
            return mIo.submit(() -> {
                int size0 = pageSize <= 0 || pageSize > PAGE_MAX ? PAGE_MAX : pageSize;
                /* eine abonnierte Liste immer vom Geraet: gleiche Reihenfolge wie beim Abspielen, auch ohne Netz */
                LibraryResult<ImmutableList<MediaItem>> off = offlineChildren(parentId, page, size0, params);
                if (off != null) return off;
                try {
                    Plex plex = plex();
                    ImmutableList.Builder<MediaItem> out = ImmutableList.builder();
                    int size = size0;
                    int from0 = (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, page) * size);
                    if (ROOT.equals(parentId)) {
                        /* Drei Wege in die Sammlung; die Oberflaeche zeigt sie als Reiter */
                        out.add(section(LISTS, getString(R.string.music_tab_playlists)));
                        out.add(section(ARTISTS, getString(R.string.music_tab_artists)));
                        out.add(section(ALBUMS, getString(R.string.music_tab_albums)));
                        out.add(section(GENRES, getString(R.string.music_tab_genres)));
                    } else if (GENRES.equals(parentId)) {
                        for (Plex.Item g : plex.genres()) out.add(section("ge:" + g.key, g.title));
                    } else if (parentId.startsWith("ge:")) {
                        /* Ein Genre: gemischt durch alle seine Titel, darunter die Alben */
                        String key = parentId.substring(3);
                        List<Plex.Item> albums = plex.genreAlbums(key, from0, size);
                        if (from0 == 0 && !albums.isEmpty()) out.add(shuffleOf("sg:" + key, R.string.music_shuffle_genre));
                        for (Plex.Item a : albums)
                            out.add(folder("al:" + a.key, a.title, a.album, a.thumb == null ? null : plex.art(a.thumb, ART_PX)));
                        return LibraryResult.ofItemList(out.build(), params);
                    } else if (parentId.startsWith("aa:")) {
                        /* Alle Titel eines Kuenstlers nach dem Alphabet */
                        String key = parentId.substring(3);
                        List<Plex.Item> tracks = plex.artistTracksByTitle(key, from0, size);
                        for (int i = 0; i < tracks.size(); i++) out.add(track("an:" + key + ":" + (from0 + i), tracks.get(i), plex, false));
                        return LibraryResult.ofItemList(out.build(), params);
                    } else if (ARTISTS.equals(parentId)) {
                        for (Plex.Item a : plex.artists(from0, size))
                            out.add(folder("ar:" + a.key, a.title, null, a.thumb == null ? null : plex.art(a.thumb, ART_PX)));
                        return LibraryResult.ofItemList(out.build(), params);
                    } else if (ALBUMS.equals(parentId)) {
                        for (Plex.Item a : plex.albums(from0, size))
                            out.add(folder("al:" + a.key, a.title, a.album, a.thumb == null ? null : plex.art(a.thumb, ART_PX)));
                        return LibraryResult.ofItemList(out.build(), params);
                    } else if (parentId.startsWith("ar:")) {
                        /* Ein Kuenstler: alles von ihm gemischt, darunter seine Alben */
                        String key = parentId.substring(3);
                        List<Plex.Item> albums = plex.albumsOf(key);
                        if (!albums.isEmpty()) {
                            out.add(shuffleOf("sa:" + key, R.string.music_shuffle_artist));
                            out.add(section("aa:" + key, getString(R.string.music_all_tracks)));
                        }
                        for (Plex.Item a : albums)
                            out.add(folder("al:" + a.key, a.title, a.year > 0 ? String.valueOf(a.year) : null, a.thumb == null ? null : plex.art(a.thumb, ART_PX)));
                    } else if (parentId.startsWith("al:")) {
                        String key = parentId.substring(3);
                        List<Plex.Item> tracks = plex.albumTracks(key);
                        for (int i = 0; i < tracks.size(); i++) out.add(track("at:" + key + ":" + i, tracks.get(i), plex, false));
                    } else if (LISTS.equals(parentId)) {
                        for (Plex.Item p : plex.playlists())
                            out.add(folder("pl:" + p.key, p.title, getResources().getQuantityString(R.plurals.music_tracks, p.count, p.count),
                                    p.thumb == null ? null : plex.art(p.thumb, ART_PX)));
                    } else if (parentId.startsWith("pl:")) {
                        /* Nur die verlangte Seite vom Server holen. Eintrag 0 ist die zufaellige Wiedergabe,
                           Eintrag g >= 1 der Titel g - 1 der Liste. */
                        String key = parentId.substring(3);
                        long from = (long) Math.max(0, page) * size;
                        int first = (int) Math.max(0, from - 1), want = from == 0 ? size - 1 : size;
                        List<Plex.Item> tracks = plex.tracks(key, first, want);
                        if (from == 0 && tracks.size() > 1) out.add(shuffle(key));
                        for (int i = 0; i < tracks.size(); i++) out.add(track("tr:" + key + ":" + (first + i), tracks.get(i), plex, false));
                        return LibraryResult.ofItemList(out.build(), params);
                    }
                    return LibraryResult.ofItemList(page(out.build(), page, pageSize), params);
                } catch (IOException e) {
                    /* ohne Server: wenigstens die abonnierten Listen */
                    if (LISTS.equals(parentId) && !Offline.lists(MusicService.this).isEmpty()) {
                        ImmutableList.Builder<MediaItem> out = ImmutableList.builder();
                        for (String key : Offline.lists(MusicService.this)) {
                            List<Plex.Item> l = Offline.list(MusicService.this, key);
                            if (l == null) continue;
                            out.add(folder("pl:" + key, Offline.title(MusicService.this, key),
                                    getString(R.string.music_offline_sub, l.size()), null));
                        }
                        return LibraryResult.ofItemList(page(out.build(), page, pageSize), params);
                    }
                    return failed("Liste", e);
                }
            });
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query, @Nullable LibraryParams params) {
            mIo.execute(() -> {
                try {
                    List<Plex.Item> found = plex().search(query);
                    mFound = found;
                    session.notifySearchResultChanged(browser, query, found.size(), params);
                } catch (IOException e) {
                    Log.w(TAG, "Suche: " + e.getClass().getSimpleName());
                    session.notifySearchResultChanged(browser, query, 0, params);
                }
            });
            return Futures.immediateFuture(LibraryResult.ofVoid(params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(@NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo browser, @NonNull String query, int page, int pageSize,
                @Nullable LibraryParams params) {
            Plex plex = Plex.load(MusicService.this);
            ImmutableList.Builder<MediaItem> out = ImmutableList.builder();
            List<Plex.Item> found = mFound;
            for (int i = 0; i < found.size(); i++) out.add(track("sr:" + i, found.get(i), plex, false));
            return Futures.immediateFuture(LibraryResult.ofItemList(page(out.build(), page, pageSize), params));
        }

        /** Wer sich verbindet, darf auch die eigenen Knoepfe benutzen. */
        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                            .add(SHUFFLE).add(REPEAT).build())
                    .setMediaButtonPreferences(buttons()).build();
        }

        @NonNull
        @Override
        public ListenableFuture<androidx.media3.session.SessionResult> onCustomCommand(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull androidx.media3.session.SessionCommand command, @NonNull Bundle args) {
            if (SHUFFLE.customAction.equals(command.customAction)) {
                mPlayer.setShuffleModeEnabled(!mPlayer.getShuffleModeEnabled());
                /* kept: after a restart the queue plays mixed again (18.11.2026, "gefuehlt die gleichen 10 Lieder") */
                Plex.prefs(MusicService.this).edit().putBoolean("shuffle_on", mPlayer.getShuffleModeEnabled()).apply();
            } else if (REPEAT.customAction.equals(command.customAction)) {
                /* aus -> alles (Album, Liste) -> dieser Titel -> aus */
                int m = mPlayer.getRepeatMode();
                mPlayer.setRepeatMode(m == androidx.media3.common.Player.REPEAT_MODE_OFF ? androidx.media3.common.Player.REPEAT_MODE_ALL
                        : m == androidx.media3.common.Player.REPEAT_MODE_ALL ? androidx.media3.common.Player.REPEAT_MODE_ONE
                        : androidx.media3.common.Player.REPEAT_MODE_OFF);
            } else {
                return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED));
            }
            return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS));
        }

        /** Sterne fuer den laufenden Titel: am Server gesetzt (wie in Plex/Plexamp), dann am Titel in der Warteschlange vermerkt. */
        @NonNull
        @Override
        public ListenableFuture<androidx.media3.session.SessionResult> onSetRating(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull androidx.media3.common.Rating rating) {
            MediaItem item = mPlayer.getCurrentMediaItem();
            int index = mPlayer.getCurrentMediaItemIndex();
            Bundle extras = item == null ? null : item.mediaMetadata.extras;
            String key = extras == null ? null : extras.getString(EXTRA_KEY);
            if (key == null || !(rating instanceof androidx.media3.common.StarRating))
                return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE));
            androidx.media3.common.StarRating stars = (androidx.media3.common.StarRating) rating;
            int half = stars.isRated() ? Math.round(stars.getStarRating() * 2) : 0;
            return mIo.submit(() -> {
                try {
                    plex().rate(key, half);
                } catch (IOException e) {
                    Log.w(TAG, "Bewertung: " + e.getClass().getSimpleName());
                    return new androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_IO);
                }
                mMain.post(() -> {
                    /* nur, wenn der Titel noch an seinem Platz steht */
                    if (index < mPlayer.getMediaItemCount() && mPlayer.getMediaItemAt(index).mediaId.equals(item.mediaId))
                        mPlayer.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                                .setUserRating(half > 0 ? new androidx.media3.common.StarRating(5, half / 2f)
                                        : new androidx.media3.common.StarRating(5)).build()).build());
                });
                return new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS);
            });
        }

        /** Einreihen: ein Titel aus einer Liste oder der Suche kommt hinter das, was laeuft. */
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> items) {
            return mIo.submit(() -> {
                Plex plex = plex();
                List<MediaItem> out = new ArrayList<>();
                for (MediaItem item : items) {
                    String id = item.mediaId;
                    String[] p = id.split(":");
                    Plex.Item t = null;
                    if (id.startsWith("sr:")) {
                        int i = Integer.parseInt(p[1]);
                        List<Plex.Item> found = mFound;
                        if (i >= 0 && i < found.size()) t = found.get(i);
                    } else if (id.startsWith("tr:")) {
                        List<Plex.Item> one = plex.tracks(p[1], Integer.parseInt(p[2]), 1);
                        if (!one.isEmpty()) t = one.get(0);
                    } else if (id.startsWith("at:")) {
                        List<Plex.Item> all = plex.albumTracks(p[1]);
                        int i = Integer.parseInt(p[2]);
                        if (i >= 0 && i < all.size()) t = all.get(i);
                    } else if (id.startsWith("ak:")) {
                        List<Plex.Item> one = plex.artistTracks(p[1], Integer.parseInt(p[2]), 1);
                        if (!one.isEmpty()) t = one.get(0);
                    } else if (id.startsWith("an:")) {
                        List<Plex.Item> one = plex.artistTracksByTitle(p[1], Integer.parseInt(p[2]), 1);
                        if (!one.isEmpty()) t = one.get(0);
                    }
                    if (t != null) out.add(track(id, t, plex, true));
                }
                return out;
            });
        }

        /** Ein angetippter Titel wird zur ganzen Liste ab dieser Stelle. */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
            String first = items.isEmpty() ? "" : items.get(0).mediaId;
            /* a mix ("Mischen" of a list, an artist, a genre) is remembered as such, so a restart mixes anew */
            boolean mix = first.startsWith("sh:") || first.startsWith("sa:") || first.startsWith("sg:");
            android.content.SharedPreferences.Editor e = Plex.prefs(MusicService.this).edit();
            if (mix) e.putString("last_mix", first); else e.remove("last_mix");
            e.apply();
            return mIo.submit(() -> resolve(first, C.TIME_UNSET));
        }

        /** "Weiter" ohne Warteschlange (nach einem Neustart): dort fortsetzen, wo zuletzt gespielt wurde. */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(@NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            android.content.SharedPreferences p = Plex.prefs(MusicService.this);
            String id = p.getString("last_id", null);
            long pos = p.getLong("last_pos", 0);
            String mix = p.getString("last_mix", null);
            if (id == null) return Futures.immediateFailedFuture(new UnsupportedOperationException("noch nichts gespielt"));
            /* 18.11.2026 ("Trotz Versuch, dass er einen richtigen Zufallsmodus verwendet, startet er gefuehlt mit den
               gleichen 10 Liedern"): a mix was rebuilt from its last title - the list from there in its own order, so
               always the same ones followed. Now the last title goes on where it stopped and a fresh mix follows it. */
            if (mix == null) return mIo.submit(() -> resolve(id, pos));
            return mIo.submit(() -> {
                MediaSession.MediaItemsWithStartPosition last = resolve(id, pos);
                MediaSession.MediaItemsWithStartPosition fresh = resolve(mix, C.TIME_UNSET);
                List<MediaItem> out = new ArrayList<>();
                if (!last.mediaItems.isEmpty()) out.add(last.mediaItems.get(Math.max(0, Math.min(last.mediaItems.size() - 1, last.startIndex))));
                String lastId = out.isEmpty() ? "" : out.get(0).mediaId;
                for (MediaItem m : fresh.mediaItems) if (!m.mediaId.equals(lastId)) out.add(m);
                return new MediaSession.MediaItemsWithStartPosition(out, 0, pos);
            });
        }

        /** Aus der Kennung eines Eintrags die Warteschlange bauen: die Liste, das Album, die Mischung dahinter. */
        private MediaSession.MediaItemsWithStartPosition resolve(String id, long positionMs) throws IOException {
            MediaSession.MediaItemsWithStartPosition off = offlineQueue(id, positionMs);
            if (off != null) return off;
            {
                Plex plex = plex();
                List<MediaItem> out = new ArrayList<>();
                int start = 0;
                if (id.startsWith("tr:")) {
                    /* Passt die Liste ganz in die Warteschlange, kommt sie ganz hinein und beginnt beim angetippten Titel -
                       so mischt und wiederholt "Mischen"/"Wiederholen" die ganze Liste. Sonst ab dem Titel, hoechstens QUEUE_MAX. */
                    String[] p = id.split(":");
                    int at = Math.max(0, Integer.parseInt(p[2]));
                    int from = plex.count(p[1]) <= QUEUE_MAX ? 0 : at;
                    List<Plex.Item> tracks = plex.tracks(p[1], from, QUEUE_MAX);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("tr:" + p[1] + ":" + (from + i), tracks.get(i), plex, true));
                    start = Math.max(0, Math.min(tracks.size() - 1, at - from));
                } else if (id.startsWith("at:")) {
                    /* ein Album ab dem angetippten Titel */
                    String[] p = id.split(":");
                    List<Plex.Item> tracks = plex.albumTracks(p[1]);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("at:" + p[1] + ":" + i, tracks.get(i), plex, true));
                    start = Math.max(0, Math.min(tracks.size() - 1, Integer.parseInt(p[2])));
                } else if (id.startsWith("sa:")) {
                    /* alles von einem Kuenstler, gemischt (bis SHUFFLE_ALL Titel geladen, QUEUE_MAX gespielt) */
                    String key = id.substring(3);
                    List<MediaItem> picked = new ArrayList<>();
                    for (int at = 0; at < SHUFFLE_ALL; at += CHUNK) {
                        List<Plex.Item> tracks = plex.artistTracks(key, at, CHUNK);
                        for (int i = 0; i < tracks.size(); i++) picked.add(track("ak:" + key + ":" + (at + i), tracks.get(i), plex, true));
                        if (tracks.size() < CHUNK) break;
                    }
                    java.util.Collections.shuffle(picked, new java.security.SecureRandom());
                    out.addAll(picked.subList(0, Math.min(picked.size(), QUEUE_MAX)));
                } else if (id.startsWith("an:")) {
                    /* aus "Alle Titel" eines Kuenstlers: ab dem angetippten weiter nach dem Alphabet */
                    String[] p = id.split(":");
                    int at = Math.max(0, Integer.parseInt(p[2]));
                    List<Plex.Item> tracks = plex.artistTracksByTitle(p[1], at, QUEUE_MAX);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("an:" + p[1] + ":" + (at + i), tracks.get(i), plex, true));
                } else if (id.startsWith("sg:")) {
                    /* ein Genre, vom Server gemischt; die Titel behalten die Kennung ihres Albums nicht - sie spielen als Mischung */
                    String key = id.substring(3);
                    List<Plex.Item> tracks = plex.genreShuffle(key, QUEUE_MAX);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("gk:" + tracks.get(i).key, tracks.get(i), plex, true));
                } else if (id.startsWith("ak:")) {
                    /* ein Titel aus der Mischung eines Kuenstlers (nach einem Neustart): ab dort durch seine Titel */
                    String[] p = id.split(":");
                    int at = Math.max(0, Integer.parseInt(p[2]));
                    List<Plex.Item> tracks = plex.artistTracks(p[1], at, QUEUE_MAX);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("ak:" + p[1] + ":" + (at + i), tracks.get(i), plex, true));
                } else if (id.startsWith("sh:")) {
                    /* Echter Zufall, jedes Mal neu (Zufallsgenerator des Systems), ueber die GANZE Liste:
                       - bis SHUFFLE_ALL Titel: alle laden und gleichverteilt mischen (Fisher-Yates);
                       - darueber: zufaellig gewaehlte Stuecke aus der ganzen Liste, zusammen QUEUE_MAX Titel, gemischt.
                         Die ganze Sammlung wird nie geladen. */
                    String key = id.substring(3);
                    java.security.SecureRandom rnd = new java.security.SecureRandom();
                    int n = plex.count(key);
                    List<MediaItem> picked = new ArrayList<>();
                    if (n <= SHUFFLE_ALL) {
                        for (int at = 0; at < n; at += CHUNK) {
                            List<Plex.Item> tracks = plex.tracks(key, at, CHUNK);
                            for (int i = 0; i < tracks.size(); i++) picked.add(track("tr:" + key + ":" + (at + i), tracks.get(i), plex, true));
                            if (tracks.isEmpty()) break;
                        }
                    } else {
                        List<Integer> pieces = new ArrayList<>();
                        for (int at = 0; at < n; at += PIECE) pieces.add(at);
                        java.util.Collections.shuffle(pieces, rnd);
                        for (int k = 0; k < pieces.size() && picked.size() < QUEUE_MAX; k++) {
                            int at = pieces.get(k);
                            List<Plex.Item> tracks = plex.tracks(key, at, PIECE);
                            for (int i = 0; i < tracks.size(); i++) picked.add(track("tr:" + key + ":" + (at + i), tracks.get(i), plex, true));
                        }
                    }
                    java.util.Collections.shuffle(picked, rnd);
                    out.addAll(picked);
                } else if (id.startsWith("pl:")) {
                    String key = id.substring(3);
                    List<Plex.Item> tracks = plex.tracks(key, 0, QUEUE_MAX);
                    for (int i = 0; i < tracks.size(); i++) out.add(track("tr:" + key + ":" + i, tracks.get(i), plex, true));
                } else if (id.startsWith("sr:")) {
                    List<Plex.Item> found = mFound;
                    for (int i = 0; i < found.size(); i++) out.add(track("sr:" + i, found.get(i), plex, true));
                    start = Math.max(0, Math.min(found.size() - 1, Integer.parseInt(id.substring(3))));
                }
                return new MediaSession.MediaItemsWithStartPosition(out, start, positionMs);
            }
        }
    }
}
