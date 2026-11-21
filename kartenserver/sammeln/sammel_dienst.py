#!/usr/bin/env python3
"""Sammelstelle des Kartenservers: Ladebewertungen und Schild-Korrekturen von Mate.

Navi L-003 / E-010: Ladebewertungen anonym auf dem eigenen Server; E-013: Schild-Korrekturen (L-027) über
dieselbe Schnittstelle. Läuft hinter dem Tor (Geräte-Token prüft das Tor), speichert aber nichts über das
Gerät: kein Token, keine Adresse, nur den Tag (keine Uhrzeit) und den Ort des Laders bzw. Schilds auf
4 Nachkommastellen (etwa 11 m - ein Lader ist ein öffentlicher Ort, eine Fahrt lässt sich daraus nicht lesen).

Schnittstelle (hinter dem Tor unter /sammeln/...):
  POST /bewertung  {"lat","lon","kw","netz"?,"sterne"?:1-5,"leistung"?:1-3,"preis"?:1-3,"funktion"?:1-2}
                   (wie Mate fragt: Leistung 1 wie angegeben, 2 gedrosselt, 3 sehr langsam; Preis 1 günstig,
                   2 angemessen, 3 teuer; Funktion 1 ja, 2 nein; mindestens eins davon)
  GET  /bewertungen            je Lader: Anzahl, Mittel der Sterne, Leistung, Preis, Anteil "funktioniert"
  POST /schild     {"lat","lon","alt":km/h,"neu":km/h,"richtung"?:Grad,"weg"?:OSM-Weg}
  GET  /schilder               alle Meldungen, neueste zuerst
  GET  /gesund                 Lebenszeichen
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
MAX_BODY = 4096
_lock = threading.Lock()


def verbindung():
    c = sqlite3.connect(DB)
    c.execute("""CREATE TABLE IF NOT EXISTS bewertung (
        lat REAL, lon REAL, kw INTEGER, netz TEXT, sterne INTEGER,
        leistung INTEGER, preis INTEGER, funktion INTEGER, tag TEXT)""")
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
    zeile = (lat, lon, kw, netz) + noten + (heute(),)
    with _lock, verbindung() as c:
        c.execute("INSERT INTO bewertung VALUES (?,?,?,?,?,?,?,?,?)", zeile)


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
    print("sammeln: Port %d, Daten %s" % (PORT, DB), flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
