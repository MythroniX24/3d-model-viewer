package com.modelviewer3d

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ModelRenderer : GLSurfaceView.Renderer {

    var onFpsUpdate: ((Float) -> Unit)? = null

    // Called when GL context is lost (app goes background) and needs re-init
    var onContextLost: (() -> Unit)? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL context was (re)created — must re-init everything
        // Surface size not known yet; wait for onSurfaceChanged
        Log.i(TAG, "Surface created — GL context ready")
        // Notify activity so it can re-upload any previously loaded model
        onContextLost?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        try {
            NativeLib.nativeInit(width, height)
            Log.i(TAG, "nativeInit ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "nativeInit failed: ${e.message}")
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
