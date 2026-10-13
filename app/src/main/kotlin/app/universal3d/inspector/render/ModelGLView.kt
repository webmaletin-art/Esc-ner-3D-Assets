package app.universal3d.inspector.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.hypot

/** Vista GL con gestos: 1 dedo rota, pellizco hace zoom, 2 dedos desplazan, toque selecciona, doble toque reencuadra. */
class ModelGLView(context: Context, callbacks: RendererCallbacks) : GLSurfaceView(context) {
    val renderer = ModelRenderer(this, callbacks)
    private var lastX = 0f; private var lastY = 0f; private var lastCx = 0f; private var lastCy = 0f
    private var pointers = 0
    private var moved = false

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { renderer.zoom(d.scaleFactor); return true }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (!moved) renderer.pickAt(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { renderer.resetCamera(); return true }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e); taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; moved = false; pointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> { pointers = e.pointerCount; lastCx = (e.getX(0) + e.getX(1)) / 2; lastCy = (e.getY(0) + e.getY(1)) / 2; moved = true }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scale.isInProgress) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (moved || hypot(dx, dy) > 8f) { moved = true; renderer.rotate(dx, dy); lastX = e.x; lastY = e.y }
                } else if (e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) / 2; val cy = (e.getY(0) + e.getY(1)) / 2
                    renderer.pan(cx - lastCx, cy - lastCy); lastCx = cx; lastCy = cy
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { pointers = e.pointerCount - 1; val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers = 0
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    fun releaseModel() { renderer.release() }
}
