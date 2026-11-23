# Hosting Google Maps: the private `ICarProjection` protocol

Google Maps (and a few other apps) do **not** draw their car screen through the public AndroidX Car
App Library. They expose a private projection service that speaks an undocumented GMS Binder
interface, `com.google.android.gms.car.ICarProjection`, and render into a `Surface` the host
provides. This is the protocol AARE must speak to show Maps' real navigation UI on the car screen.

This document describes the protocol and AARE's host implementation. **Status: Maps (26.46) accepts
the host, builds its car screen and draws it onto the car display** — checked on a device whose ROM
lets the host through (below). Maps needs the location permission on the phone, otherwise it shows
its own lock-out screen instead of the map. Taps and swipes work (checked on the zoom button and by
dragging the map), and a place handed over (`onNewIntent`, tx 3, a `geo:0,0?q=` view intent) opens
Maps' list of results. Sound and the car's position are not checked yet.

## The shape of it

- Maps exports `com.google.android.apps.gmm.car.GmmCarProjectionService` (and variants), with no
  manifest permission. A host binds it and talks `ICarProjection` to its returned Binder.
- The host provides a `Surface`; the app creates its own window on that surface and renders into it.
  No `MediaProjection`, screen capture or accessibility is involved — the host simply shows the
  surface (a `SurfaceView` on the car screen), and SurfaceFlinger composites the app's window.
- Startup sequence the host drives:
  1. `onSetup` (tx 1) — hand the app our `ICarProjectionCallback` and `ICar` Binders.
  2. `onProjectionStart` (tx 26) — a `DrawingSpec` carrying the surface and geometry.
  3. `onProjectionResume` (tx 4) — without this the app creates its window but never draws.
  4. Input and lifecycle flow through `onMotionEvent` (tx 7), `onKeyEvent` (tx 8),
     `onProjectionPause` (tx 5) and `onProjectionStop` (tx 9).
- Before drawing, the app reads back `CarInfo` (ICar tx 1), `CarUiInfo` (ICar tx 2) and, via an
  `ICarDisplayManager` (ICar tx 77) → `ICarDisplay` (tx 3), the display's `CarUiInfo`. The host
  returns minimal, internally consistent placeholders for all of these.

`CarInfo`, `CarUiInfo` and `DrawingSpec` are GMS **SafeParcelable** objects with no class in the
SDK, so the host writes their parcels by hand ([SafeParcel.kt](../app/src/main/java/net/prototux/aare/host/projected/SafeParcel.kt)):
a `20293` header, a back-patched size, then tagged fields (inline 4-byte values, or length-prefixed
strings/parcelables). The field layouts are in
[ProjectedProtocol.kt](../app/src/main/java/net/prototux/aare/host/projected/ProjectedProtocol.kt).

## The permission wall

The projection service runs a check on **every** transaction: the binding host must be Google-signed
or hold `android.permission.CAPTURE_VIDEO_OUTPUT`. That permission is `signature`, so an ordinary
app can't hold it, and the first `onSetup` is rejected with a `SecurityException` on a stock phone.

The protocol code here is identical whether or not that check passes. Mate has no way past it: the
hook module AARE had for rooted phones is not part of Mate, and a shell/Shizuku process can't bind
the service at all, because ActivityManager won't bind a service for a process it doesn't track as
an app.

## The code

| File | Role |
| --- | --- |
| `host/projected/SafeParcel.kt` | writes the GMS SafeParcelable wire format |
| `host/projected/ProjectedProtocol.kt` | descriptors, transaction codes, `DrawingSpec`/`CarInfo`/`CarUiInfo` builders |
| `host/projected/ProjectedHost.kt` | binds the service, serves the callback/`ICar`/display Binders, drives setup → start → resume, forwards input |
| `host/projected/ProjectedAppCatalog.kt` | the known projection apps installed on the phone |
| `host/projected/ProjectedAppScreen.kt` | a `SurfaceView` on the car screen whose surface the host draws into, with touch forwarding |

## Finding out what Maps is waiting for

Maps tells its whole state — without a picture, which matters because the map shows where the car is:

```
adb shell dumpsys activity service com.google.android.apps.maps/com.google.android.apps.gmm.car.GmmCarProjectionService
```

It lists the activity state, the window (insets, focus), the view tree with sizes, the map renderer
(frames drawn) and `ProjectedLockoutScreenController` (`currentLockouts`, e.g.
`LOCATION_PERMISSION_NOT_ACCEPTED`). Maps draws through a `Presentation` on a virtual display of its
own that feeds the host's surface. Two things this showed:

- `DrawingSpec` field 5 is the **insets** (left, top, right, bottom), not the bounds. With the full
  size in it, Maps laid its content out at 0 x 0 and only its background arrived.
- Debug builds log what a picture holds in numbers (`ProjectedHost: picture N: … colours`), so an
  empty picture can be told from a real one without looking at it.

## CarUiInfo: say there is a touchscreen

`CarUiInfo` (ICar tx 2 and ICarDisplay tx 3) was sent with every field 0. Maps then took the car for one without a
touchscreen: its home card with "Suchen" was missing, and everything that went back to it (closing the settings
dialog, ending guidance) changed the state but left the old cards frozen on screen. Field 2 = 1 ("has a
touchscreen") brings the search card back and makes back/close work (checked 14.11.2026 with pictures at the
desktop head unit's mock location). The other fields stay 0; `debug_ui_info` sets them for experiments.
Keyboard: a tap on Maps' search field arrives as **callback tx 16** (start input): the field's `EditorInfo`
(hint "Alle Ziele durchsuchen", action search) and a `com.google.android.gms.car.input.IProxyInputConnection`
binder; tx 17 ends it. Mate does not speak that connection: it shows its own German car keyboard
(`ProjectedInput`, typing in bursts while driving) and hands the text to Maps as `geo:0,0?q=…`, which opens Maps'
result list - checked: "louvre" → Louvre with route and Start.

## Full height, controls as an inset

Maps used to get the screen less the shell's controls (1920 x 966), which left a black strip under it. Now it gets
the whole screen and `DrawingSpec` field 5 carries the controls' height as the bottom inset: the map reaches the
bottom edge under the floating controls, Maps keeps its buttons, logo and speed sign above them (checked
14.11.2026 with pictures; taps still land right).

## Remaining work (needs a device)

- **Input.** Taps work once two things hold: the host has told the app it has the input focus
  (`onInputFocusChange`, tx 18 — until then every touch is answered "not handled"), and each
  `MotionEvent` has exactly the source `SOURCE_TOUCHSCREEN` (anything else is taken as a touchpad or
  rotary movement and ends touch mode). The app answers each touch with callback tx 32
  (`TouchEventCompleteData`). Swiping and two-finger zoom work too (checked with
  `debug_swipe` / `debug_pinch`: the picture changes, then stands still); keys are unchecked.
- **Car data.** Maps has two clients of Android Auto's car service (`GearheadCarStartupService`, which a ROM
  can redirect to the host - see `host/CarDataService.kt`). The first fills `ProjectedCarInfo` (fuel and plug
  types, ICarSensor tx 6/7). The second registers the sensors and starts only when the host binds Maps'
  **navigation provider** (`CarNavigationProviderService`, interface
  `com.google.android.apps.auto.sdk.nav.INavigationProvider`: tx 2 configuration bundle, tx 3 start with
  `mode` 1, tx 4 stop - `host/projected/NavigationProvider.kt`). It then reads `CarUiInfo` through the display
  manager (ICar tx 77), needs an `ICarMessage` (ICar tx 17), asks the supported sensors (ICarSensor tx 1), the
  latest value (tx 3) and registers a listener (tx 2; events arrive at `ICarSensorEventListener` tx 1 as a
  `CarSensorEvent`: type, time, float[], byte[]).
- **Charging stops.** Sensor **23** is the vehicle energy model, a protobuf message in the event's bytes.
  Maps learns nothing itself; with a model and a level it plans charging stops (checked: `dumpsys` shows
  `BatteryLevel(batteryLevelWh=…, batteryCapacityWh=…)`, the route shows "Ladestopps"). Layout as far as
  known (`host/EnergyModel.kt`): 1 battery (2.1 reserve Wh, 3.1 level Wh, 4.1 capacity Wh, 6 air density,
  7 outside temperature), 2 consumption in short (1.1 Wh/km), 3.7 power in W by speed in m/h, 4 a power curve
  by Wh (a real car's model has the motor's power there, not the charger's), 5.4 energy to get up to speed,
  7 heating/cooling in W by temperature, 8 charging time tables by charger power. **Section 8.1** is a table for each charger power of points (Wh still missing to full, seconds until full from
  there), the most missing first, none at zero; Google's server takes the time of a stop from a to b percent as
  the difference of two points (public description: egmp-aa-ev-feat, `docs/ENERGY-MODEL.md`). Mate computes the
  tables from the charging curve (`EnergyModel.chargeTimes`, 29 points, chargers of 11 kW to 400 kW) - checked:
  Maps shows "27 % → 72 %, 17 min" where the table gives 17.5. The earlier finding that the server takes only
  tables it knows was wrong: the Ford sample's tables are not sound (outliers, other AC rows) and break once a
  byte changes, and our first tables were not in the server's reading. A model from a file
  (`files/ev_model.bin`) is taken instead of the computed one, with level and capacity written into it.
- **Day and night.** Maps does not take day or night from the configuration in `onProjectionStart`, nor from
  the phone's own dark mode: it follows `onConfigurationChanged` (tx 6: display id, the changed
  `ActivityInfo.CONFIG_*` bits, drawing spec, configuration) and its `uiMode`. The host sends it right after
  the start and whenever the car (or the theme setting) switches. Check without a picture:
  `CarNightModeController` in the dumpsys above (`uiModeIsNight`, `useNightMode`). Note for every item in
  `onProjectionStart`: a "present" flag must be followed by the full object, else the app reads the next
  item as this one.
- **Coordinate mapping** for touch when the surface isn't 1:1 with the view, and **key** input.
- **Location.** Maps runs only as a service bound by the host, never on screen itself, so Android
  treats it as background and keeps its location requests "inactive" (`dumpsys location`). Binding with
  `BIND_INCLUDE_CAPABILITIES` hands it the host's foreground rights; then it receives fixes. The car's GPS
  reaches it through the existing "car GPS for the phone" setting (mock location app = Mate).
- **Voice.** Guidance starts with a `google.navigation:q=<place>` intent (tx 3; debug tool `debug_projected_intent`).
  Maps speaks with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`; with the Shizuku audio policy the host captures that
  usage in a mix of its own (a remote submix apart from the music) and sends it on the car's navigation channel.
  Checked in `dumpsys audio` / `dumpsys media.audio_policy`: the voice player plays into the navigation mix's
  remote submix, the music is ducked meanwhile. Whether it sounds right is for a person to hear.

## Waze is a different path

Waze uses the **public** Car App Library (`WazeCarAppService`), so it needs none of this. It is
rejected only by its host allowlist (`HostValidator`); AARE's hook module for rooted phones cleared
that check, Mate has no such module.
