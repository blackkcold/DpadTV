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
    private val accent = Theme.ACCENT
    private val panel = Theme.PANEL
    private val ink = Theme.INK
    private val muted = Theme.MUTED

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    private val pool = LinkedHashMap<String, MovieItem>()
    private var recommendView: View? = null
    private var page: LinearLayout? = null
    private var libraryView: View? = null

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        page = col
        col.addView(header())
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
            setBackgroundColor(Color.parseColor("#0b1220"))
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
            setPadding(0, dp(Theme.GAP_XS), 0, dp(14))
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = "为你推荐"
            setTextSize(Theme.TITLE)
            setTextColor(ink)
        })
        head.addView(TextView(host).apply {
            text = "  · 按你的片单和播放记录挑的"
            setTextSize(Theme.CAPTION)
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
            setPadding(0, dp(6), 0, dp(14))
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = "我的片单"
            setTextSize(Theme.TITLE)
            setTextColor(ink)
        })
        val counts = store.counts().filterValues { it > 0 }
            .entries.joinToString(" · ") { "${it.key.label} ${it.value}" }
        head.addView(TextView(host).apply {
            text = if (counts.isEmpty()) "  · 长按海报加入" else "  · $counts"
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
        })
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
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

    private fun header(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        row.addView(TextView(host).apply {
            text = "▶"
            setTextSize(15f)
            setTextColor(0xFF04121B.toInt())
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
        row.addView(TextView(host).apply {
            text = "今晚看点什么"
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
        })
        // 顶部导航：M1 换掉旧首页时把入口一起弄丢了，电视上没有地址栏，必须有常驻入口
        row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        nav.forEach { (label, action) ->
            row.addView(navPill(label, action))
        }
        return row
    }

    private fun navPill(label: String, action: () -> Unit): View = TextView(host).apply {
        text = label
        setTextSize(Theme.BODY)
        setTextColor(ink)
        isFocusable = true
        // 触摸模式下点一下就直接触发，不要"先聚焦再点一次"
        isFocusableInTouchMode = false
        setPadding(dp(13), dp(6), dp(13), dp(6))
        setOnClickListener { action() }
        setOnFocusChangeListener { v, f -> v.background = pill(if (f) accent else Color.TRANSPARENT, if (f) accent else Color.TRANSPARENT) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(4) }
    }

    private class Row(val shelf: Shelf, val strip: LinearLayout, val count: TextView)

    private val rows = ArrayList<Row>()

    private fun render(row: Row, merged: List<MovieItem>) {
        row.strip.removeAllViews()
        if (merged.isEmpty()) {
            row.count.text = "  · 暂时取不到（稍后自动重试）"
            row.strip.addView(retryCell(row.shelf, row.strip, row.count))
            return
        }
        row.count.text = "  · ${merged.size} 部"
        val dominant = merged.groupingBy { it.badge }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        merged.forEach { row.strip.addView(posterCell(it, dominant)) }
        row.strip.addView(endCell(row.shelf))
    }

    private fun shelfSection(shelf: Shelf): View {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(14))
        }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(host).apply {
            text = shelf.title
            setTextSize(Theme.TITLE)
            setTextColor(ink)
        })
        val countLabel = TextView(host).apply {
            text = "  · 加载中"
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
        }
        head.addView(countLabel)
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
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
        box.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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

    /** 状态色：和「来源角标」区分开，否则用户分不清"收藏"是状态还是榜单 */
    private fun stateColor(label: String): Int = when (label) {
        "收藏" -> accent
        "在看" -> 0xFF34D399.toInt()
        "看过" -> 0xFFCBD5E1.toInt()
        "想看" -> 0xFFFBBF24.toInt()
        else -> 0xFFF87171.toInt()
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
            background = rounded(Color.parseColor("#1a2233"), dp(8))
        }
        val img = ImageView(host).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1a2233"))
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
                    background = Theme.rounded(host, 0x99000000.toInt(), c, 6, 1)
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
            frame.background = ring(if (focused) accent else Color.TRANSPARENT, base = Color.parseColor("#1a2233"))
            cell.scaleX = if (focused) 1.06f else 1f
            cell.scaleY = if (focused) 1.06f else 1f
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
            background = rounded(Color.parseColor("#16202e"), dp(8))
        })
        addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(10)).apply { topMargin = dp(8) }
            background = rounded(Color.parseColor("#16202e"), dp(3))
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

    private fun ring(color: Int, base: Int = Color.TRANSPARENT): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            setColor(base)
            cornerRadius = dp(8).toFloat()
            if (color != Color.TRANSPARENT) setStroke(dp(3), color)
        }
        return InsetDrawable(g, dp(2))
    }
}
