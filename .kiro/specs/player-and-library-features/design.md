# Design: player, recording, playlist and guide features

Read against `main` at `3f90862`. Every claim about existing code names the file. Nothing here is implemented.

## Delivery plan

One PR per feature, in this order (small and isolated first; each ends with the project gate and green CI):

| PR | Feature | Schema | Why this position |
|---|---|---|---|
| 1 | F7 guide filters | none | isolated, small |
| 2 | F5 default languages | none | settings + service only |
| 3 | F6 playlist refresh | none | worker, independent of the player |
| 4 | F2 recording padding and reconnect | 6 → 7 | F1 builds on its padding rule |
| 5 | F1 record from the player | none | needs F2 |
| 6 | F3 channel number jump | none | player UI |
| 7 | F4 channel picker | none | player UI, shares `ChannelList` paging with F3 |
| 8 | F8 alternative servers | 7 → 8 | largest; touches service, player, backup |

Every new query that returns items, history, reminders, programme hits, recordings or servers takes `:unlocked`
(`LockAwareDaoTest` fails otherwise). New guarded return types (`ServerRow`) are added to that test's list.
Every new setting is added to `BackupSettings` as a nullable field (old backups stay valid).

## F7. Guide filters (PR 1)

- `GuideDao.linkedPage(sourceId, group, unlocked)` gains `favoritesOnly: Boolean` and `onNowAt: Long?` (5 parameters, under
  detekt's limit):
  `AND (NOT :favoritesOnly OR EXISTS (SELECT 1 FROM favorites f WHERE f.sourceId = i.sourceId AND f.stableKey = i.stableKey))
   AND (:onNowAt IS NULL OR EXISTS (SELECT 1 FROM programs p WHERE p.sourceId = l.sourceId AND p.channelId = l.channelId
        AND p.pending = 0 AND p.start <= :onNowAt AND p.stop > :onNowAt))`
  (`l` is the `epg_links` row the query already joins, so the programme lookup uses the existing index.)
- `GuideViewModel` gets `filter: MutableStateFlow<GuideFilter>` (`ALL`, `FAVORITES`, `ON_NOW`) and `onNowAt`, set to the
  current time when "On now" is chosen and again on resume (`LifecycleEventEffect(ON_RESUME)`); it is not a ticking clock, so the
  list does not jump while scrolling. On another day than today the filter row shows only All and Favorites.
- UI: a `PillRow` of the three choices under the group pills; the empty state names the filter ("Nothing on now in this group").
- Tests: Robolectric DB test with programmes past, now and future, favourites, combined with group; hidden and locked channels
  absent; migration untouched.

## F5. Default audio and subtitle language (PR 2)

- `AppSettings` gains `audioLanguage: String?` and `subtitleLanguage: String?`: `null` = Automatic; `"device"`; `"off"` (subtitles only);
  otherwise a language tag from a fixed list (`en, hi, es, fr, de, it, pt, ar, ru, tr, bn, ta, te, mr, ur, id, ja, ko, zh`).
- A pure function `TrackSelectionParameters.withLanguages(settings, deviceLanguage)` sets `setPreferredAudioLanguage` and
  `setPreferredTextLanguage`, and for `"off"` `setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`; Automatic changes nothing, which keeps
  today's behaviour (media3's default selector already prefers the device language and the system caption settings; PR 2 starts by
  checking that on the pinned media3 version, because it decides whether "Device language" differs from Automatic for audio at all).
- `PlaybackService.onCreate` applies it once and collects the settings flow to apply later changes. It starts from the player's
  *current* parameters, so a track the user chose by hand (an override from `TrackSection` in `PlayerScreen`) is not cleared (F5.3, F5.4).
- Settings UI: two `PillRow`/dialog pickers in Settings → Playback; copy says that Automatic is the stream's own choice.
- Tests: JVM test of `withLanguages` on `TrackSelectionParameters` (all five modes, device language mapping, an existing
  override kept); `BackupRepositoryTest` round trip. Real streams with several tracks are device-only.

## F6. Background playlist refresh (PR 3)

- `PlaylistRefresh` enum (`OFF`, `DAILY`, `EVERY_3_DAYS`, `WEEKLY`) in `AppSettings`; default `OFF`.
- `PlaylistRefresher(db, sources, clock)` (plain class) holds the logic: for each source whose `refreshedAt` is older than 90% of the
  interval, call `SourceRepository.refresh`; one source failing does not stop the others (its error is already stored on it).
  `PlaylistRefreshWorker` is a thin `CoroutineWorker` over it, scheduled like `EpgWork` (periodic, `CONNECTED` or `UNMETERED`
  from the existing `guideWifiOnly` setting, battery not low). `MuxlumeApp.onCreate` already combines settings flows for the guide;
  the same collector schedules or cancels this work.
- The existing setting `guide_wifi_only` keeps its key and now covers both; the label changes to "Wi-Fi only for background refresh".
- Concurrency (F6.4): `SourceRepository.refresh` takes a per-source `Mutex`. A caller that had to wait re-reads the source and returns
  at once if `refreshedAt` changed meanwhile ("someone just did it"). The Library screen's `refreshing` set stays as it is.
- M3U file playlists are refreshed like any other (the persisted read permission is used); a revoked permission is stored as that source's error.
- No notification. The worker never retries in a loop (WorkManager's next period is the retry).
- Tests (no new dependency, so no `work-testing`): `PlaylistRefresherTest` with MockWebServer and an in-memory database: due, not due,
  one failing source, concurrent calls, kept favourites/prefs/locks; the interval-to-hours mapping is a pure function. Scheduling
  and Doze behaviour are device-only.

## F2. Recording padding and reconnect (PR 4)

**Data (Room 6 → 7, AutoMigration):** `recordings` gains `leadSeconds INTEGER NOT NULL DEFAULT 0` and `trailSeconds INTEGER NOT NULL DEFAULT 0`.
`startAt`/`endAt` stay the programme's times (shown in the list). The recording window is `[startAt − lead, endAt + trail]`.
Settings: `recordLeadMinutes` (default 1), `recordTrailMinutes` (default 5), copied into the row when it is created.

**Queries that change:** `scheduledAfter(now)` and the alarm use `startAt − lead`; `markMissed(now)` uses `endAt + trail`;
`RecordingPlan.start` takes the padded start; a new `nextStartOnChannel(sourceId, stableKey, after): Long?` returns the next padded start of another
`SCHEDULED` recording on the same channel (not a guarded type). `RecordingService.prepare` ends the recording at
`min(endAt + trail, thatStart)` (F2.3).

**Reconnect (`Recorder`):**
- `recordDirect` becomes a loop. After an `IOException` from reading, or a clean end of a stream whose length is unknown (a live feed that
  closed early), it waits 2, 4, 8, 15, then 30 s (capped) and opens a new request with the same URL and headers, appending to the same sink.
  A clean end of a stream with a known `Content-Length` (a progressive file) is still "done".
- It tracks `lastDataAt`; if `now − lastDataAt` passes `giveUpAfterMs` (constructor parameter, 120 s by default; tests pass small values) it throws `NETWORK`.
  Only the first connection's refusals (DASH, DRM, bad status on the first request) are immediate failures.
- HLS: the failure counter (3) becomes the same time rule (any successful playlist or segment resets it). If the newest sequence in a playlist
  is lower than `lastSequence`, the server restarted: `lastSequence` is reset to `firstSequence − 1`.
- shortcut: the URL is fixed for the whole recording. Tokens that expire in less than the recording length end it with `NETWORK` after the
  give-up window; re-resolving per reconnect is the upgrade.
- Tests (MockWebServer): drop and resume yields the bytes of both connections in order; a server that stays down ends the recording after the
  window with the earlier bytes kept; a clean end with Content-Length ends it; HLS playlist 404 for less than the window recovers;
  HLS sequence restart is not skipped; padding maths in `RecordingPlan`; overlap rule in `RecordingsTest`; migration 6 → 7.

## F1. Record from the player (PR 5)

- `Recordings.add` gains `pad: Boolean = true`. A new `Recordings.addNow(sourceId, key, channelName, seconds)` creates a recording
  `[now, now + seconds]` with `pad = false` and the title "<channel> <date time>" (F1.3).
- `PlayerScreen.Controls` (live only) gets a Record `IconButton` beside Options. On tap: `epg.nowNext(sourceId, key).now` → `Recordings.add`
  with that programme; if null, an `AlertDialog` with four lengths. `mediaId` already gives `(sourceId, key)`.
- Indicator and stop: `RecordingDao.runningFor(sourceId, stableKey): Flow<Long?>` (id of a `RECORDING` row, or a `SCHEDULED` one whose window
  has started). It returns a `Long`, not a guarded type. While it is non-null the control shows a red dot and tapping stops it
  (`RecordingService.stopIntent`). While another server plays (F8) the key is that of the playing server, which is what is recorded.
- No folder → `Recordings.Result.NoFolder` → the existing toast, plus an action text pointing to Settings → Recordings.
- Docs: `docs/ARCHITECTURE.md` and the Recordings screen note that a second connection may be refused by providers that allow one stream.
- Tests: `RecordingsTest` for `addNow` and the player path (programme and fixed length), `RecordingDbTest` for `runningFor`.
  The control itself is device-only.

## F3. Channel number jump (PR 6)

- `ItemDao.byNumber(sourceId, number, unlocked)` on `visible_items` (`kind = 'LIVE'`, `ORDER BY position LIMIT 1`), `hasNumbers(sourceId)` and
  `nthLive(sourceId, n, unlocked)` (`LIMIT 1 OFFSET n − 1` in provider order, the fallback of F3.2). Numbers exist already: M3U `tvg-chno`
  (`M3uParser`) and Xtream `num` (`SourceRepository`).
- `NumberEntry` state in `PlayerScreen`: digits from `KEYCODE_0..9` and `KEYCODE_NUMPAD_0..9` accumulate; a `LaunchedEffect` keyed on the buffer
  commits 2.5 s after the last digit, Enter commits at once, Back clears. A small overlay shows the digits. A "Go to channel" control opens a
  number pad sheet for touch.
- Jump: resolve the target in the playing channel's playlist; `context.playChannel(target, list)` where `list` is the current list if it
  has no group filter or the group equals the target's group, otherwise `ChannelList(PROVIDER, target.groupTitle)` (F3.5).
  Not found → a transient "No channel N" message.
- Previous channel: `PlayerScreen` keeps `previousMediaId` (updated when `ui.mediaId` changes) and a control and `KEYCODE_LAST_CHANNEL`
  play it. It resets when the player screen is left. (The 10-second history write is too slow to use for this.)
- Tests: DB test for the three queries incl. lock and hidden; a pure test of the digit-buffer state machine (timeouts with a fake clock).

## F4. Channel picker (PR 7)

- `ChannelList.pagingSource(db, sourceId, unlocked)` in `playback/ChannelList.kt`, next to `neighbour`, so "the list" has one definition:
  PROVIDER → `page(sourceId, LIVE, group, false, unlocked)`, NAME → `page(..., true, ...)`, FAVORITES → `favoritesPage(LIVE, favoritesSourceId, unlocked)`.
- `ChannelPickerSheet` (`ModalBottomSheet`, full height) with a search field and a `LazyColumn` over a `Pager`; rows reuse the live row look and
  the `Play <name>` label; the current channel is marked. Search uses a new lock-aware `ItemDao.searchLive(match, like, sourceId, limit, unlocked)`
  (the existing `search` is global across kinds).
- Choosing a row calls `playChannel(item, list)` and closes the sheet; playback of the old channel continues until then. Back closes the sheet.
- shortcut: the list opens at the top; scrolling to the playing channel needs its rank in the ordering, which is the upgrade if it is missed in use.
- Tests: DB tests for `searchLive` and the paging sources (provider, A–Z, group, favourites; hidden, locked); the sheet is device/Compose-test only
  (a Robolectric Compose test for "rows are labelled and a click plays" is possible with the existing setup).

## F8. Alternative servers (PR 8)

### Name matching (`playback/ChannelNames.kt`, pure)

`normalise(name) → Normalised(tokens, key)`:
1. Unicode NFKD, strip combining marks, lower case.
2. Drop bracketed parts `(…)`, `[…]`, `{…}`.
3. Strip a leading country or provider tag only when the tag is an ISO 3166 alpha-2 or alpha-3 code (plus `uk`) followed by `:`, `|` or ` - `
   ("UK: BBC One", "|UK| BBC One", "IN - Star Plus"). "BBC - News" keeps "BBC" because it is not a country code.
4. Replace separators `| : - _ . /` with spaces, `+` with ` plus `, drop other symbols.
5. Remove whole-word quality tokens: `hd fhd uhd sd 4k 8k hevc h264 h265 1080p 1080i 720p 576p 480p 50fps 60fps hq lq raw`.
6. Tokens are the remaining words; `key` is them joined by one space. A name with an empty key or a key under 3 characters has no alternatives.

`tier(a, b)`:
- **SAME** when the keys are equal.
- **SIMILAR** when all tokens that contain a digit ("1", "f1", "24") and `plus` tokens are equal as multisets (so "Sky Sports 1" ≠ "Sky Sports 2",
  "ITV" ≠ "ITV plus 1", "Sky Sports F1" ≠ "F2"), at least one other token is shared, and `max(jaccard(tokens), 1 − levenshtein(keyA, keyB) / max(len))` ≥ **0.75**.
  When only the letters make them close (Jaccard under 0.75), every token that differs must be within one edit (one more per 5 letters) of a token on the other
  side, so "Sky Sport News" ≈ "Sky Sports News" but "Sony SAB" ≠ "Sony MAX".
- otherwise none.
The threshold and the lists are constants with a test table of 40+ pairs (positive and negative), so a change is a visible diff.

### Finding candidates (`AlternativeFinder`)

`ItemDao.liveByWords(match, limit, unlocked)` joins `items_fts` to `visible_items` (`kind = 'LIVE'`). The finder runs two FTS queries built with
the existing `ItemDao.ftsQuery` style: all non-numeric tokens (prefix terms, AND) and, to find shorter or longer variants, the first two non-numeric tokens;
the union (cap 500) is scored in memory. The current item is dropped. Order: tier (SAME first), then other playlists before the same playlist,
then score, then provider position; at most 30 results.
shortcut: candidates come from the text index by name words, so a very different spelling ("Skysports") is not found; a trigram or normalised-name
column is the upgrade if recall disappoints.

### Saved servers (Room 7 → 8)

`channel_servers(sourceId, stableKey, serverSourceId, serverStableKey, position)`, primary key = the first four, foreign keys to `sources` (both
columns) with cascade, so they survive refreshes (keys, like favourites) and vanish with their playlist. `ChannelServerDao.forChannel(sourceId, key, unlocked):
Flow<List<ServerRow>>` (`ServerRow(@Embedded item, position)`, joined to `visible_items`, so hidden or locked servers disappear), `add` (position =
max + 1), `remove`. Included in the backup like channel preferences (validated, length-limited, unknown playlists dropped).

### Player (`PlayerScreen`, `MediaIds`)

- `MediaIds.request(item, list, primary)` adds an extra `muxlume.primary`: the channel the user chose. The Servers list is always anchored to it,
  so switching to an alternative does not change the list.
- A "Servers" control under the channel information opens `ServersSheet`: **Saved** (with Remove), then **Same name**, then **Similar name**, each row
  "<channel name> · <playlist> · <group>", the playing one marked, an "Add as server" button on found ones. No row ever shows a URL. The same list is
  shown inside the error panel with "Try another server".
- Tapping a row calls `playChannel(item, list, primary)`.

### Automatic failover (`PlaybackService`)

- `FailoverPlan` (pure, unit-tested): given the primary id, the candidate list (saved, then SAME) and a `tried` set, returns the next candidate or
  null; stops after 3 attempts; never repeats.
- In `Recovery.onPlayerError`, after the existing branches (`retryAsHls`, behind-live-window, network retries exhausted) and for errors that are not
  retried (HTTP 4xx, unsupported format), if the item is live and the setting is on: build candidates with the finder (`unlockedNow()`), resolve the
  next one with `resolve(...)` (its own login and HTTP policy, via `StreamResolver`), `setMediaItem`, `prepare`, `play`. On a failover server the retry
  budget is 1 instead of 3 (otherwise 3 servers could take about 45 s). A subtitle line "Trying another server: <playlist>" is set in the request's
  metadata, which `PlayerUi` already shows.
- `tried` and the count reset when the user starts any playback. Failover is skipped for items with the primary extra missing and kind ≠ LIVE.
- While the playing item's id differs from `primary`, `entryFor` returns null (no history write, F8.8).
- Setting: "When a channel fails, try other servers" (default on).
- Privacy: matching is local; trying a server connects to that playlist's own provider, as playing it would.
- Tests: `ChannelNamesTest`, `AlternativeFinderTest` (Robolectric DB: SAME before SIMILAR, other playlist before same playlist, numbers apart, self
  excluded, hidden and locked excluded, cap), `ChannelServerDbTest` (add/remove/order, cascade, refresh survival, lock-aware),
  `FailoverPlanTest`, backup round trip, migration 7 → 8, `LockAwareDaoTest` with `ServerRow`. The service wiring and real failover are device-only.

## Cross-cutting

| Concern | Decision |
|---|---|
| Schema | 7 (F2), 8 (F8); each with an AutoMigration and the committed `N.json`; migration tests from the previous schema |
| Settings screen | new rows grouped as Playback (languages, failover), Recordings (padding), Playlists (auto refresh, Wi-Fi only) |
| Strings | all in `strings.xml`, plurals where a count appears, no hard-coded text |
| Accessibility | every new control has a label; list rows keep ≥ 48 dp targets; remote focus works in the picker and server list |
| Docs per PR | CHANGELOG `[Unreleased]`, README status table, `docs/TESTING.md`, `docs/ARCHITECTURE.md` where the design changes |
| Security | no new permission; F8 shows no URLs; the lock applies to every new query; crash-log redaction unchanged |

## Risks

| Risk | Handling |
|---|---|
| Provider allows one connection per account: recording from the player or a failover attempt can end playback | stated in the docs and the Recordings screen; failover happens only after a failure |
| Similar-name guess plays the wrong channel | automatic failover uses only SAME; SIMILAR needs a tap (Decision D1); the threshold is tested with negative pairs |
| Name normalisation drops a real word (a country code that is a channel name) | restricted to ISO codes followed by a separator; known limit noted in the code |
| Reconnect appends a TS discontinuity | players cope with TS discontinuities; documented, not hidden |
| Background refresh drains battery or data | default Off, Wi-Fi only by default, battery-not-low constraint, interval of at least a day |
| Four PRs touch `PlayerScreen` | delivered in sequence, each rebased on the last; no parallel branches on that file |
| Unverified on a device | every PR lists what is device-only; nothing is claimed done until the owner reports it |

## Decisions for the owner

- **D1.** Automatic failover: SAME only (recommended), or SAME and SIMILAR.
- **D2.** Padding defaults (1 minute early, 5 late) and the F6 default (Off).
- **D3.** Ship these as 0.2.0 after the release tag, not before.
