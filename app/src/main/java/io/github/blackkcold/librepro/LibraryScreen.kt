package io.github.blackkcold.librepro

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 我的片单：收藏 / 想看 / 在看 / 看过 / 弃剧。
 *
 * 形态跟「查看更多」一致（chip + 网格），因为这是用户已经熟悉的第二种浏览姿势；
 * 唯一差别是这里的 chip 是四个抽屉，而不是豆瓣标签。
 */
class LibraryScreen(
    private val host: Activity,
    private val loader: ImageLoader,
    private val store: LibraryStore,
    private val onOpenTitle: (MovieItem) -> Unit,
    private val onBack: () -> Unit,
) {

    // getter，不是快照：换主题后已经建好的界面也要立刻跟着变
    private val accent get() = Theme.ACCENT
    private val ink get() = Theme.INK
    private val muted get() = Theme.MUTED
    private val panel get() = Theme.PANEL

    private var filter: LibState? = null
    private val items = ArrayList<LibEntry>()

    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var chipsRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(20), dp(48), dp(14))
        }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(host).apply { text = "我的片单"; setTextSize(Theme.DISPLAY); setTextColor(ink) })
        status = TextView(host).apply { setTextSize(Theme.CAPTION); setTextColor(muted) }
        head.addView(status)
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(host).apply {
            text = "‹ 返回首页"
            setTextSize(Theme.BODY)
            setTextColor(ink)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(13), dp(6), dp(13), dp(6))
            setOnClickListener { onBack() }
            setOnFocusChangeListener { v, f -> v.background = pill(if (f) accent else Color.TRANSPARENT, if (f) accent else Color.TRANSPARENT) }
        })
        head.addView(TextView(host).apply {
            text = "长按 OK 改状态"
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
        })
        col.addView(head)

        chipsRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        empty = TextView(host).apply {
            text = "片单还是空的。\n在任何海报上长按 OK → 选「收藏 / 想看 / 在看 / 看过 / 弃剧」，下次就在这里。"
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, 0)
            visibility = View.GONE
        }
        col.addView(empty)

        adapter = cellAdapter()
        grid = GridView(host).apply {
            numColumns = 6
            horizontalSpacing = dp(12)
            verticalSpacing = dp(10)
            setPadding(0, dp(10), 0, 0)
            isFocusable = true
            // 网格本身要在触摸模式下也能拿焦点：子项已不可聚焦，否则进页后焦点会落到「返回首页」上
            isFocusableInTouchMode = true
            // 子项不再聚焦：焦点落在列表上，由 selector 画双环（和详情页选集同一套）
            setSelector(android.graphics.drawable.InsetDrawable(Theme.focusRing(host, Theme.RADIUS), dp(2)))
            this.adapter = this@LibraryScreen.adapter
            setOnItemClickListener { _, _, pos, _ ->
                items.getOrNull(pos)?.let { onOpenTitle(it.toItem()) }
            }
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderChips()
        reload()
        // 子项不可聚焦后，必须显式把焦点交给网格
        grid.postDelayed({ if (!grid.hasFocus()) grid.requestFocus() }, 260)
        return col
    }

    private fun renderChips() {
        chipsRow.removeAllViews()
        val counts = store.counts()
        val total = counts.values.sum()
        chipsRow.addView(chip("全部 $total", filter == null) { filter = null; renderChips(); reload() })
        LibState.entries.forEach { st ->
            chipsRow.addView(chip("${st.label} ${counts[st] ?: 0}", filter == st) {
                filter = if (filter == st) null else st
                renderChips()
                reload()
            })
        }
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit): View = TextView(host).apply {
        text = label
        setTextSize(Theme.CAPTION)
        setTextColor(if (on) Theme.ACCENT_INK else ink)
        isFocusable = true
        isFocusableInTouchMode = false
        setPadding(dp(15), dp(8), dp(15), dp(8))
        background = pill(if (on) accent else panel)
        setOnClickListener { click() }
        setOnFocusChangeListener { v, f ->
            v.background = pill(if (on) accent else panel, if (f) accent else Color.TRANSPARENT)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    private fun reload() {
        items.clear()
        items.addAll(store.list(filter))
        status.text = "  · ${items.size} 部"
        grid.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun cellAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(4), dp(5), dp(4))
                // 与详情页选集网格统一：子项不抢焦点，由 GridView 持焦 + 双环 selector
                isFocusable = false
                isFocusableInTouchMode = false
                addView(ImageView(host).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(146), dp(204))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Theme.POSTER_BG)
                })
                addView(TextView(host).apply {
                    setTextSize(Theme.CAPTION); setTextColor(ink); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(5), 0, 0)
                })
                addView(TextView(host).apply {
                    setTextSize(Theme.MICRO); setTextColor(accent); maxLines = 1
                    setPadding(dp(2), dp(1), 0, 0)
                })
            }
            val e = items[position]
            val img = cell.getChildAt(0) as ImageView
            (cell.getChildAt(1) as TextView).text = e.title
            (cell.getChildAt(2) as TextView).text = e.state.label
            img.tag = e.cover
            img.setImageBitmap(null)
            if (e.cover.isNotEmpty()) {
                loader.load(e.cover, dp(160), dp(220)) { bmp ->
                    if (img.tag == e.cover && bmp != null) img.setImageBitmap(bmp)
                }
            }
            val item = e.toItem()
            PosterActions.attach(host, cell, item, store) { renderChips(); reload() }
            return cell
        }
    }

    private fun pill(color: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    /** 焦点环：走 Theme 的**双环**（强调色 + 对向 halo），单环在亮封面上会隐形 */
    private fun ring(color: Int): android.graphics.drawable.Drawable =
        if (color == Color.TRANSPARENT) InsetDrawable(Theme.fill(host, Color.TRANSPARENT, Theme.RADIUS), dp(2))
        else Theme.focusRing(host, Theme.RADIUS)
}
