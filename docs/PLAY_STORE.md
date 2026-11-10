# Google Play

Nothing has been submitted to Google Play. This is the setup the `release-play.yml` workflow expects.

## Play Console

1. Create the app: name MUXLUME, package `com.muxlume.app`, free, app (not game).
2. **App signing**: enrol in Play App Signing. Decide the certificate first (see
   [RELEASE.md](RELEASE.md#keys-and-stores)); to keep GitHub and Play builds updatable into each
   other, upload the existing release key as app-signing key and register a separate upload key.
3. First upload must be manual (Play needs one bundle before the API accepts uploads): download the
   AAB from a GitHub release and upload it to Internal testing.

## Upload credentials (no JSON keys)

1. Google Cloud project → enable the *Google Play Android Developer API*.
2. Service account `muxlume-play-upload`, no Cloud roles.
3. Workload Identity Federation pool + OIDC provider for `https://token.actions.githubusercontent.com`,
   attribute condition `assertion.repository == 'SimplyFahimOfficial/MUXLUME'`, and grant
   `roles/iam.workloadIdentityUser` on the service account to that repository's principal set.
4. Play Console → Users and permissions → invite the service account **for this app only** with
   "Release to testing tracks" (add "Release to production" only if you want production drafts).
5. GitHub environments `play-testing` and `play-production` (required reviewer on production), each
   with variables `PLAY_WIF_PROVIDER` (full provider resource name) and `PLAY_SERVICE_ACCOUNT` (email).

## Publishing

Run *Release (Google Play)* with a stable tag that already has a GitHub release and a track.
The workflow verifies the AAB checksum (and its build attestation on a public repository), then
uploads. Production uploads are created as drafts: review and start the rollout in Play Console
(use a staged rollout). A tag push never publishes to Play.

Rollback: Play can't take back an update. Halt the staged rollout, then ship a fixed build with a
higher `versionCode`.

## Store listing and policy

- Use `fastlane/metadata/android/en-US/` texts and graphics; real screenshots from devices only, no
  third-party channel logos, posters or brand names in screenshots or text.
- Describe it as a player for user-supplied content; don't imply bundled channels.
- Privacy policy URL: host `PRIVACY.md` publicly (for example on the project website).
- **Data safety**: no data collected, no data shared; data is not encrypted in transit when users
  choose HTTP servers, so answer "data is encrypted in transit" as **not always**; users can delete data
  in the app. Credentials stay on device and are not "collected" in Play's sense, but re-check the
  form's current definitions.
- Content rating questionnaire: the app shows user-supplied content; answer honestly (it can display
  unrestricted content from the user's sources).
- Target API: `targetSdk = 36` meets the current requirement for phones; re-check yearly.
- New personal developer accounts must run a closed test with a minimum number of testers and days
  before production access; check the current numbers in Play Console.
