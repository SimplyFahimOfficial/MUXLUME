<p align="center">
  <img src="brand/muxlume-wordmark.png" alt="MUXLUME" width="560">
</p>

<p align="center">A cinematic, privacy-conscious IPTV player for Android.<br>
Bring your own M3U playlists and Xtream Codes-compatible accounts.</p>

---

> **MUXLUME is only a player.** It does not include, sell or link to TV channels, subscriptions or
> copyrighted media, and is not affiliated with any provider. Add only content you are legally
> allowed to watch.

## Status

Early development, version **0.1.0** (not released yet). The table lists what is in the code today;
everything else is planned.

| Area | Implemented | Planned |
|---|---|---|
| Sources | M3U/M3U8 from a link or a local file (SAF), Xtream Codes-compatible login with connection test, edit, refresh, remove, multiple sources | Periodic background refresh, other provider formats |
| Live TV | Groups, favorites, recently watched, A–Z sort, search, channel up/down in the player, now/next | Hidden channels, custom order |
| Movies and series | Poster grids by category, detail pages with provider metadata, seasons and episodes, watchlist, resume, continue watching, next episode | Optional third-party metadata |
| Appearance | Dark and light themes, System option, five accents, Material You (Android 12+), AMOLED black, reduced motion | Layout density, shared-element transitions |
| Playback | Media3 ExoPlayer: HLS, DASH, RTSP, progressive (TS/MP4/MKV…); audio and subtitle tracks; picture size; speed (VOD); PiP; optional background audio; media notification; reconnect with backoff; diagnostics without links | Buffer settings, decoder preferences, catch-up, timeshift, recording |
| EPG | XMLTV (opt-in per playlist): matching by id, name or by hand, timeline guide, now/next, 12-hourly background refresh, reminders | Programme search |
| Other | Onboarding, settings, help & about, Support (native UPI), data deletion, encrypted local backup and restore | Parental PIN, Android TV layout |

There are no app screenshots yet; they will be added from real devices.

## Privacy and security in short

- No account, ads, analytics or trackers. Network traffic goes only to the sources you add.
- Playlist links, usernames and passwords are encrypted with an Android Keystore key and excluded from backups.
- Plain-HTTP servers are refused until you explicitly allow HTTP for a source.
- Untrusted playlists are parsed with size, line-length and entry limits, and only network stream schemes are accepted.

Details: [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md).

## Build

Requirements: JDK 21 and the Android SDK (platform 37, build tools 36).

```sh
./gradlew assembleDebug            # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug
```

The debug build installs next to a release build (`com.muxlume.app.debug`).
Docs: [architecture](docs/ARCHITECTURE.md), [design system](docs/DESIGN_SYSTEM.md),
[IPTV formats](docs/IPTV_FORMATS.md), [testing](docs/TESTING.md), [releases](docs/RELEASE.md),
[Google Play](docs/PLAY_STORE.md), [F-Droid](docs/FDROID.md), [contributing](CONTRIBUTING.md).

## Licence

[GNU General Public License v3.0 or later](LICENSE) (GPL-3.0-or-later). You may use, study, share and
modify it; derivatives must stay under the same licence.

## Community and contact

- Telegram: [t.me/Muxlume](https://t.me/Muxlume)
- Contact the developer: [simplyfahim.pages.dev/contactme](https://simplyfahim.pages.dev/contactme)
- Support development: [simplyfahim.pages.dev/pay-options](https://simplyfahim.pages.dev/pay-options)
