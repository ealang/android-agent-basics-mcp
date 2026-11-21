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
knowledge of how, lives in skill files under [`skills/`](skills).

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
| ✅ [Google Maps](skills/android-apps/google-maps.md) | start navigation, screen must be unlocked to start; cannot end it |
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

**Generic mechanisms:**

- [Media control](skills/media-control.md) — play, pause, skip, seek and
  queue in any app with a media session, including apps that refuse the
  standard route.
- [Media discovery](skills/media-discovery.md) — finding something
  playable: browse, search, the queue, now playing, the vendor's web API.
- [App launch](skills/app-launch.md) — when opening an app's screen is
  needed at all.
- [Timers and alarms](skills/alarms-and-timers.md) — setting them on
  whatever clock app the phone uses.
- [Files](skills/files.md) — moving files on and off the phone and
  opening one in another app.
- [Pebble watch control](skills/pebble-watch-control.md) — launching a
  watchapp and sending it a message.

Give the agent only the files for the apps you use. The recommended way
is a personal skill file, or a section of your own agent instructions,
that links the files for your apps and adds your preferences: which
podcast app is *the* podcast app, the usual navigation mode, what a bare
"set a timer" should mean. [skills/AGENTS.md](skills/AGENTS.md) explains
how the files are organised and how to write one for a new app.

## Tools

Each family has its own switch in the app, and a family that is switched
off is absent from the server's `tools/list`.

- **Generic Android**
  - **Inspect** — shows what an installed app exposes.
  - **Intents** — dry-run and send intents to any app.
  - **Content providers** — read app state and call provider methods.
  - **Media** — browse, search, play and control any app with a media
    session.
- **Files** — upload, download and manage files in a private temporary
  folder and in folders you add in the app.
- **Phone** — location, find my phone, volume, flashlight and
  notifications.
- **Pebble watch** — launch a watchapp and exchange messages with it;
  needs the Pebble companion app on the phone.

## Setting up

The app runs a plain HTTP server on the phone with a single endpoint,
`POST /mcp`, speaking JSON-RPC 2.0 / MCP and requiring
`Authorization: Bearer <token>`. The
traffic is unencrypted, so it is meant to be reached over a VPN rather than
the open network. [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn)
is recommended. The app installs on Android 10 (API 29) or later.

Curate skill files for the apps you actually use, as described above;
what the agent learns about a new app belongs in a new file under
[skills/](skills/).
