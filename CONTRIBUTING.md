# Contributing

Thanks for helping. Keep changes small and focused; one concern per pull request.

## Setup

JDK 21, Android SDK platform 37 and build tools 36. Then:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug   # all three builds
```

Robolectric caches Android jars in your home directory; if it's read-only, add
`-PtestUserHome=/some/writable/dir`.

## Principles

- Read the code you change first and follow its conventions.
- Prefer the simplest thing that fully works: no new modules, DI frameworks or wrappers without a
  measured need; no new dependency for a few lines.
- Untrusted input (playlists, provider JSON, artwork, URLs) is validated where it enters.
- Never log URLs, usernames, passwords or tokens. Never commit real playlists or credentials;
  tests use generated fixtures and MockWebServer.
- No proprietary SDKs (Google Play services, Firebase, ads, analytics): F-Droid compatibility.
- Accessible by default: content descriptions, 48 dp targets, font scaling, reduced motion.

## Pull requests

- Branch from `main`, open the PR against `main`.
- Commit messages: `type: summary` (`feat`, `fix`, `docs`, `ci`, `refactor`, `test`, `chore`).
- New logic comes with a test. Room schema changes need a migration and the exported schema.
- Update `CHANGELOG.md` under `[Unreleased]` and the docs that describe changed behaviour.
- CI must pass: Android CI, Security, CodeQL, Dependency review, Workflow audit, F-Droid readiness.
