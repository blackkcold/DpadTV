package io.github.blackkcold.librepro

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
    private val onBack: () -> Unit = {},
    private val onOpenTitle: (MovieItem) -> Unit,
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
    /** 请求令牌：快切筛选时，先发的慢响应回来不许覆盖后发的结果 */
    private var reqSeq = 0
    private var reqLive = 0

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
            setPadding(dp(10), dp(4), dp(10), dp(4))
            // 失败态才允许聚焦：电视上「按 OK 重试」必须是一个能到达的焦点
            isFocusable = false
            isFocusableInTouchMode = false
            setOnFocusChangeListener { v, f ->
                v.background = if (f) Theme.focusRing(host, Theme.RADIUS) else null
            }
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
            isFocusable = true
            // 网格本身要在触摸模式下也能拿焦点：子项已不可聚焦，否则进页后焦点会落到「返回首页」上
            isFocusableInTouchMode = true
            // 子项不再聚焦：焦点落在列表上，由 selector 画双环（和详情页选集同一套）
            setSelector(android.graphics.drawable.InsetDrawable(Theme.focusRing(host, Theme.RADIUS), dp(2)))
            this.adapter = this@BrowseScreen.adapter
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
                override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!loading && !done && total > 0 && first + visible >= total - 6) loadMore()
                }
            })
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { onOpenTitle(it) }
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderChips()
        // 子项不再可聚焦：进页就得把焦点交给网格，否则整页没有焦点（遥控器直接失灵）
        grid.postDelayed({ if (!grid.hasFocus()) grid.requestFocus() }, 260)
        // 「查看更多」不再把首页合并行塞进来当第一页：那一行的 Query 和本页 base 不是同一个
        // （4 个榜合并 vs 电影+9分+），续页从 start=18 接会让 9 分+ 的前 18 条永远消失。
        // 直接按 base 取第一页，本地索引命中时同样是秒开。
        reload()
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
                box.post { box.findViewWithTag<View>("${spec.name}:$v")?.requestFocus() }
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
                text = if (name == "年份" && v != "全部" && !repo.localYearKnown(v)) "$v · 联网" else v
                // 三个维度都有「全部」：tag 必须带维度前缀，否则焦点会跳回年份那一行
                tag = "$name:$v"
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

    /** 状态行：retry=true 时变成可聚焦的「重试」入口（电视上没别的点法） */
    private fun setStatus(text0: String, retry: Boolean = false) {
        status.text = text0
        status.isFocusable = retry
        status.isFocusableInTouchMode = false
        status.setTextColor(if (retry) Theme.ACCENT else muted)
        status.setOnClickListener(if (retry) View.OnClickListener { reload() } else null)
    }

    private fun failureText(): String = when (repo.lastFailure) {
        FetchFailure.RATE_LIMITED -> "豆瓣暂时不给数据（按 OK 重试）"
        FetchFailure.OFFLINE -> "网络不通，本地索引也没有这一块（按 OK 重试）"
        FetchFailure.UPSTREAM -> "取不到（按 OK 重试）"
        FetchFailure.NONE -> "这个组合本地没有（按 OK 联网取）"
    }

    /**
     * chip 切换：与 [reload] 同一套体验 —— 不清空、走 3dp 进度条、失败可重试。
     * 以前这里 `items.clear()` + 「加载中」文字 = 一秒白屏（审计 P1-5），
     * 文案还停在已经删掉的「侧车」。
     */
    private fun loadRef(ref: CollectionRef) {
        val my = ++reqSeq
        reqLive = my
        loading = true
        done = true
        setStatus("  · 正在取 ${ref.label}")
        progressBar?.visibility = View.VISIBLE
        scope.launch {
            val got = repo.fetch(ref, allowNetwork = true)
            if (my != reqLive) return@launch
            progressBar?.visibility = View.GONE
            loading = false
            items.clear()
            items.addAll(got)
            adapter.notifyDataSetChanged()
            if (got.isEmpty()) setStatus(failureText(), retry = true)
            else setStatus("  · 共 ${got.size} 部 · ${ref.label}${sourceHint()}")
        }
    }

    /**
     * 重新取数。**不清空已有结果** —— 之前一进来就 clear()，慢的时候用户看到的是白屏，
     * 分不清"在加载"还是"坏了"。现在：旧内容留着、顶部走一条 3dp 进度条、状态栏写清在取什么。
     */
    private fun reload() {
        done = false
        val q = currentQuery(0)
        val my = ++reqSeq
        reqLive = my
        loading = true
        setStatus("  · 正在取 ${DoubanUrls.label(q)}")
        progressBar?.visibility = View.VISIBLE
        scope.launch {
            val page = repo.fetchQuery(q, allowNetwork = true)
            // 令牌不匹配 = 用户已经切到别的筛选，这次结果作废（否则慢响应会覆盖新结果）
            if (my != reqLive) return@launch
            progressBar?.visibility = View.GONE
            loading = false
            if (page.isNotEmpty()) {
                items.clear()
                items.addAll(page)
                adapter.notifyDataSetChanged()
                setStatus("  · 共 ${items.size} 部${sourceHint()}")
            } else {
                done = true
                setStatus(failureText(), retry = true)
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
        val my = reqLive
        val q = currentQuery(items.size)
        scope.launch {
            val page = repo.fetchQuery(q, allowNetwork = true)
            if (my != reqLive) return@launch
            loading = false
            if (page.isEmpty()) {
                done = true
                if (items.isNotEmpty()) setStatus("  · 共 ${items.size} 部（到底了）")
                else setStatus(failureText(), retry = true)
            } else {
                items.addAll(page.filter { p -> items.none { it.title == p.title } })
                setStatus("  · 共 ${items.size} 部")
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
            PosterActions.attach(host, cell, item, store)
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
