# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

This repository holds `ElevationProfiler.groovy`, a standalone CLI tool
that turns a GPX hiking route into a colour-coded HTML elevation profile
(see `README.md` for usage), plus two standalone companions:
`RouteCalibrator.groovy` (calibrates the duration model against a recorded
track) and `WalkAnalyser.groovy` (analyses recorded Apple Watch walks from
FIT and GPX exports). Shared classes (`TerrainModel`, `ThermalComfort`) are
mirrored between the scripts rather than shared, so each runs on its own;
keep the copies identical. GPX route files, recorded walks
(`gpx/real_world/`) and generated map cache are kept locally under `gpx/`,
which is gitignored — not tracked in this repository.

## Code style

- **Kotlin, Java and Groovy**: indent with 4 spaces. No tabs.
- **HTML and XML**: indent with 2 spaces. No tabs.

## Language and tone

- Always follow the European Commission's DGT English Style Guide; the rules that matter here are summarised in `docs/style-guide.md` (numbers, units, dates, times, dashes, and the Finnish equivalents). Read it before writing user-facing text.
- Use British English in all comments, READMEs and documentation (e.g. "colour", "organised", "behaviour").
- `WalkAnalyser.groovy` reports in English and Finnish (`--language`): every user-facing string goes in both `MESSAGES` maps, and numbers go through its formatting helpers.
- Use sentence case for titles and headings — capitalise only the first word and proper nouns.

## Markdown

All Markdown files must pass markdownlint (the ruleset used by the VSCode markdownlint extension):

- No trailing spaces.
- Blank lines before and after headings and fenced code blocks.
- ATX-style headings (`#`, `##`, ...), not Setext-style.
- Fenced code blocks always specify a language.
