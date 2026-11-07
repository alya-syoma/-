package ua.svitlo.kr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

object Colors {
    const val ON = 0xFF4CD964.toInt()
    const val OFF = 0xFFFF5A5F.toInt()
    const val MAYBE = 0xFFFFB340.toInt()
    const val UNKNOWN = 0x33FFFFFF

    fun of(p: Power) = when (p) {
        Power.ON -> ON
        Power.OFF -> OFF
        Power.MAYBE -> MAYBE
        Power.UNKNOWN -> UNKNOWN
    }
}

/** Draws a 24h bar (48 half-hour slots) with a white "now" marker. */
object BarRenderer {
    fun render(day: Day, now: Long, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = height / 2f
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW) }
        c.clipPath(clip)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = width / 48f
        day.slots.forEachIndexed { i, s ->
            p.color = Colors.of(s)
            c.drawRect(i * w, 0f, (i + 1) * w + 0.5f, height.toFloat(), p)
        }
        val span = 48 * SLOT_MS
        if (now >= day.start && now < day.start + span) {
            val x = (now - day.start).toFloat() / span * width
            p.color = Color.WHITE
            c.drawRect(x - 2f, 0f, x + 2f, height.toFloat(), p)
        }
        return bmp
    }
}
