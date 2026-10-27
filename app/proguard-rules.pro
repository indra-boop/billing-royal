# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class id.jerco.billingroyal.**$$serializer { *; }
-keepclassmembers class id.jerco.billingroyal.** {
    *** Companion;
}
-keepclasseswithmembers class id.jerco.billingroyal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit (antarmuka API dipanggil lewat refleksi/proxy) ---
-keepattributes Exceptions
-keep interface id.jerco.billingroyal.data.remote.BillingApi { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jangan pernah menyisakan log debug dari android.util.Log di release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
