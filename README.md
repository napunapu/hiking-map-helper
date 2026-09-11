# Elevation profiler

A standalone Groovy script that turns a GPX hiking route into a colour-coded
elevation profile, with distance, ascent/descent and two independent
difficulty ratings.

## Requirements

- [Groovy](https://groovy-lang.org/) on your `PATH`.
- Internet access on first run, so `@Grab` can download
  [picocli](https://picocli.info/) (cached afterwards).

## Usage

```sh
groovy ElevationProfiler.groovy <input.gpx> [-w <window>] [-o <output.html>] [-t <tempC>] [-e <exposure>]
```

| Option | Description | Default |
| --- | --- | --- |
| `-w`, `--window` | Moving-average smoothing window, in points | `5` |
| `-o`, `--output` | Output HTML file path | `<input>-profile.html` |
| `-t`, `--temp` | Forecast max ambient temperature in shade, in °C | `20.0` |
| `-e`, `--exposure` | Route shading factor: `1.0` forest/partial shade, `1.1` fully exposed ridges | `1.0` |
| `-h`, `--help` | Show usage and exit | |
| `-V`, `--version` | Show version and exit | |

Example:

```sh
groovy ElevationProfiler.groovy GR92-etappi16.gpx
```

This prints a summary to the console and writes
`GR92-etappi16-profile.html` next to the input file.

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
- **Estimated duration**: the DIN 33466 hiking formula (4 km/h horizontal,
  400 m/h ascent, 800 m/h descent; the larger of the horizontal/vertical
  time plus half of the smaller).

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

## Visual output

The HTML file contains a self-contained SVG elevation profile:

- The area under the curve is colour-coded by slope gradient — downhill
  (blue), flat/gentle 0-6% (green), moderate 6-12% (yellow), steep 12-20%
  (orange), very steep >20% (red).
- Hovering over the profile shows distance, elevation and grade at that
  point.
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
