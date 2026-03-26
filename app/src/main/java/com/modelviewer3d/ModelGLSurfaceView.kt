package com.modelviewer3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * Multi-touch GL surface.
 * Has two modes:
 *   MODE_CAMERA  – orbit/zoom/pan
 *   MODE_RULER   – single tap picks a 3D point on the mesh
 */
class ModelGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    enum class Mode { CAMERA, RULER }
    var mode: Mode = Mode.CAMERA

    /** Called when ruler picks a 3D point: provides FloatArray(3) world coords */
    var onRulerPick: ((FloatArray) -> Unit)? = null

    private var lastX = 0f; private var lastY = 0f
    private var lastMidX = 0f; private var lastMidY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (mode == Mode.CAMERA) queueEvent { NativeLib.nativeTouchZoom(d.scaleFactor) }
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode == Mode.CAMERA) queueEvent { NativeLib.nativeResetCamera() }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode == Mode.RULER) {
                    // Pick point on GL thread then callback on UI thread
                    val sx = e.x; val sy = e.y
                    val sw = width.toFloat(); val sh = height.toFloat()
                    queueEvent {
                        val pt = NativeLib.nativePickPoint(sx, sy, sw, sh)
                        if (pt != null) {
                            post { onRulerPick?.invoke(pt) }
                        }
                    }
                }
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
    }

    fun attachRenderer(renderer: ModelRenderer) {
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        if (mode == Mode.RULER) return true  // Only single-tap in ruler mode

        val count = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x; lastY = event.y
                if (count >= 2) {
                    lastMidX = (event.getX(0)+event.getX(1))/2f
                    lastMidY = (event.getY(0)+event.getY(1))/2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x-lastX; val dy = event.y-lastY
                    queueEvent { NativeLib.nativeTouchRotate(dx, dy) }
                    lastX = event.x; lastY = event.y
                } else if (count >= 2 && !scaleDetector.isInProgress) {
                    val mx=(event.getX(0)+event.getX(1))/2f
                    val my=(event.getY(0)+event.getY(1))/2f
                    queueEvent { NativeLib.nativeTouchPan(mx-lastMidX, my-lastMidY) }
                    lastMidX=mx; lastMidY=my
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val rem = if (event.actionIndex==0) 1 else 0
                lastX=event.getX(rem); lastY=event.getY(rem)
            }
        }
        return true
    }
}
