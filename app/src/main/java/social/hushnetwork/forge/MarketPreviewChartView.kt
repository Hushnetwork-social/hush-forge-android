package social.hushnetwork.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class MarketPreviewChartView(
    context: Context,
    private val progressBps: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = w * 0.06f
        val right = w * 0.94f
        val top = h * 0.14f
        val bottom = h * 0.84f
        val chartWidth = right - left
        val chartHeight = bottom - top
        val currentT = progressBps.coerceIn(0, 10_000) / 10_000f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(35, 255, 255, 255)
        repeat(4) { index ->
            val y = top + chartHeight * index / 3f
            canvas.drawLine(left, y, right, y, paint)
        }

        fun curveY(t: Float): Float {
            val eased = t * t * (1.22f - 0.22f * t)
            return bottom - chartHeight * eased.coerceIn(0.0f, 1.0f)
        }

        val linePath = Path()
        val fillPath = Path()
        val points = 42
        for (index in 0 until points) {
            val t = index.toFloat() / (points - 1).toFloat()
            val x = left + chartWidth * t
            val y = curveY(t)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(right, bottom)
        fillPath.close()

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(62, 255, 90, 47)
        canvas.drawPath(fillPath, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(255, 90, 47)
        canvas.drawPath(linePath, paint)

        val currentX = left + chartWidth * currentT
        val currentY = curveY(currentT)
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.BUTT
        paint.pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
        paint.color = Color.argb(125, 255, 90, 47)
        canvas.drawLine(left, top, left, bottom, paint)
        canvas.drawLine(currentX, currentY, currentX, bottom, paint)
        paint.color = Color.argb(150, 31, 205, 132)
        canvas.drawLine(right, top, right, bottom, paint)
        paint.pathEffect = null

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 90, 47)
        canvas.drawCircle(left, bottom, 8f, paint)
        canvas.drawCircle(currentX, currentY, 8f, paint)
        paint.color = Color.rgb(31, 205, 132)
        canvas.drawCircle(right, curveY(1f), 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
    }
}
