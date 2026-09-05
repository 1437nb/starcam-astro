# StarCam / 星空识星

[**简体中文**](README.md) | [**English**](README_en.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

一款纯离线运行的 Android 天文摄影与星空识别 App。拍摄或导入夜空照片，本地盲解天区坐标（RA / Dec / 视场 / 旋转），在照片上精确叠加星座连线、恒星专名与梅西耶深空天体标注。

---

## 核心特性

- **三层混合求解引擎**：
  - 官方 `astrometry.net` 0.97 本地 NDK 盲解（带 4100 系列全天索引）；
  - 自研 8400+ 星表三角形投票匹配器（带亮源掩蔽、弱星多候选轮，广角照片秒级求解）；
  - 可选在线 `nova.astrometry.net` API 兜底（需用户自行配置 API Key）。
- **实时取景认星与 AR 实时星图**：CameraX 分析流周期盲解；并支持传感器驱动的 AR 实时星图——手机转到哪，星图零延迟跟到哪（可调 FOV、可校准）。
- **图层控制与天体科普卡片**：星座连线、恒星名、星座名、梅西耶标注四类图层独立开关；支持按住看原图对比；点击画面中的天体弹出中英双语科普卡（类型 / 视星等 / 距离 / 天文背景）。
- **丰富的星空标注**：
  - 88 星座官方连线与中英文名称；
  - 3800+ 中国传统星官与西方恒星专名（天狼、织女一、参宿四、大角、北极星等）；
  - 45 个常见梅西耶深空天体（M31 仙女座星系、M42 猎户星云、M45 昴星团等），按类型区分颜色标注。
- **中英双语界面**：全应用一键切换简体中文 / English，星座、恒星、深空天体名称与全部 UI 同步本地化。
- **专业天文工具**：
  - 原图相册直接读取（绕过系统安全中心降采样，保留真实星点）；
  - 双图层全屏缩放查看器（原图 vs 标注图对比）；
  - 失败可视化诊断（星点提取热点图 + 拍摄建议）；
  - 深空蓝与夜视红主题（暗适应保护）。
- **零隐私泄露**：完全离线运行，不上传任何照片，无广告、无跟踪器。

---

## 项目结构

```
.
├── code/                   # Android 应用工程源码
│   ├── app/                # 主模块（Kotlin + Jetpack Compose）
│   │   ├── src/main/assets/indexes/         # astrometry.net 离线 FITS 索引
│   │   ├── src/main/assets/offline_photos/  # 离线演示测试素材（12 张经典星空）
│   │   └── src/main/jniLibs/arm64-v8a/      # libstellar_solver.so 预编译引擎
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── docs/                   # 完整工程文档与 40 份验证增补报告（§0.10 ~ §0.49c）
├── tools/                  # Python 星表生成器与离线工具集
├── LICENSE                 # GNU General Public License v2.0
├── README.md               # 中文说明文档
├── README_en.md            # 英文说明文档
└── .gitignore
```

---

## 本地构建

### 环境要求

- **JDK**：OpenJDK 17
- **Android SDK**：API 34（编译与目标 SDK），最低支持 Android 8.0（API 26）
- **Gradle**：8.12+
- **架构**：当前离线引擎仅提供 `arm64-v8a` 架构 native 库，需在 64 位真机运行（x86_64 模拟器支持 JVM 星表引擎，但官方引擎会被优雅回退）。

### 命令行编译

```bash
cd code

# 编译 Debug APK
./gradlew :app:assembleDebug

# 运行全量单元测试（含星表完整性、天文数学、跨引擎验证，80 项全绿）
./gradlew :app:testDebugUnitTest

# 产物位置
# app/build/outputs/apk/debug/StarCam-v*-debug.apk
```

---

## 开源许可证与版权致谢

本项目整体采用 **[GNU General Public License v2.0](LICENSE)** 开源。

本项目使用了以下开源项目与公开数据，谨致谢意：

1. **[astrometry.net](https://astrometry.net/)**（GPL-2.0）
   - 本地盲解核心算法与 FITS 索引体系源自 astrometry.net。
2. **[StellarSolver](https://github.com/rlancaste/stellarsolver)**（LGPL-2.1 / MIT）
   - 提供 astrometry.net 引擎的跨平台 C++ 封装。
3. **[Stellarium](https://stellarium.org/)**（GPL-2.0）
   - 中国传统星官与恒星中文名称数据源自 Stellarium 星空文化数据集。
4. **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)**（公有领域）
   - 补充星表数据源自 Yale Bright Star Catalog（BSC5）。
5. **[Android Jetpack & CameraX](https://developer.android.com/jetpack)**（Apache-2.0）
   - 现代 Android UI 与相机控制框架。
6. **[OkHttp](https://square.github.io/okhttp/)**（Apache-2.0）
   - 在线 API 通信网络库。

---

## 隐私声明

1. **完全离线**：默认使用本地引擎与离线索引求解，识别全流程在设备本地完成，照片不离开手机。
2. **在线模式**：仅在用户显式在「设置」中输入 astrometry.net API Key 并选择在线模式时，才会向 `nova.astrometry.net` 发起网络请求。
3. **权限最小化**：仅申请相机与照片读取权限；不申请定位权限、不采集任何个人信息。
