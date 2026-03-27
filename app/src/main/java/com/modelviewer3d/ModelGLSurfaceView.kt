package com.modelviewer3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs

/**
 * Multi-touch GL surface — fixed touch controls:
 *   1-finger drag  → orbit (rotate)
 *   2-finger pinch → zoom  (scale detector)
 *   2-finger drag  → pan
 *   double-tap     → reset camera
 *   single-tap     → ruler pick (ruler mode only)
 */
class ModelGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    enum class Mode { CAMERA, RULER }
    var mode: Mode = Mode.CAMERA
    var onRulerPick: ((FloatArray) -> Unit)? = null

    // Last known positions
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f

    // Whether we are currently in a pan gesture vs rotate
    private var isScaling = false
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (mode == Mode.CAMERA) {
                    // scaleFactor > 1 = fingers spread apart = zoom IN
                    queueEvent { NativeLib.nativeTouchZoom(d.scaleFactor) }
                }
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                isScaling = false
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
                    val sx = e.x; val sy = e.y
                    val sw = width.toFloat(); val sh = height.toFloat()
                    queueEvent {
                        val pt = NativeLib.nativePickPoint(sx, sy, sw, sh)
                        if (pt != null) post { onRulerPick?.invoke(pt) }
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
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (mode == Mode.RULER) return true

        val count = event.pointerCount
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                isScaling = false; isPanning = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down — switch to 2-finger mode
                lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
                lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
                isPanning = true
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    count == 1 && !isScaling -> {
                        // 1-finger: rotate
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                            queueEvent { NativeLib.nativeTouchRotate(dx, dy) }
                        }
                        lastX = event.x; lastY = event.y
                    }
                    count >= 2 && !isScaling -> {
                        // 2-finger (non-pinch): pan
                        val mx = (event.getX(0) + event.getX(1)) * 0.5f
                        val my = (event.getY(0) + event.getY(1)) * 0.5f
                        val dx = mx - lastMidX
                        val dy = my - lastMidY
                        if (abs(dx) > 0.3f || abs(dy) > 0.3f) {
                            queueEvent { NativeLib.nativeTouchPan(dx, dy) }
                        }
                        lastMidX = mx; lastMidY = my
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted — reset to remaining finger position
                val remainIdx = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remainIdx)
                lastY = event.getY(remainIdx)
                isPanning = false; isScaling = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false; isScaling = false
            }
        }
        return true
    }
}
