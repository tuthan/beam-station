# Station AR: notes for Claude

Head-tracked screens for the Rokid Station and its glasses (3DoF): screens stay put in the room as you turn your head.
They show the laptop's extra monitors (Station AR's own receiver), other Android apps (on virtual displays), or a test
card. It's a separate app from Station Home (the home screen at the top of this repository), with its own package
(`io.github.tuthan.stationar`), process and Gradle build in `ar/`. Station Home opens it through one intent
(`docs/intents.md`). The plan and its phases are in the vault: `docs-vault/station-ar/`.

## Building

- Temurin 17 from `mise.toml` (`export JAVA_HOME=$(mise where java)`), the Android SDK's `platforms;android-37.0`
  and `build-tools;36.0.0` (never downloaded by the build), `sdk.dir` in `ar/local.properties`.
- `./gradlew :core:check :app:assembleRelease` from `ar/`. Works offline once the Gradle cache holds the reviewed
  coordinates. `tools/install.sh -s <serial>` builds, signs (creating `stationar.keystore` once) and installs.
- **Dependencies:** a row in `docs/dependency-reviews.md` in the same commit as any catalog change, with the Socket
  result or Hung's disposition. Today the APK ships `kotlin-stdlib` and nothing else: no AndroidX, no Compose, no
  coroutines. Threads and `java.util.concurrent`.
- **Never commit** `stationar.keystore`, `local.properties`, pairing tokens or certificate keys.

## The device

- **Always `adb -s 192.168.199.98:5555`.** Other TVs share the adb server; `tools/*.sh` refuse to run without `-s`.
- **The person wears the glasses** while you work from the computer. Taking them off sleeps the Station and drops
  Wi-Fi and adb. Check `dumpsys power | grep mWakefulness` before a test, and don't disturb the box while someone is
  watching something (`dumpsys media_session`, the resumed activity).
- **Leave the Station's settings alone:** never change picture, sound, network or account settings. The glasses'
  mode and refresh rate change only on entering AR, and `GlassesSession` puts the refresh rate back on leaving (the
  glasses stay in 2D), even after a crash (the values are saved to a file first and restored on the next start).
- **Never claim HOME.** Never uninstall, disable or clear system apps. Station Home stays the home screen.

## The code

- `:core` is plain Kotlin on the JVM, with no Android imports (`verifyCoreBoundary`), and is where the logic lives:
  maths, head pose, layouts and focus, the stream framing, pairing, the helper's command codec, the settings store.
  Every change there comes with a JUnit test in `core/src/test`.
- `:app` is the Android side: `ArActivity` (a `GLSurfaceView`, GLES 2), sensors, the glasses session, the receiver,
  the decoders, virtual displays, the ADB client, the shell helper and the key filter.
- **No allocation** in `onDrawFrame`, `onSensorChanged` or `onFrameAvailable`: pre-allocated `FloatArray`s and
  `core.math`'s in-place operations; no Kotlin collection iteration, boxing or capturing lambdas on those paths.
  One draw per screen, one for the HUD, no post-processing; 60 Hz in AR.
- **Head tracking is `SensorManager` only** (the glasses' Game RotationVector and gyroscope). Never open the
  glasses' USB HID: Rokid's sensor HAL owns it.
- **The shell helper** (`helper.Main`, uid 2000, `app_process` from this APK over the app's own ADB key) runs only
  while AR has an app screen, on an abstract socket that only the app's uid may use. Never on TCP, never left behind.
- **Input on Android 11** (Phase 00, 2026-10-22): there is one focused display, and an untrusted virtual display
  gets the remote's keys only once it is on top, which `setFocusedTask` doesn't do but a touch on it does. Focus
  follows the gaze through the helper: a touch-down and cancel on the screen you look at (nothing is clicked). **No
  key is ever injected at a display that isn't the focused one** (Android drops it). The Station stamps its own
  buttons' keys for display 0 whatever has the focus (2026-10-22), so they always reach `ArActivity`: while an app has
  the remote, `AppScreens.onKey` runs them through `core.keys.KeyFilter` (held OK, held Back and Menu stay with AR) and
  the helper passes the rest on to the focused display. No accessibility service: one never received a key here, and
  Station AR leaves `enabled_accessibility_services` alone.
- **Laptop streams are always TLS 1.3** with a pinned certificate (`AndroidKeyStore`); pairing needs the PIN shown
  in AR. There is no plaintext mode.
- Ported code (`AdbClient`, `RokidDisplay`) keeps the MIT notice; see `docs/notices.md`.
