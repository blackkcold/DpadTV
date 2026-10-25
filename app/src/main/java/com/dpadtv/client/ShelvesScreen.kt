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
    private val onOpenTitle: (String) -> Unit,
    private val onMore: (Shelf) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val accent = 0xFF38BDF8.toInt()
    private val panel = 0xFF1F2937.toInt()
    private val ink = 0xFFE5E7EB.toInt()
    private val muted = 0xFF94A3B8.toInt()

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        col.addView(header())
        Shelves.home.forEach { shelf ->
            col.addView(shelfSection(shelf))
        }
        return ScrollView(host).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0b1220"))
            addView(col)
        }
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
            setTextSize(21f)
            setTextColor(ink)
            setPadding(dp(10), 0, dp(14), 0)
        })
        row.addView(TextView(host).apply {
            text = "豆瓣榜单 · 合并货架"
            setTextSize(12.5f)
            setTextColor(muted)
        })
        return row
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
            setTextSize(18f)
            setTextColor(ink)
        })
        val countLabel = TextView(host).apply {
            text = "  · 加载中"
            setTextSize(12.5f)
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

        scope.launch {
            val groups = shelf.refs.map { ref -> ref to repo.fetch(ref) }
            val merged = Merger.merge(groups, shelf.size)
            strip.removeAllViews()
            if (merged.isEmpty()) {
                countLabel.text = "  · 暂时取不到（豆瓣接口可能被限流）"
                strip.addView(retryCell(shelf, strip, countLabel))
                return@launch
            }
            countLabel.text = "  · ${merged.size} 部"
            // 角标只用来"解释差异"：整行都来自同一个榜时，那个角标就是噪音（整屏 18 个 TOP250）
            val dominant = merged.groupingBy { it.badge }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            merged.forEach { item -> strip.addView(posterCell(item, dominant)) }
            strip.addView(endCell(shelf))
        }
        return box
    }

    private fun moreButton(shelf: Shelf): View = TextView(host).apply {
        text = "查看更多 ›"
        setTextSize(14f)
        setTextColor(muted)
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onMore(shelf) }
        setOnFocusChangeListener { v, focused -> v.background = ring(if (focused) accent else Color.TRANSPARENT) }
    }

    private fun posterCell(item: MovieItem, dominantBadge: String = ""): View {
        val cell = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            isFocusable = true
            isFocusableInTouchMode = true
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
        if (item.badge.isNotEmpty() && item.badge != dominantBadge) {
            frame.addView(TextView(host).apply {
                text = item.badge
                setTextSize(9.5f)
                setTextColor(0xFF04121B.toInt())
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = rounded(accent, dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(6); topMargin = dp(6) }
            })
        }
        cell.addView(frame)
        val label = TextView(host).apply {
            text = if (item.rating > 0) "${item.title}  ★${item.rating}" else item.title
            setTextSize(12.5f)
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
        setTextSize(13f)
        setTextColor(muted)
        isFocusable = true
        background = rounded(panel, dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(204)).apply { leftMargin = dp(8) }
        setOnClickListener { onMore(shelf) }
        setOnFocusChangeListener { v, f -> v.background = ring(if (f) accent else Color.TRANSPARENT, base = panel) }
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
