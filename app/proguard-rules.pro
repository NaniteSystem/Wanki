# zstd-jni (Anki .apkg import): classes and members are looked up from native code
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ML Kit finds its component registrars by class name (manifest metadata) and creates them by
# reflection; R8 full mode would drop the no-arg constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
