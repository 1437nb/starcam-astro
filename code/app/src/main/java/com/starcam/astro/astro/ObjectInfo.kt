package com.starcam.astro.astro

/**
 * 天体科普卡片数据（§0.47）：梅西耶深空天体与亮星的中英双语介绍。
 * 数据为观星常用常识值：视星等（m）、距离（光年）、类型与 1~2 句科普。
 */
object ObjectInfo {

    /** 梅西耶天体科普条目 */
    data class MessierInfo(
        val mag: Double,
        val distLyZh: String,
        val distLyEn: String,
        val descZh: String,
        val descEn: String,
    )

    /** 类型 → 中英文名称 */
    fun typeLabel(type: String, isEnglish: Boolean): String = when (type) {
        "G" -> if (isEnglish) "Galaxy" else "星系"
        "N" -> if (isEnglish) "Nebula" else "弥漫星云"
        "PN" -> if (isEnglish) "Planetary Nebula" else "行星状星云"
        "GC" -> if (isEnglish) "Globular Cluster" else "球状星团"
        "OC" -> if (isEnglish) "Open Cluster" else "疏散星团"
        else -> if (isEnglish) "Deep-sky Object" else "深空天体"
    }

    /** 梅西耶编号 → 科普信息（覆盖目录中全部 45 个天体） */
    val messier: Map<Int, MessierInfo> = mapOf(
        31 to MessierInfo(3.4, "约 254 万光年", "~2.54M ly",
            "距银河系最近的大型旋涡星系，晴夜肉眼可辨，正在与我们相撞，数十亿年后将合并。", "Nearest major spiral galaxy, visible to naked eye; on a collision course with the Milky Way."),
        32 to MessierInfo(8.1, "约 265 万光年", "~2.65M ly",
            "仙女座星系的伴星系，致密的椭圆矮星系。", "Compact dwarf elliptical companion of the Andromeda Galaxy."),
        33 to MessierInfo(5.7, "约 295 万光年", "~2.95M ly",
            "本星系群第三大星系，脸面朝向我们，适合低倍率观测。", "Third-largest galaxy of the Local Group, face-on and diffuse."),
        42 to MessierInfo(4.0, "约 1,344 光年", "~1,344 ly",
            "全天最明亮的弥漫星云之一，猎户座'佩剑'处，是正在诞生恒星的育婴房。", "Brightest diffuse nebula in the sky, a stellar nursery in Orion's sword."),
        43 to MessierInfo(9.0, "约 1,600 光年", "~1,600 ly",
            "猎户座大星云北部的附属星云，被一道尘埃带隔开。", "Comma-shaped companion nebula detached from M42 by a dust lane."),
        44 to MessierInfo(3.7, "约 577 光年", "~577 ly",
            "即鬼星团，巨蟹座肉眼可见的疏散星团，中国古代称'积尸气'。", "Beehive Cluster in Cancer, visible to the naked eye from dark sites."),
        45 to MessierInfo(1.6, "约 444 光年", "~444 ly",
            "昴星团（七姊妹），最著名而明亮的疏散星团，蓝色反射星云环绕。", "The Pleiades, the most famous bright open cluster wrapped in blue reflection nebulosity."),
        13 to MessierInfo(5.8, "约 2.2 万光年", "~22,200 ly",
            "武仙座大球状星团，数十万颗恒星密集成球，北天最壮观的球状星团。", "Great globular cluster of Hercules: hundreds of thousands of stars packed in a sphere."),
        57 to MessierInfo(8.8, "约 2,283 光年", "~2,283 ly",
            "天琴座环状星云，类太阳恒星死亡后抛出的外壳，行星状星云的经典代表。", "Ring Nebula in Lyra: a sun-like star's shed outer shell, classic planetary nebula."),
        27 to MessierInfo(7.4, "约 1,360 光年", "~1,360 ly",
            "狐狸座哑铃星云，较亮的行星状星云，双瓣形状如哑铃。", "Dumbbell Nebula in Vulpecula, a bright bi-lobed planetary nebula."),
        8 to MessierInfo(6.0, "约 4,100 光年", "~4,100 ly",
            "人马座礁湖星云，肉眼可见的大型发射星云，内含'沙漏'结构。", "Lagoon Nebula in Sagittarius, a large naked-eye emission nebula."),
        20 to MessierInfo(6.3, "约 5,200 光年", "~5,200 ly",
            "三裂星云，三条暗尘埃带把它分成三瓣，紧邻礁湖星云。", "Trifid Nebula, split into three lobes by dark dust lanes, near M8."),
        17 to MessierInfo(6.0, "约 5,500 光年", "~5,500 ly",
            "欧米伽星云（天鹅星云），人马座明亮发射星云，形状如天鹅。", "Omega/Swan Nebula, a bright emission nebula in Sagittarius."),
        16 to MessierInfo(6.4, "约 7,000 光年", "~7,000 ly",
            "鹰状星云，'创生之柱'所在的恒星形成区。", "Eagle Nebula, home of the famous 'Pillars of Creation'."),
        51 to MessierInfo(8.4, "约 2,300 万光年", "~23M ly",
            "涡状星系，正面旋涡星系与伴星系 interacting，旋臂清晰经典。", "Whirlpool Galaxy: textbook grand-design spiral interacting with a companion."),
        104 to MessierInfo(8.0, "约 2,930 万光年", "~29.3M ly",
            "草帽星系，侧向旋涡星系，中央巨大核球与尘埃带如草帽檐。", "Sombrero Galaxy: edge-on spiral with a huge bulge and dust lane."),
        81 to MessierInfo(6.9, "约 1,180 万光年", "~11.8M ly",
            "波德星系，大熊座明亮旋涡星系，与 M82 成对。", "Bode's Galaxy, a bright spiral paired with M82 in Ursa Major."),
        82 to MessierInfo(8.4, "约 1,200 万光年", "~12M ly",
            "雪茄星系，正在爆发形成恒星的星暴星系。", "Cigar Galaxy, a starburst galaxy furiously forming stars."),
        101 to MessierInfo(7.9, "约 2,100 万光年", "~21M ly",
            "风车星系，正面大旋涡星系，旋臂宽松如风车。", "Pinwheel Galaxy, a large face-on spiral with loose arms."),
        63 to MessierInfo(8.6, "约 2,700 万光年", "~27M ly",
            "向日葵星系，旋臂呈羽毛状向外发散。", "Sunflower Galaxy with feathery spiral arms."),
        64 to MessierInfo(8.5, "约 1,700 万光年", "~17M ly",
            "黑眼星系，一条醒目的暗尘埃带横过明亮核心。", "Black Eye Galaxy: a dark dust band across the bright core."),
        5 to MessierInfo(5.9, "约 2.5 万光年", "~24,500 ly",
            "巨蛇座球状星团，北天最古老的球状星团之一，约 130 亿岁。", "One of the oldest globular clusters (~13 billion years) in Serpens."),
        15 to MessierInfo(6.2, "约 3.4 万光年", "~33,600 ly",
            "飞马座球状星团，核心极其致密，可能存在中等质量黑洞。", "Dense globular in Pegasus, possibly hosting an intermediate-mass black hole."),
        11 to MessierInfo(5.8, "约 6,200 光年", "~6,200 ly",
            "野鸭星团（盾牌座），紧密的疏散星团呈扇形如野鸭飞行。", "Wild Duck Cluster, a compact V-shaped open cluster in Scutum."),
        35 to MessierInfo(5.3, "约 2,800 光年", "~2,800 ly",
            "双子座富星疏散星团，与邻近的 NGC 2158 构成一对。", "Rich open cluster in Gemini, paired with NGC 2158 nearby."),
        36 to MessierInfo(6.3, "约 4,100 光年", "~4,100 ly", "御夫座年轻疏散星团。", "Young open cluster in Auriga."),
        37 to MessierInfo(6.2, "约 4,500 光年", "~4,500 ly", "御夫座最富星的疏散星团，含数百颗恒星。", "Richest of Auriga's clusters with hundreds of stars."),
        38 to MessierInfo(7.4, "约 4,200 光年", "~4,200 ly", "御夫座疏散星团， telescope 中呈'Ω'形星链。", "Open cluster in Auriga forming an 'Omega'-shaped star chain."),
        41 to MessierInfo(4.5, "约 2,300 光年", "~2,300 ly", "大犬座明亮疏散星团，含数颗红巨星，古中国文献已记载。", "Bright open cluster in Canis Major with several red giants."),
        3 to MessierInfo(6.2, "约 3.4 万光年", "~33,900 ly", "猎犬座球状星团，含约 50 万颗恒星，天文学家研究变星的标杆。", "Globular with ~500,000 stars, a benchmark for variable-star studies."),
        4 to MessierInfo(5.9, "约 7,200 光年", "~7,200 ly", "天蝎座球状星团，距心宿二仅 1.3°，是最靠近太阳系的球状星团之一。", "Globular in Scorpius near Antares, one of the closest to the Sun."),
        6 to MessierInfo(4.2, "约 1,600 光年", "~1,600 ly", "天蝎座蝴蝶星团，蓝色亮星排成展开的蝶翼。", "Butterfly Cluster: blue stars forming outstretched wings in Scorpius."),
        7 to MessierInfo(3.3, "约 980 光年", "~980 ly", "托勒密星团，肉眼极易见的大而亮的疏散星团。", "Ptolemy Cluster: large, bright and easy naked-eye open cluster."),
        80 to MessierInfo(7.3, "约 2.8 万光年", "~28,000 ly", "天蝎座致密球状星团。", "Compact globular cluster in Scorpius."),
        53 to MessierInfo(7.6, "约 6 万光年", "~58,000 ly", "后发座球状星团，远离银心的贫金属老年星团。", "Metal-poor ancient globular in Coma Berenices, far from the galactic center."),
        92 to MessierInfo(6.4, "约 2.7 万光年", "~26,700 ly", "武仙座另一美丽球状星团，较 M13 更致密。", "A dense globular in Hercules, even more compact than M13."),
        71 to MessierInfo(8.2, "约 1.2 万光年", "~12,000 ly", "天箭座松散球状星团，介于球状与疏散之间。", "Loose globular in Sagitta, intermediate between globular and open."),
        67 to MessierInfo(6.1, "约 2,700 光年", "~2,700 ly", "巨蟹座老年疏散星团，年龄约 40 亿年，与太阳系年龄相近。", "Ancient open cluster in Cancer (~4 billion years old)."),
        65 to MessierInfo(10.3, "约 3,500 万光年", "~35M ly", "狮子座三重星系成员之一，侧向旋涡星系。", "One of the Leo Triplet, an inclined spiral galaxy."),
        66 to MessierInfo(9.0, "约 3,600 万光年", "~36M ly", "狮子座三重星系成员，旋臂不对称，曾受引力扰动。", "Leo Triplet member with asymmetric, perturbed arms."),
        96 to MessierInfo(9.2, "约 3,100 万光年", "~31M ly", "狮子座旋涡星系，M96 星系群最亮成员。", "Brightest member of the M96 galaxy group in Leo."),
        97 to MessierInfo(9.9, "约 2,030 光年", "~2,030 ly", "大熊座猫头鹰星云，行星状星云，两颗'眼睛'为中央双星。", "Owl Nebula: planetary nebula whose two 'eyes' are the central binary star."),
        108 to MessierInfo(10.0, "约 4,600 万光年", "~46M ly", "大熊座侧向旋涡星系，前景散布银河系星点。", "Edge-on spiral in Ursa Major strewn with foreground Milky Way stars."),
        109 to MessierInfo(10.0, "约 5,500 万光年", "~55M ly", "大熊座棒旋星系，紧邻大北斗的 Mizar 视线方向。", "Barred spiral in Ursa Major, near Mizar's line of sight."),
    )

    /** 著名亮星 HIP → 科普信息（HIP 编号与 StarNamesEn 星名表对齐，仅收录有故事的亮星） */
    data class StarInfo(
        val distLyZh: String,
        val distLyEn: String,
        val descZh: String,
        val descEn: String,
    )

    val stars: Map<Int, StarInfo> = mapOf(
        32349 to StarInfo("8.6 光年", "8.6 ly", "全天最亮的恒星，古埃及人以它的偕日升预告尼罗河泛滥。", "Brightest star in the night sky; the Egyptians timed Nile floods by its rising."),
        30438 to StarInfo("约 310 光年", "~310 ly", "南天第二亮星，船底座导航标，许多航天器用它校准姿态。", "Second-brightest star; a key navigation beacon for spacecraft."),
        69673 to StarInfo("约 37 光年", "~37 ly", "大角星，北天最亮的恒星，橙色巨人的'守熊人'。", "Arcturus: brightest star of the northern sky, an orange giant."),
        71683 to StarInfo("4.4 光年", "4.4 ly", "南门二，距太阳最近的恒星系统，比邻星即其成员。", "Alpha Centauri: the nearest star system to the Sun, home of Proxima."),
        91262 to StarInfo("25 光年", "25 ly", "织女星，北半球夏夜大三角之一，人类第一颗被拍摄与被测光的恒星。", "Vega: a Summer Triangle vertex, the first star ever photographed."),
        24608 to StarInfo("约 43 光年", "~43 ly", "五车二，御夫座两对巨星组成的著名亮星。", "Capella: a system of giant stars shining as one bright beacon."),
        24436 to StarInfo("约 860 光年", "~860 ly", "参宿七，猎户座最亮的蓝超巨星，光度约为太阳 12 万倍。", "Rigel: Orion's blue supergiant, ~120,000 times the Sun's luminosity."),
        37279 to StarInfo("11.5 光年", "11.5 ly", "南河三，冬季大三角成员，与天狼星相邻的双星。", "Procyon, the 'Before the Dog' star of the Winter Triangle."),
        27989 to StarInfo("约 550 光年", "~550 ly", "参宿四，红超巨星，随时可能以超新星终结一生。", "Betelgeuse, a red supergiant destined to explode as a supernova."),
        7588 to StarInfo("约 139 光年", "~139 ly", "波江座尽头之星，自转极快呈明显的扁球形。", "Achernar, the 'End of the River', visibly flattened by fast rotation."),
        68702 to StarInfo("约 390 光年", "~390 ly", "马腹一，南门二的伴星，半人马座第二亮星。", "Hadar (Beta Centauri), companion beacon to Alpha Centauri."),
        97649 to StarInfo("16.7 光年", "16.7 ly", "牛郎星（河鼓二），自转极快，呈扁球形。", "Altair spins so fast it is visibly flattened into an oblate shape."),
        60718 to StarInfo("约 320 光年", "~320 ly", "南十字座最亮星，十字交叉点南延即天南极方向。", "Acrux: brightest star of the Southern Cross, pointing to the south pole."),
        21421 to StarInfo("约 65 光年", "~65 ly", "毕宿五，金牛座'牛眼'，毕星团前景的橙巨星。", "Aldebaran, the orange 'eye' of Taurus the Bull."),
        80763 to StarInfo("约 550 光年", "~550 ly", "心宿二（大火），天蝎座心脏的红色超巨星，意为'火星的对手'。", "Antares, the red supergiant heart of Scorpius; name means 'rival of Mars'."),
        65474 to StarInfo("约 250 光年", "~250 ly", "角宿一，室女座麦穗，蓝白色密近双星。", "Spica: a blue-white close binary marking Virgo's sheaf of wheat."),
        37826 to StarInfo("约 34 光年", "~34 ly", "北河三，双子座'弟弟'，最近的巨星之一。", "Pollux: the nearest giant star to the Sun."),
        113368 to StarInfo("约 25 光年", "~25 ly", "北落师门，秋夜南方孤星，环绕碎屑盘与已确认的行星。", "Fomalhaut, the 'Lonely Star of Autumn' surrounded by a debris disk."),
        100453 to StarInfo("约 2,600 光年", "~2,600 ly", "天津四，天鹅座尾巴，肉眼可见最遥远的恒星之一。", "Deneb: one of the most distant and luminous stars visible to the naked eye."),
        49841 to StarInfo("约 79 光年", "~79 ly", "轩辕十四，狮子座'镰刀'底端的王星。", "Regulus, the 'little king' at the base of Leo's sickle."),
        36850 to StarInfo("约 51 光年", "~51 ly", "北河二，实际是至少六颗恒星组成的六合星系统。", "Castor: actually a six-star system."),
        11767 to StarInfo("约 433 光年", "~433 ly", "北极星，近天极的造父变星，千百年来为旅人导航。", "Polaris, the North Star: a Cepheid variable that has guided travelers for millennia."),
        54061 to StarInfo("约 123 光年", "~123 ly", "北斗七星勺口第一星（天枢），指极星之一。", "Dubhe, the leading 'pointer' of the Big Dipper."),
        53910 to StarInfo("约 80 光年", "~80 ly", "北斗七星勺口第二星（天璇），与天枢连线指向北极星。", "Merak, the other Big Dipper pointer."),
        62956 to StarInfo("约 83 光年", "~83 ly", "玉衡，北斗勺柄第一星，大熊座最亮成员。", "Alioth, the brightest star of the Big Dipper."),
        65378 to StarInfo("约 83 光年", "~83 ly", "开阳，旁有小星'辅'成著名双星，古代用它测试视力。", "Mizar: famous double with fainter Alcor, an eyesight test since antiquity."),
        67301 to StarInfo("约 104 光年", "~104 ly", "摇光，北斗勺柄末端。", "Alkaid, the end star of the Big Dipper's handle."),
        25336 to StarInfo("约 250 光年", "~250 ly", "参宿五，猎户座左肩的蓝巨星。", "Bellatrix, the blue giant on Orion's left shoulder."),
        25930 to StarInfo("约 2,000 光年", "~2,000 ly", "参宿二（伐二），腰带中央，腰带三星中最遥远者。", "Alnilam, the central and most distant star of Orion's Belt."),
        26727 to StarInfo("约 1,200 光年", "~1,200 ly", "参宿一（觜宿），腰带三星东侧，旁有著名的火焰星云。", "Alnitak, eastern Belt star of Orion near the Flame Nebula."),
        26311 to StarInfo("约 1,200 光年", "~1,200 ly", "参宿三，腰带三星西侧，横跨赤道的天文标定点。", "Mintaka, westernmost star of Orion's Belt, almost exactly on the equator."),
        26451 to StarInfo("约 1,100 光年", "~1,100 ly", "觜宿一，猎户头部的蓝巨星。", "Meissa, the blue giant at Orion's head."),
        677 to StarInfo("约 97 光年", "~97 ly", "壁宿二，仙女座头部，与飞马座大方框共角。", "Alpheratz, joining Andromeda to the Great Square of Pegasus."),
        112748 to StarInfo("约 133 光年", "~133 ly", "室宿一，飞马座大方框的西南角。", "Markab, the southwest corner of the Great Square."),
        113963 to StarInfo("约 196 光年", "~196 ly", "危宿一，飞马座大方框的西北角。", "Scheat, the northwest corner of the Great Square."),
        113881 to StarInfo("约 690 光年", "~690 ly", "危宿三，飞马座的'鼻子'，橙色超巨星。", "Enif, the orange supergiant at Pegasus's nose."),
        25428 to StarInfo("约 134 光年", "~134 ly", "五车五，金牛座北角的亮星。", "Elnath, the bright star on the Bull's northern horn."),
        17702 to StarInfo("约 440 光年", "~440 ly", "昴宿六，昴星团最亮成员，七姊妹之首。", "Alcyone, the brightest member of the Pleiades."),
        101772 to StarInfo("约 430 光年", "~430 ly", "辇道增七，天鹅座嘴部著名的金蓝双色双星。", "Albireo: a celebrated gold-and-blue double star at Cygnus's beak."),
        50583 to StarInfo("约 36 光年", "~36 ly", "五帝座一，狮子座尾巴，春季大三角成员。", "Denebola, the tail of Leo."),
        34444 to StarInfo("约 109 光年", "~109 ly", "井宿三，双子座脚上的亮星。", "Alhena, marking the Twins' feet."),
        86228 to StarInfo("约 143 光年", "~143 ly", "箕宿三，人马座'茶壶'底部的最亮星。", "Kaus Australis, the brightest star of the Teapot in Sagittarius."),
        90185 to StarInfo("约 228 光年", "~228 ly", "斗宿四，人马座'茶壶'柄顶部的蓝色亮星。", "Nunki, the blue star atop the Teapot's handle."),
        82729 to StarInfo("约 400 光年", "~400 ly", "房宿三，天蝎头部三连星的核心。", "Dschubba, the core of Scorpius's three-star head."),
        9884 to StarInfo("约 66 光年", "~66 ly", "娄宿三，白羊座最亮星，春分点曾位于此。", "Hamal, brightest star of Aries; the vernal equinox once lay here."),
        86032 to StarInfo("约 49 光年", "~49 ly", "侯，蛇夫座最亮星。", "Rasalhague, brightest star of Ophiuchus the Serpent Bearer."),
        76267 to StarInfo("约 75 光年", "~75 ly", "贯索四，北冕座的'皇冠宝石'。", "Alphecca, the 'gem of the Northern Crown'."),
    )

    /** 星座缩写 → 中英科普一句话 */
    data class ConstellationInfo(val zh: String, val en: String)

    private val constellationInfo: Map<String, ConstellationInfo> = mapOf(
        "Ori" to ConstellationInfo("冬夜最壮丽的猎人，腰带三星与猎户大星云使它成为全天最有辨识度的星座。",
            "The celestial hunter; its Belt and the Orion Nebula make it unmistakable."),
        "UMa" to ConstellationInfo("大熊座，著名的北斗七星即其背部与尾部，指极星永远指向北极星。",
            "Home of the Big Dipper, whose pointer stars lead to Polaris."),
        "CMa" to ConstellationInfo("大犬座，携带全天最亮的天狼星，冬夜东南天区的主角。",
            "The Great Dog carrying Sirius, the brightest star of all."),
        "Cyg" to ConstellationInfo("天鹅座展翅沿银河飞舞，天津四与北美洲星云都在其翼间。",
            "A swan flying down the Milky Way, crowned by Deneb."),
        "Sco" to ConstellationInfo("天蝎座蜷曲的心脏是红色超巨星心宿二，夏夜南天的标志性星座。",
            "The Scorpion, whose red heart Antares dominates the summer sky."),
        "Leo" to ConstellationInfo("狮子座'镰刀'勾勒出狮首，轩辕十四是黄道带上的王星。",
            "The Lion, whose sickle of stars centers on Regulus."),
        "Lyr" to ConstellationInfo("天琴座虽小，织女星与环状星云使它成为夏夜焦点。",
            "Small but rich: Vega and the Ring Nebula crown the Harp."),
        "Aql" to ConstellationInfo("天鹰座驮着牛郎星沿银河展翼，是夏夜大三角南角。",
            "The Eagle carrying Altair along the Milky Way."),
        "Tau" to ConstellationInfo("金牛座牛眼毕宿五与昴星团同框，冬夜银河边的金牛。",
            "The Bull: Aldebaran's eye and the Pleiades on its shoulder."),
        "Gem" to ConstellationInfo("双子座北河二与北河三并肩，孪生兄弟守望黄道。",
            "The Twins, Castor and Pollux side by side."),
        "Peg" to ConstellationInfo("飞马座大方框是秋夜的路标，星系群散落四周。",
            "The Great Square of Pegasus anchors autumn skies."),
        "And" to ConstellationInfo("仙女座怀抱肉眼极限的仙女星系 M31，秋夜珍宝。",
            "Andromeda hosts M31, the farthest object visible to the naked eye."),
        "Cas" to ConstellationInfo("仙后座 W 形横卧银河，怀抱众多疏散星团。",
            "The Queen's 'W' rides the Milky Way among open clusters."),
        "Cep" to ConstellationInfo("仙王座是造父变星的故乡，'量天尺'由此得名。",
            "Home of Cepheid variables, the yardsticks of the universe."),
        "Cru" to ConstellationInfo("南十字座虽小，却是南半球最著名的路标星座。",
            "The Southern Cross, the southern sky's famous navigator."),
    )

    fun constellation(conAbbr: String): ConstellationInfo? = constellationInfo[conAbbr]
}
