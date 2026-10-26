# StarCam / 星空识星

[**简体中文**](README.md) | [**English**](README_en.md)

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![CI](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml/badge.svg)](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)

一款**纯离线优先**的 Android 天文摄影与星空识别 App。拍摄或导入夜空照片，在设备本地盲解天区坐标（RA / Dec / 视场 / 旋转），精确叠加星座连线、恒星专名、梅西耶深空天体与日月行星标注，并提供实时 AR 星图与今日天象指引。

---

## 下载安装

最新版本：**[v1.5.87](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.87)**

| 安装包 | 大小 | 说明 |
| :--- | :--- | :--- |
| **[StarCam-v1.5.87-new-brand-icon-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.87/StarCam-v1.5.87-new-brand-icon-release.apk)** | 15.8 MB | **推荐**，R8 压缩官方签名包（可直接覆盖安装升级） |

- **系统要求**：Android 8.0（API 26）及以上，推荐 **arm64-v8a** 64 位真机（离线 `astrometry.net` 原生引擎仅包含 arm64 库；x86_64 模拟器自动回退至纯 JVM 星表引擎）。
- **近期更新亮点（v1.5.82 ~ v1.5.87）**：
  - **v1.5.87**：全新「镜头取景 + 星座连线 + 十字星芒」Adaptive Icon（含 Android 13+ Material You 单色层）与首页深空蓝/夜视红双主题自适应品牌徽章；
  - **v1.5.86**：结果页新增图层快捷 FilterChip 栏、一键导出 `📐 仅星座连线` 纯净星空图、4 象限防重叠标签边界收敛，以及预览图与全屏放大查看器图层状态双向同步；
  - **v1.5.82 ~ v1.5.85**：首页新增「今日天象卡片」（月相、月龄、五大亮行星视窗与观星建议）；大视场双引擎并发竞速、无锁并发 `MatchContext`、历史记录 WCS 持久化 0ms 即开。
- 历史版本与完整发版日志见 **[GitHub Releases](https://github.com/1437nb/starcam-astro/releases)** 与 [`docs/`](docs/)。

---

## 核心特性

### 1. 三层混合盲解引擎
- **官方 `astrometry.net` 0.97 本地 NDK 引擎**：随包内置 4100 系列全天 FITS 索引，无需联网即可完成高精度天球 WCS 解算；
- **自研 8400+ 分级星表匹配引擎**：结合浅域（$\le 4.0$ 等）/ 深域（$\le 6.5$ 等）分级索引、三角形投票与宽场一对一打分轮，覆盖 **10° 窄场至 70°+ 超广角**，大视场下支持与官方引擎并发竞速；
- **在线 API 兜底（可选）**：支持用户自行配置 `nova.astrometry.net` API Key（采用 `EncryptedSharedPreferences` 加密存储）。

### 2. 星空标注、图层控制与纯净导出
- **全天体图层**：88 星座官方连线（对齐 Stellarium v23.4）、3800+ 中国传统星官与西方恒星专名、44 个梅西耶深空天体，以及基于拍摄时刻 + GPS 实时解算的**太阳、月球与行星位置**（含站心视差修正与真实视直径绘制）；
- **智能排版与交互**：梅西耶天体近邻星名自动抑制（消除 M45/M42 内部文字重叠），画幅边缘星名 4 象限防碰撞安全收敛；点击图中任意天体即可弹出中英双语科普卡片；
- **自由图层与一键导出**：支持单独开关任一图层、长按或进入全屏双指缩放查看器对比原图，并提供 `📐 仅星座连线` 与 `💾 保存当前标注` 双模式导出及最大 9 张照片有界并发批量识别。

### 3. AR 实时星图与找星导航
- **传感器融合零延迟投影**：旋转矢量 + 陀螺仪互补滤波驱动，手持取景平滑不抖动；支持**全天星空模式**（低头可看地平线以下星空与八方位罗盘）；
- **🎯 找星导航**：选择目标亮星、星座或深空天体后，取景框实时引导方向箭头与脉冲光环。

### 4. 天文专业工具与暗适应体验
- **今日天象卡片**：纯 JVM 太阳系历表实时计算当晚月相照度、月龄、金木土火水五大行星观测窗口与深空摄影建议；
- **FITS 与相机 RAW 直读**：自研纯 Kotlin FITS 解码器（支持 BITPIX 8/16/32/±32/±64、WCS 先验提取）与相机 RAW（CR2/NEF/ARW/DNG 等内嵌预览提取），并提供原图相册直读模式（绕过系统相册降采样与 EXIF 脱敏）；
- **双主题与双语切换**：内置「深空蓝（默认）」与「夜视红（天文暗适应）」双主题，支持简体中文 / English 全局即时切换。

---

## 项目结构

```text
.
├── code/                   # Android 工程源码（Kotlin + Jetpack Compose Material 3）
│   └── app/src/
│       ├── main/java/com/starcam/astro/     # 核心天文算法、求解管线与 Compose UI
│       ├── main/assets/indexes/             # astrometry.net 离线 FITS 索引（8 档）
│       ├── main/assets/offline_photos/      # 12 张内置公开星空演示照片
│       ├── main/jniLibs/arm64-v8a/          # 预编译 libstellar_solver.so 原生求解库
│       ├── main/cpp/astro_bridge.c          # JNI 桥接层源码
│       └── test/java/com/starcam/astro/     # 35 个单元测试类与真值回归套件
├── docs/                   # 架构设计与 100+ 篇验证报告（§0.00 ~ §0.121）
├── tools/                  # 星表构建、真值定标、凭据体检与一键发版脚本
└── LICENSE                 # GNU General Public License v2.0
```

---

## 本地构建与参与贡献

仓库已随包提供预编译的 `libstellar_solver.so`，日常修改 Kotlin 逻辑、UI 或运行单元测试**只需 JDK 17 与 Android SDK 34**，无任何工程外依赖：

```bash
git clone https://github.com/1437nb/starcam-astro.git
cd starcam-astro/code
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 1. 构建 Debug APK
./gradlew :app:assembleDebug

# 2. 运行单元测试与 12 张公开演示照片真值回归
./gradlew :app:testDebugUnitTest --rerun-tasks -PskipPhotoTests=true

# 3. 构建 Release APK（R8 压缩）
./gradlew :app:assembleRelease -x lintVitalRelease
```

| 贡献方向 | 所需环境 |
| :--- | :--- |
| 修改 Kotlin / 天文算法 / Compose UI / 文档与多语言 | **JDK 17 + Android SDK 34**（克隆即编） |
| 修改 JNI 桥接 `app/src/main/cpp/astro_bridge.c` | 另需 NDK r26d + `astrometry.net` 0.97 静态库 + `cfitsio` 3.47（详见 [`docs/75`](docs/75-外部贡献者指南与工程外依赖说明.md)） |

---

## 隐私声明

1. **默认完全离线**：星图盲解、天体计算与图像渲染默认全部在设备本地完成，照片不离开手机，无广告、无遥测或第三方追踪 SDK。
2. **在线模式显式受控**：仅当用户在「设置」中主动填写 `astrometry.net` API Key 并开启在线兜底时，才会在本地盲解失败后向 `nova.astrometry.net` 上传当次照片，请求结束后立即清理本地上传缓存。
3. **最小化权限**：相机与存储权限仅用于拍摄与读取星空图像；可选定位权限仅在本地用于传感器辅助寻星与太阳系天体视差计算，绝不后台追踪或随图上传。

---

## 开源许可证与致谢

本项目采用 **[GNU General Public License v2.0](LICENSE)** 开源，谨向以下开源项目与科学星表致谢：

- **[astrometry.net](https://astrometry.net/)**（GPL-2.0）— 盲解核心算法与 FITS 索引体系
- **[StellarSolver](https://github.com/rlancaste/stellarsolver)**（LGPL-2.1 / MIT）— `astrometry.net` 跨平台封装参考
- **[Stellarium](https://stellarium.org/)**（GPL-2.0）— 88 星座连线与中国传统星官文化数据
- **[VizieR V/50 Bright Star Catalogue](https://vizier.cds.unistra.fr/viz-bin/VizieR?-source=V/50)**（Public Domain）— 耶鲁亮星星表（BSC5）数据
- **[Android Jetpack Compose & CameraX](https://developer.android.com/jetpack)**（Apache-2.0）& **[OkHttp](https://square.github.io/okhttp/)**（Apache-2.0）
