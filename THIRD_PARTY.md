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
| MapLibre Native for Android | BSD-2-Clause |
| MapLibre Compose | BSD-3-Clause |
| MapLibre spatial-k (GeoJSON, units) | MIT |
| MapLibre Android GeoJSON | Apache-2.0 |
| AndroidX, Jetpack Compose, Material 3, Car App Library, Media3, Lifecycle | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Protocol Buffers (protobuf-javalite) | BSD-3-Clause |
| OkHttp | Apache-2.0 |
| ZXing Android Embedded | Apache-2.0 |
| Shizuku API and provider, RikkaApps | MIT |
| HiddenApiBypass, LSPosed | Apache-2.0 |
| sherpa-onnx (speech recognition and voices on the device), k2-fsa; includes ONNX Runtime | Apache-2.0 (ONNX Runtime: MIT) |
| libxposed API (only the optional Xposed module, not part of the app) | Apache-2.0 |

## Downloaded at run time / Zur Laufzeit geladen

Not part of the repository or the app package; fetched on the device when the user asks for them.

| What | Licence |
|---|---|
| Whisper speech recognition models (OpenAI, converted for sherpa-onnx) | MIT |
| Piper voice "Thorsten" (de_DE, medium), dataset by Thorsten Müller | CC0-1.0 (voice data), model MIT |
| Kokoro voices (Kokoro-82M) | Apache-2.0 |
| espeak-ng data (pronunciation rules) | GPL-3.0-or-later |
| Phone certificate from github.com/iscle/android-auto-certificates (only on "Download certificate") | third party, no licence given; the user is responsible for using it |

## Fonts / Schriften

| What | Licence |
|---|---|
| Noto Sans (map glyphs, `app/src/main/assets/navi/glyphs`), via protomaps/basemaps-assets | SIL Open Font License 1.1, see [LIZENZ.txt](app/src/main/assets/navi/glyphs/LIZENZ.txt) |

## Data / Daten

| What | Licence |
|---|---|
| Towns, service areas and fast chargers (`app/src/main/assets/navi/*.tsv`), derived from OpenStreetMap | © OpenStreetMap contributors, Open Database License 1.0 (ODbL); the tables are derived databases under the ODbL as well |
| Fast chargers, additionally from the Bundesnetzagentur's Ladesäulenregister | CC BY 4.0, data merged and shortened |
| Map tiles, routing data and place search of the own map server (`kartenserver/`), from Geofabrik extracts of OpenStreetMap | © OpenStreetMap contributors, ODbL 1.0 |
| Map style (`app/src/main/assets/navi/karte-v4.json`), made from Protomaps basemaps | BSD-3-Clause (style), data ODbL |
| Weather along the route, Open-Meteo (open-meteo.com) | CC BY 4.0 |
| Elevation (map server, terrain tiles "skadi" on AWS Open Data) | various public sources (SRTM and others), see https://github.com/tilezen/joerd/blob/master/docs/attribution.md |
| Traffic, TomTom (only with the user's own API key) | TomTom terms of use; no TomTom data is stored in the repository |

## Map server software / Kartenserver

| What | Licence |
|---|---|
| Valhalla (routing) | MIT |
| Other services in `kartenserver/` | as stated in their own files and images |

Android Auto is a trademark of Google LLC. Mate is not affiliated with or endorsed by Google, Volkswagen,
Samsung or the AARE project.
