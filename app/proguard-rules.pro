# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.thrive.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.thrive.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
