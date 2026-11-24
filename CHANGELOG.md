# Changelog

All notable changes are listed here. Versions follow [SemVer](https://semver.org); the release
workflow requires a `## [x.y.z]` section for the version being tagged.

## [Unreleased]

### Added
- Hide, rename and pin channels: press and hold a channel in Live TV or the guide. Hidden channels leave
  the lists, search, the guide, channel up/down and recent channels; Settings → Hidden channels brings them
  back one by one or all at once. Pinned channels come first in lists, the guide and channel up/down.
  Your choices survive playlist refreshes and are part of the backup.
- Recording (programme sheet → Record, Settings → Recordings): copies a live stream byte for byte into a
  folder you choose, with no re-encoding and no storage permission. Direct TS and progressive streams, and HLS
  with TS segments; DASH, RTSP, DRM, encrypted and fragmented-MP4 streams are refused with the reason. A recording
  starts at once or at the programme's start. With the "Alarms & reminders" permission it starts by itself;
  without it you get a notification to tap. Recordings run in a foreground service with a Stop button, keep the
  partial file if the connection drops or the storage fills, and are cut at Android's 6 hour limit for such
  services (on Android 15 and later) with a note saying so. Play, stop and delete them in the Recordings screen.
  Recordings are not part of the backup.
- Parental controls (Settings → Parental controls): a 4 to 8 digit PIN and a choice of groups to lock per
  playlist. Locked groups disappear from lists, search, the guide, history, continue watching, recent
  channels, reminders and playback (also from the media notification and Bluetooth) until you enter the PIN.
  The app locks again after 5 minutes in the background or on "Lock now". Five wrong tries lock the PIN out for
  30 seconds, doubling each time. A forgotten PIN is reset only by Settings → Delete all data. Backups keep
  which groups are locked, never the PIN. It keeps casual users and children out; it is not protection against
  someone who can read the app's files.
- Google Play build: "Check for updates" in Help & about uses Play in-app updates (the `play` build only; it asks
  Google Play when you tap, and the F-Droid and GitHub builds contain none of Google's libraries).
- Local crash report: after a crash, Help & about offers "Share last crash" (the error, app version and phone
  model, with web links removed, capped at 64 KB). It stays on the device until you share it; Delete all data
  removes it. No third-party crash reporting.
- Guide search: find upcoming programmes by title on the guide screen and open their details.
- Default audio and subtitle language (Settings → Playback): Automatic (as before), the device language, a language from a short
  list, or subtitles off. Used when a stream has that language; what you pick in the player's options still wins for that
  stream. Part of the backup.
- Alternative servers: the Servers button under the channel name in the live player lists the channel itself, the
  servers you saved for it, and channels with the same name in your playlists ("Same name": equal once case, accents,
  brackets, quality words such as HD or 4K and country prefixes such as "UK:" are ignored), then channels with a similar
  name ("Similar name": a typo or a plural; numbers must match, so Sky Sports 1 never matches Sky Sports 2 and ITV never
  matches ITV +1). Playlists other than the channel's own come first. Tap a row to switch to it; "Add as server" saves it
  for the channel (kept across refreshes and in the backup) and "Remove" deletes it. When a channel fails for good (after the
  usual retries) MUXLUME tries its saved servers, then same-name channels, at most 3 per failure and never one twice, and
  says which playlist it is trying; similar-name channels are only used when you tap them. The error screen offers "Try
  another server". Turn the automatic part off in Settings → Playback. Each server uses its own playlist's login and HTTP
  setting, locked groups and hidden channels are never offered, and playing another server does not change the channel's
  watch history. Matching happens on the device.
- Channel picker: the Channels button in the live player lists the channels of the list you came from (provider order,
  A to Z, a group or favourites) over the video, which keeps playing until you choose one. Typing searches the live channels
  of the same playlist. Hidden channels, and locked ones while locked, are not listed; channel up and down keep that list.
- Channel number jump: in the live player, type the number on a remote or keyboard (it plays after a short pause or on
  Enter; Back clears it) or tap "Go to channel" for a number pad. The number is the channel's number in its playlist
  (tvg-chno or the provider's number); a playlist without numbers counts channels in provider order. "No channel N"
  is shown when nothing matches. "Switch to last channel" (and the remote's Last channel key) goes back to the channel
  before this one. Channel up and down keep following the list you came from where the new channel is in it.
- Record from the player: a Record button in the live player records the programme airing now (from the guide, with
  the padding above), or, for a channel without guide data, asks for 30 minutes, 1, 2 or 4 hours and records from now
  with no padding. The button turns red while that channel is being recorded and stops it. The recording uses its own
  connection: a provider that allows one stream per account may refuse it or end playback.
- Recording padding and reconnect (Settings → Recordings): recordings start 1 minute early and end 5 minutes late by
  default (choose 0 to 5 minutes early, 0 to 15 late), so the start and end of a programme are not cut off; padding
  never runs into the next recording on the same channel. If the stream drops, MUXLUME reopens it and keeps appending
  to the same file, and stops with a network error only after two minutes without data. The file has a gap at each
  reconnect. Padding is part of the backup.
- Automatic playlist refresh (Settings → Playlists): off by default, or daily, every 3 days or weekly, in the background
  while the phone is online and the battery isn't low (Wi-Fi only follows the TV guide setting). A playlist that fails
  keeps its channels and shows the error; favourites, hidden channels and locks are kept. A refresh you start yourself
  while a background one runs waits for it instead of downloading twice. Part of the backup.
- Guide filters: show only your favorite channels, or only what is on now (today), together with the group and day.
- Sleep timer in the player options (15 to 90 minutes, then pauses).
- Buffering setting (Settings → Buffering): normal, more or less buffer ahead.
- Local backup and restore (Settings → Backup and restore): one file with playlists (including links
  and logins), favorites, watch history and settings, encrypted with a passphrase you choose
  (AES-256-GCM, PBKDF2-SHA256). Nothing is uploaded. Playlists that point to a file on the device are
  left out.
- Three builds from one source: `fdroid` (clean), `github` and `play`. The GitHub build has a manual
  "Check for updates" button in Help & about.
- TV guide (XMLTV), off until enabled per playlist: guide link on the playlist form or the one the
  playlist announces, matching by id, name or by hand, now/next in Live TV and the player, a timeline
  guide with programme details, a 12-hourly Wi-Fi-only background refresh, and reminders (a
  notification 5 minutes before; inexact timing; needs notification permission on Android 13+).
- Support: highlighted entry, a separate "More ways to pay" card, tap the QR code to enlarge it with
  the payee name and UPI ID, and a link to the project's GitHub repository.
- Recording warns before it schedules a clash: when the playlist is an Xtream account whose connection limit
  the recordings would exceed at some moment (the limit is read at each import), or when a recording would
  cut a recording of the same channel short. You can record anyway or cancel; nothing is saved until you choose.
- Move up and Move down for favorites: press and hold a channel in Live TV → Favorites. The Favorites list, the home
  row, the player's channel list and channel up/down all follow the same order. Until you move something, favorites stay
  newest first; a favorite you add later goes to the top. The order is part of the backup (older backups restore in
  the default order).

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
- Cancelling a playlist import (the Cancel button, or leaving while it runs) now ends the download at once, also while the provider is slow to
  send; it used to wait for the connection to time out, hold the playlist's turn meanwhile, and then show the cancellation as a provider error. What
  was downloaded so far is discarded, and the playlist keeps its channels, favorites and last update time.
- A channel the playlist gives no name (a bare address, or an empty title) is called "#N" (its place in the playlist) instead of the last part of its address, which
  can be a token or a login and was shown in lists, search, history and the notification. Names the playlist gives are unchanged, and so are
  channel keys, so favorites stay.
- Playback requests now belong to the channel they were made for: after a quick switch to another channel, or a failover to another playlist,
  a request that was still to be made (a playlist reload, the next segment) carries its own channel's headers and HTTP setting, not the new one's.
- Error messages show only text the app wrote or a fixed text for the kind of failure; the wording of a network, system or provider error
  (which can contain an address, a login or what a server sent) is no longer displayed. The crash report keeps the kind of error and the
  stack but no longer any message.
- Plain HTTP is allowed per playlist for everything that playlist owns. A playlist's logos and posters, and the notification artwork of its
  channels, used HTTP whenever any playlist allowed it; now only the playlist they belong to does (an image already cached is still shown).
  Allowing HTTP is withdrawn from a stream that is playing as soon as you turn it off for its playlist.
- An https playlist (a link) can now allow its http:// channels: Add or edit shows the "allow unencrypted HTTP" choice for every playlist and account,
  off by default, and saving keeps it. Before, only an http:// link could be allowed, and saving a restored playlist with a new name
  turned its choice off.
- A playlist's Referer header no longer goes whole to other servers: a redirect target or an HLS segment on another origin gets only its origin
  (scheme and host), so a path, token or login in it is not passed to a CDN. Same-origin streams and the User-Agent are unchanged; a CDN that
  checks the Referer's path or query will refuse such requests.
- SECURITY.md and PRIVACY.md said the playlists' data was encrypted: only the playlist link, guide link and Xtream login are. An M3U playlist's channel
  stream links and headers are plain text in the app's private database; both documents now say so.
- TV guide: a refresh from the screen and the background update no longer mix their downloads into one guide, turning the guide off
  (or off and on) while it downloads leaves it empty, and a guide downloaded from a feed you have since changed is not shown. Refreshes
  of one source take turns; other sources are not held up; cancelling a refresh stops its download at once, and a refresh that gave up
  no longer records an error.
- Playlists over the limit of 500,000 entries failed silently: the first 500,000 were published as if they were the whole playlist.
  The refresh now fails with an error and keeps the previous channels, favorites and last-success time. A playlist with exactly that many
  entries still imports.
- Parental lock: when the app relocks, a protected recording you were playing now stops too (it kept playing),
  a channel that failed over to another server stops if the channel you chose is locked, and a channel change
  made while the lock was being checked is no longer stopped by the answer for the previous channel.
- Settings → Delete all data now also resets every setting (the next launch shows the welcome screen, as on a new install), releases
  the write access to your recordings folder (only read access was released), stops playback and a running
  recording first, can be repeated if it was interrupted, and reports success only when it is finished. Your recording
  files and saved backups are not deleted; the confirmation and the privacy policy now say so.
- Links to HLS streams without ".m3u8" now play (retried as HLS once).
- Back gesture: the screen no longer shrinks and snaps at the end, and screens no longer show through each other
  while moving between screens (the TV guide screen also got its missing background).
- Refreshing a playlist that answers with an empty file or an HTML page (HTTP 200) no longer empties
  its channels: the previous channels stay and the source shows an error. A valid playlist that the provider
  emptied (only the "#EXTM3U" header) still removes them. Only a source that already has channels is protected,
  and a playlist without an "#EXTM3U" header that lists nothing counts as an error, not as emptied.
- Saving a different playlist link or login while a refresh is running now imports the new one; the
  paused refresh's old channels are dropped instead of being published under the new link.
- Favorites, hidden and pinned channels, history and reminders stay with a channel after a playlist edit
  that changes its group or stream link, when the playlist gives it a tvg-id that no other channel shares, and on
  later refreshes while its group and link stay as they are, even if the playlist was emptied and restored in
  between. The match is part of the backup.
- While another server plays a channel, channel up/down, number entry and the channel picker follow the
  channel you chose and its list, and only that channel is highlighted, also in Favorites that hold the same
  channel from two playlists.
- A programme's sheet drops "Remind me" when the programme starts and "Record" when it ends, also if the
  sheet stayed open while the app was in the background.
- A recording no longer shows as saved when the file could not be opened, written or closed: it is marked
  stopped with the reason (storage full, file could not be written), the part already recorded is kept, and an
  empty file is removed.

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
