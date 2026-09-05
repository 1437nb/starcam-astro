package com.starcam.astro.astro

/**
 * 全天主要著名恒星的 IAU 官方标准英文专名（Proper Names，§0.43d）
 * 独立类存储以避免 [StarNames] 的 <clinit> 方法超出 JVM 64KB 字节码上限。
 */
object StarNamesEn {

    val names: Map<Int, String> = mapOf(
        // 最亮一等星与主要导航星
        32349 to "Sirius",
        30438 to "Canopus",
        69673 to "Arcturus",
        71683 to "Rigil Kentaurus",
        91262 to "Vega",
        24608 to "Capella",
        24436 to "Rigel",
        37279 to "Procyon",
        27989 to "Betelgeuse",
        7588 to "Achernar",
        68702 to "Hadar",
        97649 to "Altair",
        60718 to "Acrux",
        21421 to "Aldebaran",
        80763 to "Antares",
        65474 to "Spica",
        37826 to "Pollux",
        113368 to "Fomalhaut",
        100453 to "Deneb",
        62434 to "Mimosa",
        49841 to "Regulus",
        33579 to "Adhara",
        36850 to "Castor",
        61084 to "Gacrux",
        85927 to "Shaula",
        // 北极星与北斗七星
        11767 to "Polaris",
        54061 to "Dubhe",
        53910 to "Merak",
        58001 to "Phecda",
        59774 to "Megrez",
        62956 to "Alioth",
        65378 to "Mizar",
        67301 to "Alkaid",
        // 猎户座主要亮星
        25336 to "Bellatrix",
        25930 to "Alnilam",
        26727 to "Alnitak",
        26311 to "Mintaka",
        27366 to "Saiph",
        26451 to "Meissa",
        // 仙后座 W
        746 to "Caph",
        3179 to "Schedar",
        4427 to "Navi",
        6686 to "Ruchbah",
        8886 to "Segin",
        // 飞马座与仙女座
        677 to "Alpheratz",
        1067 to "Algenib",
        113963 to "Scheat",
        112748 to "Markab",
        113881 to "Enif",
        // 英仙座与金牛座
        14576 to "Algol",
        15863 to "Mirfak",
        25428 to "Elnath",
        17702 to "Alcyone",
        // 夏季大三角与其他主要星座
        106032 to "Sadr",
        107556 to "Gienah",
        101772 to "Albireo",
        95947 to "Tarazed",
        97278 to "Alshain",
        92855 to "Sulafat",
        93194 to "Sheliak",
        86032 to "Rasalhague",
        84345 to "Rasalgethi",
        76267 to "Alphecca",
        // 人马座、天蝎座与南天亮星
        86228 to "Kaus Australis",
        90185 to "Nunki",
        89931 to "Ascella",
        82729 to "Dschubba",
        82514 to "Graffias",
        84143 to "Sargas",
        // 白羊、双鱼、鲸鱼、宝瓶
        9884 to "Hamal",
        9487 to "Sheratan",
        3419 to "Diphda",
        10826 to "Mira",
        106278 to "Sadalmelik",
        109074 to "Sadalsuud",
        // 狮子、双子、大犬、小犬
        50583 to "Denebola",
        50335 to "Algieba",
        34444 to "Alhena",
        35550 to "Mebsuta",
        33152 to "Murzim",
        35904 to "Wezen",
        37819 to "Gomeisa",
        // 乌鸦、长蛇、半人马
        46390 to "Alphard",
        60965 to "Gienah Corvi",
        61359 to "Algorab",
        71681 to "Toliman",
        68002 to "Menkent",
    )
}
