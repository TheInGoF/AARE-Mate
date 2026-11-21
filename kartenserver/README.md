# Kartenserver für Mate

Ein Docker-Stack „karten“ auf dem NAS liefert Mate Navi (und dem IDmate-Dashboard, Roundtrip) Kacheln, Routing, Suche,
Verkehr und Offline-Regionspakete. Die App erreicht ihn nur über Tailscale und nur über das **Tor** (Port 8090) mit einem
Geräte-Token.

```
 Tablet (Mate) ── Tailscale ──► karten-tor :8090 ──┬─ /kacheln/*   → karten-tiles (go-pmtiles, Protomaps-Planet)
                                  │                 ├─ /route/*     → karten-valhalla (Route, Matrix, Höhen, Isochrone)
                                  │                 ├─ /suche/*     → karten-photon (Suche, Rückwärtssuche)
                                  │                 ├─ /verkehr/*   → TomTom Traffic (Schlüssel bleibt auf dem Server)
                                  │                 └─ /pakete/*    → Offline-Regionspakete (Dateien)
                                  └─ forward_auth ──► karten-schluessel (Geräte-Tokens, /koppeln)
 Browser (Admin) ── LAN/Tailscale ──► karten-regionen :8095 (Regionen wählen → Regionspakete, Höhenkacheln)
```

## Dateien

| Datei | Inhalt |
|---|---|
| `docker-compose.yml` | der ganze Stack „karten“ (Portainer) |
| `tor/Caddyfile` | Tor: Token-Prüfung, Weiterleitungen, TomTom ohne Geräte-Token |
| `schluessel/schluessel.py` | Kopplungsdienst: Einmal-Codes, Geräte-Tokens, Sperren |
| `regionen/regionen_dienst.py` | Weboberfläche: Regionen wählen, je Region ein Paket bauen und frisch halten, Höhenkacheln laden |

Keine Adressen, Tokens oder Schlüssel in diesem Ordner: sie stehen als Umgebungsvariablen in Portainer
(`TOMTOM_KEY`, `REGIONEN_PASS`, `BASIS_URL`, übergangsweise `MATE_TOKEN`).

## Dienste und Ports

| Dienst | Container | Port | Daten (`/volume1/docker/karten/…`) |
|---|---|---|---|
| Tor (Zugang der App) | `karten-tor` | 8090 | `tor/Caddyfile` |
| Routing | `karten-valhalla` | 8002 | `valhalla/` (Europa-Kacheln, Konfiguration) |
| Suche | `karten-photon` | 2322 | `photon/` (Europa-Index, ~46 GB) |
| Kacheln | `karten-tiles` | 8581 | `tiles/` (Protomaps-Planet, ~140 GB) |
| Kopplung | `karten-schluessel` | – (nur im Netz) | `schluessel/daten/` (nur Prüfsummen) |
| Offline-Karten, Höhen | `karten-regionen` | 8095 | `regionen/daten/`, `pakete/`, `valhalla/elevation_data/` |

Ports 8002, 2322, 8581, 8095 nur für LAN und Tailscale freigeben (DSM-Firewall, TCP). Der Router leitet keinen davon ins
Internet weiter.

## Schnittstellen für die App (über das Tor)

| Zweck | Anfrage | Ziel |
|---|---|---|
| Kachel | `GET /kacheln/{z}/{x}/{y}.mvt` | go-pmtiles `/planet/…`, Protomaps v4, z0–15 |
| Route, Matrix, Höhen | `POST /route/route`, `/route/sources_to_targets`, `/route/height` … | Valhalla (jede Aktion unter `/route/<aktion>`) |
| Suche | `GET /suche/api?q=…`, `/suche/reverse?lat=&lon=` | Photon |
| Verkehr | `GET /verkehr/traffic/…` (Flow-/Incident-Kacheln, `incidentDetails`) | TomTom, nur Pfade unter `/traffic/` |
| Pakete | `GET /pakete/katalog.json`, `/pakete/<region>/…` | Dateien, mit Range (fortsetzbar) |
| Koppeln | `POST /koppeln {"code": "…"}` (ohne Token) | Kopplungsdienst |

Jede Anfrage außer `/koppeln` trägt `Authorization: Bearer <Geräte-Token>`; sonst 401 mit leerem Body.

## Einrichten

1. Netz und Ordner: `docker network create karten`, darunter `valhalla/`, `photon/`, `tiles/`, `tor/`, `schluessel/daten/`,
   `regionen/daten/`, `pakete/` anlegen; die Dateien dieses Ordners an ihre Stelle kopieren.
2. Stack „karten“ in Portainer aus `docker-compose.yml` anlegen, Umgebungsvariablen setzen.
3. Nach jeder Änderung am Caddyfile: `docker restart karten-tor`.

### Valhalla übernehmen statt neu bauen
Einen vorhandenen Valhalla-Ordner verschieben (`mv`, gleiches Volume) und die Umgebung **unverändert** übernehmen;
das Image auf den Digest des bisher laufenden Containers festnageln:
```sh
docker image inspect --format '{{index .RepoDigests 0}}' $(docker inspect --format '{{.Image}}' <alter-container>)
```
Im Log muss „Jumping directly to the tile loading“ stehen; erscheint ein PBF-Download oder Kachelbau: sofort stoppen.
Ein anderer Stack, der `valhalla` nutzt (IDmate), hängt sich mit `networks: [default, karten]` an; vor dem Deploy nach
alten Pfaden suchen – ein fehlender Bind-Mount bricht den Deploy ab, nachdem die alten Container schon entfernt sind.

### Kacheln
Roundtrip lädt und erneuert den Protomaps-Planeten selbst (`TILE_URL=/tiles/planet.pmtiles`, alle 60 Tage). Der Ordner
`tiles/` liegt im Stack „karten“ und wird in Roundtrip als `/data/tiles` eingebunden; `karten-tiles` liest ihn nur.
Nach einer Erneuerung `karten-tiles` neu starten (DSM-Aufgabenplaner, wöchentlich `docker restart karten-tiles`).

### Photon
Den Index nicht auf dem NAS entpacken, sondern auf dem Build-PC mit **demselben Image-Digest** laden:
```sh
docker run -d --name photon-bau -p 2322:2322 -e REGION=europe -e UPDATE_STRATEGY=SEQUENTIAL \
  -v ~/kartenserver/photon:/photon/data rtuszik/photon-docker@sha256:<digest>
```
Danach stoppen, Ordner per rsync nach `photon/` kopieren (WSL: IP-Adresse statt Windows-Name), Besitz setzen:
`chown -R 9011:9011 /volume1/docker/karten/photon`. Auf dem NAS läuft Photon mit `UPDATE_STRATEGY=DISABLED`.
Update alle 1–2 Monate genauso (auf dem Build-PC mit `FORCE_UPDATE=TRUE`). Die Log-Warnung `RssAnon line not found`
ist harmlos (älterer DSM-Kernel).

## Geräte koppeln

```sh
docker exec -it karten-schluessel python /app/schluessel.py koppeln "<Gerätename>"   # Code + QR, 10 min, einmal
docker exec karten-schluessel python /app/schluessel.py liste
docker exec karten-schluessel python /app/schluessel.py sperren "<Gerätename>"
```
QR-Inhalt: `{"v":1,"u":"<BASIS_URL>","c":"<code>"}`. Die App schickt `POST /koppeln` und erhält ihren eigenen Token.
Geräte-Tokens verfallen nicht; gespeichert werden nur SHA-256-Werte. Der Übergangs-Token `MATE_TOKEN` gilt, bis die
Zeile im Block `schluessel` gelöscht ist.

Zum Testen auf dem NAS selbst:
```sh
T=$(curl -s -X POST localhost:8090/koppeln -d '{"code":"<CODE>"}' | sed 's/.*"token": "\([0-9a-f]*\)".*/\1/'); echo ${#T}
```
Testgeräte danach sperren.

## Tailscale

Tablets für Mate bekommen den Tag `tag:mate` (am besten über einen einmaligen Auth-Key mit diesem Tag) und erreichen auf dem
NAS nur Port 8090:
```json
{
  "tagOwners": { "tag:mate": ["autogroup:admin"] },
  "hosts":     { "nas": "<Tailscale-IP des NAS>" },
  "grants": [
    {"src": ["autogroup:member"], "dst": ["*"],   "ip": ["*"]},
    {"src": ["tag:mate"],         "dst": ["nas"], "ip": ["tcp:8090"]}
  ],
  "tests": [ {"src": "tag:mate", "accept": ["<IP>:8090"], "deny": ["<IP>:8581", "<IP>:8002", "<IP>:2322"]} ]
}
```
Im Heim-WLAN über die LAN-Adresse gelten diese Regeln nicht; die App nutzt deshalb nur den `ts.net`-Namen.

## Offline-Karten (Regionspakete)

`http://<nas>:8095` (admin / `REGIONEN_PASS`). Oben die gewählten Regionen mit Größe, Datenstand, Rhythmus und Zustand
(„Neu bauen“, „Entfernen“), darunter alle Regionen von Geofabrik als Baum (Kontinent → Land → Bundesland) mit Suche zum
Hinzufügen. Eine gewählte Region enthält ihre Unterregionen: ist „Germany“ gewählt, sind die Bundesländer nicht zusätzlich
wählbar; wird eine Oberregion gewählt, gehen gewählte Unterregionen in ihr auf (ihre Pakete bleiben, bis die Oberregion gebaut
ist). Änderungen wirken sofort.

Jede Region wird ein eigenes Paket, mit `pmtiles extract --region` geschnitten: `pakete/<region>/karte.pmtiles` und
`grenze.geojson`, dazu `pakete/katalog.json` (Format 1) mit einem Eintrag je fertiger Region (`id`, `name`, `eltern`, `pfad` =
Namen der Oberregionen, `bbox`, `stand` = Datenstand, `dateien[]` mit `art`, `pfad`, `bytes`, `sha256`). Kacheln an Grenzen stehen
ganz in beiden Nachbarpaketen; Mate (ab 2.3.0) lädt und aktualisiert Regionen einzeln und zeichnet aus allen zugleich.

Rhythmus je Region: monatlich, alle 2–6 Monate, jährlich oder nie. Ist er abgelaufen und gibt es neuere Daten, wird die Region
neu geschnitten. Datenquelle (`QUELLE`, Standard `auto`): der neueste Tagesbau von Protomaps (`build.protomaps.com`, es werden
nur die Bereiche der Region geladen), wenn er neuer ist als der lokale Planet, sonst `tiles/planet.pmtiles`; `QUELLE=lokal`
schneidet nur aus dem lokalen Planeten. So bleibt eine Region monatlich frisch, auch wenn der Planet nur alle 60 Tage kommt.
Gebaut wird eine Region nach der anderen, geprüft alle 6 h und sofort nach jeder Änderung. Das frühere Gesamtpaket
(`pakete/gesamt`) bleibt im Katalog, bis alle gewählten Regionen einzeln gebaut sind, dann wird es gelöscht. Eine Auswahl aus
früheren Versionen (eine Liste mit gemeinsamem Intervall) wird beim Start übernommen.

## Verkehr (TomTom)

Das Tor setzt `key=$TOMTOM_KEY` selbst, entfernt `Authorization` und Cookies vor der Weitergabe und erlaubt nur `/traffic/*`.
TomTom wertet einen mitgeschickten `Authorization`-Kopf als Anmeldung und lehnt dann ab – deshalb das Entfernen.

## Höhen

Mate fragt die Höhen der Route über `/route/height` (Steigung für den Verbrauch) und Umwege zu Ladern über
`/route/sources_to_targets`. Ohne Höhenmodell plant Mate flach (Log „heights none …“), ohne Matrix mit grobem Umweg.
`/height` braucht keinen Neuaufbau der Kacheln, nur Höhenkacheln im Ordner `valhalla/elevation_data`:

- **Automatisch:** `karten-regionen` lädt für die gewählten Regionen die fehlenden 1°×1°-Kacheln (Tilezen „skadi“, `.hgt.gz`)
  nach `/hoehen` = `valhalla/elevation_data` und löscht selbst geladene, die keine gewählte Region mehr braucht. Von Hand
  geladene Kacheln bleiben unberührt. Nach neuen Kacheln zeigt die Oberfläche „Valhalla neu starten“.
- **Einmalig:** In `valhalla/valhalla.json` muss `"additional_data": {"elevation": "/custom_files/elevation_data/"}` stehen.
- **Von Hand** (ohne Regionsdienst): `docker exec karten-valhalla valhalla_build_elevation -b <minLon>,<minLat>,<maxLon>,<maxLat> -o /custom_files/elevation_data -p 4 -v`
- **Prüfen:** `/route/height` mit Hamburg (53.55, 10.0) und einem Alpenpunkt (47.42, 10.98) liefert z. B. `[25, 2685]` statt 0/`null`.

## Gesundheit

Jeder Dienst hat einen Healthcheck (`docker-compose.yml`); Portainer zeigt „healthy“/„unhealthy“.

| Dienst | Prüfung | Anlauf |
|---|---|---|
| valhalla | `curl -fs localhost:8002/status` | 10 min (Kacheln laden) |
| photon | echte Suche `/api?q=berlin` | 10 min (Index laden) |
| schluessel | `/pruefen` ohne Token antwortet 401 | 1 min |
| regionen | Oberfläche antwortet (401 ohne Passwort) | 2 min |
| tor | `/gesund` (ohne Token, antwortet „ok“) und `tiles:8080/planet/metadata` | 30 s |
| tiles | `/go-pmtiles show /data/planet.pmtiles` (Kopf der Planetdatei lesbar) | – |

`karten-tiles` hat keine Shell im Image: sein Healthcheck nutzt das Programm selbst und prüft die Datei; ob der Server antwortet, prüft das Tor mit.
Das Tor startet erst, wenn `karten-schluessel` gesund ist.

Docker startet einen „unhealthy“ Container **nicht** von selbst neu. Wer das will, nimmt einen Wächter wie
`willfarrell/autoheal` (braucht `/var/run/docker.sock`, also volle Docker-Rechte) und gibt den Diensten das Label
`autoheal=true` – oder schaut bei Störungen in Portainer nach.

## Betrieb

| Was | Wie oft | Wie |
|---|---|---|
| Planet | ~60 Tage | Roundtrip; `karten-tiles` wöchentlich neu starten |
| Regionspakete, Höhen | Rhythmus je Region | `karten-regionen`, prüft alle 6 h; nach neuen Höhenkacheln Valhalla neu starten |
| Photon | 1–2 Monate | Build-PC, kopieren |
| Valhalla | selten | Neubau auf dem Build-PC, Ergebnis kopieren; Digest im Stack anpassen |
| Geräte | bei Verlust | `sperren`, Gerät in Tailscale entfernen |
