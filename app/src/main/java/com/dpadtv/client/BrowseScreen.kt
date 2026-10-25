package com.dpadtv.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「查看更多」= 浏览页。
 *
 * 结构：chip 条（预设榜 + 我的榜单 + 存为榜单） + 四维筛选（年份/地区/类型/排序） + 网格分页。
 * 设计原则：**榜单不占首页，但一个都不能少** —— 全收进 chip 与筛选器里，
 * 用户「存为榜单」后常用组合就是一次点击。
 */
class BrowseScreen(
    private val host: Activity,
    private val loader: ImageLoader,
    private val repo: CollectionRepo,
    private val store: LibraryStore,
    private val shelf: Shelf,
    private val initial: List<MovieItem> = emptyList(),
    private val onOpenTitle: (String) -> Unit,
) {

    private val accent = 0xFF38BDF8.toInt()
    private val ink = 0xFFE5E7EB.toInt()
    private val muted = 0xFF94A3B8.toInt()
    private val panel = 0xFF1F2937.toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chips = ChipStore(host)

    private var base: Query = Shelves.defaultQuery(shelf)
    private var region: String? = null
    private var genre: String? = null
    private var year: String? = null
    private var sort: Sort = Sort.RATING
    private var activeChip: String? = null
    /** chip 模式下直接走 ref 的缓存（Top250 有 50 条、9分+ 有 20 条，都是首页已经拉过的） */
    private var activeRef: CollectionRef? = null

    private val items = ArrayList<MovieItem>()
    private var loading = false
    private var done = false

    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var status: TextView
    private lateinit var chipsRow: LinearLayout

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(20), dp(48), dp(14))
        }

        // 标题
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(host).apply {
            text = shelf.title
            setTextSize(20f); setTextColor(ink)
        })
        status = TextView(host).apply {
            text = "  · 加载中"
            setTextSize(12.5f); setTextColor(muted)
        }
        head.addView(status)
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(host).apply {
            text = "返回 = 回首页"
            setTextSize(12f); setTextColor(muted)
        })
        col.addView(head)

        // chip 条
        chipsRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        // 四维筛选
        col.addView(filterRow("年份", listOf("全部", "2025", "2024", "2023")) { v ->
            year = if (v == "全部") null else v; activeChip = null; activeRef = null; renderChips(); reload()
        })
        col.addView(filterRow("地区", listOf("全部", "华语", "欧美", "韩国", "日本")) { v ->
            region = if (v == "全部") null else v; activeChip = null; activeRef = null; renderChips(); reload()
        })
        col.addView(filterRow("类型", listOf("全部", "剧情", "悬疑", "科幻", "喜剧", "爱情", "动作", "动画")) { v ->
            genre = if (v == "全部") null else v; activeChip = null; activeRef = null; renderChips(); reload()
        })
        col.addView(filterRow("排序", listOf("评分", "热门", "最新")) { v ->
            sort = Sort.entries.first { it.label == v }; activeChip = null; activeRef = null; renderChips(); reload()
        })

        // 网格
        adapter = cellAdapter()
        grid = GridView(host).apply {
            numColumns = 6
            horizontalSpacing = dp(12)
            verticalSpacing = dp(10)
            setPadding(0, dp(10), 0, 0)
            this.adapter = this@BrowseScreen.adapter
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
                override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!loading && !done && total > 0 && first + visible >= total - 6) loadMore()
                }
            })
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderChips()
        if (initial.isNotEmpty()) {
            items.addAll(initial)
            adapter.notifyDataSetChanged()
            status.text = "  · 共 ${items.size} 部 · ${DoubanUrls.label(currentQuery(0))}"
        } else {
            reload()
        }
        return col
    }

    // ---------- chip ----------

    private fun renderChips() {
        chipsRow.removeAllViews()
        val preset = Shelves.chipsFor(shelf.id)
        preset.forEach { ref ->
            chipsRow.addView(chipView(ref.label, activeChip == ref.id) {
                activeChip = ref.id
                activeRef = ref
                base = ref.toQuery()
                year = base.year
                region = null
                genre = null
                sort = base.sort
                renderChips()
                loadRef(ref)
            })
        }
        chips.list(shelf.id).forEach { c ->
            chipsRow.addView(chipView(c.label, activeChip == c.id) {
                activeChip = c.id
                activeRef = null
                base = c.query
                year = c.query.year
                region = null
                genre = null
                sort = c.query.sort
                renderChips()
                reload()
            })
        }
        chipsRow.addView(chipView("＋ 存为榜单", false) { saveChip() })
    }

    private fun chipView(label: String, on: Boolean, click: () -> Unit): View = TextView(host).apply {
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

    private fun filterRow(name: String, values: List<String>, onPick: (String) -> Unit): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(host).apply {
            text = name
            setTextSize(12f)
            setTextColor(muted)
            setPadding(0, 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        values.forEach { v ->
            val on = when (name) {
                "年份" -> (year ?: "全部") == v
                "地区" -> (region ?: "全部") == v
                "类型" -> (genre ?: "全部") == v
                else -> sort.label == v
            }
            row.addView(TextView(host).apply {
                text = v
                setTextSize(12.5f)
                setTextColor(if (on) accent else muted)
                isFocusable = true
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { onPick(v) }
                setOnFocusChangeListener { view, f ->
                    view.background = pill(Color.TRANSPARENT, if (f) accent else Color.TRANSPARENT)
                }
            })
        }
        return HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    // ---------- 数据 ----------

    private fun currentQuery(start: Int): Query = Query(
        tags = base.tags + listOfNotNull(region, genre),
        year = year,
        minRate = base.minRate,
        sort = sort,
        start = start,
    )

    private fun loadRef(ref: CollectionRef) {
        loading = true
        done = true
        items.clear()
        adapter.notifyDataSetChanged()
        status.text = "  · 加载中"
        scope.launch {
            val got = repo.fetch(ref)
            loading = false
            items.addAll(got)
            adapter.notifyDataSetChanged()
            status.text = if (got.isEmpty()) {
                val wait = repo.blockedSecondsLeft()
                if (wait > 0) "  · 豆瓣限流，${wait / 60 + 1} 分钟后自动恢复" else "  · 这个榜暂时取不到"
            } else "  · 共 ${got.size} 部 · ${ref.label}"
        }
    }

    private fun reload() {
        items.clear()
        done = false
        adapter.notifyDataSetChanged()
        status.text = "  · 加载中"
        loadMore()
    }

    private fun loadMore() {
        if (loading || done) return
        loading = true
        val q = currentQuery(items.size)
        scope.launch {
            val page = repo.fetchQuery(q)
            loading = false
            if (page.isEmpty()) {
                done = true
                val wait = repo.blockedSecondsLeft()
                status.text = when {
                    wait > 0 -> "  · 豆瓣限流，${wait / 60 + 1} 分钟后自动恢复（已缓存的结果仍可看）"
                    items.isEmpty() -> "  · 这个组合没有结果，换个筛选试试"
                    else -> "  · 共 ${items.size} 部"
                }
            } else {
                items.addAll(page.filter { p -> items.none { it.title == p.title } })
                status.text = "  · 共 ${items.size} 部 · ${DoubanUrls.label(q)}"
                adapter.notifyDataSetChanged()
            }
        }
    }

    // ---------- 存为榜单 ----------

    private fun saveChip() {
        val q = currentQuery(0)
        val suggested = DoubanUrls.label(q)
        val input = EditText(host).apply { setText(suggested); setSingleLine() }
        AlertDialog.Builder(host)
            .setTitle("存为榜单")
            .setMessage("把当前筛选保存成一个 chip，下次一次点击就到。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().ifBlank { suggested }
                val ok = chips.add(shelf.id, name, q)
                if (!ok) toast("已经有同名榜单了")
                renderChips()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------- 网格 ----------

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
            }
            val item = items[position]
            val img = cell.getChildAt(0) as ImageView
            val label = cell.getChildAt(1) as TextView
            label.text = if (item.rating > 0) "${item.title}  ★${item.rating}" else item.title
            img.tag = item.cover
            img.setImageBitmap(null)
            if (item.cover.isNotEmpty()) {
                loader.load(item.cover, dp(160), dp(220)) { bmp ->
                    if (img.tag == item.cover && bmp != null) img.setImageBitmap(bmp)
                }
            }
            cell.setOnClickListener { onOpenTitle(item.title) }
            PosterActions.attach(host, cell, item, store)
            cell.setOnFocusChangeListener { v, f ->
                v.background = ring(if (f) accent else Color.TRANSPARENT)
            }
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
