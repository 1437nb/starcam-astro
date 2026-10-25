package com.starcam.astro.astro

import com.starcam.astro.astro.SolarSystemEphemeris.SolarBody
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 今日天象概览（纯 JVM，无 Android 依赖，可单测）。
 *
 * 基于 [SolarSystemEphemeris] 提供的精确高阶历表，计算：
 * 1. 当晚月相（月龄、照亮比例、月相名称与符号）；
 * 2. 裸眼可见亮行星（金星、火星、木星、土星）视星等、距角与观测窗口；
 * 3. 当晚天文观测推荐（无月深空夜 / 环形山观测夜 / 满月夜）。
 */
object TonightSky {

    data class MoonPhase(
        val phaseNameZh: String,
        val phaseNameEn: String,
        val symbol: String,
        val illuminationPct: Int,
        val moonAgeDays: Double,
        val isWaxing: Boolean,
    )

    data class VisiblePlanet(
        val body: SolarBody,
        val nameZh: String,
        val nameEn: String,
        val magnitude: Double,
        val elongationDeg: Double,
        val isVisible: Boolean,
        val windowZh: String,
        val windowEn: String,
    )

    data class TonightSkySummary(
        val epochSec: Long,
        val moon: MoonPhase,
        val visiblePlanets: List<VisiblePlanet>,
        val recommendationZh: String,
        val recommendationEn: String,
        val recommendationIcon: String,
    )

    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0.0) x += 360.0
        return x
    }

    /**
     * 计算指定 Unix 秒时刻的天象概览（默认当前系统时间）
     */
    fun compute(epochSec: Long = System.currentTimeMillis() / 1000): TonightSkySummary {
        val jd = SolarSystemEphemeris.unixSecondsToJd(epochSec)
        val sun = SolarSystemEphemeris.geocentric(SolarBody.SUN, jd)
        val moon = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)

        // 1. 月相解算
        val deltaRa = norm360(moon.raDeg - sun.raDeg)
        val isWaxing = deltaRa in 0.0..<180.0
        val elong = moon.elongationDeg.coerceIn(0.0, 180.0)
        val illumPct = (moon.phase * 100.0).roundToInt().coerceIn(0, 100)

        val moonAge = if (isWaxing) {
            (elong / 180.0) * 14.765
        } else {
            14.765 + ((180.0 - elong) / 180.0) * 14.765
        }

        val (phaseZh, phaseEn, symbol) = when {
            elong < 22.5 -> Triple("新月 / 朔", "New Moon", "🌑")
            elong < 67.5 -> if (isWaxing) Triple("蛾眉月", "Waxing Crescent", "🌒") else Triple("残月", "Waning Crescent", "🌘")
            elong < 112.5 -> if (isWaxing) Triple("上弦月", "First Quarter", "🌓") else Triple("下弦月", "Third Quarter", "🌗")
            elong < 157.5 -> if (isWaxing) Triple("盈凸月", "Waxing Gibbous", "🌔") else Triple("亏凸月", "Waning Gibbous", "🌖")
            else -> Triple("满月 / 望", "Full Moon", "🌕")
        }

        val moonPhase = MoonPhase(
            phaseNameZh = phaseZh,
            phaseNameEn = phaseEn,
            symbol = symbol,
            illuminationPct = illumPct,
            moonAgeDays = moonAge,
            isWaxing = isWaxing,
        )

        // 2. 太阳系亮行星可见性解算
        val candidates = listOf(
            SolarBody.VENUS,
            SolarBody.JUPITER,
            SolarBody.SATURN,
            SolarBody.MARS,
            SolarBody.MERCURY,
        )

        val planets = candidates.map { body ->
            val p = SolarSystemEphemeris.geocentric(body, jd)
            val dRa = norm360(p.raDeg - sun.raDeg)
            val isEvening = dRa in 0.0..<180.0

            val isVisible = when (body) {
                SolarBody.VENUS -> p.elongationDeg >= 10.0
                SolarBody.JUPITER -> p.elongationDeg >= 15.0
                SolarBody.SATURN -> p.elongationDeg >= 15.0
                SolarBody.MARS -> p.elongationDeg >= 15.0
                SolarBody.MERCURY -> p.elongationDeg >= 14.0
                else -> false
            }

            val (winZh, winEn) = when (body) {
                SolarBody.VENUS -> if (isEvening) {
                    "日落后见于西方（长庚星 / 昏星）" to "Western evening sky after sunset"
                } else {
                    "日出前见于东方（启明星 / 晨星）" to "Eastern morning sky before sunrise"
                }
                SolarBody.JUPITER -> when {
                    p.elongationDeg > 150.0 -> "整夜可见（接近冲日）" to "Visible all night (near opposition)"
                    isEvening -> "前半夜可见于西南天空" to "Visible in evening sky"
                    else -> "后半夜可见于东南天空" to "Visible in second half of night"
                }
                SolarBody.SATURN -> when {
                    p.elongationDeg > 150.0 -> "整夜可见（接近冲日）" to "Visible all night (near opposition)"
                    isEvening -> "前半夜可见" to "Visible in first half of night"
                    else -> "后半夜可见" to "Visible in second half of night"
                }
                SolarBody.MARS -> when {
                    p.elongationDeg > 150.0 -> "整夜可见（接近冲日）" to "Visible all night"
                    isEvening -> "傍晚可见" to "Visible in evening"
                    else -> "黎明前可见" to "Visible before dawn"
                }
                SolarBody.MERCURY -> if (isEvening) {
                    "日落后低空短暂可见（昏伏）" to "Briefly visible low in western dusk"
                } else {
                    "日出前低空短暂可见（晨伏）" to "Briefly visible low in eastern dawn"
                }
                else -> "" to ""
            }

            VisiblePlanet(
                body = body,
                nameZh = SolarSystemCatalog.name(body, isEnglish = false),
                nameEn = SolarSystemCatalog.name(body, isEnglish = true),
                magnitude = p.magnitude,
                elongationDeg = p.elongationDeg,
                isVisible = isVisible,
                windowZh = winZh,
                windowEn = winEn,
            )
        }.filter { it.isVisible }

        // 3. 观星建议
        val (recZh, recEn, recIcon) = when {
            illumPct <= 25 -> Triple(
                "无明显月光干扰 · 极佳深空天体与银河摄影时机",
                "Minimal moonlight · Ideal for deep sky & Milky Way astrophotography",
                "✨",
            )
            illumPct in 26..74 -> Triple(
                "月光明亮适中 · 适合观测月面环形山明暗界线与主要行星",
                "Moderate moonlight · Great for lunar craters and bright planets",
                "🔭",
            )
            else -> Triple(
                "月光强烈 · 推荐观赏满月全貌、辐射纹与高亮度行星",
                "Strong moonlight · Best for lunar features & bright planets",
                "🌕",
            )
        }

        return TonightSkySummary(
            epochSec = epochSec,
            moon = moonPhase,
            visiblePlanets = planets,
            recommendationZh = recZh,
            recommendationEn = recEn,
            recommendationIcon = recIcon,
        )
    }
}
