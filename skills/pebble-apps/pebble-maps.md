---
name: pebble-maps
description: Pebble Maps watchapp, forked build. Show Google Maps search results on the watch, or start turn-by-turn navigation to a favourite, address, place or coordinate with an optional travel mode.
verified: 2026-10-20
---

# Pebble Maps (Pebble watchapp)

UUID `cce60bc9-e29f-4988-a83b-b4652291650d`. Google Maps search and
turn-by-turn directions on the watch. Driving it from the phone needs the
fork github.com/ealang/TomBolger-PebbleMaps, which adds the two commands
below to github.com/TomBolger/PebbleMaps; the store build ignores them.
Each is one `pebblekit_launch_and_send` call with an empty message as the
ping. A string's `length` is its length in bytes.

## Search

Command 120 with the query in key 10004. The watch shows the results as
if the user had spoken the query. A search ends any navigation in
progress.

```
pebblekit_launch_and_send
{
  "app": "cce60bc9-e29f-4988-a83b-b4652291650d",
  "ping": [],
  "tuples": [
    {"key": 10000, "type": "int", "length": 4, "value": 120},
    {"key": 10004, "type": "string", "length": <byte length>, "value": "<query>"}
  ]
}
```

## Navigate

Command 121 with the destination in key 10004: the name of one of the
user's Pebble Maps favourites, a street address, a place name, or
`lat,lng`. The app finds the place, so there is no need to convert to
coordinates. Directions start straight away, replacing any navigation
already running. Key 10003 is the travel mode, optional: 0 drive, 1 walk,
2 bike, 3 transit; without it the favourite's mode applies, or else the
user's default mode from the watchapp's settings.

```
pebblekit_launch_and_send
{
  "app": "cce60bc9-e29f-4988-a83b-b4652291650d",
  "ping": [],
  "tuples": [
    {"key": 10000, "type": "int", "length": 4, "value": 121},
    {"key": 10004, "type": "string", "length": <byte length>, "value": "<destination>"},
    {"key": 10003, "type": "int", "length": 4, "value": <mode>}
  ]
}
```

An ACK means the app got the request; whether Google found the place
shows only on the watch.
