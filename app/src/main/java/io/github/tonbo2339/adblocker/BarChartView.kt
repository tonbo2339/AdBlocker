package io.github.tonbo2339.adblocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** 日ごとの棒グラフ。灰色の棒が問い合わせ、その上に重ねた緑の棒がブロック。 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Bar(val label: String, val total: Long, val highlighted: Long)

    private val density = resources.displayMetrics.density
    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.separator) }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.sys_green) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.label_secondary)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    var bars: List<Bar> = emptyList()
        set(value) {
            field = value
            contentDescription = value.joinToString { "${it.label}: ${it.highlighted} / ${it.total}" }
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val labelHeight = labelPaint.textSize + 6 * density
        val chartHeight = height - paddingTop - paddingBottom - labelHeight
        val slot = (width - paddingLeft - paddingRight).toFloat() / bars.size
        val barWidth = (slot * 0.5f).coerceAtMost(28 * density)
        val radius = 4 * density
        val max = bars.maxOf { it.total }.coerceAtLeast(1)
        val bottom = paddingTop + chartHeight
        for ((i, bar) in bars.withIndex()) {
            val center = paddingLeft + slot * (i + 0.5f)
            fun drawBar(value: Long, paint: Paint) {
                if (value <= 0) return
                // 0 でない日は最低でも少し見えるようにする
                val h = (chartHeight * value / max).coerceAtLeast(2 * density)
                rect.set(center - barWidth / 2, bottom - h, center + barWidth / 2, bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            drawBar(bar.total, totalPaint)
            drawBar(bar.highlighted, highlightPaint)
            canvas.drawText(bar.label, center, height - paddingBottom.toFloat() - 2 * density, labelPaint)
        }
    }
}
