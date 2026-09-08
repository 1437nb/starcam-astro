package com.starcam.astro.ui

import com.starcam.astro.ui.theme.LocaleState

/**
 * 全局 UI 中英文双语字典（§0.45）
 * 底层基于响应式单例 [LocaleState.isEnglish]，语言切换时各界面自动重组刷新。
 */
object I18n {

    val isEn: Boolean get() = LocaleState.isEnglish

    // ==================== 通用 (Common) ====================
    val appName: String get() = if (isEn) "StarCam" else "星空识星"
    val appDesc: String get() = if (isEn) "Photo Plate Solving · Constellations & Bright Stars" else "拍照认星 · 识别照片中的星座与亮星"
    val back: String get() = if (isEn) "Back" else "返回"
    val cancel: String get() = if (isEn) "Cancel" else "取消"
    val delete: String get() = if (isEn) "Delete" else "删除"
    val save: String get() = if (isEn) "Save" else "保存"
    val savedToast: String get() = if (isEn) "✓ Saved" else "✓ 已保存"
    val retry: String get() = if (isEn) "Retry" else "重试"
    val original: String get() = if (isEn) "Original" else "原图"
    val annotation: String get() = if (isEn) "Annotation" else "标注"
    val tapToZoom: String get() = if (isEn) "🔍 Tap image to zoom in" else "🔍 点击图片放大查看"
    val grantPermission: String get() = if (isEn) "Grant Permission" else "授予权限"

    // ==================== 主页 (HomeScreen) ====================
    object Home {
        val takePhoto: String get() = if (isEn) "📷  Take Photo" else "📷  拍照认星"
        val chooseGalleryOriginal: String get() = if (isEn) "Choose from Gallery (Original Photo)" else "从相册选择（原图·保留拍摄参数）"
        val chooseGalleryCompat: String get() = if (isEn) "System Gallery (Compatible Mode)" else "系统相册（兼容模式·位置可能被隐藏）"
        val offlineDemo: String get() = if (isEn) "✨  Offline Demo (Built-in Photos)" else "✨  离线演示（内置真实星空照片）"
        val history: String get() = if (isEn) "🕘  Recognition History" else "🕘  识别历史"
        val settings: String get() = if (isEn) "⚙️  Settings (API Key)" else "⚙️  设置（API Key）"
        val apiKeyConfigured: String get() = if (isEn) "✓ API Key configured, online solving available" else "✓ 已配置 API Key，可在线识别"
        val apiKeyNotConfigured: String get() = if (isEn) "API Key not configured: free key needed for online solving. Try \"Offline Demo\" first" else "未配置 API Key：在线识别需免费 Key；可先体验「离线演示」"
        val howToUseTitle: String get() = if (isEn) "How to Use" else "如何使用"
        val howToUse1: String get() = if (isEn) "1. Shoot night sky in low light pollution (Night/Long exposure, with at least 3 bright stars)" else "1. 夜晚到光污染较少的地方，用手机拍摄星空（建议使用夜景/长曝光，至少包含 3 颗亮星）"
        val howToUse2: String get() = if (isEn) "2. Tap \"Take Photo\" or pick from gallery" else "2. 点击「拍照认星」或从相册选择照片"
        val howToUse3: String get() = if (isEn) "3. Uses built-in star catalog for instant offline solving; falls back to astrometry.net online solver if needed" else "3. 应用先用内置星表离线识别（不联网），失败时自动回退 astrometry.net 在线服务识别天区"
        val howToUse4: String get() = if (isEn) "4. Constellation lines, star names, and Messier objects will overlay on photo after solving" else "4. 识别完成后，星座连线、星名与星座名称将叠加显示在照片上"
        val howToUseTip: String get() = if (isEn) "Tip: Offline solving requires no network or key; online fallback requires a free API Key (nova.astrometry.net/api_help)." else "提示：离线识别无需网络与 API Key；在线回退需要免费 API Key（nova.astrometry.net/api_help）。"
    }

    // ==================== 设置页 (SettingsScreen) ====================
    object Settings {
        val title: String get() = if (isEn) "Settings" else "设置"
        val languageSection: String get() = if (isEn) "Language" else "语言"
        val languageDesc: String get() = if (isEn) "Choose display language for UI, constellation names, and star names." else "选择界面、星座名称、恒星专名及梅西耶天体的显示语言。"
        val appearanceSection: String get() = if (isEn) "Appearance" else "外观"
        val appearanceDesc: String get() = if (isEn) "Night Red is recommended for dark adaptation: pure black background + red light to protect night vision and save battery." else "夜间观星建议开启「夜视红」：纯黑背景 + 低亮度红光，对暗适应的破坏最小，OLED 屏幕也更省电。"
        val sensorSection: String get() = if (isEn) "Sensor-Assisted Pointing" else "传感器粗定标辅助"
        val sensorTitle: String get() = if (isEn) "Enable Sensor Coarse Pointing" else "开启传感器粗定标"
        val sensorDesc: String get() = if (isEn) "Uses device gyroscope, gravity, compass, and GPS location during shooting to compute camera sky pointing, narrowing plate solving search space by >90% and accelerating recognition." else "利用手机陀螺仪、重力、指南针与定位，在拍照时推算相机视场的天球指向，将解算搜索空间缩减 90% 以上，显著提升识别速度。"
        val engineSection: String get() = if (isEn) "Solving Engine" else "识别引擎"
        val engineDesc: String get() = if (isEn) "Three-tier solving architecture:\n① Built-in 8400+ star catalog matching (fast wide-field solving)\n② astrometry.net official engine (local NDK blind solving, 0.1°~180°)\n③ Online nova.astrometry.net fallback (requires API Key)\nAuto mode: wide-field photos use catalog first, official engine next." else "拍照认星使用三层引擎：\n① 内置 Hipparcos 亮星表广角匹配（约 8°~180°，秒级出结果）\n② astrometry.net 官方盲求解引擎（本地，0.1°~180° 视场，含广角）\n③ 在线 nova.astrometry.net 兜底（需 API Key）\n自动模式：广角照片先亮星表快匹配，失败后用官方引擎精解；\n窄场照片（<8°，如望远镜接拍）自动跳过亮星表直接官方引擎。"
        val onlineSection: String get() = if (isEn) "Online Solver (astrometry.net)" else "在线识别（astrometry.net）"
        val onlineDesc: String get() = if (isEn) "Uses astrometry.net open plate-solving service to solve night sky photos. Free service, registration required to get API Key." else "「拍照认星」使用 astrometry.net 开源底片求解服务识别照片天区。\n该服务免费，需注册获取 API Key。"
        val apiKeyPlaceholder: String get() = if (isEn) "e.g. AbCdEf123456…" else "例如：AbCdEf123456…"
        val apiKeyHelp: String get() = if (isEn) "API Key is an alphanumeric string. Found in \"My Profile\" after login; avoid extra spaces." else "API Key 是一串字母数字（无标点），注册登录后在网站右上角「My Profile」页可见；粘贴时注意不要带上多余空格。"
        val openWebsiteButton: String get() = if (isEn) "Open nova.astrometry.net (My Profile) ↗" else "打开 nova.astrometry.net（注册后在 My Profile 查看 Key）↗"
        val serverSection: String get() = if (isEn) "Advanced: Server URL" else "高级：服务器地址"
        val serverLabel: String get() = if (isEn) "Server URL" else "服务器地址"
        val serverHelp: String get() = if (isEn) "Default: https://nova.astrometry.net/api; change if using a self-hosted instance." else "默认 https://nova.astrometry.net/api；自建 astrometry.net 服务时可修改。"
        val privacySection: String get() = if (isEn) "Privacy Notice" else "隐私说明"
        val privacyContent: String get() = if (isEn) "• For online solving, photos are uploaded to the chosen server (default astrometry.net).\n• Photos are stripped of EXIF data (GPS location, camera parameters) before upload.\n• Uploads are flagged as private by default and will not be added to public galleries.\n• Offline solving does not upload photos." else "• 在线识别时，照片会上传到所选服务器（默认 astrometry.net）。\n• 上传前照片会被重新编码：拍摄位置（GPS）、时间、相机参数等 EXIF 信息已剥离，仅上传像素内容。\n• 上传默认标记为「不公开」，不会被加入公开星图库。\n• 位置信息仅在本地用于天区先验（缩小搜索范围），绝不上传。\n• 无需网络时可使用主页「离线演示」功能完整体验。"
    }

    // ==================== 结果页 (ResultScreen) ====================
    object Result {
        val title: String get() = if (isEn) "Solving Results" else "识别结果"
        val preparing: String get() = if (isEn) "Preparing…" else "准备中…"
        val preparingPhoto: String get() = if (isEn) "Preparing photo…" else "准备照片…"
        val generatingDemo: String get() = if (isEn) "Generating synthetic sky…" else "正在生成模拟星空…"
        val preparingSolve: String get() = if (isEn) "Preparing solving…" else "准备识别…"
        fun starsDetectedMatching(count: Int): String =
            if (isEn) "Detected $count stars, matching star catalog…" else "已检测到 $count 颗星点，正在匹配星表…"

        val missingWcs: String get() = if (isEn) "Missing coordinates system in results, please retry" else "识别结果缺少坐标系信息，请重试"
        val cannotReadImage: String get() = if (isEn) "Cannot read photo file" else "无法读取照片文件"
        val solveFailedNoKey: String get() = if (isEn) "Local solving did not match catalog.\n\nEnter free astrometry.net API Key in Settings to enable online solving\n(https://nova.astrometry.net/api_help).\n💡 Turn on location when shooting to narrow search area." else "本地识别未能匹配星图。\n\n可到「设置」中填写 astrometry.net 免费 API Key 启用在线识别\n（https://nova.astrometry.net/api_help 注册即可获得）。\n💡 拍摄时开启定位可缩小搜索天区、提高成功率。"
        val solveFailedWithKey: String get() = if (isEn) "Both local and online solvers failed to match.\nTry shooting again (avoid overexposure or blur), or adjust engine mode in Settings.\n💡 Turn on location when shooting to narrow search area." else "本地与在线引擎均未能匹配星图。\n可尝试重新拍摄（避免过曝或抖动），或在「设置」中调整识别引擎模式。\n💡 拍摄时开启定位可缩小搜索天区、提高成功率。"
        val solveFailed: String get() = if (isEn) "Solving Failed" else "识别失败"
        val backHome: String get() = if (isEn) "Back to Home" else "返回主页"

        val titleDemo: String get() = if (isEn) "✨ Offline Demo · Solved" else "✨ 离线演示 · 识别成功"
        val titleNative: String get() = if (isEn) "🔭 astrometry.net Engine · Solved" else "🔭 astrometry.net 官方引擎 · 识别成功"
        val titleLocal: String get() = if (isEn) "🗺 Local Catalog · Solved" else "🗺 本地星表匹配 · 识别成功"
        val titleOnline: String get() = if (isEn) "✅ Online Solved" else "✅ 在线识别成功"
        val titleDefault: String get() = if (isEn) "Solved Successfully" else "识别成功"

        val labelEngine: String get() = if (isEn) "Solver Engine" else "识别引擎"
        val labelCenter: String get() = if (isEn) "Center Coordinates" else "中心坐标"
        val labelFov: String get() = if (isEn) "Field of View" else "视场大小"
        val labelPixScale: String get() = if (isEn) "Pixel Scale" else "像素比例尺"
        val labelOrientation: String get() = if (isEn) "Orientation" else "方向角"
        val labelTaskId: String get() = if (isEn) "Task ID" else "任务编号"
        val constellationsInField: String get() = if (isEn) "Constellations in field:" else "画面中的星座："

        val reSolve: String get() = if (isEn) "Solve Again" else "重新识别"
        val retake: String get() = if (isEn) "Retake" else "重拍一张"
        val saveToGallery: String get() = if (isEn) "💾 Save to Gallery" else "💾 保存到相册"
        val saving: String get() = if (isEn) "Saving…" else "保存中…"
        fun savedToast(loc: String): String = if (isEn) "Saved to gallery: $loc" else "已保存到本地：$loc"
        val saveFailed: String get() = if (isEn) "Save failed, check storage" else "保存失败，请检查存储空间"

        val viewerTitleSuccess: String get() = if (isEn) "Star Chart & Constellations (Pinch to Zoom)" else "星空与星座连线（双指缩放查看）"
        val viewerTitleDiag: String get() = if (isEn) "Detected Stars Diagnostic (Pinch to Zoom)" else "星点检测标注（双指缩放查看）"
        fun diagLegend(count: Int): String = if (isEn) "◯ Gold circles = $count detected stars" else "◯ 金色圈 = App 检测到的 $count 个星点"
    }

    // ==================== 历史记录 (HistoryScreen) ====================
    object History {
        val title: String get() = if (isEn) "History" else "识别历史"
        val export: String get() = if (isEn) "Export" else "导出"
        val clear: String get() = if (isEn) "Clear" else "清空"
        val emptyTitle: String get() = if (isEn) "No records yet" else "还没有识别记录"
        val emptyDesc: String get() = if (isEn) "Successfully solved photos will appear here automatically" else "成功识别的照片会自动记录在这里"
        val deleteDialogTitle: String get() = if (isEn) "Delete this record?" else "删除这条识别记录？"
        val imageInvalid: String get() = if (isEn) "Image missing" else "图片已失效"
        fun statsSummary(count: Int, rate: Int, avgSec: Long, lastSec: Long): String =
            if (isEn) "$count solved · Success rate $rate% · Avg ${avgSec}s · Last ${lastSec}s"
            else "共识别 $count 次 · 成功率 $rate% · 平均 $avgSec 秒 · 最近 $lastSec 秒"
        val noStats: String get() = if (isEn) "No stats available" else "暂无识别统计"
        fun exportSuccess(msg: String): String = if (isEn) "Exported: $msg" else "已导出：$msg"
        val exportFailed: String get() = if (isEn) "Export failed" else "导出失败"
    }

    // ==================== 批量识别 (BatchScreen) ====================
    object Batch {
        val title: String get() = if (isEn) "Batch Solving" else "批量识别"
        val queued: String get() = if (isEn) "Queued" else "排队"
        val processing: String get() = if (isEn) "Solving…" else "识别中…"
        val exported: String get() = if (isEn) "✓ Exported" else "✓ 已导出"
        val cancel: String get() = if (isEn) "Cancel" else "取消"
        fun summary(total: Int, done: Int, finished: Boolean): String =
            if (isEn) "Selected $total · Succeeded $done" + (if (finished) " · Finished" else " · Processing…")
            else "已选 $total 张 · 成功并导出 $done 张" + (if (finished) " · 完成" else " · 处理中…")
        val exportNotice: String get() = if (isEn) "Exported photos are saved in Pictures/StarCam/. Records added to History." else "导出的叠加图在相册 Pictures/StarCam/ 目录；识别记录已写入「识别历史」。"
    }

    // ==================== 离线演示 (OfflineDemoScreen) ====================
    object Demo {
        val title: String get() = if (isEn) "Offline Demo" else "离线演示"
        val randomSky: String get() = if (isEn) "✨ Random Synthetic Star Field" else "✨ 随机生成模拟星空（合成图）"
        val selectRealPhoto: String get() = if (isEn) "Or choose a built-in real night sky photo (taken with smartphone, offline solving verified, no network or API Key needed):" else "或选择一张内置真实星空照片——均为真实手机拍摄，离线识别链路已验证可识别，无需网络与 API Key："
    }

    // ==================== 相机取景 (CameraScreen) ====================
    object Camera {
        val permissionRequired: String get() = if (isEn) "Camera permission required to take photos, please enable in system settings" else "需要相机权限才能拍照，请在系统设置中开启"
        val launchFailed: String get() = if (isEn) "Camera launch failed" else "相机启动失败"
        val notReady: String get() = if (isEn) "Camera not ready, please wait" else "相机尚未就绪，请稍候"
        val captureFailed: String get() = if (isEn) "Capture failed" else "拍照失败"
        val liveOverlayDesc: String get() = if (isEn) "Live sky recognition overlay" else "实时认星标注"
        fun identifiedLabel(label: String): String = if (isEn) "Identified: $label (Live)" else "已识别：$label（实时预览）"
        val focusHint: String get() = if (isEn) "🔭 Tap brightest star to focus" else "🔭 轻点画面中的最亮星即可对焦"
        fun evLabel(ev: Int): String = "EV ${if (ev >= 0) "+$ev" else "$ev"}"
        val nightGuidance: String get() = if (isEn) "🌙 Night Mode ON: Keep phone steady or use tripod to avoid star trails.\nRecommended: ISO 800–3200 · 8–30s exposure." else "🌙 夜景增强已开启：请将手机固定（靠稳/三脚架）避免星点拖尾。\n推荐参数：ISO 800–3200 · 曝光 8–30 秒（光害大时 2–8 秒）；更长曝光请使用系统相机专业模式"
    }

    // ==================== 原图相册 (GalleryScreen) ====================
    object Gallery {
        val title: String get() = if (isEn) "Select Star Photo (Original)" else "选择星空照片（原图）"
        val multiSelect: String get() = if (isEn) "Multi-Select" else "多选"
        val cancelMulti: String get() = if (isEn) "Cancel Multi-Select" else "取消多选"
        fun identifySelected(count: Int, max: Int): String =
            if (isEn) "Solve Selected ($count${if (count >= max) ", max reached" else ""})"
            else "识别所选（$count${if (count >= max) "，已达上限" else ""}）"
        val empty: String get() = if (isEn) "No photos found in gallery" else "相册中没有照片"
        val permissionTitle: String get() = if (isEn) "Photos & Videos Permission Required" else "需要「照片和视频」权限"
        val permissionDesc: String get() = if (isEn) "Original photo mode reads raw files to preserve focal length and shooting time parameters, significantly accelerating plate solving." else "原图模式直接读取相册原文件，识别时才能使用照片的拍摄参数（焦距 → 视场、拍摄时间 → 天区）加速解算。\n系统选择器会隐藏这些参数（厂商隐私保护），导致识别变慢甚至失败。"
    }

    // ==================== 图片查看器 (ZoomableImageViewer) ====================
    object Viewer {
        val gestureHint: String get() = if (isEn) "Pinch to zoom · Drag to pan · Double tap to zoom" else "双指缩放 · 拖动查看 · 双击放大"
    }

    // ==================== AR 实时星图（§0.49 CameraScreen） ====================
    object Ar {
        val toggleDesc: String get() = if (isEn) "AR live star map" else "AR 实时星图"
        val fovLabel: String get() = if (isEn) "FOV calibration %.0f°" else "视场校准 %.0f°"
        val fovAuto: String get() = if (isEn) "🎯 FOV %.1f° (auto)" else "🎯 视场 %.1f°（自动标定）"
        val needOrientation: String get() = if (isEn) "🧭 Waiting for orientation…" else "🧭 等待传感器就绪…"
        val needLocation: String get() = if (isEn) "📍 Location needed for AR star map" else "📍 开启定位后显示 AR 星图"
        val belowHorizon: String get() = if (isEn) "⬇ Point phone at the sky" else "⬇ 请将手机朝向星空"
        val calibratedHint: String get() = if (isEn) "🎯 Calibrated by solver" else "🎯 已由识别校准"
    }

    // ==================== Pro 手动曝光（§0.50 CameraScreen） ====================
    object Pro {
        val proOn: String get() = if (isEn) "⚙️ Pro Manual (AE off) · tap to exit" else "⚙️ Pro 手动曝光（AE 关）· 点击退出"
        val proOff: String get() = if (isEn) "⚙️ Pro Manual Exposure · tap to enable" else "⚙️ Pro 手动曝光 · 点击开启"
        val isoLabel: String get() = if (isEn) "ISO sensitivity" else "感光度 ISO"
        val shutterLabel: String get() = if (isEn) "Shutter speed" else "快门时长"
        val longExposureWarn: String get() = if (isEn)
            "🌙 Long exposure: preview slows and accumulates light. Hold steady or use a tripod."
        else
            "🌙 长曝光：预览会变慢并持续积光，请固定手机或使用三脚架"
        val levelTitle: String get() = if (isEn) "Camera roll" else "相机滚转"
    }

    // ==================== 图层控制（§0.47 LayerPanel） ====================
    object Layers {
        val panelTitle: String get() = if (isEn) "Layers" else "图层"
        val lines: String get() = if (isEn) "Constellation lines" else "星座连线"
        val starNames: String get() = if (isEn) "Star names" else "恒星名称"
        val constellationNames: String get() = if (isEn) "Constellation names" else "星座名称"
        val messier: String get() = if (isEn) "Messier objects" else "梅西耶天体"
        val holdOriginal: String get() = if (isEn) "Hold to view original" else "按住看原图"
        val releaseToRestore: String get() = if (isEn) "Release to restore" else "松开恢复标注"
    }

    // ==================== 天体科普卡片（§0.47 ObjectInfoCard） ====================
    object InfoCard {
        val magLabel: String get() = if (isEn) "Magnitude" else "视星等"
        val distLabel: String get() = if (isEn) "Distance" else "距离"
        val typeLabel: String get() = if (isEn) "Type" else "类型"
        val constellationLabel: String get() = if (isEn) "Constellation" else "所属星座"
        fun close(): String = if (isEn) "Close" else "关闭"
        val tapHint: String get() = if (isEn) "Tap a marker for details" else "轻点标记查看详情"
    }
}
