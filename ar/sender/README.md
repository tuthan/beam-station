# station-ar-send

Gives your laptop one or two extra monitors that show up as screens in Station AR. You work on them with the
laptop's own keyboard and trackpad. The picture is encrypted on the LAN (TLS 1.3, the Station's key pinned), and only
a paired laptop can connect. Protocol: `../docs/protocol.md`.

Linux with Hyprland, for now. Python 3 standard library; the picture is encoded by your GPU through VA-API.

## Needs

| Tool | For | Arch |
| --- | --- | --- |
| `ffmpeg` with VA-API | every source | `ffmpeg` |
| `wf-recorder` | real monitors (`--source wf-recorder`, the default) | `wf-recorder` |
| `hyprctl` | making the headless monitors | `hyprland` |

The script installs nothing; it tells you what's missing. `--source test` (a test pattern) and `--source file` need
only ffmpeg.

## Use

Open AR on the Station first: the receiver is only there while AR is open.

```bash
sender/linux/station-ar-send discover              # Stations with AR open: address, key, paired or not
sender/linux/station-ar-send pair 192.168.199.98   # once: check the key, type the PIN shown in the glasses
sender/linux/station-ar-send run                   # one 1920x1080@60 monitor
sender/linux/station-ar-send run --monitors 2      # two, side by side in AR
sender/linux/station-ar-send run --wide            # one 3840x1080 monitor, shown as two screens
sender/linux/station-ar-send run --workspace 2     # a new monitor, with workspace 2's windows moved onto it
sender/linux/station-ar-send run --mirror          # no new monitor: the one you're on, as it is
sender/linux/station-ar-send run --mirror DP-1     # a named one (hyprctl monitors lists them)
sender/linux/station-ar-send run --source test     # a test pattern with the laptop's clock and a frame counter
```

**Extend or mirror.** By default you get a new monitor that only the glasses show, sharp at 1:1 (one laptop pixel per
glasses pixel in *Desk*). `--workspace N` moves workspace N's windows (Hyprland's number, `hyprctl workspaces`) onto
the workspace it shows while streaming, and those windows back to N on exit. Windows, not the workspace: Omarchy
renumbers a workspace moved to another monitor into that monitor's block (1 becomes 31), and a monitor tool such as
hyprmoncfg may add workspace rules of its own. `--mirror` shows a monitor you already have instead, scaled on the GPU to
at most 1080 lines (`--size`'s height) and keeping its shape: a 3440x1440 ultrawide streams as 2576x1080, and the
screen in AR takes that 21:9 shape. The glasses have 1920 pixels across, so a big monitor's text comes out small:
mirroring suits a laptop panel, a film or a talk better than a day of work.

`discover` asks the mDNS group and reads the answers on UDP 5353, which ufw's default rules let in. If it still
finds nothing (another firewall, or a network that drops multicast), give the Station's address instead: every
command takes one.

Options for `run`: `--size 2560x1440`, `--fps 60`, `--codec hevc|avc` (AVC can't do `--wide` on the Station),
`--bitrate 15M`, `--device /dev/dri/renderD128`, `-v` (bitrate every 10 s).

`run` makes a Hyprland headless output per monitor (`STATIONAR-0`, `STATIONAR-1`, or `STATIONAR-WIDE`), to the right
of your monitors at scale 1, and removes it when it exits, Ctrl-C included. Move windows onto it as onto any monitor.
It waits for Hyprland to finish placing the output before recording it, and records it again whenever it moves or
changes size (a display plugged in, Omarchy renumbering workspaces): wf-recorder keeps an output's geometry from its
start, and a stale one slides the picture down the screen in AR. If the network drops, it reconnects (every 0.5 to
3 s) and the screen in AR comes back where it was; stopped for good, the screen shows its card after 4 s.

Hyprland 0.56 with a Lua config refuses `hyprctl keyword` and `dispatch` as they were, with exit status 0 all the
same; the script reads the answer and uses `hyprctl eval` there.

Pairings live in `~/.config/station-ar/hosts` (readable only by you): each Station's key fingerprint, address,
port and token. `forget <address>` or `forget all` removes them here.

## Encoding

`hevc_vaapi`, variable bitrate capped at `--bitrate` (15 Mbit/s by default), no B-frames, `async_depth=1`, a key
frame every 1800 frames (30 s at 60 fps; a desktop has frames only when it changes, so longer), an access-unit
delimiter before every frame. Not constant bitrate: it pads each frame to its share of the bitrate, ~28 KB at
15M/60 even for a picture that didn't change. The encoder restarts on each connection, so the Station gets a key frame at
once; TCP loses nothing, so more key frames would only be hitches on a slow link (each is ~10x a frame).

The encoder writes FLV, which says where each frame ends, and the script makes the Annex-B the Station reads: each
frame goes out whole as soon as the encoder has it, followed at once by the next frame's delimiter, since the Station
cuts a frame at the delimiter after it. wf-recorder records only when the screen changes (Hyprland draws nothing new
on a still output, whatever `-r` or `-D` say), so after 1 s without a frame the script sends a user-data SEI the
decoder ignores; the Station ends a stream silent for 5 s.

Why: three stages each held the newest frame until the next one came, which on a desktop means until the next change
(the last keystroke shown only when the cursor blinked): the encoder at its default `async_depth=2`, the ffmpeg that
used to insert the delimiters (it parses raw HEVC, so a frame ends where the next begins), and the Station's cutter.
And a still desktop went silent, so the Station dropped the stream every 10–40 s and it took seconds to come back.

- `--source test` and `--source file`: one ffmpeg per screen, `-re` (real time).
- `--source wf-recorder`: `wf-recorder -o STATIONAR-n -F settb=1/<fps>,scale_vaapi=format=nv12:out_range=full
  -c hevc_vaapi -m flv`. wf-recorder's VA-API filter tags the picture full range, which the Station shows right;
  `--mirror` sizes it (`scale_vaapi=w=W:h=H:...`). No `-r`: it adds ffmpeg's `fps` filter, which keeps each frame
  until the next one comes and then sends all the repeats that fill the gap at once, so after a pause the picture
  ran seconds behind (measured on a headless output: frames out up to 4 s after their own timestamp, against 22 ms
  without it). `settb` gives the encoder the frame rate to share the bitrate by: wf-recorder's own time base is
  1 µs, which the encoder takes for a million frames a second. Frames closer than 1/fps share a timestamp, which
  FLV takes and the Station doesn't read.

## Status (2026-10-22)

- Checked: `pair` (right PIN, wrong PIN, key shown and pinned, hosts file 0600), `run --source test` (key frame and
  parameter sets first, a delimiter per frame, about 15 Mbit/s, reconnect after the connection drops, no encoder left
  after SIGTERM), and the mDNS answer parser, all against a loopback stand-in for the Station.
- Against the Station: TLS 1.3 with the Station's AndroidKeyStore key (TLS 1.2 refused), and the key's fingerprint
  matching the one setup shows. `discover` with a unicast answer found nothing because ufw dropped it; it now
  reads the group (not yet rechecked). Pairing and streaming to the Station: not yet, it went off the network.
- 2026-10-23, wf-recorder 0.6.0, Hyprland 0.56.2 (Lua config): `--source wf-recorder` streams to the Station at
  60 fps. The first runs showed the desktop slid ~900 rows down the screen: the monitor rule never applied (`keyword`
  refused), Hyprland put the output at 3440,450 at scale 2 while wf-recorder was starting, and wf-recorder kept that
  half-made geometry. Fixed by `eval`, waiting for the output to settle, and recording again when it moves.
  Then a lag of a few seconds that grew with each pause: wf-recorder's `-r` (see Encoding); the Station's own logs
  showed bursts of 120 to 177 frames a second decoded from a 60 fps sender.
  Spike S3.1 (wf-recorder vs wl-screenrec vs GStreamer) still not run.
