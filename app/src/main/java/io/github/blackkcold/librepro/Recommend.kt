package io.github.blackkcold.librepro

import kotlin.math.exp

/**
 * 本地个性化推荐（无服务端、无网络、无画像上传）。
 *
 * 信号来自用户自己的动作：收藏 / 想看 / 在看 / 看过（+ 播放次数）。
 * 特征只有「来源榜单」与「评分 / 年份」这几样本地就一定拿得到的东西 —— 不去猜类型标签，
 * 因为一旦要类型就得再打豆瓣，而豆瓣是本项目最脆的一环。
 *
 * 三层：
 *   1. taste()  —— 把片单折算成「偏好来源」权重（状态加权 + 时间衰减）
 *   2. score()  —— 候选打分：来源亲和 0.45 / 评分 0.30 / 新鲜 0.15 / 未看过 0.10
 *   3. mmr()    —— 多样性重排，防止前 12 个全是 9 分老片
 *
 * 纯函数 + JVM 单测（RecommendTest）。
 */
object Recommend {

    /** 状态权重：收藏 > 看过 > 在看 > 想看 > 弃剧（负） */
    fun stateWeight(state: LibState): Double = when (state) {
        LibState.FAV -> 1.0
        LibState.WATCHED -> 0.8
        LibState.WATCHING -> 0.9
        LibState.WANT -> 0.5
        LibState.DROPPED -> -0.6
    }

    /**
     * @param entries 片单（含来源 ref id）
     * @param now     当前时间戳，用于半衰期 30 天的衰减
     */
    fun taste(entries: List<LibEntry>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val acc = HashMap<String, Double>()
        for (e in entries) {
            val src = e.source
            if (src.isEmpty()) continue
            val days = ((now - e.updatedAt).coerceAtLeast(0)) / 86_400_000.0
            val decay = exp(-days / 30.0)
            acc[src] = (acc[src] ?: 0.0) + stateWeight(e.state) * decay
        }
        return acc
    }

    private fun freshness(year: String, newest: Int = 2025): Double {
        val y = year.toIntOrNull() ?: return 0.45
        return when {
            y >= newest -> 1.0
            y == newest - 1 -> 0.85
            y == newest - 2 -> 0.7
            y == newest - 3 -> 0.55
            else -> 0.4
        }
    }

    fun score(item: MovieItem, taste: Map<String, Double>, newest: Int = 2025): Double {
        val max = taste.values.maxOrNull() ?: 0.0
        val affinity = if (max <= 0) 0.0 else ((taste[item.from] ?: 0.0) / max).coerceIn(0.0, 1.0)
        val rating = (item.rating / 10.0).coerceIn(0.0, 1.0)
        return 0.45 * affinity + 0.30 * rating + 0.15 * freshness(item.year, newest) + 0.10
    }

    /** 相似度：同年 + 同评分档 = 1.0，仅同评分档 0.5，其它 0.15 */
    private fun similarity(a: MovieItem, b: MovieItem): Double {
        val sameYear = a.year.isNotEmpty() && a.year == b.year
        val ba = (a.rating * 2).toInt()
        val bb = (b.rating * 2).toInt()
        return when {
            sameYear && ba == bb -> 1.0
            ba == bb -> 0.5
            else -> 0.15
        }
    }

    /**
     * 最大边际相关重排（MMR）：每轮选「分数高且与已选不相似」的那一个。
     * lambda 越大越重视多样性。
     */
    fun mmr(pool: List<MovieItem>, taste: Map<String, Double>, k: Int, lambda: Double = 0.35): List<MovieItem> {
        if (k <= 0) return emptyList()
        val left = pool.toMutableList()
        val picked = ArrayList<MovieItem>()
        while (picked.size < k && left.isNotEmpty()) {
            var bestIdx = 0
            var bestVal = Double.NEGATIVE_INFINITY
            for (i in left.indices) {
                val it = left[i]
                val rel = score(it, taste)
                val sim = picked.maxOfOrNull { p -> similarity(it, p) } ?: 0.0
                val v = (1 - lambda) * rel - lambda * sim
                if (v > bestVal) { bestVal = v; bestIdx = i }
            }
            picked.add(left.removeAt(bestIdx))
        }
        return picked
    }

    /** 信号不足就不出「为你推荐」——宁可不出现，也不要给一行平庸的推荐 */
    fun enough(signals: Int): Boolean = signals >= 8
}
