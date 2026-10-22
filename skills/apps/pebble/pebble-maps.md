# Pebble Maps (Pebble watchapp)

UUID `cce60bc9-e29f-4988-a83b-b4652291650d`. Google Maps search and
turn-by-turn directions on the watch. Driving it from the phone needs the
fork github.com/ealang/TomBolger-PebbleMaps, which adds the API below to
github.com/TomBolger/PebbleMaps; the store version ignores these
messages. Verified on device 2026-10-20.

Two things it can do, each one `pebblekit_launch_and_send` call with an
empty message as the ping: show search results, or start navigation.

## Search

`cmd` 120 with the query in `text` (key 10004). The watch shows the
results as if the user had spoken the query. A search ends any navigation
in progress.

```json
{
  "app": "cce60bc9-e29f-4988-a83b-b4652291650d",
  "ping": [],
  "tuples": [
    {"key": 10000, "type": "int", "length": 4, "value": 120},
    {"key": 10004, "type": "string", "length": 6, "value": "coffee"}
  ]
}
```

## Navigate

`cmd` 121 with the destination in `text`: the name of one of the user's
Pebble Maps favorites ("Home", "Work"), a street address, a place name
("Empire State Building"), or `lat,lng` — the app finds the place, so
there is no need to convert to coordinates. Directions start straight
away, replacing any navigation already running. `mode` (key 10003) is
optional — 0 drive, 1 walk, 2 bike, 3 transit; without it the
favorite's mode, or else the user's default mode from Settings.

```json
{
  "app": "cce60bc9-e29f-4988-a83b-b4652291650d",
  "ping": [],
  "tuples": [
    {"key": 10000, "type": "int", "length": 4, "value": 121},
    {"key": 10004, "type": "string", "length": 32, "value": "20 W 34th St, New York, NY 10001"},
    {"key": 10003, "type": "int", "length": 4, "value": 1}
  ]
}
```

A string's `length` is its length in bytes. An ACK means the app got the
request; whether Google found the place shows only on the watch.
