# StarCam v1.5.105 更新说明

本版汇集了 `v1.5.97 ~ v1.5.105` 的全部更新：涵盖复杂星空离线识别 P1→P4 系统优化与叠加精度微调、宽场前景 `#5057` 30s 超时修复与 8× 三角形查表提速、全应用 UI 交互精修、模块化架构重构、28 项稳定性审计修复，以及离线演示「经典星座 / 全天漫游」双模式自选。

## 本版更新亮点（v1.5.97 ~ v1.5.105）

- **复杂星空离线识别与宽场提速（v1.5.105）**：
  - **P1→P4 离线识别系统优化**：串行调度为原生 `astrometry.net` 预留时间预算与加权盲解分段；修复 `astro_bridge.c` 中 `simplexy` 星表按栅格序截断丢失亮星的根因（补齐 `starxy_sort_by_flux`）并重建 `libstellar_solver.so`；支持校验挂载可选补充 FITS 索引（`files/indexes/extra/`）；新增预算内前景感知天空裁剪（含精确 `crpix` 全图回映）、局部背景扣除与热点抑制；增加独立 Holdout 留出集评估与保守 6 参数仿射 TAN 全图微调，16 图复杂星空 30s 离线基准由 **5/16 提升至 9/16**（0 误判）；
  - **宽场前景 `#5057` 30s 超时修复与查表提速**：修复紧凑主表与大 SEP 备用表的预算倒挂、`matchInternal` 按 `work` 子集复用 `VoteTable` 消除重复投票扫描，并引入 256×256 扁平数组 `flatBuckets` + 切比雪夫同心环精确下界剪枝；冷启动 Android 14 模拟器（含原生 `.so`）上 `#5057` 由 `30.6s` 失败降至 `10.0s` 解出（32 内点，`p50 = 1.93px`）；
  - **离线演示模拟星空双模式自选**：支持切换「经典星座（18 个著名星座不重复轮换或指定单选，叠加轻微构图变化）」与「全天漫游（按立体角全天球均匀随机采样 RA/Dec、0°–360° 相机旋转角及自动/32°/48°/68° 视场档位，并自动过滤空天区）」；
  - **相机界面细节修复**：修复拍照认星页右上角快捷按钮溢出重叠问题并优化切换镜头矢量图标。
- **全应用 UI 与交互精修（v1.5.104）**：
  - 首页新增一键「夜视红」与引擎状态胶囊、`TonightSkyCard` 真实月相球面画布与 5 张 M3 分组卡片化设置页；
  - 相机取景升级磨砂矢量 HUD 胶囊、单手可折叠 Pro 星空摄影三档预设抽屉与支持搜索筛选的找星选择器；
  - 结果页升级自适应比例画布（零像素偏移）、全屏倍率一键复位查看器与 2×2 天球遥测网格（一键复制坐标）；批量识别支持单张完成即点即看，识别历史新增清空二次确认。
- **模块化架构重构与 28 项稳定性修复（v1.5.102 ~ v1.5.103）**：
  - 将核心巨石文件按单一职责拆分为 17 个内聚模块，统一 `SettingsRepository` 跨实例加密存储共享；
  - 修复 28 项审计缺陷（预算/取消与 JNI 异常安全、HTTP 429/503 退避、FITS 32 位精度、Camera2 Pro AE 释放与 AR 姿态校准、极区网格匹配与连通域防溢出等）。
- **复杂星空多级补救链路（v1.5.97 ~ v1.5.101）**：
  - 新增局部背景/噪声与紧凑信噪提星、四边前景天空补救与整图投影校准，以及可信窄场中间星等索引匹配。

## 安装与验证

- **系统要求**：Android 8.0（API 26）及以上，推荐 **arm64-v8a** 设备使用完整离线原生引擎。
- **版本与签名**：`versionCode 125`；官方签名证书（`CN=StarCam`，SHA-256 `b04a854f...`）与 `v1.5.96` 等历史正式版完全一致，可直接覆盖安装升级。
- **回归与安全审计**：CI 同口径常规 JVM 单测 **56 类 / 399 项**（0 失败 / 0 错误，12/12 公开演示图真解）；`check_secrets.py`（482 文件 0 命中）、`apksigner` v2 验签、`zipalign -c -p 4`、8 档 FITS 与 12 张演示照逐字节比对、`libstellar_solver.so` 16 KB 页对齐、无 `debuggable`/测试入口/`vcsInfo`/`SDKP` 加密依赖块均已通过验证。详见 [§0.144 验证报告](https://github.com/1437nb/starcam-astro/blob/v1.5.105/docs/129-%E9%AA%8C%E8%AF%81%E6%8A%A5%E5%91%8A%E5%A2%9E%E8%A1%A5-%C2%A70.144-%E5%A4%8D%E6%9D%82%E6%98%9F%E7%A9%BA%E7%A6%BB%E7%BA%BF%E8%AF%86%E5%88%ABP1%E8%87%B3P4%E7%B3%BB%E7%BB%9F%E4%BC%98%E5%8C%96v1.5.105.md)。

安装包：`StarCam-v1.5.105-offline-complex-sky-recovery-release.apk`（`13,049,129` 字节，约 `12.4 MiB`）。

SHA-256：

```text
D14B20DE6C068C0A5E88995214D3A7F79B703DE733E23723C36B3857A026AA6A
```

---

## English

StarCam **v1.5.105** (`versionCode 125`) rolls up all improvements from `v1.5.97` through `v1.5.105`:

- **Complex-Sky Offline Solving & Wide-Field Speedup (v1.5.105)**: Systematic P1–P4 native budget reservation, `starxy_sort_by_flux` fix in `astro_bridge.c` with rebuilt `.so`, validated optional extra FITS indexes, budgeted sky-region cropping (exact `crpix` remapping) & local skyglow subtraction, and holdout overlay refinement (lifting the 16-photo 30s offline complex-sky benchmark from **5/16 to 9/16** with 0 false solves). Also fixes the `#5057` 30s timeout via compact-primary budget ordering, cached triangle vote tables, and a flat 256×256 concentric-ring lookup (`30s+ fail → 10.0s` on cold Android 14 emulator), fixes camera top-right HUD icon overlap, and adds a dual-mode synthetic sky selector (**Classic Sky** 18 constellations / **All-Sky Roam** uniform spherical sampling & FOV presets).
- **App-Wide UI & Interaction Polish (v1.5.104)**: 1-tap Night Vision Red pill, spherical Canvas Moon-phase disc, cardized Settings, frosted Camera HUD with collapsible Pro presets & searchable Target Picker, adaptive Result viewport with 2×2 telemetry grid, early-open batch inspection, and History clear confirmation.
- **Modular Refactoring & 28 Stability Fixes (v1.5.102 – v1.5.103)**: Decomposed core monoliths into 17 cohesive modules and resolved 28 audited concurrency, JNI, FITS, Camera2/AR, and WCS/EXIF edge-case defects.
- **Multi-Stage Complex-Sky Recovery (v1.5.97 – v1.5.101)**: Local background & compact-SNR star detection, 4-edge foreground sky recovery with whole-frame refitting, and intermediate catalog matching for trusted narrow fields.
