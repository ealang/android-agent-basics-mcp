# Agent Basics MCP

![icon](art/icons/launcher-50pct.png)

An MCP server that runs on an Android phone, so an agent can act on the device
the user is actually carrying: open apps, control media, set timers, read the
phone's location, drive a paired Pebble watch, etc.

Install the [latest release](https://github.com/ealang/android-agent-basics-mcp/releases).

## Design

The app exposes **generic automation interfaces** — Android's (intents,
content providers, media sessions) and the Pebble platform's (watchapp
launch and messaging) — rather than including support for any particular
app. Driving a specific app is done through those interfaces, and the
knowledge of how — verified recipes for individual apps, found by
experiment on a real device — lives in skill files under `skills/`. The
only other tools are the phone's basic capabilities.

## Agent skill file library

**Android apps:**

| App | What works |
|---|---|
| ✅ [Pocket Casts](skills/android-apps/pocket-casts.md) | playback, queue editing, catalogue search, downloads, feed refresh; screen locked |
| ✅ [Podcast Addict](skills/android-apps/podcast-addict.md) | playback, chapters, downloads, feed refresh; no search, queue editing limited; screen locked |
| ✅ [Simple Radio](skills/android-apps/simple-radio.md) | playback, any station by name, favourites, recents; screen locked |
| ✅ [Libro.fm](skills/android-apps/librofm.md) | playback, any owned book, catalogue search, speed; screen locked |
| ✅ [AIMP](skills/android-apps/aimp.md) | playback, library, playlists; no speed, no queue editing; screen locked |
| ✅ [Smart AudioBook Player](skills/android-apps/smart-audiobook.md) | playback, any book in the library, jumps; no seek, no speed; screen locked |
| ✅ [Audible](skills/android-apps/audible.md) | playback, any book, chapters, speed; screen locked |
| ✅ [Google Maps](skills/android-apps/google-maps.md) | start navigation, screen locked; cannot end it |
| 🟡 [YouTube Music](skills/android-apps/youtube-music.md) | playback by URL only, like; no search; screen locked |
| 🟡 [Substack](skills/android-apps/substack.md) | playback of recent posts, queue editing, speed; no search, no older posts; screen locked |
| 🟡 [YouTube](skills/android-apps/youtube.md) | playback by URL; launch needs the phone unlocked, then transport screen locked |
| 🟡 [Samsung Clock](skills/android-apps/samsung-clock.md) | timers, alarms, silence a ringing timer; cannot dismiss, snooze or cancel alarms; screen locked |
| 🟡 [Sleep as Android](skills/android-apps/sleep-as-android.md) | create alarms; cannot read or change them |
| ❌ [Eleven Reader](skills/android-apps/eleven-reader.md) | nothing |

**Pebble watchapps:**

| App | What works | Required Pebble app |
|---|---|---|
| ✅ [Agent Timer](skills/pebble-apps/agent-timer.md) | start a timer on the watch | [Pebble Appstore](https://apps.repebble.com/a8e93aeb045440408d0543c0) |
| ✅ [Pebble Maps](skills/pebble-apps/pebble-maps.md) | start navigation, search | [fork on GitHub](https://github.com/ealang/TomBolger-PebbleMaps) |

**Generic mechanisms:**

- **Media control** — play, pause, skip, seek and queue in any app with
  a media session, including apps that refuse the standard route.
- **Media discovery** — finding something playable: browse, search, the
  queue, now playing, the vendor's web API.
- **App launch** — when opening an app's screen is needed at all.
- **Timers and alarms** — setting them on whatever clock app the phone
  uses.
- **Pebble watch control** — launching a watchapp and sending it a
  message.

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
`Authorization: Bearer <token>`. The
traffic is unencrypted, so it is meant to be reached over a VPN rather than
the open network. [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn)
is recommended. The app installs on Android 10 (API 29) or later.

1. **Set up Tailscale** on the phone and on the machine hosting the agent,
   signed into the same tailnet.
2. **Open Agent Basics MCP** and check **Bind address** under Server settings. On
   first launch it is filled in with the VPN's address when one is up, so
   with Tailscale connected it already reads `100.x.y.z`; otherwise tap the
   field and pick the VPN row from the list, then **Save and apply**. Bound
   to that address, the server does not answer on the LAN at all. The last
   row, **All IPv4 interfaces**, is the opt-in for answering on every
   network the phone is on. Switch the server on at the top of the screen.
   The port defaults to 8910.
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
./gradlew :app:assembleRelease   # unsigned release APK, to be signed below
```

Output lands under `app/build/outputs/apk/`. The debug build has the package
suffix `.debug` and a "(debug)" label, so it installs alongside a release
build as a separate app with its own settings and token.

A release APK is signed after the build, with `apksigner` from the SDK's
build-tools and a keystore that lives wherever you keep secrets:

```bash
apksigner sign --ks release.jks --ks-key-alias <alias> \
  --out agent-basics-mcp-<version>.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs agent-basics-mcp-<version>.apk
```

The key is permanent: Android only installs an update signed with the same key
as the copy already on the phone. Bump `versionCode` in `app/build.gradle.kts`
for every release, or the install is refused as a downgrade. The build needs an Android SDK
(`sdk.dir` in `local.properties`, which is gitignored). Optionally
`local.properties` can also carry `DEBUG_KEYSTORE=/path/to/debug.keystore`
to sign debug builds with a key of your choosing instead of the SDK's
auto-generated one; Android's debug key is fine either way, as long as it stays
the same key from build to build so each APK installs over the last one.

The launcher and status-bar icons are generated by the pages in `art/icons/`;
see the README there.
