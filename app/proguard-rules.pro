# The engine calls into these from JavaScript, so their names must survive.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization: keep the generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.p2r3.convert.** {
    *** Companion;
}
-keepclasseswithmembers class com.p2r3.convert.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.p2r3.convert.**$$serializer { *; }
