# Tasks: player, recording, playlist and guide features

One PR per feature, in this order. Every PR: branch off `origin/main`, tests with the code, docs (CHANGELOG `[Unreleased]`, README status
table, `docs/TESTING.md`, `docs/ARCHITECTURE.md` where design changes), then the project gate (`./gradlew testDebugUnitTest lintDebug
assembleRelease`, detekt on the unchanged baseline, whitespace check, `apk_audit.py` for fdroid, github and play), open the PR, wait for green CI,
report what is device-only. The agent never merges. Wait for the owner's merge before starting the next PR. "R" = requirement in `requirements.md`.

Before PR 1: owner answers D1–D3 (`design.md`, "Decisions for the owner"), and the 0.1.0 release (`release-0-1-0` spec) is tagged.

- [ ] 1. Guide filters, F7 (R F7)
  - [ ] 1.1 `GuideDao.linkedPage` gains `favoritesOnly` and `onNowAt`; keep `:unlocked`
  - [ ] 1.2 `GuideFilter` enum and `filter`/`onNowAt` state in `GuideViewModel`; recompute `onNowAt` on resume; hide "On now" for other days
  - [ ] 1.3 Filter `PillRow` in `GuideScreen`; empty state names the filter; strings
  - [ ] 1.4 Tests: programmes past/now/future, favourites, filter × group, hidden and locked excluded
  - [ ] 1.5 Gate, docs, PR

- [ ] 2. Default audio and subtitle language, F5 (R F5)
  - [ ] 2.1 Check what media3's default selector already does for audio language on the pinned version; record the result in the PR
  - [ ] 2.2 `AppSettings` fields, keys, setters; `BackupSettings` fields (nullable); backup round trip
  - [ ] 2.3 Pure `withLanguages(...)` for `TrackSelectionParameters`; apply at service start and on settings change without clearing manual overrides
  - [ ] 2.4 Settings → Playback pickers; strings; explain Automatic
  - [ ] 2.5 Tests: all five modes, device-language mapping, override kept, backup
  - [ ] 2.6 Gate, docs, PR; note that real multi-track streams are device-only

- [ ] 3. Background playlist refresh, F6 (R F6)
  - [ ] 3.1 `PlaylistRefresh` setting (default Off) and mapping to hours; backup field
  - [ ] 3.2 Per-source `Mutex` in `SourceRepository.refresh` with the "someone just did it" early return
  - [ ] 3.3 `PlaylistRefresher` (due check at 90% of the interval, errors stay per source) and `PlaylistRefreshWorker`
  - [ ] 3.4 Schedule/cancel next to the guide collector in `MuxlumeApp`; rename the Wi-Fi switch text to cover both
  - [ ] 3.5 Settings → Playlists picker; strings
  - [ ] 3.6 Tests: due/not due, one source failing, concurrent calls, favourites/prefs/locks/recordings survive, interval mapping
  - [ ] 3.7 Gate, docs, PR; scheduling and Doze are device-only

- [ ] 4. Recording padding and reconnect, F2 (R F2)
  - [ ] 4.1 Room 6 → 7: `leadSeconds`, `trailSeconds`; AutoMigration, `7.json`, migration test
  - [ ] 4.2 Settings `recordLeadMinutes`/`recordTrailMinutes`; copy into the row in `Recordings.add`; `RecordingPlan` uses the padded start
  - [ ] 4.3 `scheduledAfter`, `markMissed`, alarm time use the window; `nextStartOnChannel` and the end clamp in `RecordingService.prepare`
  - [ ] 4.4 `Recorder.recordDirect` reconnect loop with backoff and `giveUpAfterMs`; HLS time-based failure rule and sequence-restart handling
  - [ ] 4.5 Tests: drop and resume (bytes of both connections in order), server stays down (window, partial kept), Content-Length end, HLS 404 recovery,
        sequence restart, padding maths, overlap clamp, migration; `LockAwareDaoTest` still green
  - [ ] 4.6 Settings → Recordings pickers; strings; docs (note the TS discontinuity and fixed-URL limit)
  - [ ] 4.7 Gate, PR; device-only: service, alarms, real network drops

- [ ] 5. Record from the player, F1 (R F1)
  - [ ] 5.1 `Recordings.add(pad)` and `addNow(seconds)`; `RecordingDao.runningFor`
  - [ ] 5.2 Record control, indicator and stop in `PlayerScreen.Controls`; length dialog when there is no guide; no-folder message with the pointer
  - [ ] 5.3 Strings; docs note on the one-connection limit
  - [ ] 5.4 Tests: `addNow`, programme path, `runningFor` incl. lock
  - [ ] 5.5 Gate, PR; device-only: the control and the second connection on a real provider

- [ ] 6. Channel number jump, F3 (R F3)
  - [ ] 6.1 `ItemDao.byNumber`, `hasNumbers`, `nthLive` (lock-aware, `visible_items`)
  - [ ] 6.2 Digit-buffer state machine (pure, fake clock) and the key handling for digits, numpad, Enter, Back, `KEYCODE_LAST_CHANNEL`
  - [ ] 6.3 "Go to channel" number pad sheet; "No channel N" message; "Previous channel" control and `previousMediaId`
  - [ ] 6.4 List choice for the jump (R F3.5)
  - [ ] 6.5 Tests: queries incl. hidden/locked/fallback to position, state machine timings
  - [ ] 6.6 Gate, PR; device-only: remote keys and the overlay

- [ ] 7. Channel picker, F4 (R F4)
  - [ ] 7.1 `ChannelList.pagingSource(...)`; `ItemDao.searchLive` (lock-aware)
  - [ ] 7.2 `ChannelPickerSheet` with search, paging, current-channel mark, labels, remote focus, Back closes
  - [ ] 7.3 Choosing a row keeps the list for channel up/down
  - [ ] 7.4 Tests: paging sources per order, search, hidden/locked excluded; a Compose test that rows are labelled and a click plays
  - [ ] 7.5 Gate, PR; device-only: remote navigation, TalkBack, very large lists

- [ ] 8. Alternative servers, F8 (R F8)
  - [ ] 8.1 `ChannelNames.normalise` and `tier` with the constants and a 40+ pair test table (positive and negative, numbers, "plus", country tags)
  - [ ] 8.2 `ItemDao.liveByWords`; `AlternativeFinder` ordering and cap; Robolectric tests (SAME before SIMILAR, other playlist first, self excluded,
        hidden/locked excluded)
  - [ ] 8.3 Room 7 → 8: `channel_servers`, `ChannelServerDao` (`ServerRow`), `8.json`, migration test; backup (validated, unknown playlists dropped);
        add `ServerRow` to `LockAwareDaoTest`
  - [ ] 8.4 `MediaIds` primary extra; history is not written while a non-primary server plays
  - [ ] 8.5 `ServersSheet` and the list in the error panel: Saved, Same name, Similar name; add/remove; never a URL
  - [ ] 8.6 `FailoverPlan` (pure) and its wiring in `PlaybackService.Recovery`: SAME only (per D1), max 3, no repeats, reduced retry budget on a server,
        status line, settings switch (default on)
  - [ ] 8.7 Tests: `FailoverPlanTest`, `ChannelServerDbTest`, backup round trip, migration
  - [ ] 8.8 Gate, PR; device-only: real failover with a dead stream, notification and Bluetooth behaviour while failing over

- [ ] 9. After the last PR
  - [ ] 9.1 Update `docs/DESIGN_SYSTEM.md` (bottom-sheet pattern, option rows, PIN fields, recording states; drop finished items from "Planned")
  - [ ] 9.2 Extend the device checklist in `docs/RELEASE_CHECKLIST.md` with these features
  - [ ] 9.3 Owner: device pass; the agent fixes what fails and updates `docs/TESTING.md` with what was verified
