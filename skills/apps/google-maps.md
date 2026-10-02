# Google Maps

- **Package**: `com.google.android.apps.maps`
- **Tier**: intents only — no MediaBrowserService; driven via `send_intent`
- **Verified on device**: 2026-09-05; generic flow re-verified 2026-10-01

Rule of thumb: starting single-destination navigation is fully hands-free;
on a locked phone it costs the user their normal unlock. Everything beyond
that costs exactly one on-screen tap.

## Start navigation

A VIEW intent on Maps' documented `google.navigation:` URI starts
turn-by-turn immediately, no confirmation tap:

```json
{
  "action": "android.intent.action.VIEW",
  "data": "google.navigation:q=<destination>",
  "package": "com.google.android.apps.maps",
  "mode": "activity",
  "via_unlock_gate": true
}
```

- `q=` takes anything Maps can geocode — place name, address,
  `lat,long`, or the user's saved `home` / `work`. URI-encode it.
- Append `&mode=w` for walking or `&mode=b` for biking; driving is the
  default.
- **Always target the Maps package**: Uber also claims the scheme.
- **Always pass `via_unlock_gate: true`.** On an unlocked phone it does
  nothing and the route starts straight away. On a locked phone it is
  essential: fired straight at the keyguard, Maps swallows the
  destination while the launch reports success. Through the gate the
  screen wakes with the user's own unlock prompt and navigation starts
  the moment they unlock (verified 2026-10-01); if they leave the phone
  locked, the request expires after two minutes and nothing happens.
  The result string says which of the two happened.

## Change destination mid-navigation — one tap

Fire the same URI at an active session. Maps shows an
"exit navigation?" prompt:

- User accepts → route swaps to the new destination.
- Prompt times out → new destination is dropped, original route continues.

Tell the user to expect the prompt.

## Multi-stop route — one tap

The `google.navigation:` URI takes exactly one destination. For stops,
send a VIEW intent at the web dir URL, targeted at the Maps package:

    https://www.google.com/maps/dir//<stop>/<destination>

Empty first segment = from current location; each further segment is a
stop in order. Maps opens a correct route preview and does **not**
auto-start — the user taps to begin.

## Search along route — opens the panel, user supplies the query

During active navigation, launching the exported activity
`com.google.android.apps.maps/com.google.android.apps.gmm.navigation.ui.search.ShowSearchAlongRouteActivity`
bare (activity mode, no extras) opens the real search-along-route panel
with the search box focused, over the running navigation. The query
cannot be passed in — the user types or speaks it.

## Open the destination-entry screen

Launching
`com.google.android.apps.maps/com.google.android.maps.driveabout.app.DestinationActivity`
bare opens Maps at the destination-entry screen (during active
navigation, after an exit-navigation prompt). Useful only as "open Maps
ready for the user to type a destination".

## Not possible

- **End navigation** — no exported component, no URI. The user taps End.
- **Add a stop to an active route** — nothing exported.

## No effect observed / extras ignored

Probed live; nothing visible happened. Do not re-probe. Potential
follow-up for both: decompile.

- `ShowSearchAlongRouteActivity` query extras — `query`, `q`,
  `android.intent.extra.TEXT`, `search_query` all ignored; the real extra
  name is obfuscated.
- `NavigationInfoBroadcastReceiver`
  (`com.google.android.libraries.geo.navcore.navinfo`) — a bare targeted
  broadcast is delivered (1 receiver matched) and does nothing, during
  active fullscreen navigation included.
