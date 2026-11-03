# StarCam v1.5.96 更新说明

本版汇集了 `v1.5.93 ~ v1.5.96` 的全部更新：支持照片 / RAW / FITS 统一混合导入、预览或原图尺寸标注导出、全链路取消与大 FITS 内存保护，并完成 F-Droid 开源应用商店上架准备与 GPL-3.0-or-later 许可升级。

## 本版更新亮点（v1.5.93 ~ v1.5.96）

- **F-Droid 上架准备与 GPL-3.0-or-later 许可收敛（v1.5.96）**：
  - 项目开源许可证升级为 **GPL-3.0-or-later**，新增 `THIRD_PARTY_NOTICES.md` 集中声明 `astrometry.net` 0.97、GSL、qfits、CFITSIO 3.47、Tycho-2 4100 系列离线索引、BSC5 星表、Stellarium 连线及 12 张作者实拍演示照片的来源与许可；
  - 关闭 AGP 默认写入 APK 签名块的加密依赖元数据块（`dependenciesInfo`）与 VCS 元数据（`vcsInfo`）；
  - 原生求解器构建脚本 `tools/build_native.py` 支持 Linux / Windows 跨平台与 CMake 3.22~4.x，补齐 `fastlane/metadata/android/` 商店元数据与 `docs/fdroid/com.starcam.astro.yml` 构建配方。
- **稳定性收尾与原生引擎从源码重建（v1.5.95）**：
  - 取消令牌与单调时钟时间预算贯穿协程、HTTP、三角形投票线程与 JNI 层；
  - FITS 改为有界分块三遍扫描与精确分位数计算，128 MiB 堆下可安全预览 6000×4000 大图；
  - 修复批量失败项单独重试后的统计同步、在线缓存缺失时的图片方向恢复及双引擎竞速位图生命周期；
  - 从固定 SHA-256 的 `astrometry.net` 0.97 与 `cfitsio` 3.47 官方源码重建 `libstellar_solver.so`，对齐 keep_logodds 门槛与 WCS CRPIX / Y 轴方向。
- **照片 / RAW / FITS 统一混合导入（v1.5.94）**：
  - 首页「导入照片」直接进入系统文件选择器，支持普通图片（JPG/PNG/WebP）、相机 RAW 与天文 FITS 混合多选（单次最多 9 个文件）；原图相册与兼容模式保留在「从相册选择 / 更多方式」。
- **原图级分辨率标注导出（v1.5.93）**：
  - 单张结果、批量详情与历史记录保存时可选择「预览尺寸」或「原图尺寸」，重新加载原图并在完整分辨率下精确投影绘制（自动处理 EXIF 八方向、内存预算评估与 FITS 边缘采样）。

## 安装与验证

- **系统要求**：Android 8.0（API 26）及以上，推荐 **arm64-v8a** 设备使用完整离线原生引擎。
- **版本与签名**：`versionCode 116`；官方签名证书（`CN=StarCam`，SHA-256 `b04a854f...`）与历史正式版完全一致，可直接覆盖安装升级。
- **回归验证**：59 类 / 373 项完整本机 JVM 测试、Android 9 / 14 模拟器 152 项功能与 UI 测试、C 桥接层 28 条断言及 Windows / WSL2 Linux 双端从源码构建 `.so` 均已通过验证。详见 [§0.135 验证报告](https://github.com/1437nb/starcam-astro/blob/v1.5.96/docs/120-F-Droid%E4%B8%8A%E6%9E%B6%E5%87%86%E5%A4%87-%C2%A70.135.md) 与 [§0.134 验证报告](https://github.com/1437nb/starcam-astro/blob/v1.5.96/docs/119-%E9%AA%8C%E8%AF%81%E6%8A%A5%E5%91%8A%E5%A2%9E%E8%A1%A5-%C2%A70.134-%E7%A8%B3%E5%AE%9A%E6%80%A7%E6%94%B6%E5%B0%BE%E4%B8%8E%E5%8E%9F%E7%94%9F%E6%BA%90%E7%A0%81%E9%87%8D%E5%BB%BAv1.5.95.md)。

安装包：`StarCam-v1.5.96-fdroid-readiness-release.apk`（12,901,673 字节，约 12.3 MiB）。

SHA-256：

```text
44152A718334921791E530F852DA1113BA07FF5AAB1931BE8DF1F9E24D363347
```

---

## English

StarCam **v1.5.96** (`versionCode 116`) rolls up all improvements from `v1.5.93` through `v1.5.96`:

- **F-Droid Readiness & GPL-3.0-or-later License (v1.5.96)**: Upgraded license to `GPL-3.0-or-later` with a complete `THIRD_PARTY_NOTICES.md`, disabled AGP `dependenciesInfo` and `vcsInfo` blocks, made `tools/build_native.py` cross-platform across Linux (CMake 3.22–4.x) and Windows, and added Fastlane store metadata plus the F-Droid build recipe (`docs/fdroid/com.starcam.astro.yml`).
- **Stability & Source-Rebuilt Native Solver (v1.5.95)**: End-to-end cancellation tokens and monotonic time budgets across coroutines, HTTP, triangle voting, and JNI; bounded-memory 3-pass streaming FITS decoding; synchronized single-item batch retry counts; rebuilt `libstellar_solver.so` from SHA-256-pinned `astrometry.net` 0.97 and `cfitsio` 3.47 sources.
- **Unified Photo / RAW / FITS Import (v1.5.94)**: Select standard photos, camera RAW files, and FITS images together from a single import button (up to 9 files per batch).
- **Original-Resolution Annotated Export (v1.5.93)**: Choose between preview size and full original resolution when saving annotated images.
