# IPTV formats

## M3U / M3U8 playlists

Supported:

- `#EXTM3U` header with `url-tvg` / `x-tvg-url` (the first valid URL is kept, encrypted, as the playlist's guide link; see XMLTV below).
- `#EXTINF:<duration> key="value" …,Title` with `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`,
  `group-title`; quoted values may contain commas. Titles after the first unquoted comma.
- `#EXTGRP:` when no `group-title` is given.
- Headers: `#EXTVLCOPT:http-user-agent=` / `http-referrer=` and Kodi-style `url|User-Agent=…&Referer=…`.
  Only User-Agent and Referer are used; values with control characters are dropped.
- Bare URL lines without `#EXTINF` (named after the last path segment).
- UTF-8 with or without BOM, LF or CRLF.

Entries are classified as movies when the URL path contains `/movie/` or `/series/` or ends in
`.mp4`, `.mkv`, `.avi`, `.mov`, `.webm`, `.m4v`; everything else is live.

Rejected or limited: stream schemes other than `http`, `https`, `rtsp`; lines over 64 KB; more than
500,000 entries; downloads over 256 MB. Exact duplicates (same group and URL) are merged.

Not supported yet: `#KODIPROP` (inputstream.adaptive, DRM), `catchup` attributes, file-level gzip.

## Xtream Codes-compatible API

`player_api.php` with `username`/`password` query parameters:

| Action | Use |
|---|---|
| (none) | Account check: `user_info.auth`, `status`, `exp_date`, `max_connections`, `allowed_output_formats` |
| `get_live_categories`, `get_live_streams` | Live channels |
| `get_vod_categories`, `get_vod_streams`, `get_vod_info` | Movies and details |
| `get_series_categories`, `get_series`, `get_series_info` | Series, seasons, episodes |

Stream URLs: `/live|movie|series/<user>/<pass>/<id>.<ext>`. Live uses `m3u8` when the account allows
it, otherwise `ts`. Numbers sent as strings (and the reverse), `{}`/`null` instead of empty lists and
episode lists keyed by season or flat are all accepted.

Not supported: portal/MAC (Stalker) logins, device impersonation or any unauthorized access, and a
custom guide link for Xtream sources (they use the provider's own `xmltv.php`).

## XMLTV guide

- Plain or gzip (detected by magic bytes). Times like `20261110200000 +0100`; programmes without a
  `stop` are skipped and counted.
- Stored window: 1 day back to 7 days ahead. Limits: 2 million programmes, 512 MB decompressed,
  nesting depth 32, bounded text fields.
- `<!DOCTYPE tv SYSTEM "xmltv.dtd">` is accepted and ignored; a DOCTYPE with an internal subset is
  rejected. Undefined entities are dropped by the Android parser, never expanded.
- Channels are matched to guide channels by `tvg-id`, then by normalized name, then by hand
  (Settings → TV guide → Match channels).

## Playback engine

AndroidX Media3 ExoPlayer with HLS, DASH, RTSP and progressive extractors (MPEG-TS, MP4, MKV, …).
Codec support depends on the device's decoders; there is no software decoder extension. Not
supported: RTMP, DRM-protected streams, MMS/UDP multicast. URLs without an `.m3u8`/`.mpd` extension
are first tried as progressive; if that fails as an unknown container they are retried once as HLS.
