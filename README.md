# CloudStream Desktop

A standalone desktop port of **CloudStream** for Windows, Linux, and macOS. Built with **Compose for Desktop** and reusing the official Kotlin Multiplatform library.

## What Works
- **Built-in providers** (TMDB, Trakt, etc.) run natively on JVM.
- **Built-in extractors** (HLS, MP4, various hosters) work out of the box.
- **Desktop plugin loader** — drop `.jar` or `.cs3` plugins into `~/.cloudstream/plugins` and they load on startup.
- **JavaFX WebView** — replaces Android `WebViewResolver` for scraping flows that need a browser engine.
- **Embedded HTML5 player** — plays extracted stream URLs using JavaFX WebView.

## What Needs Work
- Extensions that rely on **heavy Cloudflare bypass** or **Android-specific APIs** may fail (JavaFX WebView is not a full Chromium engine).
- The UI is a scaffold. Full feature parity (downloads, settings, subtitles, Chromecast) requires more work.

## Build from Source

### Prerequisites
- JDK 17 or later

### Windows EXE
```batch
gradlew.bat :desktop:packageExe
```
Output: `desktop\build\compose\binaries\main\exe\`

### Linux / Debian
```bash
./gradlew :desktop:packageDeb
```
Output: `desktop/build/compose/binaries/main/deb/`

### Run without packaging
```bash
./gradlew :desktop:run
```

## GitHub Actions (Automatic Builds)
Push this repo to GitHub and the workflow in `.github/workflows/desktop_build.yml` will automatically build:
- **Windows EXE** (download from Actions artifacts)
- **Linux DEB** (download from Actions artifacts)
- **Linux Portable App-Image** (download from Actions artifacts)

## Plugin Loading on Desktop
Place any desktop-compatible `.jar` or `.cs3` plugin files into:
- Windows: `%USERPROFILE%\.cloudstream\plugins\`
- Linux/macOS: `~/.cloudstream/plugins/`

Plugins must be pure Kotlin (no Android-only imports) to load on the JVM.

## Architecture
- `library/` — Core engine (extractors, plugins, networking) from the official CloudStream library, compiled for JVM only.
- `desktop/` — Compose for Desktop UI, JavaFX WebView resolver, plugin loader, and player.
