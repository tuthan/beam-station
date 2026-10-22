# Notices

Station AR is MIT-licensed, like the rest of this repository (`../LICENSE`, "Copyright (c) 2026 Free My TV
contributors").

Code ported from the home screen in this repository (`app/src/io/github/tuthan/beamstation`, itself from Free My TV,
MIT), under the same notice:

| Station AR | Ported from | What |
| --- | --- | --- |
| `adb/AdbClient.kt` | `Adb.kt` | The ADB client for the box's own 127.0.0.1:5555: the RSA key, `CNXN`/`AUTH`, `shell:` streams; here also a long-lived stream for the helper |
| `glasses/RokidDisplay.kt` | `Rokid.kt` | The `rokid_display` binder client (mode, refresh rate), with the ADB fallback |

No code is copied from UxSpace, scrcpy or other projects whose licence wasn't checked: design ideas only (scrcpy's
way of building a shell context in `app_process`, for example, is described in its documentation and re-written here).
