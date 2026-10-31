package com.starcam.astro.astro

import kotlin.random.Random

/** 天文冷知识（§0.33.4）：识别等待时随机展示一条，替代误导性的固定文案，支持中英双语 */
object AstroTips {
    val allZh: List<String> = listOf(
        "光从太阳到地球约需 8 分 20 秒——你看到的太阳，是 8 分钟前的它。",
        "北斗七星的七颗星里，只有 6 颗同属一个星群，摇光（开阳的伴侣星）其实游离在外。",
        "月亮正以每年约 3.8 厘米的速度远离地球。",
        "宇宙中约 95% 是看不见的暗物质与暗能量，你眼里的一切亮星只占 5%。",
        "仙女座星系（M31）的光出发时，人类还处于旧石器时代——它距我们约 250 万光年。",
        "中子星一立方厘米的物质重约 10 亿吨，相当于全球人口压进一粒方糖。",
        "木星能装下 1300 多个地球，是太阳系最大的行星。",
        "银河系中心的人马座 A* 是一个约 400 万倍太阳质量的超大黑洞。",
        "猎户座大星云（M42）距我们约 1344 光年，是离地球最近的大质量恒星孕育场。",
        "昴星团（M45）大约只有 1 亿岁，而太阳已经 46 亿岁——它们都是恒星，只是代差悬殊。",
        "海王星是唯一一颗「先算出来、再被找到」的行星：1846 年靠数学预言位置后被观测确认。",
        "北极星（勾陈一）并不是全天最亮的星（亮度约排第 48 位），而且正缓慢偏离正北。",
        "金星是太阳系最热的行星（约 465℃），尽管离太阳更近的是水星。",
        "一光年约 9.46 万亿公里——那是光走一整年走过的距离。",
        "天狼星（大犬座 α）是除太阳外全天最亮的恒星，距我们约 8.6 光年。",
        "月亮总以同一面朝向我们（潮汐锁定）——月球背面在地球上永远看不到。",
        "土星的平均密度比水还低：理论上它能在足够大的水面上浮起来。",
        "蟹状星云（M1）是公元 1054 年超新星爆发的遗迹，中国宋朝的天文学家记录下了这颗「客星」。",
        "织女星（天琴座 α）曾是一万多年前的北极星，约一万多年后它还会再次成为北极星。",
        "离太阳最近的恒星是比邻星，约 4.24 光年——以今天最快的飞船也要飞几万年。",
    )

    val allEn: List<String> = listOf(
        "Light takes about 8 minutes 20 seconds to travel from the Sun to Earth — the Sun you see is 8 minutes in the past.",
        "Of the Big Dipper's seven stars, only six belong to the same cluster; Alkaid is physically unrelated.",
        "The Moon is slowly drifting away from Earth at a rate of approximately 3.8 cm per year.",
        "About 95% of the universe consists of dark matter and dark energy; all visible stars make up less than 5%.",
        "Light from the Andromeda Galaxy (M31) left 2.5 million years ago, when early hominids walked Earth.",
        "A single cubic centimeter of neutron-star material weighs ~1 billion tons, like all humanity compressed into a sugar cube.",
        "Jupiter could hold over 1,300 Earths, making it by far the largest planet in our solar system.",
        "Sagittarius A* at the Milky Way's core is a supermassive black hole ~4 million times the mass of our Sun.",
        "The Orion Nebula (M42) is ~1,344 light-years away, the closest massive star-forming nursery to Earth.",
        "The Pleiades (M45) are only ~100 million years old, while our Sun is 4.6 billion years old.",
        "Neptune is the only planet discovered via mathematical prediction (1846) before being observed.",
        "Polaris is only the 48th brightest star in the sky and is slowly drifting away from the true celestial pole.",
        "Venus is the hottest planet in the solar system (~465°C), even though Mercury orbits closer to the Sun.",
        "One light-year is approximately 9.46 trillion kilometers — the distance light travels in one full year.",
        "Sirius (Alpha Canis Majoris) is the brightest star in the night sky, ~8.6 light-years away.",
        "The Moon is tidally locked to Earth, so the far side can never be seen directly from Earth's surface.",
        "Saturn's average density is lower than water: theoretically, it would float in a sufficiently vast ocean.",
        "The Crab Nebula (M1) is the remnant of a supernova recorded by Chinese astronomers in 1054 AD as a \"guest star\".",
        "Vega was the North Star ~12,000 years ago and will reclaim that title in another ~12,000 years.",
        "Proxima Centauri is the closest star to our Solar System at ~4.24 light-years away.",
    )

    val all: List<String> get() = allZh

    /** 随机一条（默认用系统随机源；[seed] 供测试固定；支持中英双语） */
    fun random(isEnglish: Boolean = false, seed: Int = Random.nextInt()): String {
        val list = if (isEnglish) allEn else allZh
        return list[((seed % list.size) + list.size) % list.size]
    }
}
