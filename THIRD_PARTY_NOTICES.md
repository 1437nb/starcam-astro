# 第三方代码、数据与素材声明 / Third-Party Notices

本文件集中列明 **StarCam / 星空识星**（`com.starcam.astro`）随包或构建时使用的全部第三方源码、科学星表、离线索引与内置演示素材的来源、版本与开源许可证，供用户、贡献者及 F-Droid 等开源应用商店审核查证。

项目自身源码自 **v1.5.96** 起采用 **[GNU General Public License v3.0 or later](LICENSE)**（`GPL-3.0-or-later`）发布（因原生求解库静态链接了 `astrometry.net` 内置的 GSL）。

---

## 1. 原生求解引擎（`libstellar_solver.so`）

由 `tools/build_native.py` 从固定 SHA-256 的上游源码归档与本仓库 JNI 桥接源码 `code/app/src/main/cpp/astro_bridge.c` 编译生成：

| 组件 | 版本 | 上游地址與固定归档 SHA-256 | 许可证 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **astrometry.net** | 0.97 | `https://github.com/dstndstn/astrometry.net` (`refs/tags/0.97`)<br>`75a36bb0d0be827c86bedac6bcbf78f110dc19229bab1604eea403d3a0368896` | BSD-3-Clause（主代码），合并分发遵循 **GPL-3.0-or-later** | 盲解核心引擎（`solver/`、`util/`、`libkd/`、`catalogs/`） |
| **GNU Scientific Library (`gsl-an`)** | vendored in astrometry.net 0.97 | 随 `astrometry-0.97.tar.gz` 内置 (`gsl-an/`) | **GPL-3.0-or-later** | 矩阵运算与最小二乘拟合（决定整体合并作品须以 GPL-3.0+ 分发） |
| **qfits (`qfits-an`)** | vendored in astrometry.net 0.97 | 随 `astrometry-0.97.tar.gz` 内置 (`qfits-an/`) | **GPL-2.0-or-later** | ESO 轻量级 FITS 读写库 |
| **CFITSIO** | 3.47 | `https://heasarc.gsfc.nasa.gov/fitsio/`<br>`418516f10ee1e0f1b520926eeca6b77ce639bed88804c7c545e74f26b3edf4ef` | NASA / HEASARC Permissive License（类 BSD 宽松许可） | 标准 FITS 图像与表读写库 |
| **StellarSolver（架构参考）** | — | `https://github.com/rlancaste/stellarsolver` | LGPL-2.1-or-later / MIT | 仅参考跨平台调用思路，未包含其源码 |

---

## 2. 随包离线天体数据与科学星表

| 数据资产 / 源码位置 | 上游来源 | 许可证 | 说明 |
| :--- | :--- | :--- | :--- |
| **离线 FITS 索引（8 档）**<br>`code/app/src/main/assets/indexes/index-4112.fits` ~ `index-4119.fits` | `http://data.astrometry.net/4100/`（基于 ESA Tycho-2 星表构建） | 与 `astrometry.net` 同源公开分发（Tycho-2 科学数据为公开科学域） | 覆盖约 2° ~ 60°+ 视场的四星（quad）几何哈希索引 |
| **内置亮星星表（8400+ 颗，J2000）**<br>`StarCatalogData.kt` / `DeepStarCatalog.kt`（由 `tools/gen_catalog_*.py` 生成） | **VizieR V/50 Yale Bright Star Catalogue (BSC5)** 及 ESA **Hipparcos (`I/239`)** | Public Domain / 公开科学星表数据 | 赤经、赤纬、视星等与 HIP 编号 |
| **88 星座连线与中国传统星官文化**<br>`Constellations.kt` / `ConstellationLines.kt` / `ChineseStarNames.kt`（由 `tools/gen_lines_stellarium.py` 生成） | **Stellarium** v23.4 (`https://stellarium.org/`) | **GPL-2.0-or-later** | 西方现代 88 星座折线拓扑与中国传统星官中文名 |
| **梅西耶深空天体表（44 项）**<br>`MessierCatalog.kt` | Charles Messier 经典天体表与公开天体测量数据（J2000） | Public Domain | 星团、星云与星系坐标、视大小与星等 |

---

## 3. 内置离线演示照片（12 张）

位于 `code/app/src/main/assets/offline_photos/photo4963.jpg` ~ `photo5092.jpg`：

- **来源与作者**：全部由本项目作者（`1437nb`）本人使用手机实拍的真实夜空照片。
- **隐私脱敏**：入库前已剥离全部 EXIF 定位与设备序列号信息，并降采样至长边 2200px。
- **许可证**：随本项目以 **GPL-3.0-or-later**（或 **CC BY-SA 4.0** 双重许可）自由分发，供离线功能演示与 CI 真值回归测试（`OfflinePhotosTruthTest`）使用。

---

## 4. Android / JVM 依赖库（Maven Central & Google Maven）

全部由 Gradle 从公开仓库拉取，不含任何闭源专有 SDK、Google Play Services（GMS）、广告、遥测或第三方追踪框架：

| 依赖坐标 | 许可证 | 用途 |
| :--- | :--- | :--- |
| `androidx.compose.*` (`compose-bom:2024.10.01`) | Apache-2.0 | Material 3 声明式 UI 与动效 |
| `androidx.core:core-ktx:1.13.1`、`androidx.activity:activity-compose:1.9.2`、`androidx.lifecycle:*:2.8.6` | Apache-2.0 | AndroidX 核心组件与生命周期 |
| `androidx.camera:camera-* :1.3.4`、`androidx.concurrent:concurrent-futures-ktx:1.1.0` | Apache-2.0 | CameraX 相机取景与拍摄 |
| `androidx.exifinterface:exifinterface:1.3.7` | Apache-2.0 | 本地读取照片焦距、曝光、时间等 EXIF 先验 |
| `androidx.security:security-crypto:1.1.0-alpha06`（基于 Google Tink） | Apache-2.0 | 用户自配 `astrometry.net` API Key 的本地加密存储 |
| `com.squareup.okhttp3:okhttp:4.12.0` | Apache-2.0 | 可选在线 `nova.astrometry.net` 兜底 HTTP 客户端 |
| `junit:junit:4.13.2`、`org.json:json:20240303`（仅 `testImplementation`） | EPL-1.0 / Public Domain (JSON) | 仅用于本地与 CI 的 JVM 单元测试，不进入 APK |
