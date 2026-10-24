package top.yukonga.mishka.custom.forms

import kotlin.math.abs

/** 一行在窗口坐标里的竖向身段。 */
internal data class DragRowSpan(val top: Float, val bottom: Float) {
    val middle: Float get() = (top + bottom) / 2f
}

/**
 * 拖动排序的命中判定（纯函数，不依赖 Compose，便于单测；由 [DragSortState] 每次命中时调用）。
 *
 * - [order]：当前视觉顺序（元素是原始下标）；[from] 是被拖行的原始下标。
 * - [fingerY]：指尖窗口 Y，调用方已钳到可见区 [visibleTop]..[visibleBottom] 之内。
 * - [spanOf]：行的身段，null 表示该行不在布局里（已被 lazy 列表回收 / 滚出组合）。
 *
 * 只有与可见区相交的行才能成为落点：滚出视口的行仍可能挂着布局，若拿它当落点，被拖行会落到
 * 看不见的位置。指尖在行间缝里时取离它中线最近的行，与边界处理保持一致。
 *
 * 返回被拖行的目标下标（从 [order] 摘掉被拖行后再插入的位置），不需要动则返回 -1。
 */
internal fun dragInsertIndex(
    order: List<Int>,
    from: Int,
    fingerY: Float,
    visibleTop: Float,
    visibleBottom: Float,
    spanOf: (Int) -> DragRowSpan?,
): Int {
    val cur = order.indexOf(from)
    if (cur < 0) return -1
    // 指尖仍在被拖行自己身上：不动（否则换位后指尖又落进别的行，会来回抖）
    val own = spanOf(from)
    if (own != null && fingerY >= own.top && fingerY <= own.bottom) return -1
    var over = -1
    var overSpan: DragRowSpan? = null
    var nearest = -1
    var nearestSpan: DragRowSpan? = null
    var best = Float.MAX_VALUE
    for (o in order) {
        if (o == from) continue
        val s = spanOf(o) ?: continue
        if (s.bottom < visibleTop || s.top > visibleBottom) continue
        if (fingerY >= s.top && fingerY <= s.bottom) {
            over = o
            overSpan = s
            break
        }
        val d = abs(fingerY - s.middle)
        if (d < best) {
            best = d
            nearest = o
            nearestSpan = s
        }
    }
    if (over < 0) {
        over = nearest
        overSpan = nearestSpan
    }
    val target = overSpan ?: return -1
    val ov = order.indexOf(over)
    if (ov < 0) return -1
    var at = ov + if (fingerY >= target.middle) 1 else 0
    if (cur < at) at -= 1   // 先摘掉被拖行，后面的下标左移一格
    if (at == cur || at < 0 || at >= order.size) return -1
    return at
}
