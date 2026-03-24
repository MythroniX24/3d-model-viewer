package com.modelviewer3d

object NativeLib {
    // Lifecycle
    external fun nativeInit(width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw()
    external fun nativeDestroy()
    // Model
    external fun nativeLoadModel(path: String): Boolean
    // Camera
    external fun nativeTouchRotate(dx: Float, dy: Float)
    external fun nativeTouchZoom(factor: Float)
    external fun nativeTouchPan(dx: Float, dy: Float)
    external fun nativeResetCamera()
    // Transform
    external fun nativeSetRotation(x: Float, y: Float, z: Float)
    external fun nativeSetTranslation(x: Float, y: Float, z: Float)
    external fun nativeSetScaleMM(w: Float, h: Float, d: Float)   // NEW: mm-based scale
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
    // Undo/Redo
    external fun nativeUndo()
    external fun nativeRedo()
    // Stats & size
    external fun nativeGetFPS(): Float
    external fun nativeGetModelSizeMM(): FloatArray  // [origW,origH,origD, curW,curH,curD]
    // Ruler / picking
    external fun nativePickPoint(sx: Float, sy: Float, sw: Float, sh: Float): FloatArray?
    external fun nativeSetRulerPoints(hasP1: Boolean, p1: FloatArray?, hasP2: Boolean, p2: FloatArray?)
    external fun nativeClearRuler()
    // Screenshot
    external fun nativeTakeScreenshot(): ByteArray?

    init { System.loadLibrary("modelviewer") }
}
