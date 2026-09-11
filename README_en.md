# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

A pure offline Android application for astrophotography plate-solving and night sky identification. Capture or import night sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), with precise overlays of constellation lines, proper star names, and Messier deep-sky objects.

---

## Key Features

- **3-Tier Hybrid Solving Engine**:
  - Native NDK port of `astrometry.net` 0.97 with 4100-series all-sky index files;
  - Custom 8,400+ star triangle voting matcher (with bright-source masking and multi-candidate weak star rounds for sub-second wide-field solving);
  - Optional online fallback via `nova.astrometry.net` API (requires user-provided API key).
- **Real-Time Viewfinder Recognition & AR Live Star Map**: CameraX analysis pipeline performs periodic blind solving, and a sensor-driven AR live star map projects the sky onto the viewfinder with zero latency as you move the phone (adjustable FOV, calibratable). Includes an all-sky mode that keeps rendering the lower hemisphere even when the phone points down (with a horizon line and 8-point compass), attitude smoothing with gyro extrapolation plus a complementary filter so the map neither jitters nor drifts, and target-finding navigation with a direction arrow and pulsing ring.
- **Layer Controls & Object Info Cards**: Independently toggle constellation lines, star names, constellation labels, and Messier overlays; press-and-hold to compare against the original photo; tap any object in the picture for a bilingual info card (type / magnitude / distance / background).
- **Rich Astronomical Overlays**:
  - Official 88 modern constellation stick figures aligned with Stellarium v23.4 constellation line data (672 segments) and names;
  - 3,800+ traditional Chinese and Western proper star names (Sirius, Vega, Betelgeuse, Arcturus, Polaris, etc.);
  - 45 prominent Messier deep-sky objects (Andromeda Galaxy M31, Orion Nebula M42, Pleiades M45, etc.) color-coded by astrophysical object type;
  - **Realtime Moon and planet labels** (v1.5.48): the Sun, Moon and planets are computed for the moment encoded in the photo's EXIF timestamp + GPS (JPL approximate Keplerian elements plus a Meeus lunar series, with topocentric parallax correction); the Sun and Moon are drawn at their true apparent diameter, and tapping them shows magnitude, elongation, illuminated fraction and apparent size.
- **Bilingual UI**: One-tap switching between Simplified Chinese and English across the entire app, including constellation, star, and deep-sky object names.
- **Professional Astrophotography Tools**:
  - Camera Pro manual exposure (ISO / shutter control for light pollution and faint star fields) with a bubble level;
  - Automatic field-of-view (FOV) calibration;
  - Direct raw image gallery loading (bypasses OS downsampling to preserve genuine faint star centroids);
  - Dual-layer zoomable viewer (smooth comparison between original photo and annotated overlay);
  - Visual failure diagnostics (star detection density heatmap and shooting guidance);
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
├── docs/                   # Engineering architecture and 48 validation reports (§0.11 ~ §0.58)
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

# Run full unit tests (astronomical math, catalog integrity, 143/143 passing)
./gradlew :app:testDebugUnitTest

# Output path
# app/build/outputs/apk/debug/StarCam-v*-debug.apk
```

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
