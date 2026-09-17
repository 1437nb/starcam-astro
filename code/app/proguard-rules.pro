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

# EncryptedSharedPreferences（androidx.security → Tink）依赖 errorprone 的**编译期**注解：
# 这些注解类不随 Tink 的运行时依赖发布，R8 的 "Missing classes" 检查会当致命错误，
# 导致 :app:minifyReleaseWithR8 失败（§0.66 引入加密存储后 release 打不出来，v1.5.56/57 卡在这）。
# 注解只影响编译期静态检查，对运行行为无影响 → 按 R8 自动生成的 missing_rules.txt 忽略。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
