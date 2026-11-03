# 验证报告增补：§0.135 F-Droid 上架准备与 GPL-3.0-or-later 许可收敛（v1.5.96）

## 一、背景与目标

按开发者明确决策推进 **F-Droid 开源应用商店上架准备**：
1. **开源许可证升级为 `GPL-3.0-or-later`**：随包原生求解库 `libstellar_solver.so` 静态链接了 `astrometry.net` 0.97 内置的 GSL（`gsl-an/`，导出 1238 个 `gsl_*` 符号，采用 GPL-3.0-or-later），`astrometry.net` 官方许可明确要求合并分发须采用 GPL-3.0 或更高版本；同时本项目依赖的 Jetpack Compose / CameraX / OkHttp 等 Apache-2.0 库亦与 GPL-3.0 兼容（与 GPL-2.0-only 不兼容）。本项目 git 历史唯一作者 `1437nb` 拍板自 v1.5.96 起将项目整体许可证升级为 **GPL-3.0-or-later**。
2. **签名方案选择方案 A**：使用 F-Droid 官方构建服务器签名（无需与 GitHub 渠道二进制逐字节同签名互通）。
3. **12 张内置离线演示照片来源确认**：全部由开发者 `1437nb` 本人实拍、剥离 EXIF 后入库，随项目按 GPL-3.0-or-later / CC BY-SA 4.0 自由分发。

---

## 二、落地改动清单

| 模块 / 路径 | 改动内容 |
| :--- | :--- |
| `LICENSE` | 替换为官方 GNU General Public License v3.0 正文（SHA-256 `3972DC9744F6499F0F9B2DBF76696F2AE7AD8AF9B23DDE66D6AF86C9DFB36986`） |
| `THIRD_PARTY_NOTICES.md` | 新建第三方代码、科学星表、离线 FITS 索引与 12 张作者实拍演示照片的来源、版本与许可证声明 |
| `README.md` / `README_en.md` / `AGENTS.md` / `交接说明.md` | 同步 GPL-3.0-or-later 徽章、许可证章节说明与 `THIRD_PARTY_NOTICES.md` / `fastlane/` 目录树 |
| `code/app/build.gradle.kts` | ① 版本号升至 `versionCode = 116` / `versionName = "1.5.96"` / `updateDesc = "fdroid-readiness"`；<br>② 新增 `dependenciesInfo { includeInApk = false; includeInBundle = false }`，关闭 AGP 默认写入 APK 签名块的 Google 加密依赖元数据块；<br>③ `release` 构建类型关闭 `vcsInfo.include = false` |
| `tools/build_native.py` | 跨平台改造以支持 F-Droid Linux 构建服务器与本机 Windows：<br>① 新增 `--ndk` / `--cmake` / `--ninja` 参数及环境变量 `ANDROID_NDK_HOME` / `ANDROID_SDK_ROOT` / `PATH` 自动探测；<br>② 新增 `safe_extract_tar()` 兼容 Python 3.10+（无 `tarfile.data_filter` 时逐成员校验路径穿越与特殊文件）；<br>③ 保持固定源码 SHA-256 校验、16 KB 页对齐、桥接源码 SHA 指纹与 6 个 JNI 导出符号断言不变 |
| `fastlane/metadata/android/{zh-CN,en-US}/` | 新建完整 Fastlane 商店元数据：`title.txt`、`short_description.txt`、`full_description.txt`、`changelogs/116.txt`、`images/icon.png`（512×512）及 4 张 `images/phoneScreenshots/*.png` |
| `docs/fdroid/com.starcam.astro.yml` | 新建 `fdroiddata` 标准格式构建配方：构建前删除预编译 `libstellar_solver.so`，在 `prebuild` 阶段调用 `python3 ../../tools/build_native.py --fetch --ndk $$NDK$$` 从固定源码编译生成 |

---

## 三、本机验证结果

1. **跨平台 `tools/build_native.py` 实测**：
   - 执行 `python tools/build_native.py --abi arm64-v8a`，从固定 SHA-256 归档全新解压并编译 `libstellar_solver.so`；
   - 产物 SHA-256 `7935d134ac3620264ae76e52c5242b410a3804b7c6b50e3a69f2edb70b5e893b`，与仓库内 `code/app/src/main/jniLibs/arm64-v8a/libstellar_solver.so` 逐字节一致，ELF 16 KB 对齐、桥接源码指纹 `34632f81e631093c9e5dafd239d6a5f62c85f9bf2f7e2241b958835254d02a31` 及 6 个 JNI 导出符号全部通过断言。
2. **JVM 单元测试回归**：
   - 执行 `.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache -PskipPhotoTests=true`；
   - **49 个测试类 / 352 项测试全部通过**（`failures=0, errors=0, skipped=0`）。
3. **Debug APK 组装构建**：
   - 执行 `.\gradlew.bat :app:assembleDebug`，产出 `StarCam-v1.5.96-fdroid-readiness-debug.apk`（23,466,186 字节），`BUILD SUCCESSFUL`。
4. **凭据扫描**：
   - 执行 `python tools/check_secrets.py`，扫描 416 个文件，0 凭据泄露。

---

## 四、关于本机 WSL 安装与后续提交流程

- **本机 WSL 状态说明**：当前终端运行在非管理员权限（`admin: False`），且 Windows 可选功能 `Microsoft-Windows-Subsystem-Linux` 处于未启用状态（`InstallState = 2`），`winget install Microsoft.WSL` 因错误码 `0x80073d28`（需要管理员特权）被系统拦截，无法在无管理员提权的终端内直接静默启用。如需在本机开启 WSL，请以**管理员身份**打开 PowerShell 执行一次 `wsl --install` 并重启系统。
- **F-Droid 正式提交流程（待开发者授权后执行）**：
  1. 开发者下达「推送」指令后，将本地 `main` 分支及 `v1.5.96` tag 推送至 GitHub 公开仓库；
  2. 在 GitLab fork `https://gitlab.com/fdroid/fdroiddata`，将 `docs/fdroid/com.starcam.astro.yml` 复制为 `metadata/com.starcam.astro.yml` 提交 Merge Request。
