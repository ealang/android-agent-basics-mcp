# phone-mcp

An MCP server that runs on an Android phone, so an agent can act on the device
the user is actually carrying. Currently it exposes timers.

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

## Running

Open the app, press **Start server**, and grant **Allow starting the clock app**.
The screen lists the URLs it's reachable on (one per network interface — LAN and
tailnet both appear) and the bearer token, with a copy button.

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
The token is generated with `SecureRandom` on first launch rather than left
blank, because a server whose auth is `""` until someone fills it in is a server
that ships unauthenticated.

## Building

```bash
./gradlew :app:assembleDebug --no-daemon
```

`local.properties` (gitignored) supplies `sdk.dir` and `DEBUG_KEYSTORE`. The
keystore is pinned outside the ephemeral home directory so rebuilds keep the
same signature and the APK installs over the copy already on the phone.
