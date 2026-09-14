# TODO

## Investigate a secondary surface-data source

Some GR92 stages have a large share of track points where OpenStreetMap's
`highway` tag is present but `surface` is missing entirely — on
`gpx/GR92-etappi18.gpx`, roughly half the route fell into this gap. Both
`ElevationProfiler.groovy` and `RouteCalibrator.groovy` now infer `"ground"`
for `highway=track`/`path`/`footway`/`bridleway`/`steps` with no `surface`
tag, rather than leaving it `"unknown"` (see `inferSurfaceFromHighway` in
both files), which is a reasonable default but still only a guess.

Worth investigating:

- A secondary data source to confirm the actual surface where OSM is silent
  — e.g. satellite/aerial imagery classification, a regional trail
  database (Catalan/Spanish hiking federation data for the GR92 specifically),
  or manually curated overrides for known stages.
- Whether the blanket `"ground"` default is actually accurate across
  different terrain types (coastal path vs. inland mountain stages), or
  whether a smarter guess could use neighbouring matched ways' surface tags
  along the same route.
- Re-running `RouteCalibrator.groovy` against a recorded track for a stage
  with a high unknown-surface rate, to see whether the calibrated eta
  values from `gpx/GR92-etappi17.gpx` (which had much better OSM coverage)
  still hold up, or need adjusting for the `"ground"` fallback specifically.

## Recalibrate for rough/loose terrain

`RouteCalibrator.groovy`'s one calibration run so far (etappi17) had almost
no scree/sand/boulders and little gravel/rock, so those surfaces share the
same calibrated eta as ordinary dirt/gravel in the speed model. Running the
calibrator against a recorded track that covers genuinely rough or loose
terrain would let severe-loose surfaces be calibrated distinctly instead of
inheriting a value derived mostly from firmer trail.
