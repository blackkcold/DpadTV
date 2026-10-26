package io.github.blackkcold.librepro

import android.app.Application

/**
 * 进程级单例。
 *
 * 为什么需要：`CollectionRepo` 里有一个 45KB gzip 索引（解压后 1.6k 条 × 4 字段）、
 * 一把请求节流 Mutex 和 60 分钟的 blockedUntil。之前每个界面各 new 一个：
 * 首页一份、点「查看更多」又一份 → 索引重复解析、节流与风控退避互相看不见，
 * 返回再进立刻又能打一轮豆瓣（审计 P1-9）。
 *
 * 只放**无状态或进程级状态**的仓库；界面自己的 View 树仍然随屏幕重建。
 */
class DpadApp : Application() {
    val repo: CollectionRepo by lazy { CollectionRepo(this) }

    override fun onCreate() {
        super.onCreate()
        // 启动就去后台把 52KB gzip 解掉：第一次查询不该在主线程做这件事（审计 R10）
        Thread({ runCatching { repo.warmUp() } }, "index-warmup").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}
