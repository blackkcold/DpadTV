package com.dpadtv.client

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * 海报长按 = 快捷菜单。电视上"长按 OK"是仅次于"按 OK"的第二个肌肉记忆，
 * 比进详情页再找按钮快得多，也不占首屏空间。
 */
object PosterActions {

    fun attach(
        host: Activity,
        view: View,
        item: MovieItem,
        store: LibraryStore,
        onChange: () -> Unit = {},
    ) {
        view.setOnLongClickListener {
            show(host, item, store, onChange)
            true
        }
    }

    private fun show(host: Activity, item: MovieItem, store: LibraryStore, onChange: () -> Unit) {
        val cur = store.stateOf(item.title)
        val actions = LibState.entries.toMutableList()
        val labels = actions.map { if (it == cur) "${it.label}  ✓" else it.label }.toMutableList()
        if (cur != null) labels.add("从片单移除")

        // 弹窗必须按解析出来的主题显式选样式，否则浅色主题下会弹出深色菜单
        AlertDialog.Builder(host, if (Theme.isLight) R.style.AppDialogLight else R.style.AppDialogDark)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which ->
                val picked = if (which < actions.size) actions[which] else null
                val result = if (picked != null) {
                    store.toggle(item, picked)
                } else {
                    store.remove(item.title)
                    null
                }
                Toast.makeText(
                    host,
                    if (result == null) "已从片单移除" else "已加入「${result.label}」",
                    Toast.LENGTH_SHORT,
                ).show()
                if (result != null) store.event(item.title, 0)
                onChange()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 给任意 TextView/容器挂一个"当前状态"角标（可选） */
    fun badgeFor(store: LibraryStore, title: String): String? = store.stateOf(title)?.label

    fun stateLine(store: LibraryStore, item: MovieItem, tv: TextView) {
        val s = store.stateOf(item.title)
        tv.text = s?.label ?: ""
        tv.visibility = if (s == null) View.GONE else View.VISIBLE
    }
}
