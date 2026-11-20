#!/usr/bin/env python3
"""Kopplungsdienst für den Kartenserver (Stack "karten").

Dienst (im Container, Port 8000, nur im Docker-Netz):
  GET  /pruefen   für Caddy forward_auth: 200, wenn "Authorization: Bearer <token>" gültig ist, sonst 401
  POST /koppeln   {"code": "..."} → {"token": "...", "geraet": "..."}; Code gilt einmal, 10 Minuten

Verwaltung (docker exec karten-schluessel python /app/schluessel.py …):
  koppeln "<Gerätename>"   neuen Kopplungscode erzeugen und als QR zeigen
  liste                    Geräte und offene Codes
  sperren "<Gerätename>"   Gerät sperren (Token sofort ungültig)

Gespeichert werden nur SHA-256-Werte von Tokens und Codes (/daten/schluessel.json).
"""
import fcntl
import hashlib
import hmac
import json
import os
import secrets
import shutil
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATEI = os.environ.get("SCHLUESSEL_DATEI", "/daten/schluessel.json")
BASIS_URL = os.environ.get("BASIS_URL", "")
ALT_TOKEN = os.environ.get("MATE_TOKEN", "")      # Übergang: gemeinsamer Token, gilt solange gesetzt
CODE_GUELTIG_S = 600
CODE_ZEICHEN = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # ohne 0/O/1/I
FEHLVERSUCHE_MAX = 20                               # je 10 Minuten, für /koppeln


def h(text):
    return hashlib.sha256(text.encode()).hexdigest()


def jetzt():
    return int(time.time())


# ---------- Ablage ----------

def lade():
    try:
        with open(DATEI, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {"geraete": {}, "codes": {}}


class Sperre:
    """Exklusiver Zugriff auf die Ablage über Dienst und Verwaltung hinweg."""
    def __enter__(self):
        os.makedirs(os.path.dirname(DATEI), exist_ok=True)
        self.f = open(DATEI + ".lock", "w")
        fcntl.flock(self.f, fcntl.LOCK_EX)
        return self

    def __exit__(self, *a):
        fcntl.flock(self.f, fcntl.LOCK_UN)
        self.f.close()


def speichere(daten):
    tmp = DATEI + ".neu"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(daten, f, ensure_ascii=False, indent=1)
    os.chmod(tmp, 0o600)
    os.replace(tmp, DATEI)


def aufraeumen(daten):
    daten["codes"] = {k: v for k, v in daten["codes"].items() if v["bis"] > jetzt()}


# ---------- Dienst ----------

class Zustand:
    """Gültige Token-Hashes im Speicher; neu geladen, wenn sich die Datei ändert."""
    def __init__(self):
        self.mtime = None
        self.gueltig = set()
        self.fehler = []

    def aktuell(self):
        try:
            m = os.stat(DATEI).st_mtime_ns
        except FileNotFoundError:
            m = 0
        if m != self.mtime:
            d = lade()
            self.gueltig = {k for k, v in d["geraete"].items() if not v.get("gesperrt")}
            self.mtime = m
        return self.gueltig


ZUSTAND = Zustand()


class Handler(BaseHTTPRequestHandler):
    server_version = "karten-schluessel"

    def log_message(self, fmt, *args):
        if not self.path.startswith("/pruefen"):          # Kachelflut nicht protokollieren
            sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def antwort(self, status, daten=None):
        body = json.dumps(daten).encode() if daten is not None else b""
        self.send_response(status)
        if body:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.split("?")[0] != "/pruefen":
            return self.antwort(404)
        kopf = self.headers.get("Authorization", "")
        if not kopf.startswith("Bearer "):
            return self.antwort(401)
        token = kopf[7:].strip()
        if ALT_TOKEN and hmac.compare_digest(token, ALT_TOKEN):
            return self.antwort(200)
        return self.antwort(200 if h(token) in ZUSTAND.aktuell() else 401)

    def do_POST(self):
        if self.path.split("?")[0] != "/koppeln":
            return self.antwort(404)
        z = jetzt()
        ZUSTAND.fehler = [t for t in ZUSTAND.fehler if t > z - 600]
        if len(ZUSTAND.fehler) >= FEHLVERSUCHE_MAX:
            return self.antwort(429, {"fehler": "zu viele Versuche, später erneut"})
        try:
            laenge = min(int(self.headers.get("Content-Length", "0")), 4096)
            code = str(json.loads(self.rfile.read(laenge) or b"{}").get("code", ""))
        except (ValueError, AttributeError):
            return self.antwort(400, {"fehler": "JSON mit \"code\" erwartet"})
        code = code.strip().upper().replace("-", "").replace(" ", "")
        with Sperre():
            daten = lade()
            aufraeumen(daten)
            eintrag = daten["codes"].pop(h(code), None) if code else None
            if not eintrag:
                ZUSTAND.fehler.append(z)
                speichere(daten)
                return self.antwort(403, {"fehler": "Code ungültig, abgelaufen oder schon benutzt"})
            token = secrets.token_hex(32)
            daten["geraete"][h(token)] = {"name": eintrag["name"], "seit": z}
            speichere(daten)
        self.log_message("gekoppelt: %s", eintrag["name"])
        return self.antwort(200, {"token": token, "geraet": eintrag["name"]})


def dienst():
    if not BASIS_URL:
        print("Hinweis: BASIS_URL nicht gesetzt – QR-Codes enthalten dann keine Adresse", file=sys.stderr)
    print("karten-schluessel hört auf :8000" + ("  (Übergangs-Token aktiv)" if ALT_TOKEN else ""), flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()


# ---------- Verwaltung ----------

def koppeln(name):
    roh = "".join(secrets.choice(CODE_ZEICHEN) for _ in range(12))
    with Sperre():
        daten = lade()
        aufraeumen(daten)
        daten["codes"][h(roh)] = {"name": name, "bis": jetzt() + CODE_GUELTIG_S}
        speichere(daten)
    lesbar = "-".join(roh[i:i + 4] for i in range(0, 12, 4))
    inhalt = json.dumps({"v": 1, "u": BASIS_URL, "c": roh}, separators=(",", ":"))
    print(f"Gerät: {name}\nCode:  {lesbar}  (gilt 10 Minuten, einmal)\nQR-Inhalt: {inhalt}\n")
    if shutil.which("qrencode"):
        subprocess.run(["qrencode", "-t", "ansiutf8", "-m", "2", inhalt], check=False)
    else:
        print("(qrencode fehlt – QR-Inhalt oben in einen QR-Generator ohne Internet geben)")


def liste():
    daten = lade()
    aufraeumen(daten)
    print("Geräte:")
    for v in sorted(daten["geraete"].values(), key=lambda v: v["seit"]):
        seit = time.strftime("%d.%m.%Y %H:%M", time.localtime(v["seit"]))
        print(f"  {v['name']:30} seit {seit}" + ("  GESPERRT" if v.get("gesperrt") else ""))
    print(f"Offene Codes: {len(daten['codes'])}")
    if ALT_TOKEN:
        print("Übergangs-Token (MATE_TOKEN) ist noch aktiv.")


def sperren(name):
    with Sperre():
        daten = lade()
        treffer = [v for v in daten["geraete"].values() if v["name"] == name and not v.get("gesperrt")]
        for v in treffer:
            v["gesperrt"] = jetzt()
        speichere(daten)
    print(f"{len(treffer)} Gerät(e) gesperrt: {name}" if treffer else f"kein aktives Gerät namens {name!r}")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a == ["dienst"]:
        dienst()
    elif len(a) == 2 and a[0] == "koppeln":
        koppeln(a[1])
    elif a == ["liste"]:
        liste()
    elif len(a) == 2 and a[0] == "sperren":
        sperren(a[1])
    else:
        sys.exit(__doc__)
