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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「查看更多」页（M1 版本）：把这一行的合并结果铺成网格。
 * M2 会在这里加 chip 条（热门/TOP250/奥斯卡/日剧2025…）与四维筛选器。
 */
class BrowseScreen(
    private val host: Activity,
    private val loader: ImageLoader,
    private val shelf: Shelf,
    private val items: List<MovieItem>,
    private val onOpenTitle: (String) -> Unit,
) {

    private val accent = 0xFF38BDF8.toInt()
    private val ink = 0xFFE5E7EB.toInt()
    private val muted = 0xFF94A3B8.toInt()

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).toInt()

    fun build(): View {
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(18))
        }
        col.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = shelf.title
                setTextSize(21f)
                setTextColor(ink)
            })
            addView(TextView(host).apply {
                text = "  · 合并 ${items.size} 部"
                setTextSize(12.5f)
                setTextColor(muted)
            })
            addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(host).apply {
                text = "按遥控器「返回」回首页"
                setTextSize(12.5f)
                setTextColor(muted)
            })
        })

        val grid = GridView(host).apply {
            numColumns = 6
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(0, dp(14), 0, 0)
            adapter = cellAdapter()
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
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
                    setTextSize(12.5f)
                    setTextColor(ink)
                    maxLines = 1
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
            cell.setOnFocusChangeListener { v, focused ->
                v.background = ring(if (focused) accent else Color.TRANSPARENT)
            }
            return cell
        }
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
