# StarCam v1.5.92 更新说明

批量识别完成后，现在可以逐张打开照片，选择图层并决定是否保存标注图。

## 本版更新

- **批量逐张查看**：成功照片直接复用已有识别结果，支持完整结果页、双指缩放和天体科普卡片；返回后保留批量列表，不重复运行整批识别。
- **手动选择导出**：可保存当前图层的标注图，或只保存星座连线；失败照片可单独打开重试。
- **重要行为变化**：批量识别不再自动将全部标注图保存到系统相册。请在想保存的照片详情中点击导出按钮。
- **导入与等待体验**：统一导入入口，支持后台复制、进度和取消；明确离线识别无需 API Key，识别时显示阶段和已用时间。
- **性能与双语体验**：复用图像预处理、缩略图和标签绘制缓冲，改善中英文界面，并包含纯黑、纯白主题等此前未公开版本的更新。

## 安装与验证

- Android 8.0（API 26）及以上，推荐 arm64-v8a 设备使用完整离线原生引擎。
- versionCode 112；官方签名与历史正式版一致，可覆盖安装升级。
- 44 类 / 323 项常规单测通过，12 张随包公开照片真值回归全部解出；Debug、签名 Release 和 lint 均已本地验证。
- 实体手机上的批量切图、相册导出与旋转操作仍待验收，不能将本机测试等同于真机验证。范围与清单见 [验证报告](https://github.com/1437nb/starcam-astro/blob/v1.5.92/docs/114-验证报告增补-§0.131-批量逐张查看与手动导出v1.5.92.md)。线上检查状态见 [GitHub Actions](https://github.com/1437nb/starcam-astro/actions/workflows/ci.yml)。

安装包：`StarCam-v1.5.92-batch-photo-review-export-release.apk`（16,657,790 字节，约 15.9 MiB）。

SHA-256：

```text
0E66D4036E319E7B75D5EC3E7B438701826FD7A7AA032B1C28B34AA7BD9DD4AC
```

## English

Open each completed batch photo in the full result viewer, reuse its solution, choose layers, and save either the current annotations or constellation lines only. Returning preserves the batch list; failed photos can be retried individually.

**Batch solving no longer exports every photo automatically.** Open the photos you want and save them manually. This release also includes background import progress and cancellation, clearer offline status, solving phases and elapsed time, shared image preprocessing and thumbnail caching, rendering improvements, and bilingual / theme refinements since v1.5.87.

Android 8.0+; arm64-v8a is recommended for the native offline solver. Official signing is unchanged. All 323 standard local tests and 12 public-photo truth checks passed; physical-device interaction still needs validation.
