# Architecture

One Gradle module (`:app`), package `com.muxlume.app`. More modules only when they bring a
measured build or maintenance benefit.

```
MuxlumeApp / AppContainer      manual dependency graph (no DI framework), process lifetime
core/net/Http.kt               the single OkHttp client, CleartextGate, URL validation/redaction, size limits
core/security/SecretBox.kt     Keystore AES-GCM for source secrets
data/m3u/M3uParser.kt          streaming, bounded M3U parser (Iterator)
data/xtream/XtreamApi.kt       Xtream Codes-compatible player_api client, lenient JSON, streamed lists
data/SourceRepository.kt       add/edit/remove sources, transactional import (refresh)
data/db/                       Room: sources, items, channel_prefs, favorites, history, guide channels/programmes/links, reminders
data/epg/                      XMLTV parser, channel matching, guide refresh (WorkManager, 12 h)
reminders/                     inexact alarms, boot re-arm, notification
data/Settings.kt               DataStore preferences
playback/PlaybackService.kt    Media3 MediaSessionService owning ExoPlayer; resolves ids to URLs
playback/MediaIds.kt           "<sourceId>|<stableKey>" playback ids; "rec|<id>" for recordings
playback/StreamResolver.kt     id -> stream URL, headers, HTTP setting (playback and recording services only)
recording/                     Recorder (TS/HLS copy), RecordingService (foreground, dataSync), scheduling, SAF files
ui/                            Compose screens, one ViewModel per screen where state is non-trivial
```

## Data model

- `sources`: name, type (M3U link, M3U file, Xtream), sealed `secret` (JSON of url/username/password),
  display label (scheme + host only), HTTP opt-in, counts, last error, account expiry.
- `items`: live channels, movies and series of a source. Replaced on every refresh. Unique
  `(sourceId, stableKey)`: `live:<id>`/`movie:<id>`/`series:<id>` for Xtream, a hash of
  group + URL for M3U (exact duplicates merge).
- `favorites`, `channel_prefs` and `history` reference `(sourceId, stableKey)` / the playback id, so they
  survive refreshes, and cascade when a source is removed.
- `channel_prefs` holds what the user did to a channel: hidden, renamed (`customName`), pinned. Rows with
  nothing set are deleted.
- `visible_items` is a database view over `items` and `channel_prefs`: the same columns plus `pinned`, the
  rename applied to `name`, hidden and staged rows removed. Lists, search, the guide and channel up/down read
  it instead of `items`, so they all agree on what exists and in what order (pinned first). Anything that
  must see every row (import, guide matching) reads `items`. A test keeps its columns equal to `items`.
- `locked_groups(sourceId, groupTitle)` is the parental lock. `visible_items.isLocked` says whether an item's group
  is in it, and every query returning items, history, reminders or programme hits ends with
  `AND (:unlocked OR … = 0)`. `ParentalLock` (PIN hash, lockout, in-memory unlocked state, 5-minute relock) supplies
  `unlocked`; view models re-query when it changes, and the playback service asks it before resolving an id (an
  episode is as locked as its series). `LockAwareDaoTest` fails if a new query of that kind has no `:unlocked`.

## Import

`SourceRepository.refresh` writes the new list as hidden `pending` rows in short batches of 1,000
(checking for cancellation between them), then one short transaction deletes the old rows and
publishes the new ones. Every read filters `pending = 0`, so a running import is invisible and the
rest of the app can keep writing. Failure or cancellation deletes the staged rows. Lists are read
through Paging; search uses an FTS4 table (`items_fts`) kept in sync by Room.

## Playback

The UI never handles stream URLs. It sends `MediaItem`s with an id and display metadata to the
service through a `MediaController`; `PlaybackService.onSetMediaItems` looks up the item, builds the
URL (Xtream URLs include credentials, so they are built only here), applies playlist headers and
the saved resume position. A live channel carries the list it was picked from (`ChannelList`:
provider order, A–Z, group, favorites); `ChannelSwitcher` answers next/previous (app, remote keys,
notification, Bluetooth) by looking up the neighbouring row in the database, so it works in lists of
any size. Links without `.m3u8` that fail as an unknown container are retried once as HLS. The service saves history every 10 s while playing and on pause,
retries network errors three times (2/4/8 s) and rejoins live streams that fell behind.

Background playback: off by default; leaving the player pauses unless PiP is active. When enabled,
the media notification keeps playback going.

## Recording

`Recordings.add` saves a `recordings` row and either starts `RecordingService` (the programme is on) or arms an alarm for
its start. An exact alarm starts the service directly; without the exact-alarm permission the alarm only posts a
notification whose tap starts it, because Android does not let an inexact alarm start a foreground service. The service
resolves the stream with `StreamResolver` (so the lock applies), creates a file in the folder the user picked (a SAF tree
URI), and `Recorder` copies a direct stream as it arrives or polls an HLS media playlist and appends each new segment once, in
order. It refuses what it cannot copy faithfully and stops at the end time, on Stop, on a write error (keeping the partial
file), or at Android 15's dataSync limit (`onTimeout`). A failure stores only a `RecordingException.Reason` name, so no URL
reaches the database or the UI. Saved recordings play through the playback service as `rec|<id>`.

## Navigation

Navigation Compose with type-safe routes (`ui/MuxlumeRoot.kt`). The five primary sections live in
`MainScreen` with a saveable state holder per tab: floating bottom bar on phones, rail at ≥ 600 dp.
