package social.hushnetwork.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Native port of the web FORGE deterministic token identicon.
 *
 * The seed is the Neo N3 contract hash. The generated icon is a mirrored 5x5
 * grid with color and shape derived from the hash bytes.
 */
class TokenIdenticonView(
    context: Context,
    private val contractHash: String
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bytes = hashToBytes(contractHash)
    private val cellColor = hslToColor(
        hue = (((bytes[0] shl 8) or bytes[1]) % 360).toFloat(),
        saturation = (55 + bytes[2] % 30) / 100f,
        lightness = (50 + bytes[3] % 20) / 100f
    )
    private val backgroundColor = hslToColor(
        hue = (((bytes[0] shl 8) or bytes[1]) % 360).toFloat(),
        saturation = (55 + bytes[2] % 30) / 100f,
        lightness = (10 + bytes[4] % 10) / 100f
    )
    private val cells = buildCells(bytes)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val radius = size / 2f
        val pad = size * 0.1f
        val inner = size - pad * 2f
        val cell = inner / 5f
        val rx = cell * 0.18f

        paint.color = backgroundColor
        canvas.drawCircle(left + radius, top + radius, radius, paint)

        paint.color = cellColor
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                if (!cells[row][col]) continue
                val x = left + pad + col * cell
                val y = top + pad + row * cell
                canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), rx, rx, paint)
            }
        }
    }

    private companion object {
        private fun hashToBytes(contractHash: String): IntArray {
            val hex = contractHash
                .removePrefix("0x")
                .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                .padEnd(40, '0')
                .take(40)

            return IntArray(20) { index ->
                hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: 0
            }
        }

        private fun buildCells(bytes: IntArray): Array<BooleanArray> {
            val gridBits = ((bytes.getOrElse(5) { 0 } shl 8) or bytes.getOrElse(6) { 0 })
            var bit = 15
            return Array(5) {
                val row = BooleanArray(5)
                for (col in 0 until 3) {
                    val on = ((gridBits shr bit) and 1) == 1
                    row[col] = on
                    row[4 - col] = on
                    bit -= 1
                }
                row
            }
        }

        private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Int {
            val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
            val hPrime = (hue % 360f) / 60f
            val x = c * (1f - kotlin.math.abs(hPrime % 2f - 1f))
            val (r1, g1, b1) = when {
                hPrime < 1f -> Triple(c, x, 0f)
                hPrime < 2f -> Triple(x, c, 0f)
                hPrime < 3f -> Triple(0f, c, x)
                hPrime < 4f -> Triple(0f, x, c)
                hPrime < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = lightness - c / 2f
            return android.graphics.Color.rgb(
                ((r1 + m) * 255f).roundToColorChannel(),
                ((g1 + m) * 255f).roundToColorChannel(),
                ((b1 + m) * 255f).roundToColorChannel()
            )
        }

        private fun Float.roundToColorChannel(): Int =
            max(0, min(255, (this + 0.5f).toInt()))
    }
}
