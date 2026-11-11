# F-Droid

MUXLUME is not on F-Droid yet. F-Droid builds apps from source on its own servers after a merge
request to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) is reviewed; no GitHub workflow can
publish there. `fdroid-readiness.yml` checks the prerequisites on every pull request.

## Current state

| Requirement | State |
|---|---|
| FLOSS licence | Yes – GPL-3.0-or-later (`LICENSE`), set in `fdroid/com.muxlume.app.yml` |
| Public source | **No** – the repository is private |
| No proprietary dependencies | Yes – AndroidX, Kotlin, OkHttp, Coil only (Apache-2.0); no Play services/Firebase |
| No prebuilt binaries in the repo | Yes (Gradle wrapper only, validated in CI) |
| Native code | Only AndroidX libraries (`androidx.graphics.path`, DataStore shared counter), built from AOSP sources |
| Dependency metadata blob | Disabled (`dependenciesInfo`) |
| Anti-features | Likely none. Users add their own sources; F-Droid reviewers may still discuss `NonFreeNet` for IPTV players |
| Fastlane metadata | Title, descriptions, icon, feature graphic, changelog for versionCode 1. Screenshots still needed |
| Release tag | None yet |

## Flavors

F-Droid builds the `fdroid` flavor (`gradle: - fdroid` in the metadata template). CI fails if that flavor
depends on Google Play services, Firebase, Play libraries or billing. The `github` and `play` flavors share
all code with it plus whatever is in `app/src/github` or `app/src/play`.

## Submitting

1. Resolve the missing items above and tag a release.
2. Test locally with fdroidserver (needs Docker or a Debian VM):
   ```sh
   git clone https://gitlab.com/fdroid/fdroiddata && cd fdroiddata
   cp ../MUXLUME/fdroid/com.muxlume.app.yml metadata/
   fdroid readmeta && fdroid lint com.muxlume.app && fdroid build -v -l com.muxlume.app
   fdroid scanner com.muxlume.app
   ```
3. Open a merge request to fdroiddata with the metadata file; answer reviewer questions.
4. After merge, F-Droid builds and signs with its own key (see [RELEASE.md](RELEASE.md#keys-and-stores));
   updates appear a few days after each new tag (`UpdateCheckMode: Tags`).

Optional later: reproducible builds, so F-Droid can publish the developer-signed APK and users can
move between GitHub and F-Droid builds.
