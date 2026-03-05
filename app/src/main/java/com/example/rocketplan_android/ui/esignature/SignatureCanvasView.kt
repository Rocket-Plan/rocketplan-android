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
                hasDrawn = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
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

    fun clear() {
        paths.clear()
        path.reset()
        hasDrawn = false
        invalidate()
        onDrawListener?.invoke(true)
    }

    fun isEmpty(): Boolean = !hasDrawn

    fun toBase64Png(): String? {
        if (isEmpty()) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Transparent background — signature strokes only
        canvas.drawColor(Color.TRANSPARENT)
        draw(canvas)

        // Crop to the bounding box of the signature strokes for a tight fit
        val cropped = cropToContent(bitmap) ?: bitmap

        val stream = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
        val bytes = stream.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun cropToContent(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var top = h
        var bottom = 0
        var left = w
        var right = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (pixels[y * w + x].ushr(24) != 0) { // non-transparent pixel
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (top > bottom || left > right) return null

        // Add small padding
        val pad = 4
        top = (top - pad).coerceAtLeast(0)
        bottom = (bottom + pad).coerceAtMost(h - 1)
        left = (left - pad).coerceAtLeast(0)
        right = (right + pad).coerceAtMost(w - 1)

        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }
}
