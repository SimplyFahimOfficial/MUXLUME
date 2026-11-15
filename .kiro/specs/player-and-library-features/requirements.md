# Requirements: player, recording, playlist and guide features

> **Basis.** The eight items chosen from the feature shortlist: (F1) record from the player, (F2) recording padding
> and reconnect, (F3) channel number jump, (F4) pick a channel without leaving playback, (F5) default audio and
> subtitle language, (F6) background playlist refresh, (F7) guide filters, (F8) alternative servers for a channel
> that does not connect. Item 8 is worded from your note: "find others from the saved playlists with the same name,
> exact first, then similar, and let me add them as servers below in the player".
>
> **Release alignment.** None of this blocks 0.1.0 (see `.kiro/specs/release-0-1-0/`). I assume it ships in 0.2.0,
> after the 0.1.0 tag.

Terms: "channel" is a live item (`ItemKind.LIVE`). "Locked" means in a group under the parental PIN while the app is
locked; locked and hidden channels never appear in anything below. "Server" means one stream a channel can be played from.

## F1. Record from the player

**Story:** while watching a channel I want to record what is on, without going to the guide.

1. WHILE a live channel plays, THE player SHALL show a Record control.
2. WHEN the guide has a programme airing now on that channel, THE control SHALL record that programme (title, start, end from
   the guide), using the same rules as recording from the guide, including padding (F2).
3. WHEN the channel has no guide data, THE control SHALL offer fixed lengths (30 minutes, 1 hour, 2 hours, 4 hours) and record
   from now for the chosen length under the title "<channel> <date time>".
4. WHILE the channel is being recorded, THE player SHALL show a recording indicator and THE control SHALL stop the recording.
5. IF no recordings folder is chosen, THEN THE control SHALL say so and point to Settings → Recordings, and SHALL NOT record.
6. THE recording SHALL use its own connection. THE docs SHALL state that providers which allow one connection per account may
   refuse it or end playback.

## F2. Recording padding and reconnect

**Story:** recordings should not miss the start or end, and should survive a short drop.

1. THE settings SHALL offer "Start early" (0, 1, 2, 5 minutes; default 1) and "Stop late" (0, 5, 10, 15 minutes; default 5).
2. WHEN a recording is created from a programme, THE padding in force SHALL be stored with it, and a later change of the
   settings SHALL NOT alter existing recordings. Fixed-length recordings (F1.3) SHALL get no padding.
3. WHEN two recordings on the same channel touch, THE earlier one SHALL stop no later than the start of the next one.
4. WHEN the connection to the stream drops, THE recorder SHALL reconnect with backoff (2, 4, 8, 15, 30 s) and keep appending to the
   same file until the end time, and SHALL give up with `NETWORK` only after 2 minutes without receiving any data.
5. WHEN an HLS server restarts and its media sequence goes backwards, THE recorder SHALL treat the new segments as new
   (today they would be skipped).
6. WHEN a recording ends after reconnecting, THE file and its size SHALL be kept and THE state SHALL be Saved, not Failed.

## F3. Channel number jump

**Story:** I want to type a channel number.

1. THE live player SHALL offer a "Go to channel" control with a number pad, and SHALL accept typed digits from a hardware or
   remote keyboard: digits typed within 2.5 seconds of each other form one number, and the jump happens after that pause or on Enter.
2. THE number SHALL be the channel's `number` (tvg-chno or Xtream `num`) in the playlist of the channel now playing. IF that
   playlist has no numbers, THEN it SHALL be the channel's place (1-based) in provider order.
3. IF no visible channel has that number, THEN THE player SHALL say "No channel N" and keep playing.
4. THE player SHALL offer "Previous channel", which returns to the channel watched before the current one, and the remote's
   Last Channel key SHALL do the same.
5. THE jump SHALL keep the list the channel was picked from for channel up/down where the target is in it, and otherwise use
   the target's own group.

## F4. Pick a channel without leaving playback

1. WHILE a live channel plays, THE player SHALL offer a Channels control that opens a list over the video; playback continues
   until a channel is chosen.
2. THE list SHALL be the list the current channel was picked from (provider order, A–Z, a group, or favourites), loaded in
   pages, with the current channel marked.
3. THE list SHALL have a search box (same matching as global search, live channels of the same playlist).
4. WHEN a channel is chosen, THE player SHALL switch to it and close the list, keeping the same list for channel up/down.
5. THE list SHALL work with a remote (focusable rows, Back closes it) and with TalkBack (rows labelled "Play <name>").
6. THE list SHALL NOT show hidden channels, or locked ones while locked.

## F5. Default audio and subtitle language

1. THE settings SHALL offer "Audio language" (Automatic, Device language, or one of a short list of languages) and
   "Subtitles" (Automatic, Off, Device language, or the same list). Default for both: Automatic, meaning today's behaviour
   (the player's own choice, which can include forced subtitles).
2. WHEN a stream starts, THE player SHALL select the audio and subtitle tracks that match, if the stream has them, and fall back
   to the stream's default otherwise.
3. WHEN the user picks a track in the player options, THAT choice SHALL win for the current stream.
4. A change in settings SHALL apply from the next stream (and to the current one if it has not been changed by hand).
5. THE two choices SHALL be part of the backup and restore with the other settings.

## F6. Background playlist refresh

1. THE settings SHALL offer "Refresh playlists automatically": Off, Daily, Every 3 days, Weekly. Default: Off.
2. WHILE a schedule is on, THE app SHALL refresh each playlist (links, Xtream accounts and file playlists) when it is older than
   the interval, only on a connected network (unmetered when "Wi-Fi only" is on) and not on a low battery.
3. THE refresh SHALL use the existing staged import: a failed refresh keeps the old channels and stores the error on the playlist.
4. A manual refresh and a background refresh of the same playlist SHALL NOT run at the same time; the second SHALL wait or report "already refreshing".
5. Hidden, renamed, pinned, favourite, locked and recorded channels SHALL survive a refresh as they do now.
6. THE app SHALL NOT notify the user about a successful background refresh.
7. THE "Wi-Fi only" switch SHALL cover both the guide and the playlists, and its text SHALL say so.

## F7. Guide filters

1. THE guide SHALL offer the filters "All", "Favorites" and "On now" next to the group and day choices, and they SHALL combine with them.
2. "Favorites" SHALL show only favourite channels that have guide data.
3. "On now" SHALL show only channels with a programme airing at the moment the filter was chosen or the screen was resumed, and
   SHALL be offered only for today.
4. WHEN a filter leaves no channels, THE guide SHALL say which filter caused it.
5. THE filters SHALL respect hidden and locked channels.

## F8. Alternative servers for a channel

**Story:** when a channel will not connect, find the same channel elsewhere in my playlists, and let me keep the good ones.

1. THE app SHALL find alternatives for a channel among all visible live channels of all saved playlists (including other groups
   of the same playlist), excluding the channel itself.
2. THE alternatives SHALL be found in two tiers, in this order: (a) "Same name": equal after normalisation (case, accents,
   punctuation, bracketed text, quality words such as HD/FHD/4K/SD, and country or provider prefixes such as "UK:" or "|UK|"
   are ignored); (b) "Similar name": not equal, but similar enough by a documented rule, where numbers are part of the identity
   ("Sky Sports 1" never matches "Sky Sports 2", "ITV" never matches "ITV +1").
3. WITHIN a tier, alternatives from other playlists SHALL come before those from the same playlist.
4. WHILE a live channel plays, THE player SHALL show a Servers list below the channel information, with the current server
   marked, the servers the user saved, and the alternatives found, each labelled "Same name" or "Similar name" with its playlist
   and group. IT SHALL NEVER show a stream URL.
5. WHEN the user taps a server, THE player SHALL switch to it. WHEN the user taps "Add as server" on an alternative, THE
   app SHALL save it for that channel; "Remove" SHALL delete it. Saved servers SHALL survive playlist refreshes, go with the
   backup, and be tried before found alternatives.
6. WHEN playback of a live channel fails for good (after the existing retries, or at once for an error that is not retried),
   AND "When a channel fails, try other servers" is on (default), THE service SHALL switch to the next saved server or
   "Same name" alternative, in order, at most 3 per failure, and SHALL tell the user which one it is trying (on the
   channel's subtitle line). IT SHALL NOT try one twice or loop. "Similar name" alternatives SHALL be tried only when the user
   taps them (Decision D1: a wrong guess would play a different channel without being asked).
7. A server from a locked group SHALL NOT be offered or tried while locked. A server's own playlist login and HTTP setting
   SHALL apply to it, not the first channel's.
8. WHILE a server other than the channel the user chose is playing, history and recent channels SHALL NOT be updated for it
   (the chosen channel keeps its previous entry).
9. THE matching SHALL run on the device only.

## Out of scope

Failover for recordings; timeshift or catch-up; a per-channel server editor with drag ordering (saved servers keep the order
they were added); translating the new strings; changing `LICENSE`.
