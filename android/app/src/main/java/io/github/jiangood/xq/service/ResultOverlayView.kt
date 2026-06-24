package io.github.jiangood.xq.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jiangood.xq.MainActivity

class ResultOverlayView(
    context: Context,
    move: String,
    private val onDismiss: (View) -> Unit
) : FrameLayout(context) {

    init {
        setBackgroundColor(0x99000000.toInt())
        val density = context.resources.displayMetrics.density
        val cardRadius = (16 * density)

        val card = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding((24 * density).toInt(), (24 * density).toInt(),
                       (24 * density).toInt(), (24 * density).toInt())
            elevation = 8 * density

            val closeBtn = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { onDismiss(this@ResultOverlayView) }
            }
            val closeLp = LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            ).apply { gravity = Gravity.END }

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                val title = TextView(context).apply {
                    text = "推荐走法"
                    textSize = 14f
                    setTextColor(Color.GRAY)
                }

                val moveText = TextView(context).apply {
                    text = move
                    textSize = 36f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                }

                val detailBtn = Button(context).apply {
                    text = "查看详情"
                    setOnClickListener {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("from_floating", true)
                            putExtra("fen", "")
                            putExtra("move", move)
                        }
                        context.startActivity(intent)
                        onDismiss(this@ResultOverlayView)
                    }
                }

                addView(title)
                addView(moveText, LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (16 * density).toInt(), 0, (16 * density).toInt()) })
                addView(detailBtn)
            }

            addView(closeBtn, closeLp)
            addView(inner, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }

        val margin = (32 * density).toInt()
        addView(card, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(margin, 0, margin, 0)
            gravity = Gravity.CENTER
        })
    }
}
