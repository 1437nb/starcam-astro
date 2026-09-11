package com.starcam.astro.astro

import com.starcam.astro.astro.SolarSystemEphemeris.SolarBody

/**
 * 太阳系天体目录与展示数据（§0.58）。
 *
 * 与 [MessierCatalog] / [ObjectInfo] 同风格：负责中英双语的显示名、按天体类型的
 * 标注配色、以及科普卡片文案。历表计算全部在 [SolarSystemEphemeris]，
 * 本对象只做「怎么显示」。
 */
object SolarSystemCatalog {

    /** 显示名（中/英） */
    fun name(body: SolarBody, isEnglish: Boolean): String = if (isEnglish) {
        when (body) {
            SolarBody.SUN -> "Sun"
            SolarBody.MOON -> "Moon"
            SolarBody.MERCURY -> "Mercury"
            SolarBody.VENUS -> "Venus"
            SolarBody.MARS -> "Mars"
            SolarBody.JUPITER -> "Jupiter"
            SolarBody.SATURN -> "Saturn"
            SolarBody.URANUS -> "Uranus"
            SolarBody.NEPTUNE -> "Neptune"
        }
    } else {
        when (body) {
            SolarBody.SUN -> "太阳"
            SolarBody.MOON -> "月亮"
            SolarBody.MERCURY -> "水星"
            SolarBody.VENUS -> "金星"
            SolarBody.MARS -> "火星"
            SolarBody.JUPITER -> "木星"
            SolarBody.SATURN -> "土星"
            SolarBody.URANUS -> "天王星"
            SolarBody.NEPTUNE -> "海王星"
        }
    }

    /** 类型标签（科普卡片副标题用） */
    fun typeLabel(body: SolarBody, isEnglish: Boolean): String = if (isEnglish) {
        when (body) {
            SolarBody.SUN -> "Star"
            SolarBody.MOON -> "Natural satellite"
            else -> "Planet"
        }
    } else {
        when (body) {
            SolarBody.SUN -> "恒星"
            SolarBody.MOON -> "天然卫星"
            else -> "行星"
        }
    }

    /**
     * 标注配色（ARGB）。按肉眼观感取色：
     * 太阳暖黄、月亮近白、火星橙红、金星牙白、木星奶油、土星淡金、天王青、海王蓝。
     */
    fun color(body: SolarBody): Int = when (body) {
        SolarBody.SUN -> 0xFFFFD54F.toInt()
        SolarBody.MOON -> 0xFFF2F2F2.toInt()
        SolarBody.MERCURY -> 0xFFBCAAA4.toInt()
        SolarBody.VENUS -> 0xFFFFF3B0.toInt()
        SolarBody.MARS -> 0xFFFF8A65.toInt()
        SolarBody.JUPITER -> 0xFFFFCC80.toInt()
        SolarBody.SATURN -> 0xFFFFE0B2.toInt()
        SolarBody.URANUS -> 0xFF9FE3E3.toInt()
        SolarBody.NEPTUNE -> 0xFF7FA8FF.toInt()
    }

    /** 是否用实心圆绘制（日、月体积大、光强，用实心 + 光晕） */
    fun isDisk(body: SolarBody): Boolean = body == SolarBody.SUN || body == SolarBody.MOON

    /** 科普卡片条目 */
    data class SolarInfo(
        val distZh: String,
        val distEn: String,
        val descZh: String,
        val descEn: String,
    )

    /** 天体 → 科普文案（距离为典型值，实际距离见 [SolarSystemEphemeris.SolarPosition]） */
    val info: Map<SolarBody, SolarInfo> = mapOf(
        SolarBody.SUN to SolarInfo(
            "1.496 亿公里", "1 AU",
            "离我们最近的恒星，直径约 139 万公里，是地球的 109 倍。它每秒钟把约 400 万吨质量转成光和热。",
            "The nearest star: 1.39 million km across, 109 times Earth's diameter, fusing 4 million tonnes of mass into light every second.",
        ),
        SolarBody.MOON to SolarInfo(
            "约 38.4 万公里", "~384,000 km",
            "地球唯一的天然卫星，直径 3475 公里。它被潮汐锁定，所以永远只以同一面朝向地球。",
            "Earth's only natural satellite, 3,475 km across. Tidally locked, it always shows us the same face.",
        ),
        SolarBody.MERCURY to SolarInfo(
            "0.39~1.42 亿公里", "0.39–1.42 AU",
            "离太阳最近、也是最小的行星。几乎没有大气，昼夜温差可超过 600℃，是最难观测的行星之一。",
            "The smallest planet and the closest to the Sun, with almost no atmosphere and 600°C day-night swings.",
        ),
        SolarBody.VENUS to SolarInfo(
            "0.26~1.74 亿公里", "0.26–1.74 AU",
            "除日月外全天最亮的天体，即\"启明星\"与\"长庚星\"。浓密的硫酸云使它表面高达 460℃，是最热的行星。",
            "The brightest planet, our Morning and Evening Star. Its sulfuric clouds trap a 460°C inferno.",
        ),
        SolarBody.MARS to SolarInfo(
            "0.37~2.68 亿公里", "0.37–2.68 AU",
            "红色的\"战神之星\"，颜色来自表面氧化铁。拥有太阳系最高的火山奥林匹斯山（约 22 公里）。",
            "The Red Planet, tinted by iron oxide dust, home to Olympus Mons — the tallest volcano in the Solar System.",
        ),
        SolarBody.JUPITER to SolarInfo(
            "3.65~6.24 亿公里", "3.65–6.24 AU",
            "太阳系最大的行星，质量是其余七大行星总和的 2.5 倍。大红斑是一场刮了至少 190 年的风暴。",
            "The giant of the Solar System, 2.5× the mass of all other planets combined. The Great Red Spot has raged for centuries.",
        ),
        SolarBody.SATURN to SolarInfo(
            "12 亿~16.6 亿公里", "1.2–1.66 billion km",
            "拥有最壮观行星环的行星，环主要由水冰碎块组成，厚度却往往不足 100 米。",
            "The ringed jewel: its rings are mostly water ice, yet often less than 100 metres thick.",
        ),
        SolarBody.URANUS to SolarInfo(
            "25.8 亿~31.5 亿公里", "2.58–3.15 billion km",
            "唯一\"躺着\"自转的行星，自转轴倾斜近 98°，可能源于早期的一次巨大撞击。",
            "The only planet that rolls on its side, tilted 98° — probably knocked over by a giant impact.",
        ),
        SolarBody.NEPTUNE to SolarInfo(
            "43 亿~47 亿公里", "4.3–4.7 billion km",
            "距太阳最远的行星，先靠数学计算预言、后被望远镜证实。风速可达 2100 km/h，是太阳系最狂暴的大气。",
            "The farthest planet, found by mathematics before it was ever seen, with 2,100 km/h winds.",
        ),
    )

    /** 默认参与广角照片标注的天体（肉眼可见 + 明亮易认，避免画面被暗淡目标塞满） */
    val defaultAnnotated: List<SolarBody> = listOf(
        SolarBody.MOON,
        SolarBody.VENUS,
        SolarBody.JUPITER,
        SolarBody.SATURN,
        SolarBody.MARS,
        SolarBody.MERCURY,
    )
}
