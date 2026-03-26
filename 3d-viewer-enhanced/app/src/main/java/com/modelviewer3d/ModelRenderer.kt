package com.modelviewer3d

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer : GLSurfaceView.Renderer {

    var onFpsUpdate: ((Float) -> Unit)? = null

    private var contextInitialized = false
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        contextInitialized = false
        Log.i(TAG, "Surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!contextInitialized) {
            try {
                NativeLib.nativeInit(width, height)
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
