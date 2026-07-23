package ir.dehaat.kiosk

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

// دایره‌ی کوچیکِ نشون‌دهنده‌ی پیشرفتِ دانلود: از لبه‌ی صفحه با انیمیشن بیرون میاد،
// درصد رو نشون می‌ده، آخرِ کار یه تیک نشون می‌ده و بعد از اون MainActivity برش می‌گردونه تو لبه.
class DownloadProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val ringStrokeWidth = 3.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStrokeWidth
        color = Color.parseColor("#33FFFFFF")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ringStrokeWidth
        color = Color.parseColor("#8B5CF6")
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6241243")
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
    }

    private val arcRect = RectF()
    private var animatedProgress = 0f
    private var progressAnimator: ValueAnimator? = null
    private var showCheck = false
    private var checkProgress = 0f
    private var checkAnimator: ValueAnimator? = null

    fun reset() {
        progressAnimator?.cancel()
        checkAnimator?.cancel()
        animatedProgress = 0f
        showCheck = false
        checkProgress = 0f
        invalidate()
    }

    fun setProgress(percent: Int) {
        if (showCheck) return
        val target = percent.coerceIn(0, 100) / 100f
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun showCheckmark() {
        showCheck = true
        checkAnimator?.cancel()
        checkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                checkProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - ringStrokeWidth
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, bgPaint)
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        if (showCheck) {
            canvas.drawArc(arcRect, -90f, 360f, false, progressPaint)
            drawCheckmark(canvas, cx, cy, radius)
        } else {
            canvas.drawArc(arcRect, -90f, 360f * animatedProgress, false, progressPaint)
            val percentText = "${(animatedProgress * 100).toInt()}٪"
            val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(percentText, cx, textY, textPaint)
        }
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val p = checkProgress
        val s = radius * 0.5f
        val x1 = cx - s * 0.55f; val y1 = cy + s * 0.05f
        val x2 = cx - s * 0.12f; val y2 = cy + s * 0.42f
        val x3 = cx + s * 0.62f; val y3 = cy - s * 0.38f
        val path = Path()
        if (p <= 0.5f) {
            val t = (p / 0.5f).coerceIn(0f, 1f)
            path.moveTo(x1, y1)
            path.lineTo(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        } else {
            val t = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            path.lineTo(x2 + (x3 - x2) * t, y2 + (y3 - y2) * t)
        }
        canvas.drawPath(path, checkPaint)
    }
}
