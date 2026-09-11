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
