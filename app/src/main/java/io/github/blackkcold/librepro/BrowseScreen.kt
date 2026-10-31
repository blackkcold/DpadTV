package io.github.blackkcold.librepro

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 「查看更多」= 浏览页。
 *
 * 结构：chip 条（预设榜 + 我的榜单 + 存为榜单） + 四维筛选（年份/评分/地区/类型/排序） + 网格分页。
 *
 * 三条硬规则（都是踩过坑写下来的）：
 *  1. **「没有更多了」只能由「成功取到空页」触发**。断网/限流/风控拿到空页 ≠ 没有更多，
 *     那是失败，必须留一个按得到、且不会被自动重试烧掉的入口。
 *  2. **翻页游标用「已请求条数」**，不是「已展示条数」。展示列表按片名去重，
 *     拿去重后的 size 当 offset 会让下一页和上一页重叠，最后表现为「划不动」。
 *  3. **筛选条件变了就先清空**。旧结果留在新筛选下面，用户会以为「日本 + 悬疑」只有这些。
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chips = ChipStore(host)

    /** 网格列宽按屏宽算：320dpi 电视只有 960dp 宽，写死 6 列会裁海报 */
    private val spec: GridSpec by lazy {
        val dm = host.resources.displayMetrics
        Theme.gridSpec((dm.widthPixels / dm.density).toInt())
    }

    private var base: Query = Shelves.defaultQuery(shelf)
    private var region: String? = null
    private var genre: String? = null
    private var year: String? = base.year
    private var rating: Double = base.minRate
    private var sort: Sort = base.sort
    private var activeChip: String? = null

    private val items = ArrayList<MovieItem>()
    private var loading = false
    private var done = false
    /**
     * 上一次「加载更多」失败过：滚动不再自动重试，必须用户按 OK。
     * 否则用户停在列表底部，onScroll 会一直触发，把请求配额烧光还一直失败。
     */
    private var moreFailed = false
    /** 请求令牌：快切筛选时，先发的慢响应回来不许覆盖后发的结果 */
    private var reqSeq = 0
    private var reqLive = 0
    /** 已经向数据源请求到的条数（游标），只增不减 */
    private var fetched = 0

    private lateinit var grid: GridView
    private lateinit var adapter: BaseAdapter
    private lateinit var status: TextView
    private lateinit var chipsRow: LinearLayout
    private var root: View? = null
    private var scrollPos = 0

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
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 失败态才允许聚焦：电视上「按 OK 重试」必须是一个能到达的焦点
            isFocusable = false
            isFocusableInTouchMode = false
            setOnFocusChangeListener { v, f ->
                v.background = if (f) Theme.focusRing(host, Theme.RADIUS) else null
            }
        }
        // 状态占满剩余宽度并省略：文案再长也不会把「刷新 / 返回」挤出屏幕
        head.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(host).apply {
            text = "刷新"
            setTextSize(Theme.BODY); setTextColor(muted)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(13), dp(6), dp(13), dp(6))
            setOnClickListener { reload(force = true) }
            setOnFocusChangeListener { v, f -> v.background = pill(if (f) accent else Color.TRANSPARENT, if (f) accent else Color.TRANSPARENT) }
        })
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

        // 筛选器：**每次点击后整行重建** —— 之前只建一次，on 是建行那一刻的快照，
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
            numColumns = spec.columns
            horizontalSpacing = dp(spec.gap)
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
                    if (!loading && !done && !moreFailed && total > 0 && first + visible >= total - 6) {
                        loadMore(auto = true)
                    }
                }
            })
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            scrollPos = grid.firstVisiblePosition
            items.getOrNull(pos)?.let { onOpenTitle(it) }
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderChips()
        // 子项不再可聚焦：进页就得把焦点交给网格，否则整页没有焦点（遥控器直接失灵）
        grid.postDelayed({ if (!grid.hasFocus()) grid.requestFocus() }, 260)
        reload()
        root = col
        return col
    }

    /** 从详情页回来时复用同一个实例：筛选条件、已加载内容、滚动位置都还在 */
    fun view(): View = root ?: build()

    fun restoreScroll() {
        if (scrollPos > 0) grid.post { grid.setSelection(scrollPos) }
    }

    /** 复用实例重新上屏后，焦点必须回到网格（否则会落到顶部「刷新」上） */
    fun requestGridFocus() {
        grid.post { if (!grid.hasFocus()) grid.requestFocus() }
    }

    fun cancel() = scope.cancel()

    // ---------- chip ----------

    private fun renderChips() {
        chipsRow.removeAllViews()
        Shelves.chipsFor(shelf.id).forEach { ref ->
            chipsRow.addView(chipView(ref.label, activeChip == ref.id) {
                activeChip = ref.id
                base = ref.toQuery()
                year = base.year
                rating = base.minRate
                region = null
                genre = null
                sort = base.sort
                renderChips()
                renderFilters()
                loadRef(ref)
            })
        }
        chips.list(shelf.id).forEach { c ->
            chipsRow.addView(chipView(c.label, activeChip == c.id) {
                activeChip = c.id
                base = c.query
                year = c.query.year
                rating = c.query.minRate
                region = null
                genre = null
                sort = c.query.sort
                renderChips()
                renderFilters()
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

    /** 一个筛选维度：读当前值 + 落值。选中态、重置游标、重建都在外层统一做。 */
    private class Dim(
        val name: String,
        val values: List<String>,
        val current: () -> String,
        val pick: (String) -> Unit,
    )

    private fun dims(): List<Dim> = listOf(
        Dim("年份", listOf("全部", "2025", "2024", "2023"), { year ?: "全部" }) { v ->
            year = v.takeIf { it != "全部" }
        },
        Dim("评分", listOf("全部", "9+", "8.5+", "8+", "7+"), { ratingLabel() }) { v ->
            rating = when (v) {
                "9+" -> 9.0
                "8.5+" -> 8.5
                "8+" -> 8.0
                "7+" -> 7.0
                else -> 0.0
            }
        },
        Dim("地区", listOf("全部", "华语", "欧美", "韩国", "日本"), { region ?: "全部" }) { v ->
            region = v.takeIf { it != "全部" }
        },
        Dim("类型", listOf("全部", "剧情", "悬疑", "科幻", "喜剧", "爱情", "动作", "动画"), { genre ?: "全部" }) { v ->
            genre = v.takeIf { it != "全部" }
        },
        Dim("排序", Sort.entries.map { it.label }, { sort.label }) { v ->
            sort = Sort.entries.first { it.label == v }
        },
    )

    private fun ratingLabel(): String = when {
        rating >= 9.0 -> "9+"
        rating >= 8.5 -> "8.5+"
        rating >= 8.0 -> "8+"
        rating > 0.0 -> "7+"
        else -> "全部"
    }

    /**
     * 重建整排筛选器。
     *
     * 5 行筛选在 540dp 高的电视上会把海报挤出首屏，所以两两并排压成 3 行；
     * 每行仍包一层 HorizontalScrollView，窄屏放不下时还能滑。
     */
    private fun renderFilters() {
        val box = filtersBox ?: return
        box.removeAllViews()
        val d = dims()
        rowOf(box, d[0], d[1])
        rowOf(box, d[2], d[3])
        rowOf(box, d[4])
    }

    private fun rowOf(box: LinearLayout, vararg dims: Dim) {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        dims.forEachIndexed { i, d ->
            if (i > 0) row.addView(View(host), LinearLayout.LayoutParams(dp(18), 1))
            row.addView(groupView(d))
        }
        box.addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
    }

    private fun groupView(d: Dim): View {
        val g = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        g.addView(TextView(host).apply {
            text = d.name
            setTextSize(Theme.CAPTION)
            setTextColor(muted)
            setPadding(0, 0, dp(8), 0)
        })
        val now = d.current()
        d.values.forEach { v ->
            val on = now == v
            g.addView(TextView(host).apply {
                text = if (d.name == "年份" && v != "全部" && !repo.localYearKnown(v)) "$v · 联网" else v
                // 多个维度都有「全部」：tag 必须带维度前缀，否则焦点会跳回年份那一行
                tag = "${d.name}:$v"
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
                setOnClickListener { pick(d, v) }
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
        return g
    }

    private fun pick(d: Dim, v: String) {
        d.pick(v)
        activeChip = null
        renderFilters()
        renderChips()
        reload()
        // 重建后把焦点还给刚点的那个值，遥控器不用重新找
        filtersBox?.post { filtersBox?.findViewWithTag<View>("${d.name}:$v")?.requestFocus() }
    }

    // ---------- 数据 ----------

    private fun currentQuery(start: Int): Query = Query(
        tags = base.tags + listOfNotNull(region, genre),
        year = year,
        minRate = rating,
        sort = sort,
        start = start,
    )

    /** 状态行：retry=true 时变成可聚焦的「重试」入口（电视上没别的点法） */
    private fun setStatus(text0: String, retry: Boolean = false, action: (() -> Unit)? = null) {
        status.text = text0
        status.isFocusable = retry
        status.isFocusableInTouchMode = false
        status.setTextColor(if (retry) Theme.ACCENT else muted)
        status.setOnClickListener(if (retry) View.OnClickListener { action?.invoke() } else null)
    }

    /** 失败原因：只说人话 */
    private fun failureText(): String = when (repo.lastFailure) {
        FetchFailure.RATE_LIMITED -> "豆瓣限流了"
        FetchFailure.OFFLINE -> "网络不通"
        FetchFailure.UPSTREAM -> "豆瓣没给数据"
        FetchFailure.NONE -> "本地没有这一组合"
    }

    /** chip 切换：不清空、走 3dp 进度条、失败可重试 */
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
            fetched = got.size
            adapter.notifyDataSetChanged()
            grid.setSelection(0)
            if (got.isEmpty()) setStatus("  · ${failureText()} · 按 OK 重试", retry = true) { loadRef(ref) }
            else setStatus("  · 已加载 ${got.size} 部 · ${ref.label}${sourceHint()}")
        }
    }

    /**
     * 重新取数。
     *
     * 条件变了就**立即清空**：旧结果留在新筛选下面是最糟的一种「看起来有数据」。
     * 只有同一条件的重试才保留旧内容 —— 那种情况下留着比白屏好。
     */
    private fun reload(force: Boolean = false) {
        done = false
        moreFailed = false
        val q = currentQuery(0)
        val my = ++reqSeq
        reqLive = my
        loading = true
        items.clear()
        fetched = 0
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        setStatus("  · 正在取 ${DoubanUrls.label(q)}" + if (force) "（强制刷新）" else "")
        progressBar?.visibility = View.VISIBLE
        scope.launch {
            // force = 跳过读缓存（ttl=0），用户点了「刷新」就该真的去问一次
            val page = repo.fetchQuery(q, ttlMs = if (force) 0L else CollectionRepo.TTL, allowNetwork = true)
            // 令牌不匹配 = 用户已经切到别的筛选，这次结果作废（否则慢响应会覆盖新结果）
            if (my != reqLive) return@launch
            progressBar?.visibility = View.GONE
            loading = false
            if (page.isNotEmpty()) {
                items.clear()
                items.addAll(page)
                fetched = page.size
                adapter.notifyDataSetChanged()
                setStatus("  · 已加载 ${items.size} 部${sourceHint()}")
            } else {
                done = true
                if (repo.lastFailure == FetchFailure.NONE) {
                    setStatus("  · 没有匹配的条目 · 换个筛选试试")
                } else {
                    setStatus("  · ${failureText()} · 按 OK 重试", retry = true) { reload() }
                }
            }
        }
    }

    /** 让用户知道这批数据是本地索引 / 缓存 / 刚联网拿的（不能混着说） */
    private fun sourceHint(): String = when (repo.lastSource) {
        "index" -> {
            val built = repo.indexBuiltAt()
            val age = repo.indexAgeDays()
            " · 本地索引" + (if (built.isNotEmpty()) " $built" else "") +
                (if (age >= 30) "（已 $age 天）" else "")
        }
        "cache" -> " · 缓存"
        "network" -> " · 已联网刷新"
        else -> ""
    }

    /**
     * 加载下一页。
     *
     * @param auto true = 滚动触发。失败后 6 秒内不再自动重试，避免停在列表底部时
     *             被 onScroll 反复打请求（烧配额，还让「重试」看起来没反应）。
     */
    private fun loadMore(auto: Boolean = false) {
        if (loading || done) return
        if (auto && moreFailed) return
        moreFailed = false
        loading = true
        val my = reqLive
        // 游标用「已请求条数」：展示列表会按片名去重，用 items.size 会回退、重叠
        val q = currentQuery(fetched)
        scope.launch {
            val page = repo.fetchQuery(q, allowNetwork = true)
            if (my != reqLive) return@launch
            loading = false
            if (page.isEmpty()) {
                if (repo.lastFailure == FetchFailure.NONE) {
                    done = true
                    setStatus("  · 已加载 ${items.size} 部 · 没有更多了")
                } else {
                    // 失败：**不能**钉死 done，也不能说「到底了」
                    moreFailed = true
                    setStatus("  · 加载更多失败（${failureText()}）· 按 OK 重试", retry = true) {
                        loadMore(auto = false)
                    }
                }
            } else {
                moreFailed = false
                fetched += page.size
                val fresh = page.filter { p -> items.none { it.title == p.title } }
                if (fresh.isEmpty()) {
                    // 整页都是重复：游标和数据源错位了，停下比死循环好
                    done = true
                    setStatus("  · 已加载 ${items.size} 部 · 没有更多了")
                    return@launch
                }
                items.addAll(fresh)
                adapter.notifyDataSetChanged()
                setStatus("  · 已加载 ${items.size} 部${sourceHint()}")
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
                addView(posterFrame(), LinearLayout.LayoutParams(dp(spec.posterW), dp(spec.posterH)))
                addView(TextView(host).apply {
                    setTextSize(Theme.CAPTION); setTextColor(ink); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(5), 0, 0)
                })
            }
            val item = items[position]
            bindPoster(cell.getChildAt(0) as FrameLayout, item)
            val label = cell.getChildAt(1) as TextView
            label.text = if (item.rating > 0) "${item.title}  ★${item.rating}" else item.title
            PosterActions.attach(host, cell, item, store)
            return cell
        }
    }

    /** 海报框：图没来 / 加载失败时显示片名首字，而不是一块死灰（断网时整页仍可辨认） */
    private fun posterFrame(): FrameLayout = FrameLayout(host).apply {
        background = Theme.rounded(host, Theme.POSTER_BG, Theme.RADIUS)
        addView(ImageView(host).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Theme.POSTER_BG)
        })
        addView(TextView(host).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            setTextColor(muted)
            alpha = 0.45f
            setTextSize(Theme.HERO)
        })
    }

    private fun bindPoster(frame: FrameLayout, item: MovieItem) {
        val img = frame.getChildAt(0) as ImageView
        val mark = frame.getChildAt(1) as TextView
        mark.text = item.title.take(1)
        img.tag = item.cover
        img.setImageBitmap(null)
        if (item.cover.isEmpty()) return
        loader.load(item.cover, dp(spec.posterW), dp(spec.posterH)) { bmp ->
            if (img.tag == item.cover && bmp != null) {
                img.setImageBitmap(bmp)
                mark.text = ""
            }
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
}
