# Third-party software and data / Fremde Software und Daten

Mate is a fork of **AARE** (https://github.com/prototux/AARE) and, like AARE, licensed under the
**GNU General Public License v3.0 or later** (see [LICENSE](LICENSE)). Everything below keeps its own
licence; the GPL-3.0 covers Mate as a whole.

Mate ist ein Fork von **AARE** und steht wie AARE unter der **GPL-3.0-or-later**. Die folgenden Teile
behalten ihre eigene Lizenz.

## Code taken over / Übernommener Code

| What | Source | Licence |
|---|---|---|
| AARE (the base of Mate: Android Auto protocol, phone side, settings) | github.com/prototux/AARE | GPL-3.0-or-later |
| Android Auto protocol definitions (`aap/src/main/proto`) | github.com/opencardev/aasdk, © f1x.studio (Michal Szwaj) and the OpenCarDev community | GPL-3.0-or-later, see [NOTICE](aap/src/main/proto/NOTICE.md) |

## Libraries / Bibliotheken

| Library | Licence |
|---|---|
| Ferrostar (navigation core, MapLibre UI, formatters), Stadia Maps | BSD-3-Clause |
| MapLibre Native for Android, © MapLibre contributors, MapTiler.com, Mapbox | BSD-2-Clause |
| MapLibre Compose | BSD-3-Clause |
| MapLibre spatial-k (GeoJSON, units) | MIT |
| MapLibre Android GeoJSON | Apache-2.0 |
| AndroidX, Jetpack Compose, Material 3, Car App Library, Media3, Lifecycle | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Protocol Buffers (protobuf-javalite) | BSD-3-Clause |
| OkHttp | Apache-2.0 |
| ZXing Android Embedded | Apache-2.0 |
| Shizuku API and provider, RikkaApps | MIT |
| libadb-android, Muntashir Al-Islam (Mate starts Shizuku over wireless debugging) | GPL-3.0-or-later or Apache-2.0 (used under GPL-3.0-or-later) |
| spake2-java (pairing for wireless debugging), Muntashir Al-Islam | LGPL-3.0 |
| Conscrypt, Google | Apache-2.0 |
| Bouncy Castle (bcprov) | Bouncy Castle Licence (MIT) |
| HiddenApiBypass, LSPosed | Apache-2.0 |
| sherpa-onnx (speech recognition and voices on the device), k2-fsa; includes ONNX Runtime | Apache-2.0 (ONNX Runtime: MIT) |

## Downloaded at run time / Zur Laufzeit geladen

Not part of the repository or the app package; fetched on the device when the user asks for them.

| What | Licence |
|---|---|
| Whisper speech recognition models (OpenAI, converted for sherpa-onnx by k2-fsa) | MIT, © 2022 OpenAI |
| Piper voice "Thorsten" (de_DE, medium), dataset by Thorsten Müller | CC0-1.0 (dataset and voice card); the piper-voices repository is marked MIT |
| Piper voices "Kerstin", "Karlsson" (de_DE) | CC0-1.0 |
| Piper voices "Eva", "Ramona" (de_DE), M-AILABS speech dataset | M-AILABS licence (BSD-style) |
| Piper voices "Dii", "Miro" (de_DE, high), OpenVoiceOS | CC BY-NC-SA 4.0 - private, non-commercial use only |
| Piper voices "Ryan" (en_US, medium and high), "HFC female" (en_US, medium) | CC BY-NC-SA 4.0 - private, non-commercial use only |
| Piper voice "Lessac" (en_US, medium and high), Blizzard 2013 dataset | Blizzard 2013 licence - non-commercial |
| Piper voices "Cori" (en_GB, high), "LJSpeech" (en_US, high) | public domain |
| Piper voice "Alba" (en_GB, medium) | CC BY 4.0 |
| Piper voice "Northern English male" (en_GB, medium), OpenSLR 83 | CC BY-SA 4.0 |
| Kokoro voices (Kokoro-82M) | Apache-2.0 |
| espeak-ng data (pronunciation rules) | GPL-3.0 |
| Phone certificate from github.com/iscle/android-auto-certificates (only on "Download certificate") | no licence given by that repository; the user is responsible for using it |

## Fonts / Schriften

| What | Licence |
|---|---|
| Noto Sans (map glyphs, `app/src/main/assets/navi/glyphs`), via protomaps/basemaps-assets | SIL Open Font License 1.1, see [LIZENZ.txt](app/src/main/assets/navi/glyphs/LIZENZ.txt) |

## Data / Daten

| What | Licence |
|---|---|
| Towns, service areas and fast chargers (`app/src/main/assets/navi/*.tsv`), derived from OpenStreetMap | © OpenStreetMap contributors, Open Database License 1.0 (ODbL); the tables are derived databases under the ODbL as well |
| Map tiles, routing data and place search of the own map server (`kartenserver/`), from Geofabrik extracts of OpenStreetMap | © OpenStreetMap contributors, ODbL 1.0 |
| Map style (`app/src/main/assets/navi/karte-v4.json`), made from Protomaps basemaps | map design CC0-1.0, Protomaps code BSD-3-Clause; data ODbL |
| Weather along the route, Open-Meteo (open-meteo.com) | data CC BY 4.0; the free API is for non-commercial use only |
| Elevation (map server, terrain tiles "skadi" on AWS Open Data) | public sources, each with its credit: 3DEP, SRTM and GMTED2010 data courtesy of the U.S. Geological Survey; ETOPO1 U.S. National Oceanic and Atmospheric Administration; produced using Copernicus data and information funded by the European Union - EU-DEM layers; full list https://github.com/tilezen/joerd/blob/master/docs/attribution.md |
| Traffic, TomTom (only with the user's own API key) | TomTom terms of use; no TomTom data is stored in the repository |

## Map server software / Kartenserver

| What | Licence |
|---|---|
| Valhalla (routing), © Valhalla contributors, Mapillary AB, Mapzen | MIT |
| Other services in `kartenserver/` | as stated in their own files and images |

Android Auto is a trademark of Google LLC. Mate is not affiliated with or endorsed by Google, Volkswagen,
Samsung or the AARE project.
