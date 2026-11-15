# Design: release readiness for MUXLUME 0.1.0

## Overview

Four small changes and one rehearsal. Two of them touch the repository (`CHANGELOG.md` plus the F-Droid text, and a
temporary version bump); two touch GitHub settings (a branch ruleset and a tag ruleset); the rehearsal pushes a tag.
No application code changes. Every file path below was read from `main` at `3f90862`.

```
E1 PR  changelog + fastlane text        ──merge──┐
ruleset on main (API)  ─────────────────────────┤
E2 PR  versionName 0.1.0-rc.1 (temporary) ─merge─┴─► tag v0.1.0-rc.1 ─► Release (GitHub) runs
                                                         │  validate ✓  build ✓  audit ✓  sign ✗ (no secrets)
                                                         ▼
                              delete tag ─► E3 PR  versionName back to 0.1.0 ─► tag ruleset v*
```

## How the release workflow decides (read from `release-github.yml` and `scripts/ci/release_version.py`)

| Check | Rule | Consequence for this plan |
|---|---|---|
| Tag format | `vMAJOR.MINOR.PATCH[-prerelease]`, commit must be on `main` | rc tag is allowed, only after the E2 merge |
| Version | `versionName` in `app/build.gradle.kts` must equal the tag without `v` | the rc run needs a temporary `versionName = "0.1.0-rc.1"` |
| `versionCode` | positive, and strictly greater than the previous tag's (`git describe` on the tagged commit's parent) | **the rc tag must be deleted before `v0.1.0`**, or `0.1.0` would be rejected at code 1 (or bumped to 2) |
| Changelog | a heading `## [<versionName>]` | E2 adds a `## [0.1.0-rc.1]` stub |
| CI | successful runs of `android-ci.yml`, `security.yml`, `codeql.yml` on the tagged SHA | tag only after CI is green on the merge commit |
| Sign | job uses `environment: release` and needs four secrets and `RELEASE_CERT_SHA256` | without them the run stops here by design |

A job that names an environment that does not exist makes GitHub create it, **unprotected and empty**. The dry run
therefore creates `release`; the owner must then add the reviewer rule and secrets (R6).

## Components

### C1. Changelog restructure (R1)

Current layout (lines in `CHANGELOG.md`): `[Unreleased]` with `Added` (l. 8), `Removed` (49), `Changed` (52), `Fixed` (62),
then a second `Added` (67), `Fixed` (73), `Changed` (76), then `## [0.1.0] - unreleased` (79) with its own `Added`.

Target:

```
## [Unreleased]

## [0.1.0] - <date of the PR>

### Added      (all entries, newest features first)
### Changed
### Fixed
### Removed
```

Method: merge by heading, keep entry text unchanged, drop only exact duplicates, and verify with a script that every
bullet of the old file (normalised whitespace) appears in the new one. The old stub's "First preview" lines are folded
into `Added`.

### C2. F-Droid text (R2)

`fastlane/metadata/android/en-US/changelogs/1.txt`, 345 bytes (checked), plain text:

> First preview. Play M3U playlists and Xtream Codes accounts: live TV, movies and series, search, favorites, resume,
> picture-in-picture. TV guide (XMLTV) with reminders. Record live TS and HLS streams to a folder you choose. Hide,
> rename and pin channels. Parental PIN. Light and dark themes. Encrypted local backup. No accounts, ads or trackers.

A one-line check (`wc -c` ≤ 500) goes in the task.

### C3. Ruleset on `main` (R3)

Created with `POST /repos/SimplyFahimOfficial/MUXLUME/rulesets`:

```json
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0, "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false, "require_last_push_approval": false,
        "required_review_thread_resolution": false } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [
          { "context": "Build, tests, lint, APK audit" },
          { "context": "Release APK smoke test (emulator)" },
          { "context": "detekt and format check" },
          { "context": "Analyze (java-kotlin)" },
          { "context": "actionlint and zizmor" },
          { "context": "Secret scan (gitleaks)" },
          { "context": "No keystores or credential files in the repository" },
          { "context": "FLOSS, metadata and version audit" },
          { "context": "Shipped dependency changes (OSV, licences)" }
        ] } }
  ]
}
```

Decisions:
- **No required approvals.** With one maintainer a required approval would block every merge.
- **No bypass actors.** The owner merges through PRs like everyone; a bypass can be added in the UI if wanted.
- **`strict` off**, so a PR need not be rebased onto each new `main` before merging.
- **Check names are job names** as seen in `commits/<sha>/check-runs` on PR #16; a typo makes a PR unmergeable, so the
  task lists them again and compares against the latest run before enabling (`enforcement: "evaluate"` first, then `active`).
- **Failure mode:** a 403/404 from the API is reported with these exact settings; no other route is tried.

### C4. Dry-run commit (R4)

E2 changes two lines: `versionName = "0.1.0-rc.1"` in `app/build.gradle.kts`, and adds
`## [0.1.0-rc.1] - dry run of the release workflow` above `## [0.1.0]` in `CHANGELOG.md`. `versionCode` stays 1.
`fdroid-readiness.yml` reads the same two places, so its changelog check passes as well.

### C5. Tag rehearsal (R4, R5)

1. After E2 merges and CI is green on the merge commit: `git tag -a v0.1.0-rc.1 <sha> && git push origin v0.1.0-rc.1`
   (unsigned annotated tag: the doc's `git tag -s` needs the owner's key).
2. Watch *Release (GitHub)*. Expected: `validate`, `build` and the unsigned-APK audit pass; `sign` fails with
   "missing in the release environment: …". No release, no assets, no Play upload.
3. Record the job-by-job result in the PR notes. Anything unexpected is a finding, not something to patch around.
4. Delete the remote and local tag (`git push origin :refs/tags/v0.1.0-rc.1`), open E3 to restore `versionName = "0.1.0"`
   and remove the rc heading, then create the `v*` tag ruleset:

```json
{ "name": "release tags", "target": "tag", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [ { "type": "deletion" }, { "type": "non_fast_forward" }, { "type": "update" } ] }
```

### C6. Owner handover and device checklist (R6, R7)

Written as `docs/RELEASE_CHECKLIST.md` (new, short): the one-time setup from `docs/RELEASE.md` with the exact secret and
variable names, the open decisions, and the device test list from `docs/TESTING.md` ("Not automated yet"), as tick boxes.
`docs/RELEASE.md` gains two sentences: the rc tag must be deleted before the real tag (previous-tag `versionCode` rule),
and a job naming a missing environment creates it unprotected.

## Error handling and risks

| Risk | Handling |
|---|---|
| Wrong check name makes `main` unmergeable | create the ruleset in `evaluate` mode, compare to the latest check-runs, then switch to `active` |
| Token cannot create rulesets | stop, give the owner the JSON above and the UI path (Settings → Rules → Rulesets) |
| rc tag left behind blocks `v0.1.0` | explicit task and a check (`git ls-remote --tags origin 'v0.1.0-rc*'` empty) before the real tag |
| `release` environment auto-created unprotected | stated in the handover; owner adds required reviewer and `v*` deployment tag rule before adding secrets |
| Dry run proves less than it seems | report plainly: signing, emulator smoke of the signed APK, publish and attestation are **not** exercised |
| Changelog entry lost in the merge | scripted check that every old bullet is in the new file |
| Something in the real release differs from the rc run (secrets present) | the first real run still needs the owner's approval on the `release` environment, which is the control |

## Verification

- C1/C2: scripted bullet-preservation check; `grep -E '^## \[0\.1\.0\]'`; `wc -c ≤ 500`; the same local gate as every PR
  (`testDebugUnitTest lintDebug assembleRelease`, detekt, whitespace check, `apk_audit.py`) although no code changes.
- C3: `GET /rulesets` shows `active`; open a trivial PR and confirm the nine checks are listed as required.
- C4/C5: the workflow run itself; `release_version.py v0.1.0-rc.1` run locally before pushing the tag.
- R7 is by hand and only the owner can mark it done.
