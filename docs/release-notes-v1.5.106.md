# StarCam v1.5.106 更新说明

本版聚焦 **nova.astrometry.net 在线底片求解全链路修复、云端广角提速与交互细节完善**，并已在 Android 14 模拟器上完成真实在线求解与星图叠加核验。

## 本版更新亮点（v1.5.106）

- **修复 Nova 在线定标解析与星图对齐**：
  - 修复 `fetchCalibration` 误要求不存在的 `status == "success"` 字段导致所有在线求解必崩的缺陷，改为按真实接口结构校验数值字段并支持浮点 `parity` 及 `width_arcsec/height_arcsec` 尺寸回退；
  - 修正 JPEG/PNG 上传图在 `WcsTransform.fromCalibration` 中的 Y 轴方向与 CD 矩阵符号（严格对齐官方 `util/sip.c` 与 `net/models.py`），真值对照 `job=1493115` 投影误差由上千像素降至 `< 2 px`，实测内置 `#4963` 75.5° 广角照片星图精准对齐；
  - 修复 `errormessage` 服务端报错字段名读取及 `radius`（度 → 角分）60× 单位换算。
- **云端广角求解加速与独立时间预算**：
  - 上传时默认附加 `downsample_factor=2`，有视场先验时自动换算水平宽度 `scale_units=degwidth` 范围（`0.65×~1.45×`）传给 Nova，实测广角照片 `#4963` 在线求解耗时由超时降至 **~20 秒**；
  - 为在线求解提供独立时间窗口（最低 `150s`，高于此值则尊重滑块设置），避免默认 `30s` 本地预算耗尽后直接饿死在线兜底。
- **设置页连通性测试与失败诊断透明化**：
  - 设置页新增「测试连接」按钮（验证 API Key 与服务器连通性并实时反馈）及保存前 HTTPS 校验；
  - 失败页透传具体在线错误原因（如 API Key 无效、网络异常、限流/超时），且「仅在线」失败时不再误报“检出 0 颗星”；修复英文按钮换行裁切与在线结果信息卡标题排版。

## 安装与验证

- **系统要求**：Android 8.0（API 26）及以上，推荐 **arm64-v8a** 设备使用完整离线原生引擎。
- **版本与签名**：`versionCode 126`；官方签名证书（`CN=StarCam`，SHA-256 `b04a854f...`）与 `v1.5.105` 等历史正式版完全一致，可直接覆盖安装升级。
- **回归与安全审计**：常规 JVM 单测 **56 类 / 404 项**（0 失败 / 0 错误，新增本地回环 Mock Nova 全协议端到端单测与真实 `job=1493115` 投影真值校验）；`check_secrets.py`（484 文件 0 命中）、`apksigner` v2 验签、`zipalign -c -p 4`、8 档 FITS 与 12 张演示照逐字节比对、全部 9 个 `.so` 16 KB 页对齐、无 `debuggable`/测试入口/`vcsInfo`/`SDKP` 加密依赖块均已通过验证。

安装包：`StarCam-v1.5.106-online-nova-solver-fix-release.apk`（`13,065,517` 字节，约 `12.46 MiB`）。

SHA-256：

```text
D5C0400E8F3E391F5E84B1071C5602AEBB923CD3385881E6BD06F2643E209786
```

---

## English

StarCam **v1.5.106** (`versionCode 126`) fixes and optimizes the end-to-end **nova.astrometry.net** online plate-solving pipeline:

- **Nova Calibration & WCS Alignment Fixes**: Fixed `fetchCalibration` rejecting valid responses without a `status` field, corrected JPEG/PNG y-down WCS orientation & parity conversion (`< 2 px` against Nova ground truth, verified live on wide-field `#4963`), fixed `errormessage` parsing, and converted `radius` from degrees to arcminutes.
- **Fast Wide-Field Online Solving & Isolated Budget**: Sends `downsample_factor=2` and horizontal `degwidth` scale bounds (`0.65×–1.45×`) when FOV is known (cutting live wide-field `#4963` solve time from timeout to **~20s**), and gives online solving a dedicated budget (minimum `150s`) independent of the local solver budget.
- **Settings Connection Test & Honest Failure Diagnostics**: Added a **Test Connection** button and HTTPS validation in Settings, surfaced exact online error messages without misleading `0 stars detected` diagnostics in Online-Only mode, and polished button/header layout.
