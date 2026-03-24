package com.modelviewer3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Custom GLSurfaceView with multi-touch handling:
 *   • 1-finger drag  → orbit
 *   • Pinch          → zoom
 *   • 2-finger drag  → pan
 *   • Double-tap     → reset camera
 *
 * FIX: use GLSurfaceView.queueEvent (this.queueEvent), NOT modelRenderer.queueEvent
 */
class ModelGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor > 0f) queueEvent { NativeLib.nativeTouchZoom(factor) }
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                queueEvent { NativeLib.nativeResetCamera() }
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)            // OpenGL ES 3.0
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)  // RGBA8 + depth16
        preserveEGLContextOnPause = true
    }

    fun attachRenderer(renderer: ModelRenderer) {
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Gesture detectors process first
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val count = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x; lastY = event.y
                if (count >= 2) {
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && !scaleDetector.isInProgress) {
                    // Single finger → orbit camera
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    queueEvent { NativeLib.nativeTouchRotate(dx, dy) }
                    lastX = event.x; lastY = event.y
                } else if (count >= 2 && !scaleDetector.isInProgress) {
                    // Two fingers stable (no pinch) → pan
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - lastMidX
                    val dy = midY - lastMidY
                    queueEvent { NativeLib.nativeTouchPan(dx, dy) }
                    lastMidX = midX; lastMidY = midY
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Reset single-finger tracking to surviving pointer
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
            }
        }
        return true
    }
}
