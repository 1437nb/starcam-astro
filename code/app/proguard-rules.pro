# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 星表与领域数据类
-keep class com.starcam.astro.astro.StarEntry { *; }
-keep class com.starcam.astro.astro.StarCatalogData { *; }
-keep class com.starcam.astro.astro.StarNames { *; }
-keep class com.starcam.astro.astro.StarNamesEn { *; }
-keep class com.starcam.astro.astro.Constellations { *; }
-keep class com.starcam.astro.astro.MessierCatalog { *; }
-keep class com.starcam.astro.astro.MessierObject { *; }
-keep class com.starcam.astro.astro.SolveResult { *; }
-keep class com.starcam.astro.astro.WcsTransform { *; }

# JNI 原生绑定与求解器
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.starcam.astro.astro.StellarSolverNative { *; }
