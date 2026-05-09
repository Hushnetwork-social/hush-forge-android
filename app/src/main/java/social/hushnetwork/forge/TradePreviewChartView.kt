package social.hushnetwork.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TradePreviewChartView(
    context: Context,
    private val candles: List<ForgeMarketCandle>,
    private val quoteAsset: String,
    private val tokenDecimals: Int,
    private val usdReference: QuoteAssetUsdReference? = null,
    private val showUsd: Boolean = false
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.US)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity
        val left = 8f * density
        val axisWidth = if (showUsd && usdReference != null) 64f else 54f
        val right = w - (axisWidth * density)
        val top = 14f * density
        val bottom = h - (24f * density)
        if (right <= left || bottom <= top) return

        val chartWidth = right - left
        val chartHeight = bottom - top
        val axisColor = Color.rgb(139, 149, 161)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(34, 255, 255, 255)
        repeat(4) { index ->
            val y = top + chartHeight * index / 3f
            canvas.drawLine(left, y, right, y, paint)
        }
        repeat(4) { index ->
            val x = left + chartWidth * index / 3f
            canvas.drawLine(x, top, x, bottom, paint)
        }

        if (candles.isEmpty()) {
            return
        }

        val visibleCandles = candles.takeLast(18)
        val values = visibleCandles.flatMap { listOf(it.open, it.high, it.low, it.close) }
        var minValue = values.minOrNull() ?: BigInteger.ZERO
        var maxValue = values.maxOrNull() ?: BigInteger.ONE
        if (minValue == maxValue) {
            val padding = (maxValue.abs() / BigInteger.valueOf(1000)).coerceAtLeast(BigInteger.ONE)
            minValue -= padding
            maxValue += padding
        }
        val valueRange = (maxValue - minValue).takeIf { it > BigInteger.ZERO } ?: BigInteger.ONE

        fun scaleY(value: BigInteger): Float {
            val ratio = (value - minValue).toDouble() / valueRange.toDouble()
            return bottom - (chartHeight * ratio.toFloat().coerceIn(0f, 1f))
        }

        paint.style = Paint.Style.FILL
        paint.textSize = 9f * scaledDensity
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.color = axisColor
        paint.textAlign = Paint.Align.LEFT
        repeat(4) { index ->
            val y = top + chartHeight * index / 3f
            val value = maxValue - (valueRange * BigInteger.valueOf(index.toLong()) / BigInteger.valueOf(3))
            canvas.drawText(formatAxisPrice(value), right + (6f * density), y + (3f * density), paint)
        }

        paint.textAlign = Paint.Align.LEFT
        drawTimeLabel(canvas, visibleCandles.first(), left, h - (6f * density), Paint.Align.LEFT)
        drawTimeLabel(canvas, visibleCandles[visibleCandles.size / 2], left + chartWidth / 2f, h - (6f * density), Paint.Align.CENTER)
        drawTimeLabel(canvas, visibleCandles.last(), right, h - (6f * density), Paint.Align.RIGHT)

        val candleWidth = (chartWidth / (visibleCandles.size.coerceAtLeast(8) * 3f)).coerceIn(5f, 12f)
        paint.strokeWidth = 3f
        visibleCandles.forEachIndexed { index, candle ->
            val t = if (visibleCandles.size == 1) 0.5f else index.toFloat() / (visibleCandles.size - 1).toFloat()
            val x = left + chartWidth * t
            val high = scaleY(candle.high)
            val low = scaleY(candle.low)
            val open = scaleY(candle.open)
            val close = scaleY(candle.close)
            val topBody = minOf(open, close)
            val bottomBody = maxOf(open, close)
            val up = candle.close >= candle.open
            paint.color = if (up) Color.rgb(31, 205, 132) else Color.rgb(255, 90, 47)
            canvas.drawLine(x, high, x, low, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(
                x - candleWidth,
                topBody,
                x + candleWidth,
                maxOf(bottomBody, topBody + 4f),
                4f,
                4f,
                paint
            )
            paint.style = Paint.Style.STROKE
        }

        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawTimeLabel(
        canvas: Canvas,
        candle: ForgeMarketCandle,
        x: Float,
        y: Float,
        align: Paint.Align
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(139, 149, 161)
        paint.textAlign = align
        canvas.drawText(timeFormatter.format(Date(candle.time * 1000L)), x, y, paint)
    }

    private fun formatAxisPrice(value: BigInteger): String {
        if (showUsd && usdReference != null) {
            val formatted = formatMarketPriceUsd(value, quoteAsset, tokenDecimals, usdReference)
            if (formatted != null) {
                return if (formatted.length <= 11) formatted else formatted.take(11)
            }
        }

        val quoteDecimals = if (quoteAsset.equals("GAS", ignoreCase = true)) 8 else 0
        val scale = (18 + quoteDecimals - tokenDecimals).coerceAtLeast(0)
        val formatted = formatTokenAmount(value, scale, maxFractionDigits = 6)
        return if (formatted.length <= 10) formatted else formatted.take(10)
    }
}
