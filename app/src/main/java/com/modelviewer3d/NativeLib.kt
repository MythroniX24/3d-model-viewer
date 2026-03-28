package com.modelviewer3d

object NativeLib {
    external fun nativeInit(width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw()
    external fun nativeDestroy()

    // Two-step load
    external fun nativeParseModel(path: String): Boolean   // IO thread
    external fun nativeUploadParsed(): Boolean             // GL thread
    external fun nativeLoadModel(path: String): Boolean    // legacy

    // Manual separation (user button)
    external fun nativePerformSeparationCPU(): Boolean     // IO thread
    external fun nativePerformSeparationGPU(): Boolean     // GL thread
    external fun nativeIsSeparated(): Boolean
    external fun nativeGetSeparationProgress(): Int  // 0-100

    // Camera
    external fun nativeTouchRotate(dx: Float, dy: Float)
    external fun nativeTouchZoom(factor: Float)
    external fun nativeTouchPan(dx: Float, dy: Float)
    external fun nativeResetCamera()

    // Transform
    external fun nativeSetRotation(x: Float, y: Float, z: Float)
    external fun nativeSetTranslation(x: Float, y: Float, z: Float)
    external fun nativeSetScaleMM(w: Float, h: Float, d: Float)
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

    // Undo/redo
    external fun nativeUndo()
    external fun nativeRedo()

    // Stats
    external fun nativeGetFPS(): Float
    external fun nativeGetModelSizeMM(): FloatArray

    // Mesh
    external fun nativeGetMeshCount(): Int
    external fun nativeGetMeshName(idx: Int): String
    external fun nativeSelectMesh(idx: Int)
    external fun nativeDeleteMesh(idx: Int)
    external fun nativeSetMeshVisible(idx: Int, visible: Boolean)
    external fun nativeGetMeshVisible(idx: Int): Boolean
    external fun nativeSetMeshColor(idx: Int, r: Float, g: Float, b: Float)
    external fun nativeSetMeshScaleMM(idx: Int, w: Float, h: Float, d: Float)
    external fun nativeGetMeshSizeMM(idx: Int): FloatArray
    external fun nativeGetMeshVertexCount(idx: Int): Int

    // Export
    external fun nativeExportOBJ(path: String): Boolean
    external fun nativeExportSTL(path: String): Boolean

    // Ruler
    external fun nativePickPoint(sx: Float, sy: Float, sw: Float, sh: Float): FloatArray?
    external fun nativeSetRulerPoints(hasP1: Boolean, p1: FloatArray?, hasP2: Boolean, p2: FloatArray?)
    external fun nativeClearRuler()

    // Screenshot
    external fun nativeTakeScreenshot(): ByteArray?

    init { System.loadLibrary("modelviewer") }
}
