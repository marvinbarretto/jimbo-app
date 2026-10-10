package dev.marvinbarretto.jimbo.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Two concentric progress arcs — kcal outside, protein inside. Glance has no
 * canvas, so the ring is drawn to a bitmap and handed over as an image.
 */
object Ring {
    private const val TRACK = 0x33808080
    private const val KCAL = 0xFFE8A33D.toInt()
    private const val PROTEIN = 0xFF4DB6AC.toInt()

    fun render(kcalFraction: Float, proteinFraction: Float, sizePx: Int = 320): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = sizePx * 0.09f
        arc(canvas, sizePx, stroke / 2, stroke, kcalFraction, KCAL)
        arc(canvas, sizePx, stroke * 1.7f, stroke, proteinFraction, PROTEIN)
        return bitmap
    }

    private fun arc(canvas: Canvas, size: Int, inset: Float, stroke: Float, fraction: Float, color: Int) {
        val rect = RectF(inset, inset, size - inset, size - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = TRACK
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = color
            canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
        }
    }
}
