# Tasks: release readiness for MUXLUME 0.1.0

Order matters. "Agent" tasks need no one else; "Owner" tasks need your account, keys or a phone.
Every PR follows the project gate: `./gradlew testDebugUnitTest lintDebug assembleRelease`, detekt on the unchanged
baseline, the whitespace check, `apk_audit.py`, and green CI before it is reported. PRs are opened, never merged, by the agent.

- [ ] 1. Release notes and F-Droid text (PR "E1")  — Agent  (R1, R2)
  - [ ] 1.1 Branch `release-notes` off `origin/main`
  - [ ] 1.2 Restructure `CHANGELOG.md` into an empty `[Unreleased]` and one `## [0.1.0] - <PR date>` with each of
        Added/Changed/Fixed/Removed once (design C1)
  - [ ] 1.3 Script check: every bullet of the old file (whitespace-normalised) exists in the new one; heading regex
        `^## \[0\.1\.0\]` matches once; no `unreleased` heading remains
  - [ ] 1.4 Write `fastlane/metadata/android/en-US/changelogs/1.txt` (design C2); check `wc -c` ≤ 500
  - [ ] 1.5 Run the gate; open the PR; wait for green CI; report
  - [ ] 1.6 Owner: review the wording and merge

- [ ] 2. Protect `main` (R3)  — Agent, then Owner if the API refuses
  - [ ] 2.1 `GET /rulesets` to see whether the token may create them
  - [ ] 2.2 Compare the nine check names (design C3) with `check-runs` of the latest `main` commit
  - [ ] 2.3 Create the `main` ruleset with `enforcement: "evaluate"`; confirm with a trivial test PR that the checks are listed
  - [ ] 2.4 Switch to `active`; confirm `GET .../branches/main` reports `protected: true`
  - [ ] 2.5 If 2.1 or 2.3 returns 403/404: stop and hand the owner the JSON and the UI path instead (no workaround)
  - Do this after task 1.6 so E1 is not the first PR through the new rules.

- [ ] 3. Dry run of the release workflow (R4)  — Agent  (needs 1.6 and 2.4)
  - [ ] 3.1 Branch `release-rc` off `origin/main`: `versionName = "0.1.0-rc.1"`, add `## [0.1.0-rc.1]` stub heading
  - [ ] 3.2 Locally: `python3 scripts/ci/release_version.py v0.1.0-rc.1` passes; run the gate; open PR "E2"
  - [ ] 3.3 Owner: merge E2. Agent: wait for `android-ci`, `security`, `codeql` to succeed on the merge commit
  - [ ] 3.4 Push the annotated tag `v0.1.0-rc.1` on that commit
  - [ ] 3.5 Watch *Release (GitHub)*; record each job's result. Expected: validate, build, audit pass; sign stops
        with "missing in the release environment"; nothing published
  - [ ] 3.6 Confirm no GitHub release, tag assets or Play upload exist
  - [ ] 3.7 Report what was and was not exercised (sign, smoke, publish, attest)

- [ ] 4. Clean up after the dry run (R4.5, R5)  — Agent
  - [ ] 4.1 Delete the remote and local tag `v0.1.0-rc.1`; confirm `git ls-remote --tags origin` is empty
  - [ ] 4.2 Branch `release-final`: `versionName = "0.1.0"`, remove the rc heading; gate; open PR "E3"
  - [ ] 4.3 Owner: merge E3
  - [ ] 4.4 Create the `release tags` ruleset for `refs/tags/v*` (design C5); confirm with `GET /rulesets`

- [ ] 5. Owner handover documents (R6)  — Agent
  - [ ] 5.1 Add `docs/RELEASE_CHECKLIST.md`: key creation (separate GitHub and Play keys), the `release` environment
        (required reviewer, deployment tags `v*`), the four secrets and `RELEASE_CERT_SHA256`, store listings and
        screenshots, Play Console and F-Droid steps
  - [ ] 5.2 Add the two notes to `docs/RELEASE.md` (rc tag must be deleted; a missing environment is created unprotected)
  - [ ] 5.3 Repeat the open decisions in the checklist: GPL additional permission for the Play build; Play Console
        declarations for `dataSync` and `SCHEDULE_EXACT_ALARM`
  - [ ] 5.4 Leave `https://simplyfahim.pages.dev/Muxlume` untouched; gate; PR; green CI
  - [ ] 5.5 Owner: merge

- [ ] 6. Real-device test pass (R7)  — Owner (agent prepares the list, task 5.1)
  - [ ] 6.1 Recording: live TS and HLS channel for a few minutes, play the result; schedule with and without
        "Alarms & reminders"; stop from the notification; fill the storage; drop the network; delete from the file manager
  - [ ] 6.2 Parental PIN: set, lock a group, check Live TV, Home, search, guide, history, reminders; Bluetooth or
        notification controls while locked; wait out the 5-minute relock with a locked item playing; 5 wrong tries
  - [ ] 6.3 Back gesture: edge swipe and toolbar back in Appearance, TV Guide → Settings and other Settings pages; hold and
        cancel; reduced motion; one phone on Android 13 or older
  - [ ] 6.4 Hide/rename/pin (long press), Hidden channels, guide programme search
  - [ ] 6.5 Backup and restore on a second device or after Delete all data
  - [ ] 6.6 Crash report: trigger a crash, restart, check Share last crash and that links are removed
  - [ ] 6.7 Play in-app update from a build installed through Play (needs a newer version published)
  - [ ] 6.8 Owner reports results; the agent updates `docs/TESTING.md` with what was verified and fixes what failed

- [ ] 7. The real release  — Owner (agent assists)
  - [ ] 7.1 Owner: create keys, the `release` environment with reviewer and secrets, `RELEASE_CERT_SHA256` (checklist 5.1)
  - [ ] 7.2 Owner: decide the Play licence question (or drop Play updates)
  - [ ] 7.3 Agent: confirm no `v0.1.0-rc*` tag exists and `versionName` is `0.1.0`, `versionCode` 1 (or greater than any previous tag)
  - [ ] 7.4 Owner: `git tag -s v0.1.0 && git push origin v0.1.0` (or run *Release (GitHub)* with the tag); approve the `release` environment
  - [ ] 7.5 Agent: watch the run; verify the APK certificate fingerprint, assets, `SHA256SUMS.txt`, SBOM and attestation; report
  - [ ] 7.6 After a successful release: change the README status line from "not released yet" and add the real download link
