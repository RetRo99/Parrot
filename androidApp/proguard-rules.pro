# Kotlin Multiplatform / Compose Multiplatform ProGuard Rules

# Keep Kotlin Metadata for reflection
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep Kotlin Serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.retro99.**$$serializer { *; }
-keepclassmembers class com.retro99.** {
    *** Companion;
}
-keepclasseswithmembers class com.retro99.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Koin
-keep class org.koin.** { *; }
-keepclassmembers class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Coil
-keep class coil3.** { *; }
-dontwarn coil3.**

# Keep Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep data classes and API models (adjust package as needed)
-keep class com.retro99.**.model.** { *; }
-keep class com.retro99.**.*ApiModel { *; }
-keep class com.retro99.**.*DomainModel { *; }
-keep class com.retro99.**.*Entity { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep R8 full mode compatibility
-allowaccessmodification
-repackageclasses

# WorkManager loads Room's generated WorkDatabase_Impl by class name. Keep the
# generated no-argument constructor used by Room's reflective factory.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Debugging - remove for production if you want smaller APK
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
