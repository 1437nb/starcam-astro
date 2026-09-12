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

- **唯一开发目录：`C:\starword`。**
- 历史上本项目有多份副本（`C:\star\StarCam` 等）曾互相分叉，导致换工具就丢进度。
  **已于 2026-09-12 合并为本仓库**，旧副本全部归档到 `C:\star\_archive\`。
- **不要再从别处开发、不要手工同步副本。** 改代码只在这里改。
- 远端：`https://github.com/1437nb/starcam-astro.git`（GPL-2.0），分支 `main`。
- 当前基线：**v1.5.48**（versionCode 68），tag `v1.5.48` 已发布到 GitHub Releases。

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
docs/      57 篇中文文档（编号即 §0.xx 章节；00 总览、01 代码地图、48 篇验证增补）
tools/     24 个 Python 工具脚本（星表生成 / 定标 / 连线 / 评测）
apk/       可安装验证基线（git 忽略，仅本机保留）
indexes/   8 个 FITS 索引副本（服务端 solve-field 定标用，git 忽略）
交接说明.md 项目总纲（公开脱敏版）
AGENTS.md  本文件
```

## 2. 构建与测试（本机 Windows 实测结论）

```bash
cd C:\starword\code
gradle :app:testDebugUnitTest --rerun-tasks   # 全量单测（142 项）
gradle :app:assembleDebug                     # 构建 debug APK
```

- 环境：OpenJDK 17、Android SDK 34、Gradle 8.12.1。
  **本机 SDK 在 `C:\dev\android-sdk`**（非默认位置），`code/local.properties` 写
  `sdk.dir=C:/dev/android-sdk`。该文件为机器相关、不入库。
- ⚠️ **单测必须加 `--rerun-tasks`**，否则 Gradle 可能 `FROM-CACHE` 复用旧结果，等于没跑。
- ⚠️ **测试与打包要分两条命令串行执行**，不要放进同一次 gradle 调用：
  两者都读写 `app/build/tmp/kotlin-classes/debug`，并行会互锁并报
  `dexBuilderDebug` / `mergeDebugJavaResource` 失败（极易误判成环境限制）。
- ⚠️ **不要并行发起多个 gradle 任务**，会抢守护进程互相拖死。
- 一次全量 Kotlin 编译约 11~14 分钟（4 核 8G）；增量约 1~5 分钟。
- native 仅 arm64-v8a；x86 模拟器走 JVM 引擎回退。
- 本机历史上无法完成 release 打包（写 `.dex`/`.jar` 被安全策略拒绝），
  编译与单测正常；正式发版在构建服务器上做（见 `docs/02-远端构建测试环境.md`）。

## 3. 硬性约束（用户要求，不可违反）

1. **APK 命名铁律**：产物文件名必须自动带本版更新内容简述，由 `code/app/build.gradle.kts`
   的 `updateDesc` 驱动，格式 `StarCam-v{versionName}-{更新内容}-{variant}.apk`。
   **发新版必须同步改 `versionCode` / `versionName` / `updateDesc` 三处。**
2. **Release 只按需**：日常迭代**只构建 debug**，绝不自动跑 `assembleRelease`，
   除非开发者明确说「跑 release」。
3. **隐私红线**：`testdata/`（私拍原图、`.gray`）**绝不提交 / 打包 / 分发**。
4. **凭据红线**：任何 API key / 密码不得硬编码进源码或文档；
   文档中服务器一律用 `<build-server-ip>`，SSH 一律用别名（如 `aa`）。
5. **不要触碰项目之外的凭据文件**（密钥 / 密码 / token 类）——不读取、不修改、不提交。
   它们不属于本工程，不要因探索而翻动。
6. **AR / 相机改动**：`ui/camera/CameraScreen.kt` 是超大文件（1500+ 行），
   内部按 `§0.xx` 注释分区；须保持**零每帧分配**（Paint / 数组在 `remember` 里一次性创建）。

## 4. git 规矩

- 提交身份：仓库级 `1437nb <1437nb@users.noreply.github.com>`。
  **不要用真实邮箱提交**（会永久写进公开历史），不要用 `--global` 覆盖。
- ⚠️ **不要改写提交历史**：本环境实测 `git rebase --root` 会**删除整个 `.git` 目录**。
  需要修正时用「重新 `init` + 一次干净提交」或 `--amend`；
  动历史前先 `cp -r .git <仓库外目录>`。
- 提交前红线体检：`testdata/`、`local.properties`、签名密钥、`/apk/`、`**/build/`、
  `*.log`、`.workbuddy/`、`交接说明.internal.md` 一律不得进暂存区。
  **禁止 `git add -f` 绕过 .gitignore。**
- `.gitignore` 的两个锚定陷阱（**已修复，勿改回去**）：
  `indexes/` 会连带排除 `code/app/src/main/assets/indexes/` 的 8 个 FITS 离线索引
  （离线求解必需），必须写成 `/indexes/`；`/apk/` 同理。自查用 `git check-ignore -v <路径>`。
- `.gitattributes` 必须保留：仓库含 `.so` / `.fits` / `.jpg`，缺 `binary` 标记会被换行转换损坏。

## 5. 文档与交接约定

- **每做一个版本功能，补一篇** `docs/{编号}-验证报告增补-§0.xx-{主题}.md`，并同步更新 README。
- 改动代码需与 `docs/` 保持同步；`交接说明.md` 为总纲，重大变更要更新。
- **进度滚动记录在 `PROGRESS.md`**：每次实质工作（改代码 / 定方案 / 发版 / 合并）结束都要更新
  「最近完成」与「下一步」。这是跨工具接力的关键一环。
- 项目知识以 `docs/` + `交接说明.md` + `AGENTS.md` + `PROGRESS.md` 为准；
  工具私有记忆（`.workbuddy/`、`.codex/` 等）**不入库、换个工具即失效**，
  不要把「只该记一次」的东西写进那里。
- 其他工具的入口文件（如 `CODEBUDDY.md`）只做指针，内容仍以本文件为唯一真源，避免多份不同步。
