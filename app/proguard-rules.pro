-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.example.hackertubelite.** { *** Companion; }
-keepclasseswithmembers class com.example.hackertubelite.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.hackertubelite.**$$serializer { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
