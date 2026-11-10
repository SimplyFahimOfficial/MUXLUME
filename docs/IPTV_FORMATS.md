# IPTV formats

## M3U / M3U8 playlists

Supported:

- `#EXTM3U` header with `url-tvg` / `x-tvg-url` (the first valid URL is stored for the future EPG).
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

Not supported: portal/MAC (Stalker) logins, device impersonation or any unauthorized access, and the
provider XMLTV guide (planned with the EPG).

## Playback engine

AndroidX Media3 ExoPlayer with HLS, DASH, RTSP and progressive extractors (MPEG-TS, MP4, MKV, …).
Codec support depends on the device's decoders; there is no software decoder extension. Not
supported: RTMP, DRM-protected streams, MMS/UDP multicast. URLs without an `.m3u8`/`.mpd` extension
are treated as progressive, so extension-less HLS links may fail.
