# Releases

## Versioning

- Public versions: SemVer `MAJOR.MINOR.PATCH` (`-rc.N` for pre-releases), tag `vX.Y.Z`.
- `versionName` and `versionCode` are literals in `app/build.gradle.kts`. `versionCode` strictly
  increases with every release (the workflow checks it against the previous tag).
- `CHANGELOG.md` needs a `## [X.Y.Z]` section; it becomes the release notes.
- Application id `com.muxlume.app` is permanent. Debug builds use `com.muxlume.app.debug`.

## Steps

1. Bump `versionName`/`versionCode`, add the changelog section and
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, merge to `main`.
2. Wait for Android CI, Security and CodeQL to pass on that commit.
3. Tag it: `git tag -s vX.Y.Z && git push origin vX.Y.Z` (or run *Release (GitHub)* manually with the tag).
4. Approve the `release` environment when asked. The workflow builds unsigned in a job without
   secrets, signs in the protected job, verifies the certificate, launches the APK on an emulator,
   and publishes the APK, AAB, SBOM, R8 mapping and `SHA256SUMS.txt`. On a public repository it adds
   a build provenance attestation.
5. For Google Play run *Release (Google Play)*; see [PLAY_STORE.md](PLAY_STORE.md).

Nothing is published if any check fails.

## One-time setup

1. Create the release key once and keep it offline with a backup:
   `keytool -genkeypair -v -keystore muxlume-release.jks -alias muxlume -keyalg RSA -keysize 4096 -validity 10000`
2. Repository → Settings → Environments → `release`: required reviewer, deployment tags `v*` only.
   Secrets: `MUXLUME_KEYSTORE_BASE64` (`base64 -w0 muxlume-release.jks`), `MUXLUME_KEYSTORE_PASSWORD`,
   `MUXLUME_KEY_ALIAS`, `MUXLUME_KEY_PASSWORD`. Variable: `RELEASE_CERT_SHA256`
   (`keytool -list -v -keystore muxlume-release.jks | grep SHA256`).
3. Protect `main` and `v*` tags (rulesets): required checks, no force pushes, no deletion.

Local signed builds: export `MUXLUME_KEYSTORE_PATH` and the three secrets, then `./gradlew assembleRelease`.
Without them, release builds are unsigned. No key material ever goes into the repository (CI fails on
tracked `.jks`/`.keystore`/`.p12` files).

## Keys and stores

| Key | Who holds it | Signs |
|---|---|---|
| APK signing key (this "release key") | You | GitHub release APKs |
| Play upload key | You | AABs uploaded to Play; Play verifies and strips it |
| Play app-signing key | Google (Play App Signing) | APKs Play delivers to devices |
| F-Droid key | F-Droid | APKs F-Droid builds, unless reproducible-build verification is set up |

Android only updates an app in place when the new APK has the same signing certificate. Decide
before the first release:

- **Same certificate on GitHub and Play**: when enrolling in Play App Signing, choose to upload your
  own app-signing key (the release key) and use a separate upload key. Then users can move between
  GitHub and Play builds. Play shows the option only at enrolment.
- **Different certificates** (Google-generated key, F-Droid key): users must uninstall (losing local
  data) to switch stores. Document which store a build came from.

F-Droid can ship the developer's signature only via reproducible builds (it rebuilds, compares, then
publishes the developer-signed APK). That requires deterministic builds verified with fdroidserver.

## Project links

The app lists only links verified to exist. Add the project website
(`https://simplyfahim.pages.dev/Muxlume`, 404 on 2026-11-10) and the GitHub repository and issue
tracker (private on 2026-11-10) to `ui/library/AboutScreen.kt` once they are public.
