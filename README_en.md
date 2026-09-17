# StarCam

[**English**](README_en.md) | [**简体中文**](README.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

A pure offline Android application for astrophotography plate-solving and night sky identification. Capture or import night sky photos to perform on-device blind plate-solving (RA, Dec, FOV, orientation, parity), with precise overlays of constellation lines, proper star names, and Messier deep-sky objects.

---

## Download

Latest release: **[v1.5.59](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.59)**

| Package | Size | Notes |
|---|---|---|
| [StarCam-v1.5.59-solve-log-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.59/StarCam-v1.5.59-solve-log-release.apk) | 16.5 MB | **Recommended** — R8-minified signed build |
| [StarCam-v1.5.59-solve-log-debug.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.59/StarCam-v1.5.59-solve-log-debug.apk) | 26.9 MB | Includes debug logging |

All versions: [Releases](https://github.com/1437nb/starcam-astro/releases).

> ⚠️ **The v1.5.55 release APK is unsigned** and cannot be installed — use v1.5.58
> instead. Signing and the release build are fixed in v1.5.57, and the signing
> certificate matches previous versions, so it installs as an in-place upgrade.

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
- **Overlay alignment fix** (v1.5.53): fixed constellation lines drifting away from the
  stars they should connect. The matcher models pixel-to-tangent-plane as a similarity
  transform, but a photograph is a gnomonic projection **about the image centre** —
  the model only holds exactly when the tangent-plane origin sits at that centre.
  It previously used the mean position of the catalog stars, which can be several
  degrees off on wide fields, imprinting a distortion no similarity transform can
  absorb (measured: 6.0 px mean error, 23 px at the edges, 0.92 % scale error).
  The origin is now iterated onto the image centre: error drops to **1.7 px**,
  inliers rise from 15 to 25, and the lines land on their stars.

- **Real-Time Viewfinder Recognition & AR Live Star Map**: CameraX analysis pipeline performs periodic blind solving, and a sensor-driven AR live star map projects the sky onto the viewfinder with zero latency as you move the phone (adjustable FOV, calibratable). Includes an all-sky mode that keeps rendering the lower hemisphere even when the phone points down (with a horizon line and 8-point compass), attitude smoothing with gyro extrapolation plus a complementary filter so the map neither jitters nor drifts, and target-finding navigation with a direction arrow and pulsing ring.
- **Layer Controls & Object Info Cards**: Independently toggle constellation lines, star names, constellation labels, and Messier overlays; press-and-hold to compare against the original photo; tap any object in the picture for a bilingual info card (type / magnitude / distance / background).
- **Rich Astronomical Overlays**:
  - Official 88 modern constellation stick figures aligned with Stellarium v23.4 constellation line data (672 segments) and names;
  - 3,800+ traditional Chinese and Western proper star names (Sirius, Vega, Betelgeuse, Arcturus, Polaris, etc.);
  - 44 prominent Messier deep-sky objects (Andromeda Galaxy M31, Orion Nebula M42, Pleiades M45, etc.) color-coded by astrophysical object type;
  - **Realtime Moon and planet labels** (v1.5.48): the Sun, Moon and planets are computed for the moment encoded in the photo's EXIF timestamp + GPS (JPL approximate Keplerian elements plus a Meeus lunar series, with topocentric parallax correction); the Sun and Moon are drawn at their true apparent diameter, and tapping them shows magnitude, elongation, illuminated fraction and apparent size.
- **Weak-EXIF fallback** (v1.5.49): when a photo carries no GPS, the device's current location is used to estimate the imaged sky region, so the native solver no longer has to start from a full-sky blind search; the Sun/Moon/planet labels and batch export benefit as well. Three hard boundaries: **EXIF GPS always wins and is never overridden**, no fallback without a capture timestamp, and a half-populated GPS pair is treated as missing and completed as a pair. The location source (EXIF vs. current fix) is stated on the solving progress and result screen, together with a "may be inaccurate" note.
- **Portrait photo solving fix** (v1.5.50): corrected the field-of-view estimate for portrait shots (EXIF Orientation=6/8). The estimator previously used the short side of the 35 mm-equivalent frame when computing the long-edge FOV, which shrank the solver's pixel-scale prior and made **portrait photos fail to solve**. The long-edge FOV is now always computed from the 36 mm long side, identical for portrait and landscape.
- **Native star extraction fix** (v1.5.55, **critical**): the native engine extracted **zero stars on every device**. Three compounding C-level defects had been misdiagnosed as "a data race on some ARM64 models" — they reproduce 100 % of the time on x86_64 as well: (1) `simplexy_set_defaults()` memsets the whole struct, but the bridge filled `image/nx/ny` *before* calling it, so those three fields were zeroed; (2) `simplexy_free_contents()` calls `free(s->image)`, yet that pointer comes from JNI `GetFloatArrayElements` (an ART heap pointer that only `Release...Elements` may hand back) — defect (1) nulled the pointer and conveniently masked (2); (3) the success test read `if (rc != 0 || npeaks <= 0)`, inverting the real semantics (`rc=0` means no pixel rose above threshold, i.e. failure), so the branch was always taken, the SEP path never succeeded, and the "SEP-first" three-tier fallback on the Kotlin side never ran. The same release also fixed `maxStars` truncating by scan order (now takes the top k by flux), a silently ignored `thresholdBgMultiple`, a JNI array-length leak, a three-state timeout misjudgement, `pthread_create` failure being reported as a timeout, and an unbounded `join` (now a 5 s grace period plus detach protection).
- **Engineering hardening** (v1.5.56): user-supplied astrometry.net API keys are now stored with `EncryptedSharedPreferences` (previously plaintext; existing values migrate automatically on read); build scripts no longer hard-code local paths (`STARCAM_KEYSTORE` env var → `-PkeystoreProps` → `~/.starcam/`); a **GitHub Actions CI** workflow runs the unit tests plus a gitleaks credential scan; the camera analysis stream switched to `YUV_420_888` reading only the Y plane (eliminating 1.9 M `ByteBuffer.get()` calls per frame); the online client is hardened (HTTPS enforced, exponential backoff, upload de-duplication); debug switches were narrowed to `internal`.
- **Reclaimable index memory** (v1.5.57): the ~11 MB catalog index cache is now handed back under memory pressure, reducing the chance of the app being killed in the background. Solver thread state moved from a **process-wide singleton** to **one instance per solve** (fixing a use-after-free latent in v1.5.55: if an earlier solve leaked a thread on timeout and the current one finished cleanly, the caller would `solver_free` memory still being read); a new `releaseIndexes()` JNI entry point plus an `Application.onTrimMemory` hook frees the indexes on `TRIM_MEMORY_RUNNING_LOW` — deliberately not a more aggressive threshold, so that back-to-back solves do not drop the cache and end up slower. Release is refused while a detached timeout thread may still be running: **rather hold 11 MB than crash**.
- **Solve logs** (v1.5.59): a failed solve now leaves a **reproducible scene** behind.
  Instead of guessing from a screenshot, the log records each engine tier's outcome and
  timing, a failure attribution (star extraction failed / too few stars / plenty of stars
  but no catalog match), the custom matcher's voting- and scoring-round statistics, and the
  **exact pixels the matcher consumed** (gzip, lossless) so the developer can replay your
  photo locally. The failure screen copies the technical detail in one tap; Settings can
  view, clear, or disable it. Local-only, never uploaded; paths keep filenames only.
  Pixel scenes compress to 14.5% (13.8 MB → 2.0 MB), capped at 10 scenes.

- **Wide-field recognition fix** (v1.5.58, **critical**): fixes "the original photo
  will not solve, but raising the contrast in a gallery app makes it work". The cause
  was the built-in native engine's **star-source priority**: it preferred 40 stars from
  the app's own detector and never ran the engine's bundled `simplexy` extractor, yet on
  wide, underexposed photos those 40 are almost all mag ~4 or brighter while the index
  reaches considerably fainter. Controlled test on one photo: `simplexy` with all
  **4,478** sources **solves** with 141 matches, while "brightest 40 only" **fails**.
  Now the engine extractor runs first (app stars remain a fallback, limit raised
  40 → 200); `simplexy` extracts **3,225** stars and wide-field photos solve again.

- **Release build fix** (v1.5.57): v1.5.56 and v1.5.57 could not produce a release package at all (daily work only builds debug, so it went unnoticed). After v1.5.56 introduced encrypted storage, the Tink library referenced `com.google.errorprone.annotations.*` — compile-time annotations not shipped with the runtime dependency — and R8's "Missing classes" check treated them as fatal. Fixed with `-dontwarn` rules; also corrected a silent failure where a missing signing key on the build server produced an **unsigned** APK.
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
├── docs/                   # Engineering architecture and 60 validation reports (§0.11 ~ §0.70)
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
# ephemerides, cross-engine checks — 150/150 passing)
./gradlew :app:testDebugUnitTest

# Output path
# app/build/outputs/apk/debug/StarCam-v*-debug.apk

# Release APK (R8-minified; add -x lintVitalRelease to skip lint on offline machines)
./gradlew :app:assembleRelease -x lintVitalRelease
# app/build/outputs/apk/release/StarCam-v*-release.apk
```

> CI (GitHub Actions) runs 145 of these — it passes `-PskipPhotoTests=true` to skip
> the 5 photo-regression tests that need real captures. With the material present
> locally the count is 150.

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
