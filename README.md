# phone-mcp

An MCP server that runs on an Android phone, so an agent can act on the device
the user is actually carrying: open apps, control media, set timers, read the
phone's location, ring it, drive a paired Pebble watch.

## Design

The app exposes **generic automation interfaces** — Android's (intents,
content providers, media sessions) and the Pebble platform's (watchapp
launch and messaging) — rather than including support for any particular
app. Driving a specific app is done through those interfaces, and the
knowledge of how — verified recipes for individual apps, found by
experiment on a real device — lives in skill files under `skills/`. The
only other tools are the phone's own capabilities: location, ringing
itself, notifications.

`skills/` ships a starter set, generic mechanisms and per-app findings
alike; the next section summarises what they cover.

## What the skill files cover

The generic mechanisms, one file each at the top of `skills/`:

- **Media control** — play, pause, skip, seek and queue in any app with
  a media session, including apps that refuse the standard route.
- **Media discovery** — finding something playable: browse, search, the
  queue, now playing, the vendor's web API.
- **App launch** — when opening an app's screen is needed at all.
- **Timers and alarms** — setting them on whatever clock app the phone
  uses.
- **Pebble watch control** — launching a watchapp and sending it a
  message.

The apps with a verified file, in `skills/android-apps/` and
`skills/pebble-apps/`. ✅ means the app's main job works hands-free from
the background, 🟡 means it works with a limit the row names, ❌ means
nothing works.

Android apps:

| App | What works |
|---|---|
| ✅ [Pocket Casts](skills/android-apps/pocket-casts.md) | play any show or episode by name, resume, queue, speed; screen locked |
| ✅ [Podcast Addict](skills/android-apps/podcast-addict.md) | play by name, resume, pause, jump |
| ✅ [Simple Radio](skills/android-apps/simple-radio.md) | any station by name, favourites, pause |
| ✅ [Libro.fm](skills/android-apps/librofm.md) | any owned book by title, resume, pause |
| ✅ [AIMP](skills/android-apps/aimp.md) | any track or playlist in the library, pause, skip; screen locked |
| ✅ [YouTube Music](skills/android-apps/youtube-music.md) | any song or playlist, resume; screen locked |
| ✅ [Smart AudioBook Player](skills/android-apps/smart-audiobook.md) | any book in the library by name, resume, pause, jump |
| ✅ [Audible](skills/android-apps/audible.md) | any book by name, resume, pause, seek |
| ✅ [Google Maps](skills/android-apps/google-maps.md) | start navigation hands-free, screen locked; multi-stop and search along the route are one tap away; cannot end navigation |
| 🟡 [Substack](skills/android-apps/substack.md) | recent episodes and narrated posts; no search, no older posts |
| 🟡 [YouTube](skills/android-apps/youtube.md) | start any video on screen, then pause, seek and switch videos while it stays in the foreground |
| 🟡 [Samsung Clock](skills/android-apps/samsung-clock.md) | set timers and alarms; cannot dismiss or snooze, no stopwatch |
| 🟡 [Sleep as Android](skills/android-apps/sleep-as-android.md) | create an alarm; existing alarms cannot be read or changed |
| ❌ [Eleven Reader](skills/android-apps/eleven-reader.md) | nothing |

Pebble watchapps:

| App | What works |
|---|---|
| ✅ [Agent Timer](skills/pebble-apps/agent-timer.md) | start a timer on the watch |
| ✅ [Pebble Maps](skills/pebble-apps/pebble-maps.md) | search, or start navigation to a favourite, address or place |

Give the agent only the files for the apps you use. The recommended way
is a personal skill file, or a section of your own agent instructions,
that links the files for your apps and adds your preferences: which
podcast app is *the* podcast app, the usual navigation mode, what a bare
"set a timer" should mean. [skills/AGENTS.md](skills/AGENTS.md) explains
how the files are organised and how to write one for a new app.

## Tools

The families, not every tool: the server's own `tools/list` is the
authoritative reference and carries each tool's full description.

- **Generic Android** — `inspect` shows what an installed app exposes;
  `intent_resolve` and `send_intent` dry-run and fire intents;
  `provider_query` and `provider_call` read app state and call methods
  through content providers; the `media_*` family browses, searches,
  plays and controls any app with a media session.
- **Phone** — `get_location`, `find_my_phone_play` / `find_my_phone_stop`,
  `get_volume` / `set_volume`, `set_flashlight`, and
  `post_notification` / `dismiss_notification`.
- **Pebble watch** — the `pebblekit_*` family launches a watchapp, sends
  it a message, or does both in one call. It works against any watchapp;
  what the messages mean is per-app skill-file knowledge. Needs the
  Pebble companion app on the phone.

Each family has its own switch in the app. A family that is switched off
is absent from `tools/list`, and its tools refuse to run.

## Setting up

The app runs a plain HTTP server on the phone with a single endpoint,
`POST /mcp`, speaking JSON-RPC 2.0 / MCP and requiring
`Authorization: Bearer <token>`; any other request is answered 401. The
traffic is unencrypted, so it is meant to be reached over a VPN rather than
the open network. [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn)
is recommended.

1. **Set up Tailscale** on the phone and on the machine hosting the agent,
   signed into the same tailnet.
2. **Open Phone MCP**, set **Bind address** to the phone's Tailscale IP
   (`100.x.y.z`), **Save and apply**, and switch the server on at the top
   of the screen. Bound to that address, the server does not answer on the
   LAN at all. The port defaults to 8910.
3. **Grant permissions** as the app asks. Each tool family states what it
   needs on its own row; a family you leave off asks for nothing.
4. **Share the MCP link with the agent**: **Copy MCP URL** gives the URL,
   and the token is shown beside it. Confirm it works by asking the agent
   what tools the phone offers, or with a direct call:

   ```bash
   curl -X POST http://<phone-tailscale-ip>:8910/mcp \
     -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
   ```

5. **Curate skill files** for the apps you actually use, as described
   above; what the agent learns about a new app belongs in a new file
   under [skills/](skills/).

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

The launcher and status-bar icons are generated by the pages in `art/icons/`;
see the README there.
