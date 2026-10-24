# sing-box (libbox, built with gomobile): Go <-> Java bindings are looked up by name.
-keep class go.** { *; }
-keep class io.nekohasekai.** { *; }
-dontwarn go.**
-dontwarn io.nekohasekai.**
-keepclasseswithmembernames class * { native <methods>; }

# zxing scanner activity is started by the library itself.
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.**
-dontwarn com.google.zxing.**
