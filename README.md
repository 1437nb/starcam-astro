# StarCam / 星空识星

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

一款纯离线运行的 Android 天文摄影与星空识别 App。拍摄或导入夜空照片，本地盲解天区坐标（RA / Dec / 视场 / 旋转），在照片上精确叠加星座连线、恒星专名与梅西耶深空天体标注。

---

## 核心特性

- **三层混合求解引擎**：
  - 官方 `astrometry.net` 0.97 本地 NDK 盲解（带 4100 系列全天索引）；
  - 自研 8400+ 星表三角形投票匹配器（带亮源掩蔽、弱星多候选轮，广角照片秒级求解）；
  - 可选在线 `nova.astrometry.net` API 兜底（需用户自行配置 API Key）。
- **实时取景认星**：CameraX 分析流周期识别，在相机取景框上实时叠加星座连线与亮星名。
- **AR 实时星图**（v1.5.40 ~ v1.5.47）：由设备方向传感器驱动，星图零延迟跟随手机转动；
  支持全天星空模式（手机朝下也能看到脚下半球与地平线罗盘）、姿态平滑预测与互补滤波
  （手持取景不抖不漂）、🎯 找星导航（方向箭头 + 脉冲光环），点击画面天体弹出中英双语科普卡。
- **丰富的星空标注**：
  - 88 星座官方连线与中文名称；
  - 3800+ 中国传统星官与西方恒星专名（天狼、织女一、参宿四、大角、北极星等）；
  - 44 个常见梅西耶深空天体（M31 仙女座星系、M42 猎户星云、M45 昴星团等），按类型区分颜色标注；
  - **月亮与八大行星实时标注**（v1.5.48）：按照片 EXIF 的拍摄时间 + GPS 解算该瞬间的
    日月行星位置（JPL 近似根数 + Meeus 月球级数，含站心视差修正），日月按真实视直径绘制，
    点击可查看视星等 / 距角 / 被照亮比例 / 视直径。
- **专业天文工具**：
  - 原图相册直接读取（绕过系统安全中心降采样，保留真实星点）；
  - 双图层全屏缩放查看器（原图 vs 标注图对比）；
  - 失败可视化诊断（星点提取热点图 + 拍摄建议）；
  - 深空蓝与夜视红主题（暗适应保护）。
- **隐私优先**：默认完全离线运行；仅在用户明确启用在线求解时才上传该次照片。无广告、无跟踪器。

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
├── docs/                   # 完整工程文档与 48 份验证增补报告（§0.11 ~ §0.58）
├── tools/                  # Python 星表生成器与离线工具集
├── LICENSE                 # GNU General Public License v2.0
├── README.md
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

# 运行全量单元测试（含星表完整性、天文数学、太阳系历表、跨引擎验证，143 项全绿）
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

1. **默认完全离线**：默认使用本地引擎与离线索引求解，识别全流程在设备本地完成，照片不离开手机。
2. **在线模式需明确选择**：仅在用户显式在「设置」中输入 astrometry.net API Key 并选择在线模式时，才会向 `nova.astrometry.net` 上传该次照片；上传副本在请求结束后立即从本地缓存删除。
3. **最小化权限与本地处理**：申请相机、照片读取，以及用于传感器辅助定标的可选定位权限。定位仅在相机页面使用于本地计算，不做后台跟踪，也不会随在线上传的 JPEG 发送。应用数据不参与系统备份。
