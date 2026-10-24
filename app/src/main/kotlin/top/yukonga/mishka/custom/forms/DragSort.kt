package top.yukonga.mishka.custom.forms

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 按住把手拖动排序（对齐 mihomo_box WebUI `js/core.js` 的 `enableDragSort`）：
 *  - 行首一个两列三点的把手（[DragHandle]），**按住即拖**——不做长按等待，与把手上的
 *    `touch-action: none` 同效；没有把手以外的排序入口；
 *  - **列表内实时换位**：命中判定 =「手指窗口 Y 落在哪一行的身位里、在该行中线的上半还是
 *    下半」，命中后直接把被拖行在视觉顺序 [DragSortState.order] 里搬到该档——这正是参考实现
 *    `elementFromPoint` + `insertBefore` 的 Compose 版：顺序在「列表」里实时改，被拖行
 *    **即换即到位**（参考实现原话：给它补滑移动画 = 不跟手），其余行由宿主给 FLIP 位移动画；
 *  - 行身段（窗口坐标）只用于命中判定，来自真实布局测量：每帧重算，不缓存槽位差、不模拟让位
 *    位移——上一版按「实测槽位差」让位，行长不等（多行 AND 规则等）时被拖行会压到邻行身上，
 *    且滚动中槽位表变旧，正是错位的根源；
 *  - **指尖位置只认窗口坐标**：容器级手势把事件的容器局部坐标 + 容器窗口原点，当场还原成指尖的
 *    窗口 Y（与行身段同系，都是窗口坐标）——与参考实现全程用 `e.clientY` 同口径。Compose 的
 *    `position` 是节点局部坐标，被拖行「即换即到位」是整行高瞬跳、自动翻滚又在指下滚内容，局部坐标
 *    会「手指没动却变了」；把这种假位移累加，每换一次位手指 Y 就偏一整行：命中判定落回被拖行身后那
 *    一行、刚换上去又被换回来（来回抖 = 拖动被打断），偏出的 Y 还会落进边缘带触发误翻滚（列表自己
 *    上滑）。这里不累加任何位移，每个事件都由容器坐标当场换算成窗口坐标；
 *  - **拖动逻辑不与任何外层手势仲裁，且手势挂在不会被回收的容器节点上**（v7，四轮实机翻车后的重写）：
 *    `detectDragGestures` / `pointerInteropFilter` 两版被外层 scrollable 的仲裁分流；v6 把手势挪到把手
 *    节点、逐事件消费整条流，抢流是解决了，但把手是会被 lazy 列表回收的行内节点——快速拖动时换位 /
 *    滚动让把手解绑，挂在它上面的手势协程被取消（`finally` 收尾），表现为「快速拖动被打断、慢速没事」
 *    （慢速不触发回收）。v7 把手势挪到**容器**修饰符上（容器节点在整个拖动里不会被回收，协程活得到底）：
 *    ① 容器 `pointerInput` 在 DOWN 用把手窗口矩形命中判定，命中就在 `PointerEventPass.Initial` 上
 *    **逐事件消费整条流**（容器修饰符在宿主链里排在 scrollable 之前、同节点 Initial pass 按链顺序先跑，
 *    容器因此先于外层 scrollable 消费 DOWN——抢流 / 拦截在 Initial 阶段就被挡下，不依赖 `userScrollEnabled`
 *    的重组时序，没有首帧空窗）；没命中把手就一个事件都不消费，列表照常滚动 / 点击；
 *    ② 结束条件只有主指针 `pressed=false`（真抬指 / 框架 CANCEL 同形），`finally` 兜底 `endDrag`；
 *    自动翻滚走 `dispatchRawDelta` 程序化滚动（实测不受 `userScrollEnabled` 影响），照旧可用；
 *    **只要手指按住不松、事件还在来，排序就继续**；
 *  - 被拖行永远停在手指命中的那一档里（手指在屏幕内），容器不可能把它回收掉——所以不再需要
 *    「拖到视口外就先写回一段」的中途提交：宿主的数据只在松手时动一次（[DragSortState.endDrag]），
 *    拖动过程中零文档写入、零重组风暴；
 *  - 非被拖行只在「真的挪了位置」时补一段 FLIP 滑移（lazy 列表用 [dragSortItem] 接
 *    `Modifier.animateItem`，.26s cubic-bezier(.2,.8,.25,1)，与参考实现同一段缓动）；
 *  - 手指进入容器上下边缘时自动翻滚：E = clamp(容器高×15%, 44dp, 88dp)，v = 1 + (depth/E)×13
 *    dp/帧（同参考实现 ≈1~14px/帧）；滚一帧立刻补一轮命中判定；
 *  - 拖动中的行加参考实现 `.rule-item.dragging` 的视觉：整行降透明 + 强调色淡底 +
 *    1.5dp 虚线描边 + 淡阴影。
 *
 * [DragSortState.order] 是「原始下标的排列」：宿主按它渲染列表（`items(order)` / `order.forEach`），
 * 不拖动时恒为恒等序。松手时把被拖行的最终档位 [DragSortState.endDrag] 交给宿主的 `onMove(from, to)`
 * ——序列的 `removeAt(from)` + `add(to)` 口径，与 YamlPatch.moveItem 一致。
 */

/** `.rule-item.dragging { opacity: .55 }`。 */
private const val DragItemAlpha = 0.55f

/** `.rule-item.dragging { background: var(--accent-soft) }`：--accent-soft = rgba(52,130,255,.12)。 */
private const val DragItemTint = 0.12f

/** 形状取 miuix `Card` 的默认圆角（CardDefaults.CornerRadius = 16.dp），虚线框贴着卡片走。 */
private val DragItemRadius = 16.dp

/** `outline-offset: -2px`：虚线画在行内 2dp 处。 */
private val DragItemOutlineInset = 2.dp

/**
 * 换位时给其它行的 FLIP 位移动画：参考实现 `transform .26s cubic-bezier(.2, .8, .25, 1)`。
 * 被拖行不给（即换即到位），宿主用 [dragSortItem] 拿到这个修饰符。
 */
private val FlipSpec: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0.8f, 0.25f, 1f))

@Stable
internal class DragSortState {
    /** 列表项数，宿主每次组合时同步（[syncCount]）。 */
    internal var count: Int = 0
        private set

    /** 视觉顺序：元素是「原始下标」，不拖动时恒为 0 until count。拖动中宿主按它渲染。 */
    private val orderState = mutableStateListOf<Int>()
    val order: List<Int> get() = orderState

    /** 正在拖动的行（原始下标）；-1 = 没在拖。 */
    internal var dragging by mutableStateOf(-1)
        private set

    /** 拖动中是否真的换过位（换过才在松手时写回宿主）。 */
    private var reordered = false

    /**
     * 提交帧抑制 FLIP：宿主数据一换序，键位（= 原始下标）整体重排，如果这时挂着位移动画，
     * 动画会把「键的旧位置 → 新位置」当成被拖行的移动、把内容挪错位（动画结束才归位）。
     * 提交那一帧把位移动画摘掉、下一帧再挂回去 —— 松手瞬间视觉位置本来就不变，
     * 摘掉动画没有任何观感损失。
     */
    internal var flipSuppressed by mutableStateOf(false)
        private set

    internal fun resumeFlip() {
        flipSuppressed = false
    }

    private var fingerY = 0f
    private var containerLeft = 0f
    private var containerTop = 0f
    private var containerBottom = 0f
    private var scrollBy: ((Float) -> Float)? = null
    /** 行的布局坐标：命中时现读窗口矩形，不缓存量到的位置（见 [rowSpan]）。 */
    private val rowCoords = HashMap<Int, LayoutCoordinates>()
    private val handleCoords = HashMap<Int, LayoutCoordinates>()

    internal var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }

    /**
     * 宿主每次组合时同步项数。数变了（新增 / 删除 / 换了个文档）就重建恒等序；
     * 拖动中数变了（正常拖不到：没有中途写入）视为数据已被外部改，放弃本次拖动。
     */
    internal fun syncCount(n: Int) {
        if (n == count) return
        count = n
        if (dragging >= 0) {
            dragging = -1
            fingerY = 0f
        }
        resetOrder()
    }

    private fun resetOrder() {
        orderState.clear()
        for (i in 0 until count) orderState.add(i)
        reordered = false
    }

    /**
     * 行容器（Column / LazyColumn 都可以）：量容器窗口位置，接上滚动能力，并**独占整条拖动手势**。
     *
     * v7 拖动逻辑（关键改动：手势不再挂在会被回收的把手节点上）：
     * 把手是行内节点，快速拖动时行被 lazy 列表回收 / 换位 → 把手节点解绑 → 挂在它上面的手势协程被
     * 取消 → `finally` 收尾（= 上一版「快速拖动被打断、慢速没事」的根因：慢速不触发回收）。
     * 现在把手只负责在 [handleModifier] 里量出自己的窗口矩形；真正的手势挂在这个**容器**修饰符上——
     * 容器节点在整个拖动里不会被回收，协程因此活得到底。
     *
     * 抢流：容器修饰符在宿主链里排在 `scrollable` / `verticalScroll` **之前**；同一节点上多个
     * `pointerInput` 在 **Initial** pass 按链顺序（靠前先跑）、Main pass 按逆序（靠后先跑）。所以这里
     * 用 `PointerEventPass.Initial` 消费整条流：DOWN 落在把手上时，容器先于外层滚动容器把 DOWN 及后续
     * 每个事件消费掉——外层 scrollable 的 slop 检测看到已消费即取消，「整体上滑 / 抢流」在 Initial 阶段
     * 就被挡下，且不依赖 `userScrollEnabled` 的重组时序（不再有首帧空窗）。DOWN 不在把手上就一个事件
     * 都不消费，列表照常滚动 / 点击。
     */
    fun containerModifier(scrollBy: ((Float) -> Float)? = null): Modifier {
        this.scrollBy = scrollBy
        return Modifier
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                containerLeft = b.left
                containerTop = b.top
                containerBottom = b.bottom
            }
            .pointerInput(Unit) {
                forEachGesture {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // 事件给的是容器局部坐标；+ 容器窗口原点 = 窗口坐标，与把手矩形同系
                        val hit = hitHandle(down.position.x + containerLeft, down.position.y + containerTop)
                        if (hit < 0) {
                            // 不在把手上：整条手势一个事件都不消费（滚动 / 点击照常），
                            // 但不重新命中判定——跟到手指离开为止，免得滚动途中误触到把手
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.changes.none { it.pressed }) break
                            }
                            return@awaitPointerEventScope
                        }
                        down.consume()
                        startDrag(hit, down.position.y + containerTop)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                var up = false
                                for (change in event.changes) {
                                    if (change.id == down.id) {
                                        if (change.pressed) {
                                            dragTo(change.position.y + containerTop)
                                        } else {
                                            up = true
                                        }
                                    }
                                    // 主手指之外的事件（第二根手指）也吞掉，别漏给外层滚动容器
                                    change.consume()
                                }
                                if (up) break
                            }
                        } finally {
                            endDrag()
                        }
                    }
                }
            }
    }

    /** 命中判定：窗口坐标 (x, y) 落在哪个把手的窗口矩形里（只认当前还挂着的手柄）。没有 → -1。 */
    private fun hitHandle(x: Float, y: Float): Int {
        for ((i, coords) in handleCoords) {
            if (!coords.isAttached) continue
            val b = coords.boundsInWindow()
            if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) return i
        }
        return -1
    }

    /**
     * 行：记下自己的布局坐标（命中判定时现读窗口矩形），被拖行抬到最上层。
     * 位置回调放在最外层：量到的永远是行的**布局**位置（lazy 列表的换位动画是画在层上的，
     * 不影响这里的布局坐标——命中判定因此不会被动画中的半程位置带偏）。
     */
    fun rowModifier(index: Int): Modifier =
        Modifier
            .onGloballyPositioned { coords ->
                claimCoords(rowCoords, index, coords)
            }
            .zIndex(if (index == dragging) 1f else 0f)

    /**
     * 同一个布局节点可能被 lazy 列表复用给别的下标（items 未指定 contentType 时不同 key 的行会共用节点）。
     * 坐标对象只能属于最近一次上报它的下标，否则旧下标会映射到现在屏上另一行的位置上。
     */
    private fun claimCoords(map: HashMap<Int, LayoutCoordinates>, index: Int, coords: LayoutCoordinates) {
        map.entries.removeAll { it.value === coords && it.key != index }
        map[index] = coords
    }

    /**
     * 行此刻的窗口身段；已不在布局树上（被 lazy 列表回收 / 滚出组合）返回 null。
     *
     * 不能缓存「上一次量到的位置」：滚出组合的行不再回调，缓存里的旧位置会和屏上现在的行重叠，
     * 指尖一落进去就把被拖行丢到看不见的地方（拖到顶部时表现为落到错误的档位、来回跳）。
     */
    private fun rowSpan(index: Int): DragRowSpan? {
        val c = rowCoords[index] ?: return null
        if (!c.isAttached) return null
        val b = c.boundsInWindow()
        return DragRowSpan(b.top, b.bottom)
    }

    /**
     * 把手：只负责**量出自己的窗口矩形**（[handleCoords]），供容器级手势在 DOWN 时命中判定。
     *
     * v7 起把手不再自带 `pointerInput` 手势——手势统一挂在 [containerModifier]（容器节点）上。
     * 原因见 [containerModifier] 的 KDoc：把手是会被 lazy 列表回收的行内节点，快速拖动时它一解绑，
     * 挂在它上面的手势协程就被取消（= 上一版「快速拖动被打断」的根因）。把手留在行里只为提供命中矩形
     * 与语义 / 外观；矩形随每次布局刷新，节点回收后 [hitHandle] 靠 `isAttached` 过滤掉失效项。
     */
    fun handleModifier(index: Int): Modifier =
        Modifier
            // 记把手节点：容器级手势用它的 boundsInWindow 做落点命中判定
            .onGloballyPositioned { coords -> claimCoords(handleCoords, index, coords) }

    /**
     * 开始拖动：`windowY` 是 DOWN 时指尖的窗口 Y（已由容器手势用容器局部坐标 + 容器窗口原点换算好）。
     */
    fun startDrag(index: Int, windowY: Float) {
        if (index < 0 || index !in orderState) return
        dragging = index
        fingerY = windowY
    }

    /**
     * 手指移动：记下指尖的窗口 Y 后跑一轮命中判定；被拖行的位置由它命中的档位决定（不跟手漂浮）。
     *
     * 全程只用窗口 Y（与 [rowSpan] 同系）：行在视口里怎么挪、边缘自动翻滚让内容在指下滚动，
     * 都不影响指尖位置，也就不会「每换一次位就偏一行高」地来回抖。
     */
    fun dragTo(windowY: Float) {
        if (dragging < 0) return
        fingerY = windowY
        step()
    }

    fun endDrag() {
        val from = dragging
        if (from < 0) return
        val to = orderState.indexOf(from)
        dragging = -1
        fingerY = 0f
        val moved = reordered && to >= 0 && to != from
        flipSuppressed = true   // 提交帧：键位随数据整体重排，这一帧不做位移动画（下一帧恢复）
        resetOrder()            // 视觉顺序已由宿主接住（或本次没换过位）：恢复恒等序，行不会跳
        if (moved) onMove(from, to)
    }

    /**
     * 命中判定 + 换位（对应参考实现的 `step()`，判定本身见 [dragInsertIndex]）：手指窗口 Y 落在哪一行的
     * 身段里，就在该行中线的上半 → 换到它前面，下半 → 换到它后面；落在被拖行自己身上 = 不动。
     *
     * 指尖先钳到容器可见区内：越过顶 / 底边时落点是最边上的可见行，两端对称；超出的距离交给边缘
     * 自动翻滚去滚动列表。
     */
    private fun step() {
        val from = dragging
        if (from < 0) return
        val y = if (containerBottom > containerTop) fingerY.coerceIn(containerTop, containerBottom) else fingerY
        val at = dragInsertIndex(orderState, from, y, containerTop, containerBottom) { rowSpan(it) }
        if (at < 0) return
        val item = orderState.removeAt(orderState.indexOf(from))
        orderState.add(at, item)
        reordered = true
    }

    /**
     * 边缘自动翻滚：手指进入容器上下边缘后按深度滚一帧（E = clamp(容器高×15%, 44dp, 88dp)，
     * v = 1 + (depth/E)×13 dp/帧，同参考实现）。
     *
     * 先按**当前布局**命中、再滚动：滚动要到下一帧布局后才反映在行的位置上，滚完立刻命中会拿滚动
     * 之前的位置判断，被拖行每帧都会偏一截、在相邻两档之间来回跳。下一帧布局后的位置由下一次
     * 调用读到。
     */
    internal fun autoScroll(edgeMin: Float, edgeMax: Float, velMin: Float, velRange: Float) {
        if (dragging < 0) return
        val scroller = scrollBy ?: return
        val span = containerBottom - containerTop
        if (span <= 0f) return
        val edge = (span * 0.15f).coerceIn(edgeMin, edgeMax)
        val up = (containerTop + edge) - fingerY
        val down = fingerY - (containerBottom - edge)
        if (up <= 0f && down <= 0f) return
        val depth = maxOf(up, down).coerceIn(0f, edge)
        val v = velMin + (depth / edge) * velRange
        step()
        scroller(if (up > down) -v else v)
    }
}

@Composable
internal fun rememberDragSortState(
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
): DragSortState {
    val state = remember { DragSortState() }
    val move = rememberUpdatedState(onMove)
    // 普通字段（非快照状态）：拖动中每帧改快照状态只会让长列表反复重组
    state.onMove = { from, to -> move.value(from, to) }
    state.syncCount(count)
    // 拖动期间的边缘自动翻滚：只在拖动存在时跑帧循环，松手立刻停（不做常驻定时器）
    val density = LocalDensity.current
    val edgeMin = with(density) { 44.dp.toPx() }
    val edgeMax = with(density) { 88.dp.toPx() }
    val velMin = with(density) { 1.dp.toPx() }
    val velRange = with(density) { 13.dp.toPx() }
    val dragging = state.dragging
    LaunchedEffect(dragging) {
        if (dragging < 0) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            state.autoScroll(edgeMin, edgeMax, velMin, velRange)
        }
    }
    // 提交帧抑制 FLIP：下一帧把位移动画挂回去（见 [DragSortState.flipSuppressed]）
    val suppressed = state.flipSuppressed
    LaunchedEffect(suppressed) {
        if (!suppressed) return@LaunchedEffect
        withFrameNanos { }
        state.resumeFlip()
    }
    return state
}

/**
 * lazy 列表项修饰符：被拖行**不给**位移动画（即换即到位——参考实现：给它补滑移动画 = 不跟手），
 * 其余行补参考实现同款 FLIP 滑移（只有真的挪了位置的行才会动）。
 * 只关淡入淡出：这里是排序，不该顺带把滚入视口的行淡进来。
 *
 * @Composable：`Modifier.animateItem` 是列表项作用域里的组合期 API，包一层省得每个宿主各自判断。
 */
@Composable
internal fun LazyItemScope.dragSortItem(state: DragSortState, index: Int): Modifier =
    if (state.dragging == index || state.flipSuppressed) Modifier
    else Modifier.animateItem(fadeInSpec = null, placementSpec = FlipSpec, fadeOutSpec = null)

/** 行容器里的每一项都包一层：序号即列表顺序（[DragSortState.order] 里的原始下标）。 */
@Composable
internal fun DragSortRow(
    state: DragSortState,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val active = state.dragging == index
    val accent = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .then(state.rowModifier(index))
            .then(if (active) Modifier.dragChrome(accent) else Modifier),
    ) {
        // 内容降到 .55：淡底与虚线框在外层画，保持清晰（参考实现整行 opacity .55，边框也一起淡，
        // 这里把描边留在满不透明度——用户要的是「看得见虚线框」）
        Box(modifier = Modifier.then(if (active) Modifier.alpha(DragItemAlpha) else Modifier)) {
            content()
        }
    }
}

/** 拖动中的行：强调色淡底（画在内容后）+ 1.5dp 虚线描边（画在内容上）+ 淡阴影。 */
private fun Modifier.dragChrome(accent: Color): Modifier =
    this
        .shadow(
            elevation = 6.dp,
            shape = RoundedCornerShape(DragItemRadius),
            clip = false,
            ambientColor = accent.copy(alpha = 0.18f),
            spotColor = accent.copy(alpha = 0.18f),
        )
        .drawBehind {
            drawRoundRect(
                color = accent.copy(alpha = DragItemTint),
                cornerRadius = CornerRadius(DragItemRadius.toPx()),
            )
        }
        .drawWithContent {
            drawContent()
            val inset = DragItemOutlineInset.toPx()
            val radius = (DragItemRadius.toPx() - inset).coerceAtLeast(0f)
            drawRoundRect(
                color = accent,
                topLeft = Offset(inset, inset),
                size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(radius),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)),
                ),
            )
        }

/** 拖动排序的把手：与 WebUI `.drag-handle` 同形（两列三点），按住即拖。 */
@Composable
internal fun DragHandle(state: DragSortState, index: Int, modifier: Modifier = Modifier) {
    val tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .size(40.dp)
            .then(state.handleModifier(index))
            .semantics { contentDescription = "按住拖动排序" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val r = size.minDimension * 0.13f
            val xs = listOf(size.width * 0.3f, size.width * 0.7f)
            val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
            for (x in xs) {
                for (y in ys) drawCircle(color = tint, radius = r, center = Offset(x, y))
            }
        }
    }
}

/**
 * 行内的小图标按钮（编辑 / 删除）：对齐 mihomo_box WebUI 的 `.mini-btn`——小方块、淡底、
 * 图标居中，取代此前满屏的「编辑 / 删」文字按钮。
 */
@Composable
internal fun MiniIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // onClick 必须是最后一个参数：47 处调用全用尾随 lambda（`MiniIconButton(icon, desc) { … }`），
    // 排到 modifier 前面时 lambda 会绑到 Modifier 上，K2 直接报 "No value passed for parameter 'onClick'"
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        minWidth = 36.dp,
        minHeight = 36.dp,
        cornerRadius = 10.dp,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}
