# ADR 0003 — Game UI recreation qualification against real reference screenshots

Status: accepted · Date: 2026-08-07

## Context

libgdx-ui-markup's claim is that declarative markup can faithfully express real game UIs. The
generic agentic-palisade benchmark used by libgdx-ui-harness is harness-specific and unrelated
to markup fidelity; this project needs its own qualification that is specific to its claim:
recreating well-made game UIs from markup and measuring how closely the recreation's structure
matches the real screenshot. Reference screenshots are third-party content (Steam store
screenshots are copyrighted by their publishers), so the corpus must be copyright-aware.

## Decision

A non-publishable `libgdx-ui-markup-qualification` module qualifies markup recreations against
real game UI screenshots.

1. **Corpus manifest, fetched at test time.** `corpus/manifest.json` pins each entry to a
   stable source URL (Steam CDN, which hosts permanent per-screenshot hashes) with a license
   note. Images are fetched at test time into a gitignored build cache and never redistributed;
   a fetch failure (offline, moved URL, oversized, undecodable) marks the entry
   `SKIPPED_REFERENCE`, never a failure. At least one entry must be scored or the test fails,
   so a silent no-op cannot pass.
2. **Recreations are plain markup.** Each entry's `corpus/*.xml` is a hand-authored markup
   recreation of the reference UI's layout, rendered by the real preview binary
   (`--frames --screenshot --exit`), not by a test-side builder.
3. **Structural, not pixel, comparison.** Both images are partitioned into an 80×45 cell grid;
   each cell's gray-level variance is classified as structured using the image's own variance
   histogram (mean + 0.75 standard deviation), which is robust to art style and font
   differences. The score is the Dice coefficient of the two structured-region masks after a
   one-cell dilation on both sides, giving a tolerance for scale and art differences while
   still measuring whether UI elements sit in the same regions.
4. **Measured thresholds.** Each entry's threshold is set from its measured baseline (about
   65% of the first measured score) and guards regressions: a recreation whose structure
   collapses or whose layout breaks fails CI. Scores below the reference's art-heavy regions
   are expected and reflected in honest baselines rather than gamed thresholds.
5. **CI.** A dedicated `qualification` job runs the module under Xvfb with network access and
   retains the bounded per-entry report (`dice`, cell counts, verdict, license) on failure.

## Consequences

- The qualification exercises the shipped product (the preview binary), the bounded CSS subset,
  and the dialect's layout attributes against real, recognizable game UIs.
- Copyrighted screenshots never enter the repository; provenance and license notes live in the
  manifest next to the pinned URLs.
- The three initial entries (Hades boon panel, Slay the Spire shop, Battle for Wesnoth
  gameplay) establish baselines of ~0.40, ~0.34, and ~0.19 dilated-Dice; future recreations
  and refinements should raise them, and thresholds move only with a measured improvement.
- The harness's agentic-palisade qualification is untouched and remains a libgdx-ui-harness
  concern.
