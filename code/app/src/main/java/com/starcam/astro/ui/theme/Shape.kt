package com.starcam.astro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 规范形状（§0.85）。
 *
 * M3 的圆角不是随手填的，是五档角色：**按钮/输入框取小档、卡片取中档、
 * 底部弹层/对话框取大档**。全 App 统一走 [androidx.compose.material3.MaterialTheme.shapes]
 * 取值后，"各屏圆角不一致"（此前实测有 6/8/10/12/14/16 六种）就会消失。
 *
 * 屏幕内**不要再写 `RoundedCornerShape(16.dp)`**，按语义取档：
 *   - 小徽标/内嵌小块  → shapes.extraSmall
 *   - 卡片/列表容器    → shapes.medium
 *   - 大卡片/弹层      → shapes.large
 *   - 对话框/底部弹层  → shapes.extraLarge
 *   - 药丸形按钮/芯片  → 用组件自身默认（M3 的 Button/Chip 默认即药丸形）
 */
internal val StarCamShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
