# Style guide

All text in this repository follows the European Commission's
[English Style Guide][dgt] (Directorate-General for Translation, DGT,
November 2025 edition) for English, and the matching Finnish conventions
for Finnish. This file is a compact summary of the rules that come up
here: in code comments, documentation, report output, the HTML profile
and the notes and highlights files. Section numbers refer to the DGT
guide; when something isn't covered here, the guide itself decides.

[dgt]: https://knowledge-centre-translation-interpretation.ec.europa.eu/sites/default/files/ckeditor5-files/styleguide_english_dgt_en.pdf

## Spelling and wording

- Irish/British spelling, with -ise rather than -ize: colour, organise,
  behaviour, metre, analyse (3.1–3.2).
- Sentence case for titles and headings: capitalise only the first word
  and proper nouns.
- Per cent is two words when written out (twenty per cent); with figures,
  use the sign (6.11).
- Spell out one to nine and use figures from 10, but use figures for
  measurements and when both occur together: 9 to 11, 5 km (6.1).

## Numbers

| Rule | English | Finnish |
| --- | --- | --- |
| Decimal marker (6.10) | point: 4.7 | comma: 4,7 |
| Thousands (6.5) | hard space: 15 000, also 3 000 | hard space: 15 000 |
| Per cent (6.11) | closed up: 25% | hard space: 25 % |
| Minus (7.13) | true minus sign (U+2212): −13 | the same |
| Missing value | en dash: – | the same |

Never use a comma as the thousands separator: 1 000 m, not 1,000 m. The
hard space is U+00A0.

## Units and ranges

- A hard space between a number and its unit or symbol: 5 km, 20 °C,
  300 m, 117 bpm, 676 W/m² (7.13).
- Proper symbols: °C (not degC), W/m² (not W/m2), ≥, ×.
- The forward slash means "per": km/h, beats/km (2.38).
- Ranges take a closed-up en dash and the unit once: 10–70 °C, 0–5%
  (Finnish 0–5 %). If the unit changes, space the dash: 100 kW – 40 MW
  (6.16). Written out, repeat the unit: between 10 °C and 70 °C (6.15).
- Durations: 5 h 03 min.

## Dates and times

| Rule | English | Finnish |
| --- | --- | --- |
| Date in running text (6.17) | 6 September 2026 | 6.9.2026 |
| Short date, in tables (6.17) | 6.9.2026, no leading zeros | 6.9.2026 |
| Time of day, 24-hour clock (6.26) | 09:30, leading zero and colon | klo 9.30 |
| Time span | 10:09–16:23 | klo 10.09–16.23 |

In code and data files (file names, cache keys, JSON), ISO 8601
(2026-09-06) stays, since it sorts correctly.

## Dashes

- En dash (–) for ranges and for joining related pairs, closed up:
  Lloret de Mar–Blanes, or spaced between longer items: Route: Lloret de
  Mar – Blanes – Tordera (3.29–3.31).
- Spaced en dash for a break in a sentence – like this – not a hyphen.

## Finnish

Finnish follows the Institute for the Languages of Finland's (Kotus)
recommendations, which match the EU's Finnish translation practice:
decimal comma, hard space between thousands and before %, °C and other
units, dates as 6.9.2026 and times as klo 9.30. Case endings go after a
colon on abbreviations and figures: 15 %:n, 1 km:n, 20 °C:seen. Foreign
place names are not inflected where avoidable (kohteessa Punta Ventosa).

## In the code

`WalkAnalyser.groovy` formats every number for the report through its
helpers (`num`, `unit`, `pct`, `range`, `clock`, `longDate`, `shortDate`,
`formatHours`), which apply these rules for the report language. Use them
rather than `String.format` for anything a person reads. Values meant to
be pasted into code (such as effort-curve anchors) stay in code notation
(`Locale.ROOT`).
