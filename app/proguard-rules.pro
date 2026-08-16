# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.thrive.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.thrive.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager bundles Room: the generated DB implementation and its no-arg
# constructor are looked up reflectively, so R8 must not strip them.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# MediaPipe LLM Inference (tasks-genai): the JNI layer and generated proto model
# options are loaded reflectively, so keep the whole package. AutoValue is a
# compile-time codegen annotation referenced by the AAR's bytecode but not
# shipped — suppressing the warning is the standard handling.
-keep class com.google.mediapipe.tasks.** { *; }
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
