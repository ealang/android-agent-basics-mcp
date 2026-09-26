# Smart AudioBook Player

- **Package**: `ak.alizandro.smartaudiobookplayer`
- **Tier**: browser route refused (signature allowlist); **fully drivable
  anyway** — its player service exports named actions as plain intent
  filters, richer than a media button
- **Verified on device**: 2026-09-26 (v11.9.0)

Mechanisms: [media-control.md](../media-control.md).

## The service actions — the whole remote control, one intent each

The app has **no MEDIA_BUTTON listener at all** (no MediaButtonReceiver,
no MEDIA_BUTTON filter on any service), so the YT Music revival recipe
does not apply. It doesn't need to: `PlayerService` declares thirteen
named actions, and `send_intent` fires them directly — no KeyEvent
extra, the action name *is* the command:

```
send_intent
  mode: foreground_service
  component: ak.alizandro.smartaudiobookplayer/ak.alizandro.smartaudiobookplayer.PlayerService
  action: ak.alizandro.smartaudiobookplayer.ActionPlay
```

Verified 2026-09-26: with no live session anywhere, this one call
resumed the current book — PLAYING, position advancing, at the app's
saved speed.

The full action vocabulary (prefix each with
`ak.alizandro.smartaudiobookplayer.`):

| Action | Effect |
|---|---|
| `ActionPlay` / `ActionPause` / `ActionPlayPause` | transport |
| `ActionRewindBig` / `ActionRewindSmall` | jump back (app-configured sizes) |
| `ActionFwdSmall` / `ActionFwdBig` | jump forward |
| `ActionPrevFile` / `ActionNextFile` | file within the book |
| `ActionAddBookmark` | bookmark at current position |
| `ActionEnableSleepTimer` / `ActionDisableSleepTimer` | sleep timer |
| `ActionExit` | stop and tear down |

Note what this buys over any session route: bookmark and sleep timer are
reachable **even with no live session** — verbs the media-session
grammar has no words for.

Only `ActionPlay` is verified so far; the rest are the same filter on
the same service, declared in the same manifest block.

## While a session is live

- `media_sessions` shows it: state, book title, position, speed.
- Advertised: play, pause, play_pause, play_from_media_id,
  play_from_search. Custom actions mirror three of the service actions
  (ActionRewindSmall, ActionFwdSmall, ActionExit) — with the service
  route available there is no reason to prefer them.

## Not usable / untested

- **Browser route refused** — `onGetRoot` signature allowlist, confirmed
  2026-09-02 and 2026-09-04. Don't re-probe.
- Untested: session `play_from_search` / `play_from_media_id` (the
  session advertises both), and `MEDIA_PLAY_FROM_SEARCH` at its
  `PlayerActivity` (the manifest accepts it — but YT Music also
  accepted-and-ignored it, so treat as unproven). Content selection by
  name is an open question; transport and resume-last-book are solved.
- Untested: revival from screen locked and from a naturally
  long-dead process (the verified run had the screen unlocked).
- Force-stopped remains unreachable, as everywhere.
