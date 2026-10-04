# TODO

## Investigate a secondary surface-data source

`ElevationProfiler.groovy` now resolves surface/eta through a five-tier
fallback hierarchy (explicit `surface` tag, then `tracktype`, then
`smoothness`, then nearby natural landcover, then `highway` class - see
`resolveSurfaceAndEta` and "Trail surface via OpenStreetMap" in
`README.md`), which shrank the genuinely-unmatched share on
`gpx/GR92-etappi18.gpx` from roughly half the route to well under 1%. The
remaining gap is now mostly *inferred* rather than *unknown* - still only a
proxy for the real surface, not a measurement.

Worth investigating:

- A secondary data source to confirm the actual surface for the
  tracktype/smoothness/landcover/highway-inferred tiers — e.g.
  satellite/aerial imagery classification, a regional trail database
  (Catalan/Spanish hiking federation data for the GR92 specifically), or
  manually curated overrides for known stages.
- Whether the fixed per-tier eta values (e.g. `"inferred:track_unspecified"`
  at 1.20) hold up across different terrain types (coastal path vs. inland
  mountain stages), or need their own calibration pass.
- Re-running `RouteCalibrator.groovy` against a recorded track for a stage
  with a high inferred-surface rate, to see whether the calibrated eta
  values from `gpx/GR92-etappi17.gpx` (which had much better OSM coverage)
  still hold up.
- `RouteCalibrator.groovy` still reads the planned route's own GPX
  elevations, while `ElevationProfiler.groovy` now defaults to the IGN
  MDT05 terrain model; a future calibration run should use the same
  terrain-model profile (the current slope anchors held up on it within
  about 3% of recorded moving time, so this isn't urgent).
- `RouteCalibrator.groovy` still uses its own older, simpler surface
  classification (`inferSurfaceFromHighway`, a 3-bucket firm/standard/rough
  split) and was not updated to match `ElevationProfiler.groovy`'s new
  five-tier hierarchy — worth reconciling so a future calibration run is
  measuring the same surface model the profiler actually uses.

## Recalibrate for rough/loose terrain

`RouteCalibrator.groovy`'s one calibration run so far (etappi17) had almost
no scree/sand/boulders and little gravel/rock, so those surfaces share the
same calibrated eta as ordinary dirt/gravel in the speed model. Running the
calibrator against a recorded track that covers genuinely rough or loose
terrain would let severe-loose surfaces be calibrated distinctly instead of
inheriting a value derived mostly from firmer trail.

## Recorded walks in RouteCalibrator

`WalkAnalyser.groovy` now reads FIT files (Garmin FIT SDK) with
full-precision GPX altitude, the barometer start correction and heart
rate. `RouteCalibrator.groovy` still takes a recorded GPX and computes
distance from GPS positions; giving it the same FIT loading would let
calibration use the watch's distance and the corrected altitude.

## Decide on the thermal pace penalty

Against 10 recorded walks (stage 19 excluded as a deliberate all-out
effort), the thermal pace penalty made moving-time predictions worse (mean
+9% against +1% without it), and neither peak air temperature nor full-sun
felt heat (UTCI) along the route correlated with actual pace (r = 0.00 and
0.01). Removing or weakening it is pending a decision.

## Notes on how each walk felt

For every recorded walk with FIT and GPX files, keep a short note (a
sentence or a few) on how the walk felt - tiredness, heat, knees on the
descents, pace, anything unusual - and show it in `WalkAnalyser.groovy`'s
report next to the measured figures. Subjective effort is the missing
check on the heart-rate and flat equivalent figures, and it would flag
walks to treat as outliers (like the all-out 15 September walk). One
option is a plain-text or Markdown note next to each FIT file with the
same name, kept under the gitignored `gpx/real_world/` with the walks.
