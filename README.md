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

- `set_timer(seconds, label?)` — schedules an exact alarm. Rejects ≤ 0 and > 24h.
- `list_timers()` — pending timers with ids and fire times.
- `cancel_timer(id)`

Timers are backed by `AlarmManager.setExactAndAllowWhileIdle`, which is the only
variant that survives Doze — precisely the state a phone is in when a timer
matters. `USE_EXACT_ALARM` is granted without a prompt for apps whose core
purpose is alarms, unlike `SCHEDULE_EXACT_ALARM`.

AlarmManager forgets everything across a reboot, so pending timers are also
written to SharedPreferences and re-armed by `BootReceiver`.

## Running

Open the app, press **Start server**. The screen lists the URLs it's reachable
on (one per network interface — LAN and tailnet both appear) and the bearer
token, with a copy button.

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
