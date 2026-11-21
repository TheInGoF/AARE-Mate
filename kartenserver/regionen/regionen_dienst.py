#!/usr/bin/env python3
"""karten-regionen: Offline-Karten für Mate – Regionen wählen, je Region ein Paket bauen und in eigenem Rhythmus frisch halten.

Weboberfläche (Port 8095, nur LAN/Tailscale, Basic-Auth admin/ADMIN_PASS):
  oben die gewählten Regionen (Größe, Datenstand, Rhythmus 1–12 Monate oder nie, Zustand, „neu bauen“, „entfernen“),
  darunter alle Regionen von Geofabrik als Baum (Kontinent → Land → Bundesland …) mit Suche zum Hinzufügen. Eine gewählte
  Region enthält ihre Unterregionen: ist „Germany“ gewählt, sind die Bundesländer darunter nicht zusätzlich wählbar.
  Änderungen wirken sofort.

Jede gewählte Region wird ein eigenes Paket: /p/<region>/karte.pmtiles und grenze.geojson, dazu /p/katalog.json mit einem
Eintrag je fertiger Region (Format 1, gelesen von der App über das Tor /pakete/). Die App lädt und aktualisiert Regionen
einzeln (Wunsch 21.11.2026: „nicht immer einen 10gb brocken bewegen“). Kacheln an den Grenzen stehen ganz in beiden
Nachbarpaketen (pmtiles extract nimmt jede Kachel, die die Grenze berührt); die App zeichnet aus allen Paketen zugleich.

Datenquelle (QUELLE): „auto“ (Standard) schneidet aus dem neuesten Tagesbau von Protomaps (build.protomaps.com, nur die
nötigen Bereiche werden geladen), wenn der neuer ist als der lokale Planet /t/planet.pmtiles; sonst und bei „lokal“ aus dem
lokalen Planeten. So hält der Dienst eine Region monatlich frisch, auch wenn der lokale Planet nur alle 60 Tage kommt.

Eine Region wird gebaut, wenn ihr Paket fehlt, wenn sie zum Neubau vorgemerkt ist oder wenn ihr Rhythmus abgelaufen ist und
neuere Daten da sind. Gebaut wird eine Region nach der anderen; der Katalog folgt nach jeder. Abgewählte Regionen werden
gelöscht – eine, die in einer neu gewählten Oberregion aufgeht, erst wenn diese gebaut ist. Das frühere Gesamtpaket
/p/gesamt bleibt im Katalog, bis alle gewählten Regionen einzeln gebaut sind.

Für die gewählten Regionen lädt der Dienst die Höhenkacheln (1°×1°, Tilezen „skadi“) nach /hoehen, dem Höhenordner von
Valhalla (`additional_data.elevation`); Kacheln, die keine gewählte Region mehr braucht, gibt er wieder frei.

Live-Seite /live: liest das JSON-Zugriffsprotokoll von Caddy (Tor, ZUGRIFF_LOG) und zeigt die letzten Anfragen mit Dauer
sowie Anzahl, Fehler, Ø/p95/max je Dienst. Ohne LIVE_DETAILS=1 werden Query-Strings, Kachelkoordinaten und Client-IPs
nicht angezeigt.
"""
import base64
import datetime
import gzip
import hashlib
import hmac
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLANET = os.environ.get("PLANET", "/t/planet.pmtiles")
PAKETE = os.environ.get("PAKETE", "/p")
DATEN = os.environ.get("DATEN", "/daten")
HOEHEN = os.environ.get("HOEHEN", "/hoehen")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "")
INDEX_URL = os.environ.get("GEOFABRIK_INDEX", "https://download.geofabrik.de/index-v1-nogeom.json")
HOEHEN_URL = os.environ.get("HOEHEN_URL", "https://elevation-tiles-prod.s3.amazonaws.com/skadi")
QUELLE = os.environ.get("QUELLE", "auto")                      # auto | lokal
BUILDS_URL = os.environ.get("BUILDS_URL", "https://build-metadata.protomaps.dev/builds.json")
BUILD_BASIS = os.environ.get("BUILD_BASIS", "https://build.protomaps.com/")
PMTILES_VERSION = "1.30.1"
PMTILES = os.environ.get("PMTILES", os.path.join(DATEN, "bin", "pmtiles"))
PRUEF_ALLE_S = 6 * 3600
GRENZE_ALTER_S = 30 * 86400
MONATE = [1, 2, 3, 4, 5, 6, 12, 0]                             # 0 = nie (einmal bauen)
STANDARD_MONATE = 6
MONAT_S = 30 * 86400
GESAMT = "gesamt"
ZUGRIFF_LOG = os.environ.get("ZUGRIFF_LOG", "/logs/zugriff.log")
LIVE_DETAILS = os.environ.get("LIVE_DETAILS", "0") == "1"
LIVE_MAX = 20000                 # so viele Anfragen höchstens im Speicher
LIVE_START_BYTES = 5 << 20       # beim ersten Lesen nur das Ende der Datei

SPERRE = threading.Lock()
WECKER = threading.Event()
LAUFEND = {"aktiv": False, "zeile": "", "region": ""}


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


# ---------- Ablage ----------

def pfad(name):
    return os.path.join(DATEN, name)


def lies(name, standard):
    try:
        with open(pfad(name), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return standard


def schreib(ziel, daten):
    tmp = ziel + ".neu"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(daten, f, ensure_ascii=False, indent=1)
    os.replace(tmp, ziel)


def sauberer_name(rid):
    name = rid.replace("/", "_")
    if not re.fullmatch(r"[a-z0-9_-]+", name):
        raise ValueError(f"ungültige Region {rid!r}")
    return name


def sha256(datei):
    h = hashlib.sha256()
    with open(datei, "rb") as f:
        for block in iter(lambda: f.read(1 << 22), b""):
            h.update(block)
    return h.hexdigest()


# ---------- Geofabrik ----------

def geofabrik_index(neu=False):
    """Regionenliste von Geofabrik (ohne Geometrie), lokal 7 Tage zwischengespeichert."""
    datei = pfad("geofabrik.json")
    if not neu and os.path.exists(datei) and time.time() - os.path.getmtime(datei) < 7 * 86400:
        with open(datei, encoding="utf-8") as f:
            return json.load(f)
    if os.path.exists(INDEX_URL):
        with open(INDEX_URL, encoding="utf-8") as f:
            roh = json.load(f)
    else:
        with urllib.request.urlopen(INDEX_URL, timeout=60) as a:
            roh = json.load(a)
    regionen = {}
    for feat in roh["features"]:
        p = feat["properties"]
        pbf = p.get("urls", {}).get("pbf", "")
        if not pbf.endswith("-latest.osm.pbf"):
            continue
        regionen[p["id"]] = {"name": p["name"], "eltern": p.get("parent"),
                             "poly": pbf[: -len("-latest.osm.pbf")] + ".poly"}
    schreib(datei, regionen)
    log(f"Geofabrik-Index: {len(regionen)} Regionen")
    return regionen


def poly_zu_polygone(text):
    """Osmosis-.poly → Liste von Polygonen (je Polygon: Außenring, dann Löcher; Ringe als [[lon, lat], …])."""
    zeilen = [z.strip() for z in text.splitlines() if z.strip()]
    polygone, i = [], 1
    while i < len(zeilen) and zeilen[i] != "END":
        loch = zeilen[i].startswith("!")
        ring, i = [], i + 1
        while zeilen[i] != "END":
            lon, lat = (float(w) for w in zeilen[i].split()[:2])
            ring.append([lon, lat])
            i += 1
        i += 1
        if ring and ring[0] != ring[-1]:
            ring.append(ring[0])
        if loch and polygone:
            polygone[-1].append(ring)
        elif not loch:
            polygone.append([ring])
    if not polygone:
        raise ValueError("keine Ringe in der .poly-Datei")
    return polygone


def grenze(rid, idx):
    """Polygone der Region, aus dem Zwischenspeicher /daten/grenzen (höchstens 30 Tage alt) oder von Geofabrik."""
    os.makedirs(pfad("grenzen"), exist_ok=True)
    datei = pfad(f"grenzen/{sauberer_name(rid)}.json")
    if os.path.exists(datei) and time.time() - os.path.getmtime(datei) < GRENZE_ALTER_S:
        with open(datei, encoding="utf-8") as f:
            return json.load(f)
    with urllib.request.urlopen(idx[rid]["poly"], timeout=60) as a:
        polygone = poly_zu_polygone(a.read().decode("utf-8"))
    schreib(datei, polygone)
    return polygone


def umriss(polygone):
    pk = [p for poly in polygone for r in poly for p in r]
    xs, ys = [p[0] for p in pk], [p[1] for p in pk]
    return [round(min(xs), 4), round(min(ys), 4), round(max(xs), 4), round(max(ys), 4)]


def im_ring(lon, lat, ring):
    drin = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > lat) != (y2 > lat) and lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
            drin = not drin
    return drin


def im_polygon(lon, lat, poly):
    return im_ring(lon, lat, poly[0]) and not any(im_ring(lon, lat, loch) for loch in poly[1:])


# ---------- Höhen ----------

def kachel_name(lat, lon):
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    return f"{ns}{abs(lat):02d}", f"{ns}{abs(lat):02d}{ew}{abs(lon):03d}.hgt.gz"


def hoehen_kacheln(polygone):
    """1°×1°-Kacheln, die die Polygone berühren: ein Eckpunkt der Grenze liegt darin oder die Kachelmitte im Polygon."""
    kacheln = set()
    for poly in polygone:
        for lon, lat in poly[0]:
            kacheln.add((int(lat // 1), int(lon // 1)))
        w, s, o, n = umriss([poly])
        for la in range(int(s // 1), int(n // 1) + 1):
            for lo in range(int(w // 1), int(o // 1) + 1):
                if im_polygon(lo + 0.5, la + 0.5, poly):
                    kacheln.add((la, lo))
    return kacheln


def hoehen_abgleichen(polygone_alle):
    """Fehlende Höhenkacheln laden, nicht mehr gebrauchte selbst geladene löschen. Gibt (neu, gelöscht) zurück."""
    if not os.path.isdir(HOEHEN):
        log(f"Höhen: Ordner {HOEHEN} fehlt – übersprungen")
        return 0, 0
    with SPERRE:
        stand = lies("stand.json", {})
    h = stand.get("hoehen", {})
    eigene = set(h.get("eigene", []))
    leer = set(h.get("leer", []))
    gebraucht = set()
    for polygone in polygone_alle:
        for la, lo in hoehen_kacheln(polygone):
            gebraucht.add("/".join(kachel_name(la, lo)))
    neu = 0
    for k in sorted(gebraucht):
        ziel = os.path.join(HOEHEN, k)
        if os.path.exists(ziel) or k in leer:
            continue
        LAUFEND["zeile"] = f"Höhen: {k}"
        os.makedirs(os.path.dirname(ziel), exist_ok=True)
        try:
            with urllib.request.urlopen(f"{HOEHEN_URL}/{k}", timeout=120) as a, open(ziel + ".neu", "wb") as z:
                shutil.copyfileobj(a, z)
            with gzip.open(ziel + ".neu") as probe:   # nur vollständige, lesbare Kacheln übernehmen
                probe.read(16)
            os.replace(ziel + ".neu", ziel)
            eigene.add(k)
            neu += 1
        except urllib.error.HTTPError as fehler:
            if fehler.code in (403, 404):            # keine Kachel dort (Meer)
                leer.add(k)
            else:
                raise
        finally:
            if os.path.exists(ziel + ".neu"):
                os.remove(ziel + ".neu")
    weg = 0
    for k in sorted(eigene - gebraucht):
        try:
            os.remove(os.path.join(HOEHEN, k))
        except FileNotFoundError:
            pass
        eigene.discard(k)
        weg += 1
    vorhanden = [k for k in gebraucht if os.path.exists(os.path.join(HOEHEN, k))]
    groesse = sum(os.path.getsize(os.path.join(HOEHEN, k)) for k in vorhanden)
    with SPERRE:
        stand = lies("stand.json", {})
        alt = stand.get("hoehen", {})
        stand["hoehen"] = {"eigene": sorted(eigene), "leer": sorted(leer & gebraucht), "gebraucht": len(gebraucht - leer),
                           "vorhanden": len(vorhanden), "bytes": groesse, "geprueft": int(time.time()),
                           "neustart": bool(alt.get("neustart")) or neu > 0 or weg > 0}
        schreib(pfad("stand.json"), stand)
    if neu or weg:
        log(f"Höhen: {neu} neu, {weg} gelöscht – Valhalla neu starten")
    return neu, weg


# ---------- pmtiles ----------

def pmtiles_bereit():
    if os.path.exists(PMTILES):
        return
    arch = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "arm64", "arm64": "arm64"}[platform.machine().lower()]
    url = (f"https://github.com/protomaps/go-pmtiles/releases/download/v{PMTILES_VERSION}/"
           f"go-pmtiles_{PMTILES_VERSION}_Linux_{arch}.tar.gz")
    os.makedirs(os.path.dirname(PMTILES), exist_ok=True)
    tgz = PMTILES + ".tar.gz"
    log("lade", url)
    urllib.request.urlretrieve(url, tgz)
    with tarfile.open(tgz) as t:
        m = next(m for m in t.getmembers() if os.path.basename(m.name) == "pmtiles")
        with t.extractfile(m) as q, open(PMTILES + ".neu", "wb") as z:
            shutil.copyfileobj(q, z)
    os.chmod(PMTILES + ".neu", 0o755)
    os.replace(PMTILES + ".neu", PMTILES)
    os.remove(tgz)


# ---------- Datenquelle ----------

def planet_zeit():
    """Zeit des lokalen Planeten (mtime), None wenn er fehlt."""
    try:
        return int(os.path.getmtime(PLANET))
    except OSError:
        return None


_BUILDS = {"zeit": 0, "neu": None}


def neuester_bau():
    """Neuester Tagesbau von Protomaps als (url, zeit, name), höchstens alle 6 h nachgefragt; None ohne Verbindung."""
    if time.time() - _BUILDS["zeit"] < 6 * 3600:
        return _BUILDS["neu"]
    neu = None
    try:
        with urllib.request.urlopen(BUILDS_URL, timeout=30) as a:
            liste = json.load(a)
        namen = sorted(b["key"] for b in liste if re.fullmatch(r"\d{8}\.pmtiles", b.get("key", "")))
        if namen:
            n = namen[-1]
            t = int(datetime.datetime.strptime(n[:8], "%Y%m%d").replace(tzinfo=datetime.timezone.utc).timestamp())
            neu = (BUILD_BASIS + n, t, n)
    except Exception as fehler:  # noqa: BLE001 – ohne Netz bleibt der lokale Planet
        log("Protomaps-Bauliste nicht lesbar:", fehler)
    _BUILDS.update(zeit=time.time(), neu=neu)
    return neu


def quelle():
    """Woraus geschnitten wird: (Pfad oder URL, Datenzeit, Anzeige). Der neueste Tagesbau, wenn er neuer ist als der lokale Planet."""
    lokal = planet_zeit()
    if QUELLE == "auto":
        bau = neuester_bau()
        if bau and (lokal is None or bau[1] > lokal + 86400):
            return bau[0], bau[1], f"Protomaps {bau[2][:8]}"
    if lokal is None:
        raise RuntimeError(f"kein Planet unter {PLANET} und kein Tagesbau erreichbar")
    return PLANET, lokal, "lokaler Planet"


def datum(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).date().isoformat()


# ---------- Auswahl ----------

def auswahl_lesen():
    """{region: {"monate": n}}; liest auch die früheren Formen ({"regionen": [...], "intervall": tage} und {id: {...}})."""
    a = lies("auswahl.json", {})
    tage_zu_monaten = {0: 1, 180: 6, 365: 12, -1: 0}
    if isinstance(a.get("regionen"), dict):
        return {r: {"monate": int(e.get("monate", STANDARD_MONATE))} for r, e in a["regionen"].items()}
    if isinstance(a.get("regionen"), list):
        m = tage_zu_monaten.get(int(a.get("intervall", 180)), STANDARD_MONATE)
        return {r: {"monate": m} for r in a["regionen"]}
    return {r: {"monate": tage_zu_monaten.get(int(e.get("intervall", 180)), STANDARD_MONATE)}
            for r, e in a.items() if isinstance(e, dict)}


def vorfahren(rid, idx):
    aus, e = [], idx.get(rid, {}).get("eltern")
    while e in idx:
        aus.append(e)
        e = idx[e].get("eltern")
    return aus


def bereinigt(auswahl, idx):
    """Ohne Regionen, die nicht (mehr) bei Geofabrik stehen, und ohne solche, deren Oberregion gewählt ist."""
    da = {r: e for r, e in auswahl.items() if r in idx}
    return {r: e for r, e in da.items() if not any(v in da for v in vorfahren(r, idx))}


def auswahl_schreiben(auswahl):
    idx = geofabrik_index()
    sauber = bereinigt(auswahl, idx)
    with SPERRE:
        schreib(pfad("auswahl.json"), {"regionen": {r: sauber[r] for r in sorted(sauber)}})
    return sauber


def region_setzen(rid, an=True, monate=None):
    """Region wählen (mit Rhythmus) oder abwählen; wirkt sofort."""
    idx = geofabrik_index()
    if rid not in idx:
        raise ValueError("unbekannte Region")
    sauberer_name(rid)
    a = auswahl_lesen()
    if an:
        m = STANDARD_MONATE if monate is None else int(monate)
        if m not in MONATE:
            raise ValueError("unbekannter Rhythmus")
        if any(v in a for v in vorfahren(rid, idx)):
            raise ValueError("in einer gewählten Oberregion enthalten")
        a[rid] = {"monate": m}
    else:
        a.pop(rid, None)
    auswahl_schreiben(a)
    with SPERRE:
        stand = lies("stand.json", {})
        stand.get("regionen", {}).get(rid, {}).pop("fehler", None)
        schreib(pfad("stand.json"), stand)
    WECKER.set()


def vormerken(rid):
    """Eine Region beim nächsten Durchlauf neu bauen (Knopf „neu bauen“)."""
    if rid not in auswahl_lesen():
        raise ValueError("Region ist nicht gewählt")
    with SPERRE:
        stand = lies("stand.json", {})
        stand["vorgemerkt"] = sorted(set(stand.get("vorgemerkt", [])) | {rid})
        stand.get("regionen", {}).get(rid, {}).pop("fehler", None)
        schreib(pfad("stand.json"), stand)
    WECKER.set()


def neustart_erledigt():
    with SPERRE:
        stand = lies("stand.json", {})
        stand.setdefault("hoehen", {})["neustart"] = False
        schreib(pfad("stand.json"), stand)


# ---------- Regionspakete ----------

def faellig_am(r, monate):
    """Ab wann der Rhythmus einer gebauten Region abgelaufen ist; None bei „nie“ oder ungebaut."""
    if not r.get("gebaut") or monate == 0:
        return None
    return r["gebaut"] + monate * MONAT_S


def faellige(daten_zeit):
    """Die gewählten Regionen, die (neu) gebaut werden müssen: fehlend und vorgemerkt zuerst, dann abgelaufene."""
    a = auswahl_lesen()
    with SPERRE:
        stand = lies("stand.json", {})
    gebaut = stand.get("regionen", {})
    vorgemerkt = set(stand.get("vorgemerkt", []))
    erst, dann = [], []
    for rid in sorted(a):
        r = gebaut.get(rid, {})
        da = r.get("dateien") and os.path.exists(os.path.join(PAKETE, sauberer_name(rid), "karte.pmtiles"))
        am = faellig_am(r, a[rid]["monate"])
        if not da or rid in vorgemerkt:
            erst.append(rid)
        elif am is not None and time.time() >= am and daten_zeit > r.get("planet", 0):
            dann.append(rid)
    return erst + dann


def katalog_schreiben():
    """Ein Eintrag je fertiger Region; das alte Gesamtpaket nur, solange es noch auf dem Server liegt."""
    with SPERRE:
        stand = lies("stand.json", {})
    idx = geofabrik_index()
    regionen = []
    for rid, r in sorted(stand.get("regionen", {}).items(), key=lambda e: e[1].get("name", e[0])):
        if not r.get("dateien"):
            continue
        name = sauberer_name(rid)
        eltern = idx.get(rid, {}).get("eltern")
        regionen.append({"id": name, "name": r.get("name", rid), "eltern": sauberer_name(eltern) if eltern else None,
                         "pfad": [idx[v]["name"] for v in reversed(vorfahren(rid, idx))], "bbox": r["bbox"],
                         "stand": datum(r["planet"]),
                         "dateien": [{"art": d["art"], "pfad": f"{name}/{d['datei']}", "bytes": d["bytes"],
                                      "sha256": d["sha256"]} for d in r["dateien"]]})
    g = stand.get("gesamt", {})
    if g.get("dateien") and os.path.exists(os.path.join(PAKETE, GESAMT, "karte.pmtiles")):
        regionen.append({"id": GESAMT, "name": f"Gesamtpaket ({len(g['regionen'])} Regionen)", "eltern": None, "pfad": [],
                         "bbox": g["bbox"], "enthaelt": g.get("namen", []),
                         "dateien": [{"art": d["art"], "pfad": f"{GESAMT}/{d['datei']}", "bytes": d["bytes"],
                                      "sha256": d["sha256"]} for d in g["dateien"]]})
    stand_karte = max([r["planet"] for r in stand.get("regionen", {}).values() if r.get("planet")] or [planet_zeit() or 0])
    os.makedirs(PAKETE, exist_ok=True)
    schreib(os.path.join(PAKETE, "katalog.json"), {"format": 1, "stand_karte": datum(stand_karte), "regionen": regionen})


def baue_region(rid, idx, src, daten_zeit, src_name):
    """Schneidet eine Region aus der Quelle: /p/<region>/karte.pmtiles und grenze.geojson."""
    name = sauberer_name(rid)
    LAUFEND.update(region=rid, zeile="Grenze laden")
    polygone = grenze(rid, idx)
    ordner = os.path.join(PAKETE, name)
    os.makedirs(ordner, exist_ok=True)
    grenze_datei = os.path.join(ordner, "grenze.geojson")
    schreib(grenze_datei, {"type": "Feature", "properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": polygone}})
    pmtiles_bereit()
    tmp = os.path.join(ordner, "karte.pmtiles.neu")
    if os.path.exists(tmp):
        os.remove(tmp)
    LAUFEND["zeile"] = f"Karte schneiden aus {src_name} …"
    p = subprocess.Popen([PMTILES, "extract", src, tmp, f"--region={grenze_datei}"],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    for zeile in p.stdout:
        LAUFEND["zeile"] = zeile.strip()[-110:]
    if p.wait() != 0:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise RuntimeError("pmtiles extract fehlgeschlagen: " + LAUFEND["zeile"])
    karte = os.path.join(ordner, "karte.pmtiles")
    os.replace(tmp, karte)
    return {"name": idx[rid]["name"], "planet": daten_zeit, "quelle": src_name, "gebaut": int(time.time()),
            "bbox": umriss(polygone),
            "dateien": [{"art": "karte", "datei": "karte.pmtiles", "bytes": os.path.getsize(karte), "sha256": sha256(karte)},
                        {"art": "grenze", "datei": "grenze.geojson", "bytes": os.path.getsize(grenze_datei),
                         "sha256": sha256(grenze_datei)}]}


def aufraeumen():
    """Abgewählte Regionen löschen (in einer neuen Oberregion aufgehende erst, wenn diese gebaut ist);
    das Gesamtpaket, sobald alle gewählten Regionen einzeln da sind."""
    idx = geofabrik_index()
    gewaehlt = auswahl_lesen()
    with SPERRE:
        stand = lies("stand.json", {})
        gebaut = stand.setdefault("regionen", {})
        for rid in [r for r in gebaut if r not in gewaehlt]:
            oben = [v for v in vorfahren(rid, idx) if v in gewaehlt]
            if oben and not gebaut.get(oben[0], {}).get("dateien"):
                continue                                   # bleibt, bis die Oberregion fertig ist
            gebaut.pop(rid)
            shutil.rmtree(os.path.join(PAKETE, sauberer_name(rid)), ignore_errors=True)
            log("Region nicht mehr gewählt, Paket gelöscht:", rid)
        stand["vorgemerkt"] = [r for r in stand.get("vorgemerkt", []) if r in gewaehlt]
        alle_da = all(gebaut.get(r, {}).get("dateien") for r in gewaehlt)
        if alle_da and stand.get("gesamt"):
            stand.pop("gesamt")
        schreib(pfad("stand.json"), stand)
    if alle_da and os.path.isdir(os.path.join(PAKETE, GESAMT)):
        shutil.rmtree(os.path.join(PAKETE, GESAMT), ignore_errors=True)
        log("Gesamtpaket gelöscht: alle Regionen liegen einzeln vor")


def arbeiter():
    while True:
        WECKER.wait(PRUEF_ALLE_S)
        WECKER.clear()
        try:
            idx = geofabrik_index()
            auswahl_schreiben(auswahl_lesen())             # frühere Formen umschreiben, Enthaltenes entfernen
            aufraeumen()
            katalog_schreiben()
            hoehen_abgleichen([grenze(r, idx) for r in auswahl_lesen()])
            src, daten_zeit, src_name = quelle()
            liste = faellige(daten_zeit)
        except Exception as fehler:  # noqa: BLE001 – Fehler in der Oberfläche zeigen
            log("Prüfung fehlgeschlagen:", fehler)
            with SPERRE:
                stand = lies("stand.json", {})
                stand["pruefung_fehler"] = str(fehler)[:300]
                schreib(pfad("stand.json"), stand)
            continue
        with SPERRE:
            stand = lies("stand.json", {})
            stand.pop("pruefung_fehler", None)
            stand["geprueft"] = int(time.time())
            schreib(pfad("stand.json"), stand)
        for rid in liste:
            if rid not in auswahl_lesen():                 # inzwischen abgewählt
                continue
            LAUFEND.update(aktiv=True, region=rid, zeile="startet")
            log("baue Region", rid, "aus", src_name)
            try:
                ergebnis = baue_region(rid, idx, src, daten_zeit, src_name)
                with SPERRE:
                    stand = lies("stand.json", {})
                    stand.setdefault("regionen", {})[rid] = ergebnis
                    stand["vorgemerkt"] = [r for r in stand.get("vorgemerkt", []) if r != rid]
                    schreib(pfad("stand.json"), stand)
                log(f"fertig: {rid}, {ergebnis['dateien'][0]['bytes'] / 1e9:.2f} GB")
            except Exception as fehler:  # noqa: BLE001
                log("Fehler bei", rid, fehler)
                with SPERRE:
                    stand = lies("stand.json", {})
                    stand.setdefault("regionen", {}).setdefault(rid, {})["fehler"] = str(fehler)[:300]
                    stand["vorgemerkt"] = [r for r in stand.get("vorgemerkt", []) if r != rid]
                    schreib(pfad("stand.json"), stand)
            finally:
                LAUFEND.update(aktiv=False, region="", zeile="")
            aufraeumen()
            katalog_schreiben()
            if WECKER.is_set():                            # Auswahl geändert: neu prüfen
                break


# ---------- Live: Zugriffsprotokoll von Caddy ----------

LIVE = {"sperre": threading.Lock(), "inode": None, "pos": 0, "rest": b"", "eintraege": deque(maxlen=LIVE_MAX)}


def dienst_von(pfad_teile):
    """/route/height → "route/height", /suche/api → "suche/api", /kacheln/… → "kacheln"."""
    if not pfad_teile or not pfad_teile[0]:
        return "/"
    erster = pfad_teile[0]
    if erster in ("route", "suche") and len(pfad_teile) > 1 and pfad_teile[1]:
        return f"{erster}/{pfad_teile[1]}"
    return erster


def anzeige_uri(uri):
    teile = urllib.parse.urlsplit(uri)
    if LIVE_DETAILS:
        return uri
    segmente = teile.path.split("/")
    if len(segmente) > 2 and segmente[1] == "kacheln":     # /kacheln/z/x/y → /kacheln/z/…
        return "/".join(segmente[:3]) + ("/…" if len(segmente) > 3 else "")
    return teile.path + ("?…" if teile.query else "")


def zugriff_umwandeln(roh):
    req = roh.get("request")
    if not isinstance(req, dict) or "duration" not in roh:
        return None
    uri = req.get("uri", "")
    pfad_teile = urllib.parse.urlsplit(uri).path.strip("/").split("/")
    e = {"ts": roh.get("ts", 0), "ms": round(float(roh["duration"]) * 1000, 1), "status": roh.get("status", 0),
         "methode": req.get("method", ""), "dienst": dienst_von(pfad_teile), "uri": anzeige_uri(uri),
         "bytes": roh.get("size", 0)}
    if LIVE_DETAILS:
        e["client"] = req.get("client_ip") or req.get("remote_ip", "")
    return e


def zugriffe_nachlesen():
    """Neue Zeilen seit dem letzten Aufruf lesen; erkennt Rotation (neue Datei) und Kürzung."""
    with LIVE["sperre"]:
        try:
            st = os.stat(ZUGRIFF_LOG)
        except FileNotFoundError:
            return False
        if st.st_ino != LIVE["inode"] or st.st_size < LIVE["pos"]:
            erstes_mal = LIVE["inode"] is None
            LIVE.update(inode=st.st_ino, rest=b"",
                        pos=max(0, st.st_size - LIVE_START_BYTES) if erstes_mal else 0)
            if erstes_mal and LIVE["pos"]:
                LIVE["rest"] = None          # erste, angeschnittene Zeile verwerfen
        if st.st_size == LIVE["pos"]:
            return True
        with open(ZUGRIFF_LOG, "rb") as f:
            f.seek(LIVE["pos"])
            neu = f.read(st.st_size - LIVE["pos"])
            LIVE["pos"] = f.tell()
        verwerfen = LIVE["rest"] is None
        zeilen = ((LIVE["rest"] or b"") + neu).split(b"\n")
        LIVE["rest"] = zeilen.pop()          # letzte Zeile ist evtl. noch unvollständig
        if verwerfen:
            if not zeilen:                   # noch kein Zeilenende gesehen: weiter verwerfen
                LIVE["rest"] = None
            zeilen = zeilen[1:]
        for z in zeilen:
            try:
                e = zugriff_umwandeln(json.loads(z))
            except (ValueError, TypeError):
                continue
            if e:
                LIVE["eintraege"].append(e)
        return True


def p95(werte):
    s = sorted(werte)
    return s[min(len(s) - 1, int(len(s) * 0.95))] if s else 0


def live_daten(fenster_s, mit_gesund):
    vorhanden = zugriffe_nachlesen()
    grenze_ts = time.time() - fenster_s
    with LIVE["sperre"]:
        alle = [e for e in LIVE["eintraege"] if e["ts"] >= grenze_ts and (mit_gesund or e["dienst"] != "gesund")]
    gruppen = {}
    for e in alle:
        gruppen.setdefault(e["dienst"], []).append(e)
    dienste = []
    for name, es in gruppen.items():
        ms = [e["ms"] for e in es]
        dienste.append({"dienst": name, "anzahl": len(es), "fehler": sum(1 for e in es if e["status"] >= 400),
                        "mittel": round(sum(ms) / len(ms), 1), "p95": p95(ms), "max": max(ms)})
    dienste.sort(key=lambda d: -d["anzahl"])
    return {"vorhanden": vorhanden, "datei": ZUGRIFF_LOG, "details": LIVE_DETAILS, "fenster": fenster_s,
            "dienste": dienste, "eintraege": alle[-200:][::-1], "langsamste": sorted(alle, key=lambda e: -e["ms"])[:10]}


LIVE_SEITE = r"""<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Live – Anfragen</title>
<style>
body{font:14px/1.4 system-ui,sans-serif;margin:0;color:#1d2125;background:#f6f7f8}
header{position:sticky;top:0;background:#fff;border-bottom:1px solid #dde1e5;padding:10px 16px;z-index:1}
.reihe{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
h1{font-size:17px;margin:0 8px 0 0} h2{font-size:15px;margin:18px 0 6px}
a{color:#1f6feb;text-decoration:none}
button{padding:5px 12px;border:0;border-radius:6px;background:#eef0f2;color:#1d2125;cursor:pointer}
button.an{background:#1f6feb;color:#fff}
select{padding:4px 6px;font-size:14px}
.info{color:#5b636b;font-size:13px}
main{padding:8px 16px 40px;max-width:1100px}
table{border-collapse:collapse;width:100%;background:#fff;border:1px solid #dde1e5;border-radius:6px}
th,td{text-align:left;padding:4px 8px;border-bottom:1px solid #eef0f2;white-space:nowrap}
th{font-weight:600;font-size:13px;color:#4a5157;background:#fafbfc}
td.zahl,th.zahl{text-align:right;font-variant-numeric:tabular-nums}
td.uri{white-space:normal;word-break:break-all;font-family:ui-monospace,monospace;font-size:12px}
tr.klick{cursor:pointer} tr.klick:hover td{background:#f3f6fa}
.st{font-size:12px;padding:1px 7px;border-radius:9px;background:#eef0f2;color:#4a5157}
.st.ok{background:#e3f4e8;color:#1a7f37}.st.warn{background:#fff4d6;color:#8a5a00}.st.err{background:#fde7e9;color:#b42318}
.langsam{color:#8a5a00;font-weight:600}.sehrlangsam{color:#b42318;font-weight:700}
.hinweis{margin-top:8px;padding:6px 10px;border-radius:6px;background:#fff4d6;color:#6b4800}
</style>
<header><div class="reihe"><h1>Live – Anfragen über das Tor</h1>
<a href="./">← Kartenpakete</a>
<label class="info">Zeitraum <select id="fenster">
<option value="300">5 Minuten</option><option value="900">15 Minuten</option><option value="3600" selected>1 Stunde</option>
<option value="21600">6 Stunden</option><option value="86400">24 Stunden</option></select></label>
<label class="info">Dienst <select id="filter"><option value="">alle</option></select></label>
<label class="info"><input type="checkbox" id="gesund"> /gesund zeigen</label>
<button id="pause" class="an">live</button>
<span class="info" id="stand"></span></div><div id="hinweis"></div></header>
<main>
<h2>Je Dienst</h2><table id="dienste"></table>
<h2>Langsamste Anfragen im Zeitraum</h2><table id="langsamste"></table>
<h2>Letzte Anfragen</h2><table id="letzte"></table>
</main>
<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const zeit=t=>new Date(t*1000).toLocaleTimeString('de-DE');
const ms=v=>{const c=v>=5000?'sehrlangsam':v>=1000?'langsam':'';const t=v>=1000?(v/1000).toLocaleString('de-DE',{maximumFractionDigits:2})+' s':Math.round(v)+' ms';return `<span class="${c}">${t}</span>`;};
const st=s=>`<span class="st ${s>=500?'err':s>=400?'warn':'ok'}">${s}</span>`;
const kb=b=>b>=1e6?(b/1e6).toFixed(1)+' MB':b>=1e3?(b/1e3).toFixed(0)+' kB':b+' B';
let live=true, D=null;
function tabelleAnfragen(liste,details){
  const kopf=`<tr><th>Zeit</th><th>Dienst</th><th>Status</th><th class="zahl">Dauer</th><th class="zahl">Größe</th>${details?'<th>Client</th>':''}<th>Anfrage</th></tr>`;
  if(!liste.length) return kopf+'<tr><td colspan="7" class="info">keine Anfragen</td></tr>';
  return kopf+liste.map(e=>`<tr><td>${zeit(e.ts)}</td><td>${esc(e.dienst)}</td><td>${st(e.status)}</td><td class="zahl">${ms(e.ms)}</td><td class="zahl">${kb(e.bytes)}</td>${details?`<td>${esc(e.client||'')}</td>`:''}<td class="uri">${esc(e.methode)} ${esc(e.uri)}</td></tr>`).join('');
}
function zeichne(){
  if(!D) return;
  $('#hinweis').innerHTML=D.vorhanden?'':`<div class="hinweis">Protokoll ${esc(D.datei)} nicht gefunden – ist das Log in Caddy eingeschaltet und der Ordner im Stack eingebunden?</div>`;
  const f=$('#filter'), wahl=f.value, namen=D.dienste.map(d=>d.dienst).sort();
  f.innerHTML='<option value="">alle</option>'+namen.map(n=>`<option${n===wahl?' selected':''}>${esc(n)}</option>`).join('');
  if(wahl&&!namen.includes(wahl)) f.innerHTML+=`<option selected>${esc(wahl)}</option>`;
  const passt=e=>!wahl||e.dienst===wahl;
  $('#dienste').innerHTML='<tr><th>Dienst</th><th class="zahl">Anfragen</th><th class="zahl">Fehler</th><th class="zahl">Ø</th><th class="zahl">p95</th><th class="zahl">max</th></tr>'+
    (D.dienste.length?D.dienste.map(d=>`<tr class="klick" data-d="${esc(d.dienst)}"><td>${esc(d.dienst)}</td><td class="zahl">${d.anzahl}</td><td class="zahl">${d.fehler?`<span class="st err">${d.fehler}</span>`:'0'}</td><td class="zahl">${ms(d.mittel)}</td><td class="zahl">${ms(d.p95)}</td><td class="zahl">${ms(d.max)}</td></tr>`).join(''):'<tr><td colspan="6" class="info">keine Anfragen im Zeitraum</td></tr>');
  $('#langsamste').innerHTML=tabelleAnfragen(D.langsamste.filter(passt),D.details);
  $('#letzte').innerHTML=tabelleAnfragen(D.eintraege.filter(passt).slice(0,100),D.details);
  $('#stand').textContent='Stand '+new Date().toLocaleTimeString('de-DE');
}
async function laden(){
  const r=await fetch(`api/live?fenster=${$('#fenster').value}&gesund=${$('#gesund').checked?1:0}`);
  if(r.ok){D=await r.json(); zeichne();}
}
$('#dienste').addEventListener('click',e=>{const tr=e.target.closest('tr[data-d]'); if(!tr) return; const f=$('#filter'); f.value=f.value===tr.dataset.d?'':tr.dataset.d; zeichne();});
$('#filter').onchange=zeichne; $('#fenster').onchange=laden; $('#gesund').onchange=laden;
$('#pause').onclick=()=>{live=!live; $('#pause').classList.toggle('an',live); $('#pause').textContent=live?'live':'pausiert'; if(live) laden();};
laden(); setInterval(()=>{ if(live) laden(); },3000);
</script></html>"""


# ---------- Weboberfläche ----------

SEITE = r"""<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Regionspakete für Mate</title>
<style>
:root{--rand:#dde1e5;--leise:#5b636b;--blau:#1f6feb;--grund:#f4f6f8}
*{box-sizing:border-box}
body{font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;color:#1d2125;background:var(--grund)}
header{background:#fff;border-bottom:1px solid var(--rand);padding:14px 20px;display:flex;align-items:baseline;gap:16px;flex-wrap:wrap}
header h1{font-size:19px;margin:0}
header .info{flex:1}
a{color:var(--blau);text-decoration:none}
main{max-width:1100px;margin:0 auto;padding:16px 20px 48px}
.kacheln{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin-bottom:14px}
.kachel{background:#fff;border:1px solid var(--rand);border-radius:10px;padding:10px 14px}
.kachel .t{font-size:12px;color:var(--leise);text-transform:uppercase;letter-spacing:.04em}
.kachel .w{font-size:20px;font-weight:600;margin-top:2px}
.kachel .u{font-size:13px;color:var(--leise)}
.karte{background:#fff;border:1px solid var(--rand);border-radius:10px;margin-bottom:16px;overflow:hidden}
.karte h2{font-size:16px;margin:0;padding:12px 16px;border-bottom:1px solid var(--rand);display:flex;align-items:center;gap:10px}
.karte h2 .rechts{margin-left:auto;font-weight:400;font-size:13px;color:var(--leise);display:flex;gap:8px;align-items:center}
.info{color:var(--leise);font-size:13px}
.hinweis{margin:0 0 12px;padding:8px 12px;border-radius:8px;background:#fff4d6;color:#6b4800;display:flex;gap:10px;align-items:center}
.hinweis.err{background:#fde7e9;color:#8a1c12}
table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f2;vertical-align:middle}
th{font-weight:600;font-size:12px;color:var(--leise);background:#fafbfc;text-transform:uppercase;letter-spacing:.03em}
td.zahl,th.zahl{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
tr:last-child td{border-bottom:0}
.name{font-weight:600}.pfad{font-size:12px;color:var(--leise)}
.st{display:inline-block;font-size:12px;padding:2px 9px;border-radius:10px;background:#eef0f2;color:#4a5157;white-space:nowrap}
.st.ok{background:#e3f4e8;color:#1a7f37}.st.lauf{background:#fff4d6;color:#8a5a00}.st.err{background:#fde7e9;color:#b42318}.st.blau{background:#e5efff;color:#1f4fa8}
.unter{font-size:12px;color:var(--leise);margin-top:2px;max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
button{font:inherit;font-size:13px;padding:5px 12px;border:1px solid var(--rand);border-radius:7px;background:#fff;color:#1d2125;cursor:pointer}
button:hover{background:#f3f5f7}button:disabled{opacity:.5;cursor:default}
button.gefahr{color:#b42318}
select{font:inherit;font-size:13px;padding:4px 6px;border:1px solid var(--rand);border-radius:7px;background:#fff}
input[type=search]{font:inherit;padding:7px 10px;border:1px solid #c5cbd1;border-radius:8px;width:100%;max-width:340px}
.leer{padding:18px 16px;color:var(--leise)}
#baum{padding:6px 8px 12px;max-height:60vh;overflow:auto}
.knoten{display:flex;align-items:center;gap:8px;padding:3px 6px;border-radius:6px}
.knoten:hover{background:#f6f8fa}
.knoten .auf{width:20px;text-align:center;cursor:pointer;color:var(--leise);user-select:none}
.knoten label{display:flex;align-items:center;gap:8px;cursor:pointer;flex:1}
.knoten.aus label{cursor:default;color:var(--leise)}
.kinder{margin-left:22px}
mark{background:#fff1a8;padding:0}
</style>
<header><h1>Regionspakete für Mate</h1><span class="info" id="kopfinfo"></span><a href="live">Live-Anfragen →</a></header>
<main>
<div class="kacheln" id="kacheln"></div>
<div id="hinweise"></div>
<section class="karte"><h2>Ausgewählte Regionen<span class="rechts" id="summe"></span></h2><div id="liste"></div></section>
<section class="karte"><h2>Region hinzufügen<span class="rechts">Rhythmus für neue Regionen <select id="neuMonate"></select></span></h2>
<div style="padding:12px 16px 4px"><input type="search" id="suche" placeholder="Region suchen, z. B. Bayern, Sweden, Europe …"></div>
<div id="baum">lädt …</div></section>
</main>
<script>
const $=s=>document.querySelector(s);
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const gb=b=>b>=1e9?(b/1e9).toLocaleString('de-DE',{maximumFractionDigits:2})+' GB':(b/1e6).toLocaleString('de-DE',{maximumFractionDigits:0})+' MB';
const tag=t=>t?new Date(t*1000).toLocaleDateString('de-DE'):'–';
const uhr=t=>new Date(t*1000).toLocaleTimeString('de-DE',{hour:'2-digit',minute:'2-digit'});
const MONATE={1:'monatlich',2:'alle 2 Monate',3:'alle 3 Monate',4:'alle 4 Monate',5:'alle 5 Monate',6:'alle 6 Monate',12:'jährlich',0:'nie (einmal bauen)'};
const monatsAuswahl=(wert,attr)=>`<select ${attr}>`+[1,2,3,4,5,6,12,0].map(m=>`<option value="${m}"${m==wert?' selected':''}>${MONATE[m]}</option>`).join('')+'</select>';
$('#neuMonate').outerHTML=monatsAuswahl(6,'id="neuMonate"');
let B=null, Z=null, offen=new Set();
async function post(url,daten){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(daten)});if(!r.ok){alert((await r.json().catch(()=>({}))).fehler||'Fehlgeschlagen');} await laden();}
function vorfahren(id){const a=[];let e=B.regionen[id]&&B.regionen[id].eltern;while(e&&B.regionen[e]){a.push(e);e=B.regionen[e].eltern;}return a;}
function pfadText(id){return vorfahren(id).reverse().map(v=>B.regionen[v].name).join(' › ');}
function gewaehlterVorfahr(id){return vorfahren(id).find(v=>Z.auswahl[v]);}
function gewaehlteNachfahren(id){return Object.keys(Z.auswahl).filter(r=>vorfahren(r).includes(id));}

function kacheln(){
  const ids=Object.keys(Z.auswahl), fertig=ids.filter(r=>(Z.stand[r]||{}).bytes);
  const summe=Object.values(Z.stand).reduce((a,r)=>a+(r.bytes||0),0);
  const q=Z.quelle;
  const bau=Z.laufend.aktiv?`<span class="st lauf">baut</span> ${esc((B.regionen[Z.laufend.region]||{}).name||Z.laufend.region)}`:'ruht';
  const bauU=Z.laufend.aktiv?esc(Z.laufend.zeile||''):(Z.warteschlange.length?`${Z.warteschlange.length} warten`:(Z.geprueft?`geprüft ${uhr(Z.geprueft)}, nächste Prüfung ${uhr(Z.geprueft+21600)}`:''));
  $('#kacheln').innerHTML=
   `<div class="kachel"><div class="t">Regionen</div><div class="w">${fertig.length} von ${ids.length}</div><div class="u">gebaut</div></div>`+
   `<div class="kachel"><div class="t">Speicher</div><div class="w">${gb(summe)}</div><div class="u">${gb(Z.frei)} frei auf der NAS</div></div>`+
   `<div class="kachel"><div class="t">Datenstand</div><div class="w">${tag(q.neueste)}</div><div class="u">${q.modus==='auto'?'neuester Protomaps-Tagesbau':'nur lokaler Planet'} · Planet ${tag(q.lokal)}</div></div>`+
   `<div class="kachel"><div class="t">Bau</div><div class="w" style="font-size:16px">${bau}</div><div class="u">${bauU}</div></div>`+
   `<div class="kachel"><div class="t">Höhen</div><div class="w">${Z.hoehen.vorhanden||0} / ${Z.hoehen.gebraucht||0}</div><div class="u">Kacheln · ${gb(Z.hoehen.bytes||0)}</div></div>`;
  $('#kopfinfo').textContent=`Mate lädt jede Region einzeln über das Tor (/pakete/). Eine Region enthält ihre Unterregionen.`;
  let h='';
  if(Z.hoehen.neustart) h+=`<div class="hinweis">Neue Höhenkacheln: Valhalla neu starten (Portainer → karten-valhalla → Restart). <button id="erledigt">Erledigt</button></div>`;
  if(Z.pruefung_fehler) h+=`<div class="hinweis err">Prüfung fehlgeschlagen: ${esc(Z.pruefung_fehler)}</div>`;
  if(Z.gesamt_da) h+=`<div class="hinweis">Das alte Gesamtpaket liegt noch auf dem Server und im Katalog, bis alle gewählten Regionen einzeln gebaut sind.</div>`;
  $('#hinweise').innerHTML=h;
  const e=$('#erledigt'); if(e) e.onclick=()=>post('api/neustart',{});
}

function zustandZelle(id){
  const r=Z.stand[id]||{}, vm=Z.vorgemerkt.includes(id), lauf=Z.laufend.aktiv&&Z.laufend.region===id;
  if(lauf) return `<span class="st lauf">wird gebaut</span><div class="unter">${esc(Z.laufend.zeile||'')}</div>`;
  if(r.fehler) return `<span class="st err" title="${esc(r.fehler)}">Fehler</span><div class="unter" title="${esc(r.fehler)}">${esc(r.fehler)}</div>`;
  if(vm) return `<span class="st blau">Neubau vorgemerkt</span>`;
  if(!r.bytes) return `<span class="st">wartet auf den Bau</span>`;
  if(Z.warteschlange.includes(id)) return `<span class="st blau">wird aufgefrischt</span>`;
  const am=r.faellig_am;
  return `<span class="st ok">aktuell</span><div class="unter">${am?(am*1000<Date.now()?'auffrischen, sobald neuere Daten da sind':'nächste Auffrischung ab '+tag(am)):'wird nicht aufgefrischt'}</div>`;
}
function liste(){
  const ids=Object.keys(Z.auswahl).sort((a,b)=>pfadText(a).localeCompare(pfadText(b),'de')||B.regionen[a].name.localeCompare(B.regionen[b].name,'de'));
  const bleiben=Object.keys(Z.stand).filter(r=>!Z.auswahl[r]&&Z.stand[r].bytes);
  const summe=ids.reduce((a,r)=>a+((Z.stand[r]||{}).bytes||0),0);
  $('#summe').textContent=ids.length?`${ids.length} Region${ids.length==1?'':'en'} · ${gb(summe)}`:'';
  if(!ids.length){$('#liste').innerHTML='<div class="leer">Noch keine Region gewählt – unten im Baum ankreuzen.</div>';return;}
  let t='<table><tr><th>Region</th><th class="zahl">Größe</th><th>Datenstand</th><th>Auffrischen</th><th>Zustand</th><th></th></tr>';
  for(const id of ids){
    const r=Z.stand[id]||{}, lauf=Z.laufend.aktiv&&Z.laufend.region===id;
    t+=`<tr><td><div class="name">${esc(B.regionen[id].name)}</div><div class="pfad">${esc(pfadText(id))}</div></td>`+
      `<td class="zahl">${r.bytes?gb(r.bytes):'–'}</td><td>${r.planet?tag(r.planet):'–'}${r.quelle?`<div class="pfad">${esc(r.quelle)}</div>`:''}</td>`+
      `<td>${monatsAuswahl(Z.auswahl[id].monate,`data-monate="${id}"`)}</td><td>${zustandZelle(id)}</td>`+
      `<td class="zahl"><button data-neu="${id}"${lauf||Z.vorgemerkt.includes(id)?' disabled':''}>Neu bauen</button> <button class="gefahr" data-weg="${id}">Entfernen</button></td></tr>`;
  }
  for(const id of bleiben){
    const oben=vorfahren(id).find(v=>Z.auswahl[v]);
    t+=`<tr><td><div class="name" style="color:var(--leise)">${esc((B.regionen[id]||{}).name||id)}</div><div class="pfad">${esc(pfadText(id))}</div></td><td class="zahl">${gb(Z.stand[id].bytes)}</td><td>${tag(Z.stand[id].planet)}</td><td></td>`+
      `<td><span class="st">bleibt, bis ${esc(oben?B.regionen[oben].name:'die Oberregion')} gebaut ist</span></td><td></td></tr>`;
  }
  $('#liste').innerHTML=t+'</table>';
}

function knoten(id,treffer,q){
  const r=B.regionen[id], kinder=B.kinder[id]||[], an=!!Z.auswahl[id], oben=gewaehlterVorfahr(id), unten=gewaehlteNachfahren(id);
  const sichtbar=treffer?kinder.filter(k=>treffer.has(k)):kinder;
  const auf=treffer?true:offen.has(id);
  let name=esc(r.name);
  if(q){const i=r.name.toLowerCase().indexOf(q); if(i>=0) name=esc(r.name.slice(0,i))+'<mark>'+esc(r.name.slice(i,i+q.length))+'</mark>'+esc(r.name.slice(i+q.length));}
  let marke='';
  if(an) marke=`<span class="st ok">gewählt</span>`;
  else if(oben) marke=`<span class="st">in ${esc(B.regionen[oben].name)} enthalten</span>`;
  else if(unten.length) marke=`<span class="st blau" title="Wird diese Region gewählt, ersetzt sie ${unten.length} gewählte Unterregion(en)">${unten.length} Unterregion${unten.length==1?'':'en'} gewählt</span>`;
  let h=`<div class="knoten${oben?' aus':''}"><span class="auf" data-auf="${id}">${kinder.length?(auf?'▾':'▸'):''}</span>`+
    `<label><input type="checkbox" data-id="${id}"${an?' checked':''}${oben?' disabled checked':''}> <span>${name}</span> ${marke}</label></div>`;
  if(kinder.length&&auf) h+=`<div class="kinder">${sichtbar.map(k=>knoten(k,treffer,q)).join('')}</div>`;
  return h;
}
function baum(){
  const q=$('#suche').value.trim().toLowerCase();
  let treffer=null;
  if(q.length>=2){
    treffer=new Set();
    for(const id in B.regionen) if(B.regionen[id].name.toLowerCase().includes(q)){treffer.add(id); vorfahren(id).forEach(v=>treffer.add(v));}
    if(!treffer.size){$('#baum').innerHTML='<div class="leer">Keine Region gefunden.</div>';return;}
  }
  const wurzeln=treffer?B.wurzeln.filter(w=>treffer.has(w)):B.wurzeln;
  $('#baum').innerHTML=wurzeln.map(w=>knoten(w,treffer,q.length>=2?q:'')).join('');
}
function zeichne(){ if(!B||!Z) return; kacheln(); liste(); if(!document.activeElement||document.activeElement.tagName!=='SELECT') baum(); }

document.addEventListener('click',e=>{
  const t=e.target;
  if(t.dataset.auf){ offen.has(t.dataset.auf)?offen.delete(t.dataset.auf):offen.add(t.dataset.auf); baum(); return; }
  if(t.dataset.neu){ post('api/neu',{region:t.dataset.neu}); return; }
  if(t.dataset.weg){ if(confirm(`${B.regionen[t.dataset.weg].name} entfernen? Das Paket wird auf dem Server gelöscht; Mate bietet es dann nicht mehr an.`)) post('api/region',{id:t.dataset.weg,an:false}); return; }
});
document.addEventListener('change',e=>{
  const t=e.target;
  if(t.dataset.monate){ post('api/region',{id:t.dataset.monate,an:true,monate:Number(t.value)}); return; }
  if(t.dataset.id){
    const id=t.dataset.id;
    if(t.checked){ const unten=gewaehlteNachfahren(id);
      if(unten.length&&!confirm(`${B.regionen[id].name} enthält ${unten.length} gewählte Unterregion(en). Sie gehen in ${B.regionen[id].name} auf und werden gelöscht, sobald das Paket gebaut ist.`)){t.checked=false;return;}
      post('api/region',{id,an:true,monate:Number($('#neuMonate').value)});
    } else post('api/region',{id,an:false});
  }
});
$('#suche').addEventListener('input',baum);
async function laden(){ const r=await fetch('api/zustand'); Z=await r.json(); zeichne(); }
(async()=>{ B=await (await fetch('api/baum')).json(); await laden(); setInterval(laden,4000); })();
</script></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "karten-regionen"

    def log_message(self, fmt, *args):
        pass

    def erlaubt(self):
        kopf = self.headers.get("Authorization", "")
        if ADMIN_PASS and kopf.startswith("Basic "):
            try:
                user, _, pw = base64.b64decode(kopf[6:]).decode().partition(":")
            except ValueError:
                user, pw = "", ""
            if user == "admin" and hmac.compare_digest(pw, ADMIN_PASS):
                return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Kartenpakete"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def senden(self, status, body, art="application/json"):
        if not isinstance(body, bytes):
            body = (json.dumps(body, ensure_ascii=False) if art == "application/json" else body).encode()
        self.send_response(status)
        self.send_header("Content-Type", art + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def zustand(self):
        auswahl = auswahl_lesen()
        with SPERRE:
            stand = lies("stand.json", {})
        regionen = {}
        for rid, r in stand.get("regionen", {}).items():
            e = {"name": r.get("name", rid), "planet": r.get("planet"), "gebaut": r.get("gebaut"), "quelle": r.get("quelle"),
                 "fehler": r.get("fehler"), "bytes": sum(d["bytes"] for d in r.get("dateien", []))}
            if rid in auswahl:
                e["faellig_am"] = faellig_am(r, auswahl[rid]["monate"])
            regionen[rid] = e
        bau = neuester_bau() if QUELLE == "auto" else None
        lokal = planet_zeit()
        neueste = max([t for t in (lokal, bau[1] if bau else None) if t] or [0])
        try:
            warte = faellige(neueste) if neueste else []
        except Exception:  # noqa: BLE001
            warte = []
        hoehen = {k: v for k, v in stand.get("hoehen", {}).items() if k not in ("eigene", "leer")}
        return {"auswahl": auswahl, "stand": regionen, "vorgemerkt": stand.get("vorgemerkt", []), "warteschlange": warte,
                "laufend": LAUFEND, "quelle": {"modus": QUELLE, "lokal": lokal, "neueste": neueste},
                "hoehen": hoehen, "frei": shutil.disk_usage(PAKETE).free, "geprueft": stand.get("geprueft"),
                "pruefung_fehler": stand.get("pruefung_fehler"),
                "gesamt_da": os.path.exists(os.path.join(PAKETE, GESAMT, "karte.pmtiles"))}

    def do_GET(self):
        if not self.erlaubt():
            return
        teile = urllib.parse.urlsplit(self.path)
        if teile.path in ("/", "/index.html"):
            return self.senden(200, SEITE, "text/html")
        if teile.path == "/live":
            return self.senden(200, LIVE_SEITE, "text/html")
        if teile.path == "/api/live":
            q = urllib.parse.parse_qs(teile.query)
            try:
                fenster = min(max(int(q.get("fenster", ["3600"])[0]), 60), 7 * 86400)
            except ValueError:
                fenster = 3600
            return self.senden(200, live_daten(fenster, q.get("gesund", ["0"])[0] == "1"))
        if teile.path == "/api/baum":
            idx = geofabrik_index()
            kinder = {}
            for rid, r in idx.items():
                if r["eltern"] in idx:
                    kinder.setdefault(r["eltern"], []).append(rid)
            for k in kinder.values():
                k.sort(key=lambda x: idx[x]["name"])
            wurzeln = sorted((r for r in idx if idx[r]["eltern"] not in idx), key=lambda x: idx[x]["name"])
            return self.senden(200, {"regionen": {k: {"name": v["name"], "eltern": v["eltern"]} for k, v in idx.items()},
                                     "kinder": kinder, "wurzeln": wurzeln})
        if teile.path == "/api/zustand":
            return self.senden(200, self.zustand())
        return self.senden(404, {"fehler": "unbekannt"})

    def do_POST(self):
        if not self.erlaubt():
            return
        try:
            laenge = min(int(self.headers.get("Content-Length", "0")), 1 << 20)
            daten = json.loads(self.rfile.read(laenge) or b"{}")
            if not isinstance(daten, dict):
                raise ValueError("Objekt erwartet")
            if self.path == "/api/neustart":
                neustart_erledigt()
            elif self.path == "/api/neu":
                vormerken(str(daten.get("region", "")))
            elif self.path == "/api/region":
                region_setzen(str(daten.get("id", "")), bool(daten.get("an", True)), daten.get("monate"))
            else:
                return self.senden(404, {"fehler": "unbekannt"})
        except (ValueError, TypeError) as fehler:
            return self.senden(400, {"fehler": str(fehler)})
        return self.senden(200, {"ok": True})


def main():
    if not ADMIN_PASS:
        sys.exit("ADMIN_PASS ist nicht gesetzt – ohne Passwort startet die Oberfläche nicht.")
    os.makedirs(DATEN, exist_ok=True)
    geofabrik_index()
    threading.Thread(target=arbeiter, daemon=True).start()
    WECKER.set()
    log("karten-regionen hört auf :8095")
    ThreadingHTTPServer(("0.0.0.0", 8095), Handler).serve_forever()


if __name__ == "__main__":
    main()
