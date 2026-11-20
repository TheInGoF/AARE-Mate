# Mate

Mate is a car interface for an Android tablet that sits in the car and projects onto the head unit
over Android Auto. It grew out of [AARE](https://github.com/prototux/AARE), an open-source
reimplementation of the phone side of Android Auto: AARE's protocol stack, car-app host and
transport stay, the car screen on top is Mate's own.

What Mate adds to AARE:

- **Dashboard:** the car's live data from an [IDmate](https://github.com/IDmate-app/IDMate) server
  (state of charge, range, charging, temperatures, trips), a map of the reachable range, the
  weather and the connection state.
- **Mate Navi:** a navigation app of its own (MapLibre, Ferrostar, Valhalla) with traffic, closures,
  speed limits, roundabout guidance, the head-up display, and route planning with
  charging stops for an electric car.
- **Speech on the device:** navigation prompts, messages read aloud and Mate's own system voice
  with Piper or Kokoro voices (sherpa-onnx); speech recognition for voice commands and dictated
  notes, all without a cloud service.
- **Music and audio:** Plex and local music, a car equaliser with measured presets per kind of
  app, one loudness target for all sources, ducking for prompts and calls.
- **Phone:** calls through the car (Bluetooth hands-free), messages, the car's Wi-Fi and internet.
- **Cinema:** phone and tablet apps on the car screen, only while the car is safely parked
  (gear in P, speed 0 from the car, no movement).
- **Diagnostics:** a diagnostic log that can be copied or shared as a file, and "mark a fault"
  with a dictated description.

> [!WARNING]
> Mate is built for one setup: a Samsung Galaxy Tab S6 Lite running a custom LineageOS build, an
> electric car with an Android Auto head unit, and a self-hosted map server. Other setups are not
> tested. Don't depend on it while driving until you have tried it in yours.

## Requirements

- An Android tablet or phone with **Android 8** (API 26) or newer. Mate is tuned for 4 GB of RAM.
- A head unit that accepts the phone certificate (see [Compatibility](#compatibility)).
- For Mate Navi: the map server in [`kartenserver/`](kartenserver/) (tiles, routing, search,
  traffic, offline region packs). The app reaches it with a device token after pairing.
- For the dashboard: an IDmate server for the car's data (optional; the rest works without it).
- Optional: [Shizuku](https://shizuku.rikka.app/) for car audio and some system settings.

## Installing

Download `mate-<version>.apk` from this repository's releases and install it, or
`adb install -r mate-<version>.apk`. Releases are signed with Mate's own key; builds you make
yourself are signed with your debug key, so switching between the two needs an uninstall first.

Mate's application id is `app.idmate.mate`; it installs next to AARE. Versions before the change
used AARE's id (`net.prototux.aare`): export the settings there (*Transfer settings*), import them in
the new app, then uninstall the old one.

The large Kokoro voices are not shipped and not downloaded by Mate: bring your own and import them
under *Voices*. Piper voices and the speech recognition models are downloaded on request.

## Using it

1. Open Mate on the tablet and go through setup: permissions, notification access, car audio,
   voices, and pairing with the map server.
2. Plug the tablet into the car's Android Auto USB port. For wireless, pair it with the car over
   Bluetooth first; after the first USB session Mate can switch to wireless when the cable is
   pulled.
3. On the car screen the bar sits on the driver's side, with the controls at the bottom left.
   Areas: dashboard, Mate Navi, music, messages, phone, other car apps, settings.

Things worth knowing:

- **Charging stops:** Mate Navi plans the chain of charging stops per route (dynamic programming
  over the chargers along the corridor), with consumption per stretch from slope, wind,
  temperature and rain, and a buffer that grows with the distance. On the way it re-plans when the
  car uses more or less than planned. Stops can be removed from the trip plan.
- **Weather and rain** only appear on the dashboard, never on Mate Navi's map; the navigation map
  has priority for performance.
- **Car apps:** apps built with the AndroidX Car App Library and media apps run as in AARE. Google
  Maps and Waze directions on the tablet are shown in the bar and sent to the head-up display.
- **Logs:** Mate writes a diagnostic log (memory, audio, connection, routing,
  charging plan). *Save issue log* stores a copy without positions, places, names, networks,
  addresses and tokens: attach that one to a public issue.

## Compatibility

Mate inherits AARE's protocol stack, so AARE's notes apply:

- **Phone certificate:** head units built on Google's receiver library only accept a phone
  certificate issued by Google's "Google Automotive Link" CA. Mate does not ship one; a
  certificate and key you are entitled to use can be imported under *Phone certificates*
  in the settings. Open-source head units accept the generated certificate.
  [docs/certificates.md](docs/certificates.md) explains the details.
- **Car apps that only trust Google:** newer Car App Library apps need the host's signing
  certificate in their allow-list (`HostValidator`), see AARE's README.
- **Coexisting with Google's Android Auto:** both answer the same USB accessory; choose Mate and
  "always", or disable Android Auto.
- **VPN:** the VPN's address ranges must not overlap the head unit's Wi-Fi network.

## Building

You need a JDK 17-21 and the Android SDK (platform `android-37`).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
./build.sh                                    # debug APK: app/build/outputs/apk/debug/app-debug.apk
./build.sh :aap:test                          # protocol tests
./build.sh :app:testDebugUnitTest             # app tests (charging planner, routing, speech, ...)
./build.sh :app:testDebugUnitTest -Ppreview   # screenshot tests of the car screen
./build.sh :app:minifyReleaseWithR8           # check the R8 rules (JNI callbacks of the voices)
```

`build.sh` finds a suitable JDK and runs Gradle. On a machine without swap, set
`AARE_BUILD_MEMORY=5G` to run the build in a memory-capped systemd scope. If Maven Central answers
with HTTP 429, run the build again.

CI (`ci.yml`) and the release (`mate-release.yml`) run only when started by hand. The release
workflow takes a `version`, tags it, signs the APK with the encrypted key in `signing/` and
publishes `mate-<version>.apk`.

## Layout

| Folder | What |
|---|---|
| `aap/` | the Android Auto protocol, pure Kotlin/JVM (from AARE): framing, TLS, session, channels, wireless bootstrap, tests |
| `app/.../car/`, `transport/`, `host/` | projection session, USB/wireless transport, Car App Library host (from AARE) |
| `app/.../ui/car/` | Mate's car screen: shell and bar (`IdmateShell.kt`), dashboard area, phone, cinema, settings |
| `app/.../ui/car/navi/` | Mate Navi: map and panels (`MateNaviScreen.kt`), guidance (`NaviSession.kt`, `NaviKeep.kt`), traffic, speed limits, head-up display, charging planner (`Charge*.kt`), region packs |
| `app/.../dash/` | the IDmate dashboard and its link to the server |
| `app/.../speech/` | text to speech and speech recognition on the device, voices, pronunciation, dictation |
| `app/.../audio/` | car audio, equaliser and measurement, loudness, fades |
| `app/.../music/` | Plex and offline music |
| `app/.../ui/MainActivity.kt` | the tablet app: setup and settings |
| `kartenserver/` | the map server stack (Docker): gate with device tokens, tiles, Valhalla, Photon, traffic, region packs |
| `headunit/` | AARE's desktop head unit for tests without a car (`./headunit.sh`) |
| `xposed/`, `tools/magisk/` | optional modules for rooted devices (from AARE) |
| `docs/` | certificates, map projection, decisions, research notes, [upstream.md](docs/upstream.md) |

Every visible and spoken text lives in resources (`values/` English, `values-de/` German), so a
new language only needs a new `values-xx` folder and, if wanted, a voice.

## Mate and AARE

Mate is based on AARE 1.4.0+58 (`5d932ae`). [docs/upstream.md](docs/upstream.md) lists which later
AARE changes are taken over and which are still candidates (`aareBase` and `aareSeen` in
`gradle.properties`). Thanks to AARE's author and contributors, listed in
[AARE's README](https://github.com/prototux/AARE#thanks).

## License

GPL-3.0-or-later (see [LICENSE](LICENSE)), like AARE. The protobuf definitions come from aasdk
(GPL-3.0-or-later, see [aap/src/main/proto/NOTICE.md](aap/src/main/proto/NOTICE.md)).

Not affiliated with or endorsed by Google, Samsung or the AARE project. Android Auto is a
trademark of Google LLC.
