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
    private val onBack: () -> Unit = {},
    private val onOpenTitle: (String) -> Unit,
) {

    // getter，不是快照：换主题后已经建好的界面也要立刻跟着变
    private val accent get() = Theme.ACCENT
    private val ink get() = Theme.INK
    private val muted get() = Theme.MUTED
    private val panel get() = Theme.PANEL

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

    private var filtersBox: LinearLayout? = null
    private var progressBar: android.widget.ProgressBar? = null

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
            setTextSize(Theme.DISPLAY); setTextColor(ink)
        })
        status = TextView(host).apply {
            text = "  · 加载中"
            setTextSize(Theme.CAPTION); setTextColor(muted)
        }
        head.addView(status)
        head.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(host).apply {
            text = "‹ 返回首页"
            setTextSize(Theme.BODY); setTextColor(ink)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(13), dp(6), dp(13), dp(6))
            setOnClickListener { onBack() }
            setOnFocusChangeListener { v, f -> v.background = pill(if (f) accent else Color.TRANSPARENT, if (f) accent else Color.TRANSPARENT) }
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

        // 四维筛选：**每次点击后整行重建** —— 之前只建一次，on 是建行那一刻的快照，
        // 于是"点了日本没有高亮"（选中态永远停在初始值）。
        filtersBox = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        col.addView(filtersBox)
        renderFilters()

        // 3dp 细进度条：不遮挡内容，也不用弹窗打断
        progressBar = android.widget.ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        col.addView(progressBar)

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
        setTextSize(Theme.CAPTION)
        setTextColor(if (on) Theme.INK else Theme.MUTED)
        isFocusable = true
        isFocusableInTouchMode = false
        setPadding(dp(15), dp(8), dp(15), dp(8))
        background = Theme.rounded(host, if (on) Theme.GLASS_2 else Color.TRANSPARENT,
            if (on) accent else Theme.HAIRLINE, Theme.RADIUS, 1)
        setOnClickListener { click() }
        setOnFocusChangeListener { v, f ->
            v.background = Theme.rounded(host, if (on || f) Theme.GLASS_2 else Color.TRANSPARENT,
                if (f || on) accent else (if (on) accent else Theme.HAIRLINE), Theme.RADIUS, if (f) 2 else 1)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    /** 重建整排筛选器（选中态就在这里被刷新） */
    private fun renderFilters() {
        val box = filtersBox ?: return
        val focusTag = host.currentFocus?.tag
        box.removeAllViews()
        data class Spec(val name: String, val values: List<String>, val apply: (String) -> Unit)
        val specs = listOf(
            Spec("年份", listOf("全部", "2025", "2024", "2023")) { v -> year = if (v == "全部") null else v },
            Spec("地区", listOf("全部", "华语", "欧美", "韩国", "日本")) { v -> region = if (v == "全部") null else v },
            Spec("类型", listOf("全部", "剧情", "悬疑", "科幻", "喜剧", "爱情", "动作", "动画")) { v -> genre = if (v == "全部") null else v },
            Spec("排序", listOf("评分", "热门", "最新")) { v -> sort = Sort.entries.first { it.label == v } },
        )
        specs.forEach { spec ->
            box.addView(filterRow(spec.name, spec.values) { v ->
                spec.apply(v)
                activeChip = null
                activeRef = null
                renderFilters()
                renderChips()
                reload()
                // 重建后把焦点还给刚点的那个值，遥控器不用重新找
                box.post { box.findViewWithTag<View>(v)?.requestFocus() }
            })
        }
    }

    private fun filterRow(name: String, values: List<String>, onPick: (String) -> Unit): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(TextView(host).apply {
            text = name
            setTextSize(Theme.CAPTION)
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
                tag = v
                setTextSize(Theme.LABEL)
                setTextColor(if (on) Theme.INK else Theme.MUTED)
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(11), dp(5), dp(11), dp(5))
                background = Theme.rounded(
                    host,
                    if (on) Theme.GLASS_2 else Color.TRANSPARENT,
                    if (on) accent else Color.TRANSPARENT,
                    Theme.RADIUS, 1,
                )
                setOnClickListener { onPick(v) }
                setOnFocusChangeListener { view, f ->
                    view.background = Theme.rounded(
                        host,
                        if (on || f) Theme.GLASS_2 else Color.TRANSPARENT,
                        if (f || on) accent else Theme.HAIRLINE,
                        Theme.RADIUS, if (f) 2 else 1,
                    )
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
                // 只在「真的是限流」时才提豆瓣；侧车没准备好是配置问题，不能算到豆瓣头上
                when (repo.lastFailure) {
                    FetchFailure.RATE_LIMITED -> "  · 豆瓣暂时不给数据，稍后重试（已缓存的仍可看）"
                    FetchFailure.OFFLINE -> "  · 缓存还没准备好，等侧车预热"
                    FetchFailure.UPSTREAM -> "  · 这个榜暂时取不到"
                    FetchFailure.NONE -> "  · 这个榜暂时取不到"
                }
            } else "  · 共 ${got.size} 部 · ${ref.label}"
        }
    }

    /**
     * 重新取数。**不清空已有结果** —— 之前一进来就 clear()，慢的时候用户看到的是白屏，
     * 分不清"在加载"还是"坏了"。现在：旧内容留着、顶部走一条 3dp 进度条、状态栏写清在取什么。
     */
    private fun reload() {
        done = false
        val q = currentQuery(0)
        status.setOnClickListener(null)
        status.text = "  · 正在取 ${DoubanUrls.label(q)}"
        progressBar?.visibility = View.VISIBLE
        scope.launch {
            val page = repo.fetchQuery(q)
            progressBar?.visibility = View.GONE
            if (page.isNotEmpty()) {
                items.clear()
                items.addAll(page)
                adapter.notifyDataSetChanged()
                status.text = "  · 共 ${items.size} 部 · ${DoubanUrls.label(q)}${sourceHint()}"
            } else {
                done = true
                status.text = "  · " + when (repo.lastFailure) {
                    FetchFailure.RATE_LIMITED -> "豆瓣暂时不给数据（按 OK 重试）"
                    FetchFailure.OFFLINE -> "本地索引缺这一块（按 OK 联网取）"
                    FetchFailure.UPSTREAM -> "取不到（按 OK 重试）"
                    FetchFailure.NONE -> "这个组合本地没有（按 OK 联网取）"
                }
                status.setOnClickListener { reload() }
            }
        }
    }

    /** 让用户知道这批数据是本地索引还是刚联网拿的 */
    private fun sourceHint(): String = when (repo.lastSource) {
        "index" -> " · 本地索引" + repo.indexBuiltAt().let { if (it.isNotEmpty()) " $it" else "" }
        "network" -> " · 已联网刷新"
        else -> ""
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
                status.text = when (repo.lastFailure) {
                    FetchFailure.RATE_LIMITED -> "  · 豆瓣暂时不给数据，稍后重试（已缓存的仍可看）"
                    FetchFailure.OFFLINE -> "  · 缓存还没准备好，等侧车预热"
                    FetchFailure.UPSTREAM -> "  · 这个组合暂时取不到"
                    FetchFailure.NONE -> if (items.isEmpty()) "  · 这个组合没有结果，换个筛选试试" else "  · 共 ${items.size} 部"
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
        dlg()
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
                    setBackgroundColor(Theme.POSTER_BG)
                })
                addView(TextView(host).apply {
                    setTextSize(Theme.CAPTION); setTextColor(ink); maxLines = 1
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

    /** 弹窗按解析出来的主题显式选样式（不能只靠 -night 限定符） */
    private fun dlg(): AlertDialog.Builder =
        AlertDialog.Builder(host, if (Theme.isLight) R.style.AppDialogLight else R.style.AppDialogDark)

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
