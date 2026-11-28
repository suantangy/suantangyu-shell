package com.suantangyu.player

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt

// 3.86.0 原生主页：网页（1_184）把主页六区算好推快照，壳只渲染＋把点击
// 转发回网页隐藏 DOM 的真实 click（播歌上下文/开详情/换一批全走网页原逻辑）。
// 主页内容同时录入 GraphicsLayer，Dock 在主页上方时改画该层＋RenderEffect
// 模糊＝真毛玻璃第一步（其余页面仍走 WebView 截图糊）。

data class HomeDuo(val show: Boolean, val title: String, val sub: String, val cover: String)
data class HomeCard(val cover: String, val name: String, val sub: String)
data class HomeChannel(val name: String, val mark: String, val g1: String, val g2: String)
data class HomeNsRow(val cover: String, val name: String, val sub: String)
data class HomeHot(
    val cover: String, val name: String, val sub: String, val dur: String,
    val src: String, val srcPlat: String, val fav: Boolean
)

object NativeHomeStore {
    var enabled by mutableStateOf(false)
    var rectLeftPx by mutableStateOf(0)
    var rectTopPx by mutableStateOf(0)
    var rectWidthPx by mutableStateOf(0)
    var rectHeightPx by mutableStateOf(0)
    var plat by mutableStateOf("qq")
    var dark by mutableStateOf(false)
    var duoPlat by mutableStateOf("")
    var duoGuess by mutableStateOf(HomeDuo(false, "", "", ""))
    var duoRadar by mutableStateOf(HomeDuo(false, "", "", ""))
    var duoAi by mutableStateOf(HomeDuo(false, "", "", ""))
    var rec by mutableStateOf<List<HomeCard>>(emptyList())
    var chView by mutableStateOf("channel")
    var channels by mutableStateOf<List<HomeChannel>>(emptyList())
    var playlists by mutableStateOf<List<HomeCard>>(emptyList())
    var nsMsg by mutableStateOf("")
    var nsCards by mutableStateOf<List<List<HomeNsRow>>>(emptyList())
    var hot by mutableStateOf<List<HomeHot>>(emptyList())
    var rankMsg by mutableStateOf("")
    var ranks by mutableStateOf<List<HomeCard>>(emptyList())
    var homeLayer by mutableStateOf<GraphicsLayer?>(null)
    // 3.86.1：站源菜单开着时原生主页让位（菜单挂在网页 header 下、垂进主页区，
    // 不让位会被原生层盖住点不了）
    var platMenuOpen by mutableStateOf(false)

    fun visibleNow(): Boolean =
        enabled && NativeDockStore.page == "home" && !NativeDockStore.detail &&
            !platMenuOpen && rectWidthPx > 0 && rectHeightPx > 0

    fun blurActive(): Boolean =
        visibleNow() && homeLayer != null && Build.VERSION.SDK_INT >= 31

    fun parse(json: String) {
        try {
            val o = JSONObject(json)
            val r = o.optJSONArray("rect")
            if (r != null && r.length() == 4) {
                rectLeftPx = r.optInt(0); rectTopPx = r.optInt(1)
                rectWidthPx = r.optInt(2); rectHeightPx = r.optInt(3)
            }
            plat = o.optString("plat", "qq")
            dark = o.optBoolean("dark")
            duoPlat = o.optString("duoPlat", "")
            platMenuOpen = o.optBoolean("platMenu", false)
            val duo = o.optJSONObject("duo")
            if (duo != null) {
                duoGuess = duoOf(duo.optJSONObject("guess"), duoGuess)
                duoRadar = duoOf(duo.optJSONObject("radar"), duoRadar)
                duoAi = duoOf(duo.optJSONObject("ai"), duoAi)
            }
            val recArr = o.optJSONArray("rec")
            rec = List(recArr?.length() ?: 0) { i ->
                val c = recArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), c.optString("artist"))
            }
            chView = o.optString("chView", "channel")
            val chArr = o.optJSONArray("channels")
            channels = List(chArr?.length() ?: 0) { i ->
                val c = chArr!!.optJSONObject(i) ?: JSONObject()
                HomeChannel(
                    c.optString("name"), c.optString("mark"),
                    c.optString("g1"), c.optString("g2")
                )
            }
            val plArr = o.optJSONArray("playlists")
            playlists = List(plArr?.length() ?: 0) { i ->
                val c = plArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), c.optString("src"))
            }
            nsMsg = o.optString("nsMsg", "")
            val nsArr = o.optJSONArray("nsCards")
            nsCards = List(nsArr?.length() ?: 0) { ci ->
                val card = nsArr!!.optJSONArray(ci)
                List(card?.length() ?: 0) { ri ->
                    val c = card!!.optJSONObject(ri) ?: JSONObject()
                    HomeNsRow(c.optString("cover"), c.optString("name"), c.optString("sub"))
                }
            }
            val hotArr = o.optJSONArray("hot")
            hot = List(hotArr?.length() ?: 0) { i ->
                val c = hotArr!!.optJSONObject(i) ?: JSONObject()
                HomeHot(
                    c.optString("cover"), c.optString("name"), c.optString("sub"),
                    c.optString("dur"), c.optString("src"), c.optString("srcPlat"),
                    c.optBoolean("fav")
                )
            }
            rankMsg = o.optString("rankMsg", "")
            val rkArr = o.optJSONArray("ranks")
            ranks = List(rkArr?.length() ?: 0) { i ->
                val c = rkArr!!.optJSONObject(i) ?: JSONObject()
                HomeCard(c.optString("cover"), c.optString("name"), "")
            }
        } catch (_: Exception) {
        }
    }

    private fun duoOf(o: JSONObject?, prev: HomeDuo): HomeDuo {
        if (o == null) return prev
        return HomeDuo(
            o.optBoolean("show", true), o.optString("title"),
            o.optString("sub"), o.optString("cover")
        )
    }
}

private class HomePal(dark: Boolean) {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFF3F3F6)
    val surface = if (dark) Color(0xFF1C1C1E) else Color(0xFFFAFAFC)
    val surface2 = if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEEF)
    val label = if (dark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val label2 = if (dark) Color(0x99EBEBF5) else Color(0xB83C3C43)
    val label3 = if (dark) Color(0x73EBEBF5) else Color(0x8C3C3C43)
    val separator = if (dark) Color(0x26FFFFFF) else Color(0x2E3C3C43)
    val nsBg = if (dark) Color(0x1A767680) else Color(0x8CFFFFFF)
    val nsBorder = if (dark) Color(0x24767680) else Color(0x14502814)
}

private fun hexColor(hex: String, fallback: Color): Color {
    if (hex.isEmpty()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

private val DUO_ACCENTS = mapOf(
    "qq" to listOf(0xFF31C27C, 0xFF0A84FF, 0xFFA855F7),
    "netease" to listOf(0xFFEC4141, 0xFF0A84FF, 0xFFFF9F0A),
    "kuwo" to listOf(0xFFFF6A00, 0xFF0A84FF, 0xFFA855F7),
    "kugou" to listOf(0xFF2CA2F9, 0xFF30D158, 0xFFA855F7),
    "migu" to listOf(0xFFED4141, 0xFF0A84FF, 0xFFFF9F0A)
)
private val SRC_COLORS = mapOf(
    "qq" to Color(0xFF31C27C), "netease" to Color(0xFFEC4141),
    "kuwo" to Color(0xFFFF6A00), "kugou" to Color(0xFF2CA2F9),
    "migu" to Color(0xFFED4141)
)
private const val HEART_PATH =
    "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"

@Composable
private fun Modifier.homePress(scaleTo: Float = 0.97f, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) scaleTo else 1f, label = "homePress")
    return this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
private fun HomeCover(url: String, modifier: Modifier, radius: androidx.compose.ui.unit.Dp, bg: Color) {
    val bmp = CoverBitmap(url)
    Box(modifier.clip(RoundedCornerShape(radius)).background(bg)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun NativeHomeRoot(onAction: (String, String) -> Unit) {
    val st = NativeHomeStore
    if (!st.enabled) return
    val vis = st.visibleNow()
    val pal = remember(st.dark) { HomePal(st.dark) }
    val density = LocalDensity.current
    val wDp = with(density) { st.rectWidthPx.toDp() }
    val hDp = with(density) { st.rectHeightPx.toDp() }
    val xDp = with(density) { st.rectLeftPx.toDp() }
    val yDp = with(density) { st.rectTopPx.toDp() }
    val layer = rememberGraphicsLayer()
    var layerFxSet by remember { mutableStateOf(false) }
    SideEffect {
        st.homeLayer = layer
        if (!layerFxSet && Build.VERSION.SDK_INT >= 31) {
            layerFxSet = true
            try {
                layer.renderEffect = androidx.compose.ui.graphics.BlurEffect(
                    110f, 110f, TileMode.Decal
                )
                // 3.86.1：网页玻璃是 saturate(220%) blur(48px)——只糊不饱和，
                // 在浅色主页上看着像白色透明；给图层补 2.2 饱和（色矩阵）
                val s = 2.2f
                layer.colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
                            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
                            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            } catch (_: Exception) {
            }
        }
    }
    val listState = rememberLazyListState()
    // 原生主页滚动驱动 Dock 收起（网页主页没在滚、它的收起值已过期）：
    // 下滚收起、上滚展开、回顶必展开
    LaunchedEffect(listState) {
        var lastTotal = -1
        snapshotFlow {
            listState.firstVisibleItemIndex * 100000 + listState.firstVisibleItemScrollOffset
        }.collect { total ->
            if (NativeHomeStore.visibleNow()) {
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= 4
                ) {
                    NativeDockStore.collapsed = false
                } else if (lastTotal >= 0) {
                    val d = total - lastTotal
                    if (d > 28) NativeDockStore.collapsed = true
                    else if (d < -28) NativeDockStore.collapsed = false
                }
            }
            lastTotal = total
        }
    }
    LaunchedEffect(vis) {
        if (vis && listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 4
        ) {
            NativeDockStore.collapsed = false
        }
    }
    Box(
        Modifier
            .offset {
                if (vis) IntOffset(st.rectLeftPx, st.rectTopPx)
                else IntOffset(12000, st.rectTopPx)
            }
            .size(wDp, hDp)
            .clipToBounds()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (vis) {
                        try {
                            val cds = this
                            // 3.86.1：图层只录底部 240dp 条（Dock 只采样这一带），
                            // 录制/光栅成本降一大截，滑动更跟手
                            val strip = 240.dp.toPx()
                            layer.record {
                                clipRect(
                                    0f, size.height - strip, size.width, size.height
                                ) {
                                    with(cds) { drawContent() }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    drawContent()
                }
                .background(pal.bg)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 150.dp)
            ) {
                // —— 双卡 ——
                item(key = "duo") {
                    val accents = DUO_ACCENTS[st.duoPlat.ifEmpty { st.plat }]
                        ?: DUO_ACCENTS["qq"]!!
                    val cards = mutableListOf<Triple<HomeDuo, String, Int>>()
                    cards.add(Triple(st.duoGuess, "猜你喜欢", 0))
                    cards.add(Triple(st.duoRadar, "听歌雷达", 1))
                    if (st.duoAi.show) cards.add(Triple(st.duoAi, "智能推荐", 2))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                    ) {
                        cards.forEach { (duo, kicker, ai) ->
                            DuoCard(
                                duo, kicker, Color(accents[ai]), pal,
                                Modifier.weight(1f)
                            ) {
                                onAction(
                                    "duo",
                                    when (ai) {
                                        1 -> "radar"; 2 -> "ai"; else -> "guess"
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
                // —— 为你推荐 ——
                item(key = "recHead") {
                    SecHead("为你推荐", "换一批", pal) { onAction("recRefresh", "") }
                }
                item(key = "recRow") {
                    if (st.rec.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 4.dp)
                        ) {
                            itemsIndexed(st.rec, key = { i, c -> "rec$i${c.name}" }) { i, c ->
                                Column(
                                    Modifier
                                        .width(164.dp)
                                        .homePress { onAction("rec", i.toString()) }
                                ) {
                                    HomeCover(
                                        c.cover, Modifier.size(164.dp), 10.dp, pal.surface2
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.Text(
                                        c.name, color = pal.label, fontSize = 13.sp,
                                        fontWeight = FontWeight(500), maxLines = 1,
                                        lineHeight = 19.sp,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (c.sub.isNotEmpty()) {
                                        androidx.compose.material3.Text(
                                            c.sub, color = pal.label2, fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 频道/歌单 ——
                item(key = "chHead") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        androidx.compose.material3.Text(
                            if (st.chView == "playlist") "热门歌单" else "频道",
                            color = pal.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.66).sp, lineHeight = 32.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(
                                "换一批", color = NativeDockStore.accent, fontSize = 17.sp,
                                fontWeight = FontWeight(600),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAction("chRefresh", "") }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(120, 120, 128).copy(alpha = 0.12f))
                                    .padding(2.dp)
                            ) {
                                ChTab("频道", st.chView == "channel", pal) {
                                    onAction("chTab", "channel")
                                }
                                ChTab("歌单", st.chView == "playlist", pal) {
                                    onAction("chTab", "playlist")
                                }
                            }
                        }
                    }
                }
                item(key = "chGrid") {
                    if (st.chView == "playlist") {
                        Grid3(st.playlists.size) { i ->
                            val c = st.playlists[i]
                            Column(
                                Modifier.homePress {
                                    onAction("playlist", i.toString())
                                }
                            ) {
                                HomeCover(
                                    c.cover,
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    12.dp, pal.surface2
                                )
                                Spacer(Modifier.height(6.dp))
                                androidx.compose.material3.Text(
                                    c.name, color = pal.label, fontSize = 12.sp,
                                    fontWeight = FontWeight(600), maxLines = 2,
                                    lineHeight = 15.6.sp, overflow = TextOverflow.Ellipsis
                                )
                                if (c.sub.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    androidx.compose.material3.Text(
                                        c.sub, color = pal.label3, fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else {
                        Grid3(st.channels.size) { i ->
                            val ch = st.channels[i]
                            Column(
                                Modifier.homePress {
                                    onAction("channel", i.toString())
                                }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    hexColor(ch.g1, pal.surface2),
                                                    hexColor(ch.g2, pal.surface2)
                                                )
                                            )
                                        )
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.48f)
                                            .align(Alignment.TopStart)
                                            .offset(x = (-14).dp, y = (-16).dp)
                                            .border(
                                                1.5.dp, Color.White.copy(alpha = 0.12f),
                                                CircleShape
                                            )
                                    )
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.7f)
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 20.dp, y = 24.dp)
                                            .border(
                                                1.5.dp, Color.White.copy(alpha = 0.18f),
                                                CircleShape
                                            )
                                    )
                                    Column(
                                        Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        androidx.compose.material3.Text(
                                            ch.mark, color = Color.White, fontSize = 22.sp,
                                            fontWeight = FontWeight(800),
                                            letterSpacing = (-0.44).sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        androidx.compose.material3.Text(
                                            "CHANNEL", color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 10.sp, fontWeight = FontWeight(600),
                                            letterSpacing = 1.2.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.Text(
                                    ch.name, color = pal.label, fontSize = 12.sp,
                                    fontWeight = FontWeight(600), maxLines = 1,
                                    lineHeight = 17.sp,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // 网页边距折叠:max(频道区 mb 28, 新歌区 mt 30) = 30
                    Spacer(Modifier.height(30.dp))
                }
                // —— 新歌首发 ——
                item(key = "nsHead") {
                    androidx.compose.material3.Text(
                        "新歌首发", color = pal.label, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.66).sp,
                        lineHeight = 32.sp,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                }
                item(key = "nsGrid") {
                    if (st.nsMsg.isNotEmpty() && st.nsCards.isEmpty()) {
                        androidx.compose.material3.Text(
                            st.nsMsg, color = pal.label3, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else if (st.nsCards.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            var base = 0
                            st.nsCards.forEach { rows ->
                                val myBase = base
                                base += rows.size
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(pal.nsBg)
                                        .border(1.dp, pal.nsBorder, RoundedCornerShape(14.dp))
                                ) {
                                    rows.forEachIndexed { ri, row ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onAction("ns", (myBase + ri).toString())
                                                }
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HomeCover(
                                                row.cover, Modifier.size(52.dp), 8.dp,
                                                pal.surface
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                NsNameText(row.name, pal.label)
                                                Spacer(Modifier.height(4.dp))
                                                androidx.compose.material3.Text(
                                                    row.sub, color = pal.label2,
                                                    fontSize = 12.sp, maxLines = 1,
                                                    lineHeight = 15.sp,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (st.nsCards.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 热门歌曲 ——
                item(key = "hotHead") {
                    SecHead("热门歌曲", null, pal, null)
                }
                item(key = "hotList") {
                    Column {
                        st.hot.forEachIndexed { i, s ->
                            HotRow(s, pal, i == st.hot.lastIndex,
                                onClick = { onAction("hot", i.toString()) },
                                onLike = { onAction("hotLike", i.toString()) })
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                // —— 精品榜单 ——
                item(key = "rankHead") {
                    SecHead("精品榜单", "换一批", pal) { onAction("rankRefresh", "") }
                }
                item(key = "rankGrid") {
                    if (st.rankMsg.isNotEmpty() && st.ranks.isEmpty()) {
                        androidx.compose.material3.Text(
                            st.rankMsg, color = pal.label3, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        Box(Modifier.padding(horizontal = 2.dp)) {
                            Grid3(st.ranks.size) { i ->
                                val c = st.ranks[i]
                                Column(
                                    Modifier.homePress {
                                        onAction("rank", i.toString())
                                    }
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                    ) {
                                        HomeCover(
                                            c.cover, Modifier.fillMaxSize(), 14.dp,
                                            pal.surface2
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    Brush.verticalGradient(
                                                        0.58f to Color.Transparent,
                                                        1f to Color.Black.copy(alpha = 0.18f)
                                                    )
                                                )
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    androidx.compose.material3.Text(
                                        c.name, color = pal.label, fontSize = 12.sp,
                                        fontWeight = FontWeight(600), maxLines = 1,
                                        lineHeight = 17.sp,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid3(count: Int, cell: @Composable (Int) -> Unit) {
    Column {
        var i = 0
        while (i < count) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                for (k in 0 until 3) {
                    Box(Modifier.weight(1f)) {
                        if (i + k < count) cell(i + k)
                    }
                }
            }
            if (i + 3 < count) Spacer(Modifier.height(12.dp))
            i += 3
        }
    }
}

// 3.86.1 新歌名跑马灯：照网页 newSongMarquee——溢出才滚、双份复制无缝循环、
// 约 30px/秒、时长 max(5s, (文宽+60)/30)
@Composable
private fun NsNameText(name: String, color: Color) {
    val style = TextStyle(color = color, fontSize = 14.sp, lineHeight = 17.5.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val boxW = constraints.maxWidth
        val textW = remember(name, boxW) {
            try {
                measurer.measure(name, style).size.width
            } catch (e: Exception) {
                0
            }
        }
        if (textW <= boxW || textW == 0) {
            androidx.compose.material3.Text(
                name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        } else {
            val cyclePx = textW + with(density) { 30.dp.toPx() }
            val durMs = maxOf(
                5000,
                ((textW + with(density) { 60.dp.toPx() }) /
                    with(density) { 30.dp.toPx() } * 1000).toInt()
            )
            val anim = remember(name) { Animatable(0f) }
            LaunchedEffect(name, textW) {
                while (true) {
                    anim.snapTo(0f)
                    anim.animateTo(-cyclePx, tween(durMs, easing = LinearEasing))
                }
            }
            Row(
                Modifier
                    .wrapContentWidth(unbounded = true)
                    .offset { IntOffset(anim.value.roundToInt(), 0) }
            ) {
                val wDp = with(density) { textW.toDp() }
                androidx.compose.material3.Text(
                    name, style = style, maxLines = 1, modifier = Modifier.width(wDp)
                )
                Spacer(Modifier.width(30.dp))
                androidx.compose.material3.Text(
                    name, style = style, maxLines = 1, modifier = Modifier.width(wDp)
                )
                Spacer(Modifier.width(30.dp))
            }
        }
    }
}

@Composable
private fun ChTab(text: String, on: Boolean, pal: HomePal, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (on) {
                    if (pal.bg == Color.Black) Color(0xFF5A5A5E) else Color.White
                } else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        androidx.compose.material3.Text(
            text, fontSize = 13.sp, fontWeight = FontWeight(600),
            lineHeight = 19.sp,
            color = if (on) pal.label else pal.label3
        )
    }
}

@Composable
private fun SecHead(
    title: String, linkText: String?, pal: HomePal, onLink: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        androidx.compose.material3.Text(
            title, color = pal.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.66).sp, lineHeight = 32.sp
        )
        if (linkText != null && onLink != null) {
            androidx.compose.material3.Text(
                linkText, color = NativeDockStore.accent, fontSize = 17.sp,
                fontWeight = FontWeight(600),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLink)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun DuoCard(
    duo: HomeDuo, kicker: String, barColor: Color, pal: HomePal,
    modifier: Modifier, onClick: () -> Unit
) {
    Box(
        modifier
            .height(176.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pal.bg == Color.Black) Color(0xFF2C2C2E) else Color(0xFFE8E8ED))
            .homePress { onClick() }
    ) {
        HomeCover(duo.cover, Modifier.fillMaxSize(), 0.dp, Color.Transparent)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.34f)
                    )
                )
        )
        Row(
            Modifier
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .border(0.5.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
                .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Text(
                kicker, color = Color.White.copy(alpha = 0.98f), fontSize = 11.sp,
                fontWeight = FontWeight(680)
            )
        }
        androidx.compose.material3.Text(
            duo.title, color = Color.White,
            style = TextStyle(
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), 6f),
                lineHeight = 19.5.sp
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 14.dp, bottom = 30.dp)
        )
        androidx.compose.material3.Text(
            duo.sub, color = Color.White.copy(alpha = 0.84f),
            style = TextStyle(
                fontSize = 12.sp, fontWeight = FontWeight(500),
                shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 5f)
            ),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun HotRow(
    s: HomeHot, pal: HomePal, isLast: Boolean,
    onClick: () -> Unit, onLike: () -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeCover(s.cover, Modifier.size(56.dp), 8.dp, pal.surface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    s.name, color = pal.label, fontSize = 16.sp, maxLines = 1,
                    lineHeight = 20.8.sp,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (s.src.isNotEmpty()) {
                        val sc = SRC_COLORS[s.srcPlat] ?: NativeDockStore.accent
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(sc.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            androidx.compose.material3.Text(
                                s.src, color = sc, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    androidx.compose.material3.Text(
                        s.sub, color = pal.label2, fontSize = 13.sp, maxLines = 1,
                        lineHeight = 18.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (s.dur.isNotEmpty()) {
                androidx.compose.material3.Text(
                    s.dur, color = pal.label3, fontSize = 12.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier
                        .width(36.dp)
                        .padding(horizontal = 2.dp)
                )
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLike),
                contentAlignment = Alignment.Center
            ) {
                val heartPath = remember { PathParser().parsePathString(HEART_PATH).toPath() }
                Canvas(Modifier.size(20.dp)) {
                    val scale = size.minDimension / 24f
                    val path = Path().apply {
                        addPath(heartPath)
                        transform(androidx.compose.ui.graphics.Matrix().apply {
                            scale(scale, scale, 1f)
                        })
                    }
                    if (s.fav) {
                        drawPath(path, color = Color(0xFFFA2D48))
                    } else {
                        drawPath(path, color = pal.label3, style = Stroke(width = 2.15f * scale))
                    }
                }
            }
        }
        if (!isLast) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(pal.separator)
            )
        }
    }
}
