package com.dpadtv.client

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 首页：合并货架。
 *
 * 为什么不是一个大网格、也不是几十行榜单：
 * 电视一屏只扫得动 3–4 行，把 28 个榜平铺等于把「选择成本」全推给用户。
 * 这里一行 = 3–4 个榜合并去重（Merger），想换口味点行尾「查看更多」。
 */
class ShelvesScreen(
    private val host: Activity,
    private val loader: ImageLoader,
    private val repo: CollectionRepo,
    private val store: LibraryStore,
    private val onOpenTitle: (String) -> Unit,
    private val onMore: (Shelf) -> Unit,
    private val onLibrary: () -> Unit,
    private val nav: List<Pair<String, () -> Unit>> = emptyList(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // getter，不是快照：换主题后已经建好的行也要立刻跟着变
    private val accent get() = Theme.ACCENT
    private val panel get() = Theme.PANEL
    private val ink get() = Theme.INK
    private val muted get() = Theme.MUTED

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    private val pool = LinkedHashMap<String, MovieItem>()
    private var recommendView: View? = null
    private var page: LinearLayout? = null
    private var libraryView: View? = null

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            // 通栏：hero 要贴到屏幕边，横向留白交给每一行自己
            setPadding(0, 0, 0, dp(18))
        }
        page = col
        col.addView(heroBlock())
        if (store.list(null, 1).isNotEmpty()) {
            libraryView = libraryRow()
            col.addView(libraryView)
        }
        Shelves.home.forEach { shelf ->
            col.addView(shelfSection(shelf))
        }
        // 单条串行流水线：一行拉完再拉下一行（限速在仓库内部），冷启动也不会打出并发风暴
        scope.launch {
            for (row in rows) {
                val groups = row.shelf.refs.map { ref -> ref to repo.fetch(ref) }
                var merged = Merger.merge(groups, row.shelf.size)
                if (row.shelf.id == "tv") merged = merged.filterNot { Merger.isAnimation(it.title) }
                render(row, merged)
                merged.forEach { pool.putIfAbsent(Merger.normalize(it.title), it) }
            }
            buildRecommendRow()
        }
        val root = ScrollView(host).apply {
            isFillViewport = true
            setBackgroundColor(Theme.BG)
            addView(col)
        }
        root.postDelayed({
            rows.firstOrNull()?.strip?.getChildAt(0)?.requestFocus()
        }, 260)
        return root
    }

    /**
     * 「为你推荐」：信号不足（< 8）整行不出现 —— 一行平庸的推荐比没有更伤。
     * 候选池用首页已经拉到的全部条目，不额外发请求。
     */
    private fun buildRecommendRow() {
        val p = page ?: return
        if (recommendView != null) return
        if (!Recommend.enough(store.signals())) return
        val owned = store.list(null, 500).map { Merger.normalize(it.title) }.toHashSet()
        val cands = pool.values.filter { Merger.normalize(it.title) !in owned }
        if (cands.size < 6) return
        val taste = Recommend.taste(store.list(null, 500))
        val picked = Recommend.mmr(cands, taste, 14)
        if (picked.isEmpty()) return

        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(Theme.GAP_XS), dp(48), dp(14))
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = "为你推荐"
            setTextSize(Theme.LABEL)
            setTextColor(muted)
            letterSpacing = 0.08f
        })
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(host).apply {
            text = "按你的片单和播放记录挑的"
            setTextSize(Theme.LABEL)
            setTextColor(muted)
        })
        box.addView(head)
        val strip = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        picked.forEach { strip.addView(posterCell(it)) }
        box.addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        recommendView = box
        // 放在「我的片单」之后、公共榜之前：个性化内容应该一眼看到，但不能盖住自己的片单
        p.addView(box, minOf(p.childCount, 2))
    }

    /**
     * 第一次收藏后在首页立刻长出「我的片单」行。
     * 只做「插入」不做「重建」：重建会把焦点扔掉，电视上焦点乱跳比多等一次更难受。
     */
    private fun ensureLibraryRow() {
        val p = page ?: return
        if (libraryView != null || store.list(null, 1).isEmpty()) return
        val row = libraryRow()
        libraryView = row
        p.addView(row, 1)
    }

    /** 首页第一行永远是自己攒的片单 —— 比任何公共榜都更该占这个位置 */
    private fun libraryRow(): View {
        val entries = store.list(null, 14)
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(6), dp(48), dp(14))
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = "我的片单"
            setTextSize(Theme.LABEL)
            setTextColor(muted)
            letterSpacing = 0.08f
        })
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        val counts = store.counts().filterValues { it > 0 }
            .entries.joinToString("  ·  ") { "${it.key.label} ${it.value}" }
        head.addView(TextView(host).apply {
            text = if (counts.isEmpty()) "长按海报加入" else counts
            setTextSize(Theme.LABEL)
            setTextColor(muted)
            setPadding(0, 0, dp(14), 0)
        })
        head.addView(TextView(host).apply {
            text = "查看全部 ›"
            setTextSize(14f)
            setTextColor(muted)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { onLibrary() }
            setOnFocusChangeListener { v, f -> v.background = ring(if (f) accent else Color.TRANSPARENT) }
        })
        box.addView(head)

        val strip = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        entries.forEach { e -> strip.addView(posterCell(e.toItem(), dominantBadge = "", stateBadge = true)) }
        if (entries.size < 4) {
            strip.addView(TextView(host).apply {
                text = "长按\n海报\n加入"
                gravity = Gravity.CENTER
                setTextSize(Theme.CAPTION)
                setTextColor(muted)
                background = rounded(panel, dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(204)).apply { leftMargin = dp(8) }
            })
        }
        box.addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        return box
    }

    /** 顶部导航：压在 hero 图上，ghost 形态（未选中 62% 白，聚焦才亮一条 hairline 胶囊） */
    private fun header(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(20), dp(36), dp(10))
        }
        row.addView(TextView(host).apply {
            text = "▶"
            setTextSize(15f)
            setTextColor(Theme.ACCENT_INK)
            gravity = Gravity.CENTER
            background = rounded(accent, dp(9))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        row.addView(TextView(host).apply {
            text = "DpadTV Pro"
            setTextSize(Theme.BRAND)
            setTextColor(ink)
            setPadding(dp(10), 0, dp(14), 0)
        })
        // 顶部导航：M1 换掉旧首页时把入口一起弄丢了，电视上没有地址栏，必须有常驻入口
        row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        nav.forEach { (label, action) ->
            row.addView(navPill(label, action))
        }
        return row
    }

    // ---------- hero：顶部 38% 幕布，跟着焦点换图 ----------

    private var heroA: ImageView? = null
    private var heroB: ImageView? = null
    private var heroFrontIsA = true
    private var heroTitle: TextView? = null
    private var heroMeta: TextView? = null

    /**
     * 首页 hero：整块是"当前焦点内容的图像"，不是容器。
     *
     * 图占满 38% 屏高，底部用 [Theme.scrimBottom] 过渡到页面底色（浅色主题下过渡带更长，
     * 否则亮底上会出现一条脏灰边），导航和标题都压在图上的暗幕里。
     */
    private fun heroBlock(): View {
        val h = (host.resources.displayMetrics.heightPixels * 0.40f).toInt()
        val frame = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            // 图没到之前也必须是"一块幕布"，不能是黑洞（网络慢时用户先看到的就是这块）
            background = Theme.scrimTop(if (Theme.isLight) 0xFFD8DADE.toInt() else 0xFF16191F.toInt())
        }
        heroA = ImageView(host).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        heroB = (heroA as ImageView).let {
            ImageView(host).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0f
            }
        }
        frame.addView(heroA)
        frame.addView(heroB)
        fun layer(h: Int, bg: android.graphics.drawable.Drawable, gravity: Int = Gravity.TOP) {
            frame.addView(View(host).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h), gravity)
                background = bg
            })
        }
        // 顶部压暗给导航；底部 220dp 压成暗幕 —— **浅色主题也必须是暗的**，
        // 白字压亮图等于没有字。最后 56dp 再淡入页面底色，接缝才不硬。
        layer(150, Theme.scrimTop(0xCC000000.toInt()))
        frame.addView(header(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val textBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(8), dp(48), dp(44))
            // 暗幕贴在文字块上：白字必须有底，浅色主题也一样；
            // 最后 56dp 由下面的页面色渐变吃掉接缝
            background = Theme.scrimBottom(0xE6000000.toInt())
        }
        heroTitle = TextView(host).apply {
            setTextSize(Theme.HERO)
            setTextColor(Theme.ON_IMAGE_INK)
            maxLines = 1
        }
        heroMeta = TextView(host).apply {
            setTextSize(Theme.LABEL)
            setTextColor(Theme.ON_IMAGE_MUTED)
            setPadding(0, dp(2), 0, 0)
        }
        textBox.addView(heroTitle)
        textBox.addView(heroMeta)
        frame.addView(textBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        layer(56, Theme.scrimBottom(Theme.BG), Gravity.BOTTOM)
        return frame
    }

    /** 焦点换了内容就把幕布换掉：220ms 交叉淡入（唯一允许超过 160ms 的动效） */
    private fun setHero(item: MovieItem) {
        heroTitle?.text = item.title
        heroMeta?.text = buildString {
            if (item.rating > 0) append("★ %.1f".format(item.rating))
            if (item.badge.isNotEmpty()) {
                if (isNotEmpty()) append("   ·   ")
                append(item.badge)
            }
        }
        if (item.cover.isEmpty()) return
        val front = if (heroFrontIsA) heroA else heroB
        val back = if (heroFrontIsA) heroB else heroA
        if (front?.tag == item.cover || back == null) return
        back.tag = item.cover
        loader.load(item.cover, dp(960), dp(540)) { bmp ->
            if (back.tag != item.cover || bmp == null) return@load
            back.setImageBitmap(bmp)
            back.animate().alpha(1f).setDuration(Theme.HERO_ANIM).start()
            front?.animate()?.alpha(0f)?.setDuration(Theme.HERO_ANIM)?.start()
            heroFrontIsA = !heroFrontIsA
        }
    }

    private fun navPill(label: String, action: () -> Unit): View = TextView(host).apply {
        text = label
        setTextSize(Theme.LABEL)
        setTextColor(0xD1FFFFFF.toInt())   // 压在图上：固定 82% 白，不跟主题
        isFocusable = true
        // 触摸模式下点一下就直接触发，不要"先聚焦再点一次"
        isFocusableInTouchMode = false
        setPadding(dp(13), dp(6), dp(13), dp(6))
        setOnClickListener { action() }
        setOnFocusChangeListener { v, f ->
            v.background = if (f) Theme.rounded(host, Theme.GLASS_1, Theme.HAIRLINE, 8, 1) else null
            (v as TextView).setTextColor(if (f) Theme.ON_IMAGE_INK else Theme.ON_IMAGE_MUTED)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(4) }
    }

    /**
     * 行外壳：左右各 80dp 渐变，吃掉"半张海报"。
     * 只在真正还能滚的那一侧显示 —— 停在行首时左边有渐变会把第一张海报的边啃掉。
     */
    private fun rowScroller(scroller: HorizontalScrollView): View {
        val left = View(host).apply {
            layoutParams = FrameLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
            background = Theme.edgeFade(true)
            visibility = View.GONE
        }
        val right = View(host).apply {
            layoutParams = FrameLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            background = Theme.edgeFade(false)
        }
        scroller.setOnScrollChangeListener { _, x, _, _, _ ->
            val inner = scroller.getChildAt(0) ?: return@setOnScrollChangeListener
            left.visibility = if (x > 8) View.VISIBLE else View.GONE
            right.visibility = if (x + scroller.width < inner.width - 8) View.VISIBLE else View.GONE
        }
        return FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(scroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(left)
            addView(right)
        }
    }

    private class Row(val shelf: Shelf, val strip: LinearLayout, val count: TextView)

    private val rows = ArrayList<Row>()

    private fun render(row: Row, merged: List<MovieItem>) {
        row.strip.removeAllViews()
        if (merged.isEmpty()) {
            row.count.text = "稍后自动重试"
            row.strip.addView(retryCell(row.shelf, row.strip, row.count))
            return
        }
        row.count.text = "${merged.size} 部"
        val dominant = merged.groupingBy { it.badge }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        merged.forEach { row.strip.addView(posterCell(it, dominant)) }
        if (heroTitle?.text.isNullOrEmpty()) merged.firstOrNull()?.let { setHero(it) }
        row.strip.addView(endCell(row.shelf))
    }

    private fun shelfSection(shelf: Shelf): View {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(6), dp(48), dp(14))
        }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = shelf.title
            setTextSize(Theme.LABEL)
            setTextColor(muted)
            letterSpacing = 0.08f
        })
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        val countLabel = TextView(host).apply {
            text = "加载中"
            setTextSize(Theme.LABEL)
            setTextColor(muted)
            setPadding(0, 0, dp(14), 0)
        }
        head.addView(countLabel)
        head.addView(moreButton(shelf))
        box.addView(head)

        val strip = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        val scroller = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        box.addView(rowScroller(scroller))

        // 骨架屏：先占位，避免"加载中…"的文字闪烁
        repeat(7) { strip.addView(skeletonCell()) }
        rows.add(Row(shelf, strip, countLabel))
        return box
    }

    private fun moreButton(shelf: Shelf): View = TextView(host).apply {
        text = "查看更多 ›"
        setTextSize(14f)
        setTextColor(muted)
        isFocusable = true
        isFocusableInTouchMode = false
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onMore(shelf) }
        setOnFocusChangeListener { v, focused -> v.background = ring(if (focused) accent else Color.TRANSPARENT) }
    }

    /**
     * 状态色：和「来源角标」区分开，否则用户分不清"收藏"是状态还是榜单。
     * 走令牌两套明度 —— 浅色下 #34D399 在 #F2F3F5 上只有 1.73:1，等于看不见。
     */
    private fun stateColor(label: String): Int = when (label) {
        "收藏" -> accent
        "在看" -> Theme.ST_WATCHING
        "看过" -> Theme.ST_WATCHED
        "想看" -> Theme.ST_WISH
        else -> Theme.ST_DROPPED
    }

    private fun posterCell(item: MovieItem, dominantBadge: String = "", stateBadge: Boolean = false): View {
        val cell = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            isFocusable = true
            isFocusableInTouchMode = false
        }
        val frame = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(146), dp(204))
            background = rounded(Theme.POSTER_BG, dp(8))
        }
        val img = ImageView(host).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Theme.POSTER_BG)
        }
        frame.addView(img)
        if (item.badge.isNotEmpty() && (stateBadge || item.badge != dominantBadge)) {
            frame.addView(TextView(host).apply {
                text = item.badge
                setTextSize(Theme.MICRO)
                if (stateBadge) {
                    // 状态：描边标签（空心）+ 状态色，和"来源榜"的实心蓝标签一眼区分
                    val c = stateColor(item.badge)
                    setTextColor(c)
                    background = Theme.rounded(host, Theme.GLASS_2, c, 6, 1)
                } else {
                    setTextColor(Theme.ACCENT_INK)
                    background = rounded(accent, dp(4))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(6); topMargin = dp(6) }
            })
        }
        cell.addView(frame)
        val label = TextView(host).apply {
            text = if (item.rating > 0) "${item.title}  ★${item.rating}" else item.title
            setTextSize(Theme.CAPTION)
            setTextColor(ink)
            maxLines = 1
            setPadding(dp(2), dp(5), 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(146), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cell.addView(label)

        cell.setOnFocusChangeListener { _, focused ->
            frame.background = ring(if (focused) accent else Color.TRANSPARENT, base = Theme.POSTER_BG)
            val s = if (focused) Theme.FOCUS_SCALE else 1f
            cell.animate().scaleX(s).scaleY(s).setDuration(Theme.ANIM).start()
            if (focused) setHero(item)
        }
        cell.setOnClickListener { onOpenTitle(item.title) }
        PosterActions.attach(host, cell, item, store) { ensureLibraryRow() }

        if (item.cover.isNotEmpty()) {
            img.tag = item.cover
            loader.load(item.cover, dp(160), dp(220)) { bmp ->
                if (img.tag == item.cover && bmp != null) img.setImageBitmap(bmp)
            }
        }
        return cell
    }

    private fun skeletonCell(): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(146), dp(204))
            background = rounded(Theme.POSTER_BG, dp(8))
        })
        addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(10)).apply { topMargin = dp(8) }
            background = rounded(Theme.POSTER_BG, dp(3))
        })
    }

    private fun retryCell(shelf: Shelf, strip: LinearLayout, countLabel: TextView): View = TextView(host).apply {
        text = "点这里重试"
        setTextSize(14f)
        setTextColor(accent)
        isFocusable = true
        setPadding(dp(16), dp(90), dp(16), dp(90))
        setOnClickListener {
            strip.removeAllViews()
            repeat(7) { strip.addView(skeletonCell()) }
            countLabel.text = "  · 重新加载中"
            scope.launch {
                val groups = shelf.refs.map { ref -> ref to repo.fetch(ref, ttlMs = 0) }
                val merged = Merger.merge(groups, shelf.size)
                strip.removeAllViews()
                if (merged.isEmpty()) { strip.addView(retryCell(shelf, strip, countLabel)); return@launch }
                countLabel.text = "  · ${merged.size} 部"
                val dom = merged.groupingBy { it.badge }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                merged.forEach { strip.addView(posterCell(it, dom)) }
                strip.addView(endCell(shelf))
            }
        }
    }

    private fun endCell(shelf: Shelf): View = TextView(host).apply {
        text = "全部\n›"
        gravity = Gravity.CENTER
        setTextSize(Theme.CAPTION)
        setTextColor(muted)
        isFocusable = true
        background = rounded(panel, dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(204)).apply { leftMargin = dp(8) }
        setOnClickListener { onMore(shelf) }
        setOnFocusChangeListener { v, f -> v.background = ring(if (f) accent else Color.TRANSPARENT, base = panel) }
    }

    private fun pill(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(8).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    /** 焦点环：走 Theme 的**双环**（强调色 + 对向 halo），单环在亮海报上会隐形 */
    private fun ring(color: Int, base: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable =
        if (color == Color.TRANSPARENT) InsetDrawable(rounded(base, dp(8)), dp(2))
        else Theme.focusRing(host, Theme.RADIUS, base = base)
}
