# Testing

```sh
./gradlew testDebugUnitTest            # JVM + Robolectric + Compose UI tests
./gradlew lintDebug
```

All fixtures are generated in the tests or served by MockWebServer; no real IPTV service is
contacted.

| Suite | Covers |
|---|---|
| `M3uParserTest` | Extended attributes, quoted commas, unicode, empty playlists, malformed tags, rejected schemes (`file:`, `content:`, `javascript:`), custom headers and injection attempts, header isolation between entries, BOM/CRLF, over-long lines, entry limit, 200k-entry performance, VOD detection |
| `XtreamApiTest` | Active/invalid/expired/inactive accounts, 403, non-JSON and wrong-shape answers, 5xx, streamed lists with junk elements, `{}` as empty list, truncated responses, series info shapes, credential encoding in URLs, server normalization, HTTP gate |
| `PlaylistRefresherTest` (Robolectric, MockWebServer) | Due at 90% of each interval, never when off, always when never imported; only due playlists downloaded; a failing playlist keeps its error and does not stop the others; two simultaneous refreshes download once |
| `SourceRepositoryTest` (Robolectric, in-memory Room) | Import counts and duplicates, labels and secrets not leaking URLs, favorites surviving refresh, failed refresh keeping data, cancellation rollback, cascade delete, Xtream import |
| `HttpTest` | URL redaction, playable schemes, download limit, playback ids, LIKE escaping |
| `DatabaseTest` (Robolectric, Room) | Channel up/down in provider, A–Z, group and favorites order; staged rows never shown; channel preferences: kept across a refresh, hidden channels absent from lists, groups, search, favorites, recents and up/down, renames shown and searchable (wildcards literal), pinned-first order in lists and up/down, restore all, cascade delete, `visible_items` columns match `items`; migrations 1 → 3, 2 → 3, 3 → 5, 4 → 5 and 5 → 6 from the exported schema, including the search index |
| `XmltvParserTest`, `EpgMatcherTest`, `EpgRepositoryTest`, `EpgTimeTest` (Robolectric for the repository) | XMLTV times and zones, gzip, DOCTYPE/entity handling, nesting and size limits, stop-less programmes, matching by id then name then manual, now/next, 7-day window, staged refresh keeping the old guide on failure, idempotent reminders, programme search (upcoming only, hidden channels left out, `%` and `_` literal) |
| `ParentalLockTest` | PIN stored as a salted hash, 4–8 digits only, wrong-try countdown, 30 s lockout doubling and capped, lockout kept across a restart, new process starts locked, nothing enforced without a PIN, relock after 5 minutes in the background (also when the timer never ran), reset by Delete all data (fake clock) |
| `ParentalDbTest` (Robolectric, Room) | Locked groups absent from lists, groups, lookups, search (also by a chosen name), favorites, channel up/down in every order, the guide, programme search, history, continue watching, recent channels, reminders and the hidden-channels list; the lock is per playlist; episodes follow their series |
| `LockAwareDaoTest` | Every DAO query returning items, history, reminders or programme hits takes `:unlocked`, or is on a short list with the reason it may not |
| `HlsParserTest`, `RecorderTest` (MockWebServer) | HLS media and master playlists, encryption/DRM/fMP4 detection; a direct stream copied byte for byte, cut at the end time, cut off mid-stream and reopened with the bytes of both connections in order, a live feed that closes early, a server that stays down (network error after the give-up window, partial kept), the first connection failing at once, headers sent; HLS segments appended once each and in order across a sliding playlist, best variant only, stop at the end time, a playlist that stays down, one that recovers, a restarted sequence, skipped segment; DASH/RTSP/DRM/AES/fMP4 refused with nothing written; out of space and write errors; cancel; failures never name the host |
| `NumberEntryTest`, `DatabaseTest` (number jump) | Digits and numpad keys, leading zeros, five digit limit, clear; channel by number, place in the list for playlists without numbers, number clashes, hidden and locked channels not reachable; the list kept after a jump only where the target is in it |
| `RecordingsTest`, `RecordingDbTest`, `StreamResolverTest` (Robolectric) | Start rules (now / exact alarm / notification), file names, no folder / over / duplicate, alarm and service intents, re-arming and giving up after a restart; recordings hidden and unplayable while their channel is locked, one row per programme, cascade; padding copied from settings and used for the alarm and the immediate start, give-up after the padded end, next padded start on the same channel, migration 6 → 7, `addNow` (fixed length, no padding, title, no folder), running-state query; URLs, headers and HTTP setting per source, RTSP gate, locked / hidden / missing items, episodes through their series |
| `CrashLogTest` | Links removed in messages and causes in any case, the rest of the report kept, 64 KB cap, a second crash replaces the first, the handler writes then calls the previous one, an unwritable log does not hide the crash |
| `GuideFilterTest` (Robolectric, Room) | Guide favorites and on-now filters, alone and with a group: start inclusive, end exclusive, staged programmes ignored, hidden and locked channels never listed |
| `TrackLanguagesTest` | Default audio and subtitle language on the player's track selection: automatic changes nothing, a language or the device language replaces the preference, subtitles off and back on (only when the app turned them off), restoring what the player started with, hand-picked overrides kept, backup values checked |
| `SleepTimerTest` | Timer fires once after the chosen minutes, restart replaces it, 0 cancels; buffer modes satisfy ExoPlayer's ordering rules |
| `BackupCryptoTest`, `BackupRepositoryTest` | Round trip, no plaintext in the file, wrong passphrase, tampered header or body, non-backup files, restore on a second database (sources, favorites, history keys remapped, channel preferences, settings), channel preferences validated (blank or over-long keys, over-long names, unknown playlists), locked groups kept and the PIN never saved, no duplicates on a second restore, newer history kept, file playlists left out |
| `UpdatesTest`, `GithubUpdatesTest` (github flavor), `PlayUpdatesTest` (play flavor) | Version comparison; newer, up-to-date and foreign-link release responses; which Google Play states offer an update. The Play flow itself needs a build installed from Play and is by hand |
| `SupportUiTest` | Highlighted entries, QR dialog with payee and UPI ID, personal links gone |
| `UiSmokeTest` (Robolectric Compose) | First launch shows the disclaimer, HTTP acknowledgement gate, Xtream form validation, masked password |

CI also launches the minified release APK on an emulator (`scripts/ci/emulator-smoke.sh`) to catch
R8 problems.

`VisualRender` renders key screens to PNG for a manual look and is skipped by default:
`./gradlew testDebugUnitTest --tests '*VisualRender*' -Pmuxlume.visual=build/visual`.

## Not automated yet

Channel number jump: typing digits on a remote and keyboard, the 2.5 second pause and Enter, the overlay, the number pad, Back clearing, Last channel key.

Recording: the foreground service, exact alarms and the notification fallback, the 6 hour `onTimeout`, the folder picker, SD cards and external storage, and playing a saved file. By hand: record a live HLS and a TS channel for a few minutes and play the result; schedule one with and without "Alarms & reminders" allowed; stop from the notification; fill the storage; drop the network and bring it back within two minutes, then keep it off for longer; delete a file from outside the app; lock a channel's group while it records; the player's Record button with and without guide data (and the length dialog), its red state and stopping from it, and recording the channel that is playing on a provider that allows one connection.

Player lifecycle, orientation, audio focus, PiP and network-recovery behaviour need instrumented
tests on a device with locally generated media fixtures (planned). Until then, check by hand:
playback of an HLS and a TS stream, default audio and subtitle language on a stream with several tracks (the player's own track list shows what was chosen), the guide (scroll the timeline, set a reminder, deny and allow the notification permission), a reminder firing and surviving a reboot, rotate, PiP, leave the app with background audio on and off,
airplane mode during playback, the parental PIN (set it, lock a group, check it is gone from Live TV, Home, search, the guide, history and reminders; try Bluetooth/notification controls while locked; wait out the 5-minute relock with something from a locked group playing), Bluetooth/headset buttons, TalkBack on the main screens, 200% font size.
