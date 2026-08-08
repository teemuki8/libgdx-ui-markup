# Releasing to Maven Central

This guide is for repository maintainers.

## Preconditions

Before preparing a tag:

1. Verify the Maven Central namespace `io.github.teemuki8` is registered and approved
   (shared with libgdx-ui-harness; already published).
2. Verify the protected GitHub environment `maven-central` is configured with the secrets and
   variables below.
3. Verify the artifact-signing public key is retrievable from a
   [Central-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key)
   by both its full fingerprint and 16-hex long key ID:
   fingerprint `2EB8F98C568F89038071930EF0484A483AB9CC7B`. The armored public key is committed
   at the repository root as `release-public.asc`; re-upload it with
   `gpg --send-keys 2EB8F98C568F89038071930EF0484A483AB9CC7B` after any rotation.
4. Verify the latest `main` checks pass locally (`xvfb-run -a ./gradlew build`).
5. Confirm the release notes describe the exact version being published.

Account enrollment, namespace ownership challenges, and recovery credentials are private
administrative records. Do not commit them to this repository.

## GitHub environment contract

The `maven-central` environment must provide:

| Name | Kind | Purpose |
|---|---|---|
| `RELEASE_SIGNING_PUBLIC_KEY` | Secret | Armored public key authorized to sign release tags |
| `RELEASE_SIGNING_FINGERPRINT` | Variable | Exact 40- or 64-hex primary-key fingerprint |
| `MAVEN_CENTRAL_USERNAME` | Secret | Username from a Maven Central Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | Secret | Password from the same Portal user token |
| `MAVEN_SIGNING_KEY` | Secret | Armored private key used to sign Maven artifacts |
| `MAVEN_SIGNING_PASSWORD` | Secret | Private-key passphrase |

A GitHub token is not a Maven Central user token. Never place secret values in repository
files, workflow arguments, issue comments, logs, or retained artifacts. Rotate the public key
and configured fingerprint together after independently verifying the new fingerprint.

## Verify the candidate locally

Run the complete release candidate gate:

```bash
xvfb-run -a ./gradlew clean check javadoc publishToMavenLocal --warning-mode=fail
```

This is the exact command CI/release run for checks (plus `centralBundle` for releases). It runs
with strict dependency verification and STRICT dependency locking by default (see the next
section) and never writes lock or verification state — a missing or altered lockfile, checksum,
or signature fails the build before task execution.

Confirm that Maven local contains only the three publishable modules:

- `libgdx-ui-markup`
- `libgdx-ui-markup-runtime`
- `libgdx-ui-markup-harness`

`libgdx-ui-markup-preview` and `libgdx-ui-markup-idea` must not be published (the preview is a
runnable app distribution; the IDEA plugin has its own marketplace channel).

## Dependency supply-chain enforcement

Every Gradle invocation in this repository — local, CI, or release — runs with two fail-closed
supply-chain controls that come from committed configuration, not from command-line flags:

1. **Strict dependency locking.** The root `build.gradle.kts` configures
   `dependencyLocking { lockAllConfigurations(); lockMode = LockMode.STRICT }` for every
   project. All six modules plus the settings graph ship committed `gradle.lockfile` /
   `settings-gradle.lockfile` files. A missing or altered lock entry fails resolution before
   any task work; the lock state changes only through `--write-locks`.
2. **Strict dependency verification.** `gradle.properties` sets
   `org.gradle.dependency.verification=strict` (with the verbose console), so every resolved
   artifact — project, plugin, settings, buildscript, and tooling graphs — must match
   `gradle/verification-metadata.xml` and a reviewed PGP signature scoped to the exact
   artifact file in `gradle/verification-keyring.keys`/`.gpg`, or the build fails before task
   execution.

CI and release enforce this explicitly. `.github/workflows/ci.yml` runs a `supply-chain` job and
`.github/workflows/release.yml` runs the same steps before the release gate. Both assert the
committed configuration above and fail if any workflow file uses a bypass flag
(`--refresh-dependencies`, `--write-locks`, `--write-verification-metadata`, or
`--dependency-verification lenient|off`), then run the non-writing
`./gradlew resolveAndLockAll --warning-mode=fail`, which resolves every resolvable configuration
in every project — proving the committed lock set is exhaustive and enforced and the
verification metadata covers the currently resolved graph.

### Recovery: deliberate dependency upgrades

Upgrading a dependency is a deliberate act. Because strict verification and STRICT locking
are default-on, a "locks first, then verification metadata" two-step sequence deadlocks: the
`--write-locks` pass would be rejected by strict verification (the new artifact is not yet in
`verification-metadata.xml`) before any lock state is written. Instead, bootstrap both in a
single full-graph invocation that writes locks and verification metadata together, in a fresh
`GRADLE_USER_HOME` so every artifact, plugin, and settings component is re-downloaded and
re-checked against the committed keyring and trust policy:

1. Edit the version catalog (`gradle/libs.versions.toml`) or build files.
2. Bootstrap in one pass (local write; these flags never run in CI/release):
   ```bash
   GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-daemon \
     --write-locks --write-verification-metadata pgp,sha256 --export-keys \
     help resolveAndLockAll :libgdx-ui-markup-idea:buildPlugin :libgdx-ui-markup-idea:unitTest \
     :libgdx-ui-markup-preview:installDist javadoc \
     :libgdx-ui-markup:publishMavenJavaPublicationToCentralStagingRepository \
     :libgdx-ui-markup-runtime:publishMavenJavaPublicationToCentralStagingRepository \
     :libgdx-ui-markup-harness:publishMavenJavaPublicationToCentralStagingRepository
   ```
   The task set is the full CI/release resolution surface from Task 2: settings and project
   plugin graphs (`help`), every resolvable configuration in every module (`resolveAndLockAll`),
   the IDEA plugin build and its unit tests, the preview distribution, Javadocs, and the release
   publication tooling. `--export-keys` imports any new signer keys into the committed keyring;
   the committed keyring and file-exact trust policy stay in force for the whole bootstrap, so
   nothing new is trusted without a signature from a known key. If the bootstrap fails on
   keyserver access, import the new keys from a keyserver manually into
   `gradle/verification-keyring.gpg` and re-run.
3. Review the generated diff: every new coordinate, checksum, and signer key must come from the
   intended upgrade and a trusted publisher, with each key scoped to its exact
   group/module/version/file entries — no wildcard trust, no `trusted-artifacts`, no
   `ignored-keys`. **Never commit before this provenance and key/file-scope review.**
4. Commit the lockfiles, `gradle/verification-metadata.xml`, and keyring changes together so the
   committed state never mixes an old lock set with new verification metadata.
5. Verify with the non-writing sweep `./gradlew resolveAndLockAll --warning-mode=fail` and the
   full candidate gate above; CI/release then enforce the new state exactly as before.

If a dependency is reverted instead, restore the previously reviewed lockfiles and metadata
verbatim — do not regenerate, because the current graph would then be re-locked against the
reverted build files.

## Create the release

Replace `X.Y.Z` with the release version (semantic, optional prerelease suffix). The
`centralBundle` task fails closed unless `-PreleaseVersion` is non-SNAPSHOT and all four Maven
secrets are present:

```bash
xvfb-run -a ./gradlew -Prelease=true -PreleaseVersion=X.Y.Z \
  clean check javadoc centralBundle --warning-mode=fail
```

This builds the deterministic signed bundle at `build/distributions/central-bundle-X.Y.Z.zip`
and verifies every staged artifact (main, sources, javadoc, POM) has a signature and a
`META-INF/LICENSE`, and that no unpublished module leaked into staging.

Tag the candidate with a PGP-signed annotated tag; pushing it starts
`.github/workflows/release.yml`:

```bash
git switch main
git pull --ff-only
git tag --sign vX.Y.Z --message "libgdx-ui-markup X.Y.Z"
git tag --verify vX.Y.Z
git push origin vX.Y.Z
```

The release tag must be annotated and PGP-signed by the configured key and must point to the
exact candidate commit. Never move or reuse a release tag.

## Automated publication

Pushing the tag starts the `Release` workflow on the `maven-central` environment. The workflow:

1. imports only the configured trusted public key into an isolated temporary GnuPG home;
2. verifies the primary fingerprint, signed semantic-version tag, and tag-to-commit binding;
3. asserts the supply-chain configuration and rejects bypass flags, then runs the non-writing
   `resolveAndLockAll` sweep proving locked strict resolution and verification coverage;
4. runs the clean checks and Javadocs under JDK 25 (headless via Xvfb);
5. builds and signs the deterministic three-module Central bundle;
6. rejects missing artifacts, signatures, or unpublished-module leakage;
7. uploads a user-managed Maven Central deployment;
8. waits for Central state `VALIDATED`;
9. publishes the validated deployment;
10. waits for Central state `PUBLISHED`.

Publication credentials are scoped only to the steps that require them. Central authorization
is written to a mode-0600 temporary curl configuration and deleted on every exit path.

## Confirm the release

After the workflow succeeds:

1. confirm the GitHub Actions release job is green;
2. confirm the deployment is `PUBLISHED` in the Maven Central Portal;
3. verify all three module coordinates and their POM, main JAR, sources JAR, Javadoc JAR, and
   signatures are available;
4. create the corresponding GitHub release from the immutable signed tag;
5. update installation examples only after Maven Central resolves the released coordinates.

A green build before upload does not prove publication. The release is complete only after
Central reports `PUBLISHED` and the public coordinates resolve.
