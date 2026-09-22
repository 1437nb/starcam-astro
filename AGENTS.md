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

### 凭据管理规范（务必遵守）

**硬性规则**：

1. **任何凭据不得进入 git 跟踪范围**（含历史）。服务器口令、API Key、token 一律走
   环境变量或 `~/.starcam/*.env`（`.gitignore` 已覆盖 `*.env` 等）。
2. **提交前跑 `python tools/check_secrets.py`** —— 已装为 pre-commit 钩子
   （`core.hooksPath=.githooks`，新克隆需执行一次 `git config core.hooksPath .githooks`）。
3. **不要依赖 CI 的默认扫描**：gitleaks 默认规则只认「已知凭据格式」，
   「自定义变量名 = 任意口令」不在其覆盖范围 —— 本项目必须用仓库内 `.gitleaks.toml`。
4. **`tools/gh_api_push.py` 是唯一推送通道**（git push 被墙），它已内置推送前自检；
   若绕过它用别的方式推送，先手动跑 `check_secrets.py`。
5. **发布前审阅 `git diff`**，尤其 `tools/` 这类辅助目录。

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
docs/      78 篇中文文档（编号即 §0.xx 章节；00 总览、01 代码地图、62 篇验证增补
           §0.11~§0.72，另有 7 份 release-notes。⚠️ §0.73~§0.76 **未补独立报告**，
           与 §5 约定不符，目前只记在 `PROGRESS.md`，属待还的文档债）
tools/     32 个 Python 工具脚本（星表生成 / 定标 / 连线 / 评测）
apk/       可安装验证基线（git 忽略，仅本机保留）
indexes/   8 个 FITS 索引副本（服务端 solve-field 定标用，git 忽略）
交接说明.md 项目总纲（公开脱敏版）
AGENTS.md  本文件
```

## 2. 构建与测试（**全部在本机 Windows 完成**，2026-09-17 起）

> **铁律（开发者 2026-09-17 明确要求）：所有编译必须在本机运行。**
> 不再依赖远端构建服务器。下方命令均在本机实测通过。

```bash
cd C:\starword\code
gradle :app:testDebugUnitTest --rerun-tasks   # 全量单测（本机素材齐时 176 项）
gradle :app:assembleDebug                     # 构建 debug APK（约 3 分钟）
```

### 2.0 native 库（`.so`）也本机构建

```bash
cd C:\starword
tools\build_so.bat                            # 重建 libstellar_solver.so（<1 分钟）
tools\build_so.bat <自定义输出路径>            # 可选
```

产物 `code/app/src/main/jniLibs/arm64-v8a/libstellar_solver.so`（约 5.07MB，
6 个 JNI 导出符号）。改动 `code/app/src/main/cpp/astro_bridge.c` 后必须重跑它。

**关键事实（打通本机构建的前提）**：Windows 版 NDK clang **能直接链接 Linux ELF
格式的 `.a` 静态库** —— 不需要 WSL / 虚拟机 / Linux 机器。

依赖放在工程外（不入库，需在换机时重新准备）：

```
C:\dev\astrometry-local\
  ├── src\include\astrometry\   astrometry 0.97 头文件
  ├── lib\…\*.a                 8 个静态库（solver/util×3/catalogs/libkd/qfits-an/gsl-an）
  ├── cross\{lib,include}\      cfitsio 3.47 交叉产物
  └── out\                      构建产物临时目录
C:\dev\android-ndk-r26d\        NDK r26d（Windows 原生 clang 17）
```

- 环境：OpenJDK 17、Android SDK 34、Gradle 8.12.1。
  **本机 SDK 在 `C:\dev\android-sdk`**（非默认位置），`code/local.properties` 写
  `sdk.dir=C:/dev/android-sdk`。该文件为机器相关、不入库。
- ⚠️ **单测必须加 `--rerun-tasks`**，否则 Gradle 可能 `FROM-CACHE` 复用旧结果，等于没跑。
- ⚠️ **测试与打包要分两条命令串行执行**，不要放进同一次 gradle 调用：
  两者都读写 `app/build/tmp/kotlin-classes/debug`，并行会互锁并报
  `dexBuilderDebug` / `mergeDebugJavaResource` 失败（极易误判成环境限制）。
- ⚠️ **不要并行发起多个 gradle 任务**，会抢守护进程互相拖死。
- native 仅 arm64-v8a；x86 模拟器走 JVM 引擎回退。
- `.bat` 构建脚本必须是 **CRLF 行尾**（LF 会被 CMD 逐行解析失败）。
- ⚠️ **AI 工具（带执行沙箱）下跑 gradle 会异常慢，且退出码不可信** —— 2026-09-22 实测：
  沙箱拒绝 Gradle 写 `~/.gradle/daemon/<版本>/registry.bin.lock`，daemon 反复失败重试，
  `--rerun-tasks` 全量单测由 ~1 分钟膨胀到 **42 分钟**。更坑的是**脚本退出码非零**，
  但结果其实全是好的。**判成败只看这两处，不看退出码**：
  ① 输出里的 `BUILD SUCCESSFUL`；② `code/app/build/test-results/testDebugUnitTest/*.xml`
  的 `failures` / `errors` 计数（本次：24 类 / 176 项 / 0 失败）。
  要跑 gradle 就让命令**脱离沙箱执行**，否则时间成本极高。
- 本机 2026-09-17 实测：debug APK 3 分 16 秒、单测 57 秒、`.so` <1 分钟，
  均远快于服务器（后者 1.8GB 内存，R8/全量重编译多次 OOM）。

### 2.1 发版（release）必读 —— 2026-09-17 实测（§0.68）

发版在本机做，要点：

1. **签名密钥必须先在位**：`C:\dev\starcam-release.jks` +
   `C:\dev\starcam-keystore.properties`（后者里的 `storeFile` 指向 jks）。
   三级查找顺序见 `code/app/build.gradle.kts`：环境变量 `STARCAM_KEYSTORE` →
   `-PkeystoreProps` → `~/.starcam/…`。**缺了不会报错** —— `signingConfig` 静默
   变 null，产物是**未签名 APK**（装不上）。**发版后必须验证签名**：
   ```bash
   java -jar C:\dev\android-sdk\build-tools\34.0.0\lib\apksigner.jar verify --print-certs <apk>
   # 期望 CN=StarCam，SHA-256 b04a854f…
   ```
2. **release 构建要显式给堆**：默认堆可能让 R8 `OutOfMemoryError: Java heap space`
   （**Java 堆溢出，不是物理 OOM**）。用：
   ```bash
   gradle -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m" \
          --no-daemon --console=plain :app:assembleRelease
   ```

R8 若报 `Missing class xxx` → **不是 OOM，是缺 keep 规则**。R8 会把建议规则
写到 `app/build/outputs/mapping/release/missing_rules.txt`，照抄进
`code/app/proguard-rules.pro` 即可（§0.68 就是这么修的 Tink/errorprone 注释类）。


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
- ⚠️ **`docs/71-*` 被 `.gitignore` 预留**（见该文件 `:63`，给「安全事件复盘」——
  内容等于泄露细节说明书，绝不入库）。**新写的验证报告不要占 71 号**，
  否则会被**静默排除**、提交时无声消失。docs 编号从 72 继续
  （当前已用到 `73-`）。写新文档后请用 `git status` 确认它确实出现在待提交列表里。
- `.gitattributes` 必须保留：仓库含 `.so` / `.fits` / `.jpg`，缺 `binary` 标记会被换行转换损坏。
- ⚠️ **`git push` 在本机不可用**（`github.com:443` 被阻断，实测 Connection reset /
  timeout；`ssh.github.com:443` 能连通但 publickey 被拒）。两条可用通道：
  1. **拉取**：`git fetch https://ghfast.top/https://github.com/1437nb/starcam-astro.git main:refs/remotes/origin/main --force`
     （ghfast.top 代理只读，**不能 push**）；
  2. **推送**：用 GitHub **Git Data API**（`api.github.com` 可达，凭据从
     `git credential fill` 取）：blobs → tree(base_tree) → commit → PATCH refs/heads/main。
     逐提交重建，先确认远端是本地祖先（fast-forward）。
     **务必用 `git show <sha>:<path>` 读内容**——直接读工作区文件会带 CRLF
     （`core.autocrlf=true`），把整仓库行尾污染成 CRLF（v1.5.55 踩过，已用
     `git add --renormalize` 修回）。
  发布 Release 同样走 API：`POST /releases` + `POST uploads.github.com/.../assets`。
  现成工具：`tools/gh_api_push.py`（`push` / `release` 两个子命令）。
- **更省事的通道（2026-09-17 起）**：经构建服务器中转的 SOCKS 隧道能让
  **原生 git** 直连 GitHub，不必再走 API 逐提交重建：
  ```bash
  python _socks_proxy.py &          # 监听 127.0.0.1:1080（仅回环）
  git -c http.proxy=socks5h://127.0.0.1:1080 push origin main
  ```
  隧道依赖 paramiko + 服务器 SSH（凭据见 `ssh_helper.py`）。
  注意：隧道是前台进程，shell 会话结束即断；用 nohup 起。
- ⚠️ **`.github/workflows/` 下的文件无法通过 API 推送**：写入这类路径要求 token
  具备 `workflow` scope，当前凭据只有 `repo` scope，GitHub 对这类路径一律回
  404（Contents API 与 Git Data API 都是）。改 workflow 只能：
  ① 在 GitHub 网页上直接编辑（推荐），或
  ② 换一个带 `workflow` scope 的 token 再跑 `gh_api_push.py push`。
  脚本会自动跳过这类文件并明确提示，不会静默丢失。

## 5. 文档与交接约定

- **每做一个版本功能，补一篇** `docs/{编号}-验证报告增补-§0.xx-{主题}.md`，并同步更新 README。
- 改动代码需与 `docs/` 保持同步；`交接说明.md` 为总纲，重大变更要更新。
- **进度滚动记录在 `PROGRESS.md`**：每次实质工作（改代码 / 定方案 / 发版 / 合并）结束都要更新
  「最近完成」与「下一步」。这是跨工具接力的关键一环。
- 项目知识以 `docs/` + `交接说明.md` + `AGENTS.md` + `PROGRESS.md` 为准；
  工具私有记忆（`.workbuddy/`、`.codex/` 等）**不入库、换个工具即失效**，
  不要把「只该记一次」的东西写进那里。
- 其他工具的入口文件（如 `CODEBUDDY.md`）只做指针，内容仍以本文件为唯一真源，避免多份不同步。
