# Privacy policy

_Last updated: 2026-11-10. Applies to MUXLUME 0.1.0 and later._

MUXLUME is a local media player. It has no user accounts and the developer runs no server for it.

## What the app stores, on your device only

- The playlists and provider accounts you add: links, usernames and passwords (encrypted with a key
  in your device's Android Keystore), and the channel, movie and series lists they contain.
- Favorites, watch history and resume positions.
- Which channels you hid, renamed or pinned.
- After a crash, a short report (the error, app version, phone model; web links removed) until you share or delete it.
- If you record a programme: the file, in the folder you picked, and a list of your recordings (title, channel, time, size). Nothing is uploaded.
- If you set a parental PIN: a salted hash of it (never the PIN) and the groups it covers.
- If you turn the TV guide on for a playlist: its programme data and your reminders, stored only on
  the device.
- Settings, and cached artwork images.

The Google Play build contacts Google Play to look for an update only when you tap "Check for updates"; Google's own terms apply to that. The F-Droid and GitHub builds do not contain that code.

A backup file is created only when you ask for one, is saved where you choose, and is encrypted with a passphrase only you know. The app does not upload it.

None of it is sent to the developer or anyone else, and none of it is included in Android backups or
device-to-device transfers. **Settings → Delete all data** removes everything; uninstalling does too.

## Network connections

The app connects only to:

- the playlist links and provider servers you add (to download lists, metadata and streams);
- the TV guide link, only for playlists where you turned the guide on (downloaded when you enable it and about every 12 hours on Wi-Fi unless you change that);
- the artwork (logo, poster) addresses those lists contain;
- links you open yourself from the Support screen, in your browser;
- only in the GitHub build, `api.github.com`, when you tap **Check for updates** in Help & about (never in the background).

There are no analytics, advertising, crash-reporting or tracking SDKs. Servers you connect to see
your IP address and what you request, as with any streaming app; their own privacy policies apply.
Unencrypted (HTTP) servers are blocked unless you allow them, because others on the network could
read that traffic.

## Permissions

Internet access, a foreground service for playback with the screen off or in the background, and
keeping the device awake while a stream plays. Notifications (for guide reminders, only if you set one) and a restart receiver that re-arms those reminders. Files are only read when you pick them.

## Children

MUXLUME is not directed at children and collects no personal data from anyone.

## Contact

<https://simplyfahim.pages.dev/contactme>
