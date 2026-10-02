package app.videowall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** يقصّ أي صورة إلى الشكل المطلوب بأبعاد محددة وحواف ناعمة. */
object IconShape {
    enum class Shape(val ar: String) {
        NONE("بلا"),
        CIRCLE("دائري"),
        ROUNDED("حواف مستديرة"),
        SQUARE("مربع"),
        SQUIRCLE("مربع دائري")
    }

    fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 1
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 1
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return b
    }

    /**
     * ينتج صورة مربعة بأبعاد [size] بكسل من [src] بعد قصّها لملء المربع (center-crop)
     * ثم تطبيق الشكل. [radiusFrac] نسبة استدارة الحواف (0..0.5) للشكل ROUNDED.
     */
    fun apply(src: Bitmap, size: Int, shape: Shape, radiusFrac: Float): Bitmap {
        val px = size.coerceAtLeast(1)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // قص المصدر إلى مربع من مركزه ليملأ الأبعاد المطلوبة
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val srcRect = Rect(left, top, left + side, top + side)
        val dstRect = Rect(0, 0, px, px)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, srcRect, dstRect, paint)

        if (shape == Shape.NONE) return out

        val path = when (shape) {
            Shape.CIRCLE -> Path().apply {
                addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW)
            }
            Shape.ROUNDED -> {
                val r = px * radiusFrac.coerceIn(0f, 0.5f)
                Path().apply { addRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, Path.Direction.CW) }
            }
            Shape.SQUARE -> Path().apply {
                addRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), Path.Direction.CW)
            }
            Shape.SQUIRCLE -> squircle(px.toFloat(), 4f)
            else -> Path().apply { addRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), Path.Direction.CW) }
        }

        // قناع بحواف ناعمة: يُبقي فقط الجزء داخل الشكل
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawPath(path, mask)
        return out
    }

    /** منحنى فائق (superellipse) يعطي شكلاً دائرياً مربعاً شبيهاً بأيقونات iOS. */
    private fun squircle(size: Float, n: Float): Path {
        val p = Path()
        val a = size / 2f
        val c = size / 2f
        val steps = 240
        val exp = (2.0 / n)
        for (i in 0..steps) {
            val t = (i.toDouble() / steps) * 2.0 * Math.PI
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = c + (a * sign(ct.toFloat()) * abs(ct.toFloat()).pow(exp.toFloat())).toDouble().toFloat()
            val y = c + (a * sign(st.toFloat()) * abs(st.toFloat()).pow(exp.toFloat())).toDouble().toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }
}
