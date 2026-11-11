# Changelog

All notable changes are listed here. Versions follow [SemVer](https://semver.org); the release
workflow requires a `## [x.y.z]` section for the version being tagged.

## [Unreleased]

### Added
- Local backup and restore (Settings → Backup and restore): one file with playlists (including links
  and logins), favorites, watch history and settings, encrypted with a passphrase you choose
  (AES-256-GCM, PBKDF2-SHA256). Nothing is uploaded. Playlists that point to a file on the device are
  left out.
- TV guide (XMLTV), off until enabled per playlist: guide link on the playlist form or the one the
  playlist announces, matching by id, name or by hand, now/next in Live TV and the player, a timeline
  guide with programme details, a 12-hourly Wi-Fi-only background refresh, and reminders (a
  notification 5 minutes before; inexact timing; needs notification permission on Android 13+).
- Support: highlighted entry, a separate "More ways to pay" card, tap the QR code to enlarge it with
  the payee name and UPI ID, and a link to the project's GitHub repository.

### Removed
- Personal GitHub and Instagram links from the Support screen.

### Changed
- HTTP permission is now per source: one source allowing HTTP no longer lets other sources use it.
- Imports no longer block the app: new lists are staged and swapped in at the end.
- Long channel, movie and series lists load in pages.
- Search uses a full-text index and matches word beginnings ("bbc on" finds "BBC One").
- Channel up/down works through the whole list it was picked from (provider order, A–Z, group or favorites), from the app, a remote, the notification or Bluetooth.
- Renaming a source no longer re-downloads it.
- All UI text is in string resources, ready for translation.
- The Support screen is more compact.

### Fixed
- Links to HLS streams without ".m3u8" now play (retried as HLS once).

### Added
- Light theme and an Appearance screen: System/Light/Dark, five accent colours, Material You accent
  on Android 12+, AMOLED black for dark mode, and reduced motion.
- Support screen with an on-device UPI QR code (no web service), copy/pay/share/save actions, and
  the project and developer links; Help & about is now a focused troubleshooting/licenses screen.

### Fixed
- "Pay with UPI" found no UPI apps on Android 11+ (missing package-visibility query).

### Changed
- Licensed under GPL-3.0-or-later.

## [0.1.0] - unreleased

First preview (not tagged yet).

### Added
- M3U/M3U8 playlists from a link or a local file; Xtream Codes-compatible accounts with connection test.
- Live TV with groups, favorites, recently watched, sorting and search.
- Movies and series with provider metadata, seasons, episodes, watchlist and resume.
- Media3 playback: HLS, DASH, RTSP and progressive streams, track selection, picture size, speed,
  picture-in-picture, optional background audio, media notification, automatic reconnect.
- Encrypted storage of playlist links and credentials; opt-in for unencrypted HTTP; no backups.
- CI, security, CodeQL, dependency review, workflow audit, release, Google Play and F-Droid readiness workflows.
