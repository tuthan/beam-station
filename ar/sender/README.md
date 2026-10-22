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
sender/linux/station-ar-send run --source test     # a test pattern with the laptop's clock and a frame counter
```

`discover` asks the mDNS group and reads the answers on UDP 5353, which ufw's default rules let in. If it still
finds nothing (another firewall, or a network that drops multicast), give the Station's address instead: every
command takes one.

Options for `run`: `--size 2560x1440`, `--fps 60`, `--codec hevc|avc` (AVC can't do `--wide` on the Station),
`--bitrate 15M`, `--device /dev/dri/renderD128`, `-v` (bitrate every 10 s).

`run` makes a Hyprland headless output per monitor (`STATIONAR-0`, `STATIONAR-1`, or `STATIONAR-WIDE`), to the right
of your monitors, and removes it when it exits, Ctrl-C included. Move windows onto it as onto any monitor. If the
network drops, it reconnects (every 0.5 to 3 s) and the screen in AR comes back where it was.

Pairings live in `~/.config/station-ar/hosts` (readable only by you): each Station's key fingerprint, address,
port and token. `forget <address>` or `forget all` removes them here.

## Encoding

`hevc_vaapi`, constant bitrate (15 Mbit/s by default), no B-frames, a key frame every 2 s, an access-unit delimiter
before every frame. The encoder restarts on each connection, so the Station gets a key frame at once.

- `--source test` and `--source file`: one ffmpeg per screen, `-re` (real time).
- `--source wf-recorder`: `wf-recorder -o STATIONAR-n -r <fps> -c hevc_vaapi` (a constant frame rate, so the
  Station can tell a dead connection from a still desktop), piped through `ffmpeg -c copy` to insert the delimiters.

## Status (2026-10-22)

- Checked: `pair` (right PIN, wrong PIN, key shown and pinned, hosts file 0600), `run --source test` (key frame and
  parameter sets first, a delimiter per frame, about 15 Mbit/s, reconnect after the connection drops, no encoder left
  after SIGTERM), and the mDNS answer parser, all against a loopback stand-in for the Station.
- Against the Station: TLS 1.3 with the Station's AndroidKeyStore key (TLS 1.2 refused), and the key's fingerprint
  matching the one setup shows. `discover` with a unicast answer found nothing because ufw dropped it; it now
  reads the group (not yet rechecked). Pairing and streaming to the Station: not yet, it went off the network.
- **Not checked at all: `--source wf-recorder`.** wf-recorder isn't installed on this laptop, so its flags
  (`-r`, `-p`, `-m`, `-f /dev/stdout`) and the headless output commands follow their documentation only. Spike S3.1
  (wf-recorder vs wl-screenrec vs GStreamer on encode lag, CPU and stability) couldn't run for the same reason.
