# Station AR

Screens that stay put in the room as you turn your head, for the Rokid Station and its glasses (3DoF). They show:

- **the test card** (how steady and how sharp the screens are);
- **your laptop's extra monitors**, streamed to Station AR's own receiver over TLS (Phase 03);
- **Android apps** (VLC, SmartTube, Jellyfin…) on virtual displays, which the remote drives when you look at
  them (Phase 04).

It's an app of its own (`io.github.tuthan.stationar`), next to Station Home, the home screen in this repository.
Station Home opens it from its drawer; it shows up in *All Apps* too. Leaving it (Back or Home) ends AR and puts the
glasses back as they were, in 2D.

## Use

| Key | In AR |
| --- | --- |
| OK (held) | Re-centre: what you look at becomes straight ahead |
| OK, Menu | The panel: layouts, apps, size, refresh rate, calibration, *Leave AR* |
| D-pad | In the panel: moves. Otherwise left and right nudge the screen you look at by 5°. Without glasses: turns the view |
| Back | Closes the panel, or leaves AR |
| Back (held) | The screen menu for the screen you look at: move it, close it |

**Apps in screens:** pick one under *Apps* in the panel (at most two at a time). When you look at an app's screen,
the remote drives the app: the D-pad, OK and Back are its, while Menu, held OK and held Back stay AR's. The first
time, the Station asks to allow USB debugging for Station AR (tick *Always allow*), since app screens need its shell
helper; *Allow app screens* on the setup screen does it ahead of time. Apps with protected video (Netflix and the like)
and YouTube's TV app play only full-screen, outside AR.

**Laptop monitors:** with AR open, run `sender/linux/station-ar-send pair <Station address>` once on the laptop
(check the key, type the PIN the glasses show), then `station-ar-send run`. Each monitor appears as a screen; see
`sender/README.md`.

The first time AR sees the glasses' sensors, it asks you to look straight ahead and then up, to learn which way
the glasses' sensor faces (*Calibrate* in the panel redoes it).

## Build and install

Needs Temurin 17 (`mise install`; `export JAVA_HOME=$(mise where java)`), the Android SDK's `platforms;android-37.0`
and `build-tools;36.0.0`, and `sdk.dir` in `ar/local.properties`.

```bash
cd ar
./gradlew :core:check :app:assembleRelease      # JVM tests, then the APK
tools/install.sh -s 192.168.199.98:5555         # builds, signs (creates stationar.keystore once), installs
tools/logcat.sh -s 192.168.199.98:5555          # the log
tools/probe.sh -s 192.168.199.98:5555           # read-only: glasses, sensors, GPU, decoders, ports
```

The only library in the APK is the Kotlin standard library; see `docs/dependency-reviews.md`. Opening Station AR
from another app: `docs/intents.md`. Code ported from Station Home: `docs/notices.md`. The plan, phases and
evidence are in the vault (`docs-vault/station-ar/`).
