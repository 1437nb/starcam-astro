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
- **Real-Time Viewfinder Recognition**: CameraX analysis pipeline performs periodic solving, projecting golden dashed constellation lines and bright star names onto the camera preview in real time.
- **Rich Astronomical Overlays**:
  - Official 88 modern constellation stick figures and names;
  - 3,800+ traditional Chinese and Western proper star names (Sirius, Vega, Betelgeuse, Arcturus, Polaris, etc.);
  - 44 prominent Messier deep-sky objects (Andromeda Galaxy M31, Orion Nebula M42, Pleiades M45, etc.) color-coded by astrophysical object type.
- **Professional Astrophotography Tools**:
  - Direct raw image gallery loading (bypasses OS downsampling to preserve genuine faint star centroids);
  - Dual-layer zoomable viewer (smooth comparison between original photo and annotated overlay);
  - Visual failure diagnostics (star detection density heatmap and shooting guidance);
  - Deep space blue and night-vision red themes (protects dark adaptation in the field).
- **Zero Privacy Leakage**: Fully offline operation. No photos are uploaded to any server. No telemetry, no trackers, no ads.

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
├── docs/                   # Engineering architecture and 36 validation reports (§0.10 ~ §0.43c)
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

# Run full unit tests (astronomical math, catalog integrity, 61/61 passing)
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

1. **Fully Offline**: By default, solving runs entirely on-device using local indexes. Your photos never leave your device.
2. **Online Mode**: Network requests to `nova.astrometry.net` only occur if you explicitly enter an API key in Settings and choose online mode.
3. **Minimal Permissions**: Requests camera and storage read permissions only. No location permission, no background tracking, and no personal data collection.
