# VPSH keeps service + data classes reflective-safe
-keep class com.batman.vpsh.service.** { *; }
-keep class com.batman.vpsh.data.** { *; }
-dontwarn kotlinx.coroutines.**
