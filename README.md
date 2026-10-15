# EgyDead — CloudStream 3 Extension

إضافة CloudStream 3 لموقع EgyDead (`https://tv10.egydead.live`) — أفلام ومسلسلات وأنمي مترجمة ومدبلجة.

## Features

- Main page sections: latest additions, foreign movies, and genre pages
  (all paginated with the site's verified `?page={n}/` pattern where the site supports it)
- WordPress-style search (`/?s=`)
- Movies at `/{slug}/` with poster, plot, year and genres parsed from the post page
- Series: `/serie/` pages aggregate every season's episodes; `/season/` pages list
  episodes sorted ascending; opening an episode card jumps to its full season
- Watch links: unlocks the watch view by POSTing `View=1` (the site's `.watchNow`
  form), reads `.serversList li[data-link]` embeds and the player iframe, delegates
  to CloudStream extractors, and falls back to scanning the embed page for a
  direct `.m3u8` / `.mp4` source

## Build

Requires JDK 17 and Android SDK (platform 35, build-tools 35).

```bash
./gradlew EgyDead:make
```

Output: `EgyDead/build/outputs/plugin/EgyDead.cs3`

## Notes

- Built and verified against the live site structure on 2026-10-15.
- EgyDead changes domains often (`tv10.` prefix); if the domain changes,
  update `mainUrl` in `EgyDeadProvider.kt` and rebuild.
