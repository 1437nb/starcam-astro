# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

A pure offline Android application for astrophotography plate-solving and night sky identification. Capture or import night sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), with precise overlays of constellation lines, proper star names, and Messier deep-sky objects.

---

## Download

Latest release: **[v1.5.52](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.52)**

| Package | Size | Notes |
|---|---|---|
| [StarCam-v1.5.52-perf-fix-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.52/StarCam-v1.5.52-perf-fix-release.apk) | 15.7 MB | **Recommended** — R8-minified signed build |
| [StarCam-v1.5.52-perf-fix-debug.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.52/StarCam-v1.5.52-perf-fix-debug.apk) | 24.8 MB | Includes debug logging |

All versions: [Releases](https://github.com/1437nb/starcam-astro/releases).

**Requirements**: Android 8.0 (API 26) or newer on an **arm64-v8a** device
(the bundled native solver ships arm64 libraries only; x86_64 emulators fall
back gracefully to the JVM catalog matcher).

## Key Features

- **3-Tier Hybrid Solving Engine**:
  - Native NDK port of `astrometry.net` 0.97 with 4100-series all-sky index files;
  - Custom 8,400+ star triangle matcher (triangle voting plus a **wide-field scoring round**, with bright-source masking and multi-candidate weak star rounds). The scoring round (v1.5.51) fixes identification for 60°+ wide-angle photos: each candidate triangle is fitted, stars are matched one-to-one, and the winner is chosen by alignment *rate* instead of per-star voting, which noisy false triangles can swamp;
- **Solving-time regression fix** (v1.5.52): the v1.5.51 wide-field scoring round
  cost an extra 15 s whenever solving *failed* (it scored ~70,000 candidate
  triangles one by one); stacked on the native engine's blind-solve segments the
  total could exceed a minute. A precomputed unit-vector table, a 3-second time
  budget and a high-confidence early exit bring the failure-path overhead down to
  2.5–3 s. The 12 bundled demo photos are back to **1–2 s each**, with no change in
  accuracy or success rate.

  - Optional online fallback via `nova.astrometry.net` API (requires user-provided API key).
- **Real-Time Viewfinder Recognition & AR Live Star Map**: CameraX analysis pipeline performs periodic blind solving, and a sensor-driven AR live star map projects the sky onto the viewfinder with zero latency as you move the phone (adjustable FOV, calibratable). Includes an all-sky mode that keeps rendering the lower hemisphere even when the phone points down (with a horizon line and 8-point compass), attitude smoothing with gyro extrapolation plus a complementary filter so the map neither jitters nor drifts, and target-finding navigation with a direction arrow and pulsing ring.
- **Layer Controls & Object Info Cards**: Independently toggle constellation lines, star names, constellation labels, and Messier overlays; press-and-hold to compare against the original photo; tap any object in the picture for a bilingual info card (type / magnitude / distance / background).
- **Rich Astronomical Overlays**:
  - Official 88 modern constellation stick figures aligned with Stellarium v23.4 constellation line data (672 segments) and names;
  - 3,800+ traditional Chinese and Western proper star names (Sirius, Vega, Betelgeuse, Arcturus, Polaris, etc.);
  - 44 prominent Messier deep-sky objects (Andromeda Galaxy M31, Orion Nebula M42, Pleiades M45, etc.) color-coded by astrophysical object type;
  - **Realtime Moon and planet labels** (v1.5.48): the Sun, Moon and planets are computed for the moment encoded in the photo's EXIF timestamp + GPS (JPL approximate Keplerian elements plus a Meeus lunar series, with topocentric parallax correction); the Sun and Moon are drawn at their true apparent diameter, and tapping them shows magnitude, elongation, illuminated fraction and apparent size.
- **Weak-EXIF fallback** (v1.5.49): when a photo carries no GPS, the device's current location is used to estimate the imaged sky region, so the native solver no longer has to start from a full-sky blind search; the Sun/Moon/planet labels and batch export benefit as well. Three hard boundaries: **EXIF GPS always wins and is never overridden**, no fallback without a capture timestamp, and a half-populated GPS pair is treated as missing and completed as a pair. The location source (EXIF vs. current fix) is stated on the solving progress and result screen, together with a "may be inaccurate" note.
- **Portrait photo solving fix** (v1.5.50): corrected the field-of-view estimate for portrait shots (EXIF Orientation=6/8). The estimator previously used the short side of the 35 mm-equivalent frame when computing the long-edge FOV, which shrank the solver's pixel-scale prior and made **portrait photos fail to solve**. The long-edge FOV is now always computed from the 36 mm long side, identical for portrait and landscape.
- **Bilingual UI**: One-tap switching between Simplified Chinese and English across the entire app, including constellation, star, and deep-sky object names.
- **Professional Astrophotography Tools**:
  - Camera Pro manual exposure (ISO / shutter control for light pollution and faint star fields) with a bubble level;
  - Automatic field-of-view (FOV) calibration;
  - Direct raw image gallery loading (bypasses OS downsampling to preserve genuine faint star centroids);
  - Dual-layer zoomable viewer (smooth comparison between original photo and annotated overlay);
  - Visual failure diagnostics (star detection density heatmap and shooting guidance; since v1.5.49 the annotated diagnostic image matches the original resolution, so it stays sharp when zoomed);
  - Deep space blue and night-vision red themes (protects dark adaptation in the field).
- **Privacy First**: Fully offline by default, so photos never leave the phone. A photo is uploaded to `nova.astrometry.net` (and the local cache copy deleted right after the request) only when the user explicitly enters an API key and selects online mode. The optional location permission is used solely for on-device sensor-assisted calibration in the camera screen — never for background tracking, and never attached to an uploaded JPEG. App data is excluded from system backup. No telemetry, trackers, or ads.

---

## Project Structure

```
.
├── code/                   # Android application source code
│   ├── app/                # Main module (Kotlin + Jetpack Compose)
│   │   ├── src/main/assets/indexes/         # astrometry.net offline FITS indexes
│   │   ├── src/main/assets/offline_photos/  # Benchmark test photos (12 real night sky shots)
│   │   └── src/main/jniLibs/arm64-v8a/      # Prebuilt libstellar_solver.so native engine
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── docs/                   # Engineering architecture and 51 validation reports (§0.11 ~ §0.62)
├── tools/                  # Python catalog generators and offline test utilities
├── LICENSE                 # GNU General Public License v2.0
├── README.md               # Chinese documentation
├── README_en.md            # English documentation
└── .gitignore
```

---

## Building Locally

### Prerequisites

- **JDK**: OpenJDK 17
- **Android SDK**: API 34 (Compile & Target SDK), minimum supported Android 8.0 (API 26)
- **Gradle**: 8.12+
- **Architecture**: The native solver engine is compiled for `arm64-v8a`. Real 64-bit devices are recommended (x86_64 emulators support the JVM star catalog engine, with native solver gracefully disabled).

### Command Line Build

```bash
cd code

# Assemble Debug APK
./gradlew :app:assembleDebug

# Run full unit tests (astronomical math, catalog integrity, solar-system
# ephemerides, cross-engine checks, real-photo regression — 150/150 passing)
./gradlew :app:testDebugUnitTest

# Output path
# app/build/outputs/apk/debug/StarCam-v*-debug.apk

# Release APK (R8-minified; add -x lintVitalRelease to skip lint on offline machines)
./gradlew :app:assembleRelease -x lintVitalRelease
# app/build/outputs/apk/release/StarCam-v*-release.apk
```

### Real-Photo Regression (optional)

`RealPhotoMatchTest` and `Photo12RegressionTest` read `.gray` captures
(`int32 width + int32 height + float32 grayscale`). The material is not stored in
the repository (privacy and size) — point the tests at your local copy:

```bash
PHOTO_DIR=/path/to/realphotos ./gradlew :app:testDebugUnitTest
```

The suite pins ground-truth assertions: `apod4` (Big Dipper, 34° narrow field)
and `user-nanning-20260912` (a user capture, 74° wide field, ground truth from an
independent astrometry.net solve) must solve; `apod1/2/3/5`, `pleiades`, and two
`m44` frames must stay UNSOLVED as false-positive controls.

---

## License & Acknowledgements

This project is licensed under the **[GNU General Public License v2.0](LICENSE)**.

We gratefully acknowledge the following open-source projects and scientific catalogs:

1. **[astrometry.net](https://astrometry.net/)** (GPL-2.0)
   - Core blind plate-solving algorithms and FITS index structure.
2. **[StellarSolver](https://github.com/rlancaste/stellarsolver)** (LGPL-2.1 / MIT)
   - Cross-platform C++ wrapper for the astrometry.net solver.
3. **[Stellarium](https://stellarium.org/)** (GPL-2.0)
   - Traditional star names and celestial culture data.
4. **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)** (Public Domain)
   - Bright star astrometric data (Yale BSC5).
5. **[Android Jetpack & CameraX](https://developer.android.com/jetpack)** (Apache-2.0)
   - Modern Android UI and camera streaming architecture.
6. **[OkHttp](https://square.github.io/okhttp/)** (Apache-2.0)
   - Network layer for optional online fallback API.

---

## Privacy Policy

1. **Offline by Default**: Local engines and offline indexes handle solving on-device, so photos stay on the device by default.
2. **Explicit Online Mode**: A photo is uploaded to `nova.astrometry.net` only after you enter an API key in Settings and select online mode. The local upload copy is deleted after the request finishes.
3. **Minimal Permissions and Local Processing**: The app requests camera, photo-read, and optional location permissions for sensor-assisted calibration. Location is used only while the camera screen is open for on-device calculations; it is neither tracked in the background nor included in the uploaded JPEG. App data is excluded from system backups.
