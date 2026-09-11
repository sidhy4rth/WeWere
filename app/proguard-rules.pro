# Firestore / Firebase model classes are reflectively instantiated.
-keepclassmembers class com.rollapp.shared.data.remote.dto.** {
    *;
}
-keepclassmembers class com.rollapp.shared.domain.model.** {
    *;
}
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.firebase.**

# OkHttp / Okio (used directly by SupabaseImageStore).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
