---
name: android-google-maps
description: Google Maps. Start turn-by-turn navigation to any destination hands-free, including on a locked phone; multi-stop previews, search along route and destination entry open one tap from done. Navigation cannot be ended.
verified: 2026-10-01
---

# Google Maps

Package `com.google.android.apps.maps`. Starting single-destination
navigation is fully hands-free; on a locked phone it costs the user
their normal unlock. Everything beyond that costs exactly one on-screen
tap. Navigation cannot be ended from the phone.

## Start navigation

A VIEW intent on Maps' `google.navigation:` URI starts turn-by-turn
immediately, no confirmation tap:

```
send_intent
{
  "action": "android.intent.action.VIEW",
  "data": "google.navigation:q=<destination>",
  "package": "com.google.android.apps.maps",
  "mode": "activity",
  "via_unlock_gate": true
}
```

- `q=` takes anything Maps can geocode: place name, address, `lat,long`,
  or the user's saved `home` / `work`. URI-encode it.
- Append `&mode=w` for walking or `&mode=b` for biking; driving is the
  default.
- Always target the Maps package: other apps claim the scheme.
- Always pass `via_unlock_gate: true`. On an unlocked phone it does
  nothing and the route starts straight away. On a locked phone the
  screen wakes with the user's own unlock prompt, and navigation is
  already running the moment they unlock. If they leave the phone
  locked, the request expires after two minutes and nothing happens.
  The result string says which of the two happened.

## Change destination mid-navigation — one tap

Fire the same URI at an active session. Maps shows an "exit
navigation?" prompt: accepted, the route swaps to the new destination;
timed out, the new destination is dropped and the original route
continues.

## Multi-stop route — one tap

The `google.navigation:` URI takes exactly one destination. For stops,
send the VIEW intent at the web directions URL, targeted at the Maps
package:

    https://www.google.com/maps/dir//<stop 1>/<stop 2>

Empty first segment means from the current location; each further
segment is a stop in order. Maps opens a correct route preview and does
not auto-start; the user taps to begin.

## Search along route — one tap

During active navigation, launching the exported activity bare opens the
real search-along-route panel with the search box focused, over the
running navigation:

```
send_intent
{
  "component": "com.google.android.apps.maps/com.google.android.apps.gmm.navigation.ui.search.ShowSearchAlongRouteActivity",
  "mode": "activity"
}
```

The query cannot be passed in; the user types or speaks it. The extras
`query`, `q`, `android.intent.extra.TEXT` and `search_query` are all
ignored.

## Open the destination-entry screen

Launching
`com.google.android.apps.maps/com.google.android.maps.driveabout.app.DestinationActivity`
bare, the same way, opens Maps at the destination-entry screen (during
active navigation, after an exit-navigation prompt). Useful only as
"open Maps ready for the user to type a destination".

## Not possible

- **End navigation**: no exported component, no URI. The user taps End.
- **Add a stop to an active route**: nothing exported.
- A bare targeted broadcast at `NavigationInfoBroadcastReceiver` is
  delivered and does nothing, during active navigation included.
