package com.dpadtv.client

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
    private val onOpenTitle: (String) -> Unit,
    private val onBack: () -> Unit,
) {

    private val accent = 0xFF38BDF8.toInt()
    private val ink = 0xFFE5E7EB.toInt()
    private val muted = 0xFF94A3B8.toInt()
    private val panel = 0xFF1F2937.toInt()

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
        head.addView(TextView(host).apply { text = "我的片单"; setTextSize(20f); setTextColor(ink) })
        status = TextView(host).apply { setTextSize(12.5f); setTextColor(muted) }
        head.addView(status)
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(host).apply {
            text = "长按 OK 改状态"
            setTextSize(12f)
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
            setTextSize(13f)
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
            this.adapter = this@LibraryScreen.adapter
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderChips()
        reload()
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
        setTextSize(13f)
        setTextColor(if (on) Color.parseColor("#04121B") else ink)
        isFocusable = true
        isFocusableInTouchMode = true
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
                isFocusable = true
                addView(ImageView(host).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(146), dp(204))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.parseColor("#1a2233"))
                })
                addView(TextView(host).apply {
                    setTextSize(12.5f); setTextColor(ink); maxLines = 1
                    setPadding(dp(2), dp(5), 0, 0)
                })
                addView(TextView(host).apply {
                    setTextSize(10.5f); setTextColor(accent); maxLines = 1
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
            cell.setOnClickListener { onOpenTitle(e.title) }
            PosterActions.attach(host, cell, item, store) { renderChips(); reload() }
            cell.setOnFocusChangeListener { v, f -> v.background = ring(if (f) accent else Color.TRANSPARENT) }
            return cell
        }
    }

    private fun pill(color: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    private fun ring(color: Int): android.graphics.drawable.Drawable {
        val g = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            if (color != Color.TRANSPARENT) setStroke(dp(3), color)
        }
        return InsetDrawable(g, dp(2))
    }
}
