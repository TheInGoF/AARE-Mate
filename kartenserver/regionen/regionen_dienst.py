#!/usr/bin/env python3
"""karten-regionen: Offline-Karten für Mate auswählen, als ein Gesamtpaket bauen und aktuell halten.

Weboberfläche (Port 8095, nur LAN/Tailscale, Basic-Auth admin/ADMIN_PASS):
  alle Regionen von Geofabrik als Baum (Kontinent → Land → Bundesland …), ankreuzen, speichern.
Aus der ganzen Auswahl entsteht **ein** Paket (Vereinigung der Grenzen, nichts doppelt): /p/gesamt/karte.pmtiles und
grenze.geojson, dazu /p/katalog.json mit genau diesem einen Eintrag (Format 1, gelesen von der App über das Tor /pakete/).
Für dieselbe Auswahl lädt der Dienst die Höhenkacheln (1°×1°, Tilezen „skadi“) nach /hoehen, dem Höhenordner von Valhalla
(`additional_data.elevation`); Kacheln, die keine gewählte Region mehr braucht, gibt er wieder frei (nur selbst geladene).

Neu gebaut wird, wenn sich die Auswahl ändert, wenn das Paket fehlt oder wenn der Planet neuer ist und das Paket mindestens
so alt wie das gewählte Intervall (0 = mit jedem neuen Planeten, 180, 365, -1 = nie).
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
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLANET = os.environ.get("PLANET", "/t/planet.pmtiles")
PAKETE = os.environ.get("PAKETE", "/p")
DATEN = os.environ.get("DATEN", "/daten")
HOEHEN = os.environ.get("HOEHEN", "/hoehen")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "")
INDEX_URL = os.environ.get("GEOFABRIK_INDEX", "https://download.geofabrik.de/index-v1-nogeom.json")
HOEHEN_URL = os.environ.get("HOEHEN_URL", "https://elevation-tiles-prod.s3.amazonaws.com/skadi")
PMTILES_VERSION = "1.30.1"
PMTILES = os.environ.get("PMTILES", os.path.join(DATEN, "bin", "pmtiles"))
PRUEF_ALLE_S = 6 * 3600
GRENZE_ALTER_S = 30 * 86400
INTERVALLE = {0: "mit jedem neuen Planeten", 180: "alle 6 Monate", 365: "jährlich", -1: "nie (einmalig bauen)"}
GESAMT = "gesamt"

SPERRE = threading.Lock()
WECKER = threading.Event()
LAUFEND = {"aktiv": False, "zeile": ""}


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


def planet_zeit():
    return int(os.path.getmtime(PLANET))


def auswahl_lesen():
    """{"regionen": [...], "intervall": n}; liest auch die alte Form {id: {"intervall": n}}."""
    a = lies("auswahl.json", {})
    if "regionen" in a:
        return {"regionen": sorted(a["regionen"]), "intervall": int(a.get("intervall", 0))}
    ivs = [int(e.get("intervall", 0)) for e in a.values() if isinstance(e, dict)]
    pos = [i for i in ivs if i >= 0]
    return {"regionen": sorted(a), "intervall": min(pos) if pos else (-1 if ivs else 0)}


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


# ---------- Gesamtpaket ----------

def katalog_schreiben():
    with SPERRE:
        g = lies("stand.json", {}).get("gesamt", {})
    regionen = []
    if g.get("dateien"):
        regionen.append({"id": GESAMT, "name": f"Gesamtpaket ({len(g['regionen'])} Regionen)", "eltern": None, "pfad": [],
                         "bbox": g["bbox"], "enthaelt": g.get("namen", []),
                         "dateien": [{"art": d["art"], "pfad": f"{GESAMT}/{d['datei']}", "bytes": d["bytes"],
                                      "sha256": d["sha256"]} for d in g["dateien"]]})
    stand_karte = datetime.datetime.fromtimestamp(planet_zeit(), datetime.timezone.utc).date().isoformat()
    os.makedirs(PAKETE, exist_ok=True)
    schreib(os.path.join(PAKETE, "katalog.json"), {"format": 1, "stand_karte": stand_karte, "regionen": regionen})


def faellig():
    a = auswahl_lesen()
    with SPERRE:
        g = lies("stand.json", {}).get("gesamt", {})
    if sorted(g.get("regionen", [])) != a["regionen"]:
        return True
    if not a["regionen"]:
        return False
    if not os.path.exists(os.path.join(PAKETE, GESAMT, "karte.pmtiles")):
        return True
    if a["intervall"] < 0:
        return False
    return planet_zeit() > g.get("planet", 0) and time.time() - g.get("gebaut", 0) >= a["intervall"] * 86400


def baue():
    a = auswahl_lesen()
    idx = geofabrik_index()
    ordner = os.path.join(PAKETE, GESAMT)
    if not a["regionen"]:
        shutil.rmtree(ordner, ignore_errors=True)
        hoehen_abgleichen([])
        return {"regionen": [], "namen": []}
    alle = []
    for rid in a["regionen"]:
        if rid not in idx:
            raise ValueError(f"Region {rid} nicht im Geofabrik-Index")
        LAUFEND["zeile"] = f"Grenze: {idx[rid]['name']}"
        alle.append(grenze(rid, idx))
    hoehen_abgleichen(alle)
    vereinigt = [poly for polygone in alle for poly in polygone]
    os.makedirs(ordner, exist_ok=True)
    grenze_datei = os.path.join(ordner, "grenze.geojson")
    schreib(grenze_datei, {"type": "Feature", "properties": {},
                           "geometry": {"type": "MultiPolygon", "coordinates": vereinigt}})
    pmtiles_bereit()
    planet = planet_zeit()
    tmp = os.path.join(ordner, "karte.pmtiles.neu")
    if os.path.exists(tmp):
        os.remove(tmp)
    LAUFEND["zeile"] = "Karte schneiden …"
    p = subprocess.Popen([PMTILES, "extract", PLANET, tmp, f"--region={grenze_datei}"],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    for zeile in p.stdout:
        LAUFEND["zeile"] = zeile.strip()[-120:]
    if p.wait() != 0:
        raise RuntimeError("pmtiles extract fehlgeschlagen: " + LAUFEND["zeile"])
    karte = os.path.join(ordner, "karte.pmtiles")
    os.replace(tmp, karte)
    return {"regionen": a["regionen"], "namen": sorted(idx[r]["name"] for r in a["regionen"]), "planet": planet,
            "gebaut": int(time.time()), "bbox": umriss(vereinigt),
            "dateien": [{"art": "karte", "datei": "karte.pmtiles", "bytes": os.path.getsize(karte), "sha256": sha256(karte)},
                        {"art": "grenze", "datei": "grenze.geojson", "bytes": os.path.getsize(grenze_datei),
                         "sha256": sha256(grenze_datei)}]}


def alte_einzelpakete_entfernen():
    """Aus der Zeit vor dem Gesamtpaket: /p/<region>/karte.pmtiles einzelner Regionen freigeben."""
    if not os.path.isdir(PAKETE):
        return
    for name in os.listdir(PAKETE):
        d = os.path.join(PAKETE, name)
        if name != GESAMT and os.path.isdir(d) and re.fullmatch(r"[a-z0-9_-]+", name) \
                and set(os.listdir(d)) <= {"karte.pmtiles", "grenze.geojson", "karte.pmtiles.neu"}:
            shutil.rmtree(d, ignore_errors=True)
            log("altes Einzelpaket entfernt:", name)


def arbeiter():
    while True:
        WECKER.wait(PRUEF_ALLE_S)
        WECKER.clear()
        try:
            if not faellig():
                hoehen_abgleichen([grenze(r, geofabrik_index()) for r in auswahl_lesen()["regionen"]])
                continue
        except Exception as fehler:  # noqa: BLE001 – Fehler in der Oberfläche zeigen
            log("Prüfung fehlgeschlagen:", fehler)
            continue
        LAUFEND.update(aktiv=True, zeile="startet")
        log("baue Gesamtpaket")
        try:
            ergebnis = baue()
            with SPERRE:
                stand = lies("stand.json", {})
                stand["gesamt"] = ergebnis
                schreib(pfad("stand.json"), stand)
            katalog_schreiben()
            if ergebnis.get("dateien"):
                log(f"fertig: {len(ergebnis['regionen'])} Regionen, {ergebnis['dateien'][0]['bytes'] / 1e9:.2f} GB")
        except Exception as fehler:  # noqa: BLE001
            log("Fehler:", fehler)
            with SPERRE:
                stand = lies("stand.json", {})
                stand.setdefault("gesamt", {})["fehler"] = str(fehler)[:300]
                schreib(pfad("stand.json"), stand)
        finally:
            LAUFEND.update(aktiv=False, zeile="")


def speichern(neu):
    """neu = {"regionen": [...], "intervall": n}."""
    idx = geofabrik_index()
    regionen = sorted({r for r in neu.get("regionen", []) if r in idx and sauberer_name(r)})
    iv = int(neu.get("intervall", 0))
    if iv not in INTERVALLE:
        raise ValueError("unbekanntes Intervall")
    with SPERRE:
        schreib(pfad("auswahl.json"), {"regionen": regionen, "intervall": iv})
        stand = lies("stand.json", {})
        stand.get("gesamt", {}).pop("fehler", None)
        schreib(pfad("stand.json"), stand)
    WECKER.set()


def neustart_erledigt():
    with SPERRE:
        stand = lies("stand.json", {})
        stand.setdefault("hoehen", {})["neustart"] = False
        schreib(pfad("stand.json"), stand)


# ---------- Weboberfläche ----------

SEITE = r"""<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kartenpakete</title>
<style>
body{font:15px/1.45 system-ui,sans-serif;margin:0;color:#1d2125;background:#f6f7f8}
header{position:sticky;top:0;background:#fff;border-bottom:1px solid #dde1e5;padding:10px 16px;z-index:1}
.reihe{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
h1{font-size:17px;margin:0 8px 0 0}
input[type=search]{padding:6px 9px;border:1px solid #c5cbd1;border-radius:6px;min-width:220px}
button{padding:7px 14px;border:0;border-radius:6px;background:#1f6feb;color:#fff;font-weight:600;cursor:pointer}
button:disabled{background:#9db7e0}
button.leise{background:#eef0f2;color:#1d2125;font-weight:500;padding:3px 10px}
select{padding:4px 6px;font-size:14px}
.info{color:#5b636b;font-size:13px}
.karte{margin-top:8px;display:flex;gap:16px;flex-wrap:wrap;font-size:14px}
.karte b{font-weight:600}
.hinweis{margin-top:8px;padding:6px 10px;border-radius:6px;background:#fff4d6;color:#6b4800;font-size:14px;display:flex;gap:10px;align-items:center}
main{padding:8px 16px 40px;max-width:980px}
details{margin-left:18px}
summary{cursor:pointer;padding:2px 0}summary .zeile{display:inline-flex;padding-left:0}
.zeile{display:flex;align-items:center;gap:8px;padding:3px 0 3px 18px;flex-wrap:wrap}
.zeile.wurzel{padding-left:0}
.name{min-width:200px}
.st{font-size:12px;padding:1px 7px;border-radius:9px;background:#eef0f2;color:#4a5157}
.st.ok{background:#e3f4e8;color:#1a7f37}.st.lauf{background:#fff4d6;color:#8a5a00}.st.err{background:#fde7e9;color:#b42318}
.versteckt{display:none}
</style>
<header>
<div class="reihe"><h1>Kartenpakete für Mate</h1>
<input type="search" id="suche" placeholder="Region suchen …">
<label class="info">Aktualisieren <select id="intervall"></select></label>
<button id="speichern" disabled>Speichern</button></div>
<div class="karte" id="gesamt"></div>
<div id="hinweis"></div>
</header>
<main id="baum">lädt …</main>
<script>
const IV={"0":"mit jedem neuen Planeten","180":"alle 6 Monate","365":"jährlich","-1":"nie (einmalig)"};
let D, auswahl=new Set(), intervall=0, geaendert=false;
const $=s=>document.querySelector(s);
const gb=b=>(b/1e9).toLocaleString('de-DE',{maximumFractionDigits:2})+' GB';
const datum=t=>new Date(t*1000).toLocaleDateString('de-DE');
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
$('#intervall').innerHTML=Object.entries(IV).map(([k,v])=>`<option value="${k}">${v}</option>`).join('');
function status(id){
  if(!auswahl.has(id)) return '';
  return (D.gesamt.regionen||[]).includes(id)?'<span class="st ok">im Paket</span>':'<span class="st">nach dem Speichern</span>';
}
function zeile(id,wurzel,kopf){
  const r=D.regionen[id], an=auswahl.has(id), tag=kopf?'span':'label';
  return `<${tag} class="zeile${wurzel?' wurzel':''}" data-n="${esc(r.name.toLowerCase())}"><input type="checkbox" data-id="${id}"${an?' checked':''}><span class="name">${esc(r.name)}</span>${status(id)}</${tag}>`;
}
function zweig(id,tiefe){
  const k=(D.kinder[id]||[]);
  if(!k.length) return zeile(id,tiefe===0,false);
  return `<details data-id="${id}"><summary>${zeile(id,true,true)}</summary>${k.map(c=>zweig(c,tiefe+1)).join('')}</details>`;
}
function kopf(){
  const g=D.gesamt, h=D.hoehen;
  let t;
  if(D.laufend.aktiv) t=`<span class="st lauf">wird gebaut … ${esc(D.laufend.zeile||'')}</span>`;
  else if(g.fehler) t=`<span class="st err" title="${esc(g.fehler)}">Fehler: ${esc(g.fehler.slice(0,80))}</span>`;
  else if(g.dateien) t=`<span class="st ok">${gb(g.dateien[0].bytes)} · ${datum(g.gebaut)}</span>`;
  else t='<span class="st">noch nicht gebaut</span>';
  const n=(g.regionen||[]).length;
  $('#gesamt').innerHTML=`<span><b>Gesamtpaket</b> ${n} Region${n==1?'':'en'} ${t}</span>`+
    `<span><b>Höhen</b> ${h.vorhanden||0} von ${h.gebraucht||0} Kacheln · ${gb(h.bytes||0)}</span>`+
    `<span class="info">${auswahl.size} ausgewählt · ${gb(D.frei)} frei · Planet vom ${datum(D.planet)}</span>`;
  $('#hinweis').innerHTML=h.neustart?`<div class="hinweis">Neue Höhenkacheln: Valhalla neu starten (Portainer → karten-valhalla → Restart). <button class="leise" id="erledigt">Erledigt</button></div>`:'';
  const e=$('#erledigt'); if(e) e.onclick=async()=>{await fetch('api/neustart',{method:'POST'}); laden();};
}
function zeichne(){
  const offen=[...document.querySelectorAll('details[open]')].map(d=>d.dataset.id);
  $('#baum').innerHTML=D.wurzeln.map(w=>zweig(w,0)).join('');
  offen.forEach(id=>{const d=document.querySelector(`details[data-id="${CSS.escape(id)}"]`); if(d) d.open=true;});
  kopf(); filtern();
}
function filtern(){
  const q=$('#suche').value.trim().toLowerCase();
  const t=n=>!q||n.includes(q);
  document.querySelectorAll('#baum details').forEach(d=>{const treffer=[...d.querySelectorAll('.zeile')].some(z=>t(z.dataset.n)); d.classList.toggle('versteckt',!treffer); if(q) d.open=treffer;});
  document.querySelectorAll('#baum > label.zeile, #baum details > label.zeile').forEach(l=>l.classList.toggle('versteckt',!t(l.dataset.n)));
}
function aendern(){ geaendert=true; $('#speichern').disabled=false; zeichne(); }
document.addEventListener('change',e=>{
  if(e.target.id==='intervall'){ intervall=Number(e.target.value); aendern(); return; }
  const id=e.target.dataset.id; if(!id) return;
  if(e.target.checked) auswahl.add(id); else auswahl.delete(id);
  aendern();
});
$('#suche').addEventListener('input',filtern);
$('#speichern').onclick=async()=>{
  $('#speichern').disabled=true;
  const r=await fetch('api/auswahl',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({regionen:[...auswahl],intervall})});
  if(!r.ok){alert('Speichern fehlgeschlagen: '+await r.text()); $('#speichern').disabled=false; return;}
  geaendert=false; laden();
};
async function laden(){
  const r=await fetch('api/zustand'); D=await r.json();
  if(!geaendert){ auswahl=new Set(D.auswahl.regionen); intervall=D.auswahl.intervall; $('#intervall').value=String(intervall); }
  zeichne();
}
laden(); setInterval(()=>{ if(!geaendert) laden(); },5000);
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

    def do_GET(self):
        if not self.erlaubt():
            return
        if self.path in ("/", "/index.html"):
            return self.senden(200, SEITE, "text/html")
        if self.path == "/api/zustand":
            idx = geofabrik_index()
            kinder = {}
            for rid, r in idx.items():
                if r["eltern"] in idx:
                    kinder.setdefault(r["eltern"], []).append(rid)
            for k in kinder.values():
                k.sort(key=lambda x: idx[x]["name"])
            wurzeln = sorted((r for r in idx if idx[r]["eltern"] not in idx), key=lambda x: idx[x]["name"])
            with SPERRE:
                stand = lies("stand.json", {})
            gesamt = {k: v for k, v in stand.get("gesamt", {}).items() if k != "namen"}
            hoehen = {k: v for k, v in stand.get("hoehen", {}).items() if k not in ("eigene", "leer")}
            return self.senden(200, {
                "regionen": {k: {"name": v["name"]} for k, v in idx.items()}, "kinder": kinder, "wurzeln": wurzeln,
                "auswahl": auswahl_lesen(), "gesamt": gesamt, "hoehen": hoehen, "laufend": LAUFEND,
                "frei": shutil.disk_usage(PAKETE).free, "planet": planet_zeit()})
        return self.senden(404, {"fehler": "unbekannt"})

    def do_POST(self):
        if not self.erlaubt():
            return
        if self.path == "/api/neustart":
            neustart_erledigt()
            return self.senden(200, {"ok": True})
        if self.path != "/api/auswahl":
            return self.senden(404, {"fehler": "unbekannt"})
        try:
            laenge = min(int(self.headers.get("Content-Length", "0")), 1 << 20)
            neu = json.loads(self.rfile.read(laenge) or b"{}")
            if not isinstance(neu, dict):
                raise ValueError("Objekt erwartet")
            speichern(neu)
        except (ValueError, TypeError) as fehler:
            return self.senden(400, {"fehler": str(fehler)})
        return self.senden(200, {"ok": True})


def main():
    if not ADMIN_PASS:
        sys.exit("ADMIN_PASS ist nicht gesetzt – ohne Passwort startet die Oberfläche nicht.")
    os.makedirs(DATEN, exist_ok=True)
    geofabrik_index()
    alte_einzelpakete_entfernen()
    with SPERRE:   # alte Form der Auswahl einmal umschreiben
        schreib(pfad("auswahl.json"), auswahl_lesen())
    katalog_schreiben()
    threading.Thread(target=arbeiter, daemon=True).start()
    WECKER.set()
    log("karten-regionen hört auf :8095")
    ThreadingHTTPServer(("0.0.0.0", 8095), Handler).serve_forever()


if __name__ == "__main__":
    main()
