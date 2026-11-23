# Architecture

One Gradle module (`:app`), package `com.muxlume.app`. More modules only when they bring a
measured build or maintenance benefit.

```
MuxlumeApp / AppContainer      manual dependency graph (no DI framework), process lifetime
core/net/Http.kt               the single OkHttp client, CleartextGate (per-request source policy; untagged = https only), Referer narrowing, URL validation/redaction, size limits
core/security/SecretBox.kt     Keystore AES-GCM for source secrets
data/m3u/M3uParser.kt          streaming, bounded M3U parser (Iterator)
data/xtream/XtreamApi.kt       Xtream Codes-compatible player_api client, lenient JSON, streamed lists
data/SourceRepository.kt       add/edit/remove sources, transactional import (refresh)
data/db/                       Room: sources, items, channel_prefs, favorites, history, guide channels/programmes/links, reminders
data/PlaylistRefresh.kt        optional background playlist refresh (WorkManager, checks every 12 h, due at 90% of the chosen interval)
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
- `favorites.sortOrder` is null until the user moves a favorite (then 1..n for the favorites a list shows); every favorites query, the player's
  picker and channel up/down order by `COALESCE(sortOrder, -addedAt), id`, so unplaced favorites stay newest first and new ones come first.
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
  `PlaybackLockGuard` judges what plays when the lock closes, when a playlist is removed and whenever another item starts: the playing item
  (a finished recording through its channel, an episode through its series) and, during failover, the channel the user chose; one that is
  locked or whose playlist is gone is stopped. A verdict applies only to the item it was made for.

## Delete all data

`DataReset` (Settings → Delete all data) stops a running recording (also one still being prepared, through `RecordingService.busy`) and
waits for it to close its file, fails if it does not stop, stops playback, then clears the database, the crash report, the image cache
(through Coil) and the access to picked files (each grant for the modes it holds), and the settings file with the PIN last. Every step
can be repeated; until the last one the app still looks set up. It runs in the app's scope, one at a time, so leaving the screen does
not stop it and a second tap does not start another.
No step is needed for scheduled work: every table cascades from `sources`, so a late writer is refused, and the settings that
schedule background work go with the file. Recording files and saved backups are outside the app and are left alone.

## Import

`SourceRepository.refresh` writes the new list as hidden `pending` rows in short batches of 1,000
(checking for cancellation between them), then one short transaction deletes the old rows and
publishes the new ones. Every read filters `pending = 0`, so a running import is invisible and the
rest of the app can keep writing. Imports of one source run one at a time (a per-source mutex); one that waited and finds `refreshedAt` changed returns the stored counts. Failure or cancellation deletes the staged rows.
An M3U with more playable entries than the cap (a product limit of 500,000; `M3uParser.overflowed`, found by reading on past the cap) fails before its last batch is staged, so
a playlist that was cut off is never published as the whole of it; the previous items and `refreshedAt` stay.
An M3U import that finds no playable entry in a source that had channels fails as an error instead of publishing an empty list, unless the
body has an `#EXTM3U` header and no entry was skipped (a playlist the provider emptied on purpose). The commit
drops the staged rows if the source's link or login was changed while it ran (the new one is imported next). Before the commit, when a staged live
channel has a key no earlier row has, it takes the key of the earlier channel it is: first the key remembered for its group and link
(`key_aliases`, written when a tvg-id match gave a channel an older key, so it outlives the item rows and travels in the backup;
`ItemDao.reuseKeysByAlias`), then the earlier channel with the same tvg-id when that tvg-id is on exactly one earlier and one staged live channel
of the source (`ItemDao.reuseKeysByTvgId`, only when something is still new and the source had channels), so favorites, preferences, history and
reminders follow a changed group or stream link. Lists are read
through Paging; search uses an FTS4 table (`items_fts`) kept in sync by Room.

## Guide refresh

`EpgRepository.refresh` stages the programmes and channels as `pending` rows like an import, but its rows have no owner, so refreshes of one
source take turns (a per-source mutex; one that waited and finds `epgRefreshedAt` changed returns what is stored; a guide that is off is not
downloaded). The download is not part of the transaction. A refresh publishes only if it still applies inside the publishing transaction: the
guide is on and its link is the one downloaded, and nothing called `supersede` meanwhile (`setEnabled(false)`, or a refresh that wants another
feed). Superseding also cancels the OkHttp call, as does cancelling the coroutine, so a stalled download ends at once. A refresh that no
longer applies drops its staged rows and records neither success nor error; one that was cancelled records no error. Other sources are not affected.

## Playback

The UI never handles stream URLs. It sends `MediaItem`s with an id and display metadata to the
service through a `MediaController`; `PlaybackService.onSetMediaItems` looks up the item, builds the
URL (Xtream URLs include credentials, so they are built only here), applies playlist headers and
the saved resume position. A live channel carries the list it was picked from (`ChannelList`:
provider order, A–Z, group, favorites); `ChannelSwitcher` answers next/previous (app, remote keys,
notification, Bluetooth) by looking up the neighbouring row in the database, so it works in lists of
any size. While another server plays, the list, number entry and the picker use `primaryId` (the channel the user chose), while stream
resolution and the servers sheet use the playing id. Links without `.m3u8` that fail as an unknown container are retried once as HLS. The service saves history every 10 s while playing and on pause,
retries network errors three times (2/4/8 s) and rejoins live streams that fell behind.

Background playback: off by default; leaving the player pauses unless PiP is active. When enabled,
the media notification keeps playback going.

## Alternative servers

`ChannelNames` (pure) normalises a name (NFKD, no accents or case, no bracketed text, a leading ISO country code only when a `:`,
`|` or ` - ` follows, `+` as "plus", no quality words) and compares two: **SAME** when the normalised names are equal, **SIMILAR** when
every word with a digit and every "plus" matches, they share a word, `max(Jaccard of words, 1 - Levenshtein / length)` is at least 0.75, and,
when it is the letters that make them close, every differing word is a typo of one on the other side ("Sky Sport" and "Sky Sports", not "SAB"
and "MAX"). `AlternativeFinder` takes candidates from the text index by the name's words (`ChannelServerDao.liveByWords`, visible live
channels, lock-aware) and orders them SAME before SIMILAR, other playlists before the channel's own, then by closeness and provider order.
The index is by word start, so a name that shares no word start with the channel ("Skysports") is not found.

Saved servers are `channel_servers` rows keyed like favourites (channel and server by playlist and stable key), so they survive refreshes
and go when either playlist is deleted; `ChannelServerDao.forChannel` joins `visible_items`, so hidden or locked channels disappear.
`MediaIds` requests carry `muxlume.primary`, the channel the user chose, when another server of it plays (`MediaItem.primaryId`);
the Servers sheet, channel up/down, the previous-channel control and watch history are anchored to it (history is not written for a
server that is not the chosen channel).

`PlaybackService.Recovery` tries another server after the existing retries are used up (or for an error that is not retried), when the
setting is on: `FailoverPlan` picks the next of the saved servers then the SAME alternatives, never one tried before and at most 3 per
failure; the item is resolved through `resolve`, so its own playlist's login and HTTP setting apply; a server gets one retry instead of three.
The counters reset when the user starts playback. Nothing but the playlist's name is shown, never a URL. Trying a server connects to that
playlist's own provider, as playing it would.

## Default languages

`AppSettings.audioLanguage/subtitleLanguage` (null = Automatic) are applied by `PlaybackService` through the pure
`TrackLanguages.apply` onto the player's `TrackSelectionParameters`: on every settings change, and at each new stream when a
language is set. The service keeps the parameters the player started with, so Automatic restores them, and remembers whether it
turned subtitles off so it never undoes the user's own choice. A track picked by hand in the player is an override on top and wins.

## Recording

`Recordings.add` saves a `recordings` row and either starts `RecordingService` (the programme is on) or arms an alarm for
its start. An exact alarm starts the service directly; without the exact-alarm permission the alarm only posts a
notification whose tap starts it, because Android does not let an inexact alarm start a foreground service. The service
resolves the stream with `StreamResolver` (so the lock applies), creates a file in the folder the user picked (a SAF tree
URI), and `Recorder` copies a direct stream as it arrives or polls an HLS media playlist and appends each new segment once, in
order. It refuses what it cannot copy faithfully and stops at the end time, on Stop, on a write error (keeping the partial
file), or at Android 15's dataSync limit (`onTimeout`). The recording window is `[startAt − leadSeconds, endAt + trailSeconds]`
(both copied from settings into the row when it is created); the service ends it no later than the padded start of the next
scheduled recording on the same channel. A direct stream that drops, or a live feed without a length that closes, is reopened
with 2, 4, 8, 15, then 30 second waits and appended to the same sink; an HLS playlist or segment failure is tolerated the same
way, and a playlist whose sequence numbers go backwards is treated as a restart. Both give up with `NETWORK` after 120 seconds
without data. The URL is resolved once, so a token that expires sooner ends the recording after that window. A failure stores only a `RecordingException.Reason` name, so no URL
reaches the database or the UI. Saved recordings play through the playback service as `rec|<id>`.
The player's Record button calls `Recordings.add` with the programme airing now (from `EpgRepository.nowNext`) or
`Recordings.addNow` (fixed length, no padding); `RecordingDao.runningFor` drives its red state and the same button stops the
recording through `RecordingService.stopIntent`. A recording opens its own connection to the stream, so a provider that
allows one stream per account may refuse it or end playback.
The file is finalized by `writeRecording`: opening, writing and closing must all work for DONE; any failure stores `STORAGE_FULL` or `WRITE_FAILED`,
keeps the bytes written, and an empty file is deleted. Before a recording is saved, `RecordingConflicts` compares its padded window with the waiting and
running ones of the same source against the account's `maxConnections` (read at each Xtream import) and reports same-channel truncation;
the guide and the player both pass `RecordingPrompt.confirm` to `Recordings.add`, which saves nothing until the user continues.

## Navigation

Navigation Compose with type-safe routes (`ui/MuxlumeRoot.kt`). The five primary sections live in
`MainScreen` with a saveable state holder per tab: floating bottom bar on phones, rail at ≥ 600 dp.
