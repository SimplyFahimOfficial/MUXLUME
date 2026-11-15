# Requirements: release readiness for MUXLUME 0.1.0

> **Basis.** No requirements document existed. These are derived from the Stage E brief ("Release readiness"):
> turn the changelog into `## [0.1.0]`, add the F-Droid changelog, protect `main`, dry-run the release workflow
> with a pre-release tag, keep the website link, and list what only the owner can do. Stages A to D are merged.
> Nothing here adds an app feature.

## Introduction

MUXLUME 0.1.0 is feature-complete but unreleased: there are no tags, `main` is unprotected, the changelog has
several repeated "Added" blocks and an old `0.1.0 - unreleased` stub, and the release workflow has never run.
The goal is a first release the owner can publish by adding signing secrets and pushing one tag.

## Requirements

### R1. Release notes for 0.1.0

**User story:** As the maintainer, I want one clean `0.1.0` changelog section, so that the release workflow
accepts the tag and the GitHub release notes read well.

1. THE `CHANGELOG.md` SHALL contain exactly one `## [0.1.0] - <date>` heading, and an empty `## [Unreleased]` above it.
2. THE `0.1.0` section SHALL list each of `Added`, `Changed`, `Fixed`, `Removed` at most once, and SHALL contain
   every user-visible entry now under `[Unreleased]` and under the old `[0.1.0] - unreleased` stub, with no entry lost.
3. THE old `## [0.1.0] - unreleased` heading SHALL NOT remain.
4. THE heading SHALL match the check in `release-github.yml` (`^## \[0\.1\.0\]`) and in `fdroid-readiness.yml`.

### R2. F-Droid changelog

**User story:** As an F-Droid user, I want the store's "what's new" text to describe this release.

1. THE file `fastlane/metadata/android/en-US/changelogs/1.txt` SHALL describe what 0.1.0 actually contains.
2. THE file SHALL be at most 500 bytes of UTF-8 (F-Droid's limit) and plain text.
3. THE file name SHALL stay equal to `versionCode` (1) unless `versionCode` changes.

### R3. Protected `main`

**User story:** As the maintainer, I want `main` protected, so that nothing unchecked or force-pushed reaches a release.

1. WHEN a change targets `main`, THE repository SHALL require a pull request (no required approvals: there is one maintainer).
2. THE repository SHALL require these checks to pass: `Build, tests, lint, APK audit`, `Release APK smoke test (emulator)`,
   `detekt and format check`, `Analyze (java-kotlin)`, `actionlint and zizmor`, `Secret scan (gitleaks)`,
   `No keystores or credential files in the repository`, `FLOSS, metadata and version audit`,
   `Shipped dependency changes (OSV, licences)`.
3. THE repository SHALL refuse force pushes to and deletion of `main`.
4. IF the available token lacks the rights to create the ruleset, THEN the agent SHALL NOT work around it and SHALL give
   the owner the exact settings and check names instead.
5. THE checks that are often skipped (dependency review, shipped-dependencies OSV scan, CodeRabbit) SHALL NOT be required.

### R4. Release workflow dry run

**User story:** As the maintainer, I want to see the release workflow run before the real release, so that
workflow errors are found while nothing can be published.

1. THE dry run SHALL use a pre-release tag `v0.1.0-rc.1` on a commit that is on `main` and has passed
   `android-ci.yml`, `security.yml` and `codeql.yml`.
2. THE commit SHALL have `versionName = "0.1.0-rc.1"` and a `## [0.1.0-rc.1]` changelog heading, because the workflow
   requires `versionName` to equal the tag without `v`.
3. WHILE the `release` environment has no secrets, THE run SHALL stop at the signing job with the message listing the
   missing secrets, and SHALL NOT create a GitHub release, upload to Play, or publish anything.
4. THE agent SHALL report which jobs passed (`validate`, `build`, APK audit) and which were not exercised
   (`sign`, `smoke`, `publish`, `attest`).
5. AFTER the dry run, THE temporary version change SHALL be reverted and the `v0.1.0-rc.1` tag deleted, so that the
   real `v0.1.0` release is not rejected for a `versionCode` not greater than the previous tag's.

### R5. Protected release tags

1. AFTER the dry-run tag is deleted, THE repository SHALL refuse deletion, update and force-push of tags matching `v*`.

### R6. Owner handover

**User story:** As the owner, I want an exact list of what only I can do, so that nothing is guessed.

1. THE handover SHALL list: creating the release key (separate keys for GitHub and Play), the `release` environment
   (required reviewer, tags `v*` only) with secrets `MUXLUME_KEYSTORE_BASE64`, `MUXLUME_KEYSTORE_PASSWORD`,
   `MUXLUME_KEY_ALIAS`, `MUXLUME_KEY_PASSWORD` and variable `RELEASE_CERT_SHA256`, store listings and screenshots,
   Play Console and F-Droid submissions.
2. THE handover SHALL repeat the open decisions: the GPL additional permission for the Play build, and Play Console
   declarations for the `dataSync` service type and `SCHEDULE_EXACT_ALARM`.
3. THE website link `https://simplyfahim.pages.dev/Muxlume` SHALL be left as it is.

### R7. Verification on a real device

**User story:** As the maintainer, I do not want to tag a release whose newest features have never run on a phone.

1. BEFORE the real `v0.1.0` tag, a checklist SHALL exist for a hand test on a real device covering: recording (live TS and
   HLS, schedule with and without exact alarms, stop, storage full, delete), the parental PIN (lock, unlock, relock,
   lockout, playback and notification refusal), the back gesture (edge swipe, toolbar, hold and cancel), hide/rename/pin
   and guide search, backup and restore, the crash report share, and Play in-app update from a Play-installed build.
2. THE agent SHALL NOT claim any of these works until the owner reports a result.

### Out of scope

New features; the real signing and publication; Play Console and F-Droid submission; changing `LICENSE`; screenshots.
