# ADR 0003 — Game UI recreation qualification against real reference screenshots

Status: accepted · Date: 2026-08-07 (updated 2026-08-08 for multi-signal fidelity)

## Context

libgdx-ui-markup's claim is that declarative markup can faithfully express real game UIs. The
generic agentic-palisade benchmark used by libgdx-ui-harness is harness-specific and unrelated
to markup fidelity; this project needs its own qualification that is specific to its claim:
recreating well-made game UIs from markup and measuring how closely the recreation's structure
matches the real screenshot. Reference screenshots are third-party content (Steam store
screenshots are copyrighted by their publishers), so the corpus must be copyright-aware.

The coarse-only pass gate measured dilated-region Dice around 0.186–0.396 with low per-example
thresholds, which proved coarse region overlap but could not detect typography, color,
layering, or fine-layout regressions (issue #27). The gate is now a deterministic multi-signal
fidelity score with independent geometry, color, and detail components calibrated from
positive and deliberate-negative fixtures.

## Decision

A non-publishable `libgdx-ui-markup-qualification` module qualifies markup recreations against
real game UI screenshots.

1. **Corpus manifest with owned and remote references.** `corpus/manifest.json` pins each
   entry to either a committed `referenceFile` (fully owned, no network) or a stable remote
   `sourceUrl` (Steam CDN, which hosts permanent per-screenshot hashes) with a license note.
   Remote entries are fetched at test time over pinned TLS into a bounded per-run in-memory
   cache, authenticated against the committed SHA-256/byte/media-type identity, and never
   redistributed. A fetch failure marks the entry `SKIPPED_REFERENCE`, lenient locally but a
   hard failure under `-PstrictQualification=true` (set in CI). At least one entry must be
   scored or the test fails.
2. **Recreations are plain markup.** Each entry's `corpus/*.xml` is a hand-authored markup
   recreation of the reference UI, rendered by the real preview binary
   (`--frames --screenshot --exit`) with that entry's palette override
   (`corpus/<id>-palette.json`, consumed by the default skin via the `markup.skin.palette`
   system property), not by a test-side builder. The preview writes upright screenshots (the
   OpenGL framebuffer capture is Y-flipped before the PNG is written).
3. **Multi-signal fidelity, not pixel matching.** Both images are decoded through the bounded
   normalized-image path (≤1920 px per side) and scored by three deterministic components
   over fixed grids, plus a coarse diagnostic:
   - **Geometry** — an 80×45 grid of Sobel-gradient cell energies, classified per image by
     its own mean + 0.75σ. Each cell that is edge-structured in both images contributes its
     directed-gradient orientation similarity (1 − ½·TV over the four gradient-quadrant
     histograms, pixels with magnitude ≥ 16). The score is the orientation-weighted Dice
     `2·Σ orientationSim / (cellsA + cellsB)`; both-empty masks score 1, one-sided 0. The
     orientation weighting rejects a vertical flip (mirrored gradient directions) and the
     positional grid rejects translation and uniform scale.
   - **Color** — 4-bit-per-channel RGB histogram (4096 bins, primitive int array);
     `Σ min(hA, hB) / max(totalA, totalB)`. A hue-rotated or re-paletted recreation drops
     without any geometric dependence.
   - **Detail** — half grid-local structural fidelity over reference-detail cells
     (unthresholded gradient-energy ratio `min/max` and sharp-edge (≥64) orientation
     similarity; reference-blank cells excluded so a blur cannot raise the average by erasing
     spurious recreation detail) and half the global gradient-energy ratio `min/max` of the
     whole images (box blur is an averaging operator, so it monotonically reduces total
     high-frequency energy and can never be rewarded). Blank-blank cells score 1, one-sided 0.
   - **Coarse layout** — the legacy dilated-region Dice, diagnostic only, never gates.
4. **Calibrated thresholds from positives and deliberate negatives, no human tuning.** Each
   entry's render is measured (the positive) together with its deterministic deliberate
   negatives: vertical flip, fixed translation (192×108 px), 120° hue rotation, box blur
   (radius 8, channels averaged independently — summing packed ARGB integers would corrupt
   colors and inflate gradient energy), and uniform 0.75 scale anchored at the top-left.
   Each transformation is a negative for the failure mode it exhibits and only contributes to
   that component's calibration: flip/translation/scale → geometry, hue → color,
   blur/scale → detail. The committed threshold per entry and component is the midpoint of
   the observed separation `[maxNegative, minPositive]`, so it sits strictly below the
   positive and strictly above every deliberate negative by exactly half the measured
   separation. Calibration fails loudly (`ReferenceException.Kind.CALIBRATION`) when the
   ranges overlap or touch. Strict CI never recalibrates; the qualification test fails when a
   current measurement no longer clears its committed threshold (stale baselines).
4b. **Absolute per-component floors; calibration may never lower required fidelity below
   them.** Every gated component has an immutable, achievable-but-material floor — geometry
   0.10, color 0.15, detail 0.10 — enforced in `QualificationPolicy` (`GEOMETRY_FLOOR`,
   `COLOR_FLOOR`, `DETAIL_FLOOR`, `floor(FidelityComponent)`). Each floor sits above the
   worst threshold a self-transform calibration ever minted (geometry 0.076, color 0.047,
   detail 0.044) so that failure mode can never be committed again, while staying below the
   current faithful recreations after the fixed-canvas scale alignment (Hades
   0.112/0.198/0.114, STS 0.107/0.230/0.186) so honest recreations can still calibrate;
   Wesnoth must still improve its color and detail above the floors. The effective
   calibrated threshold is
   `max(component floor, midpoint)`, so a midpoint that would undercut the floor is lifted to
   it, and a positive that itself scores below the floor refuses calibration outright
   (`calibrate` returns empty and the calibration task fails with a typed
   `ReferenceException.Kind.CALIBRATION` naming the component and floor): a sub-floor
   recreation can never be committed as a passing baseline, so no self-transform calibration
   can mint a gate that accepts a broken recreation. The gate verdict itself compares against
   the committed thresholds, so committed baselines below the floor keep gating until they
   are re-calibrated; staleness reports them because the floor-aware implied threshold
   exceeds them.
4c. **Floor-aware bidirectional staleness.** Staleness compares the committed threshold
   against exactly what `calibrate` would commit today — the floor-aware
   `max(floor, midpoint)` of the current positive and its component-relevant negatives — in
   both directions: an upward drift (implied threshold rose beyond the 10% relative
   tolerance) and a downward drift (implied threshold fell beyond it) are both stale, while a
   committed threshold at the floor is not falsely flagged when the raw midpoint dips below
   it (the floor holds the implied value up), and a measured positive below the floor, which
   can imply no threshold at all, is stale by definition.
5. **Committed negative fixtures.** The five transforms of the palisade recreation are
   committed under `src/test/resources/negative/` (deterministic, offline). A test measures
   each against the palisade reference and asserts it is rejected by its intended component's
   committed threshold, so the gate's negative rejection is proven reproducibly in CI.
6. **Report evidence.** The report is schema version 2: per entry it serializes every
   component score, every committed threshold, the cell counts, the verdict, and the exact
   failed dimensions, so a failing gate names the regression without re-derivation.
7. **CI.** A dedicated `qualification` job runs the module under Xvfb in strict mode
   (`-PstrictQualification=true`) and retains the report, rendered recreations, references,
   and negative fixtures on failure.

## Measured calibration (2026-08-08, strict run twice with byte-identical reports)

The detail local term averages over reference-detail cells only (reference-blank cells are
outside its scope; reference-detail cells the recreation leaves empty score 0), combined with
the global gradient-energy ratio. The authoritative verdict additionally fails an entry whose
structured-cell density leaves the reference band (`STRUCTURE_DENSITY` in failedDimensions).

| entry | geometry | color | detail | coarse (diag) |
|---|---|---|---|---|
| palisade-skirmish | 0.201 / thr 0.161 | 0.920 / 0.702 | 0.401 / 0.322 | 0.473 / 0.307 |
| hades-boon | 0.111 / 0.106 | 0.068 / 0.047 | 0.093 / 0.077 | 0.366 / 0.238 |
| sts-shop | 0.084 / 0.076 | 0.259 / 0.211 | 0.128 / 0.110 | 0.351 / 0.228 |
| wesnoth-battle | 0.093 / 0.088 | 0.060 / 0.055 | 0.051 / 0.044 | 0.362 / 0.235 |

Every named deliberate negative is rejected by its intended component: e.g. palisade flip
geometry 0.121 < 0.161, hue color 0.484 < 0.702, blur detail 0.168 < 0.405; hades scale
geometry 0.100 < 0.106; sts translate geometry 0.041 < 0.076; wesnoth flip geometry
0.082 < 0.088 and hue color 0.049 < 0.055.

These measured values predate the absolute floors of decision 4b. With the achievable
floors (geometry 0.10, color 0.15, detail 0.10), the improved faithful recreations already
clear every floor — Hades 0.112/0.198/0.114 and STS 0.107/0.230/0.186 all sit above their
floors and can re-calibrate — while Wesnoth (≈0.093/0.060/0.051) still sits below the
geometry/color/detail floors and must improve before it can calibrate. The designer target
is `floor + 0.02` (geometry ≥ 0.12, color ≥ 0.17, detail ≥ 0.12) so that re-calibration
commits thresholds at or above the floors with margin; until that re-calibration wave lands,
the committed manifest thresholds remain in force for the verdict and the floor-aware
staleness check flags every sub-floor committed baseline.

## Consequences

- The qualification exercises the shipped product (the preview binary), the bounded CSS
  subset, and the dialect's layout attributes against real, recognizable game UIs, and now
  gates typography, color, and fine layout independently of the coarse overlap.
- Copyrighted screenshots never enter the repository; provenance and license notes live in
  the manifest next to the pinned URLs. The negative fixtures are derived from the owned
  palisade reference only.
- The full pipeline — resolve reference, render with palette, score three components plus
  coarse, gate per component, and re-calibrate — runs unattended and deterministically
  offline; the only committed inputs are the corpus manifest, the recreation markup and
  palettes, the negative fixtures, and the calibrated thresholds.
- The recreations of art-heavy references (Hades, Slay the Spire, Wesnoth) reproduce the UI
  chrome (HUD, panels, text) over an approximation of the dominant scene palette; the
  remaining art texture is inherently unreproducible in markup, which bounds the achievable
  detail and color scores (calibrated accordingly).
- Calibration is floor-bound: no run can lower a required fidelity gate below the immutable
  per-component floors (geometry 0.10, color 0.15, detail 0.10), and a recreation whose own
  deliberate transforms cannot be separated from it (or that scores below a floor) fails
  calibration loudly instead of minting a passing gate. Raising the bar above the floors is
  a corpus-quality question (better markup recreations), never a calibration shortcut.
- The harness's agentic-palisade qualification is untouched and remains a libgdx-ui-harness
  concern.
