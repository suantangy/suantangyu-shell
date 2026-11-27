package com.suantangyu.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** 3.80.0 原生队列（一期试点）：数据由 MainActivity 从脑快照灌入，
 * 命令经 MainActivity 的回调出去（播歌走脑、编辑走网页钩子）。 */
data class QItem(val key: String, val name: String, val artist: String, val cover: String)

object NativeQueueStore {
    var visible by mutableStateOf(false)
    var items by mutableStateOf<List<QItem>>(emptyList())
    var curKey by mutableStateOf("")
    var playing by mutableStateOf(false)
    var dark by mutableStateOf(false)
    var accent by mutableStateOf(Color(0xFFE4002B))
    var editMode by mutableStateOf(false)
    var hideSignal by mutableStateOf(0)
}

object CoverCache {
    private val cache = LruCache<String, Bitmap>(48)

    fun get(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        if (url.isEmpty() || !url.startsWith("http")) return null
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                conn.inputStream.use { ins ->
                    val bytes = ins.readBytes()
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }

    /** 低清版(24px):给播放页氛围底当模糊源,键加 |tiny 后缀独立缓存 */
    suspend fun loadTiny(url: String): Bitmap? {
        if (url.isEmpty() || !url.startsWith("http")) return null
        val key = "$url|tiny"
        cache.get(key)?.let { return it }
        val full = load(url) ?: return null
        val tiny = try {
            Bitmap.createScaledBitmap(full, 24, 24, true)
        } catch (_: Exception) {
            full
        }
        cache.put(key, tiny)
        return tiny
    }

    /** 强模糊版(3.84.9 按网页 .np-bg 几何重做):网页背景图是封面
     *  以 145% 尺寸居中、object-fit:cover 铺放,屏幕实际只看到封面
     *  的中央窗(x 34.5%–65.5%、y 15.5%–84.5%),位置对位置对应。
     *  这里取含模糊外溢的窗口(x 28.5%–71.5%、y 9.5%–90.5%)逐块
     *  面积平均到 9x8(面积平均而非采样缩放,防亮笔画成白斑——
     *  3.84.4 教训),再裁出可见窗约 7x6 直接返回,绘制时拉满全屏。
     *  糊度由 21 首网页版截图离线全栈模拟拟合(条带均差 8.2);
     *  键加 |blur 后缀独立缓存 */
    suspend fun loadBlurred(url: String): Bitmap? {
        if (url.isEmpty() || !url.startsWith("http")) return null
        val key = "$url|blur"
        cache.get(key)?.let { return it }
        val full = load(url) ?: return null
        val blurred = try {
            val wx0 = (full.width * 0.285f).toInt()
            val wx1 = (full.width * 0.715f).toInt()
            val wy0 = (full.height * 0.095f).toInt()
            val wy1 = (full.height * 0.905f).toInt()
            val ww = wx1 - wx0
            val wh = wy1 - wy0
            val tw = 9
            val th = 8
            val px = IntArray(full.width * full.height)
            full.getPixels(px, 0, full.width, 0, 0, full.width, full.height)
            val small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            for (oy in 0 until th) {
                val y0 = wy0 + oy * wh / th
                val y1 = maxOf(y0 + 1, wy0 + (oy + 1) * wh / th)
                for (ox in 0 until tw) {
                    val x0 = wx0 + ox * ww / tw
                    val x1 = maxOf(x0 + 1, wx0 + (ox + 1) * ww / tw)
                    var r = 0L; var g = 0L; var b = 0L; var n = 0L
                    for (yy in y0 until y1) {
                        var idx = yy * full.width + x0
                        for (xx in x0 until x1) {
                            val c = px[idx++]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            n++
                        }
                    }
                    small.setPixel(
                        ox, oy,
                        android.graphics.Color.rgb(
                            (r / n).toInt(), (g / n).toInt(), (b / n).toInt()
                        )
                    )
                }
            }
            // 裁可见窗:外溢占窗口宽 0.06/0.43、高 0.06/0.81
            val sx = Math.round(tw * 0.06f / 0.43f)
            val sy = Math.round(th * 0.06f / 0.81f)
            Bitmap.createBitmap(small, sx, sy, tw - 2 * sx, th - 2 * sy)
        } catch (_: Exception) {
            full
        }
        cache.put(key, blurred)
        return blurred
    }
}

/** 封面 Bitmap 助手(队列/播放页共用;tiny=低清版,blurred=强模糊版) */
@Composable
fun CoverBitmap(url: String, tiny: Boolean = false, blurred: Boolean = false): Bitmap? {
    val key = when {
        blurred -> "$url|blur"
        tiny -> "$url|tiny"
        else -> url
    }
    var bmp by remember(key) { mutableStateOf(CoverCache.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null && url.isNotEmpty()) {
            bmp = when {
                blurred -> CoverCache.loadBlurred(url)
                tiny -> CoverCache.loadTiny(url)
                else -> CoverCache.load(url)
            }
        }
    }
    return bmp
}

@Composable
private fun CoverImage(url: String) {
    var bmp by remember(url) { mutableStateOf(CoverCache.get(url)) }
    LaunchedEffect(url) { if (bmp == null) bmp = CoverCache.load(url) }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEEEEEE)))
    }
}

@Composable
private fun PlayingWave(accent: Color, playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "qwave")
    Row(
        modifier = Modifier.padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 5) {
            val h by transition.animateFloat(
                initialValue = if (playing) 5f else 8f,
                targetValue = if (playing) 15f else 8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 320 + i * 70, delayMillis = i * 60),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "qw$i"
            )
            Box(
                Modifier
                    .padding(horizontal = 1.2.dp)
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

/** 3.84.16 自绘队列弹层(代 ModalBottomSheet):开/关/回位全走定长
 * 补间,与播放页同规格(cheng 定:两个面、打开关闭都滑、都一样)。
 * M3 sheet 的弹簧在本工程无法换规格,故自绘:遮罩淡入 + 面板平移,
 * 拖动关闭两路——标题行拖拽 + 列表到顶后继续下拉(嵌套滚动接管),
 * 与 M3 时的行为保持一致。 */
@Composable
fun NativeQueueRoot(
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onLoadMore: () -> Unit,
    onHidden: () -> Unit
) {
    val store = NativeQueueStore
    if (!store.visible) return
    val dark = store.dark
    val accent = store.accent
    val surface = if (dark) Color(0xFF1C1C1E) else Color.White
    val nameColor = if (dark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val subColor = if (dark) Color(0x73EBEBF5) else Color(0x8C3C3C43)
    val titleColor = if (dark) Color(0xADEBEBF5) else Color(0xB83C3C43)
    val curBg = accent.copy(alpha = if (dark) 0.18f else 0.10f)

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheetHState = remember { mutableStateOf(0) }
    val offsetY = remember { Animatable(10000f) }
    var closing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun rebound() {
        scope.launch {
            val h = sheetHState.value
            val frac = if (h > 0) (offsetY.value / h).coerceIn(0f, 1f) else 0f
            val dur = (350 * frac).toInt().coerceIn(120, 350)
            try {
                offsetY.animateTo(
                    0f, tween(durationMillis = dur, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                )
            } catch (_: Exception) {
            }
        }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            val h = sheetHState.value
            try {
                val remaining = if (h > 0) ((h - offsetY.value) / h).coerceIn(0f, 1f) else 1f
                val dur = (300 * remaining).toInt().coerceIn(120, 300)
                offsetY.animateTo(
                    if (h > 0) h.toFloat() else 10000f,
                    tween(durationMillis = dur, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
                )
            } catch (_: Exception) {
            }
            store.visible = false
            onHidden()
        }
    }

    // 打开:面板量到高度后先瞬移贴底,再补间滑入(与播放页同规格)
    LaunchedEffect(sheetHState.value) {
        val h = sheetHState.value
        if (h > 0 && offsetY.value > h) {
            offsetY.snapTo(h.toFloat())
            offsetY.animateTo(
                0f, tween(durationMillis = 350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
            )
        }
    }
    // hideSignal 跨开关累加:以本次合成值为基线(同播放页 3.82.2 教训)
    val openSignal = remember { store.hideSignal }
    LaunchedEffect(store.hideSignal) { if (store.hideSignal > openSignal) dismiss() }

    // 列表嵌套滚动接管:面板被拖下一截时,上拖先把面板推回去;列表
    // 到顶还继续下拉,未消费的量转成面板下移;松手按速度/位移定去留
    val sheetNested = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0f && offsetY.value > 0f) {
                val newOff = (offsetY.value + available.y).coerceAtLeast(0f)
                val consumedY = newOff - offsetY.value
                scope.launch { offsetY.snapTo(newOff) }
                return Offset(0f, consumedY)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset, available: Offset, source: NestedScrollSource
        ): Offset {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            if (available.y > 0f && atTop) {
                val h = sheetHState.value
                val newOff = (offsetY.value + available.y)
                    .coerceIn(0f, if (h > 0) h.toFloat() else Float.MAX_VALUE)
                val consumedY = newOff - offsetY.value
                scope.launch { offsetY.snapTo(newOff) }
                return Offset(0f, consumedY)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (offsetY.value > 0f) {
                val h = sheetHState.value
                when {
                    available.y < -900f -> rebound()
                    available.y > 900f || offsetY.value > h * 0.35f -> dismiss()
                    else -> rebound()
                }
                return available
            }
            return Velocity.Zero
        }
    }

    val sheetH = sheetHState.value
    val progress = if (sheetH > 0) {
        (1f - (offsetY.value / sheetH).coerceIn(0f, 1f))
    } else 0f

    Box(Modifier.fillMaxSize()) {
        // 遮罩:随滑入淡入(满 0.32 黑,与 M3 同值),点击关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = sheetH > 0
                ) { dismiss() }
        )
        val items = store.items
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .onSizeChanged { sheetHState.value = it.height }
                .shadow(12.dp, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 8.dp)
        ) {
            // 标题行：播放列表 · N 首（居中）＋编辑/完成（右）；整行可拖关
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        var dragPos = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                dragPos = offsetY.value
                                scope.launch { offsetY.stop() }
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                dragPos = (dragPos + dy)
                                    .coerceIn(0f, if (sheetHState.value > 0) sheetHState.value.toFloat() else Float.MAX_VALUE)
                                tracker.addPosition(change.uptimeMillis, Offset(0f, dragPos))
                                scope.launch { offsetY.snapTo(dragPos) }
                            },
                            onDragEnd = {
                                val v = tracker.calculateVelocity().y
                                val h = sheetHState.value
                                when {
                                    v < -1200f -> rebound()
                                    offsetY.value > with(density) { 110.dp.toPx() } || v > 1200f -> dismiss()
                                    else -> rebound()
                                }
                            },
                            onDragCancel = { rebound() }
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "播放列表 · ${items.size} 首",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = titleColor, letterSpacing = 0.3.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                if (items.isNotEmpty()) {
                    Text(
                        text = if (store.editMode) "完成" else "编辑",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clickable { store.editMode = !store.editMode }
                            .padding(8.dp)
                    )
                }
            }
            if (items.isEmpty()) {
                Text(
                    text = "暂无歌曲", fontSize = 14.sp, color = subColor,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp)
                )
            } else {
                val screenH = LocalConfiguration.current.screenHeightDp
                var lastMoreAt by remember { mutableStateOf(0L) }
                LaunchedEffect(listState, items.size) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .collectLatest { last ->
                            if (last >= items.size - 3) {
                                val now = System.currentTimeMillis()
                                if (now - lastMoreAt > 1500) { lastMoreAt = now; onLoadMore() }
                            }
                        }
                }
                // 打开时滚到当前曲
                LaunchedEffect(Unit) {
                    val ci = items.indexOfFirst { it.key == store.curKey }
                    if (ci > 2) listState.scrollToItem(ci - 1)
                }
                val stridePx = with(density) { 68.dp.toPx() }
                var dragIdx by remember { mutableStateOf<Int?>(null) }
                var dragFrom by remember { mutableStateOf(0) }
                var dragDy by remember { mutableStateOf(0f) }
                LazyColumn(
                    state = listState,
                    userScrollEnabled = dragIdx == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (screenH * 0.54f).dp)
                        .padding(horizontal = 10.dp)
                        .nestedScroll(sheetNested)
                ) {
                    itemsIndexed(items, key = { idx, it -> "$idx|${it.key}" }) { i, item ->
                        val on = item.key == store.curKey
                        val isDragged = dragIdx == i
                        // 拖动预览位置(与网页版 preview() 同算法):序号实时按落点重排
                        var dispPos = i
                        var shiftTarget = 0
                        if (dragIdx != null) {
                            val steps = (dragDy / stridePx).roundToInt()
                            val to = (dragFrom + steps).coerceIn(0, items.size - 1)
                            dispPos = when {
                                isDragged -> to
                                steps > 0 && i in (dragFrom + 1)..to -> i - 1
                                steps < 0 && i in to until dragFrom -> i + 1
                                else -> i
                            }
                            if (!isDragged) {
                                shiftTarget = when {
                                    steps > 0 && i in (dragFrom + 1)..to -> -stridePx.roundToInt()
                                    steps < 0 && i in to until dragFrom -> stridePx.roundToInt()
                                    else -> 0
                                }
                            }
                        }
                        val shiftAnim by animateIntOffsetAsState(
                            targetValue = IntOffset(0, shiftTarget),
                            animationSpec = tween(180),
                            label = "qshift$i"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .zIndex(if (isDragged) 6f else 0f)
                                .offset {
                                    if (isDragged) IntOffset(0, dragDy.roundToInt()) else shiftAnim
                                }
                                .then(if (isDragged) Modifier.shadow(12.dp, RoundedCornerShape(12.dp)) else Modifier)
                                .clip(RoundedCornerShape(12.dp))
                                // 被拖行必须实底(网页版同款白卡):先铺 surface 实色,
                                // 当前曲再叠主题色淡染——3.80.0 只铺 10% 淡染,拖起来透出底下行、糊成一片
                                .background(if (isDragged) surface else Color.Transparent)
                                .background(if (on) curBg else Color.Transparent)
                                .clickable(enabled = !store.editMode) {
                                    if (item.key == store.curKey) dismiss() else onPlay(item.key)
                                }
                                .padding(10.dp)
                        ) {
                            CoverImage(item.cover)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${dispPos + 1}. ${item.name}",
                                    fontSize = 15.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (on) accent else nameColor,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = item.artist,
                                    fontSize = 12.sp, color = subColor,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (on) PlayingWave(accent, store.playing)
                            if (store.editMode) {
                                Text(
                                    text = "移除",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = accent,
                                    modifier = Modifier
                                        .clickable { onRemove(item.key) }
                                        .padding(horizontal = 6.dp, vertical = 8.dp)
                                )
                                Text(
                                    text = "≡",
                                    fontSize = 19.sp, color = subColor,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                        .pointerInput(i) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragIdx = i; dragFrom = i; dragDy = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume(); dragDy += amount.y
                                                },
                                                onDragEnd = {
                                                    val steps = (dragDy / stridePx).roundToInt()
                                                    val to = (dragFrom + steps).coerceIn(0, items.size - 1)
                                                    if (to != dragFrom) onMove(item.key, to)
                                                    dragIdx = null; dragDy = 0f
                                                },
                                                onDragCancel = { dragIdx = null; dragDy = 0f }
                                            )
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
