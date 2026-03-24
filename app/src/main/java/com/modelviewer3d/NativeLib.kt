package com.modelviewer3d

/**
 * JNI bridge — all methods must be called from the GL thread
 * (where the EGL context is current), except nativeGetFPS which is safe from any thread.
 */
object NativeLib {

    // Lifecycle
    external fun nativeInit(width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw()
    external fun nativeDestroy()

    // Model loading
    external fun nativeLoadModel(path: String): Boolean

    // Camera touch
    external fun nativeTouchRotate(dx: Float, dy: Float)
    external fun nativeTouchZoom(factor: Float)
    external fun nativeTouchPan(dx: Float, dy: Float)
    external fun nativeResetCamera()

    // Transform
    external fun nativeSetRotation(x: Float, y: Float, z: Float)
    external fun nativeSetTranslation(x: Float, y: Float, z: Float)
    external fun nativeSetScale(x: Float, y: Float, z: Float)
    external fun nativeMirrorX()
    external fun nativeMirrorY()
    external fun nativeMirrorZ()
    external fun nativeResetTransform()

    // Visual
    external fun nativeSetColor(r: Float, g: Float, b: Float)
    external fun nativeSetAmbient(v: Float)
    external fun nativeSetDiffuse(v: Float)
    external fun nativeSetWireframe(on: Boolean)
    external fun nativeSetBoundingBox(on: Boolean)

    // Undo / Redo
    external fun nativeUndo()
    external fun nativeRedo()

    // Stats
    external fun nativeGetFPS(): Float

    // Screenshot → RGBA byte array
    external fun nativeTakeScreenshot(): ByteArray?

    init {
        System.loadLibrary("modelviewer")
    }
}
