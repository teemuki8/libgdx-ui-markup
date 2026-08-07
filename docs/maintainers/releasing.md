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

Confirm that Maven local contains only the three publishable modules:

- `libgdx-ui-markup`
- `libgdx-ui-markup-runtime`
- `libgdx-ui-markup-harness`

`libgdx-ui-markup-preview` and `libgdx-ui-markup-idea` must not be published (the preview is a
runnable app distribution; the IDEA plugin has its own marketplace channel).

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
3. runs the clean checks and Javadocs under JDK 25 (headless via Xvfb);
4. builds and signs the deterministic three-module Central bundle;
5. rejects missing artifacts, signatures, or unpublished-module leakage;
6. uploads a user-managed Maven Central deployment;
7. waits for Central state `VALIDATED`;
8. publishes the validated deployment;
9. waits for Central state `PUBLISHED`.

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
