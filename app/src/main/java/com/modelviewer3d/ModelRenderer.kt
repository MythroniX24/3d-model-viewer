package com.modelviewer3d

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer : GLSurfaceView.Renderer {

    var onFpsUpdate: ((Float) -> Unit)? = null

    /** True after our FIRST onSurfaceCreated within the lifetime of this Renderer.
     *  A subsequent onSurfaceCreated means the EGL context was DESTROYED and
     *  re-created (typical when preserveEGLContextOnPause fails, or the GPU
     *  driver issued a context reset) — every GL handle on the C++ side is
     *  now garbage and must be rebuilt from CPU buffers. */
    private var hadPreviousContext = false
    private var contextInitialized = false
    private var contextLostPending = false
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (hadPreviousContext) {
            // EGL context was destroyed & recreated — flag rebuild.
            // We call nativeOnContextLost from onSurfaceChanged (after we
            // know the new framebuffer size) so the lock ordering matches
            // the rest of the JNI surface.
            contextLostPending = true
            Log.i(TAG, "Surface re-created — EGL context lost, will rebuild")
        } else {
            Log.i(TAG, "Surface created (first time)")
        }
        contextInitialized = false
        hadPreviousContext = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!contextInitialized) {
            try {
                if (contextLostPending) {
                    NativeLib.nativeOnContextLost()
                    contextLostPending = false
                }
                NativeLib.nativeInit(width, height)
                NativeLib.nativeRebuildContext()  // no-op if no meshes were loaded yet
                contextInitialized = true
                Log.i(TAG, "nativeInit ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "nativeInit failed: ${e.message}")
            }
        } else {
            try {
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
