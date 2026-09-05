# App launch — when you need it, and mostly why you don't

## For media apps: you don't

Binding a MediaBrowserService (see [media-control.md](media-control.md))
wakes the app's process directly — no activity, no window, no keyguard.
Playback has been started on a **cold app with the screen locked**
(Pocket Casts, killed process → playFromSearch → playing). Launching the
app's UI first buys nothing and was retired as a strategy: phone-mcp's
old `extra_accl_intent` launch hack for Pocket Casts predates this
finding and is strictly worse (needs the overlay grant, animates an
activity, fights the keyguard).

So the generic rule: **if the goal is playback or transport control, skip
launch entirely and go through the media session.**

## The exception class: apps that must be foregrounded

Android denies some capabilities to background processes regardless of
what the app wants — most importantly **microphone access**. An app that
needs to record (e.g. a voice recorder) must have a foreground activity
or a properly promoted foreground service; a background-woken process
recording silence is the failure mode. These apps need a real launch,
which is a purpose-built integration (phone-mcp holds
`SYSTEM_ALERT_WINDOW` to start activities from the background), not a
generic skill — see the per-app docs in `docs/` for those.

## Launch as a fallback for dead media routes

Where the media session refuses playback (YouTube's Premium gate,
Substack posts older than the tree window), the remaining option is the
app's UI: a deep link / VIEW intent to the content's URL, which opens the
app *on screen* and requires the user (or further UI automation) to press
play. This is qualitatively different — visible, keyguard-bound, not
verifiable — and is currently **not exposed** as a generic phone-mcp
tool. Noted as future work in [README.md](README.md).
