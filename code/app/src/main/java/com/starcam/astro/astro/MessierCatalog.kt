package com.starcam.astro.astro

/**
 * 梅西耶深空天体目录（§0.43c）：广角星空照片中常见的亮目标，
 * J2000 赤经/赤纬（度），类型：G=星系 / OC=疏散星团 / GC=球状星团 /
 * N=弥漫星云 / PN=行星状星云。用于识别成功后叠加标注。
 */
data class MessierObject(
    val number: Int,
    val ra: Double,
    val dec: Double,
    val zh: String,
    val type: String,
)

object MessierCatalog {

    /** M 编号 → 天体；未收录的编号不叠加 */
    val byNumber: Map<Int, MessierObject> = listOf(
        MessierObject(31, 10.684, 41.269, "仙女座星系", "G"),
        MessierObject(32, 10.674, 40.866, "M32", "G"),
        MessierObject(33, 23.462, 30.660, "三角座星系", "G"),
        MessierObject(42, 83.822, -5.391, "猎户座大星云", "N"),
        MessierObject(43, 83.86, -5.27, "M43", "N"),
        MessierObject(44, 130.099, 19.675, "蜂巢星团", "OC"),
        MessierObject(45, 56.750, 24.117, "昴星团", "OC"),
        MessierObject(13, 250.422, 36.461, "武仙座球状星团", "GC"),
        MessierObject(57, 283.396, 33.029, "环状星云", "PN"),
        MessierObject(27, 299.902, 22.721, "哑铃星云", "PN"),
        MessierObject(8, 270.965, -24.382, "礁湖星云", "N"),
        MessierObject(20, 270.635, -23.026, "三裂星云", "N"),
        MessierObject(17, 275.192, -16.172, "欧米伽星云", "N"),
        MessierObject(16, 274.697, -13.785, "鹰状星云", "N"),
        MessierObject(51, 202.483, 47.195, "涡状星系", "G"),
        MessierObject(104, 189.998, -11.623, "草帽星系", "G"),
        MessierObject(81, 148.888, 69.065, "波德星系", "G"),
        MessierObject(82, 148.968, 69.680, "雪茄星系", "G"),
        MessierObject(101, 210.802, 54.349, "风车星系", "G"),
        MessierObject(63, 198.955, 42.029, "向日葵星系", "G"),
        MessierObject(64, 194.181, 21.683, "黑眼星系", "G"),
        MessierObject(5, 229.638, 2.081, "M5", "GC"),
        MessierObject(15, 322.493, 12.167, "M15", "GC"),
        MessierObject(11, 282.768, -6.271, "野鸭星团", "OC"),
        MessierObject(35, 92.232, 24.343, "M35", "OC"),
        MessierObject(36, 83.001, 34.134, "M36", "OC"),
        MessierObject(37, 89.482, 32.544, "M37", "OC"),
        MessierObject(38, 81.068, 35.851, "M38", "OC"),
        MessierObject(41, 101.514, -20.755, "M41", "OC"),
        MessierObject(3, 205.548, 28.378, "M3", "GC"),
        MessierObject(4, 245.897, -26.526, "M4", "GC"),
        MessierObject(6, 269.643, -32.232, "蝴蝶星团", "OC"),
        MessierObject(7, 271.079, -34.798, "托勒密星团", "OC"),
        MessierObject(80, 244.260, -22.972, "M80", "GC"),
        MessierObject(53, 198.230, 18.169, "M53", "GC"),
        MessierObject(92, 259.281, 43.136, "M92", "GC"),
        MessierObject(71, 298.419, 18.775, "M71", "GC"),
        MessierObject(67, 132.826, 11.817, "M67", "OC"),
        MessierObject(65, 168.709, 13.091, "M65", "G"),
        MessierObject(66, 168.716, 12.998, "M66", "G"),
        MessierObject(96, 162.324, 11.818, "M96", "G"),
        MessierObject(97, 168.700, 55.020, "猫头鹰星云", "PN"),
        MessierObject(108, 168.610, 55.672, "M108", "G"),
        MessierObject(109, 179.398, 53.375, "M109", "G"),
    ).associateBy { it.number }

    /** 类型 → 显示颜色（ARGB） */
    fun typeColor(type: String): Int = when (type) {
        "G" -> 0xFFFFE082.toInt()   // 星系：暖黄
        "N" -> 0xFFA5D6A7.toInt()   // 弥漫星云：绿
        "PN" -> 0xFF80DEEA.toInt()  // 行星状星云：青
        "GC" -> 0xFF90CAF9.toInt()  // 球状星团：蓝
        else -> 0xFFF8BBD0.toInt()  // 疏散星团：粉
    }
}
