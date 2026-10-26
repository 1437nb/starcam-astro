# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

An **offline-first** Android app for astrophotography plate-solving and night sky identification. Capture or import night-sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), overlay constellation lines, proper star names, Messier deep-sky objects, and real-time Sun/Moon/planet positions, or explore the sky with the sensor-driven AR live star map.

---

## Download

Latest release: **[v1.5.87](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.87)**

| Package | Size | Notes |
| :--- | :--- | :--- |
| **[StarCam-v1.5.87-new-brand-icon-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.87/StarCam-v1.5.87-new-brand-icon-release.apk)** | 15.8 MB | **Recommended** — R8-minified official signed APK (in-place upgrade) |

- **Requirements**: Android 8.0 (API 26) or newer on an **arm64-v8a** device (the bundled native `astrometry.net` engine targets arm64; x86_64 emulators gracefully fall back to the pure-JVM catalog solver).
- **Recent Highlights (v1.5.82 – v1.5.87)**:
  - **v1.5.87**: Brand-new "Camera Viewfinder + Constellation" Adaptive Icon (with Android 13+ Material You monochrome layer) and a dual-theme Home brand badge that adapts to Night Red dark-adaptation mode;
  - **v1.5.86**: Interactive layer FilterChips, one-tap `📐 Lines Only` clean export, 4-quadrant star label de-overlap & boundary clamping, and bidirectional layer state sync with the full-screen zoom viewer;
  - **v1.5.82 – v1.5.85**: Tonight Sky ephemeris card (moon phase, lunar age, 5 bright planets, and imaging tips), parallel dual-engine racing, lock-free `MatchContext` concurrency, and 0ms instant history loading via persisted WCS parameters.
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
- **Flexible Export & Batch Mode**: Toggle individual layers via FilterChips, compare with the original photo in the full-screen zoomable viewer, export either `📐 Lines Only` or custom-annotated images, and batch-solve up to 9 photos concurrently.

### 3. AR Live Star Map & Target Navigation
- **Zero-Latency Sensor Fusion**: Driven by rotation vector + gyroscope complementary filtering for jitter-free panning, including an **All-Sky Mode** (renders the lower hemisphere and an 8-point horizon compass when pointing downward);
- **🎯 Target Finder**: Select any bright star, constellation, or Messier object for directional arrow guidance and pulsing target rings.

### 4. Astrophotography Tools & Dark Adaptation
- **Tonight Sky Card**: Pure-JVM solar system ephemeris displaying moon illumination, lunar age, visibility windows for Mercury/Venus/Mars/Jupiter/Saturn, and observing advice;
- **Native FITS & Camera RAW Support**: Built-in Kotlin FITS decoder (BITPIX 8/16/32/±32/±64 with automatic WCS/date/location priors) and camera RAW preview extractor (CR2/NEF/ARW/DNG/etc.), plus direct original-file gallery loading;
- **Dual Themes & Bilingual UI**: Instant switching between **Deep Sky Blue** and **Night Vision Red** (preserves dark adaptation in the field), as well as Simplified Chinese and English.

---

## Project Structure

```text
.
├── code/                   # Android project source (Kotlin + Jetpack Compose Material 3)
│   └── app/src/
│       ├── main/java/com/starcam/astro/     # Astronomical math, solvers, and Compose UI
│       ├── main/assets/indexes/             # Bundled astrometry.net offline FITS indexes
│       ├── main/assets/offline_photos/      # 12 public night-sky demo photos
│       ├── main/jniLibs/arm64-v8a/          # Prebuilt libstellar_solver.so native solver
│       ├── main/cpp/astro_bridge.c          # JNI bridge source
│       └── test/java/com/starcam/astro/     # 35 unit test classes & truth regression suite
├── docs/                   # Architecture & 100+ validation reports (§0.00 – §0.121)
├── tools/                  # Catalog generators, truth calibration & release scripts
└── LICENSE                 # GNU General Public License v2.0
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
| Native JNI bridge (`app/src/main/cpp/astro_bridge.c`) | Also requires NDK r26d + `astrometry.net` 0.97 static libs + `cfitsio` 3.47 (see [`docs/75`](docs/75-外部贡献者指南与工程外依赖说明.md)) |

---

## Privacy Policy

1. **Offline by Default**: Plate-solving, ephemeris calculations, and overlay rendering run entirely on-device by default. Photos never leave your phone, with zero ads, telemetry, or third-party trackers.
2. **Explicit Online Mode**: A photo is uploaded to `nova.astrometry.net` only when you explicitly configure an API key in Settings and enable online fallback; the temporary upload cache is deleted immediately after the request completes.
3. **Minimal Permissions**: Camera and media permissions are used strictly for capturing and reading sky images; optional location access is used locally on-device for sensor calibration and topocentric ephemeris math, never tracked in the background or attached to uploads.

---

## License & Acknowledgements

Licensed under the **[GNU General Public License v2.0](LICENSE)**. Special thanks to:

- **[astrometry.net](https://astrometry.net/)** (GPL-2.0) — Core blind plate-solving algorithms and FITS index system
- **[StellarSolver](https://github.com/rlancaste/stellarsolver)** (LGPL-2.1 / MIT) — Cross-platform wrapper reference
- **[Stellarium](https://stellarium.org/)** (GPL-2.0) — 88 constellation line definitions and traditional Chinese star culture data
- **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)** (Public Domain) — Yale Bright Star Catalogue (BSC5)
- **[Android Jetpack Compose & CameraX](https://developer.android.com/jetpack)** (Apache-2.0) & **[OkHttp](https://github.com/square/okhttp)** (Apache-2.0)
