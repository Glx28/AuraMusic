# AuraMusic v3.2.0 (Build 29) Release Notes

> [!NOTE]
> AuraMusic 3.2.0 is the video release. It delivers a complete YouTube-backed video experience — feed, search, channels, comments, Shorts, picture-in-picture, downloads and subtitles — rebuilds video playback so videos play as videos through the music player instead of a second one, and brings that same direct playback to every television surface. It also consolidates playlist ownership, fixes the Android 15/16 foreground-service contract, and adds a configurable idle dimmer for TV.

## What's New

### Video — A Full Experience
- Added a personalised video feed with a recommendation engine, section icons, channel grouping, see-more grids, paging and a nav position setting.
- Added a Videos tab with YouTube search, search-as-you-type debounce, recent searches, grid/list toggle, YouTube-style filter dropdowns and a hero now-playing header.
- Added channel pages with channel art, descriptions, subscribe, pull-to-refresh, a community Posts tab and Shorts as a 2-column grid.
- Added a draggable channel Shorts pager and fixed mini player drag bounds.
- Added full comments with threaded replies, avatars and layout, sourced from scraped pages.
- Added collaboration credits — viewers can pick a channel when a video credits two, and both channels' art is shown.
- Added chapters, scrub previews (storyboards), a persistent progress thumb, an Up Next list and play next.
- Added video player settings: quality switch, share/copy, gestures, autoplay, queue editing and add-to-playlist.
- Added picture-in-picture and a draggable, floating minimised player.
- Added a video media notification with real transport controls, like/share buttons and artwork, which correctly hands off to and from the music player.
- Added channel thumbnails and click-to-channel across feed cards, search results and the player.
- Added video history and channel subscriptions to the library.
- Added sponsor-block support, and made the feed music-only where it should be.

### Video — Downloads
- Added a dedicated video download service with its own notification, separate from music downloads.
- Added a parallel block downloader (2 MB blocks, 3 threads, per-block retry) for substantially faster downloads.
- Added a download quality dialog, a placeholder-first announcement so the UI reacts immediately, and per-video progress on the button.
- Downloads are announced against a validated notification channel and are visible while they happen, honest about failure, and always on screen.
- **Fixed: downloaded videos now play with the network off.** They were resolved over the network unless the download store had been read by a screen first.

### Video — Playback Rebuilt
- **Videos now play through the music player instead of a second one.** This is the change most of this release follows from.
- Rebuilt the player around heap-aware buffers and hardware decode; rendered video as a texture inside the app window.
- Rebuilt video playback around a single load path that records why it cannot complete, so a failed load no longer leaves a player claiming to be playing a video that is never moving.
- Consolidated playlist ownership so the class that owns the playlist is the one asked about it — the underlying cause of skips replaying the current video and of removed videos returning.
- Queued videos as real playlist entries, so previous/next are native and the Up Next list sees the whole queue.
- Added video subtitles: YouTube captions fetched and attached to the video's own media item from the first frame, with a subtitles button on the TV player and on the phone.
- Preferred standard frame-rate streams in the quality picker and added a full resolution ladder with codec choice.
- Added video-aware Discord Rich Presence, widget and Last.fm integration.
- Made the video feed fill each page from several queries at once, and stopped the whole app recomposing on every video progress tick.

### Android TV — Videos Play as Videos
- **Videos now open as videos from every surface** — artist Videos shelf, albums, playlists, home shelves, Speed Dial, search, quick picks, Keep listening and the library — instead of playing as audio and swapping a picture in afterwards.
- A video started from a shelf now opens the player, so what you asked for is what you get.
- Videos are given a queue of what comes next, drawn like songs, and the queue no longer loses what has already played.
- Added a fullscreen TV player with remote-driven transport controls, a navigable queue panel, a video quality picker and subtitles.
- Added an idle **dimmer** for the whole app — the screen turns down but stays readable, and any key lifts it — with an on/off toggle and an adjustable 15 s–600 s timer.
- Added an artist Videos section and canvas backdrops behind TV detail screens and the player.
- Canvas is always on for TV; lyrics stay readable over it and the transport controls remain reachable with the queue panel open.
- Added a video mini player to the TV top bar, and made it light up for videos.

### Android TV — Interface & Reliability
- Unified Google TV and Android TV UI scaling, with an **Interface Size** (0.8–1.6) and **Font Size** (0.8–2.5) setting.
- Fixed the TV player opening onto a black screen on re-entry, and the surface being stolen between the split and fullscreen views.
- Fixed TV recommendation publishing and D-pad input in sign-in; published larger TV preview sets atomically.
- Fixed an empty Liked Songs library, duplicate-key crashes in lazy lists, and library `distinctBy`/`key` handling.
- Stopped song rows restarting their own flow on every recomposition, and stopped the mini tile taxing every screen.
- Stacked the tablet player vertically.

### Playback, Streaming & Reliability
- Fixed foreground-service notification crashes caused by a channel-name/channel-id collision, and validated the channel before starting the service.
- Moved foreground notification metadata into the intent so the service meets the Android 15/16 contract before anything else.
- Fixed songs skipping themselves on AudioTrack errors, queue persistence running on the main thread, and idle service shutdown.
- Fixed a resize crash and video playback lag, and reduced video UI jank.
- Fixed an invalid `DefaultLoadControl` buffer duration and restored the main dispatcher for the video scope.
- Replaced crossfade with AutoMix; made the blend reliable on cached and streamed tracks and configurable via a blend point.

### Aura Canvas
- Pointed canvas lookups at `canvas.auramusic.site`, defaulted canvas on, and removed the Aura Canvas toggle.
- Kept canvases playable with no connection and cached them across restarts.
- Stopped caching a broken canvas server as "this track has none".

### Translations
- Substantially expanded coverage, including an overhaul of Simplified Chinese translations (@a8ec29b), plus continued French, Russian and Italian work by @Mickael81 and @vityamolodets000-ctrl.

## Bug Fixes
- Fixed video playback crash, lag and metadata issues; comments never loading; the channel screen stuck on placeholders.
- Fixed the video player Up Next/comments vanishing after load.
- Fixed the video search lockup and a session-ID collision crash.
- Fixed broken video feeds, search filters and player comments/likes.
- Fixed videos chips styling, player top bar insets and the mini player layout.
- Fixed missing video thumbnails, empty liked-album states and playlist artwork fallbacks.
- Fixed a crash on `CustomAction` from a missing `setName` on the like button.
- Fixed a `state.playWhenReady` reference that does not exist.
- Fixed channel page description parsing for non-primitive values.
- Fixed a video FGS crash and made media notification buttons functional.
- Fixed TV idle service shutdown, playlist error rendering and the login popup.

## Build Update
- Version: 3.2.0 (Build 29)
- VersionCode: 29

---

## Contributors

Thank you to everyone who contributed to this release — including first-time contributors, whose work is just as welcome:

- **@chila254** (Franklin Finyange) — project lead
- **@Mickael81** (Mickaël Binos)
- **@Glx28**
- **@vityamolodets000-ctrl**
- **@a8ec29b** — first-time contributor, Simplified Chinese translations
- **@giovanni125** (Giovanni Li Pomi) — first-time contributor
- **@FraV1982** (Francesco Vangi) — first-time contributor
- **weblate** (translation bot) and the Weblate community

---

Full Changelog: https://github.com/TeamAuraMusic/AuraMusic/compare/v3.1.0...v3.2.0
