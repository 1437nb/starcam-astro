# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v3+](https://img.shields.io/badge/License-GPL%20v3%2B-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

An **offline-first** Android app for astrophotography plate-solving and night sky identification. Capture or import night-sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), overlay constellation lines, Chinese & Western proper star names, Messier deep-sky objects, and real-time Sun/Moon/planet positions, or explore the sky with a sensor-driven AR live star map.

---

## Download

Latest release: **[v1.5.106](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.106)** ([Release Notes](docs/release-notes-v1.5.106.md) · [All Releases](https://github.com/1437nb/starcam-astro/releases))

| Package | Size | SHA-256 |
| :--- | :--- | :--- |
| **[StarCam-v1.5.106-online-nova-solver-fix-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.106/StarCam-v1.5.106-online-nova-solver-fix-release.apk)** | 12.46 MiB | `D5C0400E8F3E391F5E84B1071C5602AEBB923CD3385881E6BD06F2643E209786` |

- **Requirements**: Android 8.0 (API 26) or newer, recommended on **arm64-v8a** devices (ships with the offline native `astrometry.net` engine; x86_64 emulators automatically fall back to the pure-JVM catalog solver). Official signed APKs support direct in-place upgrades.

---

## Key Features

### 1. 3-Tier Hybrid Plate-Solving Engine
- **On-Device `astrometry.net` 0.97 NDK Engine**: Ships with 8 offline 4100-series all-sky FITS indexes and supports optional validated extra indexes for high-precision offline blind WCS solving;
- **Custom 8,400+ Tiered Catalog Matcher**: Combines shallow/intermediate/deep star tiers, flat concentric-ring triangle lookup, wide-field one-to-one scoring, and foreground-aware sky recovery — covering **10° narrow fields up to 70°+ ultra-wide shots**, with parallel dual-engine racing on wide fields;
- **Optional Online Fallback**: Supports user-provided `nova.astrometry.net` API keys (stored via `EncryptedSharedPreferences`).

### 2. Rich Celestial Overlays & Original-Resolution Export
- **Complete Sky Layers**: 88 official constellations (aligned with Stellarium v23.4), 3,800+ traditional Chinese and Western proper star names, 44 Messier deep-sky objects, and **real-time Sun, Moon, and planet positions** computed from EXIF timestamp + GPS (with topocentric parallax correction);
- **Smart De-overlap & Interactive Viewer**: Automatically prevents label collisions near Messier clusters and frame edges; full-screen pinch-to-zoom viewer with 1-tap reset and hold-to-compare original; tap any celestial object for a bilingual info card and copy coordinates with one tap;
- **Flexible Export & Batch Solving**: Toggle any layer independently, export at preview or full original resolution (`Save Current` or `Lines Only`), and batch-import up to 9 mixed photos, camera RAW files, or FITS images with early per-photo inspection and manual export.

### 3. AR Live Star Map, Pro Camera & Offline Demo
- **Zero-Latency AR Target Finder**: Complementary-filtered rotation vector + gyroscope projection with lower-hemisphere All-Sky mode and a searchable target picker guiding you to stars, constellations, Messier objects, and planets;
- **Astrophotography Pro Controls**: Collapsible one-handed Pro exposure drawer (Wide Milky Way / Deep Dark Sky / Handheld Night presets) and tap-to-focus;
- **Zero-Setup Offline Demo**: Includes 12 verified smartphone night-sky photos running the full local solver pipeline, plus a **dual-mode synthetic star field generator** (**Classic Sky** with 18 non-repeating constellations or direct pick, and **All-Sky Roam** with uniform spherical sampling & FOV presets).

### 4. Observing Tools & Dark Adaptation
- **Tonight's Sky Card**: Pure-JVM ephemeris rendering a spherical Moon-phase disc, illumination, lunar age, visibility windows for Mercury–Saturn, and observing tips;
- **Native FITS & Camera RAW Support**: Built-in Kotlin FITS decoder and embedded RAW preview extractor, plus an original-file gallery mode that avoids OS downscaling and EXIF stripping;
- **Four Themes & Bilingual UI**: Deep Sky Blue, Pure Black, Pure White, and 1-tap Night Vision Red, with instant English / Simplified Chinese switching.

---

## Project Structure

```text
.
├── code/                   # Android project source (Kotlin + Jetpack Compose Material 3)
│   └── app/src/
│       ├── main/java/com/starcam/astro/     # Astronomical math, solvers, and Compose UI
│       ├── main/assets/indexes/             # Bundled astrometry.net offline FITS indexes (8)
│       ├── main/assets/offline_photos/      # 12 public night-sky demo photos
│       ├── main/jniLibs/arm64-v8a/          # Prebuilt libstellar_solver.so native solver
│       ├── main/cpp/astro_bridge.c          # JNI bridge source
│       └── test/java/com/starcam/astro/     # Unit tests & 12-photo public truth regression
├── docs/                   # Architecture, release notes, and validation reports
├── tools/                  # Catalog generators, truth calibration, audit & release scripts
├── fastlane/metadata/      # F-Droid store metadata (en-US / zh-CN descriptions, changelogs, assets)
├── THIRD_PARTY_NOTICES.md  # Sources and licenses of bundled third-party code, data, and media
└── LICENSE                 # GNU General Public License v3.0 (GPL-3.0-or-later)
```

---

## Building & Contributing

The prebuilt `libstellar_solver.so` is tracked in the repository, so everyday development and testing require **only JDK 17 and Android SDK 34**:

```bash
git clone https://github.com/1437nb/starcam-astro.git
cd starcam-astro/code
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug                                   # Assemble Debug APK
./gradlew :app:testDebugUnitTest --rerun-tasks -PskipPhotoTests=true  # Run unit tests & public photo truth suite
./gradlew :app:assembleRelease -x lintVitalRelease             # Assemble Release APK (R8-minified)
```

To modify `app/src/main/cpp/astro_bridge.c` and rebuild `.so` from source, Python 3.10+, NDK r26d, CMake, and Ninja are also required; run `python3 tools/build_native.py --fetch` on Windows or Linux to build from SHA-256-pinned sources (see [`docs/120`](docs/120-F-Droid上架准备-§0.135.md)).

---

## Privacy Policy

1. **Offline by Default**: Plate-solving, ephemeris math, and overlay rendering run completely on-device by default. Photos never leave your phone, with zero ads, telemetry, or third-party trackers.
2. **Explicit Online Mode**: Photos are uploaded to `nova.astrometry.net` only when you explicitly enter an API key and enable online fallback in Settings; upload caches are cleared immediately afterward.
3. **Minimal Permissions**: Camera and storage permissions are used strictly to capture and read sky images; optional location access stays on-device for sensor calibration and topocentric ephemeris calculations.

---

## License & Acknowledgements

Licensed under the **[GNU General Public License v3.0 or later](LICENSE)** (GPL-3.0-or-later). See **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)** for full component origins and licenses. Special thanks to:

- **[astrometry.net](https://astrometry.net/)** 0.97 (BSD-3-Clause / GSL GPL-3.0+ / qfits GPL-2.0+) — Core blind plate-solving algorithms and 4100-series FITS indexes
- **[CFITSIO](https://heasarc.gsfc.nasa.gov/fitsio/)** 3.47 — FITS I/O
- **[StellarSolver](https://github.com/rlancaste/stellarsolver)** (LGPL-2.1 / MIT) — Cross-platform wrapper reference
- **[Stellarium](https://stellarium.org/)** (GPL-2.0-or-later) — 88 constellation lines and traditional Chinese star lore
- **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)** (Public Domain) — Yale Bright Star Catalogue (BSC5)
- **[Android Jetpack Compose & CameraX](https://developer.android.com/jetpack)** & **[OkHttp](https://github.com/square/okhttp)** (Apache-2.0)
