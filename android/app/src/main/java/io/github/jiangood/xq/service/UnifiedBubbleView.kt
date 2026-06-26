package io.github.jiangood.xq.service

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class UnifiedBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE("就绪"),
        PROCESSING("处理中"),
        SUCCESS,
        FAILED
    }

    private var currentState: State = State.IDLE
    private var successMove: String? = null
    private var failedError: String? = null
    var onClick: (() -> Unit = {}

    // 尺寸常量
    private val circleRadius = (28f * density)
    private val circleDiameter = circleRadius * 2
    private val gap = (4f * density)
    private val capsuleHeight = (28f * density)
    private val capsulePaddingH = (16f * density)
    private val capsulePaddingV = (4f * density)

    // 画笔
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val circleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = density * 24
        textAlign = Paint.Align.CENTER
    }
    private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()  // 白色 85% 不透明
        style = Paint.Style.FILL
    }
    private val capsuleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = density * 14
        textAlign = Paint.Align.CENTER
    }

    // 拖拽状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var isDragging = false

    private val density = resources.displayMetrics.density

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

        // 1. 绘制上半圆形按钮
        canvas.drawCircle(cx, cyCircle, circleRadius - 3f, circlePaint)
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
            State.SUCCESS -> Color.parseColor("#2E7D32")
            State.FAILED -> Color.parseColor("#C62828")
            else -> Color.BLACK
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
        currentState = state
        successMove = move
        failedError = error
        requestLayout()  // 宽度可能变化
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams as? WindowManager.LayoutParams ?: return false
        val x = event.rawX
        val y = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = x
                initialTouchY = y
                initialWindowX = params.x
                initialWindowY = params.y
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (x - initialTouchX).toInt()
                val dy = (y - initialTouchY).toInt()
                params.x = initialWindowX + dx
                params.y = initialWindowY + dy
                clampPosition(params)
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.updateViewLayout(this, params)
                isDragging = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // 判断点击区域：圆形按钮范围内
                    val touchY = y - params.y
                    if (touchY < circleDiameter) {
                        onClick()
                    }
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