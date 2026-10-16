# AuraMusic v3.2.0 (Build 29) Changelog

> [!NOTE]
> This changelog covers all 165 commits from `4d6a413` through `a5a0a34`, inclusive. No commits in the requested range are omitted.

## Highlights

- **Videos play as videos** — through the music player, from every TV surface, instead of as audio that later becomes a picture.
- **A complete video experience** — feed, search, channels, comments with replies, Shorts, chapters, scrub previews, PiP, quality/codec ladder and subtitles.
- **Video downloads** — a dedicated service, a parallel block downloader, and downloads that play with the network off.
- **Playlist ownership consolidated** — the cause of skips replaying the current video, removed videos returning, and one load path that now says why it cannot finish.
- **TV gets a fullscreen player, a video queue, subtitles and a configurable idle dimmer.**

## Major Changes

### Video Playback
- Play video through the music player instead of a second one.
- Queue videos as real playlist entries and finish the player consolidation.
- Let the class that owns the playlist be the one asked about it.
- Load a video the one way, and say what happens when it cannot be.
- Keep what has been played in the queue, and let repeat mean what it says.
- Stop the last place that threw the queue away.
- Give videos their details, keep their queue, and make dimming adjustable.
- Repeat what was asked for, dim the whole app, and offer subtitles to videos.
- Let a video say what it is, and queue what comes after it.
- Rebuild the video player around heap-aware buffers and hardware decode.
- Render video as a texture inside the app window.
- Prefer standard frame rate streams in the quality picker.
- Move queue persistence off the main thread and polish video playback.
- Reduce video playback UI jank and add picture-in-picture.
- Float the minimised video inside the app and drop the overlay window.
- Fix video playback crash, lag, and metadata issues.
- Fix video playback and media notification issues.
- Fix crash: restore Main dispatcher for VideoPlaybackManager scope.
- Fix crash: remove invalid DefaultLoadControl buffer durations.
- Stop the whole app recomposing on every video progress tick.
- Fix resize crash; fix mini player drag bounds; fix PiP button and player teardown.

### Video Features
- Add video captions and float the player over the app.
- Add chapters, scrub previews, play next, and a download quality dialog.
- feat: video player settings — quality switch, share/copy, gestures, autoplay, queue editing, add to playlist.
- feat: persistent progress thumb, adaptive player layout, video history and subscriptions in library.
- Add fullscreen video and lyrics to the TV player.
- Add video features: progress bar, fullscreen, SponsorBlock.
- Add sponsor-block segments to the video pipeline.
- Add captions and native ExoPlayer subtitle rendering.

### Video UI
- Redesign video player UI with YouTube-like features.
- Redesign the video player UI and the Videos experience.
- Implement complete video player UI and video screen improvements.
- Modernize video UI: feed, search, player, miniplayer, channel.
- Improve video player controls, miniplayer, and fix video screen filter chips.
- Redesign the overlay actions onto one scrolling line and add video downloads.
- Put the cross-screen buttons beside the controls they belong with.
- Add a draggable channel shorts pager.
- Polish video UI: music-first grid, channel avatars, compact views.
- Fix videos chips styling and player top bar insets.
- Fix video player Up Next/comments vanishing after load.
- Fix broken video feeds, search filters, and player comments/likes.

### Video Feed, Search & Channels
- Add personalized video feed with recommendation engine.
- feat: feed long-press menu, New tab, auto-refresh on return.
- Separate feed sections, group channels, add see-more grids and a nav position setting.
- Restyle feed sections, load like Home, and fix the resize crash.
- Open the video feed on content, restyle sections, fix rail and paging.
- Fill each page of the video feed from several queries at once.
- Restyle sections, load like Home, fix the resize crash.
- feat: Videos tab with YouTube search, split nav from Music search.
- feat: search-as-you-type debounce, recent searches, grid/list toggle, hero now-playing.
- Add YouTube-style search filter dropdowns.
- Fix video search lockup parsing, session ID collision crash, and polish search grid UI.
- feat: rename video library module, add channel screen, watch metadata, comments, and richer player.
- Add a community Posts tab to the channel page.
- Show channel Shorts as a 2-column grid.
- Fix empty channel pages, missing history thumbnails and clipped titles.
- Fix channel thumbnail and click-to-channel in video feed cards, search results, and player overlay.
- Add channel thumbnails and navigation in video player.
- Show both channels' art on collaborations and in the channel picker.
- Let viewers pick a channel when a video credits two of them.
- Fix subscribe, likes and collaboration credits across the video screens.
- feat: channel subscribe button and pull-to-refresh.
- Make YouTube video browsing music-only.
- Mirror music-video shelves to the account and link channels to artists.
- Fix YouTubeChannelPage description parsing for non-primitive values.

### Comments & Channel Data
- fix: complete comment threads with replies, avatars and layout.
- fix: source video comments and channel metadata from scraped pages.
- Fix comment lag, stale video details and collaboration credits.
- fix(video): fix comments never loading and channel screen stuck on placeholders.
- fix(video): restore comments metadata and media controls.
- Fix video screen issues: shimmer, refresh, pagination, comments, and channel loading.

### Video Downloads
- Give video downloads a service of their own.
- Split the downloads screen into music and videos.
- Name videos in the download notification, show progress on the button, and split downloads in two.
- Make downloads visible while they happen, honest about failure, and always on screen.
- Offer the full resolution ladder and codec choice, and move video downloads onto the download service.
- Announce before anything else, and take the file in blocks.
- Stop looking the download up before announcing it, and put the canvas behind the television.
- Meet the foreground contract before anything else, and ask for the notification where the download is.
- Find a channel the download can be announced on, stop clipping card text, and give the artist its videos.

### Video Media Notification & Integrations
- Replace music miniplayer notification with video controls when a video plays.
- Show artwork and controls in video player notification.
- Replace music miniplayer with video miniplayer and fix video media notification.
- fix: video notification always shows artwork + controls and hands off to/from the music player.
- fix: keep video media notification from being replaced by the music notification.
- fix: persistent video media notification and video-state gap fixes.
- Surface video transport controls and clear the music mini notification.
- Show playing videos in widgets, Discord, and Last.fm.
- Add device Google account sign-in and a video-aware Discord preview.
- Add org profile README source copy.

### Android TV
- Play every video as a video, everywhere, and show it when it starts.
- Play a video as a video, rather than as music that later becomes one.
- Do not wait for a picture nobody is showing.
- Settle the television video pipeline against itself.
- Scale the television's interface in the app, and let the video be fetched sharper.
- Make the TV player fullscreen-able from anywhere, the queue navigable, and video downloads visible.
- Always show the canvas on the television, keep lyrics readable over it, and leave the controls reachable.
- Show the canvas in fullscreen, and stop posting to a channel that will not take it.
- Publish larger TV preview sets atomically.
- Publish TV recommendation refreshes atomically.
- Fix TV recommendation publishing and sign-in D-pad input.
- Fix TV recommendation channel publishing.
- Fix D-pad activation in TV sign-in WebView.
- Clear TV preview programs when refreshing channel.
- Fix Android TV recommendation channel publishing.
- Stop song rows restarting their own flow on every recomposition.
- Stack the tablet player vertically, and stop the mini tile taxing every screen.
- Grow the video surface on push-down and drop the details in full screen.
- Actually place the transport controls in the middle of the picture.
- Put the seek bar back where it was, lift the up-next tile, and make crashes readable.
- Fix empty liked songs library.
- refactor(ui): remove video mode toggle and video indicators.
- Fix missing AutomixEnabledKey import in TvSettingsScreens.kt.

### Playback, Streaming & Media3
- Fix songs skipping themselves on AudioTrack errors.
- Fix songs skipping themselves, retime the blend, stop presence flicker.
- Remove crossfade and replace with automix; fix automix skipping songs at start.
- Make automix blend reliably on cached and streamed tracks.
- Fix playlist error rendering, login popup, and idle service shutdown.
- Fix video playback, mini player layout, and improve smoothness.
- Fix compilation errors in VideosScreen and VideoRecommendationManager.
- Fix multiple remaining issues.
- Fix build errors, improve video playback and UI.
- Fix videos screen refresh, grid/list shimmer skeletons, and deduped infinite scroll.
- fix: channel posts grid, search cards match feed, player channel click minimizes.
- refactor(video): optimize metadata fetching and improve playback UI.

### Media Notification & Foreground Service
- Fix video FGS crash and make media notification buttons functional.
- Fix video media notification: show artwork and transport controls.
- Rename video notification helper to avoid MediaSessionService clash.
- fix: use proper CommandButton.Builder constructor with predefined icons for like button.
- fix: add missing setName to like button CommandButton to prevent CustomAction crash.
- fix: Use state.isPlaying instead of nonexistent state.playWhenReady.
- fix: Use existing string resources for media button preferences in VideoPlaybackService.

### Aura Canvas
- Remove Aura Canvas toggle and default canvas on.
- Point AuraCanvas lookups at canvas.auramusic.site.
- Resume videos where they stopped and cache canvases across restarts.
- Keep canvases playable with no connection.
- Stop caching a broken canvas server as "this track has none".

### Build & CI
- ci: bump setup-android to v4 and skip obsolete 'tools' package.
- Bump version to 3.2.0 (Build 29).
- Update translation files.

## Contributors

All 165 commits in `v3.1.0...v3.2.0`, by GitHub username:

- **@chila254** (Franklin Finyange) — 138 commits
- **@Mickael81** (Mickaël Binos) — 8 commits, translations
- **@Glx28** — 4 commits and 2 pull requests (TV recommendations, TV sign-in D-pad)
- **@vityamolodets000-ctrl** — 3 commits
- **@a8ec29b** — pull request #25, Simplified Chinese translations *(first-time contributor)*
- **@giovanni125** (Giovanni Li Pomi) — 1 commit *(first-time contributor)*
- **@FraV1982** (Francesco Vangi) — 1 commit *(first-time contributor)*
- **weblate** — translation bot

Thank you all — including the first-time contributors.

---

**Full Changelog**: https://github.com/TeamAuraMusic/AuraMusic/compare/v3.1.0...v3.2.0
