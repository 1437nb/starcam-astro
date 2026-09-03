# 25 · 本机（Windows）环境说明 —— 2026-08-30 迁移完成

> 原「远端 aa」（Alibaba Cloud Linux 3，2核/1.8GB）的构建+测试能力已于 2026-08-30
> 在本机（Windows Server 2022，2核4线程/8GB）完整还原。本文记录本机路径、用法与差异。

## 1. 工具链安装位置

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `C:\dev\jdk-17.0.20.1+1` | Temurin 17.0.20.1+1 |
| Gradle | `C:\dev\gradle-8.12.1` | 8.12.1 |
| Android SDK | `C:\dev\android-sdk` | platform-34 + build-tools 34.0.0 + platform-tools（adb） |
| cmdline-tools | `C:\dev\android-sdk\cmdline-tools\latest` | 12.0 |
| 构建脚本 | `C:\dev\starcam-build.bat` | 一键构建/测试 |
| 工程副本 | `C:\starcam-bundle\code` | v1.5.3-dev（versionCode 23） |
| NDK | `C:\dev\android-ndk-r26d` | r26d（26.3.11579264），重编 .so 用 |
| Python | `C:\dev\python312` | 3.12.10 + astropy 8.0.1 / numpy 2.5.2 / pillow 12.3.0 |

用户级环境变量已持久化（新开终端即生效）：
`JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_NDK_HOME`、`GRADLE_OPTS`
（-Xmx2g），PATH 已追加 JDK/Gradle/adb/sdkmanager/Python。

## 2. 日常命令

```bat
C:\dev\starcam-build.bat           rem 打 debug APK（产物 app\build\outputs\apk\debug\）
C:\dev\starcam-build.bat test      rem 跑全部单元测试
C:\dev\starcam-build.bat test "com.starcam.astro.LocalMatcherTest"   rem 跑单个类
C:\dev\starcam-build.bat clean     rem 清理
```

**发布节奏约定（2026-09-01）**：日常迭代只出 debug APK（增量 ~15s）。
release（签名+R8，约 23 分钟）**只在需要发布/验证时手动执行**
（`cd C:\starcam-bundle\code && gradle :app:assembleRelease`），
由用户决定时机，不随每次版本更新自动跑。

亦等价于原命令模板（gradle --no-daemon --console=plain :app:assembleDebug）。

## 3. 迁移当日验证结果（2026-08-30/31）

- `:app:assembleDebug` **BUILD SUCCESSFUL**（首次含全量依赖下载 31m25s；
  缓存已就绪，后续增量构建约 1~3 分钟）。
- 产物 `StarCam-v1.5.3-debug.apk`（24.4MB），SHA-256
  `30ec5d86f16189e7e648066892d3bebb2e44d7a93f4fc75f31b187b44d9de6b5`，
  已复制一份到 `C:\starcam-bundle\apk\`。
- 单元测试：**44/44 绿**（AstroMath 6、LocalMatcher 9、RescalePortrait 3、
  SkyEphemeris 7、StarSolver 19）。

## 4. 已知差异与待补事项

- **RealPhotoMatchTest 3 项本机暂缺素材**（其中 2 项有断言会失败、
  1 项为无断言观察台）：该类需要 `PHOTO_DIR` 下的 8 张 `.gray`
  （apod1-5、m44-1910/1975、pleiades），**只在原服务器
  `aa:/opt/realphotos/`，未打进迁移包**（包内仅 diag/t4984.gray）。
  补齐方法：在能 SSH 到 aa 的机器上
  `scp -r aa:/opt/realphotos C:\starcam-bundle\testdata\realphotos`，
  构建脚本检测到该目录后会自动设 `PHOTO_DIR`，即可复现完整 47/47。
- 本机 8GB 内存，GRADLE_OPTS 用 `-Xmx2g`（原 1.8GB 机器的
  -Xmx768m/SerialGC/swap 调优不需要；gradle.properties 里
  `workers.max=1` 保留未动，2 核机器本就合适）。
- **仍与原服务器不对齐的两项**：
  - realphotos 回归素材（见上条），补齐后即可复现完整 47/47；
  - astrometry.net 0.97 x86 编译树（solve-field 服务器端定标对照用，
    原 `/tmp/wide-test/astrometry.net-0.97`）——未移植 Windows；
    定标真值对照可用 `testdata/photos12-wcs/` 内的官方 WCS 代替。
- 原始迁移包归档保留在 `C:\starcam-bundle\starcam-bundle.zip`；
  曾出现的嵌套解压副本 `C:\starcam-bundle\starcam-bundle\`（内容与外层
  完全相同，哈希比对 0 差异）已于 2026-08-31 确认后删除。
- PyPI 走清华镜像（`-i https://pypi.tuna.tsinghua.edu.cn/simple`）。

## 5. 下载加速备忘（国内网络）

GitHub 直连极慢/不稳：JDK 用清华 Adoptium 镜像、Gradle 用腾讯镜像下载。
Maven（google()/mavenCentral()/gradlePluginPortal）本机直连正常，仓库配置未改。
