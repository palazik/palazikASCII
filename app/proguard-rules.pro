# Keep JNI-called methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep CameraX internals referenced via reflection
-keep class androidx.camera.** { *; }

# Compose
-dontwarn androidx.compose.**
