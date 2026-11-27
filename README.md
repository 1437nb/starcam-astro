# StarCam / 星空识星

[**简体中文**](README.md) | [**English**](README_en.md)

[![License: GPL v3+](https://img.shields.io/badge/License-GPL%20v3%2B-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

一款**纯离线优先**的 Android 天文摄影与星空识别 App。拍摄或导入夜空照片，即可在设备本地盲解天区坐标（RA / Dec / 视场 / 旋转），精确叠加星座连线、中西方恒星专名、梅西耶深空天体与实时日月行星标注，并提供传感器驱动的实时 AR 星图与今日天象指引。

---

## 下载安装

最新版本：**[v1.5.105](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.105)**（[更新说明](docs/release-notes-v1.5.105.md) · [全部历史版本](https://github.com/1437nb/starcam-astro/releases)）

| 安装包 | 大小 | SHA-256 |
| :--- | :--- | :--- |
| **[StarCam-v1.5.105-offline-complex-sky-recovery-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.105/StarCam-v1.5.105-offline-complex-sky-recovery-release.apk)** | 12.4 MiB | `D14B20DE6C068C0A5E88995214D3A7F79B703DE733E23723C36B3857A026AA6A` |

- **系统要求**：Android 8.0（API 26）及以上，推荐 **arm64-v8a** 真机（随包内置离线 `astrometry.net` 原生引擎；x86_64 模拟器自动回退至纯 JVM 星表引擎），官方签名包支持直接覆盖安装升级。

---

## 核心特性

### 1. 三层混合盲解引擎
- **本地 `astrometry.net` 0.97 NDK 引擎**：内置 8 档 4100 系列全天离线 FITS 索引，并支持在应用目录挂载额外补充索引，纯离线即可完成高精度天球 WCS 解算；
- **自研 8400+ 分级星表匹配引擎**：结合浅/中/深分级索引、扁平同心环三角形查表、宽场一对一打分轮与前景感知天空补救，覆盖 **10° 窄场至 70°+ 超广角**，大视场下支持与原生引擎并发竞速；
- **可选在线兜底**：支持用户自行配置 `nova.astrometry.net` API Key（通过 `EncryptedSharedPreferences` 加密存储）。

### 2. 星空标注、图层控制与原图级导出
- **全天体图层**：88 星座官方连线（对齐 Stellarium v23.4）、3800+ 中国传统星官与西方恒星专名、44 个梅西耶深空天体，以及基于拍摄时间与 GPS 实时解算的**太阳、月球与五大行星**（含站心视差修正与真实视直径）；
- **智能排版与交互**：梅西耶近邻星名防遮挡、画幅边缘安全收敛；全屏双指缩放查看器支持一键复位与长按对比原图，点击任意天体弹出中英双语科普卡片，结果页支持一键复制天球坐标；
- **灵活导出与批量识别**：自由开关任意图层，支持「预览尺寸 / 原图尺寸」以及「保存当前标注 / 仅星座连线」导出；单次可混合导入最多 9 张普通照片、相机 RAW 与 FITS，支持即完即看、按需手动导出。

### 3. AR 实时星图、Pro 相机与离线演示
- **实时 AR 找星导航**：旋转矢量 + 陀螺仪互补滤波零延迟投影，支持地平线以下全天星空模式，内置支持搜索与分类筛选的找星选择器，实时箭头引导定位亮星、星座、深空与行星；
- **星空摄影 Pro 控制**：内置可折叠单手 Pro 曝光面板（广角银河 / 暗夜深空 / 手持夜拍三档预设）与手势对焦；
- **零门槛离线演示**：无需外出拍星，内置 12 张真实手机星空样张走完整本地盲解链路，并提供**模拟星空双模式自选**（18 个经典星座轮换/单选，或全天球立体角均匀随机漫游与多档视场切换）。

### 4. 天文工具与暗适应体验
- **今日天象卡片**：纯本地历表实时绘制球面月相盘，显示月照度、月龄、金木土火水观测窗口与深空摄影建议；
- **RAW / FITS 与原图直读**：内置纯 Kotlin FITS 解码器与常见相机 RAW 内嵌预览提取，支持绕开系统相册降采样与 EXIF 脱敏的原图直读模式；
- **四主题与中英双语**：内置深空蓝、纯黑、纯白与一键「夜视红」暗适应模式，支持简体中文 / English 即时切换。

---

## 项目结构

```text
.
├── code/                   # Android 工程源码（Kotlin + Jetpack Compose Material 3）
│   └── app/src/
│       ├── main/java/com/starcam/astro/     # 天文算法、求解管线与 Compose UI
│       ├── main/assets/indexes/             # astrometry.net 离线 FITS 索引（8 档）
│       ├── main/assets/offline_photos/      # 12 张内置公开星空演示照片
│       ├── main/jniLibs/arm64-v8a/          # libstellar_solver.so 原生求解库
│       ├── main/cpp/astro_bridge.c          # JNI 桥接层源码
│       └── test/java/com/starcam/astro/     # 单元测试与 12 张公开演示照真值回归
├── docs/                   # 架构设计、发版说明与验证报告
├── tools/                  # 星表构建、真值定标、凭据体检与发版脚本
├── fastlane/metadata/      # F-Droid 商店元数据（中英文简介、更新日志、图标、截图）
├── THIRD_PARTY_NOTICES.md  # 第三方代码、数据与素材来源及许可清单
└── LICENSE                 # GNU General Public License v3.0（GPL-3.0-or-later）
```

---

## 本地构建与参与贡献

仓库已随包提供预编译的 `libstellar_solver.so`，日常开发或运行测试**只需 JDK 17 与 Android SDK 34**：

```bash
git clone https://github.com/1437nb/starcam-astro.git
cd starcam-astro/code
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug                                   # 构建 Debug APK
./gradlew :app:testDebugUnitTest --rerun-tasks -PskipPhotoTests=true  # 运行单测与公开照片真值回归
./gradlew :app:assembleRelease -x lintVitalRelease             # 构建 Release APK（R8 压缩）
```

如需修改 `app/src/main/cpp/astro_bridge.c` 并从源码重建 `.so`，另需 Python 3.10+、NDK r26d、CMake 与 Ninja，运行 `python3 tools/build_native.py --fetch` 即可在 Windows / Linux 上从固定 SHA-256 源码构建（详见 [`docs/120`](docs/120-F-Droid上架准备-§0.135.md)）。

---

## 隐私声明

1. **默认完全离线**：星图盲解、天体历表计算与标注渲染默认全部在设备本地完成，照片不离开手机，无广告、无遥测或第三方追踪 SDK。
2. **在线模式显式受控**：仅当用户在「设置」中主动填写 `astrometry.net` API Key 并开启在线兜底时，才会在本地盲解失败后上传当次照片，请求结束后立即清理上传缓存。
3. **最小化权限**：相机与存储权限仅用于拍摄与读取星空图像；可选定位权限仅在本地用于传感器寻星与太阳系视差计算，绝不后台追踪或上传。

---

## 开源许可证与致谢

本项目采用 **[GNU General Public License v3.0 或更高版本](LICENSE)**（GPL-3.0-or-later）开源，各组件来源与许可清单见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**。谨向以下开源项目与星表致谢：

- **[astrometry.net](https://astrometry.net/)** 0.97（BSD-3-Clause / GSL GPL-3.0+ / qfits GPL-2.0+）— 盲解核心算法与 4100 系列 FITS 索引
- **[CFITSIO](https://heasarc.gsfc.nasa.gov/fitsio/)** 3.47 — FITS 读写
- **[StellarSolver](https://github.com/rlancaste/stellarsolver)**（LGPL-2.1 / MIT）— 跨平台封装参考
- **[Stellarium](https://stellarium.org/)**（GPL-2.0-or-later）— 88 星座连线与中国传统星官文化数据
- **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)**（Public Domain）— 耶鲁亮星星表（BSC5）
- **[Android Jetpack Compose & CameraX](https://developer.android.com/jetpack)** & **[OkHttp](https://github.com/square/okhttp)**（Apache-2.0）
