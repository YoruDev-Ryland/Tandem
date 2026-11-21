# R8 rules for the release build.

# kotlinx-serialization: the library ships rules for its runtime; keep our @Serializable classes' generated
# serializers and companions, which are looked up by name.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.tandem.app.** {
    static ** Companion;
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.tandem.app.**$$serializer { *; }
-keepclasseswithmembers class dev.tandem.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The Shizuku UserService: Shizuku starts ShellService by class name, through its (Context) constructor, in a
# separate process, and talks to it over the AIDL stubs. Nothing in the app references those paths directly.
-keep class dev.tandem.app.shell.** { *; }
-keep interface dev.tandem.app.shell.** { *; }

# Shizuku's API and provider (binder plumbing reached by reflection and from the manifest).
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**

# OkHttp's optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
