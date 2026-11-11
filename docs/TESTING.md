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
| `DatabaseTest` (Robolectric, Room) | Channel up/down in provider, A–Z, group and favorites order; staged rows never shown; migrations 1 → 3 and 2 → 3 from the exported schema, including the search index |
| `XmltvParserTest`, `EpgMatcherTest`, `EpgRepositoryTest`, `EpgTimeTest` (Robolectric for the repository) | XMLTV times and zones, gzip, DOCTYPE/entity handling, nesting and size limits, stop-less programmes, matching by id then name then manual, now/next, 7-day window, staged refresh keeping the old guide on failure, idempotent reminders |
| `SleepTimerTest` | Timer fires once after the chosen minutes, restart replaces it, 0 cancels; buffer modes satisfy ExoPlayer's ordering rules |
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
airplane mode during playback, Bluetooth/headset buttons, TalkBack on the main screens, 200% font size.
