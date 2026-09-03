# Windhover

Near-real-time location tracking for Android that behaves like Life360 and reports to
whatever you run: Home Assistant, your own server, or nothing at all.

A windhover is a kestrel. It hangs motionless in the wind, then stoops the moment something
moves. That is the whole design: almost free while you are parked, 1 Hz GPS with speed and
heading the moment you drive off.

## Why

Open-source trackers such as OwnTracks, Traccar Client and GPSLogger poll on a fixed interval.
Either the interval is long and you look frozen on the map, or it is short and it eats the
battery. Commercial apps solve this with motion-aware sampling but keep your data in their cloud.

Windhover uses the same on-device building blocks the commercial apps use, adaptive sampling
driven by activity recognition and motion sensors, and sends the result only where you tell it.

## How it works

| Piece | What it does |
|---|---|
| Foreground service | Sticky, restarted after reboot, app update, or being killed. Always shows a notification. |
| Adaptive profiles | Driving 1 s · running or cycling 3 s · walking 5 s · still 60 s on balanced power · 1 s while the app is open. |
| Activity recognition | Still, walking, running, cycling and in-vehicle transitions select the profile. |
| Still watcher | While parked: a 100 m geofence exit or the significant-motion sensor wakes the tracker. |
| Fix filter | Drops poor-accuracy, stale, mock and physically impossible fixes. |
| Speed estimator | Chipset Doppler speed when its accuracy is good, otherwise distance over time gated by GPS noise, smoothed. |
| Trip detection | A trip starts on "in vehicle" or 30 s above 25 km/h and ends after 5 min below 5 km/h. Distance, duration, max and average speed. |
| Storage | Room database of samples and trips on the phone. Retention is configurable. |
| Upload | Batched JSON to your endpoint, or OwnTracks messages to Home Assistant, live while moving and every 15 min otherwise. |
| UI | Status, a tile-free trail map, history of samples and trips, settings, and guided permission setup. |

## Privacy

- Nothing leaves the phone unless you enter a server URL. There are no analytics, no accounts,
  no third-party SDKs beyond Google Play Services.
- Tracking is always visible: a persistent notification with a Stop action, and an on/off
  switch on the first screen. There is no hidden mode, and there will not be one.
- Location, activity recognition and geofencing come from Google Play Services. On a stock
  phone that adds nothing Google did not already have. On GrapheneOS with sandboxed Play, turn on
  "Reroute location requests to OS", deny Play Services location and network, and grant it
  physical activity: the only Google code left in the loop is the on-device motion classifier.
  Geofencing will fail in that setup and Windhover falls back to the motion sensor and 60 s polls.
- A Play-Services-free flavour is on the roadmap. It would lose activity classification and
  indoor positioning but make an F-Droid build possible.

## Requirements

- Android 8.0 or newer. Tested on Android 16.
- Google Play Services (stock phones, or sandboxed Play on GrapheneOS).
- For remote reporting, a phone that can reach your server from the mobile network.

## Install

Download the APK from the Releases page, or build it yourself:

```sh
export JAVA_HOME="/Applications/Android Studio Panda 2026.app/Contents/jbr/Contents/Home"  # or any JDK 17+
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project uses AGP 9.4, compileSdk 37 and the Compose BOM 2026.08. Android Studio Panda or
newer opens it directly.

## First run

The setup screen walks through the permissions in the order Android requires:

1. Precise location.
2. Background location. Choose **Allow all the time** on the system page.
3. Physical activity, for the motion-based profile switching.
4. Notifications (Android 13+).
5. Battery optimisation exemption. Without it, some manufacturers pause the service after a while.

Then tap **Start tracking** on the Status tab. The map tab shows your trail on a plain white
canvas: no tiles, no API key, pinch to zoom, "Follow me" and "Fit trail".

## Home Assistant

Windhover speaks the OwnTracks wire format, so Home Assistant's built-in
[OwnTracks integration](https://www.home-assistant.io/integrations/owntracks/) tracks it with no
extra software.

1. In Home Assistant: **Settings → Devices & services → Add integration → OwnTracks**. Copy the
   webhook URL it shows.
2. In Windhover: **Settings → Upload**. Turn on *Send samples to a server* and *Home Assistant
   (OwnTracks format)*, paste the URL, set *Person* and *Device*, tap **Save**.
3. Home Assistant creates `device_tracker.<person>_<device>` with `gps_accuracy`,
   `battery_level`, `velocity` (km/h), `course` and `tid` attributes. Put it on a Map card. It
   moves every 5 s while you drive and once a minute while parked.

Offline, fixes are stored locally. On reconnect only the newest position is sent, so the marker
does not replay history. If a reverse proxy in front of Home Assistant wants a bearer token, set it
in the app; the webhook itself needs none. Plain HTTP works for LAN installs.

To show speed beside the map, add an Entity card for the tracker's `velocity` attribute, or a
template sensor:

```yaml
template:
  - sensor:
      - name: "Phone speed"
        unit_of_measurement: "km/h"
        state: "{{ state_attr('device_tracker.michael_pixel', 'velocity') | int(0) }}"
```

## Your own backend

In batch mode Windhover POSTs every unsent sample, up to 200 per request, as JSON with
`Authorization: Bearer <token>` if a token is set. Any 2xx response marks the batch as sent.

```json
{
  "deviceId": "uuid",
  "sentAtMs": 1756900000000,
  "samples": [
    { "id": 1, "timeMs": 1756899990000, "lat": -33.86, "lon": 151.21, "altitudeM": 12.0,
      "accuracyM": 8.0, "speedMps": 13.9, "speedSource": "GNSS", "bearingDeg": 92.0,
      "motion": "DRIVING", "provider": "fused", "batteryPct": 81, "tripId": 3 }
  ]
}
```

`speedSource` is `GNSS`, `DERIVED` or `NONE`. `motion` is one of `STILL`, `MOVING`, `WALKING`,
`RUNNING`, `CYCLING`, `DRIVING`, `UNKNOWN`.

## Battery

Parked, the phone is on a 60 s balanced-power request plus a geofence, and the fused provider is
already running for other apps, so the marginal cost is close to zero. Driving runs GPS at 1 Hz,
comparable to navigation with the screen off, for the length of the trip. A day with a lot of
walking costs the most, since walking runs 5 s fixes throughout. Android's per-app battery screen
gives you the real number after a couple of days.

## Emulator testing

The Android emulator's Extended controls can play a GPX or KML route, or from a shell:

```sh
adb emu geo fix 151.2100 -33.8600   # lon lat
```

The emulator does not fire activity transitions and reports no Doppler speed, so motion state
comes from the speed override and speed shows as derived.

## Project layout

```
app/src/main/java/com/pixeltek/windhover/
  service/    TrackingService, notifications, boot receiver, WorkManager watchdog and uploader
  location/   fused provider wrapper, profiles, fix filter, speed estimator, still watcher
  activity/   activity recognition transitions
  trip/       trip detector (pure Kotlin, unit tested)
  data/       Room entities and DAOs, DataStore settings, repository
  sync/       batch uploader and OwnTracks message builder
  ui/         Compose screens and view model
  util/       geodesy helpers and permission checks
```

Unit tests cover the fix filter, speed estimator, trip detector, geodesy and the OwnTracks
message format: `./gradlew testDebugUnitTest`.

## Roadmap

- MQTT publishing for OwnTracks and Traccar servers.
- Release signing and a Releases workflow.
- A Play-Services-free build flavour for F-Droid.
- Trip export as GPX.

## Status

Early. It runs on the author's phone and in the emulator. Activity recognition and trip detection
have not yet had a long real-world shakedown, and the app has not been tested on Samsung or Xiaomi
firmware, which are known to kill foreground services aggressively. Bug reports with the phone
model and Android version are welcome.

## License

To be decided before the first public release.
