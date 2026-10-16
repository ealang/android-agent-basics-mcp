# phone-mcp

An MCP server that runs on an Android phone, so an agent can act on the device
the user is actually carrying — timers, the paired Pebble watch, and where the
phone is.

## Why this exists

Everything an agent normally reaches is a remote service. A timer isn't useful
as a database row — it's useful when it makes a sound in the room the user is
standing in. That requires code on the phone, which is what this is.

## Design

The app exposes **generic automation interfaces** — Android's (intents,
content providers, media sessions) and the Pebble platform's (watchapp
launch and messaging) — rather than including support for any particular
app. Driving a specific app is done through those interfaces, and the
knowledge of how — verified recipes for individual apps, found by
experiment on a real device — lives in skill files under `skills/`. The
only other tools are the phone's own capabilities: location, ringing
itself, notifications.

## Endpoints

| Path | Auth | Purpose |
|---|---|---|
| `POST /mcp` | `Authorization: Bearer <token>` | JSON-RPC 2.0, MCP |
| `GET /health` | none | `{"ok":true}` — tells "wrong port" apart from "wrong token" |

`GET /mcp` returns 405. The Streamable HTTP transport permits answering a POST
with an SSE stream, but these tools return a string in a millisecond and have
nothing to push, so every exchange is ordinary request/response JSON.

## Tools

### Generic

- `intent_scan(package?)` — what an installed app declares as automatable:
  exported components and their intent filters, split APKs included.
- `intent_resolve(...)` — dry run: who would handle an intent, without
  sending it.
- `send_intent(...)` — build and fire an intent as an activity start,
  broadcast, or service start. Executes exactly what it is told; the one
  opt-in mechanism is `via_unlock_gate`, a fallback for intents that
  require the phone to be unlocked — the launch waits behind the user's
  unlock prompt instead of being silently discarded by the keyguard.
- `provider_query(uri?)` — the read side: with no uri, every exported
  ContentProvider on the device with its permission guards; with a
  `content://` uri, run the query and get rows back. Intents are
  fire-and-forget; providers are the generic way to read app state.
- `media_apps` / `media_browse` / `media_play` / `media_command` /
  `media_search` — drive any app that exposes a MediaBrowserService.
  Per-app findings live in `skills/apps/`.

### Phone

- `get_location(max_age_seconds?)` — coordinates, accuracy, fix age, and a
  street address when the geocoder can supply one.
- `find_my_phone_play(seconds?, volume?, tracks?)` / `find_my_phone_stop()` —
  ring the phone to locate it. A default sound plays unless `tracks` gives a
  melody: up to two tracks of `{pitch, seconds}` notes, mixed and looped.
- `post_notification` / `dismiss_notification` — put a message in front
  of the user, at an info or alert level.

### Pebble

All on the classic PebbleKit intent protocol, hence the prefix — a future
PebbleKit 2 channel would be its own `pebblekit2_*` family.

- `pebblekit_launch_app(app)` / `pebblekit_stop_app(app)` — start or stop
  any Pebble watchapp, by UUID or by a name from `Watch.KNOWN_APPS`.
  Fire-and-forget: the watch never acknowledges a launch.
- `pebblekit_send_message(app, tuples)` — the watch equivalent of
  `send_intent`: a raw AppMessage to any watchapp, tuples passed verbatim,
  the watch's own ACK or NACK returned. An ACK proves delivery to a
  running app, not that the keys meant anything — key tables are per-app
  knowledge and belong in `skills/`. A message to a watchapp that is not
  running vanishes silently.
- `pebblekit_launch_and_send(app, ping_key, tuples)` — the handshake that
  silent discard forces, in one call: launch, ping on a key the target
  app deliberately ignores until the watch answers (any reply proves the
  app is up), then deliver the payload exactly once. The ping key is
  per-app knowledge from `skills/`.

### The permission that isn't about drawing

Activity intents hit a wall: since Android 10 a background app may not start
an activity — a foreground service does *not* earn the right. The only
exemption a normal app can obtain is `SYSTEM_ALERT_WINDOW`, "Display over
other apps". Without it the launch is dropped in silence, which is the worst
possible failure for a tool an agent is trusting.

This one grant lets the server fire a launch intent at any app from a locked
phone with the screen off, so no app being driven ever needs its own
background-start exemption.

### The watch

Nothing links against PebbleKit. The Core companion app (`coredevices.coreapp`)
registers the PebbleKit Classic broadcast receivers at runtime and exports them,
so `com.getpebble.action.app.START` with a `uuid` extra reaches
`libPebble.launchApp(uuid)` and goes down the wire as an `AppRunStateStart`
packet. `.STOP` likewise. The broadcast is addressed to the companion's package
rather than sent implicitly.

These are broadcasts, not activity starts, so none of the background rules
above apply here. No permission is needed beyond the
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

`ACCESS_BACKGROUND_LOCATION` ("Allow all the time") is also requested, because
the ordinary while-in-use grant is withheld from a service the system does not
consider in use — see *Starting from the background* below. Android will not
prompt for it: since 11 the request returns denied without showing anything, so
**Allow location all the time** explains the two taps and opens the app's
permission screen. Without it `get_location` works normally and fails only in
that one window; the status line and the tool's own error both say which.

### Finding the phone

`find_my_phone_play` synthesises a looping sound with `USAGE_ALARM`. That usage
is the whole feature: alarms are exempt from silent mode and from Do Not
Disturb, so a phone face-down under a cushion still answers. Being loud by
definition, every ring is bounded — a deadline stops it after `seconds` (30 by
default, 300 at most) even if `find_my_phone_stop` is never called, and
stopping the service stops the sound, since nothing would be left to answer
`find_my_phone_stop`.

The voice is a 25%-duty pulse wave — the chiptune lead. A default sound plays
if no melody is given; `tracks` replaces it with up to two note tracks sounding
together, each an array of `{pitch, seconds}` (pitch 0 is a rest). The longest
track sets the loop, which repeats until the time is up.

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

A **Server events** section below it logs what the server itself did: every
bind ("listening on …"), every bind failure and the retry it scheduled, accept
failures (counted always, logged at the start of a streak), socket deaths, and
restarts. A server that restarted while nobody was watching shows it here.

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

That notification sits on a minimum-importance channel ("MCP server") which the
user is welcome to block — the app's **Hide status notification** button opens
that channel's settings page directly, since some OEM Settings apps never list
channels. Blocking it must not hide a failing server, so once binding has been
failing for more than two minutes the same notification moves to a second
channel, "Server trouble", at default importance: it sounds once per outage the
way the phone is set to, and moves back to the quiet channel when the bind
succeeds. It is one notification moved between channels, never two.

The service restarts on `BOOT_COMPLETED` if it was running when the phone shut
down, and on `MY_PACKAGE_REPLACED` so an update does not leave the server off.
Two caveats: `BOOT_COMPLETED` only reaches an app the user has launched at
least once since install, and some OEM battery managers suppress it. The
**Battery settings** button leads to the exemption screen.

### Starting from the background

Both of those broadcasts start the service from the background, and that is a
weaker position than starting from the UI. `startForeground` claims every type
the manifest declares unless told otherwise, and `location` is a *while-in-use*
type — Android grants it only to an app it considers in use, which an app woken
by a broadcast is not. The refusal arrives as a `SecurityException` thrown at
the service, so it took the whole server down; `START_STICKY` then restarted it
into the same refusal, which the system reports to the user as the app
repeatedly crashing. Installing an update over a running server hit this every
time.

So the types claimed are now chosen per start rather than left implicit:
`specialUse|location|mediaPlayback` first, falling back to `specialUse` alone,
which carries no eligibility condition. The HTTP listener — the point of the
service — needs none of the others. Only `get_location` wants `location`, and
losing that is much better than losing everything, so the fallback is recorded
(`McpService.degraded`) rather than treated as failure. Opening the app retries
the full set, since being on screen is exactly the eligibility that was
missing, and the status line says so meanwhile.

Location genuinely does not work in that degraded window — measured, not
assumed: a fresh fix took 0.57 s with the type held, and timed out at the full
20 s without it, in the same room minutes apart. Android withholds it
*silently*, so `LocationManager` returns no fix and no cached position rather
than throwing, which is indistinguishable from being indoors. `get_location`
therefore checks `degraded` before blaming the weather, and says to open the
app instead.

The window is small — a background start that nothing follows, and opening the
app closes it — but it is exactly the moment a remote caller wants location, so
`ACCESS_BACKGROUND_LOCATION` is requested to close it properly. Granting that
makes the fallback harmless: location is answered whether or not the service
holds the type.

In practice it did more than that. With the permission granted, an update
installed over a running server took the *full* type set on the background
start — no fallback line in Server events — and `get_location` answered in
0.90 s and 0.63 s where the same procedure had timed out at 20 s before. The
permission appears to satisfy the eligibility condition for the `location` type
itself, so the degraded state stops happening rather than merely stopping
mattering. The fallback stays: it is what keeps a refusal from taking the server
with it, whatever the reason for the refusal.

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
./gradlew :app:assembleDebug     # debug APK, signed with the local debug key
./gradlew :app:assembleRelease   # unsigned release APK; sign it however you ship
```

Output lands under `app/build/outputs/apk/`. The build needs an Android SDK
(`sdk.dir` in `local.properties`, which is gitignored). Optionally
`local.properties` can also carry `DEBUG_KEYSTORE=/path/to/debug.keystore`
to sign debug builds with a key of your choosing instead of the SDK's
auto-generated one; Android's debug key is fine either way, as long as it stays
the same key from build to build so each APK installs over the last one.
