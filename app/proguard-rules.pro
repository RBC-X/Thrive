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
# Missing-class warnings for references that never execute in this app:
#   - com.google.mediapipe.framework.image.* is only touched by multimodal
#     (image+text) models; Thrive runs the Qwen2.5 text-only model.
#   - proto presence/nullness helpers (ProtoPresenceBits, ProtoField, etc.)
#     are referenced by audio/multimodal model-settings code paths the text
#     pipeline never calls. Proven safe: the full text LLM ran on the emulator
#     with these classes absent (they exist in no dependency on the classpath).
-dontwarn com.google.mediapipe.framework.image.**
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
