# Visual Fidelity Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issue #27 by replacing the coarse-only pass gate with deterministic geometry, color, and detail components calibrated against positive and deliberate-negative fixtures.

**Architecture:** Decode both images through the bounded normalized-image path from the qualification-security PR. Compute allocation-bounded pure metrics over fixed grids/histograms; retain coarse layout for diagnosis and require every fidelity component to pass independently.

**Tech Stack:** Java 25, ImageIO, JUnit 5, Jackson, Gradle Wrapper, Xvfb.

## Global Constraints

- Depend on deterministic upright preview screenshots and bounded image decode.
- No OCR/network/model dependency; metrics must be reproducible offline.
- Strict CI never recalibrates thresholds.
- Every score and threshold is serialized in a versioned bounded report.

---

### Task 1: Immutable Multi-Signal Score Model

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/RegionSimilarity.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationReport.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusEntry.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusManifest.java`
- Create: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/VisualFidelityTest.java`

**Interfaces:**
- Rename/generalize scorer to return `FidelityScore(coarseLayout, geometry, color, detail, referenceCells, recreationCells)` or introduce `VisualFidelity` while retaining a small deprecated-free internal `RegionSimilarity` helper.
- Entry thresholds become immutable `FidelityThresholds(geometry, color, detail)` plus optional coarse diagnostic baseline.
- Report schema version 2 serializes all component values and thresholds.

- [ ] Add failing model/JSON tests for finite `[0,1]` validation, deterministic ordering/rounding, and a verdict that fails when any one required component is below threshold despite a high average.
- [ ] Run `./gradlew :libgdx-ui-markup-qualification:test --tests '*VisualFidelityTest' --warning-mode=fail` red.
- [ ] Implement records with compact-constructor bounds and update manifest/report parsing/writing. Preserve coarse cells for diagnosis, not pass gating.
- [ ] Run tests green and commit `feat: model multi-signal visual fidelity`.

### Task 2: Geometry and Color Metrics

**Files:**
- Modify/create scorer classes under `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/VisualFidelityTest.java`

**Interfaces:**
- Geometry: thresholded Sobel-gradient cell mask with Dice score.
- Color: fixed quantized RGB histogram intersection normalized to `[0,1]`.
- All buffers scale only with bounded analysis dimensions or fixed bin counts.

- [ ] Build synthetic positive, translated, vertically flipped, and hue-shifted images in memory. Assert identity=1, translation/flip lowers geometry below the calibrated negative ceiling, and hue shift lowers color without relying on geometry.
- [ ] Run focused test red.
- [ ] Implement integer/fixed-order convolution and histogram loops with no per-pixel object allocation. Define deterministic border handling and empty-mask conventions.
- [ ] Run focused test green and commit `feat: measure geometry and color fidelity`.

### Task 3: Typography/Detail Metric

**Files:**
- Modify/create scorer classes under `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/VisualFidelityTest.java`

**Interfaces:**
- Detail score compares grid-local high-frequency magnitude and four-bin edge-orientation histograms, normalized and averaged only over non-empty reference/detail cells.

- [ ] Create deterministic text-like stripe/glyph fixtures plus blurred, scaled, and spacing-shifted negatives. Assert color similarity may stay high while detail falls below the negative ceiling.
- [ ] Run focused test red.
- [ ] Implement fixed-grid high-pass/orientation accumulation using primitive arrays. Bound cell count and define empty-detail behavior so blank matching regions score 1 while one-sided detail scores 0.
- [ ] Run focused test green and commit `feat: measure typography detail fidelity`.

### Task 4: Calibration Policy and Corpus Negatives

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationPolicy.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationRunner.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CalibrateMain.java`
- Modify: `libgdx-ui-markup-qualification/corpus/manifest.json`
- Add deterministic negative fixtures under `libgdx-ui-markup-qualification/src/test/resources/`
- Modify: `docs/adr/0003-game-ui-recreation-qualification.md`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/QualificationPolicyTest.java`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/QualificationTest.java`

**Interfaces:**
- Threshold per component is below the minimum positive score by a documented margin and above the maximum deliberate-negative score; calibration fails if those ranges overlap.
- Runner passes only if geometry, color, and detail each pass.

- [ ] Add failing policy tests for positive/negative separation, overlapping distributions, stale thresholds, and each single-component failure.
- [ ] Add reproducible negative transformations: vertical flip, fixed translation, hue rotation, and detail blur/scale.
- [ ] Implement calibration output with all component observations and explicit failure when no safe threshold interval exists.
- [ ] Update ADR 0003 with formulas, bounds, fixtures, threshold policy, and limitations. Run focused tests green and commit `feat: calibrate strict visual fidelity gate`.

### Task 5: Strict CI and Report Evidence

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationReport.java`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/QualificationTest.java`

- [ ] Add a test asserting report schema 2 includes every score/threshold and strict output identifies the failed dimension.
- [ ] Ensure CI retains report/reference/recreation/negative evidence on failure and invokes strict mode without calibration.
- [ ] Run `xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test -PstrictQualification=true --warning-mode=fail` and inspect component results.
- [ ] Commit `ci: enforce multi-signal qualification`.

### Task 6: Fidelity PR Verification

- [ ] Run all qualification tests twice and confirm identical JSON/component scores.
- [ ] Exercise each deliberate negative and record the component that rejects it.
- [ ] Run `xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:check -PstrictQualification=true --warning-mode=fail`.
- [ ] Create a ready PR with `Fixes #27`, formulas, bounds, calibration evidence, component thresholds, and limitations.