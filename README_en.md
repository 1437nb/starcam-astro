# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v3+](https://img.shields.io/badge/License-GPL%20v3%2B-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

An **offline-first** Android app for astrophotography plate-solving and night sky identification. Capture or import night-sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), overlay constellation lines, proper star names, Messier deep-sky objects, and real-time Sun/Moon/planet positions, or explore the sky with the sensor-driven AR live star map.

---

## Download

**Latest release v1.5.96 (versionCode 116, §0.135)** prepares StarCam for F-Droid inclusion: upgrades the license to **GPL-3.0-or-later** with a complete [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), disables the AGP encrypted `dependenciesInfo` signing block and `vcsInfo`, makes `tools/build_native.py` build cleanly from SHA-256-pinned sources across Linux (CMake 3.22–4.x) and Windows, and adds Fastlane metadata plus the [F-Droid build recipe](docs/fdroid/com.starcam.astro.yml). See the [release notes](docs/release-notes-v1.5.96.md) and [§0.135 report](docs/120-F-Droid上架准备-§0.135.md).

**v1.5.95 (versionCode 115, §0.134)** hardens cancellation and time budgets, large-FITS memory use, import isolation, individual retry synchronization, online cache orientation, and raced bitmap ownership. It restores a pinned source build of the native solver and fixes its acceptance threshold and WCS reference pixels / axis direction. See the [changes and verifiable checklist](docs/119-验证报告增补-§0.134-稳定性收尾与原生源码重建v1.5.95.md).

**Local signed build v1.5.94 (versionCode 114, §0.133)** unifies normal images, RAW and FITS under Import Photo, with mixed selection of up to 9 files. Original-gallery and compatible-gallery modes remain under Choose from gallery / More methods. Its official signed Release APK has been built and verified locally with the same certificate as v1.5.93, allowing in-place upgrades. It has not been published. See the [unified import and build report](docs/118-验证报告增补-§0.133-照片RAW与FITS统一导入v1.5.94.md).

**Local signed build v1.5.93 (versionCode 113, §0.132)** adds preview-size or original-size annotated export; RAW uses the full embedded JPEG preview. Its official signed Release APK was built and verified locally but has not been published. See the [scope and build verification](docs/116-验证报告增补-§0.132-原图尺寸导出v1.5.93.md). The public download remains v1.5.92.

**Compatibility checks**: v1.5.95 passed 152 functional / UI test executions across Android 9 and 14 emulators, each with the rebuilt x86_64 native solver (30 functional + 9 UI) and default JVM fallback (28 functional + 9 UI). Physical ARM phones, cameras and AR sensors still need validation. See the [results and scope](docs/119-验证报告增补-§0.134-稳定性收尾与原生源码重建v1.5.95.md) and [emulator setup](docs/117-安卓模拟器与兼容性记录-20261102.md). Open a window with `pwsh -File tools/start_android_emulator.ps1 -Api 34`.

**Full local JVM regression**: v1.5.95 forcibly reran 59 classes / 373 tests with zero failures, errors or skips, including 352 standard tests and 21 local photo/FITS/narrow-field tests. Public photos solved correctly in 12/12 cases; the narrow-field observation bench still left 20/32 unsolved. See the [per-suite counts](docs/compatibility/20261103-v1.5.95-jvm-full.json).

**v1.5.92 (versionCode 112)** lets you open each completed batch photo in the full result viewer, reuse its solution, choose layers, and export only the photos you want. Returning preserves the batch list. **Batch solving no longer saves every annotated photo automatically.** This release also includes import progress and cancellation, solving phases and elapsed time, performance improvements, and bilingual refinements. See the [release notes](docs/release-notes-v1.5.92.md) and [validation checklist](docs/114-验证报告增补-§0.131-批量逐张查看与手动导出v1.5.92.md).

Latest release: **[v1.5.96](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.96)**

| Package | Size | Notes |
| :--- | :--- | :--- |
| **[StarCam-v1.5.96-fdroid-readiness-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.96/StarCam-v1.5.96-fdroid-readiness-release.apk)** | 12.3 MiB | **Recommended** — R8-minified official signed APK (in-place upgrade) |

- **Requirements**: Android 8.0 (API 26) or newer on an **arm64-v8a** device (the bundled native `astrometry.net` engine targets arm64; x86_64 emulators gracefully fall back to the pure-JVM catalog solver).
- **Using batch results**: Select photos and wait for completion → open a photo → choose layers → save the current annotations or constellation lines only. Viewing and returning does not export to your gallery.
- **Recent Highlights (v1.5.88 – v1.5.92)**:
  - **v1.5.92**: Individual batch photo viewing and manual export, cached solutions, and preserved batch results on return;
  - **v1.5.91**: Unified import panel, background copy progress and cancellation, clearer offline status, solving phases and elapsed time, shared image preprocessing, thumbnail caching, and rendering buffers;
  - **v1.5.88 – v1.5.90**: Pure black / white themes, full-row settings switches, bilingual solving messages, magnitude labels and history constellation names, plus performance and robustness improvements.
- **APK SHA-256**: `44152A718334921791E530F852DA1113BA07FF5AAB1931BE8DF1F9E24D363347`.
- Full version history and release notes: **[GitHub Releases](https://github.com/1437nb/starcam-astro/releases)** & [`docs/`](docs/).

---

## Key Features

### 1. 3-Tier Hybrid Plate-Solving Engine
- **Native `astrometry.net` 0.97 Engine**: Ships with 8 offline 4100-series all-sky FITS indexes for high-precision on-device blind solving;
- **Custom 8,400+ Tiered Catalog Matcher**: Combines shallow ($\le 4.0$ mag) and deep ($\le 6.5$ mag) star tiers, triangle voting, and a wide-field one-to-one scoring round — covering **10° narrow fields up to 70°+ ultra-wide shots**, with parallel dual-engine racing on wide fields;
- **Optional Online Fallback**: Supports user-provided `nova.astrometry.net` API keys (stored via `EncryptedSharedPreferences`).

### 2. Rich Celestial Overlays & Clean Export
- **Complete Sky Layers**: Official 88 modern constellations (aligned with Stellarium v23.4), 3,800+ traditional Chinese and Western proper star names, 44 Messier deep-sky objects, and **real-time Sun, Moon, and planet positions** computed from EXIF timestamp + GPS (with topocentric parallax correction);
- **Smart De-overlap & Interactive Cards**: Automatically suppresses overlapping star labels inside Messier objects (e.g., Alcyone in M45, Hatysa in M42) and clamps edge labels inside safe inward quadrants; tap any object for a bilingual info card;
- **Flexible Export & Batch Mode**: Toggle individual layers via FilterChips, compare with the original photo in the full-screen zoomable viewer, export either `📐 Lines Only` or custom-annotated images, and batch-solve up to 9 photos concurrently, then open each result and export manually.

### 3. AR Live Star Map & Target Navigation
- **Zero-Latency Sensor Fusion**: Driven by rotation vector + gyroscope complementary filtering for jitter-free panning, including an **All-Sky Mode** (renders the lower hemisphere and an 8-point horizon compass when pointing downward);
- **🎯 Target Finder**: Select any bright star, constellation, or Messier object for directional arrow guidance and pulsing target rings.

### 4. Astrophotography Tools & Dark Adaptation
- **Tonight Sky Card**: Pure-JVM solar system ephemeris displaying moon illumination, lunar age, visibility windows for Mercury/Venus/Mars/Jupiter/Saturn, and observing advice;
- **Native FITS & Camera RAW Support**: Built-in Kotlin FITS decoder (BITPIX 8/16/32/±32/±64 with automatic WCS/date/location priors) and camera RAW preview extractor (CR2/NEF/ARW/DNG/etc.), plus direct original-file gallery loading;
- **Four Themes & Bilingual UI**: Deep Sky Blue, Pure Black, Pure White, and Night Vision Red, with instant Simplified Chinese / English switching.

---

## Project Structure

```text
.
├── code/                   # Android project source (Kotlin + Jetpack Compose Material 3)
│   └── app/src/
│       ├── main/java/com/starcam/astro/     # Astronomical math, solvers, and Compose UI
│       ├── main/assets/indexes/             # Bundled astrometry.net offline FITS indexes
│       ├── main/assets/offline_photos/      # 12 public night-sky demo photos
│       ├── main/jniLibs/arm64-v8a/          # libstellar_solver.so native solver (rebuildable from source via tools/build_native.py)
│       ├── main/cpp/astro_bridge.c          # JNI bridge source
│       └── test/java/com/starcam/astro/     # Standard suite: 46 classes / 336 tests, including public-photo truth checks
├── docs/                   # Architecture & 100+ validation reports (through §0.132)
├── tools/                  # Catalog generators, truth calibration & release scripts
├── fastlane/metadata/      # F-Droid store metadata (zh-CN / en-US descriptions, changelogs, icon, screenshots)
├── THIRD_PARTY_NOTICES.md  # Sources and licenses of bundled third-party code, data and media
└── LICENSE                 # GNU General Public License v3.0 (GPL-3.0-or-later)
```

---

## Building & Contributing

The prebuilt `libstellar_solver.so` is tracked in the repository. Building the app, editing Kotlin/UI code, and running unit tests require **only JDK 17 and Android SDK 34**:

```bash
git clone https://github.com/1437nb/starcam-astro.git
cd starcam-astro/code
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 1. Assemble Debug APK
./gradlew :app:assembleDebug

# 2. Run unit tests & 12-photo public truth regression
./gradlew :app:testDebugUnitTest --rerun-tasks -PskipPhotoTests=true

# 3. Assemble Release APK (R8-minified)
./gradlew :app:assembleRelease -x lintVitalRelease
```

| Contribution Area | Prerequisites |
| :--- | :--- |
| Kotlin / astronomical math / Compose UI / docs & i18n | **JDK 17 + Android SDK 34** (ready out of the box) |
| Native JNI bridge (`app/src/main/cpp/astro_bridge.c`) | Also requires Python 3.10+, NDK r26d, CMake + Ninja; `python3 tools/build_native.py --fetch` rebuilds from SHA-256-pinned `astrometry.net` 0.97 and `cfitsio` 3.47 sources on Windows or Linux (see [`docs/120`](docs/120-F-Droid上架准备-§0.135.md)) |

---

## Privacy Policy

1. **Offline by Default**: Plate-solving, ephemeris calculations, and overlay rendering run entirely on-device by default. Photos never leave your phone, with zero ads, telemetry, or third-party trackers.
2. **Explicit Online Mode**: A photo is uploaded to `nova.astrometry.net` only when you explicitly configure an API key in Settings and enable online fallback; the temporary upload cache is deleted immediately after the request completes.
3. **Minimal Permissions**: Camera and media permissions are used strictly for capturing and reading sky images; optional location access is used locally on-device for sensor calibration and topocentric ephemeris math, never tracked in the background or attached to uploads.

---

## License & Acknowledgements

Licensed under the **[GNU General Public License v3.0 or later](LICENSE)** (GPL-3.0-or-later) since v1.5.96. Reason: the bundled native solver statically links the GSL copy vendored by `astrometry.net` (GPL-3.0-or-later), and the `astrometry.net` license requires the combined work to be distributed under GPL-3.0 or later. Releases up to v1.5.95 remain under their original GPL-2.0 terms. See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** for every component's origin and license. Special thanks to:

- **[astrometry.net](https://astrometry.net/)** 0.97 (BSD-3-Clause; vendored GSL: GPL-3.0-or-later, qfits: GPL-2.0-or-later) — Core blind plate-solving algorithms and 4100-series FITS indexes (Tycho-2)
- **[CFITSIO](https://heasarc.gsfc.nasa.gov/fitsio/)** 3.47 (NASA / HEASARC permissive license) — FITS I/O
- **[StellarSolver](https://github.com/rlancaste/stellarsolver)** (LGPL-2.1 / MIT) — Cross-platform wrapper reference
- **[Stellarium](https://stellarium.org/)** (GPL-2.0-or-later) — 88 constellation line definitions and traditional Chinese star culture data
- **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)** (Public Domain) — Yale Bright Star Catalogue (BSC5)
- **[Android Jetpack Compose & CameraX](https://developer.android.com/jetpack)** (Apache-2.0) & **[OkHttp](https://github.com/square/okhttp)** (Apache-2.0)
