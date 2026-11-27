// NativePlayer.kt — 逐面原生化二期(3.82.0):播放页核心层。
// 网页播放页 1:1 核心:氛围底(封面低清放大当模糊)+标题/歌手/音质+原封面+
// 整行歌词(点行 seek/自动跟当前行)+喜欢+进度+模式/上下首/播放/队列+
// 倍速/定时/网页版入口。其余(海报/跟唱/音质切换/切源/故事/评论/皮肤/
// 弹幕/音效/漫游/波形进度)走「网页版」回网页播放页,v1 不搬。
// 状态读脑 playerUiSnapshot(标题/封面/播放/进度锚点/时长/模式/倍速);
// 歌词与喜欢由网页 __aqPlayerPush 经 postPlayerData 推(壳开页后 2 秒轮询要)。
// 开合=弹簧整页平移(Compose 合成器执行,不是 WebView 的 JS 动画,不受
// 1_173/1_174 那条结案影响);顶部区可下拉关闭,松手弹簧吸附。
package com.suantangyu.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PlayerLine(val t: Long, val x: String)

object NativePlayerStore {
    var visible by mutableStateOf(false)
    var hideSignal by mutableStateOf(0)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var cover by mutableStateOf("")
    var quality by mutableStateOf("")
    var fav by mutableStateOf(false)
    var lyrics by mutableStateOf<List<PlayerLine>>(emptyList())
    var playing by mutableStateOf(false)
    var mode by mutableStateOf("list")
    var rate by mutableStateOf(1f)
    var anchorPosMs by mutableStateOf(0L)
    var anchorAtMs by mutableStateOf(0L)
    var durMs by mutableStateOf(0L)
    var sleepLabel by mutableStateOf("定时")
    var accent by mutableStateOf(Color(0xFFE4002B))

    fun livePosMs(): Long {
        var p = anchorPosMs
        if (playing && anchorAtMs > 0) {
            p += ((SystemClock.elapsedRealtime() - anchorAtMs) * rate).toLong()
        }
        if (durMs > 0) p = p.coerceIn(0, durMs)
        return maxOf(0, p)
    }
}

private const val P_PLAY = "M8 5v14l11-7z"
private const val P_PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
private const val P_PREV = "M6 6h2v12H6zm3.5 6l8.5 6V6z"
private const val P_NEXT = "M6 18l8.5-6L6 6v12zM16 6h2v12h-2z"
private const val P_REPEAT = "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"
private const val P_SHUFFLE =
    "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.83l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
private const val P_HEART =
    "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z"
private const val P_LIST = "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"
private const val P_MOON = "M21 14.3A8.5 8.5 0 1 1 9.7 3 7 7 0 0 0 21 14.3z"
private const val P_GRID = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"

@Composable
private fun SvgIcon(pathData: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path: Path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawContext.transform.scale(s, s)
        drawPath(path, color)
    }
}

private fun fmtTime(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

@Composable
fun NativePlayerRoot(
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onMode: () -> Unit,
    onFav: () -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (Int, Boolean, String) -> Unit,
    onQueue: () -> Unit,
    onOpenWeb: () -> Unit
) {
    val st = NativePlayerStore
    if (!st.visible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val screenHPx = with(density) { conf.screenHeightDp.dp.toPx() }
    val offsetY = remember { Animatable(screenHPx) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offsetY.animateTo(screenHPx, spring(dampingRatio = 0.92f, stiffness = 300f))
            st.visible = false
            onClose()
        }
    }
    LaunchedEffect(st.hideSignal) { if (st.hideSignal > 0) close() }
    LaunchedEffect(Unit) {
        offsetY.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 260f))
    }
    val dragMod = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { scope.launch { offsetY.stop() } },
            onVerticalDrag = { _, dy ->
                scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) }
            },
            onDragEnd = {
                if (offsetY.value > 130.dp.toPx()) close()
                else scope.launch {
                    offsetY.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 300f))
                }
            },
            onDragCancel = {
                scope.launch { offsetY.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 300f)) }
            }
        )
    }

    // 显示进度(200ms 一跳;拖动时以拖动值为准)
    var dispPos by remember { mutableStateOf(st.livePosMs()) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!seeking) dispPos = st.livePosMs()
            delay(200)
        }
    }
    val shownPos = if (seeking) seekPos else dispPos
    val accent = st.accent
    val wht = Color.White
    val w70 = Color.White.copy(alpha = 0.72f)
    val w55 = Color.White.copy(alpha = 0.55f)
    val w50 = Color.White.copy(alpha = 0.5f)

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .background(Color(0xFF101013))
    ) {
        // 氛围底:封面低清图放大铺满(天然模糊)+暗罩,与网页 np-bg 同思路
        val blurBmp = CoverBitmap(st.cover, tiny = true)
        if (blurBmp != null) {
            Image(
                blurBmp.asImageBitmap(), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(26.dp))
            // 抓手
            Box(
                Modifier.fillMaxWidth().then(dragMod).padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(width = 38.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }
            // 信息区(可拖关闭)
            Column(Modifier.then(dragMod)) {
                Text(
                    st.title.ifEmpty { "—" }, color = wht, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    st.artist, color = w70, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (st.quality.isNotEmpty()) {
                    Text(
                        "音质 ${st.quality}", color = w50, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // 封面(原封面;72vw 上限 320dp、圆角 16——与网页 .np-art 一致;可拖关闭)
            val artSize = minOf(conf.screenWidthDp * 0.72f, 320f).dp
            Box(Modifier.fillMaxWidth().then(dragMod), contentAlignment = Alignment.Center) {
                val bmp = CoverBitmap(st.cover)
                Box(
                    Modifier.size(artSize)
                        .shadow(18.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2A2A2E))
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // 歌词(整行;点行 seek;自动跟当前行,手动滚动后 3 秒内不抢)
            val lines = st.lyrics
            val curIdx = remember(shownPos, lines) {
                var idx = -1
                for (i in lines.indices) {
                    if (lines[i].t <= shownPos) idx = i else break
                }
                idx
            }
            if (lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("暂无歌词", color = w50, fontSize = 14.sp)
                }
            } else {
                val listState = rememberLazyListState()
                var holdUntil by remember { mutableStateOf(0L) }
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) {
                        holdUntil = SystemClock.elapsedRealtime() + 3000
                    }
                }
                LaunchedEffect(curIdx) {
                    if (curIdx >= 0 && SystemClock.elapsedRealtime() > holdUntil) {
                        try {
                            listState.animateScrollToItem(curIdx, scrollOffset = -120)
                        } catch (_: Exception) {
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                ) {
                    itemsIndexed(lines, key = { i, l -> "$i|${l.t}" }) { i, line ->
                        val on = i == curIdx
                        Text(
                            line.x,
                            color = if (on) wht else w55,
                            fontSize = if (on) 16.sp else 14.5.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSeek(line.t) }
                                .padding(vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 喜欢(行右端,与网页版同位:进度条上方右侧)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .clickable { onFav() },
                    contentAlignment = Alignment.Center
                ) {
                    SvgIcon(P_HEART, 23.dp, if (st.fav) accent else wht)
                }
            }
            // 进度条(线条版:主题色填充+圆钮;波形样式 v1 不搬)
            val dur = maxOf(st.durMs, 1L)
            Box(
                Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(dur) {
                        fun fracOf(x: Float) = (x / size.width).coerceIn(0f, 1f)
                        detectDragGestures(
                            onDragStart = { o ->
                                seeking = true
                                seekPos = (dur * fracOf(o.x)).toLong()
                            },
                            onDrag = { change, _ ->
                                seekPos = (dur * fracOf(change.position.x)).toLong()
                            },
                            onDragEnd = { onSeek(seekPos); seeking = false },
                            onDragCancel = { seeking = false }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                val frac = (shownPos.toFloat() / dur).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                )
                Box(
                    Modifier.fillMaxWidth(frac).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Box(
                    Modifier.fillMaxWidth(frac),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        Modifier.offset { IntOffset(6.dp.roundToPx(), 0) }
                            .size(13.dp).clip(CircleShape).background(accent)
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(shownPos), color = w55, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(st.durMs), color = w55, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            // 控制行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable { onMode() },
                    contentAlignment = Alignment.Center
                ) {
                    when (st.mode) {
                        "shuffle" -> SvgIcon(P_SHUFFLE, 25.dp, wht)
                        "single" -> Box(contentAlignment = Alignment.Center) {
                            SvgIcon(P_REPEAT, 25.dp, wht)
                            Text(
                                "1", color = wht, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(y = 1.dp)
                            )
                        }
                        else -> SvgIcon(P_REPEAT, 25.dp, wht)
                    }
                }
                Box(
                    Modifier.size(52.dp).clip(CircleShape).clickable { onPrev() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_PREV, 32.dp, wht) }
                Box(
                    Modifier.size(68.dp).clip(CircleShape).clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(if (st.playing) P_PAUSE else P_PLAY, 46.dp, wht) }
                Box(
                    Modifier.size(52.dp).clip(CircleShape).clickable { onNext() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_NEXT, 32.dp, wht) }
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable { onQueue() },
                    contentAlignment = Alignment.Center
                ) { SvgIcon(P_LIST, 25.dp, wht) }
            }
            Spacer(Modifier.height(14.dp))
            // 工具行(v1:倍速/定时/网页版;其余功能从网页版进)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                            val cur = steps.minByOrNull { kotlin.math.abs(it - st.rate) } ?: 1f
                            val next = steps[(steps.indexOf(cur) + 1) % steps.size]
                            onRate(next)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        (if (st.rate == st.rate.toLong().toFloat()) "${st.rate.toLong()}.0x" else "${st.rate}x"),
                        color = wht, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text("倍速", color = w55, fontSize = 10.5.sp)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val opts = listOf(
                                Triple(0, false, "定时"),
                                Triple(15, false, "15分"),
                                Triple(30, false, "30分"),
                                Triple(60, false, "60分"),
                                Triple(0, true, "本首后")
                            )
                            val idx = opts.indexOfFirst { it.third == st.sleepLabel }
                            val next = opts[(idx + 1) % opts.size]
                            onSleep(next.first, next.second, next.third)
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    SvgIcon(P_MOON, 21.dp, wht)
                    Text(st.sleepLabel, color = w55, fontSize = 10.5.sp)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenWeb() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    SvgIcon(P_GRID, 21.dp, wht)
                    Text("网页版", color = w55, fontSize = 10.5.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
