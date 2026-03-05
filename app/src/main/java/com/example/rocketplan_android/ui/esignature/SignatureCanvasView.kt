package com.example.rocketplan_android.ui.esignature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream

class SignatureCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val path = Path()
    private val paths = mutableListOf<Path>()
    private var hasDrawn = false
    private var onDrawListener: ((Boolean) -> Unit)? = null

    // Bounding box tracked during touch events — avoids expensive pixel scan
    private var boundsLeft = Float.MAX_VALUE
    private var boundsTop = Float.MAX_VALUE
    private var boundsRight = Float.NEGATIVE_INFINITY
    private var boundsBottom = Float.NEGATIVE_INFINITY

    fun setOnDrawListener(listener: (isEmpty: Boolean) -> Unit) {
        onDrawListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in paths) {
            canvas.drawPath(p, paint)
        }
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Prevent parent ScrollView from intercepting touch
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(event.x, event.y)
                updateBounds(event.x, event.y)
                hasDrawn = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                updateBounds(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                paths.add(Path(path))
                path.reset()
                invalidate()
                onDrawListener?.invoke(isEmpty())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateBounds(x: Float, y: Float) {
        if (x < boundsLeft) boundsLeft = x
        if (x > boundsRight) boundsRight = x
        if (y < boundsTop) boundsTop = y
        if (y > boundsBottom) boundsBottom = y
    }

    fun clear() {
        paths.clear()
        path.reset()
        hasDrawn = false
        boundsLeft = Float.MAX_VALUE
        boundsTop = Float.MAX_VALUE
        boundsRight = Float.NEGATIVE_INFINITY
        boundsBottom = Float.NEGATIVE_INFINITY
        invalidate()
        onDrawListener?.invoke(true)
    }

    fun isEmpty(): Boolean = !hasDrawn

    fun toBase64Png(): String? {
        if (isEmpty()) return null

        // Use tracked bounding box to render only the signature region
        val strokePad = (paint.strokeWidth / 2f + 4f).toInt()
        val left = (boundsLeft.toInt() - strokePad).coerceAtLeast(0)
        val top = (boundsTop.toInt() - strokePad).coerceAtLeast(0)
        val right = (boundsRight.toInt() + strokePad).coerceAtMost(width)
        val bottom = (boundsBottom.toInt() + strokePad).coerceAtMost(height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return null

        val bitmap = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Translate so strokes draw at correct positions within the cropped bitmap
        canvas.translate(-left.toFloat(), -top.toFloat())
        for (p in paths) {
            canvas.drawPath(p, paint)
        }
        canvas.drawPath(path, paint)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val bytes = stream.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
