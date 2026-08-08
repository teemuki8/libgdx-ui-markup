# Qualification Trust Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issues #5, #13, #14, and #15 by making corpus input, remote images, decode work, process lifetime, and reports strictly bounded and authenticated.

**Architecture:** Validate manifest identity and containment before I/O; centralize verified reference acquisition; inspect image metadata before decode; propagate one monotonic run deadline through every entry and child process.

**Tech Stack:** Java 25, Jackson, Java HttpClient/ImageIO, JUnit 5, Gradle Wrapper, Xvfb.

## Global Constraints

- No arbitrary filesystem read/write or unrestricted URL fetch.
- Remote references require committed identity fields and HTTPS host policy.
- Limit+1 detection happens while streaming, before persistent write or decode.
- Process cleanup uses observable termination, never sleeps.

---

### Task 1: Strict Bounded Manifest and Contained Paths (#13, #15)

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusEntry.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusManifest.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationRunner.java`
- Create: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/CorpusManifestTest.java`

**Interfaces:**
- Public fixed caps for manifest bytes, entries, per-string length, ID length, and aggregate work.
- `CorpusEntry` validates slug ID and normalized relative `markupFile`/`referenceFile`.
- Runner resolves through a helper that normalizes, verifies prefix containment, and rejects symlink escape using real parent paths.

- [ ] Add failing cases for oversized bytes, too many entries, long strings, unknown/missing/wrong-type fields, absolute paths, `..`, separator variants, symlink escape, invalid ID, and output traversal; add exact-limit valid case.
- [ ] Run `./gradlew :libgdx-ui-markup-qualification:test --tests '*CorpusManifestTest' --warning-mode=fail` red.
- [ ] Implement bounded byte load, strict JSON field/type validation, immutable entries, slug/path validation, and root-contained resolution before reads/writes.
- [ ] Run tests green and commit `fix: bound manifests and contain corpus paths`.

### Task 2: Authenticated URL Identity (#14)

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusEntry.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/CorpusManifest.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/ReferenceImageStore.java`
- Modify: `libgdx-ui-markup-qualification/corpus/manifest.json`
- Create: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/ReferenceImageStoreTest.java`

**Interfaces:**
- Remote entry fields: HTTPS URL, lowercase 64-hex SHA-256, exact positive byte length, allowlisted image media type, width, and height.
- Store accepts an injectable `HttpClient`/transport seam and host/address policy for deterministic tests.

- [ ] Add failing tests for HTTP, user info, fragment, wrong host, redirect to disallowed target, excessive redirects, private/loopback/link-local resolved address, wrong digest/type/length/dimensions, and forged cache hit.
- [ ] Run focused tests red.
- [ ] Implement manual bounded redirect handling with policy validation on every target; reject ambiguous authority and prohibited address classes. Verify digest/type/length/dimensions on download and cache hit.
- [ ] Fetch the four canonical references through the verified path, record exact manifest identity, and independently checksum committed/local bytes.
- [ ] Run tests green and commit `fix: authenticate qualification references`.

### Task 3: Streaming Byte and Decode Bounds (#5)

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/ReferenceImageStore.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/RegionSimilarity.java`
- Test: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/ReferenceImageStoreTest.java`
- Create: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/RegionSimilarityTest.java`

**Interfaces:**
- Fixed maximum download bytes, width, height, pixels, and analysis dimensions.
- One metadata-inspection/decode helper returns a bounded normalized image used by scoring.

- [ ] Add a limit+1 streaming response test asserting the producer is stopped and no temp/cache file remains. Add crafted metadata images with excessive dimension/pixels and assert rejection before raster allocation. Add valid subsampling determinism test.
- [ ] Run focused tests red.
- [ ] Stream to owner-only create-new temp file while hashing/counting; abort at limit+1; validate identity; atomically move into cache. Use `ImageReader.getWidth/getHeight` before `read`, enforce product with overflow-safe arithmetic, and request bounded subsampling.
- [ ] Replace full-resolution `int[height][width]` scoring arrays with bounded analysis buffers. Run tests green and commit `fix: bound qualification images before decode`.

### Task 4: Total Deadline and Child Ownership (#15)

**Files:**
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationRunner.java`
- Modify: `libgdx-ui-markup-qualification/src/main/java/dev/gdx/markup/qualification/QualificationReport.java`
- Create: `libgdx-ui-markup-qualification/src/test/java/dev/gdx/markup/qualification/QualificationRunnerTest.java`

**Interfaces:**
- Runner constructor receives monotonic clock/deadline policy and process launcher seams for tests.
- One total deadline bounds all entries; each child wait uses remaining duration.

- [ ] Add fake-process tests for total-deadline exhaustion across entries, noisy output, graceful exit, hung force kill, and thread interruption. Assert child/drain termination and bounded report count/message.
- [ ] Run focused tests red.
- [ ] Propagate `deadlineNanos`; before each operation compute remaining time with overflow-safe monotonic math. Own drain tasks in a closeable scope; terminate→wait→force→final wait; preserve interruption and cancel/join drains.
- [ ] Run tests green and commit `fix: bound qualification work and processes`.

### Task 5: Qualification Security PR Verification

- [ ] Run `xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:check --warning-mode=fail`.
- [ ] Run strict qualification against verified references and confirm deterministic scores/report.
- [ ] Review all trust-boundary limits for limit+1 behavior, overflow safety, cleanup, symlink/redirect policy, and typed bounded diagnostics.
- [ ] Create a ready PR with `Fixes #5`, `#13`, `#14`, and `#15`, exact verification, manifest identity changes, and security rationale.