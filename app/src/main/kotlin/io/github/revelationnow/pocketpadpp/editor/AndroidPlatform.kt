package io.github.revelationnow.pocketpadpp.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import io.github.revelationnow.pocketpadpp.scintilla.SciFont
import io.github.revelationnow.pocketpadpp.scintilla.SciPlatform
import io.github.revelationnow.pocketpadpp.scintilla.SciSurface

/** A Scintilla font backed by an Android [Paint]. */
internal class AndroidFont(face: String, sizePx: Float, weight: Int, italic: Boolean) : SciFont {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val metrics: Paint.FontMetrics

    init {
        // Unknown family names fall back to the system default typeface.
        val family = Typeface.create(face.ifEmpty { "monospace" }, Typeface.NORMAL)
        paint.typeface = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, weight.coerceIn(1, 1000), italic)
        } else {
            val style = (if (weight >= 600) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
            Typeface.create(family, style)
        }
        paint.textSize = sizePx
        metrics = paint.fontMetrics
    }

    override fun ascent(): Float = -metrics.ascent
    override fun descent(): Float = metrics.descent
    override fun internalLeading(): Float = 0f
    override fun averageCharWidth(): Float = paint.measureText("n")
    override fun width(text: String): Float = paint.measureText(text)

    override fun advances(text: String, out: FloatArray) {
        paint.getTextWidths(text, out)
    }
}

/**
 * A Scintilla drawing surface backed by an Android [Canvas]: either the view's canvas during
 * a draw pass, or an off-screen bitmap for the small patterns Scintilla caches.
 */
internal class AndroidSurface private constructor(var canvas: Canvas?, private val bitmap: Bitmap?) : SciSurface {
    // Rectangles are filled without anti-aliasing so that neighbouring runs of text background meet without seams.
    private val fill = Paint()
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val source = Rect()
    private var patternShader: BitmapShader? = null

    companion object {
        /** A surface whose [canvas] is supplied for the duration of each draw pass. */
        fun forView(): AndroidSurface = AndroidSurface(null, null)

        fun pixmap(width: Int, height: Int): AndroidSurface {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            return AndroidSurface(Canvas(bitmap), bitmap)
        }
    }

    private fun shader(): BitmapShader? {
        val bmp = bitmap ?: return null
        return patternShader ?: BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { patternShader = it }
    }

    private fun fillAndStroke(fillArgb: Int, strokeArgb: Int, width: Float, draw: (Paint) -> Unit) {
        if (fillArgb != 0) {
            shape.style = Paint.Style.FILL
            shape.color = fillArgb
            draw(shape)
        }
        if (strokeArgb != 0 && width > 0f) {
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = width
            shape.color = strokeArgb
            draw(shape)
        }
    }

    override fun release() {
        canvas = null
        patternShader = null
        bitmap?.recycle()
    }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, argb: Int) {
        val c = canvas ?: return
        fill.color = argb
        c.drawRect(l, t, r, b, fill)
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, argb: Int, width: Float) {
        val c = canvas ?: return
        fillAndStroke(0, argb, width) { c.drawRect(l, t, r, b, it) }
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, argb: Int, width: Float) {
        val c = canvas ?: return
        fillAndStroke(0, argb, width) { c.drawLine(x1, y1, x2, y2, it) }
    }

    override fun polygon(xy: FloatArray, points: Int, fillArgb: Int, strokeArgb: Int, width: Float, closed: Boolean) {
        val c = canvas ?: return
        if (points < 2) return
        path.rewind()
        path.moveTo(xy[0], xy[1])
        for (i in 1 until points) {
            path.lineTo(xy[i * 2], xy[i * 2 + 1])
        }
        if (closed) path.close()
        fillAndStroke(fillArgb, strokeArgb, width) { c.drawPath(path, it) }
    }

    override fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, fillArgb: Int, strokeArgb: Int, width: Float) {
        val c = canvas ?: return
        rect.set(l, t, r, b)
        fillAndStroke(fillArgb, strokeArgb, width) { c.drawRoundRect(rect, radius, radius, it) }
    }

    override fun ellipse(l: Float, t: Float, r: Float, b: Float, fillArgb: Int, strokeArgb: Int, width: Float) {
        val c = canvas ?: return
        rect.set(l, t, r, b)
        fillAndStroke(fillArgb, strokeArgb, width) { c.drawOval(rect, it) }
    }

    override fun gradientRect(l: Float, t: Float, r: Float, b: Float, argb: IntArray, stops: FloatArray, horizontal: Boolean) {
        val c = canvas ?: return
        if (argb.size < 2) {
            if (argb.isNotEmpty()) fillRect(l, t, r, b, argb[0])
            return
        }
        fill.shader = LinearGradient(l, t, if (horizontal) r else l, if (horizontal) t else b, argb, stops, Shader.TileMode.CLAMP)
        fill.color = -0x1000000
        c.drawRect(l, t, r, b, fill)
        fill.shader = null
    }

    override fun image(l: Float, t: Float, r: Float, b: Float, width: Int, height: Int, argb: IntArray) {
        val c = canvas ?: return
        val image = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        rect.set(l, t, r, b)
        c.drawBitmap(image, null, rect, null)
        image.recycle()
    }

    override fun copy(l: Float, t: Float, r: Float, b: Float, fromX: Float, fromY: Float, source: SciSurface) {
        val c = canvas ?: return
        val bmp = (source as AndroidSurface).bitmap ?: return
        this.source.set(fromX.toInt(), fromY.toInt(), (fromX + (r - l)).toInt(), (fromY + (b - t)).toInt())
        rect.set(l, t, r, b)
        c.drawBitmap(bmp, this.source, rect, null)
    }

    override fun fillPattern(l: Float, t: Float, r: Float, b: Float, pattern: SciSurface) {
        val c = canvas ?: return
        val patternShader = (pattern as AndroidSurface).shader() ?: return
        fill.shader = patternShader
        fill.color = -0x1000000
        c.drawRect(l, t, r, b, fill)
        fill.shader = null
    }

    override fun text(font: SciFont, x: Float, yBase: Float, text: String, argb: Int) {
        val c = canvas ?: return
        val paint = (font as AndroidFont).paint
        paint.color = argb
        c.drawText(text, x, yBase, paint)
    }

    override fun pushClip(l: Float, t: Float, r: Float, b: Float) {
        val c = canvas ?: return
        c.save()
        c.clipRect(l, t, r, b)
    }

    override fun popClip() {
        canvas?.restore()
    }
}

internal object AndroidPlatform : SciPlatform {
    override fun createFont(face: String, sizePx: Float, weight: Int, italic: Boolean): SciFont =
        AndroidFont(face, sizePx, weight, italic)

    override fun createPixmap(width: Int, height: Int): SciSurface = AndroidSurface.pixmap(width, height)
}
