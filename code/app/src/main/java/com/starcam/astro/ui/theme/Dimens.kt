package com.starcam.astro.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距与投影令牌（§0.85）。
 *
 * M3 的 4dp 基线网格：所有 padding/margin 从这组值里取，不再出现 6/7/9/13/15
 * 这类"看起来差不多"的孤值。命名与 Material token 对齐（xs/sm/md/lg/xl/xxl）。
 */
object StarCamDimens {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** 屏幕左右留白 */
    val screenPadding: Dp = 20.dp

    /** 主操作按钮高度（Google 风格的"够按"高度） */
    val primaryButtonHeight: Dp = 56.dp

    /** 常规按钮高度 */
    val buttonHeight: Dp = 52.dp

    /** 图标按钮/头像的常用尺寸 */
    val iconSize: Dp = 24.dp
    val heroIconSize: Dp = 56.dp

    /** 隐私红线提示：分区标题与内容的固定间隔 */
    val sectionGap: Dp = 28.dp
}

/**
 * M3 标准投影档（§0.85）。
 *
 * 深色主题下"层次感"主要靠**色调分层**（surfaceContainer 系列）而不是阴影，
 * 所以本 App 的 elevation 用得很克制：只有悬浮元素（FAB、拖拽把手）才 >0。
 */
object StarCamElevation {
    val level0: Dp = 0.dp
    val level1: Dp = 1.dp
    val level2: Dp = 3.dp
    val level3: Dp = 6.dp
    val level4: Dp = 8.dp
    val level5: Dp = 12.dp
}
