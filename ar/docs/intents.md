# Opening Station AR from another app

Station Home (the Station's home screen, in this repository) opens AR through one intent. Any app may send it: it
grants nothing, and Station AR sets up everything it needs itself.

```text
action    io.github.tuthan.stationar.action.ENTER_AR
category  android.intent.category.DEFAULT
component io.github.tuthan.stationar/.EnterAr   (an exported alias of ArActivity; the action alone also resolves)
```

| Extra | Type | Meaning |
| --- | --- | --- |
| `preset` | String | `cinema`, `desk`, `desk-app` or `last` (the default): the layout AR opens in |
| `package` | String | An installed app to open in a screen as soon as AR is up (Phase 04) |
| `laptop` | Boolean | Wait for the laptop sender and put its first monitor in front (Phase 03) |

- Unknown extras and unknown values are ignored; AR opens in the last layout.
- **Back and Home always lead to Station Home**, the home screen. Nothing in this contract changes that: Station AR
  never claims HOME and never asks the home screen for anything.
- Leaving AR leaves the glasses in **2D** at the refresh rate they had; Station Home puts 2D back on every resume
  anyway (`Rokid.homeResumed`), so the glasses retrain once, not twice.

From a shell, for testing:

```bash
adb -s <serial> shell am start -a io.github.tuthan.stationar.action.ENTER_AR --es preset desk
```
