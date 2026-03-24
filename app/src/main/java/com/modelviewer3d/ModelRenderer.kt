package com.modelviewer3d

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer — thin wrapper around NativeLib JNI calls.
 *
 * FIX: nativeInit is called ONCE when the GL context is first ready (first
 * onSurfaceChanged after context creation). Subsequent orientation/resize
 * events only call nativeResize to update the viewport without re-creating
 * GPU resources (VAOs, shaders, etc.).
 */
class ModelRenderer : GLSurfaceView.Renderer {

    /** Posted to the UI thread after every 60 frames with the current FPS */
    var onFpsUpdate: ((Float) -> Unit)? = null

    private var contextInitialized = false
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL context was (re-)created → must re-init everything on next size change
        contextInitialized = false
        Log.i(TAG, "Surface created / context ready")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!contextInitialized) {
            // First call after context creation → full init
            NativeLib.nativeInit(width, height)
            contextInitialized = true
            Log.i(TAG, "nativeInit ${width}x${height}")
        } else {
            // Just a resize (orientation flip, etc.) → only update viewport
            NativeLib.nativeResize(width, height)
            Log.i(TAG, "nativeResize ${width}x${height}")
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        NativeLib.nativeDraw()
        // Report FPS every 60 frames to avoid UI spam
        if (++frameCount >= 60) {
            frameCount = 0
            val fps = NativeLib.nativeGetFPS()
            onFpsUpdate?.invoke(fps)
        }
    }

    companion object { private const val TAG = "ModelRenderer" }
}
