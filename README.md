# StarCam / 星空识星

[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

一款纯离线运行的 Android 天文摄影与星空识别 App。拍摄或导入夜空照片，本地盲解天区坐标（RA / Dec / 视场 / 旋转），在照片上精确叠加星座连线、恒星专名与梅西耶深空天体标注。

---

## 下载安装

最新版本 **[v1.5.53](https://github.com/1437nb/starcam-astro/releases/tag/v1.5.53)** —

| 包 | 大小 | 说明 |
|---|---|---|
| [StarCam-v1.5.53-overlay-align-fix-release.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.53/StarCam-v1.5.53-overlay-align-fix-release.apk) | 15.7 MB | **推荐**，R8 压缩签名包 |
| [StarCam-v1.5.53-overlay-align-fix-debug.apk](https://github.com/1437nb/starcam-astro/releases/download/v1.5.53/StarCam-v1.5.53-overlay-align-fix-debug.apk) | 24.8 MB | 含调试日志 |

全部版本见 [Releases](https://github.com/1437nb/starcam-astro/releases)。

**系统要求**：Android 8.0（API 26）及以上，**arm64-v8a** 真机
（离线官方引擎仅提供 arm64 原生库；x86_64 模拟器会回退到 JVM 星表引擎）。

---

## 核心特性

- **三层混合求解引擎**：
  - 官方 `astrometry.net` 0.97 本地 NDK 盲解（带 4100 系列全天索引）；
  - 自研 8400+ 星表三角形匹配器（三角形投票 + **宽场打分轮**，带亮源掩蔽与弱星多候选轮）；
    宽场打分轮（v1.5.51）解决 60°+ 广角照片的识别：对候选三角形逐个拟合、
    一对一统计对齐星数并按对齐率判决，不再依赖易被伪三角形稀释的逐星投票；
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
- **弱 EXIF 照片兜底**（v1.5.49）：照片没有 GPS 时，改用设备当前定位估算拍摄天区，
  官方引擎不必再从全天空盲解起步，日月行星标注与批量导出同样受益。三条硬边界：
  **EXIF 自带定位永远优先**、无拍摄时刻则不兜底、半套 GPS 视为缺失并整对补全；
  位置来源（EXIF / 当前定位）在求解进度与结果页如实标注并提示可能不准。
- **竖拍照片识别修复**（v1.5.50）：修正竖拍（EXIF Orientation=6/8）照片的视场估计——
  此前误用 35mm 等效画幅短边计算长边视场，导致官方引擎的比例尺先验区间偏小，
  **竖拍照片必然解不出**。修复后长边视场恒按 36mm 计算，竖拍与横拍一致。
- **宽场（60°+）识别修复**（v1.5.51）：修复广角星空照片在自研引擎下无法识别的问题。
  宽场照片是 gnomonic 投影，像素距离 ∝ tan θ，而星表索引存的是纯角距，导致真三角形
  的边长比失真（median 0.019）超出严格匹配窗（0.012），真信号被伪三角形投票淹没
  （实测真星 4~11 票、伪星 14~19 票）。新增**宽场打分轮**：候选三角形逐个拟合后
  一对一统计对齐星数，按对齐率判决（真解 0.27、伪解 0.05~0.11，门槛 0.20）。
  实测用户 74° 照片由 UNSOLVED 转为 SOLVED（与 astrometry.net 独立解算差 <0.01°），
  同时 6 张假阳性对照样本全部保持 UNSOLVED。

- **识别耗时修复**（v1.5.52）：v1.5.51 的宽场打分轮在**识别失败**时会多花 15 秒
  （要逐个评分 7 万个候选三角形），叠加官方引擎的盲解两段后，总耗时可超 1 分钟。
  本次为打分轮加入单位向量预算表、3 秒时间预算与高置信提前退出：
  失败路径额外开销降到 2.5~3 秒，离线演示的 12 张照片恢复**单张 1~2 秒解出**，
  识别精度与成功率均无变化。

- **叠加对齐修复**（v1.5.53）：修复星座连线相对星点整体漂移、端点落不到星上的问题。
  匹配模型是「像素 → 切平面」的相似变换，而照片是关于**图像中心**的 gnomonic
  投影 —— 只有切平面原点取在图像中心时才严格成立；原实现取的是星表星平均位置，
  宽场下可偏离画面中心数度，整场因此带上相似变换吸收不掉的畸变（实测平均偏差
  6.0 px、边缘达 23 px、比例尺偏 0.92%）。现在把切平面原点迭代到图像中心，
  偏差降到 **1.7 px**、内点数 15 → 25，连线与星点严格对齐。

- **专业天文工具**：
  - 原图相册直接读取（绕过系统安全中心降采样，保留真实星点）；
  - 双图层全屏缩放查看器（原图 vs 标注图对比）；
  - 失败可视化诊断（星点提取热点图 + 拍摄建议；v1.5.49 起标注图与原图同分辨率，放大不糊）；
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
├── docs/                   # 完整工程文档与 51 份验证增补报告（§0.11 ~ §0.62）
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

# 运行全量单元测试（含星表完整性、天文数学、太阳系历表、跨引擎验证、真实照片回归，150 项全绿）
./gradlew :app:testDebugUnitTest

# 产物位置
# app/build/outputs/apk/debug/StarCam-v*-debug.apk

# 编译 Release APK（R8 压缩；lintVital 在无网络环境可用 -x lintVitalRelease 跳过）
./gradlew :app:assembleRelease -x lintVitalRelease
# app/build/outputs/apk/release/StarCam-v*-release.apk
```

### 真实照片回归（可选）

`app/src/test` 下的 `RealPhotoMatchTest` / `Photo12RegressionTest` 会读取
`.gray` 格式的实拍素材（`int32 宽 + int32 高 + float32 灰度`）。素材不在仓库内
（隐私与体积），跑测试时用环境变量指向本地目录：

```bash
PHOTO_DIR=/path/to/realphotos ./gradlew :app:testDebugUnitTest
```

回归台固化了真值断言：`apod4`（北斗，34° 窄场）、`user-nanning-20260912`
（用户实拍，74° 广角，真值取自 astrometry.net 独立解算）必须解出；
`apod1/2/3/5`、`pleiades`、`m44×2` 作为假阳性对照必须保持 UNSOLVED。

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
