# The laptop stream protocol (version 1)

How `station-ar-send` gives Station AR a laptop monitor. Code: `core/.../stream/` (Handshake, Pairing, AnnexB),
`app/.../receiver/` (Tls, StreamServer, Advertiser), `app/.../sources/StreamSource.kt`, `sender/linux/station-ar-send`.

## Transport

- TCP to the Station's port **47800**, open **only while AR is open**. Leaving AR closes the port, every
  connection, the decoders and the advertisement.
- **TLS 1.3 only.** The Station's key is EC P-256 in `AndroidKeyStore` (alias `stationar-receiver`, made on first
  use, never leaves the keystore) with a self-signed certificate. There is no CA: the laptop pins the SHA-256 of the
  certificate's DER at pairing and refuses any other.
- While AR is open the Station advertises `_stationar._tcp` over mDNS (`NsdManager`), name "Station AR", TXT
  `v=1` and `fp=<first 16 hex digits of the fingerprint>`.

## Lines

Before the video, each side sends ASCII lines ending in `\n` (a `\r` before it is ignored), printable characters
only, at most 256 bytes. Anything else, or a first line that isn't one of these, closes the connection. The first
line must arrive within 10 s of connecting.

### Pairing (once per laptop)

```
laptop:  STATIONAR 1 PAIR <name>          name: 1-40 of A-Z a-z 0-9 space . _ -
Station: PIN                              the glasses show: Pair "<name>": PIN 123456 · key 1A2B 3C4D 5E6F 7A8B
laptop:  PIN <6 digits>                   within 120 s
Station: TOKEN <64 hex>                   32 random bytes; the laptop keeps it
     or  DENIED                           wrong PIN (the PIN is spent)
     or  LOCKED <seconds>                 three wrong PINs lock pairing for 60 s
     or  BUSY                             another laptop is pairing
```

The connection closes after the answer. The person checks that the key the sender prints (the first 16 hex digits of
the fingerprint, 64 bits) matches the one in the glasses before typing the PIN; that check is what makes the PIN
trustworthy. It is 16 digits because the fingerprint is public (every connection gets the certificate): with 8, someone
on the LAN could make certificates until one matched the first 32 bits, then relay the pairing.

The Station keeps only `SHA-256(token)` per laptop name (`files/laptops.txt`); pairing a name again replaces its
token. The laptop keeps the fingerprint, address, port, token and name in `~/.config/station-ar/hosts` (mode 0600).

### A stream (one screen per connection)

```
laptop:  STATIONAR 1 HELLO <token> <screen> <width> <height> <fps> <codec>
           screen: 0 | 1 | wide      width 64-4096, height 64-2160, fps 1-120, codec: hevc | avc
Station: OK                          then the video, until either side closes
     or  DENIED                      unknown token: pair again
     or  NO_DECODER                  the Station can't decode this size and codec (wide AVC, for one)
     or  BUSY                        no room for another screen in the layout
     or  CLOSED                      AR has no screen for it now (you chose a layout without it): try again later
     or  VERSION 1                   (to any other version) the version this Station speaks
```

- A `HELLO` for a screen that already streams **replaces** that connection: a laptop that lost the network and
  reconnects takes over at once.
- If no screen shows the stream, Station AR adds one where the Desk layout puts it (`Presets.place`), at 1:1 for
  the stream's size; `wide` is two screens, the stream's left and right halves. A stream whose screen you put away
  (by choosing a layout without it) is answered `CLOSED` until a layout shows it again.

## The video

- **Annex-B** H.265 (or H.264), no container, no audio.
- An **access-unit delimiter** before every picture (`-bsf:v hevc_metadata=aud=insert`): the Station cuts the byte
  stream at them (`AnnexB`) and hands each picture to the decoder as one buffer.
- **No B-frames**; a key frame (with VPS/SPS/PPS in band) at least every 2 s. The sender restarts its encoder on every
  connection, so the first picture is a key frame; the Station drops pictures until it sees parameter sets.
- **A constant frame rate.** Five seconds without a byte ends the stream on the Station.
- The Station queues pictures with their arrival time and renders each decoded frame at once (`KEY_LOW_LATENCY` when
  the decoder has the feature; the Amlogic HEVC decoder does).

## What the receiver refuses, and how

| Case | Result |
| --- | --- |
| Not TLS, or TLS 1.2 | The TLS handshake fails |
| First line late, too long, binary or unknown | Closed |
| Unknown token | `DENIED` (logged without the token) |
| Wrong PIN | `DENIED`; the third in a row: `LOCKED 60` |
| PIN for another name, or after `DENIED` | Counts as wrong |

Tokens, PINs and keys never appear in the log.
