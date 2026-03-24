# Keep JNI-called classes and native method declarations
-keep class com.modelviewer3d.NativeLib { *; }

# Keep all Activity / Fragment classes
-keep class com.modelviewer3d.** { *; }

# Material Components
-keep class com.google.android.material.** { *; }

# OpenGL / EGL references used in GLSurfaceView
-keep class javax.microedition.khronos.** { *; }

# Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-dontwarn kotlin.**
