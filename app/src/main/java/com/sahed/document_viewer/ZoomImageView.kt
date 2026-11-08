package com.sahed.document_viewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    data class Stroke(val points: MutableList<PointF>, val text: String? = null) {
        internal val path = Path()
        internal var pathPoints = 0
        internal var pathWidth = 0f
        internal var pathHeight = 0f
    }
    enum class Tool { READ, PEN, TEXT }
    var tool = Tool.READ
    var strokes: MutableList<Stroke> = mutableListOf()
        set(value) { field = value; invalidate() }
    var onMarkupChanged: (() -> Unit)? = null
    var onTextRequested: ((Float, Float) -> Unit)? = null
    private var activeStroke: Stroke? = null

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            onMarkupChanged?.invoke()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawable ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(imageMatrix)
        drawStrokes(canvas, strokes, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        canvas.restore()
    }

    private var userScale = 1f
    private val minScale = 1f
    private val maxScale = 5f
    private val last = PointF()
    private var mode = NONE
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (userScale > 1.1f) fitToView()
            else {
                imageMatrix = Matrix(imageMatrix).apply { postScale(2.5f, 2.5f, e.x, e.y) }
                userScale = 2.5f
                constrainPan()
            }
            return true
        }
    })
    private var navigating = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { fitToView() }
    }

    fun fitToView() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val dx = (width - d.intrinsicWidth * scale) / 2f
        val dy = (height - d.intrinsicHeight * scale) / 2f
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        userScale = 1f
    }

    private fun constrainPan() {
        val d = drawable ?: return
        val bounds = android.graphics.RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> (width - bounds.width()) / 2f - bounds.left
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> (height - bounds.height()) / 2f - bounds.top
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        imageMatrix = Matrix(imageMatrix).apply { postTranslate(dx, dy) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) navigating = false
        if (event.pointerCount > 1) {
            if (!navigating) {
                activeStroke?.let { strokes.remove(it) }
                activeStroke = null
            }
            navigating = true
        }
        parent?.requestDisallowInterceptTouchEvent(tool != Tool.READ || userScale > 1.01f)
        if (navigating && userScale <= 1.01f && !scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (tool != Tool.READ && drawable != null && !navigating) {
            val inverse = Matrix()
            imageMatrix.invert(inverse)
            val point = floatArrayOf(event.x - paddingLeft, event.y - paddingTop)
            inverse.mapPoints(point)
            val d = drawable!!
            val x = point[0] / d.intrinsicWidth
            val y = point[1] / d.intrinsicHeight
            if (tool == Tool.TEXT) {
                if (event.actionMasked == MotionEvent.ACTION_UP && x in 0f..1f && y in 0f..1f) {
                    onTextRequested?.invoke(x, y)
                    performClick()
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (x !in 0f..1f || y !in 0f..1f) return true
                    activeStroke = Stroke(mutableListOf(PointF(x, y)))
                    strokes.add(activeStroke!!)
                }
                MotionEvent.ACTION_MOVE -> activeStroke?.points?.add(PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
                MotionEvent.ACTION_UP -> {
                    if (activeStroke != null) onMarkupChanged?.invoke()
                    activeStroke = null
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> { activeStroke?.let { strokes.remove(it) }; activeStroke = null }
            }
            invalidate()
            return true
        }
        scaleDetector.onTouchEvent(event)
        if (tool == Tool.READ) gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> if (mode == DRAG && !scaleDetector.isInProgress) {
                val dx = event.x - last.x
                val dy = event.y - last.y
                imageMatrix = Matrix(imageMatrix).apply { postTranslate(dx, dy) }
                constrainPan()
                last.set(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                last.set(event.getX(remaining), event.getY(remaining))
                mode = DRAG
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = NONE
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitToView() }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val newScale = (userScale * factor).coerceIn(minScale, maxScale)
            val applied = newScale / userScale
            userScale = newScale
            imageMatrix = Matrix(imageMatrix).apply {
                postScale(applied, applied, detector.focusX, detector.focusY)
            }
            constrainPan()
            mode = ZOOM
            return true
        }
    }

    companion object {
        fun drawStrokes(canvas: Canvas, strokes: List<Stroke>, width: Float, height: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.save()
            canvas.clipRect(0f, 0f, width, height)
            for (stroke in strokes) {
                if (stroke.points.isEmpty()) continue
                paint.color = Color.rgb(25, 65, 114)
                paint.strokeWidth = width * 0.0035f
                val first = stroke.points.first()
                if (stroke.text != null) {
                    paint.style = Paint.Style.FILL
                    paint.textSize = width * 0.028f
                    stroke.text.lines().forEachIndexed { line, text ->
                        canvas.drawText(text, first.x * width, first.y * height + line * paint.textSize * 1.3f, paint)
                    }
                    paint.style = Paint.Style.STROKE
                    continue
                }
                // Rebuild only the stroke that changed, rather than every path on every frame.
                synchronized(stroke) {
                    if (stroke.pathPoints != stroke.points.size || stroke.pathWidth != width || stroke.pathHeight != height) {
                        stroke.path.reset()
                        stroke.path.moveTo(first.x * width, first.y * height)
                        for (i in 1 until stroke.points.size) {
                            val point = stroke.points[i]
                            stroke.path.lineTo(point.x * width, point.y * height)
                        }
                        if (stroke.points.size == 1) stroke.path.lineTo(first.x * width + 0.1f, first.y * height)
                        stroke.pathPoints = stroke.points.size
                        stroke.pathWidth = width
                        stroke.pathHeight = height
                    }
                    canvas.drawPath(stroke.path, paint)
                }
            }
            canvas.restore()
        }
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
