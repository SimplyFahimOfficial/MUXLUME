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
| Stored credentials | AES-256-GCM, key in Android Keystore (non-exportable); only ciphertext in the database | `core/security/SecretBox.kt` |
| Backups / device transfer | `allowBackup=false`, data extraction rules exclude every domain | `AndroidManifest.xml`, `res/xml/` |
| Logs and diagnostics | No logging of URLs or credentials; error texts never contain URLs; player diagnostics list codecs and sizes only | `data/ErrorText.kt`, `ui/player/PlayerScreen.kt` |
| Plain HTTP | See below | `core/net/Http.kt` |
| TLS | System CAs only, no user CAs, no custom trust managers or hostname verifiers; https→http redirects refused | `res/xml/network_security_config.xml`, `Http.kt` |
| Malicious playlists | Streaming parser, 64 KB line limit, 500k entries, 256 MB download limit; only `http`, `https`, `rtsp` stream URLs (no `file:`/`content:`); header values validated (no CR/LF) and limited to User-Agent and Referer | `data/m3u/M3uParser.kt` |
| Malicious provider JSON | Lenient field reading, streamed lists, 16/256 MB limits, malformed JSON is an error, not a crash | `data/xtream/XtreamApi.kt` |
| Arbitrary URLs through the media session | The UI sends only ids; the service resolves URLs from the database. The service is not exported | `playback/PlaybackService.kt` |
| Exported components | Only the launcher activity (no deep links yet) | `AndroidManifest.xml`, `scripts/ci/apk_audit.py` |
| Storage | Storage Access Framework only, read access persisted per file, released on removal | `ui/onboarding/AddSourceScreen.kt` |

### Cleartext HTTP (reviewed exception)

Many IPTV servers only offer HTTP and their hosts are user-defined, so the network security config
must permit cleartext at the platform level. The control is in-app: every request goes through one
OkHttp client whose `CleartextGate` refuses plain HTTP unless the request's own source allows it.
Playlist downloads, provider API calls and stream/segment requests carry their source's setting as
a request tag, so one source's opt-in never covers another. Artwork isn't tied to one source and
carries no credentials; it may use HTTP while any source allows HTTP. RTSP (which bypasses OkHttp)
is only played for sources with the opt-in.

`scripts/ci/apk_audit.py` records this as the only reviewed exception and fails on any other
network-config weakening (user CAs, `usesCleartextTraffic`).

## Automated checks

| Workflow | What |
|---|---|
| `android-ci.yml` | Lint with security checks as errors, tests, detekt, APK audit (permissions allowlist, exported components, debuggable, backups, network config), 16 KB alignment, emulator launch |
| `security.yml` | gitleaks secret scan, tracked keystore/credential files, OSV-Scanner on the release SBOM (fails at high), licence allowlist |
| `codeql.yml` | CodeQL `security-extended` for Java/Kotlin, gated at high |
| `dependency-review.yml` | New or changed shipped dependencies: vulnerabilities and licences |
| `workflow-audit.yml` | actionlint and zizmor (medium and above); third-party actions pinned to commit SHAs |

On a private repository without GitHub Code Security, CodeQL results aren't uploaded (the SARIF
gate still runs) and GitHub's dependency-review action is skipped; set `CODE_SCANNING_UPLOAD` and
`DEPENDENCY_REVIEW_ENABLED` repository variables to `true` once available.

## Permissions

`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` (only while
playing), and `ACCESS_NETWORK_STATE` merged from Media3. No storage, location, contacts or
notification permission (media session notifications are exempt). A new permission fails CI until
it's reviewed in `scripts/ci/apk_audit.py`.
