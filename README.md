# Elevation profiler

A standalone Groovy script that turns a GPX hiking route into a colour-coded
elevation profile, with distance, ascent/descent, two independent difficulty
ratings, a hydration recommendation, trail surface data pulled from
OpenStreetMap, and a simulated weather/solar-exposure model driving both
pace and hydration.

## Requirements

- [Groovy](https://groovy-lang.org/) on your `PATH`.
- Internet access on first run, so `@Grab` can download
  [picocli](https://picocli.info/) (cached afterwards), and to query the
  Overpass API (trail surface) and Open-Meteo API (weather/solar) — neither
  needs an API key; see below.

## Usage

```sh
groovy ElevationProfiler.groovy <input.gpx> [-w <window>] [-o <output.html>] [-t <tempC>] [-e <exposure>] [-s <speed>] [--start-time <HH:mm>] [--date <yyyy-MM-dd>] [--break <interval:duration>] [--no-cache]
```

| Option | Description | Default |
| --- | --- | --- |
| `-w`, `--window` | Moving-average smoothing window, in points | `5` |
| `-o`, `--output` | Output HTML file path | `<input>-profile.html` |
| `-t`, `--temp` | Fallback ambient temperature in °C, used only if live weather data is unavailable | `20.0` |
| `-e`, `--exposure` | Forces a *constant* shading factor (`1.0` shaded, `1.1` fully exposed) for both the static hydration model and the dynamic solar model, overriding the dynamic model's per-segment computation. Omit it to let the dynamic model compute exposure per segment | `1.0` |
| `-s`, `--speed` | Base flat walking speed in km/h, for the effort-adjusted duration model | `4.59` |
| `--start-time` | Planned hike start time, `HH:mm` 24h — anchors the weather/solar simulation | `07:00` |
| `--date` | Planned hike date, `yyyy-MM-dd` — any past date (queries the Archive API) or up to 16 days ahead (Forecast API); further ahead than that disables the weather/solar simulation for that run, falling back to `-t`/`--temp` | today |
| `--break` | Rest break cadence as `interval:duration` in minutes, e.g. `60:6` for a 6 min pause every 60 min of *moving* time; `0:0` disables breaks | `60:5` |
| `--no-cache` | Force re-querying the Overpass and Open-Meteo APIs even if cached responses exist | `false` |
| `-h`, `--help` | Show usage and exit | |
| `-V`, `--version` | Show version and exit | |

Neither `-s`/`--speed` nor `--start-time` change what's requested from
Overpass or Open-Meteo — those affect only where in the *already-fetched*
hourly weather the simulated clock begins (`--start-time`) and how fast it
advances through it (`-s`). `--date` is the exception: whether it's today
or in the future versus in the past decides which Open-Meteo endpoint
(Forecast or Archive) actually gets queried — see "Dynamic weather & solar
exposure" below.

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
   `slope_factor` and `eta` are both **empirically calibrated** against a
   real recorded GPX track (see "Calibrating the duration model" below),
   rather than derived purely from theory:
   - `slope_factor` is a piecewise-linear curve through five measured
     anchor points (grade → observed speed), replacing
     [Tobler's hiking function](https://en.wikipedia.org/wiki/Tobler%27s_hiking_function).
     Tobler predicts an exponential fall-off in both directions from a
     -5% peak; the calibration track showed real walking speed falling
     away far less sharply than that — a moderate descent was even
     slightly *faster* than flat pace. Beyond the outermost anchor
     (steeper than +25.8% or -16.5%) the factor holds flat rather than
     extrapolating further, since no calibration data exists past those
     grades.
   - `eta` here is a separate, calibrated speed-only terrain factor
     (`speedTerrainFactorForSurface` in the code) — 1.0 for firm surfaces
     (paved/compacted), 1.05 for everything else. This is **not** the
     same `eta` used by the Trail strain model below: that one keeps its
     original, wider 1.0-1.9 scale, since its own reference-route
     calibration and thresholds are tuned against it, and recalibrating
     it against pace data would silently flatten the strain score's
     terrain sensitivity too.

   Unlike DIN's fixed 800 m/h descent rate, this explicitly varies pace
   with rough or technical terrain rather than assuming descending is
   always fast. `base_speed` defaults to 4.59 km/h (also calibrated) and
   is configurable via `-s`/`--speed`. This speed is then further reduced
   by a **thermal pace penalty** from the simulated temperature and solar
   radiation at the moment each segment is actually reached — see
   "Dynamic weather & solar exposure" below.

Hydration need is computed under both models (duration × the calibrated
Zone 2 burn rate, plus the 0.5 L reserve), and the console/HTML both show
the difference between the two duration estimates. This is separate from
the fully dynamic, per-segment water total described below.

The console additionally breaks the "OSM Terrain & Grade Adjusted" figure
into two: *moving time (terrain adjusted)* is terrain/grade alone, with no
thermal penalty; *moving time (thermally adjusted)* adds the weather-driven
slowdown on top — the latter is what the HTML tile and popup show, since
it's the more complete estimate. A third figure, **total elapsed
(door-to-door)**, adds scheduled rest breaks on top of that — see
"Scheduled rest breaks" below — and gets its own summary tile.

In the HTML, the "OSM Terrain & Grade Adjusted" tile's popup has a base
speed slider. Since terrain/grade alone scale duration exactly
proportionally to speed, the browser rescales the server-computed duration
rather than re-integrating every segment — but this is now an
**approximation**, since the thermal penalty technically depends on *when*
(clock time) a faster or slower pace reaches each segment. In practice the
weather changes slowly enough over a same-day hike that this is a small
effect; the start time, finish time, break schedule and weather figures in
that popup reflect the original command-line `-s` value, not the slider.
See "Interactive sliders" below for how this and the water slider interact
and persist.

## Calibrating the duration model

`RouteCalibrator.groovy`, a separate standalone script in this repository,
compares a planned route GPX against a recorded real-world GPX track (e.g.
an Apple Watch export) and derives calibrated values for `base_speed`,
`slope_factor` and `eta` via a staged, three-step residual solver:

1. **Baseline flat speed** — the median recorded speed on firm-surface,
   -3% to +3% grade segments, locked as `v_base`.
2. **Slope response** — median recorded speed per gradient bracket, on
   firm terrain only, with a hard descent-speed ceiling (foot placement,
   not metabolic cost, limits downhill cadence) — locked as a
   piecewise-linear curve relative to `v_base`.
3. **Surface friction (eta)** — with `v_base` and the slope curve now
   fixed, the remaining speed gap on non-firm terrain is solved as a pure
   residual, clamped to a plausible 1.05-1.60 range.

Each stage is locked against matched, on-route, moving segments only,
excluding any detected route deviation (a real course change, not a model
error) and stationary pauses. Run `groovy RouteCalibrator.groovy --help`
for its options; it reports its own diagnostic breakdown (speed by
gradient/surface bracket, top overestimation sections, before/after
verification) rather than writing a profile.

The current defaults in `ElevationProfiler.groovy` (`base_speed` = 4.59
km/h, the five slope-response anchors, and the calibrated 1.0/1.05 `eta`
values) come from running this against one recorded GR92 stage and are
hardcoded, not re-derived at run time — recalibrating against further
recorded tracks (ideally covering more surface types, since this one
track had little rough/loose or scree terrain) would refine them further.

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
  is inferred as `"paved"`. If `highway` is instead a class that's almost
  always unpaved in practice (track, path, footway, bridleway, steps),
  `surface` is inferred as `"ground"` rather than left unknown — OSM
  mappers frequently tag a rural trail's existence without ever adding a
  `surface` tag, and some GR92 stages have plenty of this gap; see
  `TODO.md` for improving on this heuristic with a secondary data source.
- **No match, or the request fails**: if nothing is found within 30 m, or
  Overpass is unreachable/times out/rate-limits and no cache exists, the
  tool warns and falls back to `surface: "unknown"`, `sac_scale: "none"` for
  every point — the run always completes.

## Dynamic weather & solar exposure

The tool fetches an hourly weather timeline (temperature, direct solar
radiation, cloud cover) from the free [Open-Meteo](https://open-meteo.com/)
Forecast or Archive API — no key required — for this route's centroid, and
simulates walking through it minute by minute from `--date`/`--start-time`
(default: today, 07:00), rather than assuming one flat temperature for the
whole hike.

- **Forecast vs Archive**: `--date` today or in the future queries the
  [Forecast API](https://open-meteo.com/en/docs), covering today plus the
  next 16 days in one request; `--date` in the past queries the
  [Archive API](https://open-meteo.com/en/docs/historical-weather-api)
  instead, for that single day. Both return the same `hourly.time` /
  `temperature_2m` / `direct_radiation` / `cloud_cover` shape, so the rest
  of the simulation doesn't need to know which one answered.
- **Caching**: only the Archive API is cached, since a past date's weather
  is fixed and permanently reusable. Each historic date gets its own file,
  `maps/<gpxBaseName>.weather.<yyyy-MM-dd>.json` — fetching one historic
  date doesn't evict another you looked up earlier; each stays cached and
  is reused if you come back to it (only if it actually covers the
  requested `--date`), and `--no-cache` forces a fresh request regardless.
  The Forecast API is **never** cached — a forecast is provisional and can
  change between two runs on the same day, or as the target date gets
  closer, so every run against a today-or-future `--date` fetches live.
  If the request fails and no cache exists, the tool falls back to a flat
  `-t`/`--temp` value with no solar radiation model, and says so.
- **Date range**: `--date` can be any past date or up to 16 days in the
  future. Dates further in the future than that aren't forecast yet, which
  disables the weather/solar simulation for that run with a clear warning,
  falling back to `-t`/`--temp`.
- **Simulated clock**: each segment's *actual* time-of-day depends on how
  long the hike has taken so far, which depends on speed, terrain and the
  weather already encountered — so a slow, technical section pushes later
  segments into hotter, more sun-exposed hours than a flat-terrain estimate
  would predict.
- **Solar exposure factor**: direct radiation below 100 W/m² (dark/twilight/
  heavy overcast) applies no increase; 100-500 W/m² scales up to +10%;
  above 500 W/m² (intense Mediterranean sun) adds up to another +10%. This
  is then damped by the matched OSM way's tags: fully covered
  (`tunnel=yes`/`covered=yes`) forces it back to 1.0x; forest/woodland
  (`natural=wood`/`landuse=forest`, or a rough `tracktype=grade4`/`grade5`
  track) halves the increase; everything else (open ridges, roads, tracks)
  gets the full factor. Pass `-e`/`--exposure` to force a constant factor
  instead of computing it dynamically.
- **Known limitation**: `natural=wood`/`landuse=forest` are normally OSM
  *area* tags on separate polygon ways, not tags on the highway way itself.
  This tool only checks the nearest matched *highway* way's own tags (which
  occasionally does carry them, and reliably catches `tracktype=grade4/5`),
  so genuine forest-canopy shading is under-detected rather than requiring
  full point-in-polygon matching against woodland boundaries.
- **Thermal pace penalty**: `effective_heat = temp + (radiation / 1000) * 2`
  combines air temperature with radiant heat load; pace scales down by
  0.8% per degree above 15°C effective heat, floored at 65% of normal
  speed, feeding directly into the "OSM Terrain & Grade Adjusted" duration.
- **Dynamic hydration**: each segment's water need scales with its own
  local temperature and exposure factor, then sums across the whole route
  (plus a fixed 0.5 L reserve) — shown alongside the flat static estimate
  in the console output and the Duration popup. This active-moving total is
  reported separately from break/resting consumption — see below.

## Scheduled rest breaks

By default (`--break 60:5`), the simulation inserts a 5-minute rest every
60 minutes of *moving* time — pass `--break 0:0` to disable, or e.g.
`--break 45:10` for a 10-minute break every 45 minutes.

- **Dual clock**: moving time (pure locomotion) and elapsed/wall-clock time
  (moving *plus* pauses) are tracked separately. Breaks are triggered by
  moving time — a slower pace doesn't make breaks more frequent in
  distance/time terms, it just means more of the route is covered before
  each one.
- **Weather shift**: every break advances the wall clock (but not moving
  time) by the break duration, and every subsequent segment's weather
  lookup uses that delayed wall clock — so enough breaks can genuinely
  walk you into a hotter part of the day than a straight-through hike
  would reach. The console/HTML note the shift in peak temperature this
  causes, if any (some routes already reach the day's peak regardless of
  breaks, in which case there's nothing left to shift).
- **Resting hydration**: `resting_hourly_rate = (0.15 + max(0, temp - 15) *
  0.015) * effective_exposure` — lower than the moving rate, since you're
  not exerting, but still scales with heat and sun exposure at the pause
  location. Reported separately from active-moving consumption, and
  included in the recommended total carry.
- **Visualised** as dashed purple vertical markers along the elevation
  profile (hover for the break's time, duration and distance marker).

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
- A thin solar intensity band runs along the top of the chart: dark
  (shade/twilight), amber (partial sun) or orange (full sun), giving an
  at-a-glance sense of where along the route — and at what simulated
  time of day — the sun exposure is highest.
- Dashed purple vertical markers show each scheduled rest break (hover for
  its time, duration and distance).
- Hovering over the profile shows a crosshair at that point, plus a tooltip
  with distance, elevation, instantaneous gradient, surface type, SAC trail
  scale, the local terrain multiplier (eta), the relative strain factor
  (e.g. "1.4x flat equivalent"), the simulated (break-delayed) clock time,
  ambient temperature, direct solar radiation, the dynamic exposure
  multiplier (e.g. "1.18x (Full sun)") and the resulting thermal pace
  penalty.
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
