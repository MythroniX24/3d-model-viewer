package com.modelviewer3d

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer : GLSurfaceView.Renderer {

    var onFpsUpdate: ((Float) -> Unit)? = null

    // Set true in onSurfaceCreated so onSurfaceChanged knows to call nativeInit
    // instead of nativeResize. This correctly handles both first launch and
    // GL context recreation (e.g. app returning from background with lost context).
    private var needsFullInit = true
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL context was (re)created. Flag full init for onSurfaceChanged.
        // NOTE: Do NOT call nativeInit here — surface dimensions aren't known yet.
        needsFullInit = true
        Log.i(TAG, "onSurfaceCreated — GL context ready, waiting for dimensions")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (needsFullInit) {
            needsFullInit = false
            try {
                // Full init: rebuild shaders, recreate BB/ruler VAOs,
                // and re-upload any existing mesh data (context recovery).
                NativeLib.nativeInit(width, height)
                Log.i(TAG, "nativeInit ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "nativeInit failed: ${e.message}")
            }
        } else {
            try {
                // Surface resized (e.g. keyboard shown/hidden) — just update viewport
                NativeLib.nativeResize(width, height)
            } catch (e: Exception) {
                Log.e(TAG, "nativeResize failed: ${e.message}")
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            NativeLib.nativeDraw()
        } catch (e: Exception) {
            Log.e(TAG, "nativeDraw failed: ${e.message}")
        }
        if (++frameCount >= 60) {
            frameCount = 0
            try {
                val fps = NativeLib.nativeGetFPS()
                onFpsUpdate?.invoke(fps)
            } catch (_: Exception) {}
        }
    }

    companion object { private const val TAG = "ModelRenderer" }
}
