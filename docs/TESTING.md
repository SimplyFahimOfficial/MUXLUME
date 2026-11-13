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
| `SourceRepositoryTest` (Robolectric, in-memory Room) | Import counts and duplicates, labels and secrets not leaking URLs, favorites surviving refresh, failed refresh keeping data, cancellation rollback, cascade delete, Xtream import |
| `HttpTest` | URL redaction, playable schemes, download limit, playback ids, LIKE escaping |
| `DatabaseTest` (Robolectric, Room) | Channel up/down in provider, A–Z, group and favorites order; staged rows never shown; channel preferences: kept across a refresh, hidden channels absent from lists, groups, search, favorites, recents and up/down, renames shown and searchable (wildcards literal), pinned-first order in lists and up/down, restore all, cascade delete, `visible_items` columns match `items`; migrations 1 → 3, 2 → 3, 3 → 5 and 4 → 5 from the exported schema, including the search index |
| `XmltvParserTest`, `EpgMatcherTest`, `EpgRepositoryTest`, `EpgTimeTest` (Robolectric for the repository) | XMLTV times and zones, gzip, DOCTYPE/entity handling, nesting and size limits, stop-less programmes, matching by id then name then manual, now/next, 7-day window, staged refresh keeping the old guide on failure, idempotent reminders, programme search (upcoming only, hidden channels left out, `%` and `_` literal) |
| `ParentalLockTest` | PIN stored as a salted hash, 4–8 digits only, wrong-try countdown, 30 s lockout doubling and capped, lockout kept across a restart, new process starts locked, nothing enforced without a PIN, relock after 5 minutes in the background (also when the timer never ran), reset by Delete all data (fake clock) |
| `ParentalDbTest` (Robolectric, Room) | Locked groups absent from lists, groups, lookups, search (also by a chosen name), favorites, channel up/down in every order, the guide, programme search, history, continue watching, recent channels, reminders and the hidden-channels list; the lock is per playlist; episodes follow their series |
| `LockAwareDaoTest` | Every DAO query returning items, history, reminders or programme hits takes `:unlocked`, or is on a short list with the reason it may not |
| `SleepTimerTest` | Timer fires once after the chosen minutes, restart replaces it, 0 cancels; buffer modes satisfy ExoPlayer's ordering rules |
| `BackupCryptoTest`, `BackupRepositoryTest` | Round trip, no plaintext in the file, wrong passphrase, tampered header or body, non-backup files, restore on a second database (sources, favorites, history keys remapped, channel preferences, settings), channel preferences validated (blank or over-long keys, over-long names, unknown playlists), locked groups kept and the PIN never saved, no duplicates on a second restore, newer history kept, file playlists left out |
| `UpdatesTest`, `GithubUpdatesTest` (github flavor) | Version comparison; newer, up-to-date and foreign-link release responses |
| `SupportUiTest` | Highlighted entries, QR dialog with payee and UPI ID, personal links gone |
| `UiSmokeTest` (Robolectric Compose) | First launch shows the disclaimer, HTTP acknowledgement gate, Xtream form validation, masked password |

CI also launches the minified release APK on an emulator (`scripts/ci/emulator-smoke.sh`) to catch
R8 problems.

`VisualRender` renders key screens to PNG for a manual look and is skipped by default:
`./gradlew testDebugUnitTest --tests '*VisualRender*' -Pmuxlume.visual=build/visual`.

## Not automated yet

Player lifecycle, orientation, audio focus, PiP and network-recovery behaviour need instrumented
tests on a device with locally generated media fixtures (planned). Until then, check by hand:
playback of an HLS and a TS stream, the guide (scroll the timeline, set a reminder, deny and allow the notification permission), a reminder firing and surviving a reboot, rotate, PiP, leave the app with background audio on and off,
airplane mode during playback, the parental PIN (set it, lock a group, check it is gone from Live TV, Home, search, the guide, history and reminders; try Bluetooth/notification controls while locked; wait out the 5-minute relock with something from a locked group playing), Bluetooth/headset buttons, TalkBack on the main screens, 200% font size.
