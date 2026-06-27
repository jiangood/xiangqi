package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.*
import android.os.Vibrator
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.jiangood.xq.util.AppLog
import kotlin.math.abs

class UnifiedBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE,
        PROCESSING
    }

    private val density = resources.displayMetrics.density

    private var currentState: State = State.IDLE
    var onClick: (() -> Unit)? = null

    // 尺寸常量
    private val circleRadius = (28f * density)
    private val circleDiameter = circleRadius * 2

    // 画笔
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C19A6B")  // 象棋棋盘木色
        style = Paint.Style.FILL
    }
    private val circleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B6914")  // 深木色边框
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val circleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8DC")  // 象牙白/米色
        textSize = density * 24
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 长按拖拽状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var isDragging = false
    private var longPressRunnable: Runnable? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()  // 通常 500ms
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = circleDiameter.toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        // 绘制圆形按钮（木质底 + 边框）
        canvas.drawCircle(cx, cy, circleRadius - 3f, circlePaint)
        canvas.drawCircle(cx, cy, circleRadius - 3f, circleBorderPaint)

        // 中央文字
        val circleText = when (currentState) {
            State.PROCESSING -> "⏳"
            else -> "支"
        }
        canvas.drawText(circleText, cx, cy + circleTextPaint.textSize / 3, circleTextPaint)
    }

    fun updateState(state: State) {
        currentState = state
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWindowX = params.x
                initialWindowY = params.y
                isDragging = false

                // 启动长按检测
                longPressRunnable = Runnable {
                    isDragging = true
                    // 长按触发：震动反馈
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.vibrate(50)
                    AppLog.add("[悬浮窗触摸] 长按触发拖动模式")
                    invalidate()
                }
                postDelayed(longPressRunnable!!, longPressTimeout.toLong())

                AppLog.add("[悬浮窗触摸] DOWN: rawY=${event.rawY.toInt()}, viewY=${event.y.toInt()}, params.y=$initialWindowY")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 如果还没进入拖动模式，检查是否移动超过 touchSlop（取消长按）
                if (!isDragging && longPressRunnable != null) {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > scaledTouchSlop || abs(dy) > scaledTouchSlop) {
                        removeCallbacks(longPressRunnable!!)
                        longPressRunnable = null
                        AppLog.add("[悬浮窗触摸] 移动超过 touchSlop，取消长按检测")
                    }
                }

                // 仅在拖动模式下移动窗口
                if (isDragging) {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialWindowX + dx
                    params.y = initialWindowY + dy
                    clampPosition(params)
                    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                        ?.updateViewLayout(this, params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 清理长按检测
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null

                AppLog.add("[悬浮窗触摸] UP: event.y=${event.y.toInt()}, isDragging=$isDragging")

                if (!isDragging) {
                    // 点击：判断是否在圆形区域内
                    val hit = event.y >= 0 && event.y < circleDiameter
                    AppLog.add("[悬浮窗触摸] -> 点击检测: event.y=${event.y.toInt()} < circleDiameter=$circleDiameter = $hit")
                    if (hit) {
                        AppLog.add("[悬浮窗触摸] -> 触发 onClick!")
                        onClick?.invoke()
                    } else {
                        AppLog.add("[悬浮窗触摸] -> 不在圆形范围内")
                    }
                } else {
                    AppLog.add("[悬浮窗触摸] -> 拖动结束")
                    isDragging = false
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            ?: return
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        val maxX = metrics.widthPixels - width
        val maxY = metrics.heightPixels - height
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }
}