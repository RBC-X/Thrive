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
