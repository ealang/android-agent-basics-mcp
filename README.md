# phone-mcp

An MCP server that runs on an Android phone, so an agent can act on the device
the user is actually carrying — timers, the paired Pebble watch, and where the
phone is.

## Why this exists

Everything an agent normally reaches is a remote service. A timer isn't useful
as a database row — it's useful when it makes a sound in the room the user is
standing in. That requires code on the phone, which is what this is.

## Endpoints

| Path | Auth | Purpose |
|---|---|---|
| `POST /mcp` | `Authorization: Bearer <token>` | JSON-RPC 2.0, MCP |
| `GET /health` | none | `{"ok":true}` — tells "wrong port" apart from "wrong token" |

`GET /mcp` returns 405. The Streamable HTTP transport permits answering a POST
with an SSE stream, but these tools return a string in a millisecond and have
nothing to push, so every exchange is ordinary request/response JSON.

## Tools

- `set_timer(seconds, label?)` — rejects ≤ 0 and > 24h.
- `cancel_timer(label?)` — by label, or the next one to ring.
- `show_timers()` — opens the clock app's timer screen.
- `launch_watch_app(app)` / `stop_watch_app(app)` — a Pebble watchapp, by UUID
  or by a name from `Watch.KNOWN_APPS`.
- `get_location(max_age_seconds?)` — coordinates, accuracy, fix age, and a
  street address when the geocoder can supply one.
- `find_my_phone(seconds?, volume?)` / `silence_phone()` — ring the phone to
  locate it.

### Timers

Timers are handed to whichever app answers `AlarmClock.ACTION_SET_TIMER`, which
is normally the stock clock. That is worth more than ringing them ourselves: the
timer lands where the user already looks for timers, uses their own alarm sound,
can be edited or snoozed by hand, and keeps running even if this app is killed.

Two consequences follow from delegating, and neither has a workaround:

**There is no read API.** Nothing can enumerate the clock app's timers, so there
is no `list_timers` and no way to confirm what is running. `cancel_timer`
searches by label (`ALARM_SEARCH_MODE_LABEL`) or takes the next one to fire
(`ALARM_SEARCH_MODE_NEXT`).

**`EXTRA_SKIP_UI` is a request, not a guarantee.** The stock clock honours it and
starts the timer without coming to the front; another clock app may show its UI.

### The permission that isn't about drawing

These are activity intents, and since Android 10 a background app may not start
an activity — a foreground service does *not* earn the right. The only exemption
a normal app can obtain is `SYSTEM_ALERT_WINDOW`, "Display over other apps".
Without it the intent is dropped in silence, which is the worst possible failure
for a tool an agent is trusting, so `Clock.canStartFromBackground` checks first
and the tools return a hard error naming the permission rather than reporting a
timer that was never set.

### The watch

Nothing links against PebbleKit. The Core companion app (`coredevices.coreapp`)
registers the PebbleKit Classic broadcast receivers at runtime and exports them,
so `com.getpebble.action.app.START` with a `uuid` extra reaches
`libPebble.launchApp(uuid)` and goes down the wire as an `AppRunStateStart`
packet. `.STOP` likewise. The broadcast is addressed to the companion's package
rather than sent implicitly.

These are broadcasts, not activity starts, so none of the background rules that
govern the timer intents apply here. No permission is needed beyond the
`<queries>` entries that let this app see the companion at all.

Launching cannot pass parameters — the app opens in its default state. Doing
more requires `com.getpebble.action.app.SEND`, which delivers an AppMessage, but
that listener only exists while a non-system watchapp is running and is skipped
for apps that ship PKJS or declare an Android companion package. So it is a
route to a watchapp we write, not to a stock one.

Nothing reports back. The watch never acknowledges the launch, so the tools
answer with what was asked for. To keep that from being a silent failure they
first check the companion is installed and query its exported state provider
(`content://com.getpebble.android.provider.basalt/state`, column 0) for a
connected watch, and refuse to send if there isn't one.

### Location

Framework `LocationManager`, not Play Services, so the dependency list stays at
one. Every enabled provider is asked at once and the first fix wins, with a 20 s
ceiling. A cached fix younger than `max_age_seconds` short-circuits that; a
cached fix older than it is still returned if the fresh request comes back
empty, with its age stated, since a stale position beats none.

The service declares `location` alongside `specialUse` so it can hold the
permission while running in the background. Reverse geocoding is best effort —
it needs the network, and the response says so by simply omitting the address.

### Finding the phone

`find_my_phone` plays a looping tone with `USAGE_ALARM`. That usage is the whole
feature: alarms are exempt from silent mode and from Do Not Disturb, so a phone
face-down under a cushion still answers. Being loud by definition, every ring is
bounded — a deadline stops it after `seconds` (30 by default, 300 at most) even
if `silence_phone` is never called, and stopping the service stops the tone,
since nothing would be left to answer `silence_phone`.

The one thing the usage cannot override is a zeroed alarm slider, so `volume`
(1–100) sets the alarm stream for the length of the ring and puts the user's own
setting back afterwards. Without it the tool reads the volume and refuses at
zero, rather than ringing inaudibly and reporting success — a phone turned down
is exactly the phone you can't find.

Raising the stream is refused while Do Not Disturb is on unless the app holds
`ACCESS_NOTIFICATION_POLICY`, a much broader grant than this is worth, so in
that case the ring falls back to the user's volume instead of failing.

The tone is synthesised, not loaded: a second of 2 kHz sine then a second of
silence, looped, because a pulse is easier to locate by ear than a continuous
note. That means there is no audio file to go missing and no media permission to
grant. It is generated at 48 kHz, which divides evenly by 2 kHz so a cycle is a
whole 24 samples and the loop point lands mid-cycle-free; the burst is ramped in
and out over 5 ms, since a sine cut off mid-amplitude is a step change, which is
a click. It plays from a `MODE_STATIC` `AudioTrack`, so the hardware loops the
buffer and no thread has to stay alive feeding it.

## Running

Open the app, press **Start server**, and grant **Allow starting the clock app**
and **Allow location**.
The screen lists the URLs it's reachable on and the bearer token, with a copy
button.

A **Requests** section shows a running total and the last few requests — time,
caller, MCP method and tool, HTTP status. It is in-memory and resets with the
process; it exists so "is anything actually hitting this?" is a glance rather
than a guess, health probes and client retries included.

**Bind address** decides which interfaces answer. Left blank, the server binds
the wildcard address and is reachable on every interface, the LAN included. Set
it to the phone's tailnet address (`100.x.y.z`) and the socket is bound to that
address alone, so nothing on the LAN can reach it — the port is not merely
guarded there, it isn't listening.

The address is used rather than the interface name because Android runs
Tailscale as a `VpnService`, which gets a generic `tunN` name that belongs to
whichever VPN connected first and can change number across reconnects. The
tailnet address is pinned to the node and survives reboots and reinstalls.

Binding one address fails whenever that address is absent — a boot that gets to
`BOOT_COMPLETED` before Tailscale connects, or the VPN dropping mid-day. The
service rides this out rather than stopping: the notification reads "Waiting to
bind" and it retries on a backoff that settles at once a minute, which costs
nothing and never wakes a sleeping phone. The same recovery runs if the socket
dies underneath a running server.

The socket itself is wrapped (`ResilientServerSocket`) because NanoHTTPD's
listener loop retries a failed `accept()` instantly and logs it invisibly — a
socket in a persistent error state, as when Tailscale tears down the tun
interface under an address-bound socket, pegged a core for hours and once put
the app at 26% of a day's battery. The wrapper paces failures and, after ~5
seconds of unbroken ones, closes the socket so the service can rebind.

The server runs in a foreground service; the persistent notification is a
requirement of holding a socket open, not decoration. Android kills plain
background services within minutes and the socket dies with them.

The service restarts on `BOOT_COMPLETED` if it was running when the phone shut
down. Two caveats: that broadcast only reaches an app the user has launched at
least once since install, and some OEM battery managers suppress it. The
**Battery settings** button leads to the exemption screen.

## Client config

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "http://<phone-ip>:8910/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Traffic is plain HTTP — fine on a tailnet or trusted LAN, not on anything else.
Setting the bind address to the tailnet address is what keeps that true, since
it stops the server answering on the LAN at all.
The token is generated with `SecureRandom` on first launch rather than left
blank, because a server whose auth is `""` until someone fills it in is a server
that ships unauthenticated.

## Building

```bash
./gradlew :app:assembleDebug --no-daemon
```

`local.properties` (gitignored) supplies `sdk.dir` and `DEBUG_KEYSTORE`, which
points at a keystore kept outside the repo. The keystore is pinned
outside the ephemeral home directory so rebuilds keep the
same signature and the APK installs over the copy already on the phone.
