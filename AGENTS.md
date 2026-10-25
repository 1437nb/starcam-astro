# AGENTS.md — StarCam / 星空识星

> **本文件是工程根的统一入口。** 任何 AI 开发工具（Codex / CodeBuddy / WorkBuddy / Claude Code …）
> 打开本目录时都应先读本文件。
>
> - **开工顺序**：本文件 → **`PROGRESS.md`（当前进度：做到哪、下一步是什么）** → 再动手。
> - **收工规矩**：结束前更新 `PROGRESS.md`。
> - 项目全貌见 `交接说明.md`，代码地图见 `docs/01-项目架构与代码地图.md`。
>
> 为什么强调这两步：**换一个工具就等于失忆**。工具自带的记忆（`.workbuddy/`、`.codex/` 等）
> 只对它自己可见且不入库，跨工具接力只能靠**仓库里被 git 跟踪的文档**。

---

## 0. 唯一工作区（重要）

- **当前唯一主力工作区：`C:\starcam`（本地 Windows 11 开发机，i9-14900HX 24核32线程，JDK 17 + Android SDK 34 全套本机构建）。**
  `testdata/`、`apk/`、`indexes/` 为机器相关本地目录，不入库。
- 仓库外的本地资产（发布签名密钥在 `~/.starcam/` 或 `C:\dev\`，astrometry 交叉静态库在 `C:\dev\astrometry-local\`）。
- 远端：`https://github.com/1437nb/starcam-astro.git`（GPL-2.0），主分支 `main`。
- 辅助机器（配置在 `~/.ssh/config`，凭据绝不入库）：
  - **`ec2-hk`**：AWS EC2（香港，2 核 / 3.8GB），用于海外网络跳板、测试回归与 Git 中转推送；
  - **`huawei`**：Linux 服务器，用于辅助 GitHub Release 发布与官方授权管理。

### 凭据管理规范（务必遵守）

**硬性规则**：

1. **任何凭据不得进入 git 跟踪范围**（含历史）。服务器口令、API Key、token 一律走
   环境变量或 `~/.starcam/*.env`（`.gitignore` 已覆盖 `*.env`、`agent.md`、`*.internal.md` 等）。
2. **提交前跑 `python tools/check_secrets.py`** —— 已装为 pre-commit 钩子
   （`core.hooksPath=.githooks`，新克隆需执行一次 `git config core.hooksPath .githooks`）。
3. **不要依赖 CI 的默认扫描**：gitleaks 默认规则只认「已知凭据格式」，
   「自定义变量名 = 任意口令」不在其覆盖范围 —— 本项目必须用仓库内 `.gitleaks.toml`。
4. **发布前审阅 `git diff`**，尤其 `tools/` 与配置类文件。

---

## 1. 项目是什么

**StarCam / 星空识星**（包名 `com.starcam.astro`，用户亦称「爱观天」）——
纯离线优先的 Android 天文摄影与星空识别 App。

- 拍/导入夜空照片 → 本地盲解天区（RA/Dec、视场、旋转、parity）→ 叠加星座连线、
  恒星专名、梅西耶天体、日月行星标注。
- **三层求解引擎**：官方 astrometry.net 0.97（`.so`，arm64-v8a，主引擎）→ 自研 8400+ 星表
  三角投票匹配 → 在线 `nova.astrometry.net` API 兜底（用户自配 Key）。
- **AR 实时星图**：旋转矢量 + 陀螺互补滤波 + 定位，把真实星空投影到取景画面，
  含全天星空模式、地平线罗盘、找星导航。
- 技术栈：Kotlin + Jetpack Compose(Material3)、CameraX 1.3.4、OkHttp；
  天文数学对象为纯 JVM，可单测。
- 坐标系约定：**星表与 WCS 一律 J2000**。新增天体位置必须归算到 J2000，否则叠加整体偏移。

### 目录

```
code/      Android 工程（app/src 为全部源码；assets/indexes 为 8 个 FITS 离线索引，
           随包必需；jniLibs/arm64-v8a 为求解引擎 .so）
docs/      100+ 篇中文文档（编号即 §0.xx 章节；00 总览、01 代码地图，已演进至 §0.120，
           含 10 份 release-notes、代码审查清单 72- 等）
tools/     35+ 个 Python/脚本工具（星表生成 / 定标 / 连线 / 凭据体检 / 发版自动化）
apk/       可安装验证基线（git 忽略，仅本机保留）
indexes/   8 个 FITS 索引副本（服务端 solve-field 定标用，git 忽略）
交接说明.md 项目总纲（公开脱敏版）
AGENTS.md  本文件
```

---

## 2. 构建与测试

> **铁律（开发者 2026-09-17 明确要求）：所有编译优先在本机运行。**
> 本机拥有高配置环境（i9-14900HX 24核32线程），Kotlin 编译、单元测试与打包均在本机高速完成。

### 2.0 本机构建命令

在 `C:\starcam\code` 下执行：

```powershell
cd C:\starcam\code
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks -PskipPhotoTests=true   # 全量单测（35 类 / 276 项全绿，约 2.5 分钟）
.\gradlew.bat :app:assembleDebug                                          # 构建 debug APK（约 2~3 分钟）
```

- **环境**：OpenJDK 17（`C:\dev\jdk-17`）、Android SDK 34（`C:\dev\android-sdk`，`code/local.properties` 写 `sdk.dir=C:/dev/android-sdk`，该文件机器相关不入库）、Gradle 8.12.1。
- **Shell 环境**：本机终端命令与脚本执行**严禁使用 Windows PowerShell 5 (`powershell.exe`)**，一律走 **PowerShell 7 (`pwsh`)**。
- ⚠️ **单测必须加 `--rerun-tasks`**，否则 Gradle 可能 `FROM-CACHE` 复用旧结果，等于没跑。
- ⚠️ **测试与打包要分两条命令串行执行**，不要放进同一次 gradle 调用：两者读写同一 class 缓存，并行会互锁。
- ⚠️ **判成败看两处**：① 输出里的 `BUILD SUCCESSFUL`；② `code/app/build/test-results/testDebugUnitTest/*.xml` 的 `failures="0" errors="0"`。
- native 仅 arm64-v8a；x86 模拟器走 JVM 引擎回退。

### 2.1 native 库（`.so`）本机构建

```powershell
cd C:\starcam
tools\build_so.bat                            # 重建 libstellar_solver.so（<1 分钟）
tools\build_so.bat <自定义输出路径>            # 可选
```

产物 `code/app/src/main/jniLibs/arm64-v8a/libstellar_solver.so`（约 5.07MB，6 个 JNI 导出符号）。改动 `code/app/src/main/cpp/astro_bridge.c` 后必须重跑它。

依赖放在工程外（不入库）：
```
C:\dev\astrometry-local\
  ├── src\include\astrometry\   astrometry 0.97 头文件
  ├── lib\…\*.a                 8 个静态库（solver/util×3/catalogs/libkd/qfits-an/gsl-an）
  ├── cross\{lib,include}\      cfitsio 3.47 交叉产物
  └── out\                      构建产物临时目录
C:\dev\android-ndk-r26d\        NDK r26d（Windows 原生 clang 17）
```

### 2.2 发版（release）必读

> **优先用 `tools/release.py`（§0.80）** —— 它把「改版本号三处 → 凭据预检 → 构建 → 验签 + 与历史包比对同签名 → 归档 + SHA-256」串成一条命令：
>
> ```powershell
> python tools/release.py --version 1.5.86 --desc fix-xxx            # 全流程
> python tools/release.py --version 1.5.86 --desc fix-xxx --dry-run  # 只演算，不改文件不构建
> python tools/release.py --version 1.5.86 --desc fix-xxx --skip-build  # 只改版本号
> ```

发版要点：
1. **签名密钥在位**：`~/.starcam/starcam-keystore.properties` + `~/.starcam/starcam-release.jks`（或 `C:\dev\`）。`code/app/build.gradle.kts` 按环境变量 `STARCAM_KEYSTORE` → `-PkeystoreProps` → `~/.starcam/` 三级查找。发版后验证签名：
   ```powershell
   java -jar C:\dev\android-sdk\build-tools\34.0.0\lib\apksigner.jar verify --print-certs <apk>
   # 期望 CN=StarCam，SHA-256 b04a854f…
   ```
2. **release 构建显式给堆**（防止 R8 堆溢出）：
   ```powershell
   .\gradlew.bat -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m" --no-daemon --console=plain :app:assembleRelease
   ```

---

## 3. 硬性约束（用户要求，不可违反）

1. **APK 命名铁律**：产物文件名必须自动带本版更新内容简述，由 `code/app/build.gradle.kts`
   的 `updateDesc` 驱动，格式 `StarCam-v{versionName}-{更新内容}-{variant}.apk`。
   **发新版必须同步改 `versionCode` / `versionName` / `updateDesc` 三处。**
2. **Release 只按需**：日常迭代**只构建 debug**，绝不自动跑 `assembleRelease`，
   除非开发者明确说「跑 release」。
3. **隐私红线**：`testdata/`（私拍原图、`.gray`）**绝不提交 / 打包 / 分发**。
4. **凭据红线**：任何 API key / 密码不得硬编码进源码或文档；
   文档中服务器一律用 `<build-server-ip>`，SSH 一律用别名（如 `ec2-hk`、`huawei`）。
5. **不要触碰项目之外的凭据文件**（密钥 / 密码 / token 类）——不读取、不修改、不提交。
   它们不属于本工程，不要因探索而翻动。
6. **AR / 相机改动**：`ui/camera/CameraScreen.kt` 是超大文件，内部按 `§0.xx` 注释分区；
   须保持**零每帧分配**（Paint / 数组在 `remember` 里一次性创建）。

---

## 4. git 规矩与推送通道

- **提交身份**：仓库级 `1437nb <1437nb@users.noreply.github.com>`。
  **不要用真实邮箱提交**（会永久写进公开历史），不要用 `--global` 覆盖。
- ⚠️ **不要改写历史**：动历史前先备份 `.git` 目录。
- **提交前红线体检**：`testdata/`、`local.properties`、签名密钥、`/apk/`、`**/build/`、`*.log`、`agent.md`、`*.internal.md` 一律不得进暂存区。**禁止 `git add -f` 绕过 .gitignore。**
- **路径排除规则**：`indexes/` 与 `apk/` 在 `.gitignore` 必须带根斜杠 `/indexes/` 与 `/apk/`，防止误排除 `code/app/src/main/assets/indexes/` 中的 8 个随包离线 FITS 索引。
- ⚠️ **`docs/71-*` 被 `.gitignore` 预留**：新写的验证报告不要占 71 号，新文档从 72 往后编号（目前已用到 101+）。
- `.gitattributes` 必须保留：仓库含 `.so` / `.fits` / `.jpg` 二进制资产，文本统一 `* text=auto`。

### 推送通道说明（重要）

在本机 Windows 环境下，直接 `git push` 直连 `github.com:443` 常被 GFW 阻断（Connection reset），且无桌面交互凭据。以下为验证通过的稳定推送方式：

#### 推荐通道：通过香港验证机（`ec2-hk`）原生中转推送
本机已配置 Windows OpenSSH（`core.sshCommand = "C:/Windows/System32/OpenSSH/ssh.exe"`），向 `ec2-hk` 推送毫秒级完成，再由 `ec2-hk` 直连 GitHub：
```powershell
# 1. 本地提交推送到 ec2-hk
git push ec2-hk:~/starword main
git push ec2-hk:~/starword <tag>       # 如有新 tag

# 2. 借助 huawei 的 gh 授权，在 ec2-hk 上直推 GitHub
$tok = (ssh huawei "/opt/gh_2.102.0_linux_amd64/bin/gh auth token").Trim()
ssh ec2-hk "cd ~/starword && git push https://1437nb:$tok@github.com/1437nb/starcam-astro.git main"
ssh ec2-hk "cd ~/starword && git push https://1437nb:$tok@github.com/1437nb/starcam-astro.git <tag>"
```

#### 备用通道：Git Data API 推送
```powershell
python tools/gh_api_push.py push
```
（`tools/gh_api_push.py` 已内置多级凭据探测，自动调用 `huawei` 的 `gh` 凭据授权逐提交重建）。

#### Release 发布
Release 及 APK 附件上传由 `huawei` 的 `gh` CLI 完成：
```powershell
# 将本地 Release APK 复制到 huawei /tmp 后执行发布
scp apk/<release-apk> apk/<sha256> docs/<release-notes>.md huawei:/tmp/
ssh huawei "/opt/gh_2.102.0_linux_amd64/bin/gh release create <tag> /tmp/<release-apk> /tmp/<sha256> -t '<title>' -F /tmp/<release-notes>.md -R 1437nb/starcam-astro"
ssh huawei "rm -f /tmp/StarCam-* /tmp/<release-notes>.md"   # 及时清理
```

---

## 5. 文档与交接约定

- **每做一个版本功能，补一篇** `docs/{编号}-验证报告增补-§0.xx-{主题}.md`，并同步更新 README。
- 改动代码需与 `docs/` 保持同步；`交接说明.md` 为总纲，重大变更要更新。
- **进度滚动记录在 `PROGRESS.md`**：每次实质工作（改代码 / 定方案 / 发版 / 合并）结束都要更新「最近完成」与「下一步」。这是跨工具接力的关键一环。
- 项目知识以 `docs/` + `交接说明.md` + `AGENTS.md` + `PROGRESS.md` 为准；工具私有记忆（`.workbuddy/`、`.codex/` 等）**不入库、换个工具即失效**。
- 其他工具入口文件（如 `CODEBUDDY.md`）只做指针，内容以本文件为唯一真源。
