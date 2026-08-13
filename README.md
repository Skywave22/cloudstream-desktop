# CloudStream Desktop — Windows

A Windows desktop CloudStream port built with Compose for Desktop and the CloudStream Kotlin library.

## Features

- TMDB home, search, and metadata pages.
- Concurrent search across installed providers.
- Movie, live-stream, series, and anime episode selection.
- Provider link extraction with quality/source selection and timeouts.
- Built-in CloudStream extractors on the JVM.
- JVM plugin loading using `manifest.json` or `@CloudstreamPlugin` discovery.
- JavaFX WebView support and an embedded HTML5 player.
- Windows EXE installer and portable Windows application builds.

## Current limitations

- Standard Android `.cs3` files contain `classes.dex` and cannot execute on a desktop JVM. Use a JVM `.jar` build. A `.cs3` archive works only when it contains JVM `.class` files.
- Extensions that import Android APIs are not desktop-compatible.
- Advanced Cloudflare/browser-challenge flows may fail because JavaFX does not expose full WebKit request interception.
- DASH, torrents, and magnet links are not supported by the embedded player. HLS and codec support can vary by Windows configuration.
- Hosts requiring custom `Referer` or authorization headers may be blocked by CORS restrictions.
- Downloads, settings, subtitle rendering, and casting are not implemented yet.

## Automatic Windows build on GitHub

The workflow at `.github/workflows/desktop_build.yml` builds Windows only.

It uses the WiX Toolset 3.14 already installed on GitHub's `windows-2025` runner. The workflow sets `WIX_PATH`, so the Compose plugin skips its unreliable `downloadWix` task.

After pushing the complete source to `main` or `master`:

1. Open the repository's **Actions** tab.
2. Select **Build Windows EXE**.
3. Wait for the **Windows EXE** job to finish.
4. Download one of these artifacts:
   - `CloudStreamDesktop-Windows-Installer` — EXE installer.
   - `CloudStreamDesktop-Windows-Portable` — portable ZIP.

## Local Windows build

### Prerequisites

- JDK 17 or later.
- WiX Toolset 3.x for installer packaging.
- The included Gradle wrapper.

Before local packaging, point `WIX_PATH` at the WiX `bin` directory if it is not already on `PATH`:

```batch
set "WIX_PATH=C:\Program Files (x86)\WiX Toolset v3.14\bin"
set "PATH=%WIX_PATH%;%PATH%"
```

Run tests:

```batch
gradlew.bat check
```

Build the installer:

```batch
gradlew.bat :desktop:packageExe
```

Installer output:

```text
desktop\build\compose\binaries\main\exe\
```

Build the portable application:

```batch
gradlew.bat :desktop:createDistributable
```

Portable output:

```text
desktop\build\compose\binaries\main\app\CloudStreamDesktop\
```

## Desktop plugins

Place Windows JVM-compatible plugin archives in:

```text
%USERPROFILE%\.cloudstream\plugins\
```

A supported plugin must contain JVM `.class` files, extend `BasePlugin`, and either:

1. include `manifest.json` with `pluginClassName`, or
2. annotate its entry class with `@CloudstreamPlugin`.

## Project layout

- `library/` — CloudStream core models, extractors, and networking.
- `desktop/` — Compose UI, plugin loader, link resolution, and JavaFX player.
- `desktop/src/test/` — regression tests.
