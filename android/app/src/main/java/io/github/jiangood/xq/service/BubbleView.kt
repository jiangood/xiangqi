package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class BubbleView(context: Context) : View(context) {
    private var startDx = 0f
    private var startDy = 0f
    private var isDragging = false
    var onClick: (() -> Unit)? = null

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.resources.displayMetrics.density * 24
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (context.resources.displayMetrics.density * 56).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 6f
        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawText("帅", cx, cy + textPaint.textSize / 3, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams ?: return false
        val x = event.rawX
        val y = event.rawY
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startDx = x - params.x
                startDy = y - params.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = (x - startDx).toInt()
                params.y = (y - startDy).toInt()
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.updateViewLayout(this, params)
                isDragging = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    onClick?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
