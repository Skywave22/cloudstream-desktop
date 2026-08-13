# CloudStream Desktop

A standalone CloudStream port for Windows, Linux, and macOS, built with Compose for Desktop and the CloudStream Kotlin library.

## Features

- TMDB home, search, and metadata pages.
- Concurrent search across installed providers.
- Movie, live-stream, series, and anime episode selection.
- Provider link extraction with quality/source selection and timeouts.
- Built-in CloudStream extractors on the JVM.
- JVM plugin loading from `~/.cloudstream/plugins` using `manifest.json` or `@CloudstreamPlugin` discovery.
- JavaFX WebView support for scraping flows and an embedded HTML5 player.
- Native Windows, Linux, and macOS packaging.

## Current limitations

- The regular Android `.cs3` format contains `classes.dex` and cannot execute on a desktop JVM. Install a JVM `.jar` build instead. A `.cs3` archive is accepted only when it contains JVM `.class` files.
- Extensions that import Android APIs are not desktop-compatible.
- JavaFX exposes no full WebKit request-interception API. The resolver detects top-level navigation and Resource Timing entries, so advanced Cloudflare or browser-challenge flows may still fail.
- JavaFX media support depends on the operating system's codecs. DASH, torrents, and magnet links are not supported by the embedded player. HLS support varies by JavaFX/platform.
- Streams that require custom `Referer` or authorization headers may be blocked by host CORS rules because JavaFX WebView cannot guarantee those headers for media requests.
- Downloads, settings, subtitle rendering, and casting are not implemented yet.

## Build from source

### Prerequisites

- JDK 17 or later
- The Gradle wrapper included in this repository
- Linux `.deb` packaging only: `fakeroot` and `dpkg-dev`
- Linux portable runtime: GTK 3 and ALSA (`libgtk-3-0`/`libgtk-3-0t64` and `libasound2`/`libasound2t64`)

On current Debian/Ubuntu build hosts:

```bash
sudo apt-get install fakeroot dpkg-dev
```

The generated `.deb` declares the GTK/ALSA alternatives automatically.

Run verification first:

```bash
./gradlew clean check
```

### Run without packaging

```bash
./gradlew :desktop:run
```

### Windows

```batch
gradlew.bat :desktop:packageExe
```

Output: `desktop\build\compose\binaries\main\exe\`

### Linux

```bash
./gradlew :desktop:packageDeb
```

Output: `desktop/build/compose/binaries/main/deb/`

For a portable application directory:

```bash
./gradlew :desktop:createDistributable
```

Output: `desktop/build/compose/binaries/main/app/CloudStreamDesktop/`

### macOS

```bash
./gradlew :desktop:packageDmg
```

Output: `desktop/build/compose/binaries/main/dmg/`

Native packages must be built on their target operating system.

## Automatic builds

`.github/workflows/desktop_build.yml` runs tests and produces:

- Windows EXE installer and portable ZIP
- Linux Debian package and portable `.tar.gz`
- macOS DMG and portable ZIP

Download the generated files from a GitHub Actions run's **Artifacts** section.

## Desktop plugins

Place desktop-compatible plugin archives in:

- Windows: `%USERPROFILE%\.cloudstream\plugins\`
- Linux/macOS: `~/.cloudstream/plugins/`

A supported plugin must contain JVM `.class` files, extend `BasePlugin`, and either:

1. include `manifest.json` with `pluginClassName`, or
2. annotate its plugin entry class with `@CloudstreamPlugin`.

The loader reports malformed archives, incompatible DEX-only `.cs3` files, missing entry classes, and plugin startup failures instead of crashing the application.

## Project layout

- `library/` — CloudStream core models, extractors, networking, and JVM platform implementations.
- `desktop/` — Compose UI, navigation, plugin loader, link-resolution flow, and JavaFX player.
- `desktop/src/test/` — playback, HTML-safety, and plugin compatibility regression tests.
