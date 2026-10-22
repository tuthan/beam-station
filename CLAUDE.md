# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java and Kotlin (the Java is being converted), built without Gradle by `build.sh`. One tree builds two flavours: **Beam Home** for XGIMI projectors (`device=xgimi`, the default) and **Station Home** for the Rokid Station (`device=rokid`). The name is in all of the UI text (`Config.APP_NAME` in the Java sources, `@string/app_name` in the manifest, the art in `app/devices/<device>`); the package, the log tag and the signing key stay Free My TV's.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, Kotlin 2.4.20 (`kotlin-compiler-2.4.20.zip` from JetBrains' GitHub release, its SHA-256
   checked, unzipped so that `~/.local/share/kotlin/2.4.20/bin/kotlinc` exists, or `KOTLIN_HOME` set), the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   A v4 read access token (starts with `eyJ`) works too: `Recs` sends it as `Authorization: Bearer`, which keeps the key out of URLs.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `BeamHome.apk` (XGIMI), or `DEVICE=rokid ./build.sh` → `StationHome.apk` (Rokid Station;
   `device=rokid` in `config.properties` does the same). Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r BeamHome.apk` (or `StationHome.apk`).
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, the stock home
   screen is turned off (never uninstalled; on the Rokid Station nothing is turned off), and *Original* brings it all
   back.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## The two devices

Read first, change after. Read-only commands that tell you what the device has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

After a change to the switch: build, install, have them switch on the device, read `adb logcat -s FreeMyTV`, then
have them switch back to *Original* once and confirm everything returned.

XGIMI projectors (China ROM, GMUI): their chooser (`xgimiframeworkui`) opens XGIMI Home whenever it is on, whatever
the default home, so Free My TV mode turns off `com.xgimi.home` and nothing else (plus `HomeMode.PRIVACY`, only with
`disable_telemetry=true`). Every XGIMI app runs as the shared system UID that also saved the Wi-Fi networks; Android
keeps those (their creator, `android.uid.system`, matches no package), but don't turn off other XGIMI packages. *TV Settings* opens XGIMI's panel (its `SettingService`).
Beam Home adds English screens for what XGIMI's Chinese ones do: Wi-Fi, Bluetooth, Picture (`Picture`, the Play 6) and
Time Zone (`Zones`), and in the drawer focus, keystone, speaker and eco mode (`Xgimi`), with the Play 6's battery on the
home screen.

Rokid Station (the Android 11 box for Rokid's AR glasses, Amlogic): its ROM refuses `pm disable-user`, `pm enable`,
`pm hide` and `pm uninstall` for every system app (`Failure [not support for 0]`), so Free My TV mode turns nothing
off there and only takes the home screen; Rokid's launcher stays on (HOME stays ours through a glasses replug and a
reboot). *TV Settings* opens Rokid's Settings. Taking the glasses off puts the box to sleep and drops Wi-Fi and ADB:
keep them on (or the proximity sensor covered) while you work from the computer. The glasses' 2D/3D, refresh rate,
scene and speaker mode are Rokid's `rokid_display` service (`adb shell dumpsys rokid_display`); `Rokid` calls it over
the binder (the Station's SELinux is permissive) or else the TV's ADB, and the settings' Glasses page and the drawer's
2D/3D button use it. A new mode or refresh rate retrains HDMI for about 3 s: read back only once the glasses return.
The Station runs on a battery: `Power` shows it on the home screen and keeps the screensaver and sleep timeouts
(`settings put` over the TV's ADB) and a sleep timer, since Rokid's Settings has neither. Wi-Fi and Bluetooth have their own
screens (`Wifi`, `Bluetooth`), since Rokid's are buried in its Settings.


**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Two devices, one tree. `build.sh` takes the device from `DEVICE` in the environment, else `device=` in
  `config.properties`, else `xgimi` (anything else stops it), writes `Config.DEVICE` and `Config.APP_NAME`, links
  `app/devices/<device>/res` over `app/res` and adds `app/devices/<device>/assets` to `app/assets`, and names the APK
  `BeamHome.apk` or `StationHome.apk`. A device's folder holds its icon and banner (`res/drawable-nodpi`), its name
  (`res/values/strings.xml`), its settings mark
  (`assets/symbols/mark.png`) and its day and night backgrounds (`assets/backgrounds`); every other symbol is shared
  in `app/assets/symbols`. `python3 tools/make_art.py <device>` and `python3 tools/make_backgrounds.py <device>` draw
  them (each has a `DEVICES` table: name, gradient, mark, background colors).
- Device code runs only in its own build and on its own device: `HomeMode.XGIMI` and `HomeMode.ROKID` check both
  `Config.DEVICE` and the brand, and everything device-only goes through them (`Xgimi`, `Picture`, `Zones`; `Rokid`,
  `Power`; `Wifi` and `Bluetooth` for both). On any other TV either build is plain Free My TV. The sky (`Shaders`)
  follows `Config.DEVICE`: warm for the projector's dark room, near black and cool for the glasses.
- Never write the app's name in the code: join `Config.APP_NAME` as a constant
  (`"Measuring " + Config.APP_NAME + "…"`), and use the same expression as the case label in `Spanish.java`,
  `German.java` and `Russian.java`.
- Kotlin (JVM target 1.8) and Java 8 language level, minSdk 28 (Android 9), targetSdk 33. No Gradle; the only library is
  the Kotlin standard library, shrunk by R8 (`-dontobfuscate -dontoptimize`, the app kept whole). New code is Kotlin.
  While Java remains, a Kotlin function that Java calls takes nullable parameters (`String?`) wherever Java could pass
  null, and Java-facing statics are `@JvmStatic` in an `object`.
  Android 9 has no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 9 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step.
  To check for newer APIs, compile against `platforms/android-28/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded) and `Recording.Node` should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 9 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (some 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes. *Original* also takes an older
  build's remote buttons service (`RemoteKeyService`) out of `enabled_accessibility_services`, if it is still listed.
- After every `appops set` over ADB, run `appops write-settings` as its own shell call: app-ops reach the disk lazily,
  and a restart soon after the `set` drops it.
- On-screen text: `Ui.tr("pt", "en")`, with the Spanish, German and Russian in `Spanish.java`, `German.java` and
  `Russian.java` (keyed by the English); text built at runtime uses `Ui.tr(pt, en, es)`, and its German and Russian
  come from those files too (patterns for the parts built at runtime). Add them all. Comments in English, short, only where the why
  isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf) wins over those first values.
- After any change, `./build.sh` and `DEVICE=rokid ./build.sh` must end with `Built BeamHome.apk` and
  `Built StationHome.apk`.

## Always

- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`).
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
