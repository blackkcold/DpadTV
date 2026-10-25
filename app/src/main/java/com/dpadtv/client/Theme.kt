package com.dpadtv.client

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 唯一的设计令牌来源（Aerial 视觉方向 + 深/浅双极性）。
 *
 * 为什么要有它：M1–M4 的屏幕是分批写的，字号在 12f / 12.5f / 13.5f / 15f / 16f / 18f 之间飘，
 * 颜色也在 #0b1220 / #0b101a / #111827 之间飘。电视上这种"差半级"的漂移比手机更明显——
 * 3 米外看，只有统一的层级才读得出结构。
 *
 * 规则：**新代码不许再写魔法数字**，一律从这里取。
 *
 * ── 主题系统 ─────────────────────────────────────────────────────────
 * 令牌分三个域，混用会让浅色主题在"压在内容图上"的元素上塌掉：
 *
 *   surfaces  跟随主题：bg / glass / hairline / ink / muted / accent / onAccent
 *   onImage   恒定（两套主题共用）：幕布、压在海报上的字、播放器控制条、焦点环 halo
 *   status    语义色，两套明度各一版
 *
 * 调用方仍写 `Theme.INK`：所有颜色都是 getter，读的是当前 [palette]，
 * 所以既有引用一行都不用改。切换主题 = [bind] + 重建当前界面。
 */
object Theme {

    // ---------- 调色板 ----------

    /** 不可变的调色板快照：只在 [bind] 时整体替换，界面上不会读到半新半旧的值。 */
    data class Palette(
        val isLight: Boolean,
        val bg: Int,
        val bgDeep: Int,
        val glass1: Int,
        val glass2: Int,
        val hairline: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val danger: Int,
        val posterBg: Int,
        /** 焦点环内圈的 halo：对向色，保证环在任意亮度的海报上都看得见 */
        val ringHalo: Int,
        /** 浅色下靠投影造层级（"提亮"失效），深色恒为 0 */
        val cardShadow: Float,
        /** hero 幕布从图过渡到页面底的带宽，dp */
        val heroFeather: Int,
        // 状态角标：空心描边 + 状态色，两套明度
        val stWatching: Int,
        val stWatched: Int,
        val stWish: Int,
        val stDropped: Int,
    )

    /**
     * 深色（Aerial 正式值）：中性近黑，让海报成为屏上唯一的颜色来源。
     * 对比度实测：ink/bg 18.57、muted/bg 7.33、accent/bg 11.46。
     */
    val DARK = Palette(
        isLight = false,
        bg = 0xFF07090C.toInt(),
        bgDeep = 0xFF030406.toInt(),
        glass1 = 0x0FFFFFFF,
        glass2 = 0x1AFFFFFF,
        hairline = 0x1FFFFFFF,
        ink = 0xFFF5F7FA.toInt(),
        muted = 0x9FF5F7FA.toInt(),
        accent = 0xFFA7C5FF.toInt(),
        onAccent = 0xFF0A1220.toInt(),
        danger = 0xFFFF8A80.toInt(),
        posterBg = 0xFF14161A.toInt(),
        ringHalo = 0x99000000.toInt(),
        cardShadow = 0f,
        heroFeather = 140,
        stWatching = 0xFF34D399.toInt(),
        stWatched = 0xFFCBD5E1.toInt(),
        stWish = 0xFFFBBF24.toInt(),
        stDropped = 0xFFF87171.toInt(),
    )

    /**
     * 浅色：底色用 #F2F3F5 而不是纯白（纯白 + 海报 = 眩光，3 米外刺眼）。
     * 对比度实测：ink/bg 17.52、muted/bg 6.12（muted 必须 66% 黑：62% 时在玻璃二档上
     * 只有 4.48，差 0.02 不达 AA）、accent/bg 5.34（候选 #3A6FE0 只有 4.18，已否决）。
     */
    val LIGHT = Palette(
        isLight = true,
        bg = 0xFFF2F3F5.toInt(),
        bgDeep = 0xFFE8E9EB.toInt(),
        glass1 = 0x0A000000,
        glass2 = 0x14000000,
        hairline = 0x1F000000,
        ink = 0xFF0B0D10.toInt(),
        muted = 0xA80B0D10.toInt(),
        accent = 0xFF2C5FC4.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        danger = 0xFFB3261E.toInt(),
        posterBg = 0xFFDADCE0.toInt(),
        ringHalo = 0xEBFFFFFF.toInt(),
        cardShadow = 6f,
        heroFeather = 220,
        stWatching = 0xFF0F7B4F.toInt(),
        stWatched = 0xFF4B5563.toInt(),
        stWish = 0xFF8A5800.toInt(),
        stDropped = 0xFFB3261E.toInt(),
    )

    @Volatile
    private var current: Palette = DARK

    fun palette(): Palette = current

    fun bind(isLight: Boolean) {
        current = if (isLight) LIGHT else DARK
    }

    val isLight: Boolean get() = current.isLight

    // ---------- 颜色（旧 API 名全部保留，调用方零改动） ----------
    val BG get() = current.bg
    val BG_DEEP get() = current.bgDeep
    /** 旧名：面板一档 → 玻璃一档（白 6% / 黑 4%） */
    val SURFACE get() = current.glass1
    /** 旧名：面板二档 → 玻璃二档（白 10% / 黑 8%） */
    val PANEL get() = current.glass2
    val GLASS_1 get() = current.glass1
    val GLASS_2 get() = current.glass2
    val HAIRLINE get() = current.hairline
    val INK get() = current.ink
    val MUTED get() = current.muted
    val ACCENT get() = current.accent
    val ACCENT_INK get() = current.onAccent
    val DANGER get() = current.danger
    val POSTER_BG get() = current.posterBg

    // ---------- onImage：恒定，不跟随主题 ----------
    /** 压在内容图/视频上的文字。浅色主题下也是白的 —— 它下面是暗幕，不是页面底。 */
    val ON_IMAGE_INK = 0xFFFFFFFF.toInt()
    val ON_IMAGE_MUTED = 0xC7FFFFFF.toInt()
    /** 播放器控制条：永远深色玻璃压视频。浅色主题不做白玻璃，否则在亮场景里消失。 */
    val PLAYER_BAR = 0xB3000000.toInt()
    val PLAYER_TRACK = 0x3DFFFFFF
    val PLAYER_PROGRESS = 0xFFA7C5FF.toInt()
    val SCRIM_SOLID = 0xB3000000.toInt()
    val RING_HALO get() = current.ringHalo
    val CARD_SHADOW get() = current.cardShadow
    val HERO_FEATHER get() = current.heroFeather

    // ---------- 状态色（语义色，两套明度） ----------
    val ST_WATCHING get() = current.stWatching
    val ST_WATCHED get() = current.stWatched
    val ST_WISH get() = current.stWish
    val ST_DROPPED get() = current.stDropped

    // ---------- 字号 ----------
    // Aerial 阶梯：40 / 20 / 15 / 13（+ 10 角标）。旧值只有这四级的变体，别再引入第五级。
    const val HERO = 40f         // 详情页片名、首页 hero 标题
    const val DISPLAY = 20f      // 页面标题（Aerial 的"标题小、内容大"）
    const val BRAND = 20f        // 字标
    const val TITLE = 20f        // 区块标题 / 卡片标题
    const val BODY = 15f         // 正文、按钮
    const val INPUT = 15f        // 输入框
    const val LIST = 15f         // 列表行
    const val CAPTION = 13f      // 元信息、计数（LABEL）
    const val LABEL = 13f        // 大写小字标签
    const val MICRO = 10f        // 角标

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
    const val FOCUS_SCALE = 1.05f     // 电视上位移会抖，只放大 5%
    const val FOCUS_STROKE = 3
    const val ANIM = 160L             // 所有交互动效统一 160ms
    const val HERO_ANIM = 220L        // hero 换图是氛围动效，允许长一点

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

    /**
     * 玻璃：主题极性的镜像。
     * 深色靠"提亮"（白 6% / 10%），浅色靠"压暗"（黑 4% / 8%）；第三层开始必糊，只到二档。
     */
    fun glass(ctx: Context, radius: Int = RADIUS_L, level2: Boolean = false): GradientDrawable =
        rounded(ctx, if (level2) GLASS_2 else GLASS_1, HAIRLINE, radius, 1)

    /**
     * 顶部幕布：压在图上，两套主题一致 —— 否则浅色下白字就没底了。
     * 渐变范围由承载它的 View 高度决定（不要 setSize，否则背景会被裁成 0 宽）。
     */
    fun scrimTop(solid: Int = SCRIM_SOLID): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(solid, 0x00000000))

    /** 底部幕布：从透明渐变到页面底，让 hero 没有硬边。 */
    fun scrimBottom(to: Int = BG_DEEP): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, to))

    /** 行左右渐变，吃掉"半张海报"。浅色主题要渐变到页面底色，不能用黑。 */
    fun edgeFade(fromStart: Boolean = true): GradientDrawable {
        val edge = (BG and 0x00FFFFFF) or (0xFF shl 24)
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (fromStart) intArrayOf(edge, 0x00000000) else intArrayOf(0x00000000, edge),
        )
    }

    /**
     * 焦点环：**双环**。
     *
     * 单色环在极端海报上必失明 —— 实测旧的 #38BDF8 压亮海报只有 1.28:1，
     * 早前方案里的 #A7C5FF 更差（1.04:1，等于隐形）。因为环画在**内容图**上，
     * 对比度基准不是主题背景，所以换个主题色解决不了。
     *
     * 结构（从外到内）：accent 2dp → halo 2dp → 内容。
     * halo 取对向色：深色主题黑 40%（亮海报上托底），浅色主题白 85%（暗海报上托底）。
     */
    fun focusRing(
        ctx: Context,
        radius: Int = RADIUS,
        base: Int = Color.TRANSPARENT,
        accent: Int = ACCENT,
    ): android.graphics.drawable.Drawable {
        val gap = dp(ctx, 2)
        val wRing = dp(ctx, 2)
        val outer = GradientDrawable().apply {
            setColor(base)
            cornerRadius = dp(ctx, radius).toFloat()
            setStroke(wRing, accent)
        }
        val inner = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(ctx, radius).toFloat()
            setStroke(wRing, RING_HALO)
        }
        return LayerDrawable(arrayOf(outer, InsetDrawable(inner, gap + wRing)))
    }

    /** 兼容旧签名：单条 accent 环（无 halo）。新代码请用 [focusRing]。 */
    fun oldRing(ctx: Context, color: Int, base: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            setColor(base)
            cornerRadius = dp(ctx, RADIUS).toFloat()
            if (color != Color.TRANSPARENT) setStroke(dp(ctx, FOCUS_STROKE), color)
        }
        return InsetDrawable(g, dp(ctx, 2))
    }

    // ---------- 复合控件 ----------

    /**
     * 胶囊按钮：导航、chip、筛选、设置项都用它，保证"可点的东西长得一样"。
     * 形态按 Aerial：常态是 hairline 描边（不铺实色），选中是玻璃二档 + 强调色描边，
     * 聚焦只加环不改底 —— 电视上一排实色胶囊会把画面压死。
     */
    fun pill(ctx: Context, label: String, selected: Boolean = false,
             onFocus: (View, Boolean) -> Unit = { _, _ -> }): TextView = TextView(ctx).apply {
        text = label
        setTextSize(BODY)
        setTextColor(if (selected) INK else MUTED)
        isFocusable = true
        isFocusableInTouchMode = false
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
        fun bg(focused: Boolean): GradientDrawable = rounded(
            ctx,
            if (focused || selected) GLASS_2 else Color.TRANSPARENT,
            when {
                focused -> ACCENT
                selected -> ACCENT
                else -> HAIRLINE
            },
            RADIUS,
            if (focused) 2 else 1,
        )
        background = bg(false)
        setOnFocusChangeListener { v, f ->
            v.background = bg(f)
            (v as TextView).setTextColor(if (f || selected) INK else MUTED)
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
