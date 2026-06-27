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
        PROCESSING,
        SUCCESS,
        FAILED
    }

    private val density = resources.displayMetrics.density

    private var currentState: State = State.IDLE
    private var successMove: String? = null
    private var failedError: String? = null
    var onClick: (() -> Unit)? = null

    private val resetRunnable = Runnable { updateState(State.IDLE) }

    // 尺寸常量
    private val circleRadius = (28f * density)
    private val circleDiameter = circleRadius * 2
    private val gap = (4f * density)
    private val capsuleHeight = (28f * density)
    private val capsulePaddingH = (16f * density)
    private val capsulePaddingV = (4f * density)

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
    private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B6914")  // 深木色
        style = Paint.Style.FILL
    }
    private val capsuleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = density * 14
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
        // 宽度取圆形直径和胶囊宽度的最大值
        val capsuleText = getStateText()
        capsuleTextPaint.getTextBounds(capsuleText, 0, capsuleText.length, Rect())
        val capsuleWidth = Rect().apply { capsuleTextPaint.getTextBounds(capsuleText, 0, capsuleText.length, this) }.width() + capsulePaddingH * 2
        val width = maxOf(circleDiameter.toInt(), capsuleWidth.toInt())
        val height = (circleDiameter + gap + capsuleHeight).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cyCircle = circleRadius

        // 1. 绘制上半圆形按钮（木质底 + 边框）
        canvas.drawCircle(cx, cyCircle, circleRadius - 3f, circlePaint)
        canvas.drawCircle(cx, cyCircle, circleRadius - 3f, circleBorderPaint)
        val circleText = when (currentState) {
            State.PROCESSING -> "⏳"
            State.SUCCESS -> "✓"
            State.FAILED -> "✗"
            else -> "支"
        }
        canvas.drawText(circleText, cx, cyCircle + circleTextPaint.textSize / 3, circleTextPaint)

        // 2. 绘制下半胶囊状态条
        val capsuleTop = circleDiameter + gap
        val capsuleBottom = capsuleTop + capsuleHeight
        val capsuleLeft = (width - capsuleWidth).toFloat() / 2
        val capsuleRight = capsuleLeft + capsuleWidth

        val capsuleRect = RectF(capsuleLeft, capsuleTop, capsuleRight, capsuleBottom)
        canvas.drawRoundRect(capsuleRect, capsuleHeight / 2, capsuleHeight / 2, capsuleBgPaint)

        // 3. 绘制状态文本
        val text = getStateText()
        val textColor = when (currentState) {
            State.SUCCESS -> Color.parseColor("#8FCE00")  // 翠绿
            State.FAILED -> Color.parseColor("#E53935")   // 红色
            else -> Color.parseColor("#FFF8DC")           // 象牙白
        }
        capsuleTextPaint.color = textColor
        canvas.drawText(text, cx, capsuleTop + capsuleHeight / 2 + capsuleTextPaint.textSize / 3, capsuleTextPaint)
    }

    private val capsuleWidth: Float
        get() {
            val text = getStateText()
            val bounds = Rect()
            capsuleTextPaint.getTextBounds(text, 0, text.length, bounds)
            return bounds.width() + capsulePaddingH * 2
        }

    private fun getStateText(): String {
        return when (currentState) {
            State.IDLE -> "就绪"
            State.PROCESSING -> "处理中"
            State.SUCCESS -> successMove ?: "成功"
            State.FAILED -> failedError ?: "失败"
        }
    }

    fun updateState(state: State, move: String? = null, error: String? = null) {
        removeCallbacks(resetRunnable)
        currentState = state
        successMove = move
        failedError = error
        requestLayout()  // 宽度可能变化
        invalidate()
        if (state == State.SUCCESS || state == State.FAILED) {
            postDelayed(resetRunnable, 3000L)
        }
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
                    invalidate()  // 可选：视觉反馈
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