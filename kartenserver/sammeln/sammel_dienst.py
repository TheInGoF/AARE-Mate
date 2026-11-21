#!/usr/bin/env python3
"""Sammelstelle des Kartenservers: Ladebewertungen und Schild-Korrekturen von Mate.

Navi L-003 / E-010: Ladebewertungen anonym auf dem eigenen Server; E-013: Schild-Korrekturen (L-027) über
dieselbe Schnittstelle. Läuft hinter dem Tor (Geräte-Token prüft das Tor), speichert aber nichts über das
Gerät: kein Token, keine Adresse, nur den Tag (keine Uhrzeit) und den Ort des Laders bzw. Schilds auf
4 Nachkommastellen (etwa 11 m - ein Lader ist ein öffentlicher Ort, eine Fahrt lässt sich daraus nicht lesen).

Schnittstelle (hinter dem Tor unter /sammeln/...):
  POST /bewertung  {"lat","lon","kw","netz"?,"sterne"?:1-5,"leistung"?:1-3,"preis"?:1-3,"funktion"?:1-2,"kennung"?}
                   (wie Mate fragt: Leistung 1 wie angegeben, 2 gedrosselt, 3 sehr langsam; Preis 1 günstig,
                   2 angemessen, 3 teuer; Funktion 1 ja, 2 nein; mindestens eins davon)
  GET  /bewertungen            je Lader: Anzahl, Mittel der Sterne, Leistung, Preis, Anteil "funktioniert"
  POST /schild     {"lat","lon","alt":km/h,"neu":km/h,"richtung"?:Grad,"weg"?:OSM-Weg}
  GET  /schilder               alle Meldungen, neueste zuerst
  GET  /gesund                 Lebenszeichen
Webseite (Navi L-028) auf SEITE_PORT (8001, im Compose 8096), nur lesen, Benutzer admin / ADMIN_PASS:
  GET  /                       zwei Karten: Ladestationen mit Bewertungen, Tempolimit-Meldungen
  GET  /daten/bewertungen, /daten/schilder   dieselben Listen für die Seite
Nur Standardbibliothek (python:3.12-alpine), Daten in /daten/sammeln.db.
"""
import datetime
import json
import os
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB = os.environ.get("DB", "/daten/sammeln.db")
PORT = int(os.environ.get("PORT", "8000"))
SEITE_PORT = int(os.environ.get("SEITE_PORT", "8001"))
ADMIN_PASS = os.environ.get("ADMIN_PASS", "")
MAX_BODY = 4096
_lock = threading.Lock()


def verbindung():
    c = sqlite3.connect(DB)
    c.execute("""CREATE TABLE IF NOT EXISTS bewertung (
        lat REAL, lon REAL, kw INTEGER, netz TEXT, sterne INTEGER,
        leistung INTEGER, preis INTEGER, funktion INTEGER, tag TEXT, kennung TEXT UNIQUE)""")
    c.execute("""CREATE TABLE IF NOT EXISTS schild (
        lat REAL, lon REAL, alt INTEGER, neu INTEGER, richtung INTEGER, weg INTEGER, tag TEXT,
        stand TEXT DEFAULT 'neu')""")
    return c


def heute():
    return datetime.date.today().isoformat()


def ort(d):
    """Breite und Länge aus der Anfrage, gerundet; ValueError, wenn sie fehlen oder unsinnig sind."""
    lat, lon = float(d["lat"]), float(d["lon"])
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise ValueError("ort")
    return round(lat, 4), round(lon, 4)


def note(d, k, hoechstens):
    """Eine Stufe 1..[hoechstens], None wo sie fehlt (0 = nicht angegeben, wie in Mate)."""
    v = d.get(k)
    if v is None or v == 0:
        return None
    v = int(v)
    if not 1 <= v <= hoechstens:
        raise ValueError(k)
    return v


def bewertung_speichern(d):
    lat, lon = ort(d)
    kw = int(d.get("kw") or 0)
    netz = (str(d.get("netz") or "")[:40]) or None
    noten = (note(d, "sterne", 5), note(d, "leistung", 3), note(d, "preis", 3), note(d, "funktion", 2))
    if all(n is None for n in noten):
        raise ValueError("leer")
    # die Kennung einer Bewertung (zufällig je Ladevorgang, ohne Bezug zum Gerät): eine spätere Änderung ersetzt sie
    kennung = str(d.get("kennung") or "")[:40] or None
    zeile = (lat, lon, kw, netz) + noten + (heute(), kennung)
    with _lock, verbindung() as c:
        c.execute("INSERT OR REPLACE INTO bewertung VALUES (?,?,?,?,?,?,?,?,?,?)", zeile)


def bewertungen():
    with _lock, verbindung() as c:
        rows = c.execute("""SELECT lat, lon, MAX(kw), MAX(netz), COUNT(*), AVG(sterne), AVG(leistung), AVG(preis),
            AVG(CASE funktion WHEN 1 THEN 1.0 WHEN 2 THEN 0.0 END), MAX(tag) FROM bewertung GROUP BY lat, lon ORDER BY COUNT(*) DESC""").fetchall()
    r1 = lambda v: None if v is None else round(v, 1)
    return [{"lat": a, "lon": b, "kw": kw, "netz": n, "anzahl": cnt, "sterne": r1(s), "leistung": r1(l),
             "preis": r1(p), "funktioniert": None if f is None else round(f, 2), "zuletzt": t} for a, b, kw, n, cnt, s, l, p, f, t in rows]


def schild_speichern(d):
    lat, lon = ort(d)
    alt, neu = int(d["alt"]), int(d["neu"])
    if not (0 <= alt <= 200 and 0 <= neu <= 200) or alt == neu:
        raise ValueError("tempo")
    richtung = d.get("richtung")
    richtung = None if richtung is None else int(richtung) % 360
    weg = d.get("weg")
    weg = None if weg is None else int(weg)
    with _lock, verbindung() as c:
        c.execute("INSERT INTO schild (lat, lon, alt, neu, richtung, weg, tag) VALUES (?,?,?,?,?,?,?)",
                  (lat, lon, alt, neu, richtung, weg, heute()))


def schilder():
    with _lock, verbindung() as c:
        rows = c.execute("SELECT rowid, lat, lon, alt, neu, richtung, weg, tag, stand FROM schild ORDER BY rowid DESC").fetchall()
    return [{"id": i, "lat": a, "lon": b, "alt": al, "neu": n, "richtung": r, "weg": w, "tag": t, "stand": s}
            for i, a, b, al, n, r, w, t, s in rows]



SEITE = """<!DOCTYPE html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mate – Sammelstelle</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
 body{margin:0;font:15px system-ui,sans-serif;background:#0d1117;color:#f0f6fc}
 header{display:flex;gap:8px;align-items:center;padding:10px 16px;background:#161b22;border-bottom:1px solid #30363d}
 header b{color:#1de9b6;margin-right:12px}
 button{background:#21262d;color:#f0f6fc;border:1px solid #30363d;border-radius:10px;padding:8px 14px;cursor:pointer}
 button.an{background:#0e3b36;border-color:#1de9b6}
 #karte{position:absolute;top:52px;bottom:0;left:0;right:340px}
 #liste{position:absolute;top:52px;bottom:0;right:0;width:340px;overflow:auto;background:#161b22;border-left:1px solid #30363d}
 .e{padding:10px 14px;border-bottom:1px solid #30363d;cursor:pointer}.e:hover{background:#21262d}
 .k{color:#8b949e;font-size:13px}
</style></head><body>
<header><b>Mate · Sammelstelle</b><button id="b1" class="an">Ladestationen</button><button id="b2">Tempolimit-Meldungen</button><span id="n" class="k"></span></header>
<div id="karte"></div><div id="liste"></div>
<script>
const karte=L.map('karte').setView([51.2,10.4],6);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(karte);
const ebene=L.layerGroup().addTo(karte);
const farbe=s=>s==null?'#8b949e':s>=4?'#1de9b6':s>=3?'#e3b341':'#f85149';
const stufe=(n,v,t)=>v==null?'':' · '+n+' '+(t[Math.round(v)-1]||'');
const tag=d=>d?d.split('-').reverse().join('.'):'';
const txt=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function lade(art){
  document.getElementById('b1').className=art==='bewertungen'?'an':'';
  document.getElementById('b2').className=art==='schilder'?'an':'';
  ebene.clearLayers(); const liste=document.getElementById('liste'); liste.innerHTML='';
  const daten=await (await fetch(location.origin+'/daten/'+art,{credentials:'same-origin'})).json();
  document.getElementById('n').textContent=daten.length+(art==='bewertungen'?' Lader':(daten.length===1?' Meldung':' Meldungen'));
  const punkte=[];
  for(const d of daten){
    let kopf,zeile,f;
    if(art==='bewertungen'){
      f=farbe(d.sterne);
      kopf=(txt(d.netz)||'Lader')+' · '+d.kw+' kW';
      zeile=(d.sterne!=null?d.sterne+' ★ · ':'')+d.anzahl+(d.anzahl==1?' Bewertung':' Bewertungen')+stufe('Leistung',d.leistung,['wie angegeben','gedrosselt','sehr langsam'])+
        stufe('Preis',d.preis,['günstig','angemessen','teuer'])+(d.funktioniert!=null?' · funktioniert '+Math.round(d.funktioniert*100)+' %':'')+' · zuletzt '+tag(d.zuletzt);
    }else{
      f=d.stand==='neu'?'#e3b341':'#8b949e';
      kopf=d.alt+' → '+d.neu+' km/h';
      zeile=tag(d.tag)+' · '+txt(d.stand)+(d.weg?' · OSM-Weg '+d.weg:'')+(d.richtung!=null?' · Richtung '+d.richtung+'°':'');
    }
    const m=L.circleMarker([d.lat,d.lon],{radius:8,color:f,fillColor:f,fillOpacity:.8}).bindPopup('<b>'+kopf+'</b><br>'+zeile).addTo(ebene);
    punkte.push([d.lat,d.lon]);
    const e=document.createElement('div'); e.className='e'; e.innerHTML='<div>'+kopf+'</div><div class="k">'+zeile+'</div>';
    e.onclick=()=>{karte.setView([d.lat,d.lon],15);m.openPopup();}; liste.appendChild(e);
  }
  if(punkte.length) karte.fitBounds(punkte,{maxZoom:13,padding:[30,30]});
}
document.getElementById('b1').onclick=()=>lade('bewertungen');
document.getElementById('b2').onclick=()=>lade('schilder');
lade('bewertungen');
</script></body></html>"""


class Seite(BaseHTTPRequestHandler):
    """Die Webseite (L-028): nur lesen, mit Passwort; Annehmen geht nur über das Tor."""
    server_version = "sammeln-seite"

    def senden(self, code, body, typ):
        self.send_response(code)
        self.send_header("Content-Type", typ)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def erlaubt(self):
        import base64
        kopf = self.headers.get("Authorization", "")
        if ADMIN_PASS and kopf.startswith("Basic "):
            try:
                benutzer, _, pw = base64.b64decode(kopf[6:]).decode().partition(":")
                if benutzer == "admin" and pw == ADMIN_PASS:
                    return True
            except ValueError:
                pass
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Sammelstelle"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def do_GET(self):
        if not self.erlaubt():
            return
        pfad = self.path.split("?")[0]
        if pfad == "/":
            return self.senden(200, SEITE.encode(), "text/html; charset=utf-8")
        if pfad == "/favicon.ico":
            return self.senden(204, b"", "image/x-icon")
        if pfad == "/daten/bewertungen":
            return self.senden(200, json.dumps(bewertungen(), ensure_ascii=False).encode(), "application/json; charset=utf-8")
        if pfad == "/daten/schilder":
            return self.senden(200, json.dumps(schilder(), ensure_ascii=False).encode(), "application/json; charset=utf-8")
        self.senden(404, b"", "text/plain")

    def log_message(self, fmt, *args):
        pass

class Handler(BaseHTTPRequestHandler):
    server_version = "sammeln"

    def antwort(self, code, daten):
        body = json.dumps(daten, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        pfad = self.path.split("?")[0]
        if pfad == "/gesund":
            return self.antwort(200, {"ok": True})
        if pfad == "/bewertungen":
            return self.antwort(200, bewertungen())
        if pfad == "/schilder":
            return self.antwort(200, schilder())
        self.antwort(404, {"fehler": "unbekannt"})

    def do_POST(self):
        pfad = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self.antwort(413 if n > MAX_BODY else 400, {"fehler": "groesse"})
        try:
            d = json.loads(self.rfile.read(n))
            if not isinstance(d, dict):
                raise ValueError("json")
            if pfad == "/bewertung":
                bewertung_speichern(d)
            elif pfad == "/schild":
                schild_speichern(d)
            else:
                return self.antwort(404, {"fehler": "unbekannt"})
        except (ValueError, KeyError, TypeError) as e:
            return self.antwort(400, {"fehler": str(e)})
        self.antwort(201, {"ok": True})

    def log_message(self, fmt, *args):
        # nur Weg und Ergebnis, keine Adressen (das Tor steht davor, die Adresse wäre ohnehin das Tor)
        print("%s %s" % (self.command, args[1] if len(args) > 1 else ""), flush=True)


if __name__ == "__main__":
    os.makedirs(os.path.dirname(DB) or ".", exist_ok=True)
    verbindung().close()
    print("sammeln: Port %d, Seite %d%s, Daten %s" % (PORT, SEITE_PORT, "" if ADMIN_PASS else " (ohne ADMIN_PASS gesperrt)", DB), flush=True)
    threading.Thread(target=ThreadingHTTPServer(("", SEITE_PORT), Seite).serve_forever, daemon=True).start()
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
