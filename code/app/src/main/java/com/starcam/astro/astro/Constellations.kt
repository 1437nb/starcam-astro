package com.starcam.astro.astro

/** 88 个星座的国际缩写 → 中文名 */
object Constellations {

    private val ZH = mapOf(
        "And" to "仙女座", "Ant" to "唧筒座", "Aps" to "天燕座", "Aql" to "天鹰座",
        "Aqr" to "宝瓶座", "Ara" to "天坛座", "Ari" to "白羊座", "Aur" to "御夫座",
        "Boo" to "牧夫座", "Cae" to "雕具座", "Cam" to "鹿豹座", "Cap" to "摩羯座",
        "Car" to "船底座", "Cas" to "仙后座", "Cen" to "半人马座", "Cep" to "仙王座",
        "Cet" to "鲸鱼座", "Cha" to "蝘蜓座", "Cir" to "圆规座", "CMa" to "大犬座",
        "CMi" to "小犬座", "Cnc" to "巨蟹座", "Col" to "天鸽座", "Com" to "后发座",
        "CrA" to "南冕座", "CrB" to "北冕座", "Crt" to "巨爵座", "Cru" to "南十字座",
        "Crv" to "乌鸦座", "CVn" to "猎犬座", "Cyg" to "天鹅座", "Del" to "海豚座",
        "Dor" to "剑鱼座", "Dra" to "天龙座", "Equ" to "小马座", "Eri" to "波江座",
        "For" to "天炉座", "Gem" to "双子座", "Gru" to "天鹤座", "Her" to "武仙座",
        "Hor" to "时钟座", "Hya" to "长蛇座", "Hyi" to "水蛇座", "Ind" to "印第安座",
        "Lac" to "蝎虎座", "Leo" to "狮子座", "Lep" to "天兔座", "Lib" to "天秤座",
        "LMi" to "小狮座", "Lup" to "豺狼座", "Lyn" to "天猫座", "Lyr" to "天琴座",
        "Men" to "山案座", "Mic" to "显微镜座", "Mon" to "麒麟座", "Mus" to "苍蝇座",
        "Nor" to "矩尺座", "Oct" to "南极座", "Oph" to "蛇夫座", "Ori" to "猎户座",
        "Pav" to "孔雀座", "Peg" to "飞马座", "Per" to "英仙座", "Phe" to "凤凰座",
        "Pic" to "绘架座", "PsA" to "南鱼座", "Psc" to "双鱼座", "Pup" to "船尾座",
        "Pyx" to "罗盘座", "Ret" to "网罟座", "Scl" to "玉夫座", "Sco" to "天蝎座",
        "Sct" to "盾牌座", "Ser" to "巨蛇座", "Sex" to "六分仪座", "Sge" to "天箭座",
        "Sgr" to "人马座", "Tau" to "金牛座", "Tel" to "望远镜座", "TrA" to "南三角座",
        "Tri" to "三角座", "Tuc" to "杜鹃座", "UMa" to "大熊座", "UMi" to "小熊座",
        "Vel" to "船帆座", "Vir" to "室女座", "Vol" to "飞鱼座", "Vul" to "狐狸座",
    )

    /** 88 个星座的 IAU 标准英文/拉丁全名 */
    val LATIN: Map<String, String> = mapOf(
        "And" to "Andromeda", "Ant" to "Antlia", "Aps" to "Apus", "Aql" to "Aquila",
        "Aqr" to "Aquarius", "Ara" to "Ara", "Ari" to "Aries", "Aur" to "Auriga",
        "Boo" to "Boötes", "Cae" to "Caelum", "Cam" to "Camelopardalis", "Cap" to "Capricornus",
        "Car" to "Carina", "Cas" to "Cassiopeia", "Cen" to "Centaurus", "Cep" to "Cepheus",
        "Cet" to "Cetus", "Cha" to "Chamaeleon", "Cir" to "Circinus", "CMa" to "Canis Major",
        "CMi" to "Canis Minor", "Cnc" to "Cancer", "Col" to "Columba", "Com" to "Coma Berenices",
        "CrA" to "Corona Australis", "CrB" to "Corona Borealis", "Crt" to "Crater", "Cru" to "Crux",
        "Crv" to "Corvus", "CVn" to "Canes Venatici", "Cyg" to "Cygnus", "Del" to "Delphinus",
        "Dor" to "Dorado", "Dra" to "Draco", "Equ" to "Equuleus", "Eri" to "Eridanus",
        "For" to "Fornax", "Gem" to "Gemini", "Gru" to "Grus", "Her" to "Hercules",
        "Hor" to "Horologium", "Hya" to "Hydra", "Hyi" to "Hydrus", "Ind" to "Indus",
        "Lac" to "Lacerta", "Leo" to "Leo", "Lep" to "Lepus", "Lib" to "Libra",
        "LMi" to "Leo Minor", "Lup" to "Lupus", "Lyn" to "Lynx", "Lyr" to "Lyra",
        "Men" to "Mensa", "Mic" to "Microscopium", "Mon" to "Monoceros", "Mus" to "Musca",
        "Nor" to "Norma", "Oct" to "Octans", "Oph" to "Ophiuchus", "Ori" to "Orion",
        "Pav" to "Pavo", "Peg" to "Pegasus", "Per" to "Perseus", "Phe" to "Phoenix",
        "Pic" to "Pictor", "PsA" to "Piscis Austrinus", "Psc" to "Pisces", "Pup" to "Puppis",
        "Pyx" to "Pyxis", "Ret" to "Reticulum", "Scl" to "Sculptor", "Sco" to "Scorpius",
        "Sct" to "Scutum", "Ser" to "Serpens", "Sex" to "Sextans", "Sge" to "Sagitta",
        "Sgr" to "Sagittarius", "Tau" to "Taurus", "Tel" to "Telescopium", "TrA" to "Triangulum Australe",
        "Tri" to "Triangulum", "Tuc" to "Tucana", "UMa" to "Ursa Major", "UMi" to "Ursa Minor",
        "Vel" to "Vela", "Vir" to "Virgo", "Vol" to "Volans", "Vul" to "Vulpecula",
    )

    /** 缩写 → 星座名（根据语言返回中文或英文/拉丁全名，未知返回缩写本身） */
    fun name(abbr: String, isEnglish: Boolean = false): String =
        if (isEnglish) LATIN[abbr] ?: abbr else ZH[abbr] ?: abbr

    /** 缩写 → 中文名；未知返回缩写本身（兼容旧接口） */
    fun zhName(abbr: String): String = name(abbr, isEnglish = false)

    /** 缩写 → 英文/拉丁名；未知返回缩写本身 */
    fun enName(abbr: String): String = name(abbr, isEnglish = true)
}
