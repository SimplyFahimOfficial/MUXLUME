# Security

## Reporting a vulnerability

Please don't open a public issue. Report privately through the developer contact page,
<https://simplyfahim.pages.dev/contactme>, or GitHub's private vulnerability reporting once the
repository is public. Include the version (Library → Help & about), the device and steps to reproduce.
Never send real provider credentials or private playlist links; redact them.

## Threat model in brief

MUXLUME processes untrusted input (playlists, provider JSON, artwork, streams) and stores
secrets (playlist links that often embed tokens, provider usernames and passwords).

| Asset / risk | Control | Where |
|---|---|---|
| Source configuration | The playlist link or file location, the guide link and the Xtream login are AES-256-GCM sealed, key in Android Keystore (non-exportable); only ciphertext in the database | `core/security/SecretBox.kt` |
| Imported channel data | **Not encrypted.** An M3U playlist's channel stream links and their User-Agent/Referer headers are stored as plain text in the app-private Room database (stream links often carry a token or login). Xtream stream links are not stored: they are built from the sealed login when played. The protection is Android's per-app storage, `allowBackup=false` and the transfer exclusions below, and Settings → Delete all data; the database itself is not encrypted, which is a separate change from this transport control | `data/db/Entities.kt` (`ItemEntity.url`, `headers`) |
| Backups / device transfer | `allowBackup=false`, data extraction rules exclude every domain | `AndroidManifest.xml`, `res/xml/` |
| Logs and diagnostics | No logging of URLs or credentials. A failure is shown or stored only as a text the app wrote (`UserFacingException`) or a fixed one for its type (authentication, timeout, TLS, HTTP status, ...); the text of any other exception, which can quote an address, a login or what a server sent, is never displayed. Player diagnostics list codecs and sizes only | `data/ErrorText.kt`, `ui/player/PlayerScreen.kt` |
| Plain HTTP | See below | `core/net/Http.kt` |
| TLS | System CAs only, no user CAs, no custom trust managers or hostname verifiers; https→http redirects refused | `res/xml/network_security_config.xml`, `Http.kt` |
| Malicious playlists | Streaming parser, 64 KB line limit, 500k entries, 256 MB download limit; only `http`, `https`, `rtsp` stream URLs (no `file:`/`content:`); header values validated (no CR/LF) and limited to User-Agent and Referer | `data/m3u/M3uParser.kt` |
| Malicious provider JSON | Lenient field reading, streamed lists, 16/256 MB limits, malformed JSON is an error, not a crash | `data/xtream/XtreamApi.kt` |
| Arbitrary URLs through the media session | The UI sends only ids; the service resolves URLs from the database. The service is not exported | `playback/PlaybackService.kt` |
| Exported components | Only the launcher activity (no deep links yet) | `AndroidManifest.xml`, `scripts/ci/apk_audit.py` |
| Parental PIN | PBKDF2-HMAC-SHA256 hash with a random salt (150,000 rounds), constant-time comparison; 5 wrong tries lock out for 30 s, doubling each time (kept across restarts); unlocked state lives in memory only and ends after 5 minutes in the background; locked groups are filtered in the database view and in every item, history, reminder and programme query, and the playback service refuses their items (a test lists every such query) | `data/lock/ParentalLock.kt`, `data/db/MuxlumeDatabase.kt`, `playback/PlaybackService.kt` |
| Recording | Streams are copied byte for byte by `RecordingService`, which resolves them itself from an id: the UI, notifications and logs never hold a stream URL, and a failed recording stores only a reason (`RecordingException.Reason`), never a message that could contain one. DASH, RTSP, DRM, encrypted and fragmented-MP4 streams are refused. Requests carry the source's HTTP setting like playback does. Recordings of a channel in a locked group are hidden and refused while locked | `recording/`, `playback/StreamResolver.kt` |
| Crash report | The last uncaught exception is written to a private file as the types and stack frames of it and its causes, and never their messages (those can quote a stream or playlist address, a login, a host or a server's reply, in forms no pattern catches). Capped at 64 KB, and shared only if the user taps Share | `support/CrashLog.kt` |
| Storage | Storage Access Framework only, read access persisted per file, released on removal | `ui/onboarding/AddSourceScreen.kt` |

### Deferred decision: encrypting the channel database

Today the playlists' channel data, including the stream links and headers of an M3U playlist, is plain text in the app-private Room
database (see "Imported channel data" above). Encrypting it is a separate change; this is what the decision rests on.

- **The threat it would address:** someone who holds a copy of the database file without the running app and without the device's
  Keystore: a file-system image of a rooted or otherwise compromised device, or storage read out of the app's sandbox. A Keystore-held key
  would make that copy unreadable. It would not help against code running as the app (it can ask the Keystore), a screen recording, or
  a network observer of plain-HTTP streams.
- **Upgrades:** an existing install needs a one-time re-encryption of its database, with free space of about its size, that can be
  interrupted and must resume; the schema and migration tests grow. A restored or transferred device cannot reuse the key (backups are
  separately passphrase-encrypted, so nothing is lost).
- **Offline playback:** unaffected: the key is on the device and nothing is fetched to open the database.
- **Large playlists:** whole-database encryption (for example SQLCipher) puts a native dependency in every ABI and taxes every page read,
  including full-text search and paged lists over hundreds of thousands of rows. Encrypting only the `url` and `headers` columns avoids
  that: the cost is at import (every row) and at play (one row), provided one data key is unwrapped once and kept in memory, since a
  Keystore call per row would be far too slow at 500,000 entries. None of this has been measured here.
- **Not done in this change**, which makes the documents accurate instead (above).

### Cleartext HTTP (reviewed exception)

Many IPTV servers only offer HTTP and their hosts are user-defined, so the network security config
must permit cleartext at the platform level. The control is in-app: every request goes through one
OkHttp client whose `CleartextGate` refuses plain HTTP unless the request's own source allows it.
Playlist downloads, provider API calls, stream/segment requests and artwork carry their source's setting as
a request tag, so one source's opt-in never covers another. A request without a tag belongs to no
source and is HTTPS only. Artwork of a source that allows HTTP is requested with the setting of the source
that owns the image (Coil: a marker header turned into the tag inside the client; notifications: the
playback service marks each item with its source, and the setting is read when the image loads); a cached image is shown whatever its owner's
setting. A stream that is playing, and the next artwork loaded for it, lose the setting when the source stops allowing it. The choice can
be made for an https playlist or account too (Library → edit), because its channels or artwork may be http://. RTSP (which
bypasses OkHttp) is only played for sources with the opt-in. Redirects between https and http are
never followed (`followSslRedirects(false)`, tested against a local TLS server).

The player's request builder is made per media item (`ItemMediaSourceFactory`): every request of an item, including a late one made
after the user moved to another item or another playlist (failover included), carries that item's own headers and HTTP setting.
A recording has its own for its whole run.

A playlist's Referer is meant for its stream's own server. A request to another origin (a redirect to
a CDN, or an HLS playlist or segment hosted elsewhere) carries the Referer reduced to its origin, as a
browser sends across origins: the path, query and any login in it are not handed on, and a Referer that
is not a URL is not sent. The stream's own origin, and the User-Agent everywhere, are unchanged. This can
affect a CDN that checks the full Referer path or query rather than the host.

`scripts/ci/apk_audit.py` records this as the only reviewed exception and fails on any other
network-config weakening (user CAs, `usesCleartextTraffic`).

## Automated checks

| Workflow | What |
|---|---|
| `android-ci.yml` | Lint with security checks as errors, tests, detekt, APK audit (permissions allowlist, exported components, debuggable, backups, network config), 16 KB alignment, emulator launch, no proprietary or unresolved dependency in the F-Droid build |
| `security.yml` | gitleaks secret scan, tracked keystore/credential files, OSV-Scanner on the release SBOM (fails at high), licence allowlist. The dependency scan runs on `main`, weekly and on demand (pull requests use `dependency-review.yml`); gitleaks and the tracked-file check run on every pull request and push |
| `codeql.yml` | CodeQL `security-extended` for Java/Kotlin, gated at high |
| `dependency-review.yml` | New or changed shipped dependencies: vulnerabilities and licences |
| `workflow-audit.yml` | actionlint and zizmor (medium and above); third-party actions pinned to commit SHAs |

On a private repository without GitHub Code Security, CodeQL results aren't uploaded (the SARIF
gate still runs) and GitHub's dependency-review action is skipped; set `CODE_SCANNING_UPLOAD` and
`DEPENDENCY_REVIEW_ENABLED` repository variables to `true` once available.

### What the parental PIN is not

It keeps children and casual users out of groups you choose. A 4 to 8 digit PIN has few possibilities, so its hash cannot
resist someone who can read the app's private files (a rooted device, or a debugger). Delete all data resets it without
the PIN, by design: that is the only way back from a forgotten PIN. Lockout and relock timing use the device clock, so
changing the clock shortens them. Backups include which groups are locked, never the PIN or its hash.

## Backup files

Backups hold playlist links and logins, so they are always encrypted: AES-256-GCM with a key derived from the
user's passphrase (PBKDF2-HMAC-SHA256, 600,000 iterations, random salt and IV per file, header authenticated).
Minimum 8 characters; there is no recovery. The file is read with a 64 MB cap and a limit on the number of
entries, and a restore goes through the same validation as adding a playlist by hand. Strength depends on the
passphrase: a short one can be guessed offline by anyone who gets the file.

## Permissions

`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` (only while
playing), `FOREGROUND_SERVICE_DATA_SYNC` (the recording service), `SCHEDULE_EXACT_ALARM` (lets a scheduled recording start by itself; Android denies it by default and the user decides, without it a notification starts the recording), and `ACCESS_NETWORK_STATE` merged from Media3. `POST_NOTIFICATIONS` (asked the first time you
set a guide reminder) and `RECEIVE_BOOT_COMPLETED` (re-arms reminders after a restart; the receiver is
not exported). No storage, location or contacts permission (recordings go to a folder the user picks through the system picker). Reminders use inexact alarms. XMLTV guides are parsed with size, depth and entity limits (see
`docs/IPTV_FORMATS.md`). A new permission fails CI until
it's reviewed in `scripts/ci/apk_audit.py`.
