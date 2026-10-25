package com.dpadtv.client

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 唯一的设计令牌来源。
 *
 * 为什么要有它：M1–M4 的屏幕是分批写的，字号在 12f / 12.5f / 13.5f / 15f / 16f / 18f 之间飘，
 * 颜色也在 #0b1220 / #0b101a / #111827 之间飘。电视上这种"差半级"的漂移比手机更明显——
 * 3 米外看，只有统一的层级才读得出结构。
 *
 * 规则：**新代码不许再写魔法数字**，一律从这里取。
 */
object Theme {

    // ---------- 颜色 ----------
    val BG = 0xFF0B1220.toInt()
    val SURFACE = 0xFF111827.toInt()
    val PANEL = 0xFF1F2937.toInt()
    val INK = 0xFFE5E7EB.toInt()
    val MUTED = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF38BDF8.toInt()
    val ACCENT_INK = 0xFF04121B.toInt()
    val DANGER = 0xFFF87171.toInt()
    val POSTER_BG = 0xFF1A2233.toInt()

    // ---------- 字号 ----------
    const val DISPLAY = 26f      // 页面主标题
    const val BRAND = 22f        // 字标
    const val TITLE = 18f        // 区块标题 / 行标题
    const val BODY = 15f         // 正文、按钮
    const val INPUT = 18f        // 输入框
    const val LIST = 16f         // 列表行
    const val CAPTION = 12.5f    // 状态、计数、副标题
    const val MICRO = 10.5f      // 角标

    // ---------- 间距（dp） ----------
    const val GAP_XS = 4
    const val GAP_S = 8
    const val GAP_M = 12
    const val GAP_L = 16
    const val PAGE_X = 48        // 页面左右安全边（电视 overscan）
    const val PAGE_Y = 20

    // ---------- 尺寸 ----------
    const val RADIUS = 8
    const val RADIUS_L = 12
    const val POSTER_W = 146
    const val POSTER_H = 204
    const val FOCUS_SCALE = 1.06f
    const val FOCUS_STROKE = 3

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ---------- 基础形状 ----------
    fun fill(ctx: Context, color: Int, radius: Int = RADIUS): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radius).toFloat()
        }

    fun rounded(ctx: Context, fillColor: Int, strokeColor: Int = Color.TRANSPARENT,
                radius: Int = RADIUS, strokeWidth: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(ctx, radius).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(ctx, strokeWidth), strokeColor)
        }

    /** 焦点环：不改填充、不位移，只描边（电视上位移会抖） */
    fun focusRing(ctx: Context, color: Int, base: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            setColor(base)
            cornerRadius = dp(ctx, RADIUS).toFloat()
            if (color != Color.TRANSPARENT) setStroke(dp(ctx, FOCUS_STROKE), color)
        }
        return InsetDrawable(g, dp(ctx, 2))
    }

    // ---------- 复合控件 ----------

    /** 胶囊按钮：导航、chip、筛选都用它，保证"可点的东西长得一样" */
    fun pill(ctx: Context, label: String, selected: Boolean = false,
             onFocus: (View, Boolean) -> Unit = { _, _ -> }): TextView = TextView(ctx).apply {
        text = label
        setTextSize(BODY)
        setTextColor(if (selected) ACCENT_INK else INK)
        isFocusable = true
        isFocusableInTouchMode = false
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
        background = rounded(ctx, if (selected) ACCENT else PANEL)
        setOnFocusChangeListener { v, f ->
            v.background = rounded(
                ctx,
                if (selected) ACCENT else PANEL,
                if (f) ACCENT else Color.TRANSPARENT,
            )
            onFocus(v, f)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(ctx, GAP_S) }
    }

    /** 行标题：左边标题 + 右边状态/动作，首页每一行都用同一个节奏 */
    fun rowHeader(ctx: Context, title: String, sub: String = ""): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, GAP_S))
            addView(TextView(ctx).apply {
                text = title
                setTextSize(TITLE)
                setTextColor(INK)
            })
            if (sub.isNotEmpty()) addView(TextView(ctx).apply {
                text = "  · $sub"
                setTextSize(CAPTION)
                setTextColor(MUTED)
            })
        }

    /** 空态：给"下一步做什么"，不留白屏 */
    fun emptyState(ctx: Context, title: String, hint: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 70), 0, 0)
            addView(TextView(ctx).apply {
                text = title
                setTextSize(TITLE)
                setTextColor(INK)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = hint
                setTextSize(BODY)
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, 0)
            })
        }
}
