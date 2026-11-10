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
data/db/                       Room: sources, items, favorites, history
data/Settings.kt               DataStore preferences
playback/PlaybackService.kt    Media3 MediaSessionService owning ExoPlayer; resolves ids to URLs
playback/MediaIds.kt           "<sourceId>|<stableKey>" playback ids
ui/                            Compose screens, one ViewModel per screen where state is non-trivial
```

## Data model

- `sources`: name, type (M3U link, M3U file, Xtream), sealed `secret` (JSON of url/username/password),
  display label (scheme + host only), HTTP opt-in, counts, last error, account expiry.
- `items`: live channels, movies and series of a source. Replaced on every refresh. Unique
  `(sourceId, stableKey)`: `live:<id>`/`movie:<id>`/`series:<id>` for Xtream, a hash of
  group + URL for M3U (exact duplicates merge).
- `favorites` and `history` reference `(sourceId, stableKey)` / the playback id, so they survive
  refreshes, and cascade when a source is removed.

## Import

`SourceRepository.refresh` downloads and parses inside one Room transaction, inserting in batches of
1,000 and checking for cancellation between batches. Failure or cancellation rolls back to the
previous list. Known limit: the download runs inside the write transaction, so other writes wait.

## Playback

The UI never handles stream URLs. It sends `MediaItem`s with an id and display metadata to the
service through a `MediaController`; `PlaybackService.onSetMediaItems` looks up the item, builds the
URL (Xtream URLs include credentials, so they are built only here), applies playlist headers and
the saved resume position. Live channels are queued with up to 300 neighbours so next/previous and
system controls switch channels. The service saves history every 10 s while playing and on pause,
retries network errors three times (2/4/8 s) and rejoins live streams that fell behind.

Background playback: off by default; leaving the player pauses unless PiP is active. When enabled,
the media notification keeps playback going.

## Navigation

Navigation Compose with type-safe routes (`ui/MuxlumeRoot.kt`). The five primary sections live in
`MainScreen` with a saveable state holder per tab: floating bottom bar on phones, rail at ≥ 600 dp.
