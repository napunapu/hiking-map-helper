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
