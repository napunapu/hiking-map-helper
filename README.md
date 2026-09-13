# Elevation profiler

A standalone Groovy script that turns a GPX hiking route into a colour-coded
elevation profile, with distance, ascent/descent, two independent difficulty
ratings, a hydration recommendation and trail surface data pulled from
OpenStreetMap.

## Requirements

- [Groovy](https://groovy-lang.org/) on your `PATH`.
- Internet access on first run, so `@Grab` can download
  [picocli](https://picocli.info/) (cached afterwards), and to query the
  Overpass API for trail surface data (no API key needed; see below).

## Usage

```sh
groovy ElevationProfiler.groovy <input.gpx> [-w <window>] [-o <output.html>] [-t <tempC>] [-e <exposure>] [-s <speed>] [--no-cache]
```

| Option | Description | Default |
| --- | --- | --- |
| `-w`, `--window` | Moving-average smoothing window, in points | `5` |
| `-o`, `--output` | Output HTML file path | `<input>-profile.html` |
| `-t`, `--temp` | Forecast max ambient temperature in shade, in °C | `20.0` |
| `-e`, `--exposure` | Route shading factor: `1.0` forest/partial shade, `1.1` fully exposed ridges | `1.0` |
| `-s`, `--speed` | Base flat walking speed in km/h, for the effort-adjusted duration model | `4.0` |
| `--no-cache` | Force re-querying the Overpass API even if a cached response exists | `false` |
| `-h`, `--help` | Show usage and exit | |
| `-V`, `--version` | Show version and exit | |

Example:

```sh
groovy ElevationProfiler.groovy gpx/my-route.gpx
```

This prints a summary to the console and writes
`gpx/my-route-profile.html` next to the input file. `gpx/` is a
suggested, gitignored place to keep your own route files and their
generated map cache (see below) — they're personal data, not part of the
tool itself, so nothing under it is tracked or pushed.

## What it computes

- **Distance**: cumulative 2D distance between track points using the
  Haversine formula.
- **Smoothing**: a moving average over the configured window removes
  barometric/GPS elevation jitter before gradients are calculated.
- **Gradient**: instantaneous slope, measured over at least a 10 m baseline
  distance rather than between raw consecutive points, since GPS fixes can
  sit only centimetres apart. Any residual reading over 100% (physically
  implausible on foot) is capped, and the difficulty explanation below notes
  when that happened.
- **Ascent / descent**: summed from the smoothed elevation profile.

## Duration models

Two independent duration estimates are shown side by side, since they model
moving time very differently:

1. **Standard DIN 33466** — the fixed hiking-time standard (4 km/h
   horizontal, 400 m/h ascent, 800 m/h descent; the larger of the
   horizontal/vertical time plus half of the smaller). Always uses the
   standard 4 km/h regardless of `-s`/`--speed`, since it's a fixed
   reference formula, not a tunable model.
2. **OSM Terrain & Grade Adjusted** — integrates a walking speed per
   segment: `v_seg = base_speed * slope_factor / (eta * T-factor)`, where
   `slope_factor` comes from [Tobler's hiking function](https://en.wikipedia.org/wiki/Tobler%27s_hiking_function)
   (peaking on a gentle -5% downhill, then falling away on both steeper
   climbs *and* steeper descents). Unlike DIN's fixed 800 m/h descent rate,
   this explicitly slows down on rough or technical descents rather than
   assuming descending is always fast — matching real foot-placement
   braking rather than a pure energy-cost model (which would otherwise
   predict speeding up on a downhill, since it costs less energy). `eta`
   and the technical factor are the same per-point values used by the
   Trail strain model below. `base_speed` defaults to 4.0 km/h and is
   configurable via `-s`/`--speed`.

Hydration need is computed under both models (duration × the calibrated
Zone 2 burn rate, plus the 0.5 L reserve), and the console/HTML both show
the difference between the two duration estimates.

In the HTML, the "OSM Terrain & Grade Adjusted" tile's popup has a base
speed slider (since the model is exactly proportional to it, the browser
just rescales the server-computed duration rather than re-integrating every
segment). See "Interactive sliders" below for how this and the water
slider interact and persist.

## Difficulty ratings

The tool reports two independent ratings, since no single number captures
both "how steep is the worst point" and "how much total effort is this
route":

1. **Difficulty** (Easy / Moderate / Difficult) — based on the steepest
   smoothed gradient and the ascent rate (m/km). This flags short, sharp
   climbs even on an otherwise easy route.
2. **Effort (Shenandoah)** — Shenandoah National Park's hiking-difficulty
   formula, `sqrt(ascent_ft x 2 x distance_mi)`, rated Easiest / Moderate /
   Moderately strenuous / Strenuous / Very strenuous. This captures overall
   exertion from total climbing and distance combined, and can rate a long,
   gentle route harder than a short, steep one.

In the generated HTML, both ratings are clickable and open a popup
explaining exactly why that tier was reached, with the underlying numbers
and thresholds.

## Water intake recommendation

A third clickable tile, "Water", gives a general hydration guideline for a
healthy adult, using a calibrated Zone 2 endurance model:

- **Burn rate**: 0.35 L/hour at or below 15°C, rising linearly by
  0.02 L/hour per degree above that (`hourly_rate = 0.35 + max(0, temp -
  15) * 0.02`).
- **Exposure**: the burn rate is multiplied by the route's shading factor
  (`-e`/`--exposure`, 1.0 shaded / 1.1 fully exposed).
- **Consumption**: `duration_hours * active_hourly_rate`, using the DIN
  33466 moving-time estimate — duration already reflects this route's
  vertical effort, so no separate difficulty multiplier is applied on top.
- **Recommended carry**: consumption plus a fixed 0.5 L safety reserve,
  rounded to one decimal place.

Since how much water you need depends heavily on how hot it is, the popup
also shows:

- A small bar chart of recommended carry at a spread of temperatures
  (10-40°C), so you can read off a figure for whatever your forecast says
  without needing a live weather lookup.
- A slider for the day's max forecast temperature **in the shade**, plus a
  checkbox for a fully exposed route, both updating the readout (and the
  chart) live as you adjust them.

This is general guidance, not personalised medical advice — individual
needs vary with body size, fitness and health.

## Interactive sliders

The temperature/exposure slider (Water tile) and the base-speed slider
(Duration tile) are linked and persisted:

- Changing either slider updates its own popup **and** the corresponding
  header tile immediately — you don't need to keep the popup open to see
  the new figure.
- The two are one-directionally linked: temperature/exposure feed into the
  hourly hydration rate, which the duration popup's hydration-need figures
  also use, so changing temperature updates those too. Changing base speed
  only affects duration/pace, not the Water tile.
- All three settings (temperature, exposure, base speed) are saved to the
  browser's `localStorage` on every change, and restored automatically the
  next time the same report file is opened in the same browser — reopening
  it shows your last-used settings rather than the defaults it was
  generated with. This is per-browser, per-file storage: it doesn't sync
  anywhere, and re-running the script overwrites the file's content but
  not a browser's already-stored preferences for it.

## Trail surface via OpenStreetMap

The tool fetches every navigable `highway` way in the route's bounding box
(padded by 0.005° / ~500 m on each side) from the public
[Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) — no API
key required — and geometrically snaps each GPX point to the nearest way to
read its `surface`, `sac_scale`, `tracktype` and `highway` tags. This lets
the tool know, for example, that a descent is on loose gravel rather than
pavement.

- **Caching**: the raw Overpass response is saved as
  `maps/<gpxBaseName>.osm.json` (created next to the input file). On the
  next run, if that file exists it is loaded directly and the API is not
  called again — pass `--no-cache` to force a fresh request. The `maps/`
  folder is gitignored: it's large, regeneratable, third-party-derived
  cache data, not source data worth tracking or pushing to a remote.
- **Snapping**: for each GPX point, every candidate way segment within its
  bounding box is checked using a local planar projection (accurate at this
  scale, and far cheaper than repeated great-circle math) to find the
  closest point-to-segment distance. If the nearest segment is within 30 m,
  its tags are used; if the `surface` tag itself is missing but `highway` is
  a standard paved class (residential, primary, secondary, ...), `surface`
  is inferred as `"paved"`.
- **No match, or the request fails**: if nothing is found within 30 m, or
  Overpass is unreachable/times out/rate-limits and no cache exists, the
  tool warns and falls back to `surface: "unknown"`, `sac_scale: "none"` for
  every point — the run always completes.

## Mechanical descent strain

A steep, loose-surfaced descent loads the quads and knees eccentrically
(braking strain) far more than the same gradient on a smooth path. The
"Steep descent" tile sums the distance of every descent steeper than -15%,
split by surface:

- **Smooth**: paved or asphalt.
- **Rough**: gravel, ground, path, unpaved, or unknown (i.e. anything that
  isn't a hard, even surface) — everything is counted as rough when no OSM
  match is available.

## Trail strain model

A fourth clickable tile, "Trail strain", combines terrain and technical
difficulty with gradient into a single 0-100 score, separating *metabolic*
cost (how tiring) from *biomechanical* cost (how jarring):

- **Terrain factor (eta)**: how much harder a surface is to move over than
  firm pavement — 1.0 for paved/asphalt/concrete, 1.1 for compacted/fine
  gravel, 1.25 for dirt/earth/ground/grass/path, 1.5 for gravel/unpaved/
  stones/rock, 1.9 for scree/sand/boulders, 1.2 as a fallback for anything
  unrecognised.
- **Technical factor**: from the OSM `sac_scale` tag — 1.0 for
  hiking/T1/none, 1.15 for mountain_hiking/T2, 1.35 for
  demanding_mountain_hiking/T3, 1.6 for alpine_hiking/T4 and above.
- **Metabolic cost**: [Minetti's polynomial approximation](https://en.wikipedia.org/wiki/Locomotion_energetics)
  of energy cost per unit distance as a function of gradient, normalised so
  flat pavement costs exactly `1.0` ("as costly as walking flat ground"),
  then scaled by the terrain and technical factors. Summed and divided by
  1000, this gives the **effort distance**: the equivalent flat-paved
  distance this route actually costs to walk.
- **Eccentric braking strain**: on any segment steeper than -10%, an
  additional `(|grade| / 10%)²` penalty (also scaled by terrain/technical
  factor) models the extra quad/knee loading from braking on a steep
  descent — this grows quickly, since braking strain compounds with both
  steepness and rough footing.
- **Composite score**: `effort distance + braking index/1000`, scaled so a
  reference 20 km / 500 m route on flat T1 pavement lands at 50/100 — so a
  score meaningfully above 50 indicates a route that's harder, in this
  combined sense, than a "standard" 20 km day out.

The tile's popup also lists the surface breakdown by % of distance, and the
high-strain descent distance (descents steeper than -15% on a rough or
loose surface, i.e. eta >= 1.25).

## Visual output

The HTML file contains a self-contained, responsive SVG elevation profile:

- A "Colour by" toggle switches the profile between two colouring modes:
  - **Gradient**: steep/braking descent below -15% (dark purple), gentle
    descent -15% to 0% (light cyan), flat/mild 0-6% (green), moderate climb
    6-12% (yellow), steep climb 12-20% (orange), severe climb above 20%
    (red).
  - **Strain intensity**: the combined metabolic + braking strain at each
    point, from low (cyan) through flat-equivalent (green), moderate
    (yellow), high (orange) to extreme (dark red) — this can highlight
    rough, technical descents that a pure-gradient view would just show as
    "steep".
- Hovering over the profile shows a crosshair at that point, plus a tooltip
  with distance, elevation, instantaneous gradient, surface type, SAC trail
  scale, the local terrain multiplier (eta) and the relative strain factor
  (e.g. "1.4x flat equivalent").
- No external assets are loaded; the file can be opened directly in a
  browser or shared as-is.

## A note on data quality

Route-planning and recording tools occasionally export a handful of
consecutive track points at (almost) the same coordinates while elevation
keeps changing, which produces a physically impossible gradient spike. The
script guards against this by capping implausible readings and disclosing
it in the difficulty explanation, but it cannot fix bad source coordinates
on its own — see the GPX file's origin if a route's difficulty numbers look
off.
