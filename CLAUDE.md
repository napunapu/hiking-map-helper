# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

This repository holds `ElevationProfiler.groovy`, a standalone CLI tool
that turns a GPX hiking route into a colour-coded HTML elevation profile
(see `README.md` for usage). GPX route files and generated map cache are
kept locally under `gpx/`, which is gitignored — not tracked in this
repository.

## Code style

- **Kotlin, Java and Groovy**: indent with 4 spaces. No tabs.
- **HTML and XML**: indent with 2 spaces. No tabs.

## Language and tone

- Use British English in all comments, READMEs and documentation (e.g. "colour", "organised", "behaviour").
- Use sentence case for titles and headings — capitalise only the first word and proper nouns.

## Markdown

All Markdown files must pass markdownlint (the ruleset used by the VSCode markdownlint extension):

- No trailing spaces.
- Blank lines before and after headings and fenced code blocks.
- ATX-style headings (`#`, `##`, ...), not Setext-style.
- Fenced code blocks always specify a language.
